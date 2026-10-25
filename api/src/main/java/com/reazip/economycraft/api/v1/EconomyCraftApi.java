package com.reazip.economycraft.api.v1;

import net.minecraft.server.MinecraftServer;

import java.util.OptionalLong;

/** Stable, server-side entry point for EconomyCraft API v1. */
public interface EconomyCraftApi {
    static EconomyCraftApi get(MinecraftServer server) {
        return EconomyCraftApiAccess.get(server);
    }

    BalanceApi balances();
    PriceApi prices();
    LeaderboardApi leaderboard();
    BalanceEvents balanceEvents();

    /** Read-only access to the player's selected faction. */
    FactionApi factions();

    String formatMoney(long amount);
    String formatMoneyChat(long amount);
    OptionalLong parseMoney(String input);

    /** Current active-player balance multiplier used by dynamic pricing and fiscal reporting. */
    double inflationMultiplier();
    double medianActiveBalance();
}
