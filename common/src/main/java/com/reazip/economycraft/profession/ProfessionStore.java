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
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Which profession each player holds, how far they have got, and whether they are rusty (spec §2, §3).
 *
 * <h2>What is stored, and what is derived</h2>
 *
 * <ul>
 *   <li><strong>Apprentice</strong> — a fresh choice or an unmastered profession.</li>
 *   <li><strong>Master</strong> — any profession in {@link ProfessionProgress#masteredProfessions}.</li>
 *   <li><strong>Rusted</strong> ({@code Lụt nghề}) — returning to any previously mastered profession.
 *       Takes 45 minutes of online play to clear rust and restore full Master effects.</li>
 * </ul>
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
        /** All professions the player has ever reached Master at. Persisted permanently. */
        public Set<ProfessionId> masteredProfessions = new HashSet<>();
        /** Whether the player is currently serving rust on their current profession. */
        public boolean rusted;
        /** Wall-clock instant of the current choice; drives the 30-hour lockout. */
        public long selectedAtEpochMillis;
        /** Online time in ms spent rusty, feeding the {@code Lụt nghề} timer. */
        public long rustStartedAtOnlineMs;
        /** Per-villager trade counts for the Merchant's cap. Cleared when the profession changes. */
        public Map<String, Long> tradesPerVillager = new HashMap<>();

        // Legacy fields for JSON deserialization backwards compatibility:
        public Boolean everMastered;
        public ProfessionId masteredProfessionLeftBehind;

        public boolean hasProfession() {
            return professionId != null;
        }

        public boolean isCurrentMastered() {
            return professionId != null && masteredProfessions != null && masteredProfessions.contains(professionId);
        }

        /** The persisted level. {@link ProfessionLevel#RUSTED} never comes from here — see the class docs. */
        public ProfessionLevel storedLevel() {
            if (professionId == null) return null;
            return isCurrentMastered() ? ProfessionLevel.MASTER : ProfessionLevel.APPRENTICE;
        }

        /** Derived. */
        public boolean isRusted() {
            return professionId != null && rusted && isCurrentMastered();
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
     *   <li>Different profession — if returning to a profession the player previously mastered, they become
     *       {@link ProfessionLevel#RUSTED} ("Lụt nghề") and must spend 45 minutes online to recover full effects.
     *       If the profession has never been mastered, they start fresh as an Apprentice ("Tập sự").</li>
     * </ul>
     */
    public void select(UUID player, ProfessionId profession) {
        if (player == null || profession == null) return;

        ProfessionProgress progress = progressOf(player);
        if (progress.professionId == profession) return;

        progress.professionId = profession;
        progress.progress = 0L;
        progress.rustStartedAtOnlineMs = 0L;
        progress.selectedAtEpochMillis = clock.millis();
        progress.tradesPerVillager.clear();

        // Returning to any previously mastered profession makes the player rusty ("Lụt nghề").
        // Otherwise they start as a fresh Apprentice ("Tập sự").
        progress.rusted = progress.masteredProfessions.contains(profession);
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
        if (progress.professionId == ProfessionId.MERCHANT || progress.isRusted() || progress.isCurrentMastered()) {
            return false;
        }

        progress.progress += amount;
        dirty = true;

        int needed = levelUpCountFor(progress.professionId);
        if (progress.progress >= needed) {
            progress.progress = needed;
            progress.masteredProfessions.add(progress.professionId);
            progress.rusted = false;
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
     *         at its cap, or the player is not a Merchant, or player is rusted/mastered
     */
    public boolean recordVillagerTrade(UUID player, String villagerId) {
        ProfessionProgress progress = progressByPlayer.get(player);
        if (progress == null || progress.professionId != ProfessionId.MERCHANT || villagerId == null) return false;
        if (progress.isRusted() || progress.isCurrentMastered()) return false;

        int cap = com.reazip.economycraft.EconomyConfig.get().professions.merchant.maxTradesPerVillager;
        long count = progress.tradesPerVillager.getOrDefault(villagerId, 0L);
        if (count >= cap) return false;

        int neededTrades = com.reazip.economycraft.EconomyConfig.get().professions.merchant.villagerTradeCount;
        if (totalVillagerTrades(progress) >= neededTrades) return false;

        progress.tradesPerVillager.put(villagerId, count + 1L);
        dirty = true;
        checkMerchantPromotion(player, progress);
        return true;
    }

    /** Trade count for one villager, for the Merchant's own level-up check. */
    public long tradesWith(UUID player, String villagerId) {
        ProfessionProgress progress = progressByPlayer.get(player);
        if (progress == null) return 0L;
        return progress.tradesPerVillager.getOrDefault(villagerId, 0L);
    }

    /** Total qualifying villager trades across all villagers for this player. */
    public long totalVillagerTrades(UUID player) {
        ProfessionProgress progress = progressByPlayer.get(player);
        return totalVillagerTrades(progress);
    }

    /** Total qualifying villager trades across all villagers in the given progress record. */
    public static long totalVillagerTrades(ProfessionProgress progress) {
        if (progress == null || progress.tradesPerVillager == null) return 0L;
        return progress.tradesPerVillager.values().stream().mapToLong(Long::longValue).sum();
    }

    /** Records one Merchant auction purchase, which counts separately from villager trades. */
    public boolean recordAuctionPurchase(UUID player) {
        ProfessionProgress progress = progressByPlayer.get(player);
        if (progress == null || progress.professionId != ProfessionId.MERCHANT) return false;
        if (progress.isRusted() || progress.isCurrentMastered()) return false;

        int cap = com.reazip.economycraft.EconomyConfig.get().professions.merchant.auctionPurchaseCount;
        if (progress.progress >= cap) return false;

        progress.progress += 1L;
        dirty = true;
        checkMerchantPromotion(player, progress);
        return true;
    }

    /**
     * Checks D6 level-up condition (AND): total villager trades >= 50 AND auction purchases >= 5.
     * Promotes to Master if both are satisfied.
     *
     * @return true if player was just promoted to Master
     */
    public boolean checkMerchantPromotion(UUID player, ProfessionProgress progress) {
        if (progress == null || progress.professionId != ProfessionId.MERCHANT) return false;
        if (progress.isRusted() || progress.isCurrentMastered()) return false;

        int neededTrades = com.reazip.economycraft.EconomyConfig.get().professions.merchant.villagerTradeCount;
        int neededPurchases = com.reazip.economycraft.EconomyConfig.get().professions.merchant.auctionPurchaseCount;

        if (totalVillagerTrades(progress) >= neededTrades && progress.progress >= neededPurchases) {
            progress.masteredProfessions.add(ProfessionId.MERCHANT);
            progress.rusted = false;
            dirty = true;
            return true;
        }
        return false;
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

        progress.rusted = false;
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

    /** Whether the player has ever achieved Master in the given profession. */
    public boolean hasMastered(UUID player, ProfessionId profession) {
        ProfessionProgress progress = progressByPlayer.get(player);
        return progress != null && progress.masteredProfessions != null && progress.masteredProfessions.contains(profession);
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
                if (progress.masteredProfessions == null) {
                    progress.masteredProfessions = new HashSet<>();
                }
                // Backwards compatibility migration from legacy fields:
                if (Boolean.TRUE.equals(progress.everMastered) && progress.professionId != null) {
                    progress.masteredProfessions.add(progress.professionId);
                }
                if (progress.masteredProfessionLeftBehind != null) {
                    progress.masteredProfessions.add(progress.masteredProfessionLeftBehind);
                    if (progress.professionId == progress.masteredProfessionLeftBehind) {
                        progress.rusted = true;
                    }
                }
                if (progress.tradesPerVillager == null) progress.tradesPerVillager = new HashMap<>();
                progressByPlayer.put(entry.getKey(), progress);
            }
        } catch (Exception ex) {
            LOGGER.error("[EconomyCraft] Failed to read {}; professions will be treated as unchosen.", file, ex);
        }
    }
}