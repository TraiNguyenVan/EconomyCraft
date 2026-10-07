package com.reazip.economycraft.gossip;

import com.reazip.economycraft.gossip.memory.VillagerDialoguePromptBuilder;
import com.reazip.economycraft.gossip.storage.PlayerMemory;
import com.reazip.economycraft.gossip.storage.VillagerProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class VillagerDialoguePromptBuilderTest {

    @Test
    @DisplayName("Prompt builder includes persona, memories, grapevine, and inflation")
    void testPromptBuilderCompleteness() {
        UUID villagerUuid = UUID.randomUUID();
        UUID playerUuid = UUID.randomUUID();

        VillagerProfile profile = new VillagerProfile(
                villagerUuid,
                "Barnaby",
                "armorer",
                "plains",
                List.of("grumpy", "shrewd"),
                "Obsessed with iron purity.",
                "Left the capital after tax disputes.",
                0,
                0
        );

        PlayerMemory memory = new PlayerMemory(
                villagerUuid,
                playerUuid,
                25,
                4,
                1500L,
                0,
                List.of("Purchased diamond helmet", "Asked about shield repairs")
        );

        List<String> grapevine = List.of(
                "Iron prices dropped 10% today.",
                "The Quest Board posted a massive bounty."
        );

        String prompt = VillagerDialoguePromptBuilder.buildSystemInstruction(
                profile,
                memory,
                "The Feudal Lord",
                grapevine,
                7.5
        );

        assertTrue(prompt.contains("Barnaby"));
        assertTrue(prompt.contains("armorer"));
        assertTrue(prompt.contains("grumpy"));
        assertTrue(prompt.contains("shrewd"));
        assertTrue(prompt.contains("Obsessed with iron purity"));
        assertTrue(prompt.contains("The Feudal Lord"));
        assertTrue(prompt.contains("Purchased diamond helmet"));
        assertTrue(prompt.contains("Iron prices dropped 10%"));
        assertTrue(prompt.contains("7.50x"));
        assertTrue(prompt.contains("dialogue"));
        assertTrue(prompt.contains("sentiment_delta"));
    }

    @Test
    @DisplayName("Prompt builder includes negative prompt block for recently spoken topics")
    void testPromptBuilderWithRecentSpokenTopics() {
        UUID villagerUuid = UUID.randomUUID();
        UUID playerUuid = UUID.randomUUID();

        VillagerProfile profile = new VillagerProfile(
                villagerUuid, "Barnaby", "armorer", "plains",
                List.of("grumpy"), "Obsessed with iron.", "Backstory", 0, 0
        );
        PlayerMemory memory = PlayerMemory.createDefault(villagerUuid, playerUuid, 0);

        List<String> recentTopics = List.of(
                "Wooden Spear bounties are everywhere!",
                "Diamond prices collapsed."
        );

        String prompt = VillagerDialoguePromptBuilder.buildSystemInstruction(
                profile,
                memory,
                "a local merchant",
                null,
                1.0,
                null,
                recentTopics
        );

        assertTrue(prompt.contains("Recently spoken village lines"));
        assertTrue(prompt.contains("Wooden Spear bounties are everywhere!"));
        assertTrue(prompt.contains("Diamond prices collapsed."));
        assertTrue(prompt.contains("DO NOT repeat"));
    }

    @Test
    @DisplayName("Prompt builder injects active trade offers and customer trade history")
    void testPromptBuilderWithTradeOffersAndHistory() {
        UUID villagerUuid = UUID.randomUUID();
        UUID playerUuid = UUID.randomUUID();

        VillagerProfile profile = new VillagerProfile(
                villagerUuid, "Barnaby", "armorer", "plains",
                List.of("grumpy"), "Obsessed with iron.", "Backstory", 0, 0
        );
        PlayerMemory memory = PlayerMemory.createDefault(villagerUuid, playerUuid, 0);

        List<com.reazip.economycraft.gossip.memory.TradeOfferSnapshot> offers = List.of(
                new com.reazip.economycraft.gossip.memory.TradeOfferSnapshot(
                        "Emerald", 15, null, 0, "Diamond Chestplate", 1, false, 8),
                new com.reazip.economycraft.gossip.memory.TradeOfferSnapshot(
                        "Iron Ingot", 24, null, 0, "Emerald", 1, true, 0)
        );

        List<com.reazip.economycraft.gossip.storage.TradeRecord> history = List.of(
                new com.reazip.economycraft.gossip.storage.TradeRecord(
                        1L, villagerUuid, playerUuid, "Iron Helmet", 1, 60L, 1000L),
                new com.reazip.economycraft.gossip.storage.TradeRecord(
                        2L, villagerUuid, playerUuid, "Shield", 1, 40L, 2000L)
        );

        String prompt = VillagerDialoguePromptBuilder.buildSystemInstruction(
                profile,
                memory,
                "a local merchant",
                null,
                1.0,
                null,
                null,
                offers,
                history
        );

        assertTrue(prompt.contains("Your current stall trade inventory & offers:"));
        assertTrue(prompt.contains("Diamond Chestplate for 15x Emerald [In stock]"));
        assertTrue(prompt.contains("Emerald for 24x Iron Ingot [OUT OF STOCK]"));
        assertTrue(prompt.contains("This customer's past purchases at your stall:"));
        assertTrue(prompt.contains("Iron Helmet ($60)"));
        assertTrue(prompt.contains("Shield ($40)"));
    }

    @Test
    @DisplayName("Prompt builder caps large inventory and history to bounded limits")
    void testPromptBuilderCappingLimits() {
        UUID villagerUuid = UUID.randomUUID();
        UUID playerUuid = UUID.randomUUID();

        VillagerProfile profile = new VillagerProfile(
                villagerUuid, "Barnaby", "armorer", "plains",
                List.of("grumpy"), "Obsessed with iron.", "Backstory", 0, 0
        );
        PlayerMemory memory = PlayerMemory.createDefault(villagerUuid, playerUuid, 0);

        // 15 offers
        java.util.List<com.reazip.economycraft.gossip.memory.TradeOfferSnapshot> offers = new java.util.ArrayList<>();
        for (int i = 0; i < 15; i++) {
            offers.add(new com.reazip.economycraft.gossip.memory.TradeOfferSnapshot(
                    "Emerald", 1, null, 0, "Item" + i, 1, false, 10));
        }

        // 20 history records
        java.util.List<com.reazip.economycraft.gossip.storage.TradeRecord> history = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            history.add(new com.reazip.economycraft.gossip.storage.TradeRecord(
                    (long) i, villagerUuid, playerUuid, "OldItem" + i, 1, 10L, 1000L));
        }

        String prompt = VillagerDialoguePromptBuilder.buildSystemInstruction(
                profile,
                memory,
                "a buyer",
                null,
                1.0,
                null,
                null,
                offers,
                history
        );

        // Verify only up to 6 offers and 5 history items are injected
        assertTrue(prompt.contains("Item0"));
        assertTrue(prompt.contains("Item5"));
        assertFalse(prompt.contains("Item6"));

        assertTrue(prompt.contains("OldItem0"));
        assertTrue(prompt.contains("OldItem4"));
        assertFalse(prompt.contains("OldItem5"));
    }
}
