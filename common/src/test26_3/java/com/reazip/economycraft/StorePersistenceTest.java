package com.reazip.economycraft;

import com.reazip.economycraft.auction.AuctionListing;
import com.reazip.economycraft.auction.AuctionManager;
import com.reazip.economycraft.db.EconomyDatabase;
import com.reazip.economycraft.orders.OrderManager;
import com.reazip.economycraft.orders.OrderRequest;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Auction and order persistence over the shared {@code economycraft.db}.
 *
 * <p>Both stores hold other people's money in escrow, so a lost write is a lost deposit. Each test
 * therefore writes through one manager and reads through a <em>new</em> manager over a reopened
 * database file — the same thing the server does on restart — instead of asserting against the
 * in-memory map that just wrote it.
 */
class StorePersistenceTest {

    private static final UUID SELLER = UUID.randomUUID();
    private static final UUID INTRUDER = UUID.randomUUID();

    @TempDir Path world;

    private MinecraftServer server;
    private EconomyDatabase db;
    private AuctionManager auctions;
    private OrderManager orders;

    @BeforeAll
    static void bootstrap() {
        // Same order as TollUiTest: the version has to be detected before anything touches DataFixers,
        // and item components have to be readable before any stack is encoded.
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(VanillaRegistries.createWorldLookup())
                .forEach(components -> components.apply());
    }

