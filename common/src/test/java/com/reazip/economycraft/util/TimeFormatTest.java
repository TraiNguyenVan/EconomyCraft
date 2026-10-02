package com.reazip.economycraft.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link TimeFormat} is what renders the 30 h lockout in the D16 menu, so the boundary cases matter: a player
 * who sees "30h" and one who sees "29h59m" have to be describing the same clock.
 */
class TimeFormatTest {

    @Test
    void hoursAndMinutesAreBothShownWhenPresent() {
        assertEquals("29h55m", TimeFormat.formatDuration(29 * 3_600_000L + 55 * 60_000L));
    }

    @Test
    void wholeHoursDropTheMinutePart() {
        assertEquals("30h", TimeFormat.formatDuration(30 * 3_600_000L));
    }

    @Test
    void subMinuteValuesFallBackToSeconds() {
        assertEquals("5s", TimeFormat.formatDuration(5_000L));
    }

    @Test
    void zeroAndNegativeRenderAsZero() {
        assertEquals("0s", TimeFormat.formatDuration(0L));
        assertEquals("0s", TimeFormat.formatDuration(-1L));
    }

    @Test
    void secondsOnlyAppearAsTheLastMeaningfulUnit() {
        assertEquals("1m30s", TimeFormat.formatDuration(90_000L));
    }
}
