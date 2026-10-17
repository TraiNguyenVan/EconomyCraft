package com.reazip.economycraft.market;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
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
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
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
 * {@code GET /economycraft/v1/market} — the market as JSON, for a consumer that is not an in-process
 * mod. Started with the server and stopped with it.
 *
 * <p>Read-only by construction: one context is registered, it answers {@code GET} and nothing else,
 * and the snapshot it returns has no field that reaches a mutation. The shared secret is required
 * before the route is even resolved, and it is never logged or echoed back.
 *
 * <p>The listener binds in the server's own network namespace. Reaching it is a deployment decision
 * — this class publishes nothing and opens nothing outside the process.
 */
public final class MarketSnapshotServer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String ROUTE = "/economycraft/v1/market";
    private static final String TOKEN_HEADER = "X-EconomyCraft-Token";
    private static final String PLAYER_PARAM = "player";
    private static final Gson GSON = new Gson();
    /** Long enough for a tick to answer, short enough that a wedged server thread frees the worker. */
    private static final long SNAPSHOT_TIMEOUT_MS = 5_000L;

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
            instance.http.setExecutor(instance.workers);
            instance.http.start();
        } catch (IOException e) {
            LOGGER.warn("[EconomyCraft] Could not bind {} on port {}; the market snapshot is not served.",
                    ROUTE, config.httpMarketPort, e);
            instance.workers.shutdownNow();
            return;
        }
        active = instance;
        LOGGER.info("[EconomyCraft] Market snapshot on http://{}:{}{} (shared-secret auth).",
                instance.http.getAddress().getAddress().getHostAddress(),
                instance.http.getAddress().getPort(), ROUTE);
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
        return pending.get(SNAPSHOT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
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
     */
    private static MarketSnapshotModel.PrivateState readPrivateRows(EconomyManager eco,
                                                                    HolderLookup.Provider registries,
                                                                    UUID player) {
        Long balance = eco.getBalance(player, false);
        List<MarketSnapshotModel.Delivery> deliveries = new ArrayList<>();
        for (ItemStack stack : eco.getDeliveries().getDeliveries(player)) {
            deliveries.add(new MarketSnapshotModel.Delivery(encode(registries, stack)));
        }
        return new MarketSnapshotModel.PrivateState(balance == null ? 0L : balance, deliveries);
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