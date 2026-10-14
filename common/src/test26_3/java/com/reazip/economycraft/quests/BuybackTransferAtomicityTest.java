package com.reazip.economycraft.quests;

import com.reazip.economycraft.DeliveryManager;
import com.reazip.economycraft.EconomyConfig;
import com.reazip.economycraft.EconomyManager;
import com.reazip.economycraft.NotificationManager;
import com.reazip.economycraft.PriceRegistry;
import com.reazip.economycraft.auction.AuctionExpiration;
import com.reazip.economycraft.auction.AuctionListing;
import com.reazip.economycraft.auction.AuctionManager;
import com.reazip.economycraft.db.EconomyDatabase;
import com.reazip.economycraft.util.EconomyPaths;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * Quest buyback stock conservation across the two documents it lives in: {@code stock.json} (the
 * ledger) and {@code auctions.json} (the listings those ledger units are moved into).
 *
 * <p>A transfer is legal only when the units end up on exactly one side of it — never on both
 * (duplication) and never on neither (loss). Each test drives a real transfer through a real
 * {@link AuctionManager} and {@link QuestStock} over a file-backed database, breaks the transfer
 * where a crash would (the auctions write fails), and then reads the state back through freshly
 * opened stores, exactly as a restarted server would. Before the transfer was one transaction the
 * ledger was written first, so these reads saw the withdrawn units missing (or, on the return
 * path, present while the listing still was) and the sum changed.
 */
class BuybackTransferAtomicityTest {

    /** The one priced item these tests move around. */
    private static final String KEY = "minecraft:diamond";

    @TempDir Path world;

    private MinecraftServer server;
    private EconomyDatabase db;
    private DeliveryManager deliveries;
    private EconomyManager eco;

    @BeforeAll
    static void bootstrap() {
        // Same order as StorePersistenceTest: detect the version before DataFixers, then let the
        // item component registries exist so a stack can be encoded into a listing.
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

        // Pin the quest keys these tests depend on; another test class may have moved them.
        EconomyConfig cfg = EconomyConfig.get();
        cfg.auctionExpirationHours = 24;
        cfg.quests.enabled = true;
        cfg.quests.buyback.enabled = true;
        cfg.quests.buyback.priceFactor = 0.8;
        cfg.quests.requireShopPrice = false;
        cfg.quests.sellFallbackMultiplier = 3.3;
        cfg.quests.maxUnsoldExpiries = 1;

        db = openDatabase();
        deliveries = mock(DeliveryManager.class);

        PriceRegistry prices = mock(PriceRegistry.class);
        PriceRegistry.PriceEntry diamond =
                new PriceRegistry.PriceEntry(KEY, null, "materials", 64, 100L, 50L, null, false);
        when(prices.findByKey(KEY)).thenReturn(diamond);
        when(prices.resolve(any(ItemStack.class))).thenReturn(diamond);
        when(prices.createPrototype(any(PriceRegistry.PriceEntry.class)))
                .thenReturn(new ItemStack(Items.DIAMOND));

        QuestManager quests = mock(QuestManager.class);
        when(quests.getAutoMarketBlacklist()).thenReturn(Set.of());

        eco = mock(EconomyManager.class);
        when(eco.getDatabase()).thenReturn(db);
        when(eco.getPrices()).thenReturn(prices);
        when(eco.getQuests()).thenReturn(quests);
        when(eco.getDeliveries()).thenReturn(deliveries);
        when(eco.getNotifications()).thenReturn(mock(NotificationManager.class));
        when(eco.getEffectiveBuyPrice(any(PriceRegistry.PriceEntry.class))).thenReturn(100L);
    }

    @AfterEach
    void closeDatabase() {
        if (db != null) db.close();
    }

    // ---- stock -> listing ----

    @Test
    @DisplayName("A listing that never commits does not consume the stock")
    void interruptedListingKeepsTheUnitsOnTheLedger() throws Exception {
        QuestStock stock = new QuestStock(server, db);
        stock.deposit(KEY, 10L);

        // The auctions write is where a crash lands: the ledger already recorded the withdrawal.
        AuctionManager auctions = mock(AuctionManager.class);
        doThrow(new RuntimeException("crash before the listing reaches the database"))
                .when(auctions).addListing(any());
        when(eco.getQuestStock()).thenReturn(stock);
        when(eco.getAuctions()).thenReturn(auctions);

        assertThrows(RuntimeException.class, () -> QuestBuyback.sweep(eco));

        QuestStock reloadedStock = restartStock();
        assertEquals(10L, reloadedStock.get(KEY), "a rolled-back listing must leave the ledger whole");
        assertTrue(restartAuctions().getListings().isEmpty(), "no listing was committed");
    }

    @Test
    @DisplayName("A committed listing moves the units and keeps the unsold remainder on the ledger")
    void sweepKeepsTheRemainderOnTheLedger() throws Exception {
        QuestStock stock = new QuestStock(server, db);
        stock.deposit(KEY, 100L); // larger than one lot of 64

        AuctionManager auctions = new AuctionManager(server, deliveries, db);
        when(eco.getQuestStock()).thenReturn(stock);
        when(eco.getAuctions()).thenReturn(auctions);

        QuestBuyback.sweep(eco);

        AuctionManager reloadedAuctions = restartAuctions();
        QuestStock reloadedStock = restartStock();
        assertEquals(1, reloadedAuctions.getListings().size());
        assertEquals(64, reloadedAuctions.getListings().get(0).item.getCount());
        assertEquals(36L, reloadedStock.get(KEY), "the units over one lot stay on the ledger");
        assertEquals(100L, reloadedStock.total() + reloadedAuctions.getListings().get(0).item.getCount(),
                "listing plus ledger still hold every unit");
    }

