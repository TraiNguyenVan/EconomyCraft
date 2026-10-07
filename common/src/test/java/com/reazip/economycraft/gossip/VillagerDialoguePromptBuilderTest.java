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
}
