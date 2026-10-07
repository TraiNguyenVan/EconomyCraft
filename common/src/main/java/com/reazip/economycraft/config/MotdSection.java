package com.reazip.economycraft.config;

import com.google.gson.annotations.SerializedName;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for the in-game join MOTD.
 */
public class MotdSection {
    private static final Logger LOGGER = LogUtils.getLogger();

    public static final int DEFAULT_DELAY_TICKS = 40;
    public static final int MIN_DELAY_TICKS = 0;
    public static final int MAX_DELAY_TICKS = 1200; // 60 seconds

    @SerializedName("enabled")
    public boolean enabled = true;

    @SerializedName("delay_ticks")
    public int delayTicks = DEFAULT_DELAY_TICKS;

    @SerializedName("lines")
    public List<String> lines = defaultLines();

    public static List<String> defaultLines() {
        List<String> list = new ArrayList<>();
        list.add("&8&m----------------------------------------");
        list.add("&6Hello &e{player}&6!");
        list.add("&7If you want to share any ideas, create an issue at: &bhttps://github.com/TraiNguyenVan/EconomyCraft/issues");
        list.add("&8&m----------------------------------------");
        return list;
    }

    public void clamp() {
        if (delayTicks < MIN_DELAY_TICKS || delayTicks > MAX_DELAY_TICKS) {
            int old = delayTicks;
            delayTicks = Math.clamp(delayTicks, MIN_DELAY_TICKS, MAX_DELAY_TICKS);
            LOGGER.warn("[EconomyCraft] motd.delay_ticks ({}) outside {}-{} range; clamping to {}.",
                    old, MIN_DELAY_TICKS, MAX_DELAY_TICKS, delayTicks);
        }
        if (lines == null) {
            lines = defaultLines();
        }
    }
}
