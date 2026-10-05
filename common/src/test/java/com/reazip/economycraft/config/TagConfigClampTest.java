package com.reazip.economycraft.config;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Phase 2 tuning surface: every new key is clamped, and clamping never throws.
 *
 * <p>The spec's numbers are defaults, not constraints. An admin who wants a 200 % income tax rate must be able
 * to type one and be told what happened; an admin who types {@code "abc"} must get the default rather than a
 * server that refuses to start.
 */
class TagConfigClampTest {

    @Test
    void defaultsMatchTheSpec() {
        FactionsSection factions = new FactionsSection();

        assertTrue(factions.enabled);
        assertEquals(30, factions.selectionLockoutHours);
        assertEquals(45, factions.levyIntervalMinutes);
        assertEquals(10L, factions.communism.partyFee);
        assertEquals(10_000L, factions.communism.incomeTaxTier1Threshold);
        assertEquals(0.0025, factions.communism.incomeTaxTier1Rate);
        assertEquals(0.00375, factions.communism.incomeTaxTier2Rate);
        assertEquals(0.00625, factions.communism.incomeTaxTier3Rate);
        assertEquals(0.5, factions.communism.tollTaxExemptChance);
        assertEquals(0.025, factions.capitalism.dailyTaxRate);
        assertTrue(factions.capitalism.useGlobalInflation);
        assertEquals(0.15, factions.capitalism.concentrationReferenceShare);
        assertEquals(5.0, factions.capitalism.concentrationMaxMultiplier);
        assertEquals(0.25, factions.capitalism.maxRateChangePerDay);
        assertEquals(1.25, factions.capitalism.tollTaxMultiplier);
        assertEquals(0.01, factions.monarchy.dailyTaxRate, "D19: Monarchy's own rate, well under Capitalism's");
        assertEquals(1000.0, factions.monarchy.moneySupplyReferencePerPlayer);
        assertEquals(3.0, factions.monarchy.moneySupplyInflationMax);
        assertEquals(0.5, factions.monarchy.claimCostMultiplier);
        assertEquals(1.15, factions.monarchy.ownClaimDamageMultiplier);
        assertEquals(1.15, factions.anarchism.unclaimedSpeedMultiplier);
        assertEquals(1.15, factions.anarchism.unclaimedHorseSpeedMultiplier);
    }

    @Test
    void professionDefaultsMatchTheSpec() {
        ProfessionsSection professions = new ProfessionsSection();

        assertTrue(professions.enabled);
        assertEquals(30, professions.selectionLockoutHours);
        assertEquals(45, professions.rustOnlineMinutes);
        assertEquals(0.5, professions.rustEffectFactor);
        assertEquals(1000, professions.builder.levelUpCount);
        assertEquals(1, professions.builder.hasteLevel);
        assertEquals(1, professions.builder.hasteRefreshSeconds, "D20: a refresh window, not a duration");
        assertEquals(1, professions.miner.hasteRefreshSeconds);
        assertEquals(300, professions.farmer.levelUpCount);
        assertEquals(24, professions.farmer.cropBoostRadiusBlocks);
        assertEquals(4, professions.farmer.cropBoostCooldownMinutes);
        assertEquals(270, professions.miner.levelUpCount);
        assertEquals(List.of("#minecraft:ores"), professions.miner.oreTags);
        assertEquals(2, professions.miner.lavaRegenerationLevel);
        assertEquals(4, professions.miner.lavaRegenerationSeconds);
        assertEquals(5, professions.miner.lavaCooldownMinutes);
        assertEquals(50, professions.merchant.villagerTradeCount);
        assertEquals(20, professions.merchant.maxTradesPerVillager);
        assertEquals(5, professions.merchant.auctionPurchaseCount);
        assertEquals(100, professions.soldier.killCount);
        assertEquals(4, professions.soldier.adrenalineWindowSeconds);
    }

    @Test
    void ratesAboveOneAreClamped() {
        FactionsSection factions = new FactionsSection();
        factions.capitalism.dailyTaxRate = 2.5;
        factions.communism.incomeTaxTier1Rate = -1.0;

        factions.clamp();

        assertEquals(1.0, factions.capitalism.dailyTaxRate, "a rate above 100 % is a decimal-factor mistake");
        assertEquals(0.0, factions.communism.incomeTaxTier1Rate);
    }

    @Test
    void chancesAreClampedToo() {
        ProfessionsSection professions = new ProfessionsSection();
        professions.farmer.cropBoostChanceMaster = 4.0;
        professions.miner.doubleDropChanceApprentice = -0.5;

        professions.clamp();

        assertEquals(1.0, professions.farmer.cropBoostChanceMaster);
        assertEquals(0.0, professions.miner.doubleDropChanceApprentice);
    }

    @Test
    void negativeCountsClampToZero() {
        FactionsSection factions = new FactionsSection();
        factions.selectionLockoutHours = -30;
        factions.levyIntervalMinutes = -1;
        factions.communism.partyFee = -500L;

        factions.clamp();

        assertEquals(0, factions.selectionLockoutHours, "0 hours is how a lockout is disabled");
        assertEquals(0, factions.levyIntervalMinutes);
        assertEquals(0L, factions.communism.partyFee, "a negative fee would pay the player to join a party");
    }

