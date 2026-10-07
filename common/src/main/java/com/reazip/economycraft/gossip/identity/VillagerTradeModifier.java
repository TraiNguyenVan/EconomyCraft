package com.reazip.economycraft.gossip.identity;

import java.util.UUID;

/**
 * Extensible trade pricing modifier interface.
 *
 * <p>Currently operates as a neutral passthrough (1.0x / 0% modifier) as ratified in the
 * architectural specification, preserving economic balance while providing a clean hook for
 * future dynamic reputation discounts or personality haggling.
 */
@FunctionalInterface
public interface VillagerTradeModifier {
    /**
     * Calculates the price multiplier to apply to a villager trade offer.
     *
     * @param villagerUuid the UUID of the selling villager
     * @param playerUuid   the UUID of the buying player
     * @param sentiment    the current player-to-villager sentiment (-100 to +100)
     * @return multiplier to apply to the price (1.0 = standard neutral price)
     */
    double getPriceMultiplier(UUID villagerUuid, UUID playerUuid, int sentiment);

    /**
     * Default neutral passthrough implementation.
     */
    VillagerTradeModifier NEUTRAL = (villagerUuid, playerUuid, sentiment) -> 1.0;
}
