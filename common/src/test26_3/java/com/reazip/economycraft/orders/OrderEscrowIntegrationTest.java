package com.reazip.economycraft.orders;

import com.reazip.economycraft.EconomyConfig;
import com.reazip.economycraft.EconomyManager;
import com.reazip.economycraft.EconomySources;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    }
}
