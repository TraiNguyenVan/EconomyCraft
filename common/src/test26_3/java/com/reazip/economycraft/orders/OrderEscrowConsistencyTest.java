package com.reazip.economycraft.orders;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.reazip.economycraft.DeliveryManager;
import com.reazip.economycraft.EconomyManager;
import com.reazip.economycraft.NotificationManager;
import com.reazip.economycraft.PriceRegistry;
import com.reazip.economycraft.SellService;
import com.reazip.economycraft.api.v1.BalanceMutationResult;
import com.reazip.economycraft.api.v1.BalanceMutationStatus;
import com.reazip.economycraft.api.v1.BalanceMutationType;
import com.reazip.economycraft.api.v1.MutationSource;
import com.reazip.economycraft.db.EconomyDatabase;
import com.reazip.economycraft.negotiation.NegotiationStore;
import com.reazip.economycraft.quests.QuestManager;
import com.reazip.economycraft.quests.QuestStock;
import com.reazip.economycraft.util.EconomyPaths;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.player.Inventory;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Crash-consistency of the order escrow lifecycle against a real {@link OrderManager} over a
 * file-backed {@link EconomyDatabase}, with a ledger-backed mock {@link EconomyManager} standing
 * in for the balance engine.
 *
 * <p>The ledger mock reproduces the production commit seam: a <em>successful</em> money mutation
 * persists the balances, orders, deliveries and quest-stock documents together — exactly what
 * {@code EconomyManager.save()}'s single SQLite transaction does on every committed mutation —
 * and a rejected mutation persists nothing. The mock captures the orders and deliveries documents
 * both before that commit and as the commit wrote them, which is what a crash at either instant
 * would leave on disk. Every scenario then reopens the database, reloads every store and discards
 * the in-memory ledger — the same thing the server does on restart — and asserts that the
 * persisted balance and the persisted order escrow (and any delivered goods) still agree.
 *
 * <p>The ledger enforces the real engine's two sharp edges — debits fail on insufficient funds,
 * credits fail past {@link EconomyManager#MAX} — so rejection and recovery paths behave here
 * the way they behave in production.
 */
class OrderEscrowConsistencyTest {

    private static final UUID REQUESTER = UUID.randomUUID();
    private static final UUID SELLER = UUID.randomUUID();
    private static final UUID BOT = QuestManager.BOT_UUID;

    @TempDir
    Path world;

    private MinecraftServer server;
    private EconomyDatabase db;
    private DeliveryManager deliveries;
    private QuestStock questStock;
    private OrderManager orders;
    private EconomyManager eco;
    /** The test ledger: every money movement in these tests lands here. */
    private final Map<UUID, Long> balances = new HashMap<>();

    /** The orders document as it stood before the last successful money mutation committed. */
    private String ordersJsonBeforeCommit;
    /** The orders document as the last successful money mutation's transactional save wrote it. */
    private String ordersJsonAtCommit;
    /** The deliveries document as the last successful money mutation's transactional save wrote it. */
    private String deliveriesJsonAtCommit;

    /** One-shot fault: the next transactional save aborts the way a failed SQLite write would. */
    private boolean failNextCommit;

    /** Reads how many goods the seller holds right now; the payout-ordering test points it at the seller. */
    private Supplier<Integer> sellerHoldings = () -> 0;
    /** How many goods the seller held at the instant the payout was attempted. */
    private int heldAtPayout = -1;

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
        // The OrderManager's server-thread guard runs during its saves; the test thread plays it.
        when(server.isSameThread()).thenReturn(true);
        PlayerList players = mock(PlayerList.class);
        when(server.getPlayerList()).thenReturn(players);
        when(players.getPlayers()).thenReturn(List.of());

        db = openDatabase();
        deliveries = new DeliveryManager(server, db);
        questStock = new QuestStock(server, db);
        orders = new OrderManager(server, deliveries, db);

        balances.clear();
        balances.put(REQUESTER, 100_000L);
        balances.put(BOT, 100_000L);

        eco = mock(EconomyManager.class);
        when(eco.getOrders()).thenReturn(orders);
        when(eco.getDeliveries()).thenReturn(deliveries);
        when(eco.getQuestStock()).thenReturn(questStock);
        PriceRegistry prices = mock(PriceRegistry.class);
        // One price for every stack, so the order routing finds the fixture order.
        when(prices.resolve(any(ItemStack.class))).thenReturn(new PriceRegistry.PriceEntry(
                "emerald", null, "misc", 64, 1_000L, 1_000L, null, false));
        when(eco.getPrices()).thenReturn(prices);
        when(eco.getServer()).thenReturn(server);
        when(eco.getNotifications()).thenReturn(mock(NotificationManager.class));
        when(eco.getNegotiations()).thenReturn(mock(NegotiationStore.class));
        doAnswer(call -> {
            commitDocuments();
            return null;
        }).when(eco).save();

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
                    try {
                        commitDocuments();
                    } catch (RuntimeException e) {
                        // The production engine puts the ledger back the way the caller found it
                        // when its transactional save throws; the mock mirrors that so the
                        // handlers around it see the same failure the real code does.
                        balances.put(id, before);
                        throw e;
                    }
                    return result(BalanceMutationStatus.SUCCESS, BalanceMutationType.REMOVE,
                            id, amount, before, before - amount, source);
                });
        when(eco.addMoney(any(UUID.class), anyLong(), any(MutationSource.class), anyString()))
                .thenAnswer(call -> {
                    UUID id = call.getArgument(0);
                    long amount = call.getArgument(1);
                    MutationSource source = call.getArgument(2);
                    if (SELLER.equals(id)) heldAtPayout = sellerHoldings.get();
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
                    try {
                        commitDocuments();
                    } catch (RuntimeException e) {
                        // The production engine puts the ledger back the way the caller found it
                        // when its transactional save throws; the mock mirrors that.
                        balances.put(id, before);
                        throw e;
                    }
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

    /**
     * Simulates a server restart: reopens the database, reloads the order store and every other
     * document from what was committed, and discards the in-memory ledger.
     */
    private void restart() throws SQLException {
        db.close();
        db = openDatabase();
        deliveries = new DeliveryManager(server, db);
        questStock = new QuestStock(server, db);
        orders = new OrderManager(server, deliveries, db);
        loadBalances();
        when(eco.getOrders()).thenReturn(orders);
        when(eco.getDeliveries()).thenReturn(deliveries);
        when(eco.getQuestStock()).thenReturn(questStock);
    }

    /** Reloads the test ledger the way {@link EconomyManager#load()} reloads the production one. */
    private void loadBalances() {
        balances.clear();
        String json = db.readDocument("balances.json");
        if (json == null) return;
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        for (var entry : root.entrySet()) {
            balances.put(UUID.fromString(entry.getKey()), entry.getValue().getAsLong());
        }
    }

    /**
     * Writes the ledger's first baseline commit — the way production always has a persistent
     * balances document behind the live one — so a restart that must not move money can be asserted.
     */
    private void persistBaselineLedger() {
        commitDocuments();
    }

    /**
     * The production commit seam in miniature: a committed mutation persists the balances, orders,
     * deliveries and quest-stock documents in one transactional save, and nothing else writes in
     * between.
     */
    private void commitDocuments() {
        ordersJsonBeforeCommit = db.readDocument("orders.json");
        if (failNextCommit) {
            failNextCommit = false;
            throw new IllegalStateException("simulated SQLite write failure");
        }
        db.runInTransaction(() -> {
            db.writeDocument("balances.json", balancesJson());
            orders.save();
            deliveries.save();
            questStock.save();
        });
        ordersJsonAtCommit = db.readDocument("orders.json");
        deliveriesJsonAtCommit = db.readDocument("deliveries.json");
    }

    private String balancesJson() {
        JsonObject root = new JsonObject();
        for (var entry : balances.entrySet()) {
            root.addProperty(entry.getKey().toString(), entry.getValue());
        }
        return root.toString();
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
        return fundedBy(REQUESTER, price);
    }

    private OrderRequest fundedBy(UUID requester, long price) {
        OrderRequest request = OrderFulfillment.createEscrowedRequest(eco, requester,
                new ItemStack(Items.EMERALD, 1), 64, price, "Bulk buy");
        assertNotNull(request, "the fixture order must fund");
        return request;
    }

    /**
     * A seller holding {@code amount} emeralds, and the slots that hold them.
     *
     * <p>The inventory is mocked but really stores what it is given, so a restore that puts the
     * goods back where they came from is visible here instead of vanishing into a mock.
     */
    private SellerFixture fulfillerWithEmeralds(int amount) {
        ServerPlayer seller = mock(ServerPlayer.class);
        when(seller.getUUID()).thenReturn(SELLER);
        List<ItemStack> slots = new ArrayList<>();
        slots.add(new ItemStack(Items.EMERALD, amount));
        Inventory inventory = mock(Inventory.class);
        when(inventory.getContainerSize()).thenReturn(36);
        when(inventory.getItem(anyInt())).thenAnswer(call -> {
            int slot = call.getArgument(0);
            return slot < slots.size() ? slots.get(slot) : ItemStack.EMPTY;
        });
        when(inventory.add(any(ItemStack.class))).thenAnswer(call -> {
            slots.add(call.getArgument(0));
            return true;
        });
        when(seller.getInventory()).thenReturn(inventory);
        when(seller.getOffhandItem()).thenReturn(ItemStack.EMPTY);
        return new SellerFixture(seller, slots);
    }

    /** A seller and the slots that hold their goods. */
    private record SellerFixture(ServerPlayer seller, List<ItemStack> slots) {
        /** How many emeralds the seller is holding right now. */
        int held() {
            int total = 0;
            for (ItemStack stack : slots) {
                if (stack != null && stack.is(Items.EMERALD)) total += stack.getCount();
            }
            return total;
        }
    }

    /**
     * How many copies of the taken goods exist: in the seller's hands, in the buyer's mailbox and
     * in the seller's own mailbox. The goods must exist exactly once at every stage of a
     * fulfillment — two copies is duplication, none is a loss.
     */
    private int goodsCopies(SellerFixture seller, UUID buyer) {
        int copies = seller.held();
        for (ItemStack stack : deliveries.getDeliveries(buyer)) {
            if (stack.is(Items.EMERALD)) copies += stack.getCount();
        }
        for (ItemStack stack : deliveries.getDeliveries(SELLER)) {
            if (stack.is(Items.EMERALD)) copies += stack.getCount();
        }
        return copies;
    }

    /** How many delivery stacks the persisted document holds for one player. */
    private static int deliveryCount(String document, UUID player) {        if (document == null) return 0;
        JsonObject root = JsonParser.parseString(document).getAsJsonObject();
        if (!root.has(player.toString())) return 0;
        return root.getAsJsonArray(player.toString()).size();
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
    @DisplayName("a thrown escrow debit leaves neither an order, nor a charge, nor a persisted trace")
    void thrownDebitLeavesNoMoneyNorOrderBehind() throws SQLException {
        persistBaselineLedger();
        String baselineOrders = ordersJsonAtCommit;
        failNextCommit = true;

        assertThrows(RuntimeException.class,
                () -> OrderFulfillment.createEscrowedRequest(eco, REQUESTER,
                        new ItemStack(Items.EMERALD, 1), 64, 1_280L));

        assertTrue(orders.getRequests().isEmpty(), "no staged order may stay in memory after a thrown debit");
        assertEquals(100_000L, balances.get(REQUESTER),
                "the thrown save must not leave the escrow charge in the live ledger");
        assertEquals(baselineOrders, ordersJsonAtCommit,
                "a thrown debit must not trigger the transactional save");

        restart();
        assertTrue(orders.getRequests().isEmpty(), "no half-created order may survive a restart");
        assertEquals(100_000L, balances.get(REQUESTER), "the debit must not be persisted with nothing to show for it");
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

    // === fulfillment ===

    @Test
    @DisplayName("the goods leave the seller before the payout is attempted")
    void goodsLeaveTheSellerBeforeThePayout() {
        OrderRequest order = funded(1_280L);
        SellerFixture seller = fulfillerWithEmeralds(64);
        sellerHoldings = seller::held;

        OrderFulfillment.Result result = OrderFulfillment.fulfill(eco, seller.seller(), order.id, 64);

        assertTrue(result.success(), "the fixture fulfillment must succeed");
        assertEquals(0, heldAtPayout,
                "the goods must leave the seller before the payout: a crash between the payment "
                        + "and the removal would leave the buyer with the goods and the seller with "
                        + "the originals, and no transaction spans the ledger and the inventory");
        assertEquals(64, goodsCopies(seller, REQUESTER),
                "the goods must exist exactly once: the buyer has them, the seller has none");
    }

    @Test
    @DisplayName("fulfillment commits the buyer's delivery with the payout and the order reduction")
    void fulfillmentCommitsDeliveryWithSettlement() throws SQLException {
        OrderRequest order = funded(1_280L);
        SellerFixture seller = fulfillerWithEmeralds(64);

        OrderFulfillment.Result result = OrderFulfillment.fulfill(eco, seller.seller(), order.id, 64);

        assertEquals(OrderFulfillment.Status.OK, result.status());
        assertEquals(1, deliveryCount(deliveriesJsonAtCommit, REQUESTER),
                "the payout's own save must already contain the buyer's delivery");
        assertEquals(0, deliveryCount(deliveriesJsonAtCommit, SELLER),
                "the same save must consume the seller's handoff with the settlement");
        assertNull(requestJson(ordersJsonAtCommit, order.id),
                "the same save must persist the fully fulfilled order as gone");
        assertEquals(98_720L, balances.get(REQUESTER));
        assertEquals(result.payout(), balances.get(SELLER));
        assertEquals(0, seller.held(),
                "the seller must not keep the goods the buyer was paid for");
        assertEquals(64, goodsCopies(seller, REQUESTER),
                "the goods must exist exactly once: the buyer has them, the seller has none");

        restart();
        assertNull(orders.getRequest(order.id));
        assertEquals(98_720L, balances.get(REQUESTER), "the escrow debit must survive a restart");
        assertEquals(result.payout(), balances.get(SELLER), "the seller's payout must survive a restart");
        List<ItemStack> stored = deliveries.getDeliveries(REQUESTER);
        assertEquals(1, stored.size(), "the delivered goods must survive the restart");
        assertEquals(64, stored.get(0).getCount());
        assertTrue(stored.get(0).is(Items.EMERALD));
        assertTrue(deliveries.getDeliveries(SELLER).isEmpty(),
                "the consumed handoff must not come back as a second claimable copy");
    }

    @Test
    @DisplayName("bot fulfillment commits quest stock with the payout and the order reduction")
    void botFulfillmentCommitsQuestStockWithSettlement() throws SQLException {
        OrderRequest order = fundedBy(BOT, 1_280L);
        SellerFixture seller = fulfillerWithEmeralds(64);

        OrderFulfillment.Result result = OrderFulfillment.fulfill(eco, seller.seller(), order.id, 64);

        assertEquals(OrderFulfillment.Status.OK, result.status());
        assertEquals(64, questStock.total(), "bot-bound goods must be booked into quest stock");
        assertEquals(98_720L, balances.get(BOT));
        assertEquals(result.payout(), balances.get(SELLER));
        assertTrue(deliveries.getDeliveries(BOT).isEmpty(),
                "bot orders must not leave goods in an unread mailbox");
        assertTrue(deliveries.getDeliveries(SELLER).isEmpty(),
                "the handoff for the taken stacks must be consumed with the settlement");

        restart();
        assertNull(orders.getRequest(order.id));
        assertEquals(64, questStock.total(), "the delivered quest stock must survive a restart");
        assertEquals(98_720L, balances.get(BOT));
        assertEquals(result.payout(), balances.get(SELLER));
    }

    @Test
    @DisplayName("a rejected payout gives the goods back to the seller and leaves the mailbox empty")
    void rejectedFulfillmentReturnsTheGoodsToTheSeller() throws SQLException {
        OrderRequest order = funded(1_280L);
        balances.put(SELLER, EconomyManager.MAX);
        SellerFixture seller = fulfillerWithEmeralds(64);

        OrderFulfillment.Result result = OrderFulfillment.fulfill(eco, seller.seller(), order.id, 64);

        assertEquals(OrderFulfillment.Status.FULFILLER_CANT_RECEIVE, result.status());
        assertEquals(0, result.taken(),
                "the goods must go back to the seller, not wait in their mailbox");
        assertEquals(64, seller.held(), "the seller must get their goods back");
        assertTrue(eco.getDeliveries().getDeliveries(SELLER).isEmpty(),
                "the mailbox must not hold a second copy of goods the seller holds again");
        assertTrue(eco.getDeliveries().getDeliveries(REQUESTER).isEmpty(),
                "a rejected payout must not leave the staged goods in the buyer's mailbox");
        assertEquals(64, order.amount, "the claim must be rolled back in memory");
        assertEquals(1_280L, order.escrow, "the escrow must be untouched by a refused payout");

        restart();
        OrderRequest reloaded = orders.getRequest(order.id);
        assertNotNull(reloaded, "a rejected payout must leave the order on disk");
        assertEquals(64, reloaded.amount);
        assertEquals(1_280L, reloaded.escrow);
        assertEquals(98_720L, balances.get(REQUESTER));
        assertTrue(deliveries.getDeliveries(REQUESTER).isEmpty(),
                "a rejected payout must not persist goods against the escrow");
        assertTrue(deliveries.getDeliveries(SELLER).isEmpty(),
                "the handoff must not survive a restart when the goods went back to the seller");
    }

    @Test
    @DisplayName("a handoff write that fails gives the goods back to the seller instead of losing them")
    void failedHandoffWriteReturnsTheGoodsToTheSeller() throws Exception {
        OrderRequest order = funded(1_280L);
        SellerFixture seller = fulfillerWithEmeralds(64);

        SqliteDocumentFault.failWritesTo(server, "deliveries.json");
        try {
            assertThrows(RuntimeException.class,
                    () -> OrderFulfillment.fulfill(eco, seller.seller(), order.id, 64),
                    "a handoff that did not commit must surface as a failure, not as a silent loss");
        } finally {
            SqliteDocumentFault.dropFailingTrigger(server);
        }

        assertEquals(64, seller.held(),
                "the goods must go back to the seller when the handoff did not commit");
        assertTrue(eco.getDeliveries().getDeliveries(SELLER).isEmpty(),
                "a handoff that did not commit must not leave a claimable copy behind");
        assertTrue(eco.getDeliveries().getDeliveries(REQUESTER).isEmpty(),
                "a failed handoff must not leave the buyer's goods staged");
        assertEquals(64, orders.getRequest(order.id).amount, "the claim must be rolled back");
        assertEquals(1_280L, orders.getRequest(order.id).escrow);

        restart();
        assertNotNull(orders.getRequest(order.id), "the order must stay open when the handoff failed");
        assertTrue(deliveries.getDeliveries(SELLER).isEmpty(),
                "nothing may be persisted for a handoff that did not commit");
        assertTrue(deliveries.getDeliveries(REQUESTER).isEmpty());
    }

    @Test
    @DisplayName("a refused order payout does not sell the same goods to the server as well")
    void refusedOrderPayoutDoesNotSellTheSameGoodsTwice() {
        OrderRequest order = funded(1_280L);
        balances.put(SELLER, EconomyManager.MAX);
        SellerFixture seller = fulfillerWithEmeralds(64);
        ItemStack source = seller.slots().get(0);

        // The order pays more per item than the server does, so the routing tries the order first.
        SellService.SaleSplit split = SellService.sellHandWithRouting(eco, seller.seller(), source, 64, 1L);

        assertEquals(0, split.orderGiven(), "a refused payout must not count as a sale to the order");
        assertEquals(0, split.taken(), "the goods must go back to the seller, not wait in the mailbox");
        assertEquals(64, split.serverRemaining(),
                "the server may only buy what the seller still holds");
        assertEquals(64, seller.held(), "the seller must still hold the goods the server pays for");
        assertTrue(eco.getDeliveries().getDeliveries(SELLER).isEmpty(),
                "the goods must not sit in the mailbox and in the seller's hands at once");
    }

    @Test
    @DisplayName("a thrown settlement rolls the staged goods, claim and balances back, leaving the handoff claimable")
    void thrownSettlementRestoresEverythingAroundIt() throws SQLException {
        OrderRequest order = funded(1_280L);
        SellerFixture seller = fulfillerWithEmeralds(64);
        failNextCommit = true;

        assertThrows(RuntimeException.class, () -> OrderFulfillment.fulfill(eco, seller.seller(), order.id, 64),
                "the thrown save must reach the caller as a real failure");

        assertEquals(1_280L, orders.getRequest(order.id).escrow, "the claim must be rolled back in memory");
        assertEquals(64, orders.getRequest(order.id).amount);
        assertTrue(eco.getDeliveries().getDeliveries(REQUESTER).isEmpty(),
                "the staged delivery must not outlive a settlement that never committed");
        assertEquals(64, eco.getDeliveries().getDeliveries(SELLER).get(0).getCount(),
                "the taken stacks must come back claimable, since nothing was paid for them");
        assertEquals(0, seller.held(),
                "the goods must have left the seller before the settlement, or a crash would leave "
                        + "them with the seller while the buyer was paid for them");
        assertEquals(64, goodsCopies(seller, REQUESTER),
                "the goods must exist exactly once: the seller's mailbox has them, and neither the "
                        + "seller nor the buyer holds a second copy");
        assertEquals(98_720L, balances.get(REQUESTER), "the settlement must not leave a charge behind");
        assertEquals(0L, balances.get(SELLER), "the settlement must not leave a payout behind");
        String liveDeliveryDoc = db.readDocument("deliveries.json");
        assertEquals(0, deliveryCount(liveDeliveryDoc, REQUESTER),
                "the buyer's delivery must not be persisted against a failed settlement");

        restart();
        OrderRequest reloaded = orders.getRequest(order.id);
        assertNotNull(reloaded, "the order must stay on disk when the settlement aborts");
        assertEquals(1_280L, reloaded.escrow);
        assertEquals(64, reloaded.amount);
        assertTrue(deliveries.getDeliveries(REQUESTER).isEmpty());
        List<ItemStack> handoff = deliveries.getDeliveries(SELLER);
        assertEquals(1, handoff.size(), "the handoff must still be claimable after the restart");
        assertEquals(64, handoff.get(0).getCount());
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
    @DisplayName("a thrown hold leaves neither a charge nor a raised price behind")
    void thrownHoldLeavesNeitherChargeNorRaisedPrice() throws SQLException {
        OrderRequest order = funded(1_280L);
        failNextCommit = true;

        assertThrows(RuntimeException.class,
                () -> OrderFulfillment.setPrice(eco, REQUESTER, order.id, 2_000L));

        assertEquals(1_280L, order.price, "the staged raise must be rolled back in memory");
        assertEquals(1_280L, order.escrow);
        assertEquals(98_720L, balances.get(REQUESTER),
                "the thrown save must not leave the raise's hold in the live ledger");

        restart();
        assertEquals(1_280L, orders.getRequest(order.id).price, "the aborted raise must not survive");
        assertEquals(1_280L, orders.getRequest(order.id).escrow);
        assertEquals(98_720L, balances.get(REQUESTER), "the hold must not be persisted with nothing to show for it");
    }

    @Test
    @DisplayName("a thrown refund leaves neither a refund nor a lowered price behind")
    void thrownRefundLeavesNeitherRefundNorLoweredPrice() throws SQLException {
        OrderRequest order = funded(2_000L);
        failNextCommit = true;

        assertThrows(RuntimeException.class,
                () -> OrderFulfillment.setPrice(eco, REQUESTER, order.id, 1_200L));

        assertEquals(2_000L, order.price, "the staged reduction must be rolled back in memory");
        assertEquals(2_000L, order.escrow);
        assertEquals(98_000L, balances.get(REQUESTER),
                "the thrown save must not leave the refund in the live ledger");

        restart();
        assertEquals(2_000L, orders.getRequest(order.id).price, "the aborted reduction must not survive");
        assertEquals(98_000L, balances.get(REQUESTER),
                "the refund must not be persisted with nothing to show for it");
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

    @Test
    @DisplayName("a complete reset removes escrow it cannot refund past the balance cap")
    void completeResetDiscardsOverCapEscrow() throws SQLException {
        OrderRequest order = funded(1_280L);
        balances.put(REQUESTER, EconomyManager.MAX);
        persistBaselineLedger();

        int cleared = OrderFulfillment.clearAllForReset(eco);

        assertEquals(1, cleared);
        assertNull(orders.getRequest(order.id), "a complete reset must not leave a funded order behind");
        restart();
        assertNull(orders.getRequest(order.id), "the cancelled order must stay removed after restart");
        assertEquals(EconomyManager.MAX, balances.get(REQUESTER), "a rejected refund must not change the balance");
    }
}
