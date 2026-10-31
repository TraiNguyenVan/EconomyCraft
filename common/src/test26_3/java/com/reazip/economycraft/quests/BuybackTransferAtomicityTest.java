package com.reazip.economycraft.quests;

import com.reazip.economycraft.DeliveryManager;
import com.reazip.economycraft.EconomyManager;
import com.reazip.economycraft.PriceRegistry;
import com.reazip.economycraft.auction.AuctionListing;
import com.reazip.economycraft.auction.AuctionManager;
import com.reazip.economycraft.db.EconomyDatabase;
import com.reazip.economycraft.negotiation.NegotiationStore;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Atomic migration of legacy quest-board AH listings into finite shop stock. */
class BuybackTransferAtomicityTest {
    private static final String KEY = "minecraft:diamond";

    @TempDir Path world;

    private MinecraftServer server;
    private EconomyDatabase db;
    private EconomyManager eco;
    private DeliveryManager deliveries;

    @BeforeAll
    static void bootstrap() {
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
        when(server.isSameThread()).thenReturn(true);

        db = openDatabase();
        deliveries = mock(DeliveryManager.class);
        PriceRegistry prices = mock(PriceRegistry.class);
        PriceRegistry.PriceEntry diamond =
                new PriceRegistry.PriceEntry(KEY, null, "materials", 64, 100L, 50L, null, false);
        when(prices.resolve(any(ItemStack.class))).thenReturn(diamond);

        eco = mock(EconomyManager.class);
        AuctionManager auctions = new AuctionManager(server, deliveries, db);
        QuestStock stock = new QuestStock(server, db);
        QuestManager quests = new QuestManager(server, db);
        NegotiationStore negotiations = new NegotiationStore(db,
                EconomyPaths.dataDir(server).resolve("negotiations.json"));
        when(eco.getDatabase()).thenReturn(db);
        when(eco.getPrices()).thenReturn(prices);
        when(eco.getDeliveries()).thenReturn(deliveries);
        when(eco.getAuctions()).thenReturn(auctions);
        when(eco.getQuestStock()).thenReturn(stock);
        when(eco.getQuests()).thenReturn(quests);
        when(eco.getNegotiations()).thenReturn(negotiations);
    }

    @AfterEach
    void closeDatabase() {
        if (db != null) db.close();
    }

    @Test
    void migrationMovesLegacyListingsToStockExactlyOnce() {
        AuctionManager auctions = eco.getAuctions();
        auctions.addListing(botListing(13), false);

        assertEquals(1, QuestBuyback.migrateListingsToStock(eco));
        assertTrue(auctions.getListings().isEmpty());
        assertEquals(13L, eco.getQuestStock().get(KEY));

        assertEquals(0, QuestBuyback.migrateListingsToStock(eco));
        assertEquals(13L, eco.getQuestStock().get(KEY));
        assertEquals(13L, new QuestStock(server, db).get(KEY));
        assertTrue(new AuctionManager(server, deliveries, db).getListings().isEmpty());
    }

    @Test
    void failedMigrationWriteRestoresListingAndStock() throws SQLException {
        AuctionManager auctions = eco.getAuctions();
        auctions.addListing(botListing(4), false);
        failUpdatesTo("auctions.json");

        assertThrows(RuntimeException.class, () -> QuestBuyback.migrateListingsToStock(eco));

        assertEquals(4, auctions.getListings().get(0).item.getCount());
        assertEquals(0L, eco.getQuestStock().get(KEY));
        assertEquals(4, new AuctionManager(server, deliveries, db).getListings().get(0).item.getCount());
        assertEquals(0L, new QuestStock(server, db).get(KEY));
    }

    @Test
    void rolledBackMoneyTransferRestoresTheBalanceSnapshot() {
        Map<UUID, Long> snapshot = Map.of(UUID.randomUUID(), 73L);
        when(eco.getBalancesSnapshot()).thenReturn(snapshot);

        assertThrows(IllegalStateException.class,
                () -> BuybackTransfers.run(eco, () -> { throw new IllegalStateException("rollback"); }));

        verify(eco).restoreBalancesSnapshot(snapshot);
    }

    private static AuctionListing botListing(int count) {
        AuctionListing listing = new AuctionListing();
        listing.seller = QuestManager.BOT_UUID;
        listing.item = new ItemStack(Items.DIAMOND, count);
        listing.price = count * 100L;
        listing.createdAt = 1_000L;
        listing.expiresAt = 1L;
        return listing;
    }

    private void failUpdatesTo(String key) throws SQLException {
        Path file = EconomyPaths.dataDir(server).resolve(EconomyDatabase.DB_FILE_NAME);
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
             Statement st = conn.createStatement()) {
            st.execute("CREATE TRIGGER fail_document_update BEFORE UPDATE ON documents " +
                    "WHEN OLD.name = '" + key + "' BEGIN SELECT RAISE(ABORT, 'simulated write failure'); END");
        }
    }

    private EconomyDatabase openDatabase() throws SQLException {
        EconomyDatabase opened = new EconomyDatabase(
                EconomyPaths.dataDir(server).resolve(EconomyDatabase.DB_FILE_NAME));
        opened.initialize();
        return opened;
    }
}
