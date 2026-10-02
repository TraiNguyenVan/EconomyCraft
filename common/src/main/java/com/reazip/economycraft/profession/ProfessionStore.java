package com.reazip.economycraft.profession;

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
 * Which profession each player holds, how far they have got, and whether they are rusty (spec §2, §3).
 *
 * <h2>What is stored, and what is derived</h2>
 *
 * <p>Five fields per player, and the third state is deliberately not one of them:
 *
 * <ul>
 *   <li><strong>Apprentice</strong> — a fresh choice.</li>
 *   <li><strong>Master</strong> — {@code everMastered}, reached without switching away.</li>
 *   <li><strong>Rusted</strong> ({@code Lụt nghề}) — never written to disk. It is
 *       {@link ProfessionProgress#isRusted()}: currently at a profession that was left while mastered and has
 *       now come back. Persisting the word "rusted" would mean storing the answer to a question whose inputs
 *       are already on disk, and then keeping the two in agreement through every code path that could touch
 *       either.</li>
 * </ul>
 *
 * <p>That is what {@link ProfessionProgress#masteredProfessionLeftBehind} is: a single field carrying "the
 * profession the player walked away from at Master", which is the entire input the rust rule needs. Losing it
 * would silently make every returning player a non-rusted Master, so it is written and read with the rest.
 *
 * <p>Like {@code FactionStore}, this writes nothing until a choice is made, and it keeps its own 30-hour
 * {@code selectedAtEpochMillis} so changing party never disturbs it (D17).
 */
public final class ProfessionStore {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type TYPE = new TypeToken<Map<UUID, ProfessionProgress>>() { }.getType();

    private final Path file;
    private final Map<UUID, ProfessionProgress> progressByPlayer = new HashMap<>();
    private final WallClock clock;
    private boolean dirty;

    /**
     * One player's profession state.
     *
     * <p>Public mutable fields, not a record, because the counters change in place many times a minute and this
     * object is what the save serialises; rebuilding it per block broken would churn the map for nothing.
     */
    public static final class ProfessionProgress {
        /** The current profession, or {@code null} for "no profession chosen". Never a default. */
        public ProfessionId professionId;
        /** Progress towards the current profession's level-up. Reset on every choice. */
        public long progress;
        /** Whether the player has reached Master at {@link #professionId}. */
        public boolean everMastered;
        /**
         * The profession the player left while at Master, or {@code null}.
         *
         * <p>Returning to it is what makes the player rusty until the 45-minute online timer fires.
         */
        public ProfessionId masteredProfessionLeftBehind;
        /** Wall-clock instant of the current choice; drives the 30-hour lockout. */
        public long selectedAtEpochMillis;
        /** Online time in ms spent rusty, feeding the {@code Lụt nghề} timer. */
        public long rustStartedAtOnlineMs;
        /** Per-villager trade counts for the Merchant's cap. Cleared when the profession changes. */
        public Map<String, Long> tradesPerVillager = new HashMap<>();

        public boolean hasProfession() {
            return professionId != null;
        }

        /** The persisted level. {@link ProfessionLevel#RUSTED} never comes from here — see the class docs. */
        public ProfessionLevel storedLevel() {
            if (professionId == null) return null;
            return everMastered ? ProfessionLevel.MASTER : ProfessionLevel.APPRENTICE;
        }

        /** Derived, never stored. */
        public boolean isRusted() {
            return professionId != null && everMastered && professionId == masteredProfessionLeftBehind;
        }
    }

    public ProfessionStore(Path file) {
        this(file, WallClock.SYSTEM);
    }

    public ProfessionStore(Path file, WallClock clock) {
        this.file = file;
        this.clock = clock;
        load();
    }

    /**
     * The player's profession, or {@code null} if they have never chosen one.
     *
     * <p>Deliberately not a default, unlike {@code FactionId}. Every profession grants something, so "no
     * profession" is a real state the UI has to render; filling it with a plausible default would hand a new
     * player free buffs and, worse, a progress counter towards a level-up they never started.
     */
    public ProfessionId professionOf(UUID player) {
        ProfessionProgress progress = progressByPlayer.get(player);
        return progress == null ? null : progress.professionId;
    }

    /** The player's state, creating an empty one if they have no record yet. */
    public ProfessionProgress progressOf(UUID player) {
        return progressByPlayer.computeIfAbsent(player, k -> new ProfessionProgress());
    }

    /** The player's state, or {@code null} if they have never chosen a profession. */
    public ProfessionProgress existingProgressOf(UUID player) {
        return progressByPlayer.get(player);
    }

    /**
     * The player's current level, derived.
     *
     * @return the level, or {@code null} if the player has no profession
     */
    public ProfessionLevel levelOf(UUID player) {
        ProfessionProgress progress = progressByPlayer.get(player);
        if (progress == null || progress.professionId == null) return null;
        return progress.isRusted() ? ProfessionLevel.RUSTED : progress.storedLevel();
    }

    /**
     * The player's base level (APPRENTICE or MASTER), ignoring any active rust state.
     *
     * <p>Effects read this level and apply rust through {@link ProfessionEffects#resolveMultiplier},
     * so that a rusty Master still receives their tier's benefits scaled by 0.5 rather than zero.
     */
    public ProfessionLevel baseLevelOf(UUID player) {
        ProfessionProgress progress = progressByPlayer.get(player);
        if (progress == null || progress.professionId == null) return null;
        return progress.storedLevel();
    }

    /**
     * Records a choice, resetting progress and the rust timer.
     *
     * <p>Switching is not a plain overwrite, because it is what creates rust. The three cases:
     *
     * <ul>
     *   <li>First choice — a clean Apprentice.</li>
     *   <li>Same profession — ignored, so a double-clicked confirm button cannot reset a player's progress.</li>
     *   <li>Different profession — if the player leaves at Master, that profession is remembered as
     *       {@link ProfessionProgress#masteredProfessionLeftBehind}; coming back to it later starts rusty.
     *       Moving to a different profession always starts a fresh Apprentice.</li>
     * </ul>
     */
    public void select(UUID player, ProfessionId profession) {
        if (player == null || profession == null) return;

        ProfessionProgress progress = progressOf(player);
        if (progress.professionId == profession) return;

        if (progress.professionId != null && progress.everMastered
                && progress.masteredProfessionLeftBehind == null) {
            progress.masteredProfessionLeftBehind = progress.professionId;
        }

        // Returning to the profession that was left at Master restores Master — and with it the rust rule.
        progress.everMastered = profession == progress.masteredProfessionLeftBehind;
        progress.professionId = profession;
        progress.progress = 0L;
        progress.rustStartedAtOnlineMs = 0L;
        progress.selectedAtEpochMillis = clock.millis();
        progress.tradesPerVillager.clear();
        dirty = true;
    }

    /**
     * Adds progress towards the current level-up and promotes to Master on the way.
     *
     * <p>Refuses, by design, for a rusty player (D13): earning progress while rusty would let the timer be
     * skipped by working, which is what the debuff exists to prevent. It also refuses for
     * {@link ProfessionId#MERCHANT}, whose level-up needs two counters — Phase 5 goes through
     * {@link #recordVillagerTrade} and {@link #recordAuctionPurchase} so half of its condition can never be
     * applied by accident through this path.
     *
     * @return {@code true} if the player just reached Master
     */
    public boolean addProgress(UUID player, long amount) {
        if (player == null || amount <= 0) return false;
        ProfessionProgress progress = progressByPlayer.get(player);
        if (progress == null || progress.professionId == null) return false;
        if (progress.professionId == ProfessionId.MERCHANT || progress.isRusted() || progress.everMastered) {
            return false;
        }

        progress.progress += amount;
        dirty = true;

        int needed = levelUpCountFor(progress.professionId);
        if (progress.progress >= needed) {
            progress.progress = needed;
            progress.everMastered = true;
            return true;
        }
        return false;
    }

    /** The level-up count for a profession, read from its own config record. */
    public static int levelUpCountFor(ProfessionId profession) {
        com.reazip.economycraft.EconomyConfig config = com.reazip.economycraft.EconomyConfig.get();
        return switch (profession) {
            case BUILDER -> config.professions.builder.levelUpCount;
            case FARMER -> config.professions.farmer.levelUpCount;
            case MINER -> config.professions.miner.levelUpCount;
            // See addProgress(): the Merchant's real condition is two counters, and Phase 5 checks both.
            case MERCHANT -> config.professions.merchant.villagerTradeCount;
            case SOLDIER -> config.professions.soldier.killCount;
        };
    }

    /** Whether this profession's level-up is a single counter, which {@link #addProgress} may serve. */
    public static boolean usesSingleProgressCounter(ProfessionId profession) {
        return profession != ProfessionId.MERCHANT;
    }

    /**
     * Records one Merchant trade against a villager, honouring the per-villager cap.
     *
     * @return {@code true} if the trade counted towards the level-up; {@code false} if that villager is already
     *         at its cap, or the player is not a Merchant
     */
    public boolean recordVillagerTrade(UUID player, String villagerId) {
        ProfessionProgress progress = progressByPlayer.get(player);
        if (progress == null || progress.professionId != ProfessionId.MERCHANT || villagerId == null) return false;

        int cap = com.reazip.economycraft.EconomyConfig.get().professions.merchant.maxTradesPerVillager;
        long count = progress.tradesPerVillager.getOrDefault(villagerId, 0L);
        if (count >= cap) return false;

        progress.tradesPerVillager.put(villagerId, count + 1L);
        dirty = true;
        return true;
    }

    /** Trade count for one villager, for the Merchant's own level-up check in Phase 5. */
    public long tradesWith(UUID player, String villagerId) {
        ProfessionProgress progress = progressByPlayer.get(player);
        if (progress == null) return 0L;
        return progress.tradesPerVillager.getOrDefault(villagerId, 0L);
    }

    /** Records one Merchant auction purchase, which counts separately from villager trades. */
    public boolean recordAuctionPurchase(UUID player) {
        ProfessionProgress progress = progressByPlayer.get(player);
        if (progress == null || progress.professionId != ProfessionId.MERCHANT) return false;

        int cap = com.reazip.economycraft.EconomyConfig.get().professions.merchant.auctionPurchaseCount;
        if (progress.progress >= cap) return false;

        progress.progress += 1L;
        dirty = true;
        return true;
    }

    /**
     * Adds online time towards clearing rust, promoting the player back to Master once the threshold is met.
     *
     * <p>The caller passes <em>online</em> time from {@code OnlineTimeService}, never wall clock: the spec says
     * "45 minutes of play", so a week logged out must not clear the debuff.
     *
     * @param onlineMillis      time to add, in milliseconds
     * @param thresholdMinutes  the configured rust threshold
     * @return {@code true} if the player stopped being rusty
     */
    public boolean completeRustIfEarned(UUID player, long onlineMillis, int thresholdMinutes) {
        ProfessionProgress progress = progressByPlayer.get(player);
        if (progress == null || !progress.isRusted() || onlineMillis <= 0) return false;

        progress.rustStartedAtOnlineMs += onlineMillis;
        dirty = true;

        if (progress.rustStartedAtOnlineMs < thresholdMinutes * 60_000L) return false;

        progress.masteredProfessionLeftBehind = null;
        progress.rustStartedAtOnlineMs = 0L;
        return true;
    }

    /** How much of the rust timer a player has served, in milliseconds, for {@code /tag} progress display. */
    public long rustOnlineMillis(UUID player) {
        ProfessionProgress progress = progressByPlayer.get(player);
        return progress == null ? 0L : progress.rustStartedAtOnlineMs;
    }

    /** Forgets the player's profession entirely, so they are free to choose again with no history. */
    public void reset(UUID player) {
        if (player != null && progressByPlayer.remove(player) != null) {
            dirty = true;
        }
    }

    /** Milliseconds until the player may choose a different profession; {@code 0} when they may choose now. */
    public long remainingCooldownMillis(UUID player, long lockoutHours) {
        if (lockoutHours <= 0) return 0L;
        ProfessionProgress progress = progressByPlayer.get(player);
        if (progress == null || progress.professionId == null) return 0L;

        long lockoutMillis = lockoutHours * 3_600_000L;
        long elapsed = clock.millis() - progress.selectedAtEpochMillis;
        // A clock that moved backwards must not lock a player out for longer than the lockout itself.
        if (elapsed < 0) return lockoutMillis;
        return Math.max(0L, lockoutMillis - elapsed);
    }

    /** Whether the player may change profession right now. */
    public boolean canChange(UUID player, long lockoutHours) {
        return remainingCooldownMillis(player, lockoutHours) <= 0L;
    }

    /** Writes pending changes if anything changed since the last write. */
    public void flush() {
        if (!dirty) return;
        dirty = false;
        AsyncFileWriter.writeAsync(file, GSON.toJson(new HashMap<>(progressByPlayer), TYPE));
    }

    private void load() {
        if (file == null || Files.notExists(file)) return;
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            Map<UUID, ProfessionProgress> loaded = GSON.fromJson(json, TYPE);
            if (loaded == null) return;

            for (Map.Entry<UUID, ProfessionProgress> entry : loaded.entrySet()) {
                ProfessionProgress progress = entry.getValue();
                if (entry.getKey() == null || progress == null) continue;

                if (progress.professionId == null) {
                    LOGGER.warn("[EconomyCraft] A saved profession record has no readable profession; the player " +
                            "is treated as having no profession until the id is valid again.");
                }
                if (progress.tradesPerVillager == null) progress.tradesPerVillager = new HashMap<>();
                progressByPlayer.put(entry.getKey(), progress);
            }
        } catch (Exception ex) {
            LOGGER.error("[EconomyCraft] Failed to read {}; professions will be treated as unchosen.", file, ex);
        }
    }
}