package com.reazip.economycraft.profession;

import com.reazip.economycraft.EconomyCraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * The single resolver for profession effects: returns the effective multiplier and any level-specific
 * values, taking rust into account.
 *
 * <p>All profession logic reads their numbers only through this, so the rust rule (effects × 0.5 while
 * rusty) cannot be forgotten. No status effects are applied here — callers handle their own.
 */
public final class ProfessionEffects {
    private ProfessionEffects() {}

    public static double resolveMultiplier(ServerPlayer player, ProfessionId profession) {
        if (player == null || profession == null) return 1.0D;
        return resolveMultiplier(player.getUUID(), player.level().getServer(), profession);
    }

    public static double resolveMultiplier(UUID playerId, MinecraftServer server, ProfessionId profession) {
        if (playerId == null || server == null || profession == null) return 1.0D;
        try {
            ProfessionStore store = EconomyCraft.getManager(server).getProfessions();
            ProfessionLevel level = store.levelOf(playerId);
            if (level == null) return 1.0D;
            if (level == ProfessionLevel.RUSTED) return 0.5D;
            return 1.0D;
        } catch (Exception ignored) {
            return 1.0D;
        }
    }

    public static double resolveMultiplier(ServerPlayer player) {
        if (player == null) return 1.0D;
        return resolveMultiplier(player, getProfession(player));
    }

    private static ProfessionId getProfession(ServerPlayer player) {
        try {
            return EconomyCraft.getManager(player.level().getServer()).getProfessions().professionOf(player.getUUID());
        } catch (Exception ignored) {
            return null;
        }
    }
}
