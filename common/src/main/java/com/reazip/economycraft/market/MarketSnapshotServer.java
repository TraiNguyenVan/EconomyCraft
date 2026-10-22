package com.reazip.economycraft.market;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.JsonOps;
import com.reazip.economycraft.EconomyConfig;
import com.reazip.economycraft.EconomyCraft;
import com.reazip.economycraft.EconomyManager;
import com.reazip.economycraft.auction.AuctionListing;
import com.reazip.economycraft.auction.AuctionManager;
import com.reazip.economycraft.auction.AuctionTrade;
import com.reazip.economycraft.orders.OrderFulfillment;
import com.reazip.economycraft.orders.OrderManager;
import com.reazip.economycraft.orders.OrderRequest;
import com.reazip.economycraft.util.DeliveryLedger;
import com.reazip.economycraft.quests.QuestManager;
import com.reazip.economycraft.tax.TaxPolicy;
import com.reazip.economycraft.tax.TaxScope;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
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
 * — the write commands the snapshot advertises in {@code commands} — for a consumer that is not an
 * in-process mod. Started with the server and stopped with it.
 *
 * <p>Two contexts, each answering one verb and refusing every other: the market route is read-only by
 * construction, because the snapshot it returns has no field that reaches a mutation, and the command
 * route accepts exactly the names in {@link MarketSnapshotModel#COMMANDS} and refuses every other
 * name. Both demand the shared secret before the route is resolved, and it is never logged or echoed
 * back. Writes never happen on a worker thread: the command hops to the server thread and waits
 * there, so a mutation can only ever run where every other mutation in this mod runs.
 *
 * <p>What this class keeps for itself is only the part that needs the server: reading the live state
 * a command is validated against and running the mutation. Parsing and validation are
 * {@link MarketCommandArgs}, offline player data is {@link OfflineInventory}, and the one ordering
 * that must not lose an item is {@link ListingTransaction}.
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
    private static final int MAX_COMMAND_BODY_BYTES = 64 * 1024;
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
        LOGGER.info("[EconomyCraft] Market snapshot on http://{}:{}{} and command endpoint {} (shared-secret auth).",
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
     * {@code POST /economycraft/v1/command} — one synchronous write per request, executed on the
     * server thread and answered with the outcome. The body names the command and its arguments; the
     * {@code ?player=} scope names the account it acts for, exactly as the snapshot does.
     *
     * <p>Every command is validated against the live state before anything is mutated, and the
     * mutation itself is the last step — a failure anywhere above it leaves the world untouched.
     * The response is always {@code {ok:true,...}} or {@code {ok:false,error:"..."}}; there is no
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

            JsonObject body;
            try {
                body = commandBody(exchange);
            } catch (RequestBodyTooLargeException tooLarge) {
                respond(exchange, 413, "{\"error\":\"request body too large\"}");
                return;
            }
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
                    // Logged here, never returned: an exception's own text can carry a filesystem
                    // path or a class name, and that belongs in the server log, not in a reply to
                    // whoever holds the shared secret.
                    LOGGER.error("[EconomyCraft] Command '{}' failed for {}", cmd, scope, t);
                    pending.complete(new CommandResult(false, "command failed"));
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
            byte[] bytes = readBounded(exchange.getRequestBody());
            JsonObject json = GSON.fromJson(
                    new String(bytes, StandardCharsets.UTF_8), JsonObject.class);
            if (json == null || !json.has("cmd") || !json.get("cmd").isJsonPrimitive()) return null;
            return json;
        } catch (RequestBodyTooLargeException tooLarge) {
            throw tooLarge;
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    private static byte[] readBounded(InputStream body) throws IOException {
        byte[] bytes = body.readNBytes(MAX_COMMAND_BODY_BYTES + 1);
        if (bytes.length > MAX_COMMAND_BODY_BYTES) throw new RequestBodyTooLargeException();
        return bytes;
    }

    private static final class RequestBodyTooLargeException extends IOException {}

    /** Runs one command on the server thread. Returns the outcome; throws nothing. */
    private CommandResult dispatch(UUID scope, String cmd, JsonObject json) {
        EconomyManager eco = EconomyCraft.getManager(server);
        eco.requireServerThread();
        // The names here and MarketSnapshotModel.COMMANDS are one advertisement: a command this
        // switch serves but the snapshot does not name is invisible to a consumer, and one the
        // snapshot names but this switch does not serve is a broken promise. Change both together.
        return switch (cmd) {
            case "claimDelivery" -> claimDelivery(eco, scope, json);
            case "listItem" -> listItem(eco, scope, json);
            case "buyListing" -> buyListing(eco, scope, json);
            case "cancelListing" -> cancelListing(eco, scope, json);
            case "placeOrder" -> placeOrder(eco, scope, json);
            case "cancelOrder" -> cancelOrder(eco, scope, json);
            case "deliverOrder" -> deliverOrder(eco, scope, json);
            default -> new CommandResult(false, "unknown command: " + cmd);
        };
    }

    /**
     * Moves one delivery into the player's inventory — all of it or none of it — and drops it from
     * the ledger only once it is somewhere safe. A refused claim leaves both stores exactly as they
     * were, because a caller cannot tell a duplicate from a delivery it already had.
     */
    private CommandResult claimDelivery(EconomyManager eco, UUID player, JsonObject json) {
        MarketCommandArgs.Parsed<MarketCommandArgs.ClaimDelivery> parsed = MarketCommandArgs.claimDelivery(json);
        if (!parsed.ok()) return new CommandResult(false, parsed.error());
        UUID deliveryId = parsed.value().deliveryId();

        // A stable id keeps retries tied to this delivery even if an earlier timed-out request ran
        // after the caller received its 500 response. A list index could silently select the next row.
        DeliveryLedger.Entry delivery = eco.getDeliveries().getDeliveryEntry(player, deliveryId);
        if (delivery == null) {
            return new CommandResult(false, "no delivery with id " + deliveryId);
        }
        ItemStack delivered = delivery.stack();

        ServerPlayer online = server.getPlayerList().getPlayer(player);
        boolean moved = online != null
                ? moveToOnlineInventory(online, delivered)
                : addToOfflineInventory(player, delivered);
        if (!moved) return new CommandResult(false, "inventory full");

        // Only now that the item is somewhere safe do we drop it from the delivery ledger.
        eco.getDeliveries().removeDelivery(player, deliveryId);
        return new CommandResult(true, null);
    }

    /**
     * Lists one stack out of the caller's own inventory and answers with the new listing's id.
     *
     * <p>The order is the whole safety argument, and it is not the obvious one. There is no
     * transaction spanning a player's inventory and the auction store, so:
     * <ol>
     *   <li>the slot is re-read <em>here</em>, on the server thread, from whichever inventory is
     *       authoritative, and the range, count, price, per-player limit and item fingerprint are all
     *       checked against that read — never against the page the request was built from;</li>
     *   <li>only then is the item taken out of the inventory;</li>
     *   <li>and only then is the listing created, with {@link ListingTransaction} owning what happens
     *       if that write fails.</li>
     * </ol>
     * The invariant across all three: <strong>the item is always in the inventory, in a listing, or
     * in the deliveries mailbox</strong> — never lost, never duplicated. A refusal anywhere above step
     * 2 leaves the inventory exactly as it was, which is also what makes a double-submit harmless: the
     * second request finds the stack gone or reduced and refuses.
     */
    private CommandResult listItem(EconomyManager eco, UUID player, JsonObject json) {
        if (!EconomyConfig.get().auctionEnabled) {
            return new CommandResult(false, "the auction house is disabled");
        }

        ServerPlayer online = server.getPlayerList().getPlayer(player);
        OfflineInventory offline = offlineInventory();

        // (a) Everything the request claims is checked against the slot's live contents. The held count
        // the parser caps `count` with is that same live count.
        MarketCommandArgs.Parsed<MarketCommandArgs.ListItem> parsed =
                MarketCommandArgs.listItem(json, slot -> liveStack(online, offline, player, slot).getCount());
        if (!parsed.ok()) return new CommandResult(false, parsed.error());
        MarketCommandArgs.ListItem args = parsed.value();

        ItemStack expected = ItemStack.CODEC
                .parse(RegistryOps.create(JsonOps.INSTANCE, server.registryAccess()), args.item())
                .result()
                .orElse(ItemStack.EMPTY);
        ItemStack live = liveStack(online, offline, player, args.slot());
        if (expected.isEmpty() || live.isEmpty() || live.getCount() != expected.getCount()
                || !ItemStack.isSameItemSameComponents(live, expected)) {
            // The encoded stack is also the request's snapshot of how many were in the slot when it
            // was built. Requiring that count to remain unchanged makes a delayed retry of a partial
            // listing refuse after the first request has reduced the stack. `args.count()` may still
            // be smaller than the held count. An unreadable `item` is the same refusal, because a
            // fingerprint that cannot be computed is not one that matched.
            return new CommandResult(false, "the slot no longer holds that item");
        }

        AuctionManager auctions = eco.getAuctions();
        if (auctions.hasReachedLimit(player)) {
            return new CommandResult(false, "you have reached your limit of "
                    + auctions.getEffectiveLimit(player) + " active listing(s)");
        }

        // (b) Take the item. It now belongs to nobody, so every exit from here must place it.
        ItemStack taken;
        if (online != null) {
            ItemStack slotStack = online.getInventory().getItem(args.slot());
            taken = slotStack.copyWithCount(args.count());
            slotStack.shrink(args.count());
            // shrink() leaves a zero-count stack behind; clearing it keeps the slot as visibly empty
            // to every other reader as it is to the inventory.
            if (slotStack.isEmpty()) online.getInventory().setItem(args.slot(), ItemStack.EMPTY);
        } else {
            taken = live.copyWithCount(args.count());
            if (!offline.removeFromSlot(player, args.slot(), args.count())) {
                return new CommandResult(false, "that item could not be taken out of your inventory");
            }
        }

        // (c)/(d) Create the listing, or compensate into the mailbox. The item is never left nowhere.
        ListingTransaction.Result listed = ListingTransaction.list(auctions, player, taken, args.price(),
                args.description(), EconomyConfig.get().auctionExpirationHours);
        if (!listed.ok()) {
            return new CommandResult(false, listed.error(), null, 0L, 0L, listed.stored());
        }
        return new CommandResult(true, null, listed.id(), 0L, 0L, false);
    }

    /**
     * Buys someone else's listing for the caller. Delegates to the one purchase implementation, so
     * the web route cannot diverge from the menu on tax, the buyback branch or the seller's
     * notification. `total` is what the caller was charged, including the buyer's tax.
     */
    private CommandResult buyListing(EconomyManager eco, UUID buyer, JsonObject json) {
        if (!EconomyConfig.get().auctionEnabled) {
            return new CommandResult(false, "the auction house is disabled");
        }
        MarketCommandArgs.Parsed<Integer> parsed = MarketCommandArgs.listingId(json, "buyListing");
        if (!parsed.ok()) return new CommandResult(false, parsed.error());
        int id = parsed.value();

        AuctionTrade.PurchaseResult result = AuctionTrade.purchaseFor(eco, buyer, id);
        return switch (result.status()) {
            case OK -> new CommandResult(true, null, id, result.totalPaid(), 0L, result.stored());
            case OWN_LISTING -> new CommandResult(false, "you cannot buy your own listing");
            case CANT_AFFORD -> new CommandResult(false, "not enough balance");
            case SELLER_CANT_RECEIVE -> new CommandResult(false, "seller cannot receive this payment");
            case LISTING_GONE -> new CommandResult(false, "listing no longer available");
        };
    }

    /**
     * Un-lists one of the caller's own listings, returning the item. Delegates to the one
     * cancellation implementation; no money is involved either way, and an offline seller gets the
     * stack in their deliveries mailbox instead of an inventory.
     */
    private CommandResult cancelListing(EconomyManager eco, UUID seller, JsonObject json) {
        if (!EconomyConfig.get().auctionEnabled) {
            return new CommandResult(false, "the auction house is disabled");
        }
        MarketCommandArgs.Parsed<Integer> parsed = MarketCommandArgs.listingId(json, "cancelListing");
        if (!parsed.ok()) return new CommandResult(false, parsed.error());
        int id = parsed.value();

        AuctionTrade.CancelResult result = AuctionTrade.cancelFor(eco.getAuctions(), seller, id);
        return switch (result.status()) {
            case OK -> new CommandResult(true, null, id, 0L, 0L, result.stored());
            case NOT_OWNER -> new CommandResult(false, "you don't own this listing");
            case LISTING_GONE -> new CommandResult(false, "listing no longer available");
        };
    }

    /**
     * Posts a buy-order: escrows {@code price} up front and answers with the new order's id.
     * The item is only a prototype read from the slot — nothing is taken from the inventory.
     */
    private CommandResult placeOrder(EconomyManager eco, UUID player, JsonObject json) {
        if (!EconomyConfig.get().ordersEnabled) {
            return new CommandResult(false, "orders are disabled");
        }
        ServerPlayer online = server.getPlayerList().getPlayer(player);
        OfflineInventory offline = offlineInventory();

        MarketCommandArgs.Parsed<MarketCommandArgs.PlaceOrder> parsed = MarketCommandArgs.placeOrder(json);
        if (!parsed.ok()) return new CommandResult(false, parsed.error());
        MarketCommandArgs.PlaceOrder args = parsed.value();

        ItemStack expected = ItemStack.CODEC
                .parse(RegistryOps.create(JsonOps.INSTANCE, server.registryAccess()), args.item())
                .result()
                .orElse(ItemStack.EMPTY);
        ItemStack live = liveStack(online, offline, player, args.slot());
        if (expected.isEmpty() || live.isEmpty() || !ItemStack.isSameItemSameComponents(live, expected)) {
            return new CommandResult(false, "the slot no longer holds that item");
        }

        OrderManager orders = eco.getOrders();
        if (orders.hasReachedLimit(player)) {
            return new CommandResult(false, "you have reached your limit of "
                    + orders.getEffectiveLimit(player) + " active order request(s)");
        }
        int maxAmount = MarketCommandArgs.MAIN_INVENTORY_SLOTS * live.getMaxStackSize();
        if (args.amount() > maxAmount) {
            return new CommandResult(false, "amount exceeds " + MarketCommandArgs.MAIN_INVENTORY_SLOTS + " stacks (max " + maxAmount + ")");
        }

        ItemStack prototype = live.copyWithCount(1);
        String desc = args.description();
        if (desc != null) {
            desc = desc.replace("\r", "").replace("\n", "").trim();
            if (desc.isEmpty()) desc = null;
            else if (desc.length() > 100) desc = desc.substring(0, 100);
        }

        OrderRequest request = OrderFulfillment.createEscrowedRequest(eco, player, prototype, args.amount(),
                args.price(), desc);
        if (request == null) {
            return new CommandResult(false, "you can't afford to reserve " + EconomyCraft.formatMoney(args.price()));
        }
        return new CommandResult(true, null, request.id, 0L, 0L, false);
    }

    /**
     * Cancels one of the caller's own orders and refunds the escrow.
     */
    private CommandResult cancelOrder(EconomyManager eco, UUID player, JsonObject json) {
        if (!EconomyConfig.get().ordersEnabled) {
            return new CommandResult(false, "orders are disabled");
        }
        MarketCommandArgs.Parsed<Integer> parsed = MarketCommandArgs.listingId(json, "cancelOrder");
        if (!parsed.ok()) return new CommandResult(false, parsed.error());
        int id = parsed.value();

        OrderFulfillment.CancelStatus status = OrderFulfillment.cancel(eco, player, id);
        return switch (status) {
            case OK -> new CommandResult(true, null, id, 0L, 0L, false);
            case NOT_OWNER -> new CommandResult(false, "you don't own this order");
            case ORDER_GONE -> new CommandResult(false, "order no longer available");
            case REFUND_FAILED -> new CommandResult(false, "the escrow could not be refunded");
        };
    }

    /**
     * Delivers into someone else's order. Online players only for this cut — an offline fulfiller's
     * items would have to come out of player-data NBT, and that write can fail after the payout.
     */
    private CommandResult deliverOrder(EconomyManager eco, UUID player, JsonObject json) {
        if (!EconomyConfig.get().ordersEnabled) {
            return new CommandResult(false, "orders are disabled");
        }
        ServerPlayer online = server.getPlayerList().getPlayer(player);
        if (online == null) {
            return new CommandResult(false, "you must be online to deliver an order");
        }
        MarketCommandArgs.Parsed<MarketCommandArgs.DeliverOrder> parsed = MarketCommandArgs.deliverOrder(json);
        if (!parsed.ok()) return new CommandResult(false, parsed.error());
        MarketCommandArgs.DeliverOrder args = parsed.value();

        OrderFulfillment.Result result = OrderFulfillment.fulfill(eco, online, args.id(), args.amount());
        CommandResult outcome = switch (result.status()) {
            case OK -> new CommandResult(true, null, args.id(), result.payment(), result.payout(), false);
            case OWN_ORDER -> new CommandResult(false, "you cannot fulfill your own request");
            case ORDER_GONE -> new CommandResult(false, "order no longer available");
            case INVALID_AMOUNT -> new CommandResult(false, "invalid amount");
            case FULL_AMOUNT_REQUIRED -> new CommandResult(false, "this request must be fulfilled all at once.");
            case NOT_ENOUGH_ITEMS -> new CommandResult(false, "you don't have enough of that item");
            case REQUESTER_CANT_PAY -> new CommandResult(false, "requester can't pay");
            case FULFILLER_CANT_RECEIVE -> new CommandResult(false, "your balance is too high to receive this payout");
        };
        if (result.taken() > 0) {
            // The goods could not go back to the seller and are waiting in their mailbox instead:
            // say so, or they would look like they vanished.
            return new CommandResult(false, outcome.error() + "; " + result.taken()
                    + " item(s) are waiting in your deliveries mailbox");
        }
        return outcome;
    }

    /**
     * The stack one slot holds in whichever inventory is authoritative for this player right now, or
     * empty for a slot a command may not address. The live container for an online player, the
     * player-data NBT for an offline one — the same split the snapshot reads through, so a command
     * validates against the very rows it was built from.
     */
    private ItemStack liveStack(@Nullable ServerPlayer online, OfflineInventory offline, UUID player, int slot) {
        if (slot < 0 || slot >= MarketCommandArgs.MAIN_INVENTORY_SLOTS) return ItemStack.EMPTY;
        if (online != null) return online.getInventory().getItem(slot);
        return offline.readSlot(player, slot);
    }

    /** The player-data machinery for this server. Server thread only: it reads the registries. */
    private OfflineInventory offlineInventory() {
        return new OfflineInventory(server.getWorldPath(LevelResource.PLAYER_DATA_DIR), server.registryAccess());
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
     * Appends one whole stack to an offline player's inventory, through {@link OfflineInventory}.
     *
     * <p>An online player is never written to: their authoritative inventory is in memory, so a write
     * here would be silently overwritten by their next save and the item would vanish with nothing to
     * report it. The caller resolved this player as offline on this same server-thread task — a login
     * is server-thread work too, so it cannot land in between — and this re-checks anyway, because the
     * cost of being wrong is somebody's inventory.
     */
    private boolean addToOfflineInventory(UUID player, ItemStack stack) {
        if (server.getPlayerList().getPlayer(player) != null) return false;
        return offlineInventory().add(player, stack);
    }

    /** The one shape every command returns. */
    private record CommandResult(boolean ok, @Nullable String error, @Nullable Integer id, long total,
                                 long paid, boolean stored) {
        /**
         * A refusal, or a success with nothing to report: no id, nothing charged, nothing received
         * and nothing put in the mailbox. The overwhelming majority of answers.
         */
        CommandResult(boolean ok, @Nullable String error) {
            this(ok, error, null, 0L, 0L, false);
        }
    }

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
        OfflineInventory playerData = offlineInventory();

        List<MarketSnapshotModel.Auction> auctions = new ArrayList<>();
        for (AuctionListing listing : eco.getAuctions().getListings()) {
            auctions.add(new MarketSnapshotModel.Auction(listing.id, listing.seller,
                    name(eco, listing.seller), listing.price, listing.createdAt, listing.expiresAt,
                    listing.description, false, OfflineInventory.encode(registries, listing.item)));
        }

        List<MarketSnapshotModel.Order> orders = new ArrayList<>();
        for (OrderRequest request : eco.getOrders().getRequests()) {
            orders.add(new MarketSnapshotModel.Order(request.id, request.requester,
                    name(eco, request.requester), request.price, request.amount, request.escrow,
                    OrderManager.rewardPerItem(request.price, request.amount),
                    request.createdAt, request.expiresAt, request.description, false,
                    OfflineInventory.encode(registries, request.item)));
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
                player -> readPrivateRows(eco, registries, playerData, player));
    }

    /**
     * The requested player's own money and goods. The balance is read with
     * {@code newBalanceIfNonExistent = false} on purpose: this is a read endpoint, and conjuring an
     * account for a uuid that has none would write to the database on somebody's behalf.
     *
     * <p>Inventory and ender chest come from the live {@link ServerPlayer} when the player is online,
     * and from the server's player-data NBT when they are offline. The two never mix: an online
     * player's authoritative state is the in-memory inventory the server saves on logout, so reading
     * their file would publish stale contents, and writing it — as a claim or a listing does — would
     * be overwritten by that save.
     */
    private MarketSnapshotModel.PrivateState readPrivateRows(EconomyManager eco,
                                                             HolderLookup.Provider registries,
                                                             OfflineInventory playerData,
                                                             UUID player) {
        Long balance = eco.getBalance(player, false);
        List<MarketSnapshotModel.Delivery> deliveries = new ArrayList<>();
        for (DeliveryLedger.Entry entry : eco.getDeliveries().getDeliveryEntries(player)) {
            deliveries.add(new MarketSnapshotModel.Delivery(entry.id(), OfflineInventory.encode(registries, entry.stack())));
        }

        List<MarketSnapshotModel.Slot> inventory = new ArrayList<>();
        List<MarketSnapshotModel.Slot> enderChest = new ArrayList<>();
        ServerPlayer online = server.getPlayerList().getPlayer(player);
        if (online != null) {
            playerData.collectSlots(online.getInventory(), inventory);
            playerData.collectSlots(online.getEnderChestInventory(), enderChest);
        } else {
            playerData.read(player, inventory, enderChest);
        }
        return new MarketSnapshotModel.PrivateState(balance == null ? 0L : balance, deliveries,
                inventory, enderChest);
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
