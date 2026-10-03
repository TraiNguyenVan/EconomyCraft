package com.reazip.economycraft.profession;

import com.reazip.economycraft.EconomyConfig;
import com.reazip.economycraft.EconomyCraft;
import com.reazip.economycraft.EconomyManager;
import com.reazip.economycraft.config.ProfessionsSection;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Miner profession gameplay effects (Phase 6, spec lines 51-55):
 *
 * <ul>
 *   <li>{@code Lanh lợi} (spec 53) — conditional Haste II when mining trigger blocks (handled in {@link ProfessionHaste}).</li>
 *   <li>{@code Khéo tay} (spec 54) — 5% (Apprentice) / 15% (Master) chance of doubled ore drops.</li>
 *   <li>{@code Bảo hộ lao động} (spec 55) — Master only: Regeneration II for 4 s upon touching lava, on a 5-minute cooldown.</li>
 * </ul>
 */
public final class MinerEffects {
    public static final String LAVA_REGEN_COOLDOWN_KEY = "miner_lava_regen";

    private MinerEffects() {}

    public static boolean isMiner(ServerPlayer player) {
        if (player == null) return false;
        try {
            ProfessionId prof = EconomyCraft.getManager(player.level().getServer())
                    .getProfessions().professionOf(player.getUUID());
            return prof == ProfessionId.MINER;
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * {@code Khéo tay} (spec 54): rolls for a doubled ore drop when mining an ore block.
     */
    public static void applyDoubleOreDrop(EconomyManager eco, ServerPlayer player, BlockPos pos, BlockState broken) {
        if (player == null || pos == null || broken == null || eco == null) return;
        if (!EconomyConfig.get().professions.enabled) return;

        try {
            if (eco.getProfessions().professionOf(player.getUUID()) != ProfessionId.MINER) return;
            if (!eco.getBlockTags().isOre(broken)) return;

            ProfessionsSection.MinerSettings settings = EconomyConfig.get().professions.miner;
            ProfessionLevel level = eco.getProfessions().baseLevelOf(player.getUUID());
            double rustMultiplier = ProfessionEffects.resolveMultiplier(player, ProfessionId.MINER);

            double chance = effectiveDoubleDropChance(settings, level, rustMultiplier);
            if (chance <= 0.0D) return;

            if (player.getRandom().nextDouble() < chance) {
                Block.dropResources(broken, player.level(), pos);
                player.sendSystemMessage(
                        Component.literal("⛏ ")
                                .withColor(0x55FFFF)
                                .append(Component.literal("Khéo tay: x2 quặng!").withColor(0xFFFFFF)),
                        true
                );
            }
        } catch (Exception ignored) {
            // Drop doubling must never crash the block-break path
        }
    }

    /**
     * {@code Bảo hộ lao động} (spec 55): grants Regeneration II for 4 s when a Master Miner touches lava,
     * on a 5-minute cooldown.
     */
    public static void onLavaContact(EconomyManager eco, ServerPlayer player) {
        if (player == null || eco == null) return;
        if (!EconomyConfig.get().professions.enabled) return;

        try {
            if (eco.getProfessions().professionOf(player.getUUID()) != ProfessionId.MINER) return;
            if (eco.getProfessions().baseLevelOf(player.getUUID()) != ProfessionLevel.MASTER) return;
            if (!eco.getCooldowns().isReady(player.getUUID(), LAVA_REGEN_COOLDOWN_KEY)) return;

            ProfessionsSection.MinerSettings settings = EconomyConfig.get().professions.miner;
            eco.getCooldowns().startMinutes(player.getUUID(), LAVA_REGEN_COOLDOWN_KEY, settings.lavaCooldownMinutes);

            double rustMultiplier = ProfessionEffects.resolveMultiplier(player, ProfessionId.MINER);
            int durationTicks = effectiveLavaRegenTicks(settings, rustMultiplier);
            if (durationTicks <= 0) return;

            int amplifier = Math.max(0, settings.lavaRegenerationLevel - 1);
            player.addEffect(new MobEffectInstance(MobEffects.REGENERATION, durationTicks, amplifier, false, true, true));
            player.sendSystemMessage(
                    Component.literal("⛏ ")
                            .withColor(0xFFA500)
                            .append(Component.literal("Bảo hộ lao động: Hồi máu II!").withColor(0xFFFFFF)),
                    true
            );
        } catch (Exception ignored) {
            // Lava contact effect must never break server ticks
        }
    }

    // --- Pure arithmetic helpers for testing ---

    public static double effectiveDoubleDropChance(ProfessionsSection.MinerSettings settings, ProfessionLevel level, double rustMultiplier) {
        if (settings == null || level == null) return 0.0D;
        double base = switch (level) {
            case MASTER -> settings.doubleDropChanceMaster;
            case APPRENTICE -> settings.doubleDropChanceApprentice;
            case RUSTED -> 0.0D;
        };
        return base * rustMultiplier;
    }

    public static int effectiveLavaRegenTicks(ProfessionsSection.MinerSettings settings, double rustMultiplier) {
        if (settings == null) return 0;
        int baseTicks = Math.max(1, settings.lavaRegenerationSeconds) * 20;
        return (int) Math.round(baseTicks * rustMultiplier);
    }
}
