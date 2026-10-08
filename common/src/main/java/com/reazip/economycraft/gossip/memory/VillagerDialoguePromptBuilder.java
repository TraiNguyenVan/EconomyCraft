package com.reazip.economycraft.gossip.memory;

import com.reazip.economycraft.gossip.storage.PlayerMemory;
import com.reazip.economycraft.gossip.storage.VillagerProfile;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Builds structured prompts for generating personalized individual villager dialogue.
 */
public final class VillagerDialoguePromptBuilder {
    private VillagerDialoguePromptBuilder() {}

    public static String buildSystemInstruction(
            VillagerProfile profile,
            PlayerMemory memory,
            String playerArchetype,
            double inflation
    ) {
        return buildSystemInstruction(profile, memory, playerArchetype, inflation, null, null);
    }

    public static String buildSystemInstruction(
            VillagerProfile profile,
            PlayerMemory memory,
            String playerArchetype,
            double inflation,
            @Nullable String customInstructions
    ) {
        return buildSystemInstruction(profile, memory, playerArchetype, inflation, customInstructions, null);
    }

    public static String buildSystemInstruction(
            VillagerProfile profile,
            PlayerMemory memory,
            String playerArchetype,
            double inflation,
            @Nullable String customInstructions,
            @Nullable List<String> recentSpokenTopics
    ) {
        return buildSystemInstruction(profile, memory, playerArchetype, inflation, customInstructions, recentSpokenTopics, null, null);
    }

    public static String buildSystemInstruction(
            VillagerProfile profile,
            PlayerMemory memory,
            String playerArchetype,
            double inflation,
            @Nullable String customInstructions,
            @Nullable List<String> recentSpokenTopics,
            @Nullable List<TradeOfferSnapshot> currentOffers,
            @Nullable List<com.reazip.economycraft.gossip.storage.TradeRecord> tradeHistory
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT,
                "You are roleplaying as %s, an individual Minecraft %s villager.\n" +
                "Personality Traits: %s.\n" +
                "Quirk: %s\n" +
                "Backstory: %s\n\n",
                profile.name(),
                profile.profession(),
                String.join(", ", profile.traits()),
                profile.quirk(),
                profile.backstory()
        ));

        sb.append(String.format(Locale.ROOT,
                "Customer visiting your stall: %s.\n" +
                "Your relationship with them: %s (Sentiment: %d/100, Interactions: %d).\n",
                playerArchetype,
                memory.sentimentDescription(),
                memory.sentiment(),
                memory.interactionCount()
        ));

        List<String> promptMemories = memory.recentEvents().stream()
                .filter(event -> "Visited stall".equalsIgnoreCase(event))
                .limit(5)
                .toList();
        if (!promptMemories.isEmpty()) {
            sb.append("Your recent memories with this customer:\n");
            for (String event : promptMemories) {
                sb.append("- ").append(event).append("\n");
            }
        } else if (memory.interactionCount() == 0) {
            sb.append("You have no prior memories with this customer; they are a newcomer to your stall.\n");
        }

        if (recentSpokenTopics != null && !recentSpokenTopics.isEmpty()) {
            sb.append("\nRecently spoken village lines (DO NOT repeat these topics or focus on these exact items):\n");
            for (String line : recentSpokenTopics) {
                sb.append("- \"").append(line).append("\"\n");
            }
        }

        if (currentOffers != null && !currentOffers.isEmpty()) {
            sb.append("\nYour current stall trade inventory & offers:\n");
            int count = 0;
            for (TradeOfferSnapshot offer : currentOffers) {
                if (count++ >= 10) break; // bounded context cap (covers all 10 trades of a Master villager)
                sb.append("- ").append(offer.toPromptDescription()).append("\n");
            }
        }

        List<String> verifiedTradeDetails = new ArrayList<>();
        if (tradeHistory != null) {
            for (com.reazip.economycraft.gossip.storage.TradeRecord trade : tradeHistory) {
                String description = trade.toPromptDescription();
                if (!description.isBlank()) verifiedTradeDetails.add(description);
                if (verifiedTradeDetails.size() >= 5) break;
            }
        }
        if (!verifiedTradeDetails.isEmpty()) {
            sb.append("\nThis customer's past purchases at your stall:\n");
            for (String detail : verifiedTradeDetails) {
                sb.append("- Historical purchase: ").append(detail).append("\n");
            }
        }

        if (promptMemories.isEmpty() && verifiedTradeDetails.isEmpty()) {
            sb.append("\nNo eligible stored interaction or trade details are available. Do not claim a past visit, trade, or relationship.\n");
        }

        sb.append("""
            Dialogue Instructions:
            1. Keep it concise (12 to 25 words). Avoid overly verbose prose, but don't be so brief that you omit item details.
            2. Speak in exactly 1 natural, conversational sentence matching your personality, quirk, and relationship with this player.
            3. MANDATORY SALES PITCH & ITEM AWARENESS: Greet the customer and pitch, mention, or offer a specific item from your CURRENT stall inventory. Historical purchases do not imply current availability.
            4. Item Specificity: Always refer to your actual stock items by name. Do not speak in vague generalities like 'my stock' or 'something'—name a real item you have for sale!
            5. Trade accuracy: Historical purchase details establish only the listed item and quantity. Never infer or state spending, prices paid, or other transaction details from trade records or relationship memories.
            6. Villagers have quirky mannerisms: occasionally mutter or hum ('Hmm...', 'Huh?', 'Haah...'), but vary how you speak and DO NOT start every line with 'Hrmm...'.
            7. Respond strictly with valid JSON with fields:
               {
                 "dialogue": "<your concise line>",
                 "sentiment_delta": <-2 to 5 integer>
               }
            """);

        if (customInstructions != null && !customInstructions.isBlank()) {
            sb.append("\nAdditional Custom Instructions:\n").append(customInstructions.trim()).append("\n");
        }

        return sb.toString();
    }
}
