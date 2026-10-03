package com.reazip.economycraft.profession;

import com.reazip.economycraft.config.ProfessionsSection;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the Miner profession's arithmetic, mechanics, and rust scaling (Phase 6, spec lines 51-55).
 */
class MinerEffectsTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(VanillaRegistries.createWorldLookup())
                .forEach(components -> components.apply());
    }

    private static ProfessionsSection.MinerSettings defaults() {
        return new ProfessionsSection.MinerSettings();
    }

    // --- Khéo tay (Double Ore Drop) ---

    @Test
    void doubleDropChanceMatchesConfigDefaults() {
        ProfessionsSection.MinerSettings settings = defaults();
        assertEquals(0.05D, MinerEffects.effectiveDoubleDropChance(settings, ProfessionLevel.APPRENTICE, 1.0D), 1e-9D);
        assertEquals(0.15D, MinerEffects.effectiveDoubleDropChance(settings, ProfessionLevel.MASTER, 1.0D), 1e-9D);
    }

    @Test
    void doubleDropChanceCustomSettingsOverrideDefaults() {
        ProfessionsSection.MinerSettings settings = defaults();
        settings.doubleDropChanceApprentice = 0.12D;
        settings.doubleDropChanceMaster = 0.35D;

        assertEquals(0.12D, MinerEffects.effectiveDoubleDropChance(settings, ProfessionLevel.APPRENTICE, 1.0D), 1e-9D);
        assertEquals(0.35D, MinerEffects.effectiveDoubleDropChance(settings, ProfessionLevel.MASTER, 1.0D), 1e-9D);
    }

    @Test
    void doubleDropChanceScalesWithRust() {
        ProfessionsSection.MinerSettings settings = defaults();
        assertEquals(0.025D, MinerEffects.effectiveDoubleDropChance(settings, ProfessionLevel.APPRENTICE, 0.5D), 1e-9D);
        assertEquals(0.075D, MinerEffects.effectiveDoubleDropChance(settings, ProfessionLevel.MASTER, 0.5D), 1e-9D);
    }

    @Test
    void doubleDropChanceReturnsZeroForNullOrRustedLevel() {
        ProfessionsSection.MinerSettings settings = defaults();
        assertEquals(0.0D, MinerEffects.effectiveDoubleDropChance(settings, null, 1.0D), 1e-9D);
        assertEquals(0.0D, MinerEffects.effectiveDoubleDropChance(null, ProfessionLevel.MASTER, 1.0D), 1e-9D);
        assertEquals(0.0D, MinerEffects.effectiveDoubleDropChance(settings, ProfessionLevel.RUSTED, 1.0D), 1e-9D);
    }

    // --- Bảo hộ lao động (Lava Regeneration) ---

    @Test
    void lavaRegenTicksMatchesConfigDefaults() {
        ProfessionsSection.MinerSettings settings = defaults();
        // 4 seconds at 20 ticks/sec = 80 ticks
        assertEquals(80, MinerEffects.effectiveLavaRegenTicks(settings, 1.0D));
    }

    @Test
    void lavaRegenTicksCustomSettingsOverrideDefaults() {
        ProfessionsSection.MinerSettings settings = defaults();
        settings.lavaRegenerationSeconds = 8;
        assertEquals(160, MinerEffects.effectiveLavaRegenTicks(settings, 1.0D));
    }

    @Test
    void lavaRegenTicksScalesWithRust() {
        ProfessionsSection.MinerSettings settings = defaults();
        // 80 ticks * 0.5 = 40 ticks
        assertEquals(40, MinerEffects.effectiveLavaRegenTicks(settings, 0.5D));
    }

    @Test
    void lavaRegenTicksReturnsZeroForNull() {
        assertEquals(0, MinerEffects.effectiveLavaRegenTicks(null, 1.0D));
    }

    // --- Ore and Double-Value Ore Progression ---

    @Test
    void minerProgressWorthMatchesSpecValues() {
        ProfessionsSection section = new ProfessionsSection();
        BlockTags tags = BlockTags.fromConfig(section);

        // Diamonds and gold are worth 2 progress
        assertEquals(2, tags.minerProgressFor(Blocks.DIAMOND_ORE.defaultBlockState()));
        assertEquals(2, tags.minerProgressFor(Blocks.DEEPSLATE_DIAMOND_ORE.defaultBlockState()));
        assertEquals(2, tags.minerProgressFor(Blocks.GOLD_ORE.defaultBlockState()));
        assertEquals(2, tags.minerProgressFor(Blocks.NETHER_GOLD_ORE.defaultBlockState()));

        // Non-ores give 0
        assertEquals(0, tags.minerProgressFor(Blocks.STONE.defaultBlockState()));
        assertEquals(0, tags.minerProgressFor(Blocks.DIRT.defaultBlockState()));
        assertEquals(0, tags.minerProgressFor(Blocks.OAK_LOG.defaultBlockState()));
    }

    // --- Haste II Triggers ---

    @Test
    void minerHasteTriggersMatchSpecRequirements() {
        ProfessionsSection section = new ProfessionsSection();
        BlockTags tags = BlockTags.fromConfig(section);

        // Spec line 53: stone, deepslate, tuff, netherrack
        assertTrue(tags.triggersMinerHaste(Blocks.STONE.defaultBlockState()));
        assertTrue(tags.triggersMinerHaste(Blocks.DEEPSLATE.defaultBlockState()));
        assertTrue(tags.triggersMinerHaste(Blocks.TUFF.defaultBlockState()));
        assertTrue(tags.triggersMinerHaste(Blocks.NETHERRACK.defaultBlockState()));

        // Non-trigger blocks
        assertFalse(tags.triggersMinerHaste(Blocks.DIRT.defaultBlockState()));
        assertFalse(tags.triggersMinerHaste(Blocks.GLASS.defaultBlockState()));
        assertFalse(tags.triggersMinerHaste(Blocks.OAK_PLANKS.defaultBlockState()));
    }
}
