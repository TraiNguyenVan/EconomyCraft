package com.reazip.economycraft.faction;

import com.reazip.economycraft.config.FactionsSection;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class FactionEffectsTest {

    @Test
    void monarchyDamageDealtMultiplierWhenInOwnClaim() {
        FactionsSection.MonarchySettings settings = new FactionsSection.MonarchySettings();
        settings.ownClaimDamageMultiplier = 1.15;

        // Inside own claim: +15 % damage dealt
        assertEquals(1.15, FactionEffects.effectiveMonarchyDamageDealtMultiplier(settings, true), 1e-6);

        // Outside own claim: no bonus
        assertEquals(1.0, FactionEffects.effectiveMonarchyDamageDealtMultiplier(settings, false), 1e-6);

        // Null settings safety
        assertEquals(1.0, FactionEffects.effectiveMonarchyDamageDealtMultiplier(null, true), 1e-6);
    }

    @Test
    void monarchyDamageTakenMultiplierWhenInOwnClaim() {
        FactionsSection.MonarchySettings settings = new FactionsSection.MonarchySettings();
        settings.ownClaimDamageMultiplier = 1.15;

        // Inside own claim: +15 % resistance (-15 % damage taken) -> 0.85
        assertEquals(0.85, FactionEffects.effectiveMonarchyDamageTakenMultiplier(settings, true), 1e-6);

        // Outside own claim: normal damage
        assertEquals(1.0, FactionEffects.effectiveMonarchyDamageTakenMultiplier(settings, false), 1e-6);

        // Null settings safety
        assertEquals(1.0, FactionEffects.effectiveMonarchyDamageTakenMultiplier(null, true), 1e-6);
    }

    @Test
    void monarchyCustomDamageMultipliersScaleCorrectly() {
        FactionsSection.MonarchySettings settings = new FactionsSection.MonarchySettings();
        settings.ownClaimDamageMultiplier = 1.25;

        assertEquals(1.25, FactionEffects.effectiveMonarchyDamageDealtMultiplier(settings, true), 1e-6);
        assertEquals(0.75, FactionEffects.effectiveMonarchyDamageTakenMultiplier(settings, true), 1e-6);
    }

    @Test
    void anarchismSpeedBonusOnUnclaimedLand() {
        FactionsSection.AnarchismSettings settings = new FactionsSection.AnarchismSettings();
        settings.unclaimedSpeedMultiplier = 1.15;
        settings.unclaimedHorseSpeedMultiplier = 1.15;

        // On unclaimed land: +15 % speed bonus (+0.15)
        assertEquals(0.15, FactionEffects.effectiveAnarchistSpeedBonus(settings, true), 1e-6);
        assertEquals(0.15, FactionEffects.effectiveAnarchistHorseSpeedBonus(settings, true), 1e-6);

        // On claimed land: no bonus
        assertEquals(0.0, FactionEffects.effectiveAnarchistSpeedBonus(settings, false), 1e-6);
        assertEquals(0.0, FactionEffects.effectiveAnarchistHorseSpeedBonus(settings, false), 1e-6);

        // Null settings safety
        assertEquals(0.0, FactionEffects.effectiveAnarchistSpeedBonus(null, true), 1e-6);
        assertEquals(0.0, FactionEffects.effectiveAnarchistHorseSpeedBonus(null, true), 1e-6);
    }

    @Test
    void anarchismCustomSpeedMultipliersScaleCorrectly() {
        FactionsSection.AnarchismSettings settings = new FactionsSection.AnarchismSettings();
        settings.unclaimedSpeedMultiplier = 1.30;
        settings.unclaimedHorseSpeedMultiplier = 1.20;

        assertEquals(0.30, FactionEffects.effectiveAnarchistSpeedBonus(settings, true), 1e-6);
        assertEquals(0.20, FactionEffects.effectiveAnarchistHorseSpeedBonus(settings, true), 1e-6);
    }

    @Test
    void standingInOwnClaimTracking() {
        UUID player1 = UUID.randomUUID();
        UUID player2 = UUID.randomUUID();

        assertFalse(FactionEffects.isStandingInOwnClaim(player1));
        assertFalse(FactionEffects.isStandingInOwnClaim(player2));

        FactionEffects.setStandingInOwnClaimForTest(player1, true);
        assertTrue(FactionEffects.isStandingInOwnClaim(player1));
        assertFalse(FactionEffects.isStandingInOwnClaim(player2));

        FactionEffects.setStandingInOwnClaimForTest(player1, false);
        assertFalse(FactionEffects.isStandingInOwnClaim(player1));
    }
}
