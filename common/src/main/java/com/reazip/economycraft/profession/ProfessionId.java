package com.reazip.economycraft.profession;

import com.reazip.economycraft.EconomyConfig;
import com.reazip.economycraft.config.ProfessionsSection;
import com.reazip.economycraft.config.TagSettings;

import java.util.Locale;

/**
 * The five professions (spec §2).
 *
 * <p>A player holds at most one, and a closed enum is what makes "at most one" enforceable: the collection that
 * holds them is a single {@code ProfessionId} field on {@link ProfessionStore}, not a set that could grow a
 * second entry from a double-submitted GUI click.
 *
 * <p>There is deliberately no {@code levelUpCount()} here. The count is per-job and lives in that job's config
 * record, and it is not uniformly a single number: Merchant needs both {@code villager_trade_count} and
 * {@code auction_purchase_count}, so a one-number accessor on the enum would have to silently drop half of the
 * Merchant's condition. Phase 5's Merchant reads both of its own keys instead.
 */
public enum ProfessionId {
    BUILDER("Builder", "Thợ xây"),
    FARMER("Farmer", "Nông dân"),
    MINER("Miner", "Thợ mỏ"),
    MERCHANT("Merchant", "Thương nhân"),
    SOLDIER("Soldier", "Chiến binh");

    private final String displayName;
    private final String vietnameseName;

    ProfessionId(String displayName, String vietnameseName) {
        this.displayName = displayName;
        this.vietnameseName = vietnameseName;
    }

    /** English, for API surfaces and {@code /eco} output (D18). */
    public String displayName() {
        return displayName;
    }

    /** Vietnamese, for in-game tags and the gameplay wiki (D18). */
    public String vietnameseName() {
        return vietnameseName;
    }

    /**
     * This profession's colour and icon, read from {@code config.json} — the single source of truth for both.
     */
    public TagSettings settings() {
        ProfessionsSection professions = EconomyConfig.get().professions;
        return switch (this) {
            case BUILDER -> professions.builder;
            case FARMER -> professions.farmer;
            case MINER -> professions.miner;
            case MERCHANT -> professions.merchant;
            case SOLDIER -> professions.soldier;
        };
    }

    /**
     * Resolves a profession from a save file, a config value or a command argument.
     *
     * @return the matching profession, or {@code null} if unrecognised. {@code ProfessionStore} treats that as
     *         "no readable record" rather than as a reason to drop the player's progress.
     */
    public static ProfessionId fromKey(String key) {
        if (key == null) return null;
        String normalized = key.trim().toUpperCase(Locale.ROOT);
        for (ProfessionId profession : values()) {
            if (profession.name().equals(normalized)) return profession;
        }
        return null;
    }
}