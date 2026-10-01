package com.reazip.economycraft.time;

/**
 * A {@link WallClock} that only moves when a test moves it.
 *
 * <p>Lives in {@code main} rather than a test source set because it is the reason {@link WallClock} exists as an
 * interface, and having it next to the interface documents the contract. Nothing in production constructs one.
 */
public final class MutableClock implements WallClock {

    private long now;

    public MutableClock(long startMillis) {
        this.now = startMillis;
    }

    @Override
    public long millis() {
        return now;
    }

    /** Moves the clock forward. */
    public void advanceMillis(long millis) {
        now += millis;
    }

    /** Moves the clock forward by whole minutes. */
    public void advanceMinutes(long minutes) {
        advanceMillis(minutes * 60_000L);
    }

    /** Moves the clock forward by whole hours. */
    public void advanceHours(long hours) {
        advanceMillis(hours * 3_600_000L);
    }
}