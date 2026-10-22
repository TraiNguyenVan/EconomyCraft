package com.reazip.economycraft.orders;

import com.reazip.economycraft.EconomyConfig;
import com.reazip.economycraft.EconomyManager;
import com.reazip.economycraft.EconomySources;
import com.reazip.economycraft.db.EconomyDatabase;
import com.reazip.economycraft.util.EconomyPaths;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerScoreboard;
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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * End-to-end order-escrow tests over the real production persistence path: a full
 * {@link EconomyManager} — the real {@code BalanceMutationEngine} committing through the real
 * {@code EconomyManager.save()} SQLite transaction — with only the Minecraft server surface
 * mocked. {@link OrderEscrowConsistencyTest} simulates the commit seam because the unit harness
 * has no real engine; these tests close that gap by proving the simulated seam and the
 * production seam behave identically.
 *
 * <p>Every scenario reloads through a brand-new manager over the same database file — the same
 * thing a server restart does — and asserts that the persisted balance and the persisted order
 * escrow agree, both after a successful commit and after an injected failure.
 */
class OrderEscrowIntegrationTest {

    private static final UUID REQUESTER = UUID.randomUUID();
    private static final UUID SELLER = UUID.randomUUID();

    @TempDir
    Path world;

    private MinecraftServer server;
    private EconomyManager eco;
    private final List<EconomyManager> managers = new ArrayList<>();

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
    void setUp() {
        // Mutations sync the scoreboard through the server; with it disabled the sync is a no-op
        // the mocked scoreboard absorbs anyway.
        EconomyConfig.get().scoreboardEnabled = false;

        server = mock(MinecraftServer.class);
        when(server.getWorldPath(LevelResource.ROOT)).thenReturn(world);
        when(server.registryAccess())
                .thenReturn(RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
        // The real engine insists mutations run on the server thread; the test thread plays it.
        when(server.isSameThread()).thenReturn(true);
        when(server.getScoreboard()).thenReturn(mock(ServerScoreboard.class));
        PlayerList players = mock(PlayerList.class);
        when(server.getPlayerList()).thenReturn(players);
        when(players.getPlayers()).thenReturn(List.of());

        eco = track(new EconomyManager(server));
    }

    @AfterEach
    void tearDown() {
        // detach() is the server-stop path: final save, dispatcher release, database close.
        managers.forEach(EconomyManager::detach);
        managers.clear();
    }

    // === helpers ===

    private EconomyManager track(EconomyManager manager) {
        managers.add(manager);
        return manager;
    }

    /** The restart itself: a brand-new manager reopens the database file and reloads every store. */
    private EconomyManager restart() {
        return track(new EconomyManager(server));
    }

    private OrderRequest fundOrder(long price) {
        eco.setMoney(REQUESTER, 100_000L, EconomySources.ADMIN_SET);
        OrderRequest created = OrderFulfillment.createEscrowedRequest(eco, REQUESTER,
                new ItemStack(Items.EMERALD, 1), 64, price);
        assertNotNull(created, "the fixture order must fund through the real engine");
        return created;
    }

    /** A seller holding {@code amount} emeralds, enough to fulfill the fixture order. */
    private ServerPlayer fulfillerWithEmeralds(int amount) {
        ServerPlayer seller = mock(ServerPlayer.class);
        when(seller.getUUID()).thenReturn(SELLER);
        Inventory inventory = mock(Inventory.class);
        when(inventory.getContainerSize()).thenReturn(36);
        when(inventory.getItem(anyInt())).thenReturn(ItemStack.EMPTY);
        when(inventory.getItem(0)).thenReturn(new ItemStack(Items.EMERALD, amount));
        when(seller.getInventory()).thenReturn(inventory);
        when(seller.getOffhandItem()).thenReturn(ItemStack.EMPTY);
        return seller;
    }

    // === scenarios ===

    @Test
    @DisplayName("a funded order and its debit commit together through the real engine")
    void successfulCreateCommitsBalanceAndOrderThroughTheProductionSeam() {
        OrderRequest created = fundOrder(1_280L);

        assertEquals(98_720L, eco.getBalance(REQUESTER, false));
        assertEquals(1_280L, eco.getOrders().getRequest(created.id).escrow);

        EconomyManager reloaded = restart();
        assertEquals(98_720L, reloaded.getBalance(REQUESTER, false),
                "the escrow debit must be on disk after a restart");
        OrderRequest persisted = reloaded.getOrders().getRequest(created.id);
        assertNotNull(persisted, "the funded order must be on disk with the debit that paid for it");
        assertEquals(1_280L, persisted.escrow);
        assertEquals(1_280L, persisted.price);
        assertEquals(REQUESTER, persisted.requester);
    }

    @Test
    @DisplayName("an injected debit failure persists neither the money movement nor the order")
    void rejectedDebitPersistsNeitherMoneyNorOrder() {
        eco.setMoney(REQUESTER, 500L, EconomySources.ADMIN_SET);

        OrderRequest created = OrderFulfillment.createEscrowedRequest(eco, REQUESTER,
                new ItemStack(Items.EMERALD, 1), 64, 1_280L);

        assertNull(created, "an underfunded requester must not get an order");
        assertEquals(500L, eco.getBalance(REQUESTER, false));

        EconomyManager reloaded = restart();
        assertEquals(500L, reloaded.getBalance(REQUESTER, false), "the failed debit must not move money");
        assertTrue(reloaded.getOrders().getRequests().isEmpty(), "no half-created order may survive a restart");
    }

    @Test
    @DisplayName("a cancellation commits its refund and removal together through the real engine")
    void cancellationCommitsRefundAndRemovalThroughTheProductionSeam() {
        OrderRequest created = fundOrder(1_280L);

        assertEquals(OrderFulfillment.CancelStatus.OK,
                OrderFulfillment.cancel(eco, REQUESTER, created.id));
        assertEquals(100_000L, eco.getBalance(REQUESTER, false));
        assertNull(eco.getOrders().getRequest(created.id));

        EconomyManager reloaded = restart();
        assertNull(reloaded.getOrders().getRequest(created.id),
                "the removal must be on disk with its refund, never before it");
        assertEquals(100_000L, reloaded.getBalance(REQUESTER, false),
                "the refund must be on disk with the removal");
    }

    @Test
    @DisplayName("a fulfilled order's delivery, payout and reduction commit through the real engine")
    void fulfillmentCommitsDeliveryWithPayoutThroughTheProductionSeam() {
        OrderRequest created = fundOrder(1_280L);
        eco.setMoney(SELLER, 0L, EconomySources.ADMIN_SET);
        ServerPlayer seller = fulfillerWithEmeralds(64);

        OrderFulfillment.Result result = OrderFulfillment.fulfill(eco, seller, created.id, 64);

        assertTrue(result.success(), "the fixture fulfillment must succeed through the real engine");
        assertNull(eco.getOrders().getRequest(created.id));
        assertEquals(98_720L, eco.getBalance(REQUESTER, false), "the escrow must cover the whole payment");
        assertEquals(result.payout(), eco.getBalance(SELLER, false));
        List<ItemStack> delivered = eco.getDeliveries().getDeliveries(REQUESTER);
        assertEquals(1, delivered.size(), "the fulfilled goods must be in the buyer's deliveries");
        assertEquals(64, delivered.get(0).getCount());
        assertTrue(delivered.get(0).is(Items.EMERALD));

        EconomyManager reloaded = restart();
        assertNull(reloaded.getOrders().getRequest(created.id),
                "the fully fulfilled order must not come back");
        assertEquals(98_720L, reloaded.getBalance(REQUESTER, false));
        assertEquals(result.payout(), reloaded.getBalance(SELLER, false),
                "the seller's payout must be on disk with the delivery");
        List<ItemStack> persisted = reloaded.getDeliveries().getDeliveries(REQUESTER);
        assertEquals(1, persisted.size(), "the buyer's delivery must survive the restart");
        assertEquals(64, persisted.get(0).getCount());
        assertTrue(persisted.get(0).is(Items.EMERALD));
        assertTrue(reloaded.getDeliveries().getDeliveries(SELLER).isEmpty(),
                "the settlement must consume the seller's handoff, never leave a second copy");
    }

    @Test
    @DisplayName("a thrown order save returns the ledger the caller found, through the real engine")
    void thrownSaveLeavesTheBalancesUntouchedThroughTheProductionEngine() throws Exception {
        OrderRequest created = fundOrder(1_280L);
        long funded = eco.getBalance(REQUESTER, false);

        failUpdatesTo("orders.json");
        try {
            // The debit's save writes balances.json (succeeds), then orders.json — the trigger
            // aborts there, the transaction rolls back and `removeMoney` must throw without
            // leaving the debit in the live ledger.
            assertThrows(RuntimeException.class,
                    () -> OrderFulfillment.createEscrowedRequest(eco, REQUESTER,
                            new ItemStack(Items.EMERALD, 1), 64, 1_280L),
                    "a failed document write must surface as a thrown, non-committed mutation");

            assertEquals(funded, eco.getBalance(REQUESTER, false),
                    "the engine must put the live ledger back when its save aborts — otherwise the "
                            + "next successful save would persist a charge with the order unstaged around it");
            assertTrue(eco.getOrders().getRequests().stream().noneMatch(r -> r.requester.equals(REQUESTER) && r.id != created.id),
                    "no half-created order may survive a thrown debit in the live store");
        } finally {
            dropFailingTrigger();
        }

        // The retry: with the fault gone, a save must leave exactly the pre-mutation state.
        eco.save();
        EconomyManager reloaded = restart();
        assertNotNull(reloaded.getOrders().getRequest(created.id),
                "the original funded order must be untouched");
        assertEquals(funded, reloaded.getBalance(REQUESTER, false),
                "the failed debit must not end up on disk");
    }

    @Test
    @DisplayName("a thrown settlement rolls the fulfillment back and leaves the handoff claimable, through the real engine")
    void thrownSettlementLeavesTheHandoffClaimableThroughTheProductionEngine() throws Exception {
        OrderRequest created = fundOrder(1_280L);
        // A durable baseline for the fulfiller, so "no payout landed" can be asserted on disk.
        eco.setMoney(SELLER, 1_000L, EconomySources.ADMIN_SET);
        long requesterFunded = eco.getBalance(REQUESTER, false);
        long sellerBaseline = eco.getBalance(SELLER, false);

        // The settlement credit's save writes balances.json, then orders.json — the trigger aborts
        // there and the transaction rolls back, so nothing was committed.
        failUpdatesTo("orders.json");
        try {
            ServerPlayer seller = fulfillerWithEmeralds(64);
            assertThrows(RuntimeException.class, () -> OrderFulfillment.fulfill(eco, seller, created.id, 64),
                    "a failed settlement must surface as a thrown error, not a silent half-payment");

            assertEquals(requesterFunded, eco.getBalance(REQUESTER, false),
                    "the escrow must not move when the settlement aborts");
            assertEquals(sellerBaseline, eco.getBalance(SELLER, false),
                    "the engine must put the live ledger back — otherwise the next save keeps a payout the transaction refused");
            OrderRequest restored = eco.getOrders().getRequest(created.id);
            assertNotNull(restored,
                    "a fully claimed order must be restored in memory when the settlement aborts");
            assertEquals(64, restored.amount, "the claim must be rolled back with the failed settlement");
            assertEquals(1_280L, restored.escrow);
            assertTrue(eco.getDeliveries().getDeliveries(REQUESTER).isEmpty(),
                    "the staged buyer delivery must roll back with the failed settlement");
            List<ItemStack> handoff = eco.getDeliveries().getDeliveries(SELLER);
            assertEquals(1, handoff.size(),
                    "the taken stacks must be claimable compensation once the settlement did not commit");
            assertEquals(64, handoff.get(0).getCount());
            assertTrue(handoff.get(0).is(Items.EMERALD));
        } finally {
            dropFailingTrigger();
        }

        // The restart view: the rollback left the order, the escrow and the claimable handoff on
        // disk, with no buyer delivery and no payout.
        EconomyManager reloaded = restart();
        assertNotNull(reloaded.getOrders().getRequest(created.id),
                "the order must stay on disk when the settlement aborts");
        assertEquals(requesterFunded, reloaded.getBalance(REQUESTER, false));
        assertEquals(sellerBaseline, reloaded.getBalance(SELLER, false), "no payout may be on disk");
        assertTrue(reloaded.getDeliveries().getDeliveries(REQUESTER).isEmpty());
        List<ItemStack> persistedHandoff = reloaded.getDeliveries().getDeliveries(SELLER);
        assertEquals(1, persistedHandoff.size(),
                "the claimable handoff must be the durable recovery path after a crash");
        assertEquals(64, persistedHandoff.get(0).getCount());
    }

    /** Fails one document's UPDATE the way a real SQLite write failure aborts the save. */
    private void failUpdatesTo(String key) throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:"
                        + EconomyPaths.dataDir(server).resolve(EconomyDatabase.DB_FILE_NAME).toAbsolutePath());
             Statement st = conn.createStatement()) {
            st.execute("CREATE TRIGGER fail_document_update BEFORE UPDATE ON documents "
                    + "WHEN OLD.name = '" + key + "' BEGIN SELECT RAISE(ABORT, 'simulated write failure'); END");
        }
    }

    private void dropFailingTrigger() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:"
                        + EconomyPaths.dataDir(server).resolve(EconomyDatabase.DB_FILE_NAME).toAbsolutePath());
             Statement st = conn.createStatement()) {
            st.execute("DROP TRIGGER IF EXISTS fail_document_update");
        }
    }
}
