package com.reazip.economycraft.gossip.storage;

import java.util.UUID;

/**
 * Immutable entity representing a completed trade between a player and a villager.
 */
public record TradeRecord(
        long id,
        UUID villagerUuid,
        UUID playerUuid,
        String itemName,
        int itemCount,
        long pricePaid,
        long timestamp
) {
    public String toPromptDescription() {
        if (itemName == null || itemName.isBlank() || itemCount <= 0) return "";

        String name = itemName.trim();
        if (name.chars().anyMatch(Character::isISOControl) || name.length() > 120) return "";

        // Trade capture already stores names such as "3x Emerald" for stacks.
        // Avoid duplicating that quantity while still supporting older/plain names.
        if (itemCount > 1 && !name.matches("(?i)^" + itemCount + "x\\s+.*")) {
            name = itemCount + "x " + name;
        }

        // pricePaid has no currency provenance. Do not expose it to dialogue.
        return name;
    }
}
