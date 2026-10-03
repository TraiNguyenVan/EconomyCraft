package com.reazip.economycraft.faction;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D14 requires a charge message that states every factor, so that any number in the log can be explained
 * without reading the source. These tests assert the sentence itself, not just the arithmetic behind it —
 * a breakdown that computes correctly but prints two of its six factors fails the decision.
 */
class RateBreakdownTest {

    private static RateBreakdown capitalism(double applied) {
        return new RateBreakdown("Capitalism", 0.05, 1.20, 0.225, 0.15, 1.50, 0.09, applied);
    }

    @Test
    void everyFactorAppearsInTheSentence() {
        String text = capitalism(0.09).describe();
        assertTrue(text.contains("base 5.00%"), text);
        assertTrue(text.contains("inflation 1.20"), text);
        assertTrue(text.contains("concentration 1.50"), text);
        assertTrue(text.contains("wealth share 22.50%"), text);
        assertTrue(text.contains("15.00% reference"), text);
        assertTrue(text.contains("= 9.00%"), text);
        assertTrue(text.startsWith("Capitalism"), text);
    }

    @Test
    void anUnclampedRateDoesNotMentionTheClamp() {
        assertFalse(capitalism(0.09).clampBound());
        assertFalse(capitalism(0.09).describe().contains("clamped"));
    }

    @Test
    void aClampedRateShowsBothTheFormulaAndTheResult() {
        // The formula said 9 %, the clamp allowed 4 % more from 5 %. Both numbers have to be visible:
        // "the rate did not move as much as the formula says" is the thing an admin needs to see.
        RateBreakdown clamped = capitalism(0.04);
        assertTrue(clamped.clampBound());
        String text = clamped.describe();
        assertTrue(text.contains("= 9.00%"), text);
        assertTrue(text.contains("clamped to 4.00%"), text);
        assertTrue(text.contains("max 5.00%/day"), text);
    }

    @Test
    void theTwoPartiesAreLabelledDifferently() {
        RateBreakdown monarchy = new RateBreakdown("Monarchy", 0.017, 2.00, 0.10, 0.15, 0.67, 0.0228, 0.0228);
        String text = monarchy.describe();
        assertTrue(text.startsWith("Monarchy"), text);
        assertTrue(text.contains("base 1.70%"), text);
        assertTrue(text.contains("inflation 2.00"), text);
        assertFalse(text.contains("clamped"), text);
    }
}
