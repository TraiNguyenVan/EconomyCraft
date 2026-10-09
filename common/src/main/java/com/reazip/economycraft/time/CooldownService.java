package com.reazip.economycraft.time;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.logging.LogUtils;
import com.reazip.economycraft.db.Documents;
import com.reazip.economycraft.db.EconomyDatabase;
import org.slf4j.Logger;

import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Per-player, per-key cooldowns on the wall clock (spec §6, §7, §8).
 *
 * <p>This is the second of the three clocks in {@code time/package-info}, and it is the right one for a
 * cooldown: "you may boost a crop again in 4 minutes" has to keep running while the player is offline, which
 * {@link OnlineTimeService} by definition does not do. Every consumer in this codebase shares one instance
 * rather than keeping its own {@code Map<String, Long>} — the key is namespaced per job, so a Builder's Haste
 * cooldown cannot collide with a Farmer's crop cooldown, and the shared save file means a crash cannot cost a
 * player a cooldown he was waiting out.
 *
 * <p>Times are absolute epoch milliseconds, which is what makes a restart harmless: a cooldown set before the
 * shutdown is still correctly in the future afterwards.
 *
 * <p>The clock is injected so tests can move time without sleeping.
 */
public final class CooldownService {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    @SuppressWarnings("unchecked")
    private static final Type TYPE = new TypeToken<Map<UUID, Map<String, Long>>>() { }.getType();

    private final Path file;
    private final EconomyDatabase db;
    private final Map<UUID, Map<String, Long>> expiries = new HashMap<>();
    private final WallClock clock;
    private boolean dirty;

    public CooldownService(Path file) {
        this(null, file, WallClock.SYSTEM);
    }

    public CooldownService(Path file, WallClock clock) {
        this(null, file, clock);
    }

    /** Production constructor: persists to the shared database document instead of the file. */
    public CooldownService(EconomyDatabase db, Path file) {
        this(db, file, WallClock.SYSTEM);
    }

    public CooldownService(EconomyDatabase db, Path file, WallClock clock) {
        this.db = db;
        this.file = file;
        this.clock = clock;
        load();
    }

    /**
     * Whether a cooldown is not running.
     *
     * <p>Expired entries are dropped on read. They are never useful again, and a lazy sweep keeps the save file
     * from growing one dead entry per cooldown ever used.
     */
    public boolean isReady(UUID player, String key) {
        if (player == null || key == null) return true;
        Map<String, Long> perPlayer = expiries.get(player);
        if (perPlayer == null) return true;

        Long expiry = perPlayer.get(key);
        if (expiry == null) return true;

        if (expiry <= clock.millis()) {
            perPlayer.remove(key);
            if (perPlayer.isEmpty()) expiries.remove(player);
            dirty = true;
            return true;
        }
        return false;
    }

    /** Starts a cooldown, replacing any existing one for the same key rather than extending or stacking it. */
    public void start(UUID player, String key, long durationMillis) {
        if (player == null || key == null || durationMillis <= 0) return;
        expiries.computeIfAbsent(player, k -> new HashMap<>()).put(key, clock.millis() + durationMillis);
        dirty = true;
    }

    /**
     * Starts a cooldown in minutes — the unit every spec cooldown is written in (4, 5 and 5 minutes).
     *
     * <p>A duration of zero starts nothing, which is the way an admin disables a cooldown by setting it to 0
     * without any special case at every call site.
     */
    public void startMinutes(UUID player, String key, long minutes) {
        if (minutes <= 0) return;
        start(player, key, minutes * 60_000L);
    }

    /** Milliseconds left on a cooldown; 0 when it is not running or has already expired. */
    public long remainingMillis(UUID player, String key) {
        if (player == null || key == null) return 0L;
        Map<String, Long> perPlayer = expiries.get(player);
        if (perPlayer == null) return 0L;

        Long expiry = perPlayer.get(key);
        if (expiry == null) return 0L;

        long remaining = expiry - clock.millis();
        return Math.max(0L, remaining);
    }

    /** Seconds left on a cooldown, rounded up, for a countdown in chat. */
    public long remainingSeconds(UUID player, String key) {
        return (remainingMillis(player, key) + 999L) / 1000L;
    }

    /** Clears one cooldown, e.g. when an admin resets a player. */
    public void clear(UUID player, String key) {
        if (player == null || key == null) return;
        Map<String, Long> perPlayer = expiries.get(player);
        if (perPlayer == null) return;

        perPlayer.remove(key);
        if (perPlayer.isEmpty()) expiries.remove(player);
        dirty = true;
    }

    /** Clears everything for one player. */
    public void clearAll(UUID player) {
        if (player != null && expiries.remove(player) != null) {
            dirty = true;
        }
    }

    /** Writes pending changes if anything changed since the last write. */
    public void flush() {
        if (!dirty) return;
        dirty = false;
        Documents.write(db, file, "cooldowns.json", GSON.toJson(new HashMap<>(expiries), TYPE));
    }

    private void load() {
        String json = Documents.read(db, file, "cooldowns.json");
        if (json == null) return;
        try {
            Map<UUID, Map<String, Long>> loaded = GSON.fromJson(json, TYPE);
            if (loaded == null) return;

            for (Map.Entry<UUID, Map<String, Long>> entry : loaded.entrySet()) {
                Map<String, Long> perPlayer = entry.getValue();
                if (entry.getKey() == null || perPlayer == null || perPlayer.isEmpty()) continue;

                Map<String, Long> cleaned = new HashMap<>();
                for (Map.Entry<String, Long> cooldown : perPlayer.entrySet()) {
                    if (cooldown.getKey() != null && cooldown.getValue() != null) {
                        cleaned.put(cooldown.getKey(), cooldown.getValue());
                    }
                }
                if (!cleaned.isEmpty()) expiries.put(entry.getKey(), cleaned);
            }
        } catch (Exception ex) {
            LOGGER.error("[EconomyCraft] Failed to read {}; cooldowns will be treated as ready.", file, ex);
        }
    }
}