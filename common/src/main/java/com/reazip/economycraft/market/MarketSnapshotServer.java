package com.reazip.economycraft.market;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.JsonOps;
import com.reazip.economycraft.EconomyConfig;
import com.reazip.economycraft.EconomyCraft;
import com.reazip.economycraft.EconomyManager;
import com.reazip.economycraft.auction.AuctionListing;
import com.reazip.economycraft.orders.OrderManager;
import com.reazip.economycraft.orders.OrderRequest;
import com.reazip.economycraft.quests.QuestManager;
import com.reazip.economycraft.tax.TaxPolicy;
import com.reazip.economycraft.tax.TaxScope;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.ItemStackWithSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * {@code GET /economycraft/v1/market} — the market as JSON — and {@code POST /economycraft/v1/command}
 * — one write, {@code claimDelivery} — for a consumer that is not an in-process mod. Started with the
 * server and stopped with it.
 *
 * <p>Two contexts, each answering one verb and refusing every other: the market route is read-only by
 * construction, because the snapshot it returns has no field that reaches a mutation, and the command
 * route accepts exactly the one command named below and refuses every other name. Both demand the
 * shared secret before the route is resolved, and it is never logged or echoed back. Writes never
 * happen on a worker thread: the command hops to the server thread and waits there, so a mutation can
 * only ever run where every other mutation in this mod runs.
 *
 * <p>The listener binds in the server's own network namespace. Reaching it is a deployment decision
 * — this class publishes nothing and opens nothing outside the process.
 */
