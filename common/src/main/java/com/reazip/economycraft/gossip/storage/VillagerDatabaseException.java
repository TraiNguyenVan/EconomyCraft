package com.reazip.economycraft.gossip.storage;

/**
 * Raised when a villager database operation fails.
 *
 * <p>Read failures are reported through this exception instead of an empty result, so callers can
 * tell "the row does not exist" apart from "the row could not be read". Treating a failed read as a
 * missing row would let a caller create a replacement profile over data it never saw.
 */
public class VillagerDatabaseException extends RuntimeException {
    public VillagerDatabaseException(String message) {
        super(message);
    }

    public VillagerDatabaseException(String message, Throwable cause) {
        super(message, cause);
    }
}
