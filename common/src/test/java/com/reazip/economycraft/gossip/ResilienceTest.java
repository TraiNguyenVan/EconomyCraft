package com.reazip.economycraft.gossip;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Resilience & Silent Degradation Tests")
class ResilienceTest {

    @Test
    @DisplayName("VillagerGossipListener passesChance respects boundary conditions and probability")
    void testChanceGuard() {
        assertFalse(VillagerGossipListener.passesChance(0.0, 0.0));
        assertFalse(VillagerGossipListener.passesChance(0.5, 0.0));
        assertFalse(VillagerGossipListener.passesChance(0.0, -0.2));

        assertTrue(VillagerGossipListener.passesChance(0.0, 1.0));
        assertTrue(VillagerGossipListener.passesChance(0.999, 1.0));
        assertTrue(VillagerGossipListener.passesChance(0.5, 1.5));

        assertTrue(VillagerGossipListener.passesChance(0.24, 0.25));
        assertFalse(VillagerGossipListener.passesChance(0.25, 0.25));
        assertFalse(VillagerGossipListener.passesChance(0.26, 0.25));

        assertTrue(VillagerGossipListener.passesChance(0.49, 0.5));
        assertFalse(VillagerGossipListener.passesChance(0.50, 0.5));
    }
}