    @Test
    void negativeMultipliersAreClampedRatherThanInvertingTheEffect() {
        ProfessionsSection professions = new ProfessionsSection();
        professions.farmer.babyGrowthFactorApprentice = -0.5;
        professions.soldier.damageTakenFactorMaster = -1.0;

        professions.clamp();

        assertEquals(0.0, professions.farmer.babyGrowthFactorApprentice);
        assertEquals(0.0, professions.soldier.damageTakenFactorMaster);
    }

    @Test
    void coloursClampTo24Bits() {
        FactionsSection factions = new FactionsSection();
        factions.communism.color = -1;
        factions.anarchism.color = 0x1FF_5555;

        factions.clamp();

        assertEquals(0, factions.communism.color, "a negative colour is an alpha channel that got pasted in");
        assertEquals(ConfigClamp.MAX_COLOR, factions.anarchism.color);
    }

    @Test
    void multiCharacterIconsFallBack() {
        ProfessionsSection professions = new ProfessionsSection();
        professions.builder.icon = "Builder";
        professions.miner.icon = "";

        professions.clamp();

        assertEquals(1, professions.builder.icon.codePointCount(0, professions.builder.icon.length()),
                "vanilla has no fallback font, so a word would render as a word beside the player's name");
        assertEquals(1, professions.miner.icon.codePointCount(0, professions.miner.icon.length()));
    }

    @Test
    void concentrationBoundsCannotCross() {
        FactionsSection factions = new FactionsSection();
        factions.capitalism.concentrationMinMultiplier = 2.0;
        factions.capitalism.concentrationMaxMultiplier = 0.5;

        factions.clamp();

        assertTrue(factions.capitalism.concentrationMaxMultiplier >= factions.capitalism.concentrationMinMultiplier,
                "a max below the min would make the elasticity formula produce nonsense");
    }

    @Test
    void blankListEntriesAreDroppedButAnEmptyListIsKept() {
        ProfessionsSection professions = new ProfessionsSection();
        professions.builder.buildingBlocks = Arrays.asList("minecraft:stone", "  ", null);
        professions.miner.oreTags = List.of();

        professions.clamp();

        assertEquals(List.of("minecraft:stone"), professions.builder.buildingBlocks);
        assertTrue(professions.miner.oreTags.isEmpty(),
                "an empty list is a legitimate way to switch a whole set off, so it must survive clamping");
    }

    @Test
    void effectLevelsStayInByteRange() {
        ProfessionsSection professions = new ProfessionsSection();
        professions.builder.hasteLevel = 900;
        professions.miner.lavaRegenerationLevel = -3;

        professions.clamp();

        assertEquals(ConfigClamp.MAX_EFFECT_LEVEL, professions.builder.hasteLevel);
        assertEquals(0, professions.miner.lavaRegenerationLevel);
    }

    @Test
    void clampIsIdempotent() {
        FactionsSection factions = new FactionsSection();
        factions.capitalism.dailyTaxRate = 12.0;
        factions.communism.icon = "x";

        factions.clamp();
        double rate = factions.capitalism.dailyTaxRate;
        String icon = factions.communism.icon;

        factions.clamp();

        assertEquals(rate, factions.capitalism.dailyTaxRate, "clamping twice must not drift");
        assertEquals(icon, factions.communism.icon);
    }

    @Test
    void clampHelpersBehaveAsDocumented() {
        assertEquals(1.0, ConfigClamp.percentage("x", 9.0));
        assertEquals(0.0, ConfigClamp.percentage("x", -9.0));
        assertEquals(3.0, ConfigClamp.exponent("x", 3.0));
        assertEquals(ConfigClamp.MAX_EXPONENT, ConfigClamp.exponent("x", 999.0));
        assertEquals(7.0, ConfigClamp.multiplier("x", 7.0));
        assertEquals("☭", ConfigClamp.icon("x", "☭", "$"));
        assertEquals("$", ConfigClamp.icon("x", null, "$"));
        assertEquals(List.of("a", "b"), ConfigClamp.cleanList("x", Arrays.asList("a", " ", "b ")),
                "blanks are dropped, surrounding whitespace trimmed");
    }

    // --- D19: Monarchy's money-supply inflation ---

    @Test
    void monarchyMoneySupplyKeysClampToSomethingUsable() {
        FactionsSection factions = new FactionsSection();
        factions.monarchy.moneySupplyReferencePerPlayer = -1000.0;
        factions.monarchy.moneySupplyInflationMax = 0.1;
        factions.monarchy.dailyTaxRate = 3.0;

        factions.clamp();

        assertEquals(0.0, factions.monarchy.moneySupplyReferencePerPlayer,
                "a negative reference would make the inflation factor negative");
        assertEquals(1.0, factions.monarchy.moneySupplyInflationMax,
                "below 1 the ceiling would silently mute the tax entirely");
        assertEquals(1.0, factions.monarchy.dailyTaxRate);
    }

    @Test
    void hasteRefreshWindowClampsButNeverGoesNegative() {
        ProfessionsSection professions = new ProfessionsSection();
        professions.builder.hasteRefreshSeconds = -5;

        professions.clamp();

        assertEquals(0, professions.builder.hasteRefreshSeconds,
                "0 means the effect is applied and cleared within one tick");
    }
}
