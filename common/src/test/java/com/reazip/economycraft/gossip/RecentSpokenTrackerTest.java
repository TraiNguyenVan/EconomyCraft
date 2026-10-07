package com.reazip.economycraft.gossip;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RecentSpokenTracker Unit Tests")
class RecentSpokenTrackerTest {

    @Test
    @DisplayName("RecentSpokenTracker enforces capacity and discards oldest entries")
    void testCapacityRollover() {
        RecentSpokenTracker tracker = new RecentSpokenTracker(3);
        tracker.recordSpoken("Line 1");
        tracker.recordSpoken("Line 2");
        tracker.recordSpoken("Line 3");

        assertEquals(3, tracker.size());
        assertEquals(List.of("Line 1", "Line 2", "Line 3"), tracker.getRecentSpoken());

        tracker.recordSpoken("Line 4");
        assertEquals(3, tracker.size());
        assertEquals(List.of("Line 2", "Line 3", "Line 4"), tracker.getRecentSpoken());
    }

    @Test
    @DisplayName("RecentSpokenTracker strips bracket prefix and avoids consecutive duplicates")
    void testFormattingAndDuplicateSuppression() {
        RecentSpokenTracker tracker = new RecentSpokenTracker(5);
        tracker.recordSpoken("[FARMER] Wheat prices are falling fast!");
        tracker.recordSpoken("[FARMER] Wheat prices are falling fast!"); // Consecutive duplicate ignored

        assertEquals(1, tracker.size());
        assertEquals("Wheat prices are falling fast!", tracker.getRecentSpoken().get(0));

        tracker.recordSpoken("[BLACKSMITH] Iron supply is running thin.");
        assertEquals(2, tracker.size());
        assertEquals("Iron supply is running thin.", tracker.getRecentSpoken().get(1));
    }

    @Test
    @DisplayName("RecentSpokenTracker ignores null or blank messages")
    void testNullAndBlankIgnored() {
        RecentSpokenTracker tracker = new RecentSpokenTracker(5);
        tracker.recordSpoken(null);
        tracker.recordSpoken("");
        tracker.recordSpoken("   ");

        assertEquals(0, tracker.size());
        assertTrue(tracker.getRecentSpoken().isEmpty());
    }

    @Test
    @DisplayName("RecentSpokenTracker clear resets state")
    void testClear() {
        RecentSpokenTracker tracker = new RecentSpokenTracker(5);
        tracker.recordSpoken("Test line");
        assertEquals(1, tracker.size());

        tracker.clear();
        assertEquals(0, tracker.size());
        assertTrue(tracker.getRecentSpoken().isEmpty());
    }
}
