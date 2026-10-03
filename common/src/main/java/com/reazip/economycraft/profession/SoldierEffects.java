package com.reazip.economycraft.profession;

import com.reazip.economycraft.EconomyConfig;
import com.reazip.economycraft.EconomyCraft;
import com.reazip.economycraft.EconomyManager;
import com.reazip.economycraft.config.ProfessionsSection;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Soldier profession gameplay effects (Phase 8, spec lines 60-65):
 *
 * <ul>
 *   <li>Level-up tracking (spec 62): 100 mobs killed by player.</li>
 *   <li>{@code Sắt được tôi thế đấy} (spec 63): −5 % damage taken / +5 % damage dealt (Apprentice),
 *       ±15 % (Master), scaled by 0.5 when rusty.</li>
 *   <li>{@code Andrenaline} (spec 65): Master only. On receiving a negative effect, halve the duration of
 *       currently active negative effects and incoming negative effects, but only inside a 4 s window from
 *       the first negative effect; followed by a 5-minute cooldown blocking re-activation.</li>
 * </ul>
 */
public final class SoldierEffects {
    private static final AdrenalineTracker ADRENALINE_TRACKER = new AdrenalineTracker();

    private SoldierEffects() {}

    public static AdrenalineTracker getAdrenalineTracker() {
        return ADRENALINE_TRACKER;
    }

