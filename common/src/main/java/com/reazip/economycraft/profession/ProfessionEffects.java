package com.reazip.economycraft.profession;

import com.reazip.economycraft.EconomyConfig;
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
            // The profession is read only to keep the call sites honest about what they are asking; the store
            // is the authority on which player this is.
            if (level == ProfessionLevel.RUSTED) return EconomyConfig.get().professions.rustEffectFactor;
            return 1.0D;
        } catch (Exception ignored) {
            return 1.0D;
        }
    }

    public static double resolveMultiplier(ServerPlayer player) {
        if (player == null) return 1.0D;
        return resolveMultiplier(player, getProfession(player));
    }

    /**
     * Re-applies every <em>persistent</em> profession effect — the ones the player carries rather than the ones
     * conditioned on what they are doing right now.
     *
     * <p>The distinction is D20's, and keeping it here is what stops the two halves getting confused: Builder
     * reach is persistent (it is a bare attribute modifier and always applies while the player is a Builder),
     * while Haste is conditional and lives in {@link ProfessionHaste}, which the break hook refreshes and the
     * tick expires.
     *
     * <p>Must be called whenever a level can have changed — on join, on promotion, and when a rust timer
     * completes — because attribute instances are rebuilt per player and a modifier left on a player whose level
     * moved is a bug that looks exactly like "the feature is broken". Deliberately not called every tick:
     * rewriting an attribute modifier 20 times a second is churn for no benefit.
     */
    public static void applyPersistent(ServerPlayer player) {
        if (player == null) return;
        BuilderEffects.applyReach(player);
    }

    private static ProfessionId getProfession(ServerPlayer player) {
        try {
            return EconomyCraft.getManager(player.level().getServer()).getProfessions().professionOf(player.getUUID());
        } catch (Exception ignored) {
            return null;
        }
    }
}
