package com.reazip.economycraft.api.v1;

import net.minecraft.server.MinecraftServer;

/** Stable, server-side entry point for EconomyCraft API v1. */
public interface EconomyCraftApi {
    static EconomyCraftApi get(MinecraftServer server) {
        return EconomyCraftApiAccess.get(server);
    }

    BalanceApi balances();

    PriceApi prices();

    LeaderboardApi leaderboard();

    BalanceEvents balanceEvents();

    String formatMoney(long amount);

    /**
     * The economy's current inflation multiplier: the median balance of the active players divided by
     * {@code startingBalance}, clamped to the configured
     * {@code dynamic_price_min_multiplier}..{@code dynamic_price_max_multiplier} range. {@code 1.0}
     * means the median balance sits exactly at the starting balance; {@code 7.36} means it sits at
     * 7.36x, i.e. the money supply has grown past where players began.
     *
     * <p>This is a <strong>slowly moving market rate, not a transaction price</strong>. It is
     * recomputed at most once an hour and only from players seen inside the activity window, so with
     * a small player base it can jump sharply when one profile ages out of that window. Callers that
     * need a stable figure should compress it into their own range and anchor it to a reference
     * value rather than using it raw — and should never let it decide a price at a finer grain than
     * the hourly refresh.
     *
     * <p>Independent of {@code dynamic_prices_enabled}: that flag gates whether item buy prices are
     * scaled, not whether this signal is maintained. It is also the signal the daily fiscal pass
     * reads, so pricing and taxation can never disagree about who is active.
     */
    double inflationMultiplier();

    /**
     * Median balance of the players counted as active, in the same units
     * {@link #inflationMultiplier()} divides by ({@code startingBalance}). {@code 0} when no player
     * is active, in which case {@link #inflationMultiplier()} is {@code 1.0}.
     */
    double medianActiveBalance();
}
