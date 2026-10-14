package com.reazip.economycraft.orders;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.reazip.economycraft.DeliveryManager;
import com.reazip.economycraft.EconomyManager;
import com.reazip.economycraft.NotificationManager;
import com.reazip.economycraft.api.v1.BalanceMutationResult;
import com.reazip.economycraft.api.v1.BalanceMutationStatus;
import com.reazip.economycraft.api.v1.BalanceMutationType;
import com.reazip.economycraft.api.v1.MutationSource;
import com.reazip.economycraft.db.EconomyDatabase;
import com.reazip.economycraft.negotiation.NegotiationStore;
import com.reazip.economycraft.quests.QuestManager;
import com.reazip.economycraft.util.EconomyPaths;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.LevelResource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Crash-consistency of the order escrow lifecycle against a real {@link OrderManager} over a
 * file-backed {@link EconomyDatabase}, with a ledger-backed mock {@link EconomyManager} standing
 * in for the balance engine.
 *
 * <p>The ledger mock reproduces the production commit seam: a <em>successful</em> money mutation
 * persists the balances document and the orders document together — exactly what
 * {@code EconomyManager.save()}'s single SQLite transaction does on every committed mutation —
 * and a rejected mutation persists nothing. The mock captures the orders document both before
 * that commit and as the commit wrote it, which is what a crash at either instant would leave
 * on disk. Every scenario then reopens the database and reloads the store — the same thing the
 * server does on restart — and asserts the persisted balance and the persisted order escrow
 * still agree.
 *
 * <p>The ledger enforces the real engine's two sharp edges — debits fail on insufficient funds,
 * credits fail past {@link EconomyManager#MAX} — so rejection and recovery paths behave here
 * the way they behave in production.
 */
class OrderEscrowConsistencyTest {

    private static final UUID REQUESTER = UUID.randomUUID();
    private static final UUID BOT = QuestManager.BOT_UUID;

    @TempDir
    Path world;

    private MinecraftServer server;
    private EconomyDatabase db;
    private OrderManager orders;
    private EconomyManager eco;
    /** The test ledger: every money movement in these tests lands here. */
    private final Map<UUID, Long> balances = new HashMap<>();

    /** The orders document as it stood before the last successful money mutation committed. */
    private String ordersJsonBeforeCommit;
    /** The orders document as the last successful money mutation's transactional save wrote it. */
    private String ordersJsonAtCommit;

    @BeforeAll
    static void bootstrap() {
        // Same order as StorePersistenceTest: the version has to be detected before anything
        // touches DataFixers, and item components have to be readable before any stack is encoded.
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(VanillaRegistries.createWorldLookup())
                .forEach(components -> components.apply());
    }

    @BeforeEach
    void setUp() throws SQLException {
        server = mock(MinecraftServer.class);
        when(server.getWorldPath(LevelResource.ROOT)).thenReturn(world);
        when(server.registryAccess())
                .thenReturn(RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
        PlayerList players = mock(PlayerList.class);
        when(server.getPlayerList()).thenReturn(players);
        when(players.getPlayers()).thenReturn(List.of());

        db = openDatabase();
        orders = new OrderManager(server, mock(DeliveryManager.class), db);

        balances.clear();
        balances.put(REQUESTER, 100_000L);
        balances.put(BOT, 100_000L);

        eco = mock(EconomyManager.class);
        when(eco.getOrders()).thenReturn(orders);
        when(eco.getServer()).thenReturn(server);
        when(eco.getNotifications()).thenReturn(mock(NotificationManager.class));
        when(eco.getNegotiations()).thenReturn(mock(NegotiationStore.class));

        when(eco.removeMoney(any(UUID.class), anyLong(), any(MutationSource.class), anyString()))
                .thenAnswer(call -> {
                    UUID id = call.getArgument(0);
                    long amount = call.getArgument(1);
                    MutationSource source = call.getArgument(2);
                    long before = balances.getOrDefault(id, 0L);
                    if (amount <= 0) {
                        return result(BalanceMutationStatus.INVALID_AMOUNT, BalanceMutationType.REMOVE,
                                id, amount, before, before, source);
                    }
                    if (before < amount) {
                        return result(BalanceMutationStatus.INSUFFICIENT_FUNDS, BalanceMutationType.REMOVE,
                                id, amount, before, before, source);
                    }
                    balances.put(id, before - amount);
                    commitOrdersDocument();
                    return result(BalanceMutationStatus.SUCCESS, BalanceMutationType.REMOVE,
                            id, amount, before, before - amount, source);
                });
        when(eco.addMoney(any(UUID.class), anyLong(), any(MutationSource.class), anyString()))
                .thenAnswer(call -> {
                    UUID id = call.getArgument(0);
                    long amount = call.getArgument(1);
                    MutationSource source = call.getArgument(2);
                    long before = balances.getOrDefault(id, 0L);
                    if (amount <= 0) {
                        return result(BalanceMutationStatus.INVALID_AMOUNT, BalanceMutationType.ADD,
                                id, amount, before, before, source);
                    }
                    if (before + amount > EconomyManager.MAX) {
                        return result(BalanceMutationStatus.MAX_BALANCE_EXCEEDED, BalanceMutationType.ADD,
                                id, amount, before, before, source);
                    }
                    balances.put(id, before + amount);
                    commitOrdersDocument();
                    return result(BalanceMutationStatus.SUCCESS, BalanceMutationType.ADD,
                            id, amount, before, before + amount, source);
                });
    }

    @AfterEach
    void closeDatabase() {
        if (db != null) db.close();
    }

    // === helpers ===

    private EconomyDatabase openDatabase() throws SQLException {
        EconomyDatabase opened = new EconomyDatabase(
                EconomyPaths.dataDir(server).resolve(EconomyDatabase.DB_FILE_NAME));
        opened.initialize();
        return opened;
    }

    /** Simulates a server restart: reopens the database and reloads the store from what was committed. */
    private void restart() throws SQLException {
        db.close();
        db = openDatabase();
        orders = new OrderManager(server, mock(DeliveryManager.class), db);
        when(eco.getOrders()).thenReturn(orders);
    }

    /**
     * The production commit seam in miniature: a committed mutation persists the balances document
     * and the orders document in one transactional save, and nothing else writes in between.
     */
    private void commitOrdersDocument() {
        ordersJsonBeforeCommit = db.readDocument("orders.json");
        orders.save();
        ordersJsonAtCommit = db.readDocument("orders.json");
    }

    private static BalanceMutationResult result(BalanceMutationStatus status, BalanceMutationType type,
                                                UUID id, long amount, long previous, long current,
                                                MutationSource source) {
        return new BalanceMutationResult(status, type, id, amount, previous, current, Optional.of(source));
    }

    /** The persisted form of one request, or {@code null} when the document holds no such id. */
    private static JsonObject requestJson(String document, int id) {
        if (document == null) return null;
        JsonObject root = JsonParser.parseString(document).getAsJsonObject();
        if (!root.has("requests")) return null;
        for (var el : root.getAsJsonArray("requests")) {
            JsonObject obj = el.getAsJsonObject();
            if (obj.get("id").getAsInt() == id) return obj;
        }
        return null;
    }

    private OrderRequest funded(long price) {
        OrderRequest request = OrderFulfillment.createEscrowedRequest(eco, REQUESTER,
                new ItemStack(Items.EMERALD, 1), 64, price, "Bulk buy");
        assertNotNull(request, "the fixture order must fund");
        return request;
    }

    // === creation ===

    @Test
    @DisplayName("creation commits the funded order together with the escrow debit")
    void creationCommitsOrderTogetherWithDebit() throws SQLException {
        OrderRequest created = funded(1_280L);

        assertEquals(98_720L, balances.get(REQUESTER));
        assertNull(requestJson(ordersJsonBeforeCommit, created.id),
                "nothing may hit the orders document before the debit commits");
        JsonObject committed = requestJson(ordersJsonAtCommit, created.id);
        assertNotNull(committed, "the debit's transactional save must persist the funded order");
        assertEquals(1_280L, committed.get("escrow").getAsLong(),
                "a crash after the debit must leave the escrow liability on disk, not lost money");

        restart();
        OrderRequest reloaded = orders.getRequest(created.id);
        assertNotNull(reloaded, "the funded order must survive a restart");
        assertEquals(1_280L, reloaded.escrow);
        assertEquals(1_280L, reloaded.price);
        assertEquals(64, reloaded.amount);
        assertEquals(REQUESTER, reloaded.requester);
        assertEquals("Bulk buy", reloaded.description);
        assertEquals(98_720L, balances.get(REQUESTER), "escrowed money must not be lost on restart");
    }

    @Test
    @DisplayName("a rejected escrow debit leaves neither an order nor a persisted trace")
    void rejectedDebitLeavesNoOrderBehind() throws SQLException {
        balances.put(REQUESTER, 500L);

        OrderRequest created = OrderFulfillment.createEscrowedRequest(eco, REQUESTER,
                new ItemStack(Items.EMERALD, 1), 64, 1_280L);

        assertNull(created, "a broke requester must not get an order");
        assertTrue(orders.getRequests().isEmpty(), "no staged order may stay in memory");
        assertEquals(500L, balances.get(REQUESTER));
        assertNull(ordersJsonAtCommit, "a rejected debit must not trigger the transactional save");
        assertTrue(!db.hasDocument("orders.json"), "a rejected debit must not persist an order document");

        restart();
        assertTrue(orders.getRequests().isEmpty(), "no half-created order may survive a restart");
    }

    @Test
    @DisplayName("the bot quest seam commits escrow and board-week expiry together with the debit")
    void botQuestPostCommitsEscrowTogether() throws SQLException {
        long weekEnd = System.currentTimeMillis() + 7 * 24 * 3_600_000L;

        // The exact call QuestManager.post() makes: explicit board-week expiry, no chat broadcast.
        OrderRequest quest = OrderFulfillment.createEscrowedRequest(eco, BOT,
                new ItemStack(Items.OAK_LOG, 1), 32, 6_400L, null, weekEnd, false);

        assertNotNull(quest);
        assertEquals(BOT, quest.requester);
        assertEquals(weekEnd, quest.expiresAt);
        assertEquals(93_600L, balances.get(BOT));
        assertNull(requestJson(ordersJsonBeforeCommit, quest.id),
                "nothing may hit the orders document before the bot's debit commits");
        JsonObject committed = requestJson(ordersJsonAtCommit, quest.id);
        assertNotNull(committed, "the bot's debit must persist the funded quest order in the same save");
        assertEquals(weekEnd, committed.get("expiresAt").getAsLong());

        restart();
        OrderRequest reloaded = orders.getRequest(quest.id);
        assertNotNull(reloaded, "a posted quest must survive a restart");
        assertEquals(weekEnd, reloaded.expiresAt, "quest expiry is the board week, not the config default");
        assertEquals(6_400L, reloaded.escrow);
        assertEquals(BOT, reloaded.requester);
        assertEquals(93_600L, balances.get(BOT));
    }

    // === repricing ===

    @Test
    @DisplayName("raising a reward commits the raised order together with the hold")
    void setPriceRaiseCommitsTogether() throws SQLException {
        OrderRequest order = funded(1_280L);

        assertEquals(OrderFulfillment.RepriceStatus.OK,
                OrderFulfillment.setPrice(eco, REQUESTER, order.id, 2_000L));
        assertEquals(98_000L, balances.get(REQUESTER));

        JsonObject committed = requestJson(ordersJsonAtCommit, order.id);
        assertNotNull(committed);
        assertEquals(2_000L, committed.get("price").getAsLong(),
                "the raised price must ride the hold's save, not a later one");
        assertEquals(2_000L, committed.get("escrow").getAsLong());

        restart();
        assertEquals(2_000L, orders.getRequest(order.id).price);
        assertEquals(2_000L, orders.getRequest(order.id).escrow);
        assertEquals(98_000L, balances.get(REQUESTER));
    }

    @Test
    @DisplayName("a rejected hold restores the previous order state")
    void setPriceRaiseFailureRestoresPreviousState() throws SQLException {
        OrderRequest order = funded(1_280L);
        balances.put(REQUESTER, 500L);

        assertEquals(OrderFulfillment.RepriceStatus.CANT_AFFORD_RAISE,
                OrderFulfillment.setPrice(eco, REQUESTER, order.id, 2_000L));

        assertEquals(1_280L, order.price, "the staged raise must be rolled back in memory");
        assertEquals(1_280L, order.escrow);
        assertEquals(500L, balances.get(REQUESTER));

        restart();
        assertEquals(1_280L, orders.getRequest(order.id).price, "the unfunded raise must not survive");
        assertEquals(1_280L, orders.getRequest(order.id).escrow);
    }

    @Test
    @DisplayName("lowering a reward commits the lowered order together with the refund")
    void setPriceReductionRefundCommitsTogether() throws SQLException {
        OrderRequest order = funded(2_000L);

        assertEquals(OrderFulfillment.RepriceStatus.OK,
                OrderFulfillment.setPrice(eco, REQUESTER, order.id, 1_200L));
        assertEquals(98_800L, balances.get(REQUESTER));

        JsonObject committed = requestJson(ordersJsonAtCommit, order.id);
        assertNotNull(committed);
        assertEquals(1_200L, committed.get("price").getAsLong(),
                "the lowered price must already be on disk when the refund lands");
        assertEquals(1_200L, committed.get("escrow").getAsLong(),
                "a crash after the refund must not leave the old, higher reward claimable too");

        restart();
        assertEquals(1_200L, orders.getRequest(order.id).price);
        assertEquals(1_200L, orders.getRequest(order.id).escrow);
        assertEquals(98_800L, balances.get(REQUESTER));
    }

    @Test
    @DisplayName("a bounced refund restores the previous order state and moves no money")
    void setPriceReductionRefundFailureRestoresPreviousState() throws SQLException {
        OrderRequest order = funded(2_000L);
        balances.put(REQUESTER, EconomyManager.MAX);

        assertEquals(OrderFulfillment.RepriceStatus.REFUND_FAILED,
                OrderFulfillment.setPrice(eco, REQUESTER, order.id, 1_200L));

        assertEquals(2_000L, order.price, "the staged reduction must be rolled back in memory");
        assertEquals(2_000L, order.escrow);
        assertEquals(EconomyManager.MAX, balances.get(REQUESTER));

        restart();
        assertEquals(2_000L, orders.getRequest(order.id).price, "the failed reduction must leave no trace");
        assertEquals(2_000L, orders.getRequest(order.id).escrow);
    }

    // === cancellation ===

    @Test
    @DisplayName("cancellation persists the removal only with the refund, never before it")
    void cancelRemovalRidesTheRefundCommit() throws SQLException {
        OrderRequest order = funded(1_280L);

        assertEquals(OrderFulfillment.CancelStatus.OK,
                OrderFulfillment.cancel(eco, REQUESTER, order.id));
        assertEquals(100_000L, balances.get(REQUESTER));

        assertNotNull(requestJson(ordersJsonBeforeCommit, order.id),
                "the removal must not be persisted before the refund commits");
        assertNull(requestJson(ordersJsonAtCommit, order.id),
                "the refund's transactional save must persist the removal together with the money");

        restart();
        assertNull(orders.getRequest(order.id), "a cancelled order must not come back");
        assertEquals(100_000L, balances.get(REQUESTER), "the escrow must be back with its owner");
    }

    @Test
    @DisplayName("a bounced cancellation refund leaves the order and its escrow restorable")
    void cancelRefundFailureKeepsTheOrder() throws SQLException {
        OrderRequest order = funded(1_280L);
        balances.put(REQUESTER, EconomyManager.MAX);

        assertEquals(OrderFulfillment.CancelStatus.REFUND_FAILED,
                OrderFulfillment.cancel(eco, REQUESTER, order.id));
        assertEquals(EconomyManager.MAX, balances.get(REQUESTER));

        restart();
        OrderRequest reloaded = orders.getRequest(order.id);
        assertNotNull(reloaded, "a failed refund must not swallow the order");
        assertEquals(1_280L, reloaded.escrow, "the escrow liability must stay on disk with no refund paid");
        assertEquals(1_280L, reloaded.price);
    }

    // === expiry and admin clear ===

    @Test
    @DisplayName("expiry persists the removal together with the refund, never before it")
    void expiryRefundAndRemovalCommitTogether() throws SQLException {
        long past = System.currentTimeMillis() - 1_000L;
        OrderRequest order = OrderFulfillment.createEscrowedRequest(eco, REQUESTER,
                new ItemStack(Items.EMERALD, 1), 64, 1_280L, null, past, false);
        assertNotNull(order);
        assertEquals(98_720L, balances.get(REQUESTER));

        OrderFulfillment.expireOverdue(eco);

        assertEquals(100_000L, balances.get(REQUESTER), "expiry must refund the escrow");
        assertNotNull(requestJson(ordersJsonBeforeCommit, order.id),
                "the removal must not be persisted before the expiry refund commits");
        assertNull(requestJson(ordersJsonAtCommit, order.id),
                "the refund's transactional save must persist the removal together with the money");

        restart();
        assertNull(orders.getRequest(order.id), "an expired order must not come back");
        assertEquals(100_000L, balances.get(REQUESTER));
    }

    @Test
    @DisplayName("an admin clear persists the removal together with the refund, never before it")
    void adminClearRefundAndRemovalCommitTogether() throws SQLException {
        OrderRequest order = funded(1_280L);

        int cleared = OrderFulfillment.clearAll(eco);

        assertEquals(1, cleared);
        assertEquals(100_000L, balances.get(REQUESTER), "an admin clear must refund the escrow");
        assertNotNull(requestJson(ordersJsonBeforeCommit, order.id),
                "the removal must not be persisted before the clear's refund commits");
        assertNull(requestJson(ordersJsonAtCommit, order.id),
                "the refund's transactional save must persist the removal together with the money");

        restart();
        assertNull(orders.getRequest(order.id), "a cleared order must not come back");
        assertEquals(100_000L, balances.get(REQUESTER));
    }
}
