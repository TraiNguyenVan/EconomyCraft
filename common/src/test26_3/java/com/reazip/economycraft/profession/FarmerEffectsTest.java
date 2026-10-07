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
 * Tests for the Farmer profession's arithmetic, mechanics, and rust scaling (Phase 5, spec lines 46-50).
 */
class FarmerEffectsTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(VanillaRegistries.createWorldLookup())
                .forEach(components -> components.apply());
    }

    private static ProfessionsSection.FarmerSettings defaults() {
        return new ProfessionsSection.FarmerSettings();
    }

    // --- Tươi tốt (Crop Boost) ---

    @Test
    void cropBoostChanceMatchesConfigDefaults() {
        ProfessionsSection.FarmerSettings settings = defaults();
        assertEquals(0.10D, FarmerEffects.effectiveCropBoostChance(settings, ProfessionLevel.APPRENTICE, 1.0D), 1e-9D);
        assertEquals(0.20D, FarmerEffects.effectiveCropBoostChance(settings, ProfessionLevel.MASTER, 1.0D), 1e-9D);
    }

    @Test
    void cropBoostChanceCustomSettingsOverrideDefaults() {
        ProfessionsSection.FarmerSettings settings = defaults();
        settings.cropBoostChanceApprentice = 0.25D;
        settings.cropBoostChanceMaster = 0.50D;

        assertEquals(0.25D, FarmerEffects.effectiveCropBoostChance(settings, ProfessionLevel.APPRENTICE, 1.0D), 1e-9D);
        assertEquals(0.50D, FarmerEffects.effectiveCropBoostChance(settings, ProfessionLevel.MASTER, 1.0D), 1e-9D);
    }

    @Test
    void cropBoostChanceScalesWithRust() {
        ProfessionsSection.FarmerSettings settings = defaults();
        assertEquals(0.05D, FarmerEffects.effectiveCropBoostChance(settings, ProfessionLevel.APPRENTICE, 0.5D), 1e-9D);
        assertEquals(0.10D, FarmerEffects.effectiveCropBoostChance(settings, ProfessionLevel.MASTER, 0.5D), 1e-9D);
    }

    @Test
    void cropBoostChanceReturnsZeroForNullOrRustedLevel() {
        ProfessionsSection.FarmerSettings settings = defaults();
        assertEquals(0.0D, FarmerEffects.effectiveCropBoostChance(settings, null, 1.0D), 1e-9D);
        assertEquals(0.0D, FarmerEffects.effectiveCropBoostChance(null, ProfessionLevel.MASTER, 1.0D), 1e-9D);
        assertEquals(0.0D, FarmerEffects.effectiveCropBoostChance(settings, ProfessionLevel.RUSTED, 1.0D), 1e-9D);
    }

    // --- Chăm sóc: Breeding Cooldown Discount ---

    @Test
    void breedingCooldownDiscountMatchesConfigDefaults() {
        ProfessionsSection.FarmerSettings settings = defaults();
        assertEquals(0.10D, FarmerEffects.effectiveBreedingCooldownDiscount(settings, ProfessionLevel.APPRENTICE, 1.0D), 1e-9D);
        assertEquals(0.20D, FarmerEffects.effectiveBreedingCooldownDiscount(settings, ProfessionLevel.MASTER, 1.0D), 1e-9D);
    }

    @Test
    void breedingCooldownDiscountScalesWithRust() {
        ProfessionsSection.FarmerSettings settings = defaults();
        assertEquals(0.05D, FarmerEffects.effectiveBreedingCooldownDiscount(settings, ProfessionLevel.APPRENTICE, 0.5D), 1e-9D);
        assertEquals(0.10D, FarmerEffects.effectiveBreedingCooldownDiscount(settings, ProfessionLevel.MASTER, 0.5D), 1e-9D);
    }

    @Test
    void breedingCooldownDiscountReturnsZeroForNullOrRustedLevel() {
        ProfessionsSection.FarmerSettings settings = defaults();
        assertEquals(0.0D, FarmerEffects.effectiveBreedingCooldownDiscount(settings, null, 1.0D), 1e-9D);
        assertEquals(0.0D, FarmerEffects.effectiveBreedingCooldownDiscount(null, ProfessionLevel.MASTER, 1.0D), 1e-9D);
        assertEquals(0.0D, FarmerEffects.effectiveBreedingCooldownDiscount(settings, ProfessionLevel.RUSTED, 1.0D), 1e-9D);
    }

    // --- Chăm sóc: Baby Growth Multiplier ---

    @Test
    void babyGrowthMultiplierMatchesConfigDefaults() {
        ProfessionsSection.FarmerSettings settings = defaults();
        assertEquals(1.15D, FarmerEffects.effectiveBabyGrowthMultiplier(settings, ProfessionLevel.APPRENTICE, 1.0D), 1e-9D);
        assertEquals(1.30D, FarmerEffects.effectiveBabyGrowthMultiplier(settings, ProfessionLevel.MASTER, 1.0D), 1e-9D);
    }

    @Test
    void babyGrowthMultiplierScalesWithRust() {
        ProfessionsSection.FarmerSettings settings = defaults();
        // Apprentice: 1.0 + (1.15 - 1.0) * 0.5 = 1.075
        assertEquals(1.075D, FarmerEffects.effectiveBabyGrowthMultiplier(settings, ProfessionLevel.APPRENTICE, 0.5D), 1e-9D);
        // Master: 1.0 + (1.30 - 1.0) * 0.5 = 1.15
        assertEquals(1.15D, FarmerEffects.effectiveBabyGrowthMultiplier(settings, ProfessionLevel.MASTER, 0.5D), 1e-9D);
    }

    @Test
    void babyGrowthAgeShortensVanillaMaturityTime() {
        int vanillaAge = -24000;
        double apprenticeFactor = 1.15D;
        int apprenticeAge = (int) Math.round(vanillaAge / apprenticeFactor);
        assertEquals(-20870, apprenticeAge);

        double masterFactor = 1.30D;
        int masterAge = (int) Math.round(vanillaAge / masterFactor);
        assertEquals(-18462, masterAge);
    }

    @Test
    void babyGrowthMultiplierReturnsOneForNullOrRustedLevel() {
        ProfessionsSection.FarmerSettings settings = defaults();
        assertEquals(1.0D, FarmerEffects.effectiveBabyGrowthMultiplier(settings, null, 1.0D), 1e-9D);
        assertEquals(1.0D, FarmerEffects.effectiveBabyGrowthMultiplier(null, ProfessionLevel.MASTER, 1.0D), 1e-9D);
        assertEquals(1.0D, FarmerEffects.effectiveBabyGrowthMultiplier(settings, ProfessionLevel.RUSTED, 1.0D), 1e-9D);
    }

    // --- Khéo léo: Bonus Food Output ---

    @Test
    void bonusOutputChanceMatchesConfigDefaults() {
        ProfessionsSection.FarmerSettings settings = defaults();
        assertEquals(0.01D, FarmerEffects.effectiveBonusOutputChance(settings, ProfessionLevel.APPRENTICE, 1.0D), 1e-9D);
        assertEquals(0.05D, FarmerEffects.effectiveBonusOutputChance(settings, ProfessionLevel.MASTER, 1.0D), 1e-9D);
    }

    @Test
    void bonusOutputChanceScalesWithRust() {
        ProfessionsSection.FarmerSettings settings = defaults();
        assertEquals(0.005D, FarmerEffects.effectiveBonusOutputChance(settings, ProfessionLevel.APPRENTICE, 0.5D), 1e-9D);
        assertEquals(0.025D, FarmerEffects.effectiveBonusOutputChance(settings, ProfessionLevel.MASTER, 0.5D), 1e-9D);
    }

    @Test
    void bonusOutputChanceReturnsZeroForNullOrRustedLevel() {
        ProfessionsSection.FarmerSettings settings = defaults();
        assertEquals(0.0D, FarmerEffects.effectiveBonusOutputChance(settings, null, 1.0D), 1e-9D);
        assertEquals(0.0D, FarmerEffects.effectiveBonusOutputChance(null, ProfessionLevel.MASTER, 1.0D), 1e-9D);
        assertEquals(0.0D, FarmerEffects.effectiveBonusOutputChance(settings, ProfessionLevel.RUSTED, 1.0D), 1e-9D);
    }

    // --- Crop Detection ---

    @Test
    void isCropRecognizesCropsAndExcludesNonCrops() {
        assertTrue(FarmerEffects.isCrop(Blocks.WHEAT.defaultBlockState()));
        assertTrue(FarmerEffects.isCrop(Blocks.CARROTS.defaultBlockState()));
        assertTrue(FarmerEffects.isCrop(Blocks.POTATOES.defaultBlockState()));
        assertTrue(FarmerEffects.isCrop(Blocks.BEETROOTS.defaultBlockState()));
        assertTrue(FarmerEffects.isCrop(Blocks.PUMPKIN_STEM.defaultBlockState()));
        assertTrue(FarmerEffects.isCrop(Blocks.MELON_STEM.defaultBlockState()));
        assertTrue(FarmerEffects.isCrop(Blocks.COCOA.defaultBlockState()));
        assertTrue(FarmerEffects.isCrop(Blocks.OAK_SAPLING.defaultBlockState()));
        assertTrue(FarmerEffects.isCrop(Blocks.BIRCH_SAPLING.defaultBlockState()));
        assertTrue(FarmerEffects.isCrop(Blocks.SUGAR_CANE.defaultBlockState()));
        assertTrue(FarmerEffects.isCrop(Blocks.CACTUS.defaultBlockState()));
        assertTrue(FarmerEffects.isCrop(Blocks.NETHER_WART.defaultBlockState()));
        assertTrue(FarmerEffects.isCrop(Blocks.BAMBOO.defaultBlockState()));
        assertTrue(FarmerEffects.isCrop(Blocks.SWEET_BERRY_BUSH.defaultBlockState()));

        assertFalse(FarmerEffects.isCrop(Blocks.STONE.defaultBlockState()));
        assertFalse(FarmerEffects.isCrop(Blocks.DIRT.defaultBlockState()));
        assertFalse(FarmerEffects.isCrop(Blocks.GRASS_BLOCK.defaultBlockState()));
        assertFalse(FarmerEffects.isCrop(Blocks.OAK_LOG.defaultBlockState()));
        assertFalse(FarmerEffects.isCrop(null));
    }
}
