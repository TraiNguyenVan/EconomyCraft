package com.reazip.economycraft.profession;

import com.reazip.economycraft.config.ProfessionsSection;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The Builder's reach bonus arithmetic (P4-T7).
 *
 * <p>Two regressions motivated this file. The bonus used to be hard-coded to 1.0/2.0, so every value in
 * {@code professions.builder.reach_bonus_*} was silently ignored; and a rusty Builder had their modifier
 * removed outright rather than scaled, so a 45-minute timer took a Builder from +2 blocks to none at all
 * instead of down to +1.
 *
 * <p>Lives in the 26.3 source set rather than the plain one because touching {@code BuilderEffects} runs its
 * class initialiser, which parses modifier ids against the game registries and so needs a bootstrap.
 *
 * <p>What this cannot cover: P4-T7 also asks for assertions on {@code blockInteractionRange()} itself. That
 * needs a real {@code ServerPlayer}, and this project has no gametest harness, so it is left rather than
 * faked. The Haste half of P4-T6 — absent on a non-trigger block, cleared when the player stops mid-block —
 * used to be blocked the same way and is now covered by {@link ProfessionHasteTest}, which asserts the
 * decisions rather than the {@code addEffect} call behind them.
 */
class BuilderEffectsTest {

    @BeforeAll
    static void bootstrap() {
        // Same order as BlockTagsTest / TollUiTest: detect the version before anything touches DataFixers, then
        // apply the data-component initialisers before any component is read.
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(VanillaRegistries.createWorldLookup())
                .forEach(components -> components.apply());
    }

    private static ProfessionsSection.BuilderSettings defaults() {
        return new ProfessionsSection.BuilderSettings();
    }

    @Test
    void apprenticeGetsTheConfiguredApprenticeBonus() {
        assertEquals(1.0D,
                BuilderEffects.effectiveReach(defaults(), ProfessionLevel.APPRENTICE, 1.0D), 1e-9D);
    }

    @Test
    void masterGetsTheConfiguredMasterBonus() {
        assertEquals(2.0D,
                BuilderEffects.effectiveReach(defaults(), ProfessionLevel.MASTER, 1.0D), 1e-9D);
    }

    @Test
    void theBonusComesFromConfigRatherThanALiteral() {
        ProfessionsSection.BuilderSettings settings = defaults();
        settings.reachBonusApprenticeBlocks = 3.5D;
        settings.reachBonusMasterBlocks = 7.0D;

        assertEquals(3.5D,
                BuilderEffects.effectiveReach(settings, ProfessionLevel.APPRENTICE, 1.0D), 1e-9D);
        assertEquals(7.0D,
                BuilderEffects.effectiveReach(settings, ProfessionLevel.MASTER, 1.0D), 1e-9D);
    }

    @Test
    void rustHalvesTheBonusRatherThanClearingIt() {
        // The bug: RUSTED returned no bonus at all, so the timer zeroed the reach instead of halving it.
        assertEquals(0.5D,
                BuilderEffects.effectiveReach(defaults(), ProfessionLevel.APPRENTICE, 0.5D), 1e-9D);
        assertEquals(1.0D,
                BuilderEffects.effectiveReach(defaults(), ProfessionLevel.MASTER, 0.5D), 1e-9D);
    }

    @Test
    void anAdminCanTurnTheBonusOff() {
        ProfessionsSection.BuilderSettings settings = defaults();
        settings.reachBonusMasterBlocks = 0.0D;

        assertEquals(0.0D,
                BuilderEffects.effectiveReach(settings, ProfessionLevel.MASTER, 1.0D), 1e-9D);
    }

    @Test
    void noLevelMeansNoBonus() {
        assertEquals(0.0D, BuilderEffects.effectiveReach(defaults(), null, 1.0D), 1e-9D);
        assertEquals(0.0D, BuilderEffects.effectiveReach(null, ProfessionLevel.MASTER, 1.0D), 1e-9D);
    }

    @Test
    void rustyIsNotTreatedAsAConfiguredLevelOfItsOwn() {
        // Rust arrives as the multiplier, not as a level, so asking for RUSTED directly must not also pick up a
        // level's configured value — that would apply the penalty twice on a normal rust transition.
        assertEquals(0.0D, BuilderEffects.effectiveReach(defaults(), ProfessionLevel.RUSTED, 1.0D), 1e-9D);
    }
}
