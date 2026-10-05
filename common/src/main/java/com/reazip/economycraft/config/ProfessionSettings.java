package com.reazip.economycraft.config;

import com.google.gson.annotations.SerializedName;

/**
 * The shared half of the five job settings blocks: everything a job's <em>tag</em> needs on top of a party tag.
 *
 * <p>Exists only to hold one field, but holding it here rather than in {@link TagSettings} keeps
 * {@code mastered_color} out of the four party blocks, where it would be a key that does nothing. The five job
 * classes therefore change only their {@code extends} clause; their {@code clamp()} bodies already call
 * {@link #clampTag}, so the new key is validated without touching them.
 */
public abstract class ProfessionSettings extends TagSettings {

    /** Gold, and the reason a Master is legible at nametag size. */
    public static final int DEFAULT_MASTERED_COLOR = 0xFFD700;

    /**
     * The colour of a <em>Master's brackets</em> — not of the whole tag.
     *
     * <p>Only ever read at {@code MASTER}, and paired by the renderer with the guillemet pair: a Master is the one
     * tag on a nametag whose brackets differ in shape and colour, so the job icon inside can keep its own colour
     * and a Master Builder still reads as a Builder.
     */
    @SerializedName("mastered_color")
    public int masteredColor = DEFAULT_MASTERED_COLOR;

    @Override
    protected void clampTag(String path) {
        super.clampTag(path);
        masteredColor = ConfigClamp.color(path + ".mastered_color", masteredColor);
    }
}