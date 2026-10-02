package com.reazip.economycraft.util;

public final class TimeFormat {
    private TimeFormat() {}

    public static String formatDuration(long milliseconds) {
        if (milliseconds <= 0) return "0s";
        long seconds = milliseconds / 1000L;
        long hours = seconds / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        long secs = seconds % 60L;
        StringBuilder sb = new StringBuilder();
        if (hours > 0) sb.append(hours).append('h');
        if (minutes > 0) sb.append(minutes).append('m');
        if (secs > 0 || sb.length() == 0) sb.append(secs).append('s');
        return sb.toString();
    }

    public static String formatRemaining(long expiresAtEpochMillis) {
        if (expiresAtEpochMillis <= 0) return "Never expires";
        long remaining = expiresAtEpochMillis - System.currentTimeMillis();
        if (remaining <= 0) return "Expired";
        return formatDuration(remaining) + " remaining";
    }
}
