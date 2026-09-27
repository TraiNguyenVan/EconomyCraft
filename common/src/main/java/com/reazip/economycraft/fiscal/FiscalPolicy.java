package com.reazip.economycraft.fiscal;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Pure money-policy math for the daily fiscal pass. No Minecraft types, no I/O: every
 * method takes the observed state as arguments and returns a decision, so the policy can
 * be unit tested without a server.
 */
public final class FiscalPolicy {
    public static final double MAX_RATE = 1.0;

    private FiscalPolicy() {}

    /**
     * Median of a set of balances. Mirrors the statistic the dynamic price engine uses so
     * the two policies can never drift apart. Null-safe; empty input yields 0.
     */
    public static double median(List<Long> balances) {
        if (balances == null || balances.isEmpty()) return 0.0;

        List<Long> sorted = new ArrayList<>(balances.size());
        for (Long balance : balances) {
            if (balance != null) sorted.add(balance);
        }
        if (sorted.isEmpty()) return 0.0;

        sorted.sort(Long::compareTo);
        int n = sorted.size();
        if (n % 2 == 1) return sorted.get(n / 2);
        return (sorted.get(n / 2 - 1) + (double) sorted.get(n / 2)) / 2.0;
    }

    /**
     * The exempt threshold every player is measured against. Acts as both the tax floor and
     * the rebate ceiling: balances at or below it are never taxed, and are the only ones
     * eligible for a rebate.
     *
     * <p>{@code medianFloorFactor} dials who the policy reaches. 0 anchors the floor to the
     * absolute baseline and taxes the middle of the distribution too; 1 anchors it to the
     * median, which makes the median a fixed point that can never fall.
     */
    public static long floor(double medianBalance, long absoluteFloor, double medianFloorFactor) {
        double scaled = medianBalance <= 0 || medianFloorFactor <= 0
                ? 0
                : medianBalance * medianFloorFactor;

        long medianFloor = scaled >= Long.MAX_VALUE ? Long.MAX_VALUE : Math.round(scaled);
        return Math.max(Math.max(0L, absoluteFloor), medianFloor);
    }

    /**
     * Base rate, raised for players who have not been seen recently. An activity tier keeps
     * the policy off active players, whose income is already bounded by the sell cap, and
     * concentrates the drain on idle stock.
     *
     * @param lastSeenMs epoch millis of the player's last login, or 0 when never seen
     */
    public static double rateFor(long lastSeenMs, long nowMs, int inactiveDays,
                                 double baseRate, double idleMultiplier) {
        double rate = baseRate;

        if (inactiveDays > 0 && idleMultiplier > 1.0) {
            boolean neverSeen = lastSeenMs <= 0;
            boolean stale = nowMs > 0 && lastSeenMs > 0
                    && nowMs - lastSeenMs > TimeUnit.DAYS.toMillis(inactiveDays);
            if (neverSeen || stale) rate = baseRate * idleMultiplier;
        }

        return Math.clamp(rate, 0.0, MAX_RATE);
    }

    /** Tax owed on a balance. Never exceeds the surplus, so a balance can never go below the floor. */
    public static long taxFor(long balance, long floor, double rate) {
        if (balance <= floor || rate <= 0) return 0;

        long surplus = balance - floor;
        double raw = surplus * Math.clamp(rate, 0.0, MAX_RATE);
        long tax = raw >= Long.MAX_VALUE ? Long.MAX_VALUE : Math.round(raw);

        return Math.max(0L, Math.min(tax, surplus));
    }

    /** Rebate owed on a balance below the floor. Never exceeds the shortfall. */
    public static long rebateFor(long balance, long floor, double rate) {
        if (balance >= floor || rate <= 0) return 0;

        long shortfall = floor - balance;
        double raw = shortfall * Math.clamp(rate, 0.0, MAX_RATE);
        long rebate = raw >= Long.MAX_VALUE ? Long.MAX_VALUE : Math.round(raw);

        return Math.max(0L, Math.min(rebate, shortfall));
    }

    /**
     * Whether the injection side is armed. Gated on the aggregate, not on individual
     * balances: with a healthy median the floor sits above the poorest players, so a
     * per-player test alone would pay out during a perfectly healthy economy.
     */
    public static boolean rebateTriggered(double medianBalance, long reference, double triggerFactor) {
        if (medianBalance <= 0 || reference <= 0 || triggerFactor <= 0) return false;
        return medianBalance < reference * triggerFactor;
    }

    /**
     * Days of policy to apply for an elapsed gap. Follows the codebase convention where 0
     * means unlimited, and reports when the cap clipped so the caller can warn.
     */
    public record Accrual(int days, boolean clipped) {}

    public static Accrual accrual(long lastFiscalDay, long today, int maxCatchupDays) {
        if (lastFiscalDay < 0 || today <= lastFiscalDay) return new Accrual(0, false);

        long elapsed = today - lastFiscalDay;
        int cap = Math.max(0, maxCatchupDays);

        if (cap > 0 && elapsed > cap) return new Accrual(cap, true);
        if (elapsed > Integer.MAX_VALUE) return new Accrual(Integer.MAX_VALUE, true);

        return new Accrual((int) elapsed, false);
    }
}