public final class MarketSnapshotServer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String ROUTE = "/economycraft/v1/market";
    private static final String COMMAND_ROUTE = "/economycraft/v1/command";
    private static final String TOKEN_HEADER = "X-EconomyCraft-Token";
    private static final String PLAYER_PARAM = "player";
    private static final Gson GSON = new Gson();
    /**
     * Only the command responses. {@code Gson} leaves null fields out by default and the command
     * contract spells out {@code {"ok":true,"error":null}}, so this instance serialises nulls. The
     * snapshot keeps the instance above: its omission of {@code you} when the request is unscoped is
     * part of that response's contract, and turning nulls on there would add the field back.
     */
    private static final Gson COMMAND_GSON = new GsonBuilder().serializeNulls().create();
    /** Long enough for a tick to answer, short enough that a wedged server thread frees the worker. */
    private static final long REQUEST_TIMEOUT_MS = 5_000L;
    /** The player-data NBT keys, spelled as the server's own save/load spell them. */
    private static final String INVENTORY_KEY = "Inventory";
    private static final String ENDER_ITEMS_KEY = "EnderItems";
    private static final String SLOT_KEY = "Slot";
    /**
     * Slots 0–35 of a player's inventory. Armour and offhand are 36–40 live, but the server stores
     * them outside the {@code Inventory} list, so an offline player's own list can only ever name
     * these.
     */
    private static final int OFFLINE_INVENTORY_SLOTS = 36;

    private static volatile MarketSnapshotServer active;

    private final MinecraftServer server;
    private final byte[] token;
    private final ExecutorService workers;
    private HttpServer http;

    private MarketSnapshotServer(MinecraftServer server, byte[] token) {
        this.server = server;
        this.token = token;
        this.workers = Executors.newFixedThreadPool(2, runnable -> {
            Thread worker = new Thread(runnable, "EconomyCraft-MarketSnapshot");
            worker.setDaemon(true);
            return worker;
        });
    }

    /**
     * Starts the listener if {@code http_market_enabled} is set. An empty token is treated as "not
     * configured" rather than "no authentication": a market with no secret is a market with no
     * per-player scoping, so the route stays down instead of serving one.
     */
    public static synchronized void start(MinecraftServer server) {
        stop(server);
        EconomyConfig config = EconomyConfig.get();
        if (config == null || !config.httpMarketEnabled) return;
        if (config.httpMarketToken == null || config.httpMarketToken.isBlank()) {
            LOGGER.warn("[EconomyCraft] http_market_enabled is on but http_market_token is empty; "
                    + "{} stays closed.", ROUTE);
            return;
        }

        MarketSnapshotServer instance = new MarketSnapshotServer(server,
                config.httpMarketToken.getBytes(StandardCharsets.UTF_8));
        try {
            instance.http = HttpServer.create(new InetSocketAddress(config.httpMarketPort), 0);
            instance.http.createContext(ROUTE, instance::handle);
            instance.http.createContext(COMMAND_ROUTE, instance::handleCommand);
            instance.http.setExecutor(instance.workers);
            instance.http.start();
        } catch (IOException e) {
            LOGGER.warn("[EconomyCraft] Could not bind {} on port {}; the market snapshot is not served.",
                    ROUTE, config.httpMarketPort, e);
            instance.workers.shutdownNow();
            return;
        }
        active = instance;
        LOGGER.info("[EconomyCraft] Market snapshot on http://{}:{}{} and command endpoint {}{} (shared-secret auth).",
                instance.http.getAddress().getAddress().getHostAddress(),
                instance.http.getAddress().getPort(), ROUTE, COMMAND_ROUTE);
    }

    public static synchronized void stop(MinecraftServer server) {
        MarketSnapshotServer instance = active;
        if (instance == null || instance.server != server) return;
        active = null;
        instance.http.stop(0);
        instance.workers.shutdownNow();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            // One context, one verb. Anything that is not a read has no route to land on.
            if (!"GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 405, "{\"error\":\"method not allowed\"}");
                return;
            }
            if (!ROUTE.equals(exchange.getRequestURI().getPath())) {
                respond(exchange, 404, "{\"error\":\"not found\"}");
                return;
            }
            if (!authorised(exchange)) {
                respond(exchange, 403, "{\"error\":\"forbidden\"}");
                return;
            }
            respond(exchange, 200, GSON.toJson(snapshot(scope(exchange.getRequestURI().getRawQuery()))));
        } catch (Exception e) {
            LOGGER.error("[EconomyCraft] Failed to build a market snapshot", e);
            respond(exchange, 500, "{\"error\":\"snapshot unavailable\"}");
        }
    }

    /**
     * {@code POST /economycraft/v1/command} — a single synchronous write, executed on the server
     * thread and answered with the outcome. The body names the command and its arguments; the
     * {@code ?player=} scope names the account it acts for, exactly as the snapshot does.
     *
     * <p>Every command is validated against the live state before anything is mutated, and the
     * mutation itself is the last step — a failure anywhere above it leaves the world untouched.
     * The response is always {@code {ok:true}} or {@code {ok:false,error:"..."}}; there is no
     * partial success.
     *
     * <p>The verb and path are checked before the token, so a caller that has not worked out the
     * request shape is told so without the secret ever being read. That is also why a non-POST to
     * this path cannot report {@code forbidden}: there is no request here to authorise.
     */
    private void handleCommand(HttpExchange exchange) throws IOException {
        try {
            if (!"POST".equals(exchange.getRequestMethod())) {
                respond(exchange, 405, "{\"error\":\"method not allowed\"}");
                return;
            }
            if (!COMMAND_ROUTE.equals(exchange.getRequestURI().getPath())) {
                respond(exchange, 404, "{\"error\":\"not found\"}");
                return;
            }
            if (!authorised(exchange)) {
                respond(exchange, 403, "{\"error\":\"forbidden\"}");
                return;
            }

            UUID scope = scope(exchange.getRequestURI().getRawQuery());
            if (scope == null) {
                respond(exchange, 400, COMMAND_GSON.toJson(
                        new CommandResult(false, "missing or invalid ?player= scope")));
                return;
            }

            JsonObject body = commandBody(exchange);
            if (body == null) {
                respond(exchange, 400, COMMAND_GSON.toJson(
                        new CommandResult(false, "body must be JSON with a cmd field")));
                return;
            }
            String cmd = body.get("cmd").getAsString();

            CompletableFuture<CommandResult> pending = new CompletableFuture<>();
            server.execute(() -> {
                try {
                    pending.complete(dispatch(scope, cmd, body));
                } catch (Throwable t) {
                    pending.complete(new CommandResult(false, "internal error: " + t));
                }
            });
            CommandResult result = pending.get(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            respond(exchange, result.ok() ? 200 : 400, COMMAND_GSON.toJson(result));
        } catch (Exception e) {
            LOGGER.error("[EconomyCraft] Command failed", e);
            respond(exchange, 500, COMMAND_GSON.toJson(new CommandResult(false, "command unavailable")));
        }
    }

    /**
     * The request body, or null when it is not a JSON object carrying a {@code cmd}. A body that is
     * not JSON at all is refused with the same answer as one that is JSON but says nothing: to the
     * caller both are the same mistake, and neither is worth a different reason string.
     */
    @Nullable
    private static JsonObject commandBody(HttpExchange exchange) throws IOException {
        try {
            JsonObject json = GSON.fromJson(
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8),
                    JsonObject.class);
            if (json == null || !json.has("cmd") || !json.get("cmd").isJsonPrimitive()) return null;
            return json;
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    /** Runs one command on the server thread. Returns the outcome; throws nothing. */
    private CommandResult dispatch(UUID scope, String cmd, JsonObject json) {
        EconomyManager eco = EconomyCraft.getManager(server);
        eco.requireServerThread();
        return switch (cmd) {
            case "claimDelivery" -> claimDelivery(eco, scope, json);
            default -> new CommandResult(false, "unknown command: " + cmd);
        };
    }

    /**
     * Moves one delivery into the player's inventory — all of it or none of it — and drops it from
     * the ledger only once it is somewhere safe. A refused claim leaves both stores exactly as they
     * were, because a caller cannot tell a duplicate from a delivery it already had.
     */
    private CommandResult claimDelivery(EconomyManager eco, UUID player, JsonObject json) {
        JsonElement requested = json.get("index");
        if (requested == null || !requested.isJsonPrimitive()) {
            return new CommandResult(false, "claimDelivery requires an integer index");
        }
        JsonPrimitive primitive = requested.getAsJsonPrimitive();
        int index;
        try {
            if (!primitive.isNumber()) throw new NumberFormatException(primitive.toString());
            index = primitive.getAsBigDecimal().intValueExact();
        } catch (NumberFormatException | ArithmeticException notAnIndex) {
            return new CommandResult(false, "index must be an integer");
        }

        // The ledger's own stack object, addressed by position — the same ordering the snapshot
        // publishes, so the index resolves to the item the caller saw. Nothing here is allowed to
        // mutate it: DeliveryLedger.remove() matches by identity (ItemStack does not override
        // equals), so handing the inventory a copy is what keeps this the object that gets removed,
        // and two identical deliveries can never take each other's place.
        List<ItemStack> deliveries = eco.getDeliveries().getDeliveries(player);
        if (index < 0 || index >= deliveries.size()) {
            return new CommandResult(false, "no delivery at index " + index);
        }
        ItemStack delivered = deliveries.get(index);

        ServerPlayer online = server.getPlayerList().getPlayer(player);
        boolean moved = online != null
                ? moveToOnlineInventory(online, delivered)
                : addToOfflineInventory(player, delivered);
        if (!moved) return new CommandResult(false, "inventory full");

        // Only now that the item is somewhere safe do we drop it from the delivery ledger.
        eco.getDeliveries().removeDelivery(player, delivered);
        return new CommandResult(true, null);
    }

    /**
     * Puts a whole stack into a live player's inventory, or takes back everything it managed to place.
     *
     * <p>{@code Inventory.add} takes what fits and leaves the rest in the stack it was handed, so a
     * nearly-full inventory absorbs part of a delivery. That partial state must not survive a
     * refusal: the ledger entry stays put, so anything the inventory kept would be handed out a
     * second time by the next claim. What was placed is removed again before reporting failure,
     * leaving the inventory as it was.
     */
    private static boolean moveToOnlineInventory(ServerPlayer player, ItemStack delivered) {
        Inventory inventory = player.getInventory();
        ItemStack toGive = delivered.copy();
        inventory.add(toGive);

        // Counted rather than asked: add() reports success even when it placed nothing, for a player
        // with infinite materials whose inventory is full — it simply discards the stack. Only "the
        // whole stack is gone from the copy" means the whole stack is in the inventory.
        int placed = delivered.getCount() - toGive.getCount();
        if (placed == delivered.getCount()) return true;
        if (placed > 0) undoAdd(inventory, toGive, placed);
        return false;
    }

    /**
     * Removes up to {@code placed} items of the same item and components that {@code add} just put in
     * the inventory. Only matching stacks are touched, so the rest of the inventory is left alone,
     * and exactly as many items come back out as went in.
     */
    private static void undoAdd(Inventory inventory, ItemStack given, int placed) {
        for (int i = 0; i < inventory.getContainerSize() && placed > 0; i++) {
            ItemStack slot = inventory.getItem(i);
            if (slot.isEmpty() || !ItemStack.isSameItemSameComponents(slot, given)) continue;
            int take = Math.min(placed, slot.getCount());
            slot.shrink(take);
            placed -= take;
        }
        if (placed > 0) {
            // add() merged into a stack this could not find again; the inventory now holds items the
            // ledger still owes. Nothing here can fix that, but it must not pass unrecorded.
            LOGGER.error("[EconomyCraft] {} items of a refused claim could not be taken back out of an inventory",
                    placed);
        }
    }

    /**
     * Appends one whole stack to an offline player's inventory, in the same player-data NBT the
     * server writes when they log out. The file is only rewritten once the stack has a slot to go in,
     * and it is written through a temp file and an atomic rename, so a crash mid-write cannot leave a
     * truncated player behind and a refusal leaves the file as it was.
     *
     * <p>An online player is never written to: their authoritative inventory is in memory, so a write
     * here would be silently overwritten by their next save and the item would vanish with nothing to
     * report it. The caller resolved this player as offline on this same server-thread task — a login
     * is server-thread work too, so it cannot land in between — and this re-checks anyway, because the
     * cost of being wrong is somebody's inventory.
     */
    private boolean addToOfflineInventory(UUID player, ItemStack stack) {
        if (server.getPlayerList().getPlayer(player) != null) return false;
        if (stack.isEmpty() || stack.getCount() > stack.getMaxStackSize()) return false;

        Path file = playerDataFile(player);
        if (!Files.isRegularFile(file)) return false;
        try {
            CompoundTag tag = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            Tag stored = tag.get(INVENTORY_KEY);
            if (stored != null && !(stored instanceof ListTag)) return false;
            ListTag inventory = tag.getListOrEmpty(INVENTORY_KEY);

            // The server writes only occupied slots, so a free slot is an index no entry claims. The
            // existing entries are left exactly as they are and the new one is appended: an entry this
            // build cannot read is still the player's data, and rewriting it would throw it away.
            boolean[] occupied = new boolean[OFFLINE_INVENTORY_SLOTS];
            for (int i = 0; i < inventory.size(); i++) {
                int slot = inventory.getCompoundOrEmpty(i).getByteOr(SLOT_KEY, (byte) -1) & 0xFF;
                if (slot < occupied.length) occupied[slot] = true;
            }
            int free = -1;
            for (int i = 0; i < occupied.length && free < 0; i++) {
                if (!occupied[i]) free = i;
            }
            if (free < 0) return false;

            // Encoded by the server's own slot+stack codec, so the entry is byte-for-byte the shape
            // the server writes and reads: a Slot plus the item's fields beside it.
            Tag encoded = ItemStackWithSlot.CODEC
                    .encodeStart(nbtOps(server.registryAccess()), new ItemStackWithSlot(free, stack))
                    .result()
                    .orElse(null);
            if (!(encoded instanceof CompoundTag entry)) return false;

            inventory.add(entry);
            tag.put(INVENTORY_KEY, inventory);

            Path temp = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
            try {
                NbtIo.writeCompressed(tag, temp);
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temp);
            }
            return true;
        } catch (Exception e) {
            LOGGER.error("[EconomyCraft] Could not add an item to the offline inventory of {} ({}): {}",
                    player, file, e.toString());
            return false;
        }
    }

    /** Where the server keeps a player's data, named as the server itself names it. */
    private Path playerDataFile(UUID player) {
        return server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(player + ".dat");
    }

    /** NBT with the registries in reach, so components resolve by id exactly as the server's loader does. */
    private static RegistryOps<Tag> nbtOps(HolderLookup.Provider registries) {
        return RegistryOps.create(NbtOps.INSTANCE, registries);
    }

    /** The one shape every command returns. */
    private record CommandResult(boolean ok, @Nullable String error) {}

    /**
     * Compares in constant time so a wrong token cannot be discovered one byte at a time. The token
     * itself never reaches a log line or a response body — a rejection says only that it was refused.
     */
    private boolean authorised(HttpExchange exchange) {
        String presented = exchange.getRequestHeaders().getFirst(TOKEN_HEADER);
        if (presented == null) return false;
        return MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8), token);
    }

    /** The {@code ?player=} scope, or null when absent, unparseable or simply not asked for. */
    @Nullable
    private static UUID scope(@Nullable String rawQuery) {
        if (rawQuery == null) return null;
        for (String pair : rawQuery.split("&")) {
            int equals = pair.indexOf('=');
            if (equals < 0 || !PLAYER_PARAM.equals(pair.substring(0, equals))) continue;
            try {
                return UUID.fromString(URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8));
            } catch (IllegalArgumentException malformed) {
                return null;
            }
        }
        return null;
    }

    /**
     * Everything below reads live server state, so the whole assembly hops onto the server thread and
     * waits there — the same marshal-and-await shape the profile lookups already use. Only this
     * method's own frame touches a store, which is why nothing else needs to re-check.
     */
    private MarketSnapshotModel.Snapshot snapshot(@Nullable UUID scope) throws Exception {
        CompletableFuture<MarketSnapshotModel.Snapshot> pending = new CompletableFuture<>();
        server.execute(() -> {
            try {
                pending.complete(collect(scope));
            } catch (Throwable t) {
                pending.completeExceptionally(t);
            }
        });
        return pending.get(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    private MarketSnapshotModel.Snapshot collect(@Nullable UUID scope) {
        EconomyManager eco = EconomyCraft.getManager(server);
        eco.requireServerThread();

        // Encoding here rather than on the worker thread is the deep copy: each stack becomes a
        // detached JSON tree before it leaves the server thread, so the live mutable reference the
        // stores hand out cannot be observed changing underneath the response.
        HolderLookup.Provider registries = server.registryAccess();

        List<MarketSnapshotModel.Auction> auctions = new ArrayList<>();
        for (AuctionListing listing : eco.getAuctions().getListings()) {
            auctions.add(new MarketSnapshotModel.Auction(listing.id, listing.seller,
                    name(eco, listing.seller), listing.price, listing.createdAt, listing.expiresAt,
                    listing.description, false, encode(registries, listing.item)));
        }

        List<MarketSnapshotModel.Order> orders = new ArrayList<>();
        for (OrderRequest request : eco.getOrders().getRequests()) {
            orders.add(new MarketSnapshotModel.Order(request.id, request.requester,
                    name(eco, request.requester), request.price, request.amount, request.escrow,
                    OrderManager.rewardPerItem(request.price, request.amount),
                    request.createdAt, request.expiresAt, request.description, false,
                    encode(registries, request.item)));
        }

        List<MarketSnapshotModel.Player> players = new ArrayList<>();
        for (Map.Entry<UUID, String> entry : eco.getPlayerNames().entrySet()) {
            players.add(new MarketSnapshotModel.Player(entry.getKey(), entry.getValue()));
        }

        // Resolved through TaxPolicy rather than read off EconomyConfig, so this endpoint is a
        // consumer of the tax package like every other caller and a retune of the rate reaches it
        // without a second source to keep in step. A base of 1 makes the quote's rate the configured
        // rate; the value is the published number only, so no per-player discount or exemption is
        // folded in and none can leak.
        double auctionRate = TaxPolicy.resolve(TaxScope.TRANSACTION_AUCTION_BUY, 1L).rate();

        return MarketSnapshotModel.build(System.currentTimeMillis(), eco.getDynamicPriceMultiplier(),
                auctionRate, QuestManager.BOT_UUID, auctions, orders, players, scope,
                player -> readPrivateRows(eco, registries, player));
    }

    /**
     * The requested player's own money and goods. The balance is read with
     * {@code newBalanceIfNonExistent = false} on purpose: this is a read endpoint, and conjuring an
     * account for a uuid that has none would write to the database on somebody's behalf.
     *
     * <p>Inventory and ender chest come from the live {@link ServerPlayer} when the player is online,
     * and from the server's player-data NBT when they are offline. The two never mix: an online
     * player's authoritative state is the in-memory inventory the server saves on logout, so reading
     * their file would publish stale contents, and writing it — as a claim does — would be
     * overwritten by that save.
     */
    private MarketSnapshotModel.PrivateState readPrivateRows(EconomyManager eco,
                                                             HolderLookup.Provider registries,
                                                             UUID player) {
        Long balance = eco.getBalance(player, false);
        List<MarketSnapshotModel.Delivery> deliveries = new ArrayList<>();
        for (ItemStack stack : eco.getDeliveries().getDeliveries(player)) {
            deliveries.add(new MarketSnapshotModel.Delivery(encode(registries, stack)));
        }

        List<MarketSnapshotModel.Slot> inventory = new ArrayList<>();
        List<MarketSnapshotModel.Slot> enderChest = new ArrayList<>();
        ServerPlayer online = server.getPlayerList().getPlayer(player);
        if (online != null) {
            collectSlots(online.getInventory(), inventory, registries);
            collectSlots(online.getEnderChestInventory(), enderChest, registries);
        } else {
            readOfflineInventory(registries, player, inventory, enderChest);
        }
        return new MarketSnapshotModel.PrivateState(balance == null ? 0L : balance, deliveries,
                inventory, enderChest);
    }

    /** Appends every non-empty slot of a live container, each naming its own container index. */
    private static void collectSlots(Container container, List<MarketSnapshotModel.Slot> out,
                                      HolderLookup.Provider registries) {
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (!stack.isEmpty()) out.add(new MarketSnapshotModel.Slot(i, encode(registries, stack)));
        }
    }

    /**
     * Reads an offline player's inventory and ender chest out of the server's player-data NBT. A
     * missing file is not an error — it means the player has never joined this world, so both lists
     * stay empty.
     */
    private void readOfflineInventory(HolderLookup.Provider registries, UUID player,
                                      List<MarketSnapshotModel.Slot> inventory,
                                      List<MarketSnapshotModel.Slot> enderChest) {
        Path file = playerDataFile(player);
        if (!Files.isRegularFile(file)) return;
        try {
            CompoundTag tag = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            readSlots(registries, tag.getListOrEmpty(INVENTORY_KEY), inventory);
            readSlots(registries, tag.getListOrEmpty(ENDER_ITEMS_KEY), enderChest);
        } catch (Exception e) {
            LOGGER.warn("[EconomyCraft] Could not read the offline inventory for {} from {}: {}",
                    player, file, e.toString());
        }
    }

    /**
     * Parses the entries of one per-player container — each a compound of a {@code Slot} plus the item
     * — through the server's own {@code ItemStackWithSlot} codec, so the index and the stack are read
     * by the very code that writes them.
     *
     * <p>No special handling of the extra {@code Slot} key is needed, and that is not an assumption:
     * the codec feeds the whole entry, {@code Slot} and all, to {@code ItemStack.MAP_CODEC}, so the
     * server could not load a player's inventory at all if that key were rejected. An entry that does
     * not decode costs its own row and nothing else.
     */
    private static void readSlots(HolderLookup.Provider registries, ListTag slots,
                                  List<MarketSnapshotModel.Slot> out) {
        for (int i = 0; i < slots.size(); i++) {
            CompoundTag entry = slots.getCompoundOrEmpty(i);
            if (entry.isEmpty()) continue;
            ItemStackWithSlot parsed = ItemStackWithSlot.CODEC
                    .parse(nbtOps(registries), entry)
                    .result()
                    .orElse(null);
            if (parsed == null || parsed.stack().isEmpty()) continue;
            out.add(new MarketSnapshotModel.Slot(parsed.slot(), encode(registries, parsed.stack())));
        }
    }

    private static JsonElement encode(HolderLookup.Provider registries, @Nullable ItemStack stack) {
        if (stack == null) return new JsonObject();
        return ItemStack.CODEC.encodeStart(RegistryOps.create(JsonOps.INSTANCE, registries), stack)
                .result()
                .orElseGet(JsonObject::new);
    }

    /** Unknown names read as empty rather than absent: the uuid beside them is already authoritative. */
    private static String name(EconomyManager eco, @Nullable UUID player) {
        String resolved = player == null ? null : eco.getBestName(player);
        return resolved == null ? "" : resolved;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}