package com.reazip.economycraft.gossip;

import com.reazip.economycraft.gossip.storage.PlayerMemory;
import com.reazip.economycraft.gossip.storage.VillagerDatabase;
import com.reazip.economycraft.gossip.storage.VillagerProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class VillagerDatabaseTest {
    private VillagerDatabase database;

    @BeforeEach
    void setUp() throws SQLException {
        database = VillagerDatabase.createInMemory();
        database.initialize();
    }

    @AfterEach
    void tearDown() {
        if (database != null) {
            database.close();
        }
    }

    @Test
    @DisplayName("Save and retrieve villager profile")
    void testSaveAndGetVillagerProfile() throws Exception {
        UUID uuid = UUID.randomUUID();
        VillagerProfile profile = new VillagerProfile(
                uuid,
                "Barnaby",
                "blacksmith",
                "plains",
                List.of("grumpy", "perfectionist"),
                "Inspects every emerald",
                "Former royal apprentice",
                1000L,
                2000L
        );

        database.saveVillager(profile).get();

        Optional<VillagerProfile> retrieved = database.getVillager(uuid).get();
        assertTrue(retrieved.isPresent());
        assertEquals("Barnaby", retrieved.get().name());
        assertEquals("blacksmith", retrieved.get().profession());
        assertEquals("plains", retrieved.get().biome());
        assertEquals(List.of("grumpy", "perfectionist"), retrieved.get().traits());
        assertEquals("Inspects every emerald", retrieved.get().quirk());
        assertEquals(1000L, retrieved.get().createdAt());
    }

    @Test
    @DisplayName("Save and retrieve player memory")
    void testSaveAndGetPlayerMemory() throws Exception {
        UUID villagerUuid = UUID.randomUUID();
        UUID playerUuid = UUID.randomUUID();

        // Must save villager first for foreign key integrity
        VillagerProfile profile = new VillagerProfile(
                villagerUuid, "Cedric", "farmer", "plains",
                List.of("jovial"), "Likes wheat", "Quiet farmer", 100L, 100L);
        database.saveVillager(profile).get();

        PlayerMemory memory = new PlayerMemory(
                villagerUuid,
                playerUuid,
                15,
                3,
                450L,
                5000L,
                List.of("Bought 10 bread", "Complimented crops")
        );

        database.savePlayerMemory(memory).get();

        Optional<PlayerMemory> retrieved = database.getPlayerMemory(villagerUuid, playerUuid).get();
        assertTrue(retrieved.isPresent());
        assertEquals(15, retrieved.get().sentiment());
        assertEquals(3, retrieved.get().interactionCount());
        assertEquals(450L, retrieved.get().totalSpent());
        assertEquals(2, retrieved.get().recentEvents().size());
        assertEquals("Bought 10 bread", retrieved.get().recentEvents().get(0));
    }

    @Test
    @DisplayName("Counting villagers")
    void testCountVillagers() throws Exception {
        assertEquals(0, database.getVillagerCount().get());

        UUID v1 = UUID.randomUUID();
        UUID v2 = UUID.randomUUID();
        database.saveVillager(new VillagerProfile(v1, "V1", "farmer", "plains", List.of(), "", "", 0, 0)).get();
        database.saveVillager(new VillagerProfile(v2, "V2", "cleric", "plains", List.of(), "", "", 0, 0)).get();

        assertEquals(2, database.getVillagerCount().get());
    }

    @Test
    @DisplayName("Record and retrieve recent trade history")
    void testSaveAndGetRecentTrades() throws Exception {
        UUID villagerUuid = UUID.randomUUID();
        UUID playerUuid = UUID.randomUUID();

        // Save villager profile for foreign key
        VillagerProfile profile = new VillagerProfile(
                villagerUuid, "Garrick", "weaponsmith", "taiga",
                List.of("stoic"), "Sharpens blades", "Veteran smith", 100L, 100L);
        database.saveVillager(profile).get();

        long now = System.currentTimeMillis();
        database.recordTradeTransaction(villagerUuid, playerUuid, "Diamond Sword", 1, 150L, now - 3000).get();
        database.recordTradeTransaction(villagerUuid, playerUuid, "Iron Ingot", 5, 25L, now - 2000).get();
        database.recordTradeTransaction(villagerUuid, playerUuid, "Shield", 1, 50L, now - 1000).get();

        List<com.reazip.economycraft.gossip.storage.TradeRecord> trades = database.getRecentTrades(villagerUuid, playerUuid, 2).get();
        assertEquals(2, trades.size());
        // Ordered by timestamp DESC
        assertEquals("Shield", trades.get(0).itemName());
        assertEquals(50L, trades.get(0).pricePaid());
        assertEquals("Iron Ingot", trades.get(1).itemName());
        assertEquals(5, trades.get(1).itemCount());
    }

    @Test
    @DisplayName("Memory clear removes exactly one pair and its trade history")
    void clearMemoryIsPairScoped() throws Exception {
        UUID villager = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        UUID otherPlayer = UUID.randomUUID();
        database.saveVillager(new VillagerProfile(villager, "V", "farmer", "plains", List.of(), "", "", 0, 0)).get();
        long now = System.currentTimeMillis();
        database.savePlayerMemory(PlayerMemory.createDefault(villager, player, now)).get();
        database.savePlayerMemory(PlayerMemory.createDefault(villager, otherPlayer, now)).get();
        database.recordTradeTransaction(villager, player, "Wheat", 3, 99, now).get();
        database.recordTradeTransaction(villager, otherPlayer, "Carrot", 2, 77, now).get();

        assertEquals(2, database.clearMemoryPair(villager, player).get());
        assertTrue(database.getPlayerMemory(villager, player).get().isEmpty());
        assertEquals(1, database.getRecentTrades(villager, otherPlayer, 5).get().size());
        assertTrue(database.getRecentTrades(villager, player, 5).get().isEmpty());
    }

    @Test
    @DisplayName("Retention keeps the exact cutoff and excludes older rows")
    void retentionBoundaryIsInclusive() throws Exception {
        UUID villager = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        long now = VillagerDatabase.MEMORY_RETENTION_MILLIS + 50_000;
        long cutoff = VillagerDatabase.retentionCutoff(now);
        database.saveVillager(new VillagerProfile(villager, "V", "farmer", "plains", List.of(), "", "", 0, 0)).get();
        database.savePlayerMemory(PlayerMemory.createDefault(villager, player, cutoff - 1)).get();
        database.recordTradeTransaction(villager, player, "Old", 1, 1, cutoff - 1).get();
        database.recordTradeTransaction(villager, player, "Boundary", 2, 500, cutoff).get();

        assertTrue(database.getEligiblePlayerMemory(villager, player, now).get().isEmpty());
        var trades = database.getRecentTrades(villager, player, 5, now).get();
        assertEquals(1, trades.size());
        assertEquals(cutoff, trades.getFirst().timestamp());
        assertEquals(2, database.deleteExpiredMemoriesAndTrades(now).get());
    }

    @Test
    @DisplayName("Trade descriptions require safe text and a positive quantity")
    void tradeProjectionValidatesItemAndQuantity() {
        UUID villager = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        assertEquals("2x Wheat", new com.reazip.economycraft.gossip.storage.TradeRecord(1, villager, player, "Wheat", 2, 10, 1).toPromptDescription());
        assertEquals("", new com.reazip.economycraft.gossip.storage.TradeRecord(1, villager, player, "Wheat", 0, 10, 1).toPromptDescription());
        assertEquals("", new com.reazip.economycraft.gossip.storage.TradeRecord(1, villager, player, "Bad\nName", 1, 10, 1).toPromptDescription());
    }
}
