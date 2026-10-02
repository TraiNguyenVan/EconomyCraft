package com.reazip.economycraft.profession;

import com.reazip.economycraft.config.ProfessionsSection;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D20's conditional Haste: the two tests P4-T6 makes mandatory, plus the invariants the code leans on to hold
 * them.
 *
 * <p>The regression being pinned is the one the spec's silence invites: read as an ordinary timed buff, a
 * Builder becomes permanently Hasted and a Miner can never stop tunnelling. The two halves of the fix are what
 * these cover — a block outside the trigger set grants nothing at all, and a player who stops mid-block loses
 * the effect rather than carrying the bridge window's worth of it forever.
 *
 * <p>Pure on purpose. Both decisions are pulled out of the player-facing code path so they can be asserted
 * without a live {@code ServerPlayer}, following {@link BuilderEffects#effectiveReach}: what survives untested
 * is only the {@code addEffect}/{@code removeEffect} calls themselves and the wiring from the loader mixins.
 * Those still need the gametest harness this project does not have, and are left rather than faked.
 *
 * <p>Lives in the 26.3 source set because the trigger sets are matched against real block ids, which needs the
 * registry bootstrapped.
 */
class ProfessionHasteTest {

    @BeforeAll
    static void bootstrap() {
        // Same order as BlockTagsTest / BuilderEffectsTest.
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(VanillaRegistries.createWorldLookup())
                .forEach(components -> components.apply());
    }

    /** Literal-only sets, so trigger membership is deterministic without datapacks. */
    private static BlockTags tags() {
        ProfessionsSection config = new ProfessionsSection();
        config.builder.buildingBlocks = List.of("minecraft:bricks", "minecraft:dirt");
        config.builder.hasteTriggerBlocks = List.of("minecraft:stone", "minecraft:cobblestone");
        config.miner.hasteTriggerBlocks = List.of("minecraft:stone", "minecraft:deepslate", "minecraft:tuff");
        return BlockTags.fromConfig(config);
    }

    private static ProfessionHaste.ProfessionSettings builderSettings() {
        return ProfessionHaste.settingsFor(ProfessionId.BUILDER);
    }

    private static ProfessionHaste.ProfessionSettings minerSettings() {
        return ProfessionHaste.settingsFor(ProfessionId.MINER);
    }

    private static boolean grants(ProfessionHaste.ProfessionSettings settings, BlockState state) {
        return ProfessionHaste.grantsHaste(settings, tags(), state, ProfessionLevel.APPRENTICE);
    }

    // --- P4-T6, mandatory test 1: a non-trigger block leaves no Haste on the player ---

    @Test
    void breakingANonTriggerBlockLeavesNoHaste() {
        // The regression: granting Haste from the break hook alone made every Builder permanently Hasted.
        assertFalse(grants(builderSettings(), Blocks.SAND.defaultBlockState()),
                "sand is neither a haste trigger nor a building block, so breaking it must grant nothing");
        assertFalse(grants(builderSettings(), Blocks.OAK_LOG.defaultBlockState()),
                "a Builder gets Haste for building, not for felling trees");
    }

    @Test
    void aTriggerBlockDoesGrantHaste() {
        // The counterweight to the test above: the rule is not simply "never grant", which would satisfy a
        // broken trigger set just as well as a correct one.
        assertTrue(grants(builderSettings(), Blocks.STONE.defaultBlockState()));
        assertTrue(grants(builderSettings(), Blocks.COBBLESTONE.defaultBlockState()));
    }

    @Test
    void aBuildingBlockTriggersHasteWithoutBeingListed() {
        // D20: the spec triggers on "every building block", so bricks grants Haste while absent from
        // haste_trigger_blocks. Asserted here because it is the case most likely to be lost in a config edit.
        assertTrue(grants(builderSettings(), Blocks.BRICKS.defaultBlockState()));
        assertTrue(grants(builderSettings(), Blocks.DIRT.defaultBlockState()),
                "dirt is both an explicit trigger and a building block, so it must grant");
    }

    @Test
    void minerAndBuilderUseDifferentTriggerSets() {
        // P6-T2 reuses this machinery rather than reimplementing it, so the two jobs sharing one helper must
        // still consult their own sets: cobblestone is the Builder's, not the Miner's.
        assertFalse(grants(minerSettings(), Blocks.COBBLESTONE.defaultBlockState()));
        assertTrue(grants(minerSettings(), Blocks.DEEPSLATE.defaultBlockState()));
    }

    @Test
    void theJobsWithNoHasteEffectResolveToNoSettings() {
        assertNull(ProfessionHaste.settingsFor(ProfessionId.FARMER));
        assertNull(ProfessionHaste.settingsFor(ProfessionId.MERCHANT));
        assertNull(ProfessionHaste.settingsFor(ProfessionId.SOLDIER));
        assertFalse(grants(ProfessionHaste.settingsFor(ProfessionId.FARMER), Blocks.STONE.defaultBlockState()));
    }

    @Test
    void aRustyBuilderIsGrantedNoHasteAtAll() {
        // An effect amplifier is an integer, so half of Haste I has no faithful rounding: 0 would mean a debuff
        // state granting no buff, and 1 would mean rust does not weaken Haste at all.
        assertFalse(ProfessionHaste.grantsHaste(
                builderSettings(), tags(), Blocks.STONE.defaultBlockState(), ProfessionLevel.RUSTED),
                "rust cannot be expressed as an amplifier, so it is refused outright");
    }

    @Test
    void anAbsentBlockStateGrantsNothingRatherThanThrowing() {
        assertFalse(ProfessionHaste.grantsHaste(builderSettings(), tags(), null, ProfessionLevel.APPRENTICE));
        assertFalse(ProfessionHaste.grantsHaste(null, tags(), Blocks.STONE.defaultBlockState(),
                ProfessionLevel.APPRENTICE));
    }

    // --- P4-T6, mandatory test 2: stopping mid-block removes it ---

    @Test
    void stoppingMidBlockExpiresTheHaste() {
        // The regression this file exists for: the break hook only fires on ticks carrying a mining packet, so
        // without an expiry pass a player who stops mid-block keeps the bridged window forever.
        assertTrue(ProfessionHaste.isStale(20 + 1, 0, 1),
                "one tick past the one-second window is a player who has stopped mining");
    }

    @Test
    void theHasteSurvivesItsRefreshWindow() {
        // The anti-flicker half. Mining packets are more than one tick apart, so a gap inside the window means
        // "still mining", not "stopped" — expiring here is what produced visible flicker.
        assertFalse(ProfessionHaste.isStale(0, 0, 1), "the tick it was granted on");
        assertFalse(ProfessionHaste.isStale(19, 0, 1), "one tick short of the window");
        assertFalse(ProfessionHaste.isStale(20, 0, 1), "exactly the window is still kept — the comparison is strict");
    }

    @Test
    void theWindowScalesWithTheConfiguredSeconds() {
        assertFalse(ProfessionHaste.isStale(300, 0, 15), "15s configured, 300 ticks is only half of it");
        assertTrue(ProfessionHaste.isStale(301, 0, 15));
    }

    @Test
    void aRefreshWindowOfZeroStillBuysOneTick() {
        // The config clamps this non-negative but not away from zero, and a zero window would otherwise expire a
        // player on the very tick they were granted the effect.
        assertFalse(ProfessionHaste.isStale(20, 0, 0));
        assertTrue(ProfessionHaste.isStale(21, 0, 0));
    }

    @Test
    void aWrappedTickCounterStillExpiresRatherThanPinningThePlayer() {
        // The tick counter is an int and wraps after ~3.4 years of uptime. Subtracting in int arithmetic here
        // comes out negative, which reads as "not stale" — so a player who has genuinely not refreshed since
        // before the wrap would keep the Haste permanently, the precise regression isStale guards against.
        assertTrue(ProfessionHaste.isStale(100, Integer.MIN_VALUE, 1),
                "a refresh recorded before the wrap is genuinely stale, whatever the arithmetic wraps to");
    }

    @Test
    void theInstantOfTheWrapItselfIsNotTreatedAsStale() {
        // The other side of the same wrap, and the reason the subtraction is widened rather than special-cased:
        // one tick really has elapsed here, so the player must keep their Haste.
        assertFalse(ProfessionHaste.isStale(Integer.MIN_VALUE, Integer.MAX_VALUE, 1));
    }

    @Test
    void aHastePotionThePlayerDrankIsNotStripped() {
        // The window lapsing must only remove the short Haste this class applied. Builder is Haste I
        // (amplifier 0) and Miner Haste II (amplifier 1), so a Builder's window lapsing must not take the
        // stronger Haste II a Miner — or a potion — has since put on them.
        assertTrue(ProfessionHaste.isOursToRemove(0, 1), "Builder's own Haste I is ours to remove");
        assertTrue(ProfessionHaste.isOursToRemove(1, 2), "Miner's own Haste II is ours to remove");
        assertFalse(ProfessionHaste.isOursToRemove(1, 1), "a Builder must not strip a Haste II");
        assertFalse(ProfessionHaste.isOursToRemove(0, 2), "a Miner must not strip a Haste I");
    }

    @Test
    void hasteOneIsAmplifierZero() {
        // Off-by-one here is the difference between Haste I and Haste II, and it would read as "works" on a
        // server nobody checked the icon on.
        assertEquals(0, ProfessionHaste.amplifierFor(builderSettings()));
        assertEquals(1, ProfessionHaste.amplifierFor(minerSettings()));
        assertEquals(20, ProfessionHaste.windowTicksFor(builderSettings()));
    }
}