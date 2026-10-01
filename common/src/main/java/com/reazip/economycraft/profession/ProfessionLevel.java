package com.reazip.economycraft.profession;

/**
 * A profession's level: two persisted states and one that is only ever derived.
 *
 * <p>{@link #APPRENTICE} and {@link #MASTER} are written to {@code professions.json}. {@link #RUSTED} is
 * <strong>never written</strong> — it is what {@link ProfessionStore} computes for a Master who switched away
 * and came back, from {@code everMastered} plus {@code rustStartedAtOnlineMs}. Persisting it would mean
 * writing the answer to a question whose inputs are already on disk, and then keeping the two in agreement
 * through every code path that could touch either.
 *
 * <p>{@link #persisted()} exists so that the save path cannot accidentally learn to store the derived state:
 * the one place that serialises a level filters on it.
 */
public enum ProfessionLevel {
    APPRENTICE("Apprentice", "Học viên"),
    MASTER("Master", "Thợ trưởng"),
    RUSTED("Rusted", "Lụt nghề");

    private final String displayName;
    private final String vietnameseName;

    ProfessionLevel(String displayName, String vietnameseName) {
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

    /** Whether this state belongs in {@code professions.json}. False only for {@link #RUSTED}. */
    public boolean persisted() {
        return this != RUSTED;
    }

    /** The one-liner shown next to a profession in {@code /tag} and {@code /eco} output. */
    public String flavourLine() {
        return switch (this) {
            case APPRENTICE -> "Beginning to master a craft.";
            case MASTER -> "Mastered a craft; its full effect is yours.";
            case RUSTED -> "Skilled, but rusty — effects are half until you work it back up.";
        };
    }
}