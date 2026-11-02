package com.reazip.economycraft.shop;

import com.reazip.economycraft.db.EconomyDatabase;

import java.util.Objects;

/**
 * Persistent stock supplied through player sales for the server shop.
 *
 * <p>Paid prices are retained as a compact, time-bucketed history so {@link #highWater(String,
 * long)} can determine a rolling maximum even after an older, more expensive sale expires.
 */
public final class PlayerShopStock {
    private final EconomyDatabase database;

    public PlayerShopStock(EconomyDatabase database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    public long get(String key) {
        return database.getPlayerShopStock(requireKey(key));
    }

    public void deposit(String key, long count, long paidUnit, long atMillis) {
        database.depositPlayerShopStock(requireKey(key), count, paidUnit, atMillis);
    }

    /** Removes and returns up to {@code amount} units. */
    public long withdraw(String key, long amount) {
        return database.withdrawPlayerShopStock(requireKey(key), amount);
    }

    public void set(String key, long count) {
        database.setPlayerShopStock(requireKey(key), count);
    }

    public void clear(String key) {
        database.clearPlayerShopStock(requireKey(key));
    }

    /**
     * Returns the maximum paid unit price at or after {@code cutoffMillis}, pruning older paid
     * prices first. Returns zero when no qualifying player sale remains.
     */
    public long highWater(String key, long cutoffMillis) {
        return database.playerShopHighWater(requireKey(key), cutoffMillis);
    }

    private static String requireKey(String key) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("key must not be blank");
        return key;
    }
}
