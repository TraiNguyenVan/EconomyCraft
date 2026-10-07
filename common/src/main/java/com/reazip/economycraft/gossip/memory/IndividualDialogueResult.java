package com.reazip.economycraft.gossip.memory;

/**
 * Result of an individual villager's personalized dialogue generation.
 */
public record IndividualDialogueResult(
        String dialogue,
        int sentimentDelta
) {
    public IndividualDialogueResult {
        dialogue = (dialogue == null || dialogue.isBlank()) ? "" : dialogue.trim();
        sentimentDelta = Math.clamp(sentimentDelta, -10, 10);
    }

    public static IndividualDialogueResult empty() {
        return new IndividualDialogueResult("", 0);
    }

    public boolean isEmpty() {
        return dialogue.isEmpty();
    }
}
