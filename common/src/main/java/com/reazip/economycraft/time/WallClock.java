package com.reazip.economycraft.time;

/**
 * The wall clock, as an interface so tests can move time without sleeping.
 *
 * <p>Of the three clocks in this package, this is the one that keeps running while nobody is playing — see
 * {@code package-info}. Every service that needs it takes one in its constructor and defaults to
 * {@link System#currentTimeMillis()}.
 *
 * <p>{@link MutableClock} exists for tests; production code always uses {@code WallClock.SYSTEM}.
 */
@FunctionalInterface
public interface WallClock {

    /** Milliseconds since the epoch. */
    long millis();

    /** The real clock. */
    WallClock SYSTEM = System::currentTimeMillis;
}