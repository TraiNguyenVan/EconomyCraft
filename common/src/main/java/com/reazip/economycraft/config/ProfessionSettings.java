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

    /** Gold. Chosen to read as "finished" against all five job colours, none of which is gold. */
    public static final int DEFAULT_MASTERED_COLOR = 0xFFD700;

    /**
     * The colour a Master tag is drawn in, in place of the job's own colour.
     *
     * <p>Only ever read when the player has reached {@code MASTER}, and paired by the renderer with the
     * {@code << >>} framing, so that a Master is distinguishable from an Apprentice at a glance without the word
     * "Master" having to interrupt their name.
     */
    @SerializedName("mastered_color")
    public int masteredColor = DEFAULT_MASTERED_COLOR;

    @Override
    protected void clampTag(String path) {
        super.clampTag(path);
        masteredColor = ConfigClamp.color(path + ".mastered_color", masteredColor);
    }
}