    @Test
    @DisplayName("A top-up that never commits does not consume the stock")
    void interruptedTopUpKeepsTheUnitsOnTheLedger() throws Exception {
        AuctionManager seeded = new AuctionManager(server, deliveries, db);
        seeded.addListing(botListing(10)); // room in its lot, so the sweep tops it up

        AuctionManager auctions = spy(seeded);
        doThrow(new RuntimeException("crash mid top-up")).when(auctions).save();
        QuestStock stock = new QuestStock(server, db);
        stock.deposit(KEY, 10L);
        when(eco.getQuestStock()).thenReturn(stock);
        when(eco.getAuctions()).thenReturn(auctions);

        assertThrows(RuntimeException.class, () -> QuestBuyback.sweep(eco));

        assertEquals(10L, restartStock().get(KEY), "the withdrawal and the top-up commit together");
        assertEquals(10, restartAuctions().getListings().get(0).item.getCount(),
                "the open lot keeps the units it already had");
    }

    // ---- listing -> stock ----

    @Test
    @DisplayName("An expired listing returns its units to the ledger")
    void expiredListingReturnsToStock() throws Exception {
        AuctionManager auctions = new AuctionManager(server, deliveries, db);
        auctions.addListing(botListing(4));
        QuestStock stock = new QuestStock(server, db);
        when(eco.getAuctions()).thenReturn(auctions);
        when(eco.getQuestStock()).thenReturn(stock);

        AuctionExpiration.expireOverdue(eco);

        assertTrue(restartAuctions().getListings().isEmpty(), "the expired listing is gone");
        assertEquals(4L, restartStock().get(KEY), "its units are back on the ledger");
    }

    @Test
    @DisplayName("An interrupted expiry return does not leave the units in two places")
    void interruptedExpiryReturnKeepsTheUnitsOnTheListingOnly() throws Exception {
        AuctionManager seeded = new AuctionManager(server, deliveries, db);
        seeded.addListing(botListing(4));

        AuctionManager auctions = spy(seeded);
        doThrow(new RuntimeException("crash before the removal reaches the database"))
                .when(auctions).save();
        QuestStock stock = new QuestStock(server, db);
        when(eco.getAuctions()).thenReturn(auctions);
        when(eco.getQuestStock()).thenReturn(stock);

        assertThrows(RuntimeException.class, () -> AuctionExpiration.expireOverdue(eco));

        QuestStock reloadedStock = restartStock();
        AuctionManager reloadedAuctions = restartAuctions();
        assertEquals(0L, reloadedStock.get(KEY), "a rolled-back return must not leave a second copy");
        assertEquals(1, reloadedAuctions.getListings().size());
        assertEquals(4, reloadedAuctions.getListings().get(0).item.getCount());
        assertEquals(4L, reloadedStock.total() + reloadedAuctions.getListings().get(0).item.getCount(),
                "the units are still on exactly one side");
    }

    @Test
    @DisplayName("An admin clear returns buyback units to the ledger")
    void clearedListingReturnsToStock() throws Exception {
        AuctionManager auctions = new AuctionManager(server, deliveries, db);
        auctions.addListing(botListing(4));
        QuestStock stock = new QuestStock(server, db);
        when(eco.getAuctions()).thenReturn(auctions);
        when(eco.getQuestStock()).thenReturn(stock);

        assertEquals(1, AuctionExpiration.clearAll(eco));

        assertTrue(restartAuctions().getListings().isEmpty(), "the cleared listing is gone");
        assertEquals(4L, restartStock().get(KEY), "its units are back on the ledger");
    }

    @Test
    @DisplayName("An interrupted admin clear does not leave the units in two places")
    void interruptedClearKeepsTheUnitsOnTheListingOnly() throws Exception {
        AuctionManager seeded = new AuctionManager(server, deliveries, db);
        seeded.addListing(botListing(4));

        AuctionManager auctions = spy(seeded);
        doThrow(new RuntimeException("crash before the clear reaches the database"))
                .when(auctions).save();
        QuestStock stock = new QuestStock(server, db);
        when(eco.getAuctions()).thenReturn(auctions);
        when(eco.getQuestStock()).thenReturn(stock);

        assertThrows(RuntimeException.class, () -> AuctionExpiration.clearAll(eco));

        QuestStock reloadedStock = restartStock();
        AuctionManager reloadedAuctions = restartAuctions();
        assertEquals(0L, reloadedStock.get(KEY), "a rolled-back clear must not leave a second copy");
        assertEquals(1, reloadedAuctions.getListings().size());
        assertEquals(4, reloadedAuctions.getListings().get(0).item.getCount());
    }

    // ---- helpers ----

    /** A long-expired bot lot, the shape the buyback market posts. */
    private static AuctionListing botListing(int count) {
        AuctionListing listing = new AuctionListing();
        listing.seller = QuestManager.BOT_UUID;
        listing.item = new ItemStack(Items.DIAMOND, count);
        listing.price = count * 100L;
        listing.createdAt = 1_000L;
        listing.expiresAt = 1L;
        return listing;
    }

    private EconomyDatabase openDatabase() throws SQLException {
        EconomyDatabase opened = new EconomyDatabase(
                EconomyPaths.dataDir(server).resolve(EconomyDatabase.DB_FILE_NAME));
        opened.initialize();
        return opened;
    }

    /** Reopens the database and reloads the ledger, the way a server restart would. */
    private QuestStock restartStock() throws SQLException {
        db.close();
        db = openDatabase();
        return new QuestStock(server, db);
    }

    /** Reopens the database and reloads the listings, the way a server restart would. */
    private AuctionManager restartAuctions() throws SQLException {
        db.close();
        db = openDatabase();
        return new AuctionManager(server, deliveries, db);
    }
}
