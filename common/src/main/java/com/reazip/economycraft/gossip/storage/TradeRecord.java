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
        StringBuilder sb = new StringBuilder();
        if (itemCount > 1) {
            sb.append(itemCount).append("x ");
        }
        sb.append(itemName);
        if (pricePaid > 0) {
            sb.append(" ($").append(pricePaid).append(")");
        }
        return sb.toString();
    }
}