    public static boolean isSoldier(ServerPlayer player) {
        if (player == null) return false;
        try {
            ProfessionId prof = EconomyCraft.getManager(player.level().getServer())
                    .getProfessions().professionOf(player.getUUID());
            return prof == ProfessionId.SOLDIER;
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * Determines whether an entity is considered a mob/monster ("quái") for the Soldier level-up condition.
     */
    public static boolean isQualifyingMob(LivingEntity victim) {
        if (victim == null || victim instanceof Player) return false;
        return victim instanceof Enemy || victim.getType().getCategory() == MobCategory.MONSTER;
    }

    /**
     * {@code Sắt được tôi thế đấy} (spec 63): reduces damage taken by 5 % (Apprentice) / 15 % (Master).
     */
    public static float modifyDamageTaken(ServerPlayer player, float amount) {
        if (player == null || amount <= 0.0F) return amount;
        if (!EconomyConfig.get().professions.enabled) return amount;

        try {
            EconomyManager eco = EconomyCraft.getManager(player.level().getServer());
            if (eco.getProfessions().professionOf(player.getUUID()) != ProfessionId.SOLDIER) {
                return amount;
            }

            ProfessionsSection.SoldierSettings settings = EconomyConfig.get().professions.soldier;
            ProfessionLevel level = eco.getProfessions().baseLevelOf(player.getUUID());
            double rustMultiplier = ProfessionEffects.resolveMultiplier(player, ProfessionId.SOLDIER);

            double multiplier = effectiveDamageTakenMultiplier(settings, level, rustMultiplier);
            return (float) (amount * multiplier);
        } catch (Exception ignored) {
            return amount;
        }
    }

    /**
     * {@code Sắt được tôi thế đấy} (spec 63): increases damage dealt by 5 % (Apprentice) / 15 % (Master).
     */
    public static float modifyDamageDealt(ServerPlayer player, float amount) {
        if (player == null || amount <= 0.0F) return amount;
        if (!EconomyConfig.get().professions.enabled) return amount;

        try {
            EconomyManager eco = EconomyCraft.getManager(player.level().getServer());
            if (eco.getProfessions().professionOf(player.getUUID()) != ProfessionId.SOLDIER) {
                return amount;
            }

            ProfessionsSection.SoldierSettings settings = EconomyConfig.get().professions.soldier;
            ProfessionLevel level = eco.getProfessions().baseLevelOf(player.getUUID());
            double rustMultiplier = ProfessionEffects.resolveMultiplier(player, ProfessionId.SOLDIER);

            double multiplier = effectiveDamageDealtMultiplier(settings, level, rustMultiplier);
            return (float) (amount * multiplier);
        } catch (Exception ignored) {
            return amount;
        }
    }

    /**
     * {@code Andrenaline} (spec 65): Master only. Halves the duration of incoming and active negative effects
     * if inside the 4-second window from the first negative effect; afterwards enters 5-minute cooldown.
     */
    public static MobEffectInstance onIncomingEffect(ServerPlayer player, MobEffectInstance effect) {
        if (player == null || effect == null) return effect;
        if (!EconomyConfig.get().professions.enabled) return effect;

        try {
            if (effect.getEffect().value().getCategory() != MobEffectCategory.HARMFUL) {
                return effect;
            }

            EconomyManager eco = EconomyCraft.getManager(player.level().getServer());
            ProfessionStore store = eco.getProfessions();
            if (store.professionOf(player.getUUID()) != ProfessionId.SOLDIER) {
                return effect;
            }
            if (store.levelOf(player.getUUID()) != ProfessionLevel.MASTER) {
                return effect;
            }

            ProfessionsSection.SoldierSettings settings = EconomyConfig.get().professions.soldier;
            long nowMs = System.currentTimeMillis();
            if (!ADRENALINE_TRACKER.tryTrigger(player.getUUID(), nowMs, settings.adrenalineWindowSeconds, settings.adrenalineCooldownMinutes)) {
                return effect;
            }

            int halvedDuration = effectiveAdrenalineDuration(effect.getDuration(), settings.adrenalineDurationFactor);
            MobEffectInstance modified = new MobEffectInstance(
                    effect.getEffect(),
                    halvedDuration,
                    effect.getAmplifier(),
                    effect.isAmbient(),
                    effect.isVisible(),
                    effect.showIcon()
            );

            // Halve existing active harmful effects on player
            for (MobEffectInstance active : new ArrayList<>(player.getActiveEffects())) {
                if (active.getEffect().value().getCategory() == MobEffectCategory.HARMFUL) {
                    int activeHalved = effectiveAdrenalineDuration(active.getDuration(), settings.adrenalineDurationFactor);
                    if (activeHalved < active.getDuration()) {
                        player.forceAddEffect(new MobEffectInstance(
                                active.getEffect(),
                                activeHalved,
                                active.getAmplifier(),
                                active.isAmbient(),
                                active.isVisible(),
                                active.showIcon()
                        ), player);
                    }
                }
            }

            player.sendSystemMessage(
                    Component.literal("⚔ ")
                            .withColor(0xFF5555)
                            .append(Component.literal("Andrenaline: Giảm 50% thời gian hiệu ứng bất lợi!").withColor(0xFFFFFF)),
                    true
            );

            return modified;
        } catch (Exception ignored) {
            return effect;
        }
    }

    // --- Pure arithmetic and tracker helpers for testing ---

    public static double effectiveDamageTakenMultiplier(ProfessionsSection.SoldierSettings settings, ProfessionLevel level, double rustMultiplier) {
        if (settings == null || level == null) return 1.0D;
        double baseFactor = switch (level) {
            case MASTER -> settings.damageTakenFactorMaster;
            case APPRENTICE, RUSTED -> settings.damageTakenFactorApprentice;
        };
        double reduction = (1.0D - baseFactor) * rustMultiplier;
        return Math.max(0.0D, 1.0D - reduction);
    }

    public static double effectiveDamageDealtMultiplier(ProfessionsSection.SoldierSettings settings, ProfessionLevel level, double rustMultiplier) {
        if (settings == null || level == null) return 1.0D;
        double baseFactor = switch (level) {
            case MASTER -> settings.damageDealtFactorMaster;
            case APPRENTICE, RUSTED -> settings.damageDealtFactorApprentice;
        };
        double bonus = (baseFactor - 1.0D) * rustMultiplier;
        return Math.max(0.0D, 1.0D + bonus);
    }

    public static int effectiveAdrenalineDuration(int originalDuration, double factor) {
        if (originalDuration <= 0) return 0;
        return Math.max(1, (int) Math.round(originalDuration * factor));
    }

    /**
     * In-memory burst window and cooldown tracker for Soldier's Andrenaline ability.
     */
    public static final class AdrenalineTracker {
        private final Map<UUID, Long> windowStartByPlayer = new ConcurrentHashMap<>();

        public boolean tryTrigger(UUID player, long nowMs, int windowSeconds, int cooldownMinutes) {
            if (player == null) return false;
            long windowMs = windowSeconds * 1000L;
            long cooldownMs = cooldownMinutes * 60L * 1000L;
            Long start = windowStartByPlayer.get(player);
            if (start == null) {
                windowStartByPlayer.put(player, nowMs);
                return true;
            }
            long elapsed = nowMs - start;
            if (elapsed < 0L) {
                windowStartByPlayer.put(player, nowMs);
                return true;
            }
            if (elapsed <= windowMs) {
                return true;
            }
            if (elapsed < windowMs + cooldownMs) {
                return false;
            }
            // Cooldown expired; start a new burst window
            windowStartByPlayer.put(player, nowMs);
            return true;
        }

        public Long getWindowStart(UUID player) {
            return windowStartByPlayer.get(player);
        }

        public void reset(UUID player) {
            windowStartByPlayer.remove(player);
        }

        public void clear() {
            windowStartByPlayer.clear();
        }
    }
}
