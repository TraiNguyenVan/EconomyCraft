package com.reazip.economycraft.faction;

import com.reazip.economycraft.EconomyConfig;
import com.reazip.economycraft.EconomyCraft;
import com.reazip.economycraft.EconomyManager;
import com.reazip.economycraft.config.FactionsSection;
import com.reazip.economycraft.integration.ClaimBridge;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.equine.AbstractHorse;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Faction gameplay effects (Phase 10):
 *
 * <ul>
 *   <li>Monarchy {@code Phép vua thua lệ làng} (spec 28): +15 % damage dealt and +15 % damage resistance
 *       while standing inside your own claim.</li>
 *   <li>Anarchism {@code Thoải mái} (spec 35): +15 % movement speed and +15 % horse speed on unclaimed land.</li>
 * </ul>
 */
public final class FactionEffects {
    private static final Identifier ANARCHIST_SPEED_ID = Identifier.parse("economycraft:anarchist_speed");
    private static final Identifier ANARCHIST_HORSE_SPEED_ID = Identifier.parse("economycraft:anarchist_horse_speed");

    private static final Set<UUID> PLAYERS_IN_OWN_CLAIM = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, WeakReference<AbstractHorse>> LAST_RIDDEN_HORSE = new ConcurrentHashMap<>();

    private FactionEffects() {}

    public static boolean isStandingInOwnClaim(UUID playerId) {
        return playerId != null && PLAYERS_IN_OWN_CLAIM.contains(playerId);
    }

    public static void setStandingInOwnClaimForTest(UUID playerId, boolean inOwn) {
        if (inOwn) PLAYERS_IN_OWN_CLAIM.add(playerId);
        else PLAYERS_IN_OWN_CLAIM.remove(playerId);
    }

    public static float modifyDamageDealt(ServerPlayer player, float amount) {
        if (player == null || amount <= 0.0f) return amount;
        if (!EconomyConfig.get().factions.enabled) return amount;

        try {
            EconomyManager eco = EconomyCraft.getManager(player.level().getServer());
            if (eco.getFactions().factionOf(player.getUUID()) != FactionId.MONARCHY) {
                return amount;
            }
            if (!isStandingInOwnClaim(player.getUUID())) {
                return amount;
            }
            double mult = effectiveMonarchyDamageDealtMultiplier(
                    EconomyConfig.get().factions.monarchy, true);
            return (float) (amount * mult);
        } catch (Exception ignored) {
            return amount;
        }
    }

    public static float modifyDamageTaken(ServerPlayer player, float amount) {
        if (player == null || amount <= 0.0f) return amount;
        if (!EconomyConfig.get().factions.enabled) return amount;

        try {
            EconomyManager eco = EconomyCraft.getManager(player.level().getServer());
            if (eco.getFactions().factionOf(player.getUUID()) != FactionId.MONARCHY) {
                return amount;
            }
            if (!isStandingInOwnClaim(player.getUUID())) {
                return amount;
            }
            double mult = effectiveMonarchyDamageTakenMultiplier(
                    EconomyConfig.get().factions.monarchy, true);
            return (float) (amount * mult);
        } catch (Exception ignored) {
            return amount;
        }
    }

    public static double effectiveMonarchyDamageDealtMultiplier(FactionsSection.MonarchySettings settings, boolean inOwnClaim) {
        if (settings == null || !inOwnClaim) return 1.0D;
        return settings.ownClaimDamageMultiplier;
    }

    public static double effectiveMonarchyDamageTakenMultiplier(FactionsSection.MonarchySettings settings, boolean inOwnClaim) {
        if (settings == null || !inOwnClaim) return 1.0D;
        return Math.max(0.0D, 2.0D - settings.ownClaimDamageMultiplier);
    }

    public static double effectiveAnarchistSpeedBonus(FactionsSection.AnarchismSettings settings, boolean onUnclaimedLand) {
        if (settings == null || !onUnclaimedLand) return 0.0D;
        return Math.max(0.0D, settings.unclaimedSpeedMultiplier - 1.0D);
    }

    public static double effectiveAnarchistHorseSpeedBonus(FactionsSection.AnarchismSettings settings, boolean onUnclaimedLand) {
        if (settings == null || !onUnclaimedLand) return 0.0D;
        return Math.max(0.0D, settings.unclaimedHorseSpeedMultiplier - 1.0D);
    }

