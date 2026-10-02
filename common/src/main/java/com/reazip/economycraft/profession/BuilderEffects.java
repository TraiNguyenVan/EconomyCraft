package com.reazip.economycraft.profession;

import com.reazip.economycraft.EconomyConfig;
import com.reazip.economycraft.EconomyCraft;
import com.reazip.economycraft.EconomyManager;
import com.reazip.economycraft.config.ProfessionsSection;
import com.reazip.economycraft.util.IdentifierCompat;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * The Builder's {@code Thành thạo} reach bonus (P4-T5, spec 44).
 *
 * <p>This is an attribute modifier rather than a hook, which is why no mixin and no client mod are involved:
 * both the client's raycast and the server's own range check read {@code player.block_interaction_range}, so
 * one modifier widens both sides consistently.
 *
 * <p>⚠️ <b>Documented side effect, not a bug:</b> the attribute is <em>block interaction</em> range, so it also
 * widens container opening, signs and item frames, and it is the same range check P9-T14's container lock
 * composes with. Spec line 44's "only while holding a building block" is deliberately not implemented: a
 * modifier cannot be conditional on the held item, and gating it on one would kill the reach at the exact
 * moment a block is placed, because the held item is then the block that was just placed.
 */
public final class BuilderEffects {
    private static final IdentifierCompat.Id APPRENTICE_REACH = IdentifierCompat.tryParse("economycraft:builder_reach_apprentice");
    private static final IdentifierCompat.Id MASTER_REACH = IdentifierCompat.tryParse("economycraft:builder_reach_master");

    private BuilderEffects() {}

    /**
     * Applies the reach bonus that matches the player's current level, removing whatever was there before.
     *
     * <p>Remove-then-add rather than add-only is deliberate: an Apprentice who reaches Master, or a Builder who
     * drops through the rust boundary, must not keep the old amount. Two separate ids exist for the same reason
     * — with one id a demotion could only ever overwrite the value, never remove it.
     *
     * <p>Must be re-applied on join. Attribute instances are rebuilt per player, so a {@code ProfessionStore}
     * entry saying "Master" with no modifier on the player is a bug that looks exactly like "the feature is
     * broken".
     */
    public static void applyReach(ServerPlayer player) {
        if (player == null) return;
        try {
            EconomyManager eco = EconomyCraft.getManager(player.level().getServer());
            if (eco.getProfessions().professionOf(player.getUUID()) != ProfessionId.BUILDER) {
                clearReach(player);
                return;
            }

            ProfessionLevel level = eco.getProfessions().baseLevelOf(player.getUUID());
            ProfessionsSection.BuilderSettings settings = EconomyConfig.get().professions.builder;
            double bonus = effectiveReach(settings, level,
                    ProfessionEffects.resolveMultiplier(player, ProfessionId.BUILDER));

            AttributeInstance inst = player.getAttribute(Attributes.BLOCK_INTERACTION_RANGE);
            if (inst == null) return;

            clearReach(player);
            if (bonus <= 0.0D) return;

            inst.addTransientModifier(new AttributeModifier(
                    identifierFor(level), bonus, AttributeModifier.Operation.ADD_VALUE));
        } catch (Exception ignored) {
            // An effect hook must never break the login or dimension change that triggered it.
        }
    }

    public static void clearReach(ServerPlayer player) {
        if (player == null) return;
        AttributeInstance inst = player.getAttribute(Attributes.BLOCK_INTERACTION_RANGE);
        if (inst == null) return;
        Identifier masterId = identifierFor(ProfessionLevel.MASTER);
        Identifier apprenticeId = identifierFor(ProfessionLevel.APPRENTICE);
        if (inst.hasModifier(masterId)) inst.removeModifier(masterId);
        if (inst.hasModifier(apprenticeId)) inst.removeModifier(apprenticeId);
    }

    /**
     * The reach bonus actually applied, given the configured value, the level and the rust multiplier.
     *
     * <p>Pure on purpose: the arithmetic that decides how far a Builder can reach is the part most worth
     * testing, and pulling it out of {@link #applyReach} is what makes that possible without a live
     * {@code ServerPlayer}. The attribute-instance assertions P4-T7 also asks for still need a real player and
     * a gametest harness this project does not have yet.
     *
     * <p>Only a real level has a configured bonus. Rust is not a level, so it reaches the arithmetic as the
     * multiplier instead of as a third number — an admin tunes the bonus and the rust penalty in one place
     * each, and a rusty Builder keeps a reduced bonus rather than losing reach entirely, because clearing it
     * would turn the rust timer into a punishment the spec never asked for.
     */
    static double effectiveReach(ProfessionsSection.BuilderSettings settings, ProfessionLevel level, double rustMultiplier) {
        if (settings == null || level == null) return 0.0D;

        double configured = switch (level) {
            case MASTER -> settings.reachBonusMasterBlocks;
            case APPRENTICE -> settings.reachBonusApprenticeBlocks;
            case RUSTED -> 0.0D;
        };
        return configured * rustMultiplier;
    }

    private static Identifier identifierFor(ProfessionLevel level) {
        return (Identifier) (Object) (level == ProfessionLevel.MASTER ? MASTER_REACH : APPRENTICE_REACH).mcId();
    }
}
