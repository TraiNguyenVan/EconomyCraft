package com.reazip.economycraft.config;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The clamp-and-warn helpers every {@code EconomyConfig} key goes through.
 *
 * <p>These were private to {@code EconomyConfig} until Phase 2, which was the first time a config value
 * lived more than one level down — {@code factions.communism.income_tax_tier1_rate} and friends. Keeping the
 * arithmetic here rather than duplicating it per section is the point: a rule like "a rate is a decimal
 * factor clamped to 0–1" has to be stated once, or the new sections slowly drift away from the old ones.
 *
 * <p>Clamping never fails. A mistyped key must degrade to a usable value with a warning, because the
 * alternative — refusing to start — turns a one-character typo into an outage.
 */
public final class ConfigClamp {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Multipliers (a tax multiplier, a damage multiplier) never need to exceed this. */
    public static final double MAX_MULTIPLIER = 100.0;
    /** A difficulty dial such as an elasticity exponent above this stops being a dial. */
    public static final double MAX_EXPONENT = 10.0;
    /** A 24-bit RGB colour. */
    public static final int MAX_COLOR = 0xFFFFFF;
    /** Vanilla status-effect amplifiers are bytes. */
    public static final int MAX_EFFECT_LEVEL = 255;

    private ConfigClamp() {
    }

    public static double range(String fieldName, double value, double min, double max) {
        double clamped = Math.clamp(value, min, max);
        if (clamped != value) {
            LOGGER.warn("[EconomyCraft] {} ({}) is outside the valid {}-{} range; clamping to {}.",
                    fieldName, value, min, max, clamped);
        }
        return clamped;
    }

    /** A decimal factor: {@code 0.1} means 10 %. */
    public static double percentage(String fieldName, double value) {
        return range(fieldName, value, 0.0, 1.0);
    }

    /** The probability of an event. Same 0–1 shape as a rate, named for what it is. */
    public static double chance(String fieldName, double value) {
        return range(fieldName, value, 0.0, 1.0);
    }

    public static double multiplier(String fieldName, double value) {
        return range(fieldName, value, 0.0, MAX_MULTIPLIER);
    }

    public static double exponent(String fieldName, double value) {
        return range(fieldName, value, 0.0, MAX_EXPONENT);
    }

    public static int nonNegative(String fieldName, int value) {
        if (value < 0) {
            LOGGER.warn("[EconomyCraft] {} ({}) is negative; clamping to 0.", fieldName, value);
            return 0;
        }
        return value;
    }

    public static long nonNegative(String fieldName, long value) {
        if (value < 0) {
            LOGGER.warn("[EconomyCraft] {} ({}) is negative; clamping to 0.", fieldName, value);
            return 0;
        }
        return value;
    }

    public static double nonNegative(String fieldName, double value) {
        if (value < 0) {
            LOGGER.warn("[EconomyCraft] {} ({}) is negative; clamping to 0.", fieldName, value);
            return 0.0;
        }
        return value;
    }

    public static int effectLevel(String fieldName, int value) {
        return (int) range(fieldName, value, 0, MAX_EFFECT_LEVEL);
    }

    /**
     * An RGB colour. A negative value is nearly always a sign that the admin pasted a value with a leading
     * minus, or a 32-bit hex that still carries its alpha channel; clamping keeps the tag legible instead of
     * rendering it in whatever colour the top bits happen to imply.
     */
    public static int color(String fieldName, int value) {
        if (value < 0 || value > MAX_COLOR) {
            int clamped = Math.clamp(value, 0, MAX_COLOR);
            LOGGER.warn("[EconomyCraft] {} ({}) is not a 24-bit RGB value (0-{}); clamping to {}.",
                    fieldName, value, MAX_COLOR, clamped);
            return clamped;
        }
        return value;
    }

    /**
     * A tag icon: exactly one renderable glyph.
     *
     * <p>Vanilla's default font has no fallback for arbitrary text, so a multi-character "icon" renders as a
     * run of letters beside every player's name. Falling back keeps the tag readable.
     */
    public static String icon(String fieldName, String value, String fallback) {
        if (value == null || value.isEmpty()) {
            LOGGER.warn("[EconomyCraft] {} is empty; using '{}'.", fieldName, fallback);
            return fallback;
        }
        if (value.codePointCount(0, value.length()) != 1 || !Character.isDefined(value.codePointAt(0))) {
            LOGGER.warn("[EconomyCraft] {} ('{}') is not a single renderable glyph; using '{}'.",
                    fieldName, value, fallback);
            return fallback;
        }
        return value;
    }

    /** An enumerated string key, e.g. the container lock mode. Unknown values fall back and warn. */
    public static <E extends Enum<E>> E choice(String fieldName, String value, E fallback, E... allowed) {
        if (value != null) {
            String trimmed = value.trim();
            for (E option : allowed) {
                if (option.name().equalsIgnoreCase(trimmed)) return option;
            }
        }
        LOGGER.warn("[EconomyCraft] {} ('{}') is not one of {}; using {}.",
                fieldName, value, Arrays.toString(allowed), fallback);
        return fallback;
    }

    /**
     * Drops unusable entries from a list-valued key, leaving the admin's list otherwise as written — including
     * empty, which is a legitimate way to switch a whole set off.
     */
    public static List<String> cleanList(String fieldName, List<String> value) {
        if (value == null) return List.of();

        List<String> cleaned = new ArrayList<>(value.size());
        int dropped = 0;
        for (String entry : value) {
            if (entry == null || entry.isBlank()) {
                dropped++;
                continue;
            }
            cleaned.add(entry.trim());
        }
        if (dropped > 0) {
            LOGGER.warn("[EconomyCraft] {} had {} blank or unreadable entr{} dropped.", fieldName, dropped,
                    dropped == 1 ? "y" : "ies");
        }
        return List.copyOf(cleaned);
    }
}