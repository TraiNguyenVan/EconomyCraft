package com.reazip.economycraft.config;

import com.google.gson.annotations.SerializedName;

/**
 * What every player-visible tag needs: a colour and a one-glyph icon.
 *
 * <p>Shared by the four faction records and the five profession records so a tag can never be drawn by two
 * different code paths that disagree about where the colour came from — the tab list, the nametag and chat
 * all read these same two fields (Phase 3).
 *
 * <p>{@code color} is a plain 24-bit RGB integer rather than a {@code ChatFormatting} name on purpose: the
 * spec wants <em>characteristic</em> colours per party, and the sixteen vanilla ones do not include the
 * yellow-green the icon set needs.
 */
public abstract class TagSettings {

    @SerializedName("color")
    public int color;

    @SerializedName("icon")
    public String icon;

    /** Validates the two fields that every tag record has. Subclasses chain to this. */
    protected void clampTag(String path) {
        color = ConfigClamp.color(path + ".color", color);
        icon = ConfigClamp.icon(path + ".icon", icon, defaultIcon());
    }

    /** The icon this record falls back to when the configured one is unusable. */
    protected abstract String defaultIcon();
}