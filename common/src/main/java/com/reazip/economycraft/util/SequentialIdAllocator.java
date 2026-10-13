package com.reazip.economycraft.util;

import org.slf4j.Logger;

import java.util.Map;

public final class SequentialIdAllocator {
    private SequentialIdAllocator() {}

    /**
     * Returns an id that is not in {@code existing}. The candidate is only a hint: restoring a
     * cancelled listing or request re-inserts an entry without advancing the store's counter, so
     * the candidate can already be taken. Handing that id back would overwrite the live entry, so
     * an occupied candidate falls through to a scan.
     */
    public static int nextId(int candidate, Map<Integer, ?> existing, Logger logger, String what) {
        if (!existing.containsKey(candidate)) {
            return candidate;
        }
        logger.error("[EconomyCraft] {} id {} is already taken; scanning for a free id instead of overwriting the existing entry.", what, candidate);
        for (int probe = 1; probe < Integer.MAX_VALUE; probe++) {
            if (!existing.containsKey(probe)) return probe;
        }
        throw new IllegalStateException("No free " + what + " id available; the id space is completely full.");
    }

    public static int advance(int id) {
        return id < Integer.MAX_VALUE ? id + 1 : Integer.MAX_VALUE;
    }
}