    public static void updateAnarchistSpeed(ServerPlayer player, boolean apply, double bonus) {
        if (player == null) return;
        AttributeInstance inst = player.getAttribute(Attributes.MOVEMENT_SPEED);
        if (inst == null) return;
        if (apply && bonus > 0.0D) {
            if (!inst.hasModifier(ANARCHIST_SPEED_ID)) {
                inst.addTransientModifier(new AttributeModifier(
                        ANARCHIST_SPEED_ID, bonus, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
            }
        } else {
            if (inst.hasModifier(ANARCHIST_SPEED_ID)) {
                inst.removeModifier(ANARCHIST_SPEED_ID);
            }
        }
    }

    public static void updateHorseSpeed(AbstractHorse horse, boolean apply, double bonus) {
        if (horse == null) return;
        AttributeInstance inst = horse.getAttribute(Attributes.MOVEMENT_SPEED);
        if (inst == null) return;
        if (apply && bonus > 0.0D) {
            if (!inst.hasModifier(ANARCHIST_HORSE_SPEED_ID)) {
                inst.addTransientModifier(new AttributeModifier(
                        ANARCHIST_HORSE_SPEED_ID, bonus, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
            }
        } else {
            if (inst.hasModifier(ANARCHIST_HORSE_SPEED_ID)) {
                inst.removeModifier(ANARCHIST_HORSE_SPEED_ID);
            }
        }
    }

    public static void tick(EconomyManager eco, ServerPlayer player) {
        if (player == null) return;
        if (!EconomyConfig.get().factions.enabled) {
            forget(player);
            return;
        }

        // The party a player has actually joined, not their effective one. The store answers Anarchism for
        // anyone who has never chosen, so reading that raw would hand every undecided player Anarchism's +15%
        // unclaimed-land speed for free. A player earns a party's buff by joining it.
        FactionId faction = chosenFactionOf(eco, player.getUUID());
        String dim = player.level().dimension().identifier().toString();
        int x = player.getBlockX();
        int z = player.getBlockZ();

        // Monarchy: Phép vua thua lệ làng
        if (faction == FactionId.MONARCHY) {
            boolean inOwn = ClaimBridge.isOwnClaim(player.getUUID(), dim, x, z);
            if (inOwn) {
                PLAYERS_IN_OWN_CLAIM.add(player.getUUID());
            } else {
                PLAYERS_IN_OWN_CLAIM.remove(player.getUUID());
            }
        } else {
            PLAYERS_IN_OWN_CLAIM.remove(player.getUUID());
        }

        // Anarchism: Thoải mái
        if (faction == FactionId.ANARCHISM) {
            boolean unclaimed = ClaimBridge.isAvailable() && ClaimBridge.isUnclaimed(dim, x, z);
            FactionsSection.AnarchismSettings settings = EconomyConfig.get().factions.anarchism;
            double speedBonus = effectiveAnarchistSpeedBonus(settings, unclaimed);
            double horseBonus = effectiveAnarchistHorseSpeedBonus(settings, unclaimed);
            updateAnarchistSpeed(player, unclaimed, speedBonus);

            AbstractHorse currentHorse = (player.getVehicle() instanceof AbstractHorse h) ? h : null;
            WeakReference<AbstractHorse> lastRef = LAST_RIDDEN_HORSE.get(player.getUUID());
            AbstractHorse lastHorse = lastRef != null ? lastRef.get() : null;
            if (lastHorse != null && lastHorse != currentHorse) {
                updateHorseSpeed(lastHorse, false, 0.0D);
            }
            if (currentHorse != null) {
                LAST_RIDDEN_HORSE.put(player.getUUID(), new WeakReference<>(currentHorse));
                updateHorseSpeed(currentHorse, unclaimed, horseBonus);
            } else {
                LAST_RIDDEN_HORSE.remove(player.getUUID());
            }
        } else {
            updateAnarchistSpeed(player, false, 0.0D);
            WeakReference<AbstractHorse> ref = LAST_RIDDEN_HORSE.remove(player.getUUID());
            if (ref != null) {
                AbstractHorse horse = ref.get();
                if (horse != null) updateHorseSpeed(horse, false, 0.0D);
            }
        }
    }

    /**
     * The party {@code player} is subject to. A player who has not chosen falls on
     * {@link FactionId#defaultFaction()}, so they get Anarchism's unclaimed-land and horse speed until they pick a
     * party — the same rule {@code FactionTaxRules} and {@code TagDisplayService} apply, and for the same reason.
     */
    private static FactionId chosenFactionOf(EconomyManager eco, UUID player) {
        return eco.getFactions().factionOf(player);
    }

    public static void forget(ServerPlayer player) {
        if (player == null) return;
        PLAYERS_IN_OWN_CLAIM.remove(player.getUUID());
        updateAnarchistSpeed(player, false, 0.0D);
        WeakReference<AbstractHorse> ref = LAST_RIDDEN_HORSE.remove(player.getUUID());
        if (ref != null) {
            AbstractHorse horse = ref.get();
            if (horse != null) {
                updateHorseSpeed(horse, false, 0.0D);
            }
        }
    }
}
