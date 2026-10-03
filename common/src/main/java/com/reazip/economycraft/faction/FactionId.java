package com.reazip.economycraft.faction;

import com.reazip.economycraft.EconomyConfig;
import com.reazip.economycraft.config.TagSettings;

import java.util.Locale;

/**
 * The four parties (spec §1).
 *
 * <p>A closed enum rather than a stringly-typed config value: a party name reaches a {@code /tag} GUI button,
 * a nametag prefix and a save file, so a typo in any of those has to be a compile error rather than a player
 * who silently belongs to nothing.
 *
 * <p>{@link #ANARCHISM} is the {@linkplain #defaultFaction() default} for a player who has chosen nothing —
 * see the package docs for why that is the least surprising default: it is the only party that charges no tax.
 * Note that "the default" is a read-time fallback only. {@link FactionStore} writes no record until a player
 * actually chooses, so "no choice yet" and "chose Anarchism" never become indistinguishable in the save file.
 */
public enum FactionId {
    COMMUNISM("Communism", "Đảng Cộng sản"),
    CAPITALISM("Capitalism", "Tư bản"),
    MONARCHY("Monarchy", "Vương triều"),
    ANARCHISM("Anarchism", "Vô chế");

    private final String displayName;
    private final String vietnameseName;

    FactionId(String displayName, String vietnameseName) {
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
     * The stable lowercase id: the {@code /eco party} subcommand, the {@link #fromKey} input, and the value
     * {@code FactionApi.factionId} hands to other mods.
     *
     * <p>One method rather than three {@code name().toLowerCase(Locale.ROOT)} call sites, because that
     * expression is what a copy-paste gets subtly wrong (a locale-sensitive one, or one that uppercases).
     * {@code FactionRulesTest} pins every id against the {@code FactionIds} constants in the API module,
     * so a party renamed here fails the build rather than silently never matching in ShopGuard.
     */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The party a player belongs to when they have not chosen: the one that taxes them least. */
    public static FactionId defaultFaction() {
        return ANARCHISM;
    }

    /**
     * This party's colour and icon, read from {@code config.json}.
     *
     * <p>The single source of truth for both, as P2-T3 requires: the nametag, the tab list and chat all go
     * through here, so there is exactly one place a colour can come from and no way for the two render paths to
     * disagree.
     */
    public TagSettings settings() {
        return switch (this) {
            case COMMUNISM -> EconomyConfig.get().factions.communism;
            case CAPITALISM -> EconomyConfig.get().factions.capitalism;
            case MONARCHY -> EconomyConfig.get().factions.monarchy;
            case ANARCHISM -> EconomyConfig.get().factions.anarchism;
        };
    }

    /**
     * Resolves a party from a save file, a config value or a command argument.
     *
     * @return the matching party, or {@code null} if unrecognised. Callers decide what that means — the two
     *         existing callers do different things on purpose: {@code FactionStore} keeps the record it could
     *         not read and warns (so a renamed party does not cost a player their progress), while the command
     *         layer tells the sender their argument was wrong.
     */
    public static FactionId fromKey(String key) {
        if (key == null) return null;
        String normalized = key.trim().toUpperCase(Locale.ROOT);
        for (FactionId faction : values()) {
            if (faction.name().equals(normalized)) return faction;
        }
        return null;
    }
}