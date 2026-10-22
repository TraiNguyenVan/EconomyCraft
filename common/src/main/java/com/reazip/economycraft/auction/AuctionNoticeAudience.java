package com.reazip.economycraft.auction;

import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Who an offline {@code /ah} notice is queued for. Kept free of server types so the window and the
 * seller exclusion are testable without a running game.
 */
public final class AuctionNoticeAudience {
    private AuctionNoticeAudience() {}

    /**
     * @param lastSeenMs epoch millis of the player's last login, or 0 when never seen
     * @param activeDays how long a player stays eligible after their last login; {@code 0} disables the
     *                   offline queue entirely (online players are handled by the caller)
     */
    public static boolean shouldQueue(UUID player, @Nullable UUID seller, long lastSeenMs, long nowMs, int activeDays) {
        if (player == null || player.equals(seller)) return false;
        if (activeDays <= 0 || lastSeenMs <= 0) return false;
        return lastSeenMs >= nowMs - TimeUnit.DAYS.toMillis(activeDays);
    }
}
