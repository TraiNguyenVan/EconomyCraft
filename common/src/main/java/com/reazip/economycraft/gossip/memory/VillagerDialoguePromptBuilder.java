package com.reazip.economycraft.gossip.memory;

import com.reazip.economycraft.gossip.storage.PlayerMemory;
import com.reazip.economycraft.gossip.storage.VillagerProfile;
import org.jetbrains.annotations.Nullable;

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
            @Nullable List<String> grapevineRumors,
            double inflation
    ) {
        return buildSystemInstruction(profile, memory, playerArchetype, grapevineRumors, inflation, null, null);
    }

    public static String buildSystemInstruction(
            VillagerProfile profile,
            PlayerMemory memory,
            String playerArchetype,
            @Nullable List<String> grapevineRumors,
            double inflation,
            @Nullable String customInstructions
    ) {
        return buildSystemInstruction(profile, memory, playerArchetype, grapevineRumors, inflation, customInstructions, null);
    }

    public static String buildSystemInstruction(
            VillagerProfile profile,
            PlayerMemory memory,
            String playerArchetype,
            @Nullable List<String> grapevineRumors,
            double inflation,
            @Nullable String customInstructions,
            @Nullable List<String> recentSpokenTopics
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
                "Your relationship with them: %s (Sentiment: %d/100, Interactions: %d, Total spent: $%d).\n",
                playerArchetype,
                memory.sentimentDescription(),
                memory.sentiment(),
                memory.interactionCount(),
                memory.totalSpent()
        ));

        if (!memory.recentEvents().isEmpty()) {
            sb.append("Your recent memories with this customer:\n");
            for (String event : memory.recentEvents()) {
                sb.append("- ").append(event).append("\n");
            }
        } else {
            sb.append("You have no prior memories with this customer; they are a newcomer to your stall.\n");
        }

        if (grapevineRumors != null && !grapevineRumors.isEmpty()) {
            sb.append(String.format(Locale.ROOT, "\nWord from your fellow %ss across the realm:\n", profile.profession()));
            for (String rumor : grapevineRumors) {
                sb.append("- ").append(rumor).append("\n");
            }
        }

        if (recentSpokenTopics != null && !recentSpokenTopics.isEmpty()) {
            sb.append("\nRecently spoken village lines (DO NOT repeat these topics or focus on these exact items):\n");
            for (String line : recentSpokenTopics) {
                sb.append("- \"").append(line).append("\"\n");
            }
        }

        sb.append(String.format(Locale.ROOT, "\nCurrent server inflation: %.2fx.\n\n", inflation));

        if (customInstructions != null && !customInstructions.isBlank()) {
            sb.append(customInstructions.trim()).append("\n");
        } else {
            sb.append("""
                Dialogue Instructions:
                1. Keep it short and easy to understand: most lines should be under 15 words. Avoid overly complex prose or purple vocabulary.
                2. Speak in exactly 1 concise, conversational sentence matching your personality, quirk, and relationship with this player.
                3. Topic Rotation: Rotate your angle — comment on your backstory/quirk, trade prices, inflation, stall inventory shortages, or relationship with this customer. Do not fixate on the same trade item every time.
                4. Address the player or your past memories directly when appropriate.
                5. Villagers have quirky mannerisms: occasionally mutter or hum ('Hmm...', 'Huh?', 'Haah...'), but vary how you speak and DO NOT start every line with 'Hrmm...'.
                6. Respond strictly with valid JSON with fields:
                   {
                     "dialogue": "<your concise line>",
                     "sentiment_delta": <-2 to 5 integer>
                   }
                """);
        }

        return sb.toString();
    }
}
