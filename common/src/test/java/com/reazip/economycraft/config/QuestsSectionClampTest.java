package com.reazip.economycraft.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A mistyped quests key must degrade to a usable board with a warning, never to a broken one —
 * and the shipped defaults must already be the clamped values (BundledConfigTest asserts that
 * separately, from the file side).
 */
class QuestsSectionClampTest {

    @Test
    void garbageDegradesToUsableBoard() {
        QuestsSection quests = new QuestsSection();
        quests.periodDays = 0;
        quests.weeklyBudget = -5L;
        quests.priceFactor = 2.0;
        quests.weeklyCount = 0;
        quests.maxConcurrent = -1;
        quests.minQuestUnit = 50L;
        quests.maxQuestUnit = 10L;
        quests.sellFallbackMultiplier = 0.0;
        quests.blacklist = null;
        quests.botName = "   ";
        quests.repriceThresholdPercent = -0.5;
        quests.maxUnsoldExpiries = 0;
        quests.maxPerCategory = 0;
        quests.categoryWeights = null;
        quests.buyback.priceFactor = -1.0;

        quests.clamp();

        assertEquals(1, quests.periodDays);
        assertEquals(0L, quests.weeklyBudget);
        assertEquals(1.0, quests.priceFactor);
        assertEquals(1, quests.weeklyCount);
        assertEquals(1, quests.maxConcurrent);
        assertEquals(50L, quests.minQuestUnit);
        assertEquals(50L, quests.maxQuestUnit);
        assertEquals(3.3, quests.sellFallbackMultiplier);
        assertEquals(List.of(), quests.blacklist);
        assertEquals(0.0, quests.repriceThresholdPercent);
        assertEquals(1, quests.maxUnsoldExpiries);
        assertEquals(1, quests.maxPerCategory);
        assertEquals(QuestsSection.defaultCategoryWeights(), quests.categoryWeights);
        assertEquals("Server Quests", quests.botName);
        assertEquals(0.0, quests.buyback.priceFactor);
    }

    @Test
    void shippedDefaultsAreAlreadyClamped() {
        QuestsSection quests = new QuestsSection();
        quests.clamp();

        assertTrue(quests.enabled);
        assertEquals(7, quests.periodDays);
        assertEquals(12_000L, quests.weeklyBudget);
        assertEquals(0.5, quests.priceFactor);
        assertEquals(10, quests.weeklyCount);
        assertEquals(10, quests.maxConcurrent);
        assertEquals(0.10, quests.repriceThresholdPercent);
        assertEquals(1, quests.maxUnsoldExpiries);
        assertEquals(2, quests.maxPerCategory);
        assertEquals(QuestsSection.defaultCategoryWeights(), quests.categoryWeights);
        assertTrue(quests.buyback.enabled);
        assertEquals(0.8, quests.buyback.priceFactor);
    }
}
