package com.reazip.economycraft.time;

import com.mojang.logging.LogUtils;
import com.reazip.economycraft.util.UuidLongMapStore;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Accumulated <em>online</em> time, in milliseconds, per player (spec §9, §14).
 *
 * <p>This is the {@code time} clock of the three in {@code time/package-info}, and it is the one the 45-minute
 * thresholds actually read: the Communism {@code Đảng phí} and the {@code Lụt nghề} rust timer are both
 * "45 minutes online", not "45 minutes of wall clock". A player who plays for two hours and idles offline for
 * a day has 2 hours here, which is the whole reason this class exists separately from
 * {@link CooldownService}.
 *
 * <h2>How time is counted</h2>
 *
 * <p>From the tick count, not from {@link System#currentTimeMillis()}. A server tick is a fixed 50 ms of game
 * time, so {@code (tickCount - lastTick) * 50} is the online time that elapsed between two ticks — and it stops
 * counting the moment the server stops, without any special-casing of pause, sleep or a crashed process.
 *
 * <p>Three rules keep that honest:
 * <ul>
 *   <li>A player is credited for the interval only if they were already online when it began. Joining is
 *       recorded, not credited, so nobody is paid for time before they arrived.</li>
 *   <li>Leaving is not a special case: the entry simply stops being updated, and it is dropped on the next
 *       tick. There is no quit event to miss.</li>
 *   <li>A single interval is capped at {@link #MAX_INTERVAL_MILLIS}. Without it, a server that stalls for an
 *       hour would hand every online player an hour of "online" time the moment it resumed.</li>
 * </ul>
 *
 * <p>Because the service is told the tick count and the online set rather than asking the server for them, it
 * holds no {@code MinecraftServer} reference and is unit-testable without bootstrapping one.
 */
public final class OnlineTimeService {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** One server tick of wall time. Fixed by Minecraft, not by us. */
    public static final long MILLIS_PER_TICK = 50L;

    /** The longest single interval that may be credited, to survive a stall without inventing online time. */
    public static final long MAX_INTERVAL_MILLIS = 6L * 60L * 60L * 1000L;

    private final Path file;
    private final Map<UUID, Long> totalsMillis = new HashMap<>();
    /** Tick at which each currently-online player's current interval began. */
    private final Map<UUID, Long> intervalStartTicks = new HashMap<>();

    private long currentTick = -1L;
    private long lastIntervalMillis;
    private boolean dirty;
    /** Caps hit so far, only so a long stall cannot spam the log with one warning per tick. */
    private int cappedIntervals;

    public OnlineTimeService(Path file) {
        this.file = file;
        UuidLongMapStore.load(file, totalsMillis);
        dirty = false;
    }

    /**
     * Advances one tick.
     *
     * @param tickCount the server's current tick count; must increase monotonically
     * @param online    the UUIDs of every player online right now
     */
    public void tick(long tickCount, Set<UUID> online) {
        lastIntervalMillis = 0L;

        if (online == null || online.isEmpty()) {
            // Still advance the clock: otherwise the first player back online would be credited for the whole
            // outage, capped or not.
            currentTick = tickCount;
            intervalStartTicks.clear();
            return;
        }

        if (currentTick < 0) {
            for (UUID player : online) intervalStartTicks.put(player, tickCount);
            currentTick = tickCount;
            return;
        }

        long rawMillis = (tickCount - currentTick) * MILLIS_PER_TICK;
        long elapsedMillis = Math.min(rawMillis, MAX_INTERVAL_MILLIS);
        if (rawMillis > MAX_INTERVAL_MILLIS && cappedIntervals++ < 3) {
            LOGGER.warn("[EconomyCraft] A {} ms tick gap exceeded the {} ms online-time cap; the extra time was " +
                            "not credited. Further gaps in the same stall will not be logged.",
                    rawMillis, MAX_INTERVAL_MILLIS);
        }
        if (elapsedMillis > 0) {
            for (UUID player : online) {
                if (intervalStartTicks.containsKey(player)) {
                    totalsMillis.merge(player, elapsedMillis, Long::sum);
                    dirty = true;
                }
                intervalStartTicks.put(player, tickCount);
            }
        }

        intervalStartTicks.keySet().retainAll(online);
        currentTick = tickCount;
        lastIntervalMillis = elapsedMillis;
    }

    /**
     * Milliseconds credited to each continuously-online player by the most recent {@link #tick}.
     *
     * <p>Exposed so a consumer that tracks a sub-threshold of the same clock — {@code ProfessionStore}'s rust
     * timer — can advance by exactly what was credited, instead of opening a second, slightly different notion
     * of online time.
     */
    public long lastCreditedIntervalMillis() {
        return lastIntervalMillis;
    }

    /** Total online time in milliseconds; 0 for a player who has never been seen. */
    public long totalMillis(UUID player) {
        Long total = totalsMillis.get(player);
        return total == null ? 0L : total;
    }

    /** Total online time in whole minutes, which is the unit every spec threshold is written in. */
    public long totalMinutes(UUID player) {
        return totalMillis(player) / 60_000L;
    }

    /**
     * How far along the online-time threshold a player is, clamped to 0–1.
     *
     * <p>Used by {@code /tag} to show progress towards the next level or rust threshold, so a player can see
     * why nothing has happened yet instead of guessing.
     */
    public double progressToward(UUID player, long thresholdMillis) {
        if (thresholdMillis <= 0) return 1.0;
        return Math.min(1.0, (double) totalMillis(player) / (double) thresholdMillis);
    }

    /**
     * Consumes the accumulated time if the threshold has been reached, resetting the counter to zero.
     *
     * <p>The comparison is {@code >=}, not {@code >}: a player sitting on exactly 45:00 has met a "45 minutes"
     * threshold, and the boundary case is the one a player will actually notice.
     *
     * @return {@code true} if the threshold was met and the counter was reset
     */
    public boolean consumeIfThresholdMet(UUID player, long thresholdMillis) {
        if (thresholdMillis <= 0 || totalMillis(player) < thresholdMillis) return false;

        totalsMillis.put(player, 0L);
        dirty = true;
        return true;
    }

    /**
     * Adds time to a player's total directly, bypassing the tick clock.
     *
     * <p>Only for migration and admin tooling — a backfill from another plugin, or crediting a player for time
     * the counter missed. Normal accrual goes through {@link #tick}, which is the only thing that knows who is
     * actually online; anything else would let time appear for a player who is not there.
     */
    public void addOnlineTime(UUID player, long millis) {
        if (player == null || millis <= 0) return;
        totalsMillis.merge(player, millis, Long::sum);
        dirty = true;
    }

    /** Clears a player's total, e.g. when an admin resets their progression. */
    public void reset(UUID player) {
        if (totalsMillis.remove(player) != null) {
            dirty = true;
        }
    }

    /** Drops a player's entry entirely, e.g. on logout, so the file does not grow without bound. */
    public void forget(UUID player) {
        intervalStartTicks.remove(player);
        if (totalsMillis.remove(player) != null) {
            dirty = true;
        }
    }

    /** Writes pending changes if anything changed since the last write. */
    public void flush() {
        if (!dirty) return;
        dirty = false;
        UuidLongMapStore.persist(file, totalsMillis);
    }

    /** Visible for tests: the tick the service last saw. */
    long lastTick() {
        return currentTick;
    }

    /** Visible for tests: how many players are mid-interval. */
    int trackedOnlineCount() {
        return intervalStartTicks.size();
    }
}