    @BeforeEach
    void setup() throws SQLException {
        server = mock(MinecraftServer.class);
        when(server.getWorldPath(LevelResource.ROOT)).thenReturn(world);
        when(server.registryAccess())
                .thenReturn(RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
        when(server.isSameThread()).thenReturn(true);
        db = openDatabase();
        auctions = new AuctionManager(server, mock(DeliveryManager.class), db);
        orders = new OrderManager(server, mock(DeliveryManager.class), db);
    }

    @AfterEach
    void closeDatabase() {
        if (db != null) db.close();
    }

    private EconomyDatabase openDatabase() throws SQLException {
        EconomyDatabase opened = new EconomyDatabase(
                EconomyPaths.dataDir(server).resolve(EconomyDatabase.DB_FILE_NAME));
        opened.initialize();
        return opened;
    }

    /** Simulates a restart: closes the database and reopens it with fresh stores. */
    private void restart() throws SQLException {
        db.close();
        db = openDatabase();
        auctions = new AuctionManager(server, mock(DeliveryManager.class), db);
        orders = new OrderManager(server, mock(DeliveryManager.class), db);
    }

    private static AuctionListing listing(long price) {
        AuctionListing listing = new AuctionListing();
        listing.seller = SELLER;
        listing.item = new ItemStack(Items.DIAMOND, 3);
        listing.price = price;
        listing.createdAt = 1_000L;
        listing.expiresAt = 0L;
        listing.description = "  Line 1\nLine 2";
        return listing;
    }

    private static OrderRequest request() {
        OrderRequest request = new OrderRequest();
        request.requester = SELLER;
        request.item = new ItemStack(Items.EMERALD, 16);
        request.amount = 64;
        request.price = 1_280L;
        request.escrow = 1_280L;
        request.createdAt = 1_000L;
        request.expiresAt = 0L;
        return request;
    }

    @Test
    void auctionListingSurvivesRestart() throws Exception {
        AuctionListing saved = listing(500L);
        auctions.addListing(saved);
        int id = saved.id;

        restart();

        AuctionListing loaded = auctions.getListing(id);
        assertNotNull(loaded, "listing must survive a restart");
        assertEquals(SELLER, loaded.seller);
        assertEquals(500L, loaded.price);
        assertEquals(1_000L, loaded.createdAt);
        assertEquals(Items.DIAMOND, loaded.item.getItem());
        assertEquals(3, loaded.item.getCount());
        assertEquals("  Line 1\nLine 2", loaded.description, "descriptions are stored verbatim, not sanitized");
    }

    @Test
    void orderEscrowSurvivesRestart() throws Exception {
        OrderRequest saved = request();
        orders.addRequest(saved);
        int id = saved.id;

        restart();

        OrderRequest loaded = orders.getRequest(id);
        assertNotNull(loaded, "order must survive a restart");
        assertEquals(SELLER, loaded.requester);
        assertEquals(64, loaded.amount);
        assertEquals(1_280L, loaded.price);
        assertEquals(1_280L, loaded.escrow, "escrowed money must not be lost on restart");
        assertEquals(Items.EMERALD, loaded.item.getItem());
        assertEquals(16, loaded.item.getCount());
    }

    @Test
    void removedListingStaysRemovedAfterRestart() throws Exception {
        AuctionListing kept = listing(100L);
        AuctionListing dropped = listing(200L);
        auctions.addListing(kept);
        auctions.addListing(dropped);
        auctions.removeListing(dropped.id);

        restart();

        assertNull(auctions.getListing(dropped.id), "a cancelled listing must not come back");
        assertNotNull(auctions.getListing(kept.id));
    }

    @Test
    void idsDoNotCollideAfterRestart() throws Exception {
        AuctionListing first = listing(100L);
        auctions.addListing(first);
        int firstId = first.id;
        restart();

        AuctionListing second = listing(200L);
        auctions.addListing(second);

        assertEquals(firstId + 1, second.id, "the id counter has to resume past the restored listing");
        assertNotNull(auctions.getListing(firstId));
    }

    @Test
    void restoringAListingDoesNotLetTheNextOneOverwriteIt() {
        // AuctionTrade restores a claimed listing when a sale is rolled back, and that insertion
        // never advances the id counter. So on a fresh manager the counter can still point at the id
        // being restored, and the next listing must be given a different one.
        AuctionListing claimed = listing(500L);
        claimed.id = 1;
        auctions.restoreListing(claimed);
        assertEquals(1, auctions.getListing(1).id, "the restored entry occupies id 1");

        AuctionListing fresh = listing(900L);
        auctions.addListing(fresh);

        assertEquals(2, fresh.id, "the counter must not hand out an id that is already in use");
        assertEquals(500L, auctions.getListing(1).price, "the restored listing must survive");
        assertEquals(900L, auctions.getListing(2).price);
        assertEquals(2, auctions.getListings().size());
    }

    @Test
    void restoringARequestDoesNotLetTheNextOneOverwriteIt() {
        OrderRequest claimed = request();
        claimed.id = 1;
        orders.restoreRequest(claimed);
        assertEquals(1, orders.getRequest(1).id);

        OrderRequest fresh = request();
        orders.addRequest(fresh);

        assertEquals(2, fresh.id);
        assertEquals(1_280L, orders.getRequest(1).escrow, "restored escrow must survive");
        assertEquals(2, orders.getRequests().size());
    }

    @Test
    void writesLandInTheDatabaseOnly() throws Exception {
        auctions.addListing(listing(100L));
        orders.addRequest(request());

        Path dataDir = EconomyPaths.dataDir(server);
        assertTrue(db.hasDocument("auctions.json"));
        assertTrue(db.hasDocument("orders.json"));
        assertFalse(Files.exists(dataDir.resolve("auctions.json")), "no legacy file beside the database");
        assertFalse(Files.exists(dataDir.resolve("orders.json")), "no legacy file beside the database");
    }

    @Test
    void legacyFileIsReadWhenTheDatabaseHasNoRow() throws Exception {
        Path dataDir = EconomyPaths.dataDir(server);
        Files.writeString(dataDir.resolve("auctions.json"), "{\"nextId\":7}");

        try (EconomyDatabase fresh = EconomyDatabase.createInMemory()) {
            fresh.initialize();
            AuctionManager migrated = new AuctionManager(server, mock(DeliveryManager.class), fresh);
            assertTrue(migrated.getListings().isEmpty());

            AuctionListing next = listing(300L);
            migrated.addListing(next);
            assertEquals(7, next.id, "the id counter from the legacy file has to carry over");
        }
    }

    @Test
    void onlyTheSellerCanRepriceOrRedescribe() throws Exception {
        AuctionListing saved = listing(500L);
        auctions.addListing(saved);
        int id = saved.id;

        assertFalse(auctions.setPrice(id, INTRUDER, 1L), "a stale menu must not reprice someone else's listing");
        assertFalse(auctions.setDescription(id, INTRUDER, "hijacked"), "a stale menu must not rewrite a description");
        assertEquals(500L, auctions.getListing(id).price);
        assertEquals("  Line 1\nLine 2", auctions.getListing(id).description);

        assertTrue(auctions.setPrice(id, SELLER, 750L));
        assertEquals(750L, auctions.getListing(id).price);
        assertFalse(auctions.setPrice(id, SELLER, 0L), "a zero price is not a price");
    }

    @Test
    void descriptionEditsSurviveRestart() throws Exception {
        AuctionListing saved = listing(500L);
        auctions.addListing(saved);
        assertTrue(auctions.setDescription(saved.id, SELLER, "  Fresh\ncopy  "));

        restart();

        assertEquals("Freshcopy", auctions.getListing(saved.id).description,
                "setDescription sanitizes once, and storage keeps what it was given");
    }
}