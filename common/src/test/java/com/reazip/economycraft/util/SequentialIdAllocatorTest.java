package com.reazip.economycraft.util;

import com.mojang.logging.LogUtils;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Id allocation for the auction and order stores. The contract is the important part: whatever the
 * candidate is, the returned id is never one that is already in use, so a caller's map insertion
 * cannot clobber a live listing, order or contract.
 */
class SequentialIdAllocatorTest {

    private static final Logger LOGGER = LogUtils.getLogger();

    @Test
    void freeCandidateIsUsedAsIs() {
        assertEquals(7, SequentialIdAllocator.nextId(7, Map.of(1, "a", 2, "b"), LOGGER, "Auction listing"));
    }

    @Test
    void occupiedCandidateIsNeverHandedBack() {
        // Restoring a cancelled entry re-inserts it without advancing the store's counter, so the
        // candidate can already be live. Returning it would overwrite that entry.
        Map<Integer, String> existing = new HashMap<>();
        existing.put(1, "live listing");

        assertEquals(2, SequentialIdAllocator.nextId(1, existing, LOGGER, "Auction listing"));
        assertEquals(1, existing.size(), "the existing entry must survive the allocation");
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