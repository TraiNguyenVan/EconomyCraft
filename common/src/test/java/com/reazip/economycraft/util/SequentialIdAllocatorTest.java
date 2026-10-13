package com.reazip.economycraft.util;

import com.mojang.logging.LogUtils;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Id allocation for the auction and order stores. The branch that matters is the exhausted one:
 * reusing {@link Integer#MAX_VALUE} would overwrite a live entry, so the allocator has to hand back
 * a free id instead of quietly clobbering someone's listing.
 */
class SequentialIdAllocatorTest {

    private static final Logger LOGGER = LogUtils.getLogger();

    @Test
    void freeCandidateIsUsedAsIs() {
        assertEquals(7, SequentialIdAllocator.nextId(7, Map.of(1, "a", 2, "b"), LOGGER, "Auction listing"));
    }

    @Test
    void anyCandidateBelowMaxIsUsedEvenIfTaken() {
        // Only MAX_VALUE is special: stores stop advancing there, so a lower candidate cannot be a
        // stale allocator pointer.
        assertEquals(1, SequentialIdAllocator.nextId(1, Map.of(1, "a"), LOGGER, "Auction listing"));
    }

    @Test
    void exhaustedCandidateFallsBackToAFreeId() {
        Map<Integer, String> existing = new HashMap<>();
        existing.put(Integer.MAX_VALUE, "oldest listing");
        existing.put(1, "a");
        existing.put(2, "b");

        assertEquals(3, SequentialIdAllocator.nextId(Integer.MAX_VALUE, existing, LOGGER, "Auction listing"));
    }

    @Test
    void advanceCapsAtMaxValue() {
        assertEquals(2, SequentialIdAllocator.advance(1));
        assertEquals(Integer.MAX_VALUE, SequentialIdAllocator.advance(Integer.MAX_VALUE));
    }
}