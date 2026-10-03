package com.reazip.economycraft.faction;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.logging.LogUtils;
import com.reazip.economycraft.time.WallClock;
import com.reazip.economycraft.util.AsyncFileWriter;
import org.slf4j.Logger;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Which party each player belongs to, and when they chose it (spec §1).
 *
 * <p>Two rules from D17 are the reason this class is shaped the way it is:
 *
 * <ol>
 *   <li><strong>No record until a real choice.</strong> {@link #factionOf(UUID)} answers
 *       {@link FactionId#defaultFaction()} for a player who has never chosen, but nothing is written for them.
 *       The distinction matters: if the default were persisted on first sight, then "chose Anarchism" and
 *       "never chose" would become the same row, and every future party — including ones added after this
 *       phase — would silently claim players who never opted in.</li>
 *   <li><strong>The lockout is per store, not shared.</strong> Each store keeps its own
 *       {@code selectedAtEpochMillis}, so the Party clock and the Profession clock in
 *       {@code ProfessionStore} run independently and switching party never disturbs a profession, or the
 *       other way round.</li>
 * </ol>
 *
 * <p>The timestamp is wall clock, not online time, because a 30-hour lockout that pauses when the player logs
 * out is not a lockout.
 */
public final class FactionStore {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type TYPE = new TypeToken<Map<UUID, PartySelection>>() { }.getType();

    private final Path file;
    private final Map<UUID, PartySelection> selections = new HashMap<>();
    private final WallClock clock;
    private boolean dirty;

    /**
     * A player's party and the moment they joined it.
     *
     * <p>A record rather than two parallel maps so the two can never disagree — a player cannot end up with
     * Communism and Anarchism's timestamp.
     */
    public record PartySelection(FactionId faction, long selectedAtEpochMillis) {
        public boolean isReadable() {
            return faction != null;
        }
    }

    public FactionStore(Path file) {
        this(file, WallClock.SYSTEM);
    }

    public FactionStore(Path file, WallClock clock) {
        this.file = file;
        this.clock = clock;
        load();
    }

    /** The player's party, or {@link FactionId#defaultFaction()} if they have never chosen one. */
    public FactionId factionOf(UUID player) {
        PartySelection selection = selections.get(player);
        return selection == null || !selection.isReadable() ? FactionId.defaultFaction() : selection.faction();
    }

    /** The recorded choice, or {@code null} if there is none. Never a fabricated default. */
    public PartySelection selectionOf(UUID player) {
        return selections.get(player);
    }

    /** Whether the player has actually chosen a party. */
    public boolean hasChosen(UUID player) {
        PartySelection selection = selections.get(player);
        return selection != null && selection.isReadable();
    }

    /**
     * Records a choice and starts its lockout. Used by the {@code /tag} confirmation in Phase 3, and by
     * {@code /tag <player> set} for an admin.
     */
    public void select(UUID player, FactionId faction) {
        if (player == null || faction == null) return;
        selections.put(player, new PartySelection(faction, clock.millis()));
        dirty = true;
    }

    /** Forgets the player's party entirely, so they are back to the default and free to choose again. */
    public void reset(UUID player) {
        if (player != null && selections.remove(player) != null) {
            dirty = true;
        }
    }

    /**
     * Milliseconds until the player may choose a different party; {@code 0} when they may choose now.
     *
     * <p>Configured in hours and evaluated against wall clock, so it survives a restart — the expiry is a
     * timestamp on disk, not a countdown that stops when nobody is watching.
     */
    public long remainingCooldownMillis(UUID player, long lockoutHours) {
        if (lockoutHours <= 0) return 0L;
        PartySelection selection = selections.get(player);
        if (selection == null || !selection.isReadable()) return 0L;

        long elapsed = clock.millis() - selection.selectedAtEpochMillis();
        long lockoutMillis = lockoutHours * 3_600_000L;
        // A clock that moved backwards (NTP correction, a manual change) must not lock a player out for
        // longer than the lockout itself.
        if (elapsed < 0) return lockoutMillis;
        return Math.max(0L, lockoutMillis - elapsed);
    }

    /** Whether the player may change party right now. */
    public boolean canChange(UUID player, long lockoutHours) {
        return remainingCooldownMillis(player, lockoutHours) <= 0L;
    }

    /** Writes pending changes if anything changed since the last write. */
    public void flush() {
        if (!dirty) return;
        dirty = false;
        AsyncFileWriter.writeAsync(file, GSON.toJson(new HashMap<>(selections), TYPE));
    }

    private void load() {
        if (file == null || Files.notExists(file)) return;
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            Map<UUID, PartySelection> loaded = GSON.fromJson(json, TYPE);
            if (loaded == null) return;

            for (Map.Entry<UUID, PartySelection> entry : loaded.entrySet()) {
                PartySelection selection = entry.getValue();
                if (entry.getKey() == null || selection == null) continue;

                if (!selection.isReadable()) {
                    // Keep the row so the player does not lose their timestamp, but do not pretend the faction
                    // is known: factionOf() falls back to the default and canChange() stays permissive until
                    // the id is recognised again.
                    LOGGER.warn("[EconomyCraft] A saved party selection has no readable faction; it will be " +
                            "treated as unset until the id is valid.");
                }
                selections.put(entry.getKey(), selection);
            }
        } catch (Exception ex) {
            LOGGER.error("[EconomyCraft] Failed to read {}; parties will be treated as unchosen.", file, ex);
        }
    }
}