package com.reazip.economycraft.profession;

import com.reazip.economycraft.config.ProfessionsSection;
import com.reazip.economycraft.time.WallClock;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the Soldier profession (Phase 8, spec lines 60-65, P8-T1..T4):
 * - Mob kill level-up tracking, 100 kills to reach Master (spec line 62)
 * - Sắt được tôi thế đấy damage multipliers (±5% Apprentice, ±15% Master, rust halving) (spec line 63)
 * - Andrenaline 4-second burst window and 5-minute cooldown state machine (spec line 65)
 * - Exit criteria: in-window, second effect in-window, out-of-window (cooldown), post-cooldown, Master gating
 */
class SoldierEffectsTest {

    private static final UUID SOLDIER_ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SOLDIER_BOB = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void soldierReachesMasterAt100MobKills(@TempDir Path tempDir) {
        ProfessionStore store = new ProfessionStore(tempDir.resolve("professions.json"), WallClock.SYSTEM);
        store.select(SOLDIER_ALICE, ProfessionId.SOLDIER);

        assertEquals(ProfessionLevel.APPRENTICE, store.levelOf(SOLDIER_ALICE));
        assertEquals(0, store.progressOf(SOLDIER_ALICE).progress);

        // Add 99 kills
        for (int i = 0; i < 99; i++) {
            boolean promoted = store.addProgress(SOLDIER_ALICE, 1L);
            assertFalse(promoted, "Should not be promoted before 100 kills");
            assertEquals(i + 1, store.progressOf(SOLDIER_ALICE).progress);
            assertEquals(ProfessionLevel.APPRENTICE, store.levelOf(SOLDIER_ALICE));
        }

        // 100th kill triggers promotion to MASTER
        boolean promoted = store.addProgress(SOLDIER_ALICE, 1L);
        assertTrue(promoted, "100th kill must promote Soldier to Master");
        assertEquals(100, store.progressOf(SOLDIER_ALICE).progress);
        assertEquals(ProfessionLevel.MASTER, store.levelOf(SOLDIER_ALICE));

        // Subsequent kills should not increase progress or re-promote
        boolean afterMaster = store.addProgress(SOLDIER_ALICE, 1L);
        assertFalse(afterMaster);
        assertEquals(100, store.progressOf(SOLDIER_ALICE).progress);
    }

    @Test
    void rustedSoldierCannotEarnProgress(@TempDir Path tempDir) {
        ProfessionStore store = new ProfessionStore(tempDir.resolve("professions.json"), WallClock.SYSTEM);
        store.select(SOLDIER_ALICE, ProfessionId.SOLDIER);
        store.addProgress(SOLDIER_ALICE, 100L);
        assertEquals(ProfessionLevel.MASTER, store.levelOf(SOLDIER_ALICE));

        // Switch to Builder and back to Soldier -> RUSTED
        store.select(SOLDIER_ALICE, ProfessionId.BUILDER);
        store.select(SOLDIER_ALICE, ProfessionId.SOLDIER);
        assertEquals(ProfessionLevel.RUSTED, store.levelOf(SOLDIER_ALICE));

        // Cannot earn progress while rusted
        assertFalse(store.addProgress(SOLDIER_ALICE, 1L));
    }

    @Test
    void damageMultipliersForApprenticeAndMaster() {
        ProfessionsSection.SoldierSettings settings = new ProfessionsSection.SoldierSettings();
        settings.damageTakenFactorApprentice = 0.95;
        settings.damageTakenFactorMaster = 0.85;
        settings.damageDealtFactorApprentice = 1.05;
        settings.damageDealtFactorMaster = 1.15;

        // Apprentice (not rusty)
        assertEquals(0.95, SoldierEffects.effectiveDamageTakenMultiplier(settings, ProfessionLevel.APPRENTICE, 1.0), 1e-6);
        assertEquals(1.05, SoldierEffects.effectiveDamageDealtMultiplier(settings, ProfessionLevel.APPRENTICE, 1.0), 1e-6);

        // Master (not rusty)
        assertEquals(0.85, SoldierEffects.effectiveDamageTakenMultiplier(settings, ProfessionLevel.MASTER, 1.0), 1e-6);
        assertEquals(1.15, SoldierEffects.effectiveDamageDealtMultiplier(settings, ProfessionLevel.MASTER, 1.0), 1e-6);
    }

    @Test
    void damageMultipliersHalvedWhenRusty() {
        ProfessionsSection.SoldierSettings settings = new ProfessionsSection.SoldierSettings();
        settings.damageTakenFactorApprentice = 0.95;
        settings.damageTakenFactorMaster = 0.85;
        settings.damageDealtFactorApprentice = 1.05;
        settings.damageDealtFactorMaster = 1.15;

        double rustFactor = 0.5;

        // Master when rusty: dealt bonus halved (15% -> 7.5%), taken reduction halved (15% -> 7.5%)
        assertEquals(1.075, SoldierEffects.effectiveDamageDealtMultiplier(settings, ProfessionLevel.MASTER, rustFactor), 1e-6);
        assertEquals(0.925, SoldierEffects.effectiveDamageTakenMultiplier(settings, ProfessionLevel.MASTER, rustFactor), 1e-6);

        // Apprentice when rusty: dealt bonus halved (5% -> 2.5%), taken reduction halved (5% -> 2.5%)
        assertEquals(1.025, SoldierEffects.effectiveDamageDealtMultiplier(settings, ProfessionLevel.APPRENTICE, rustFactor), 1e-6);
        assertEquals(0.975, SoldierEffects.effectiveDamageTakenMultiplier(settings, ProfessionLevel.APPRENTICE, rustFactor), 1e-6);
    }

    @Test
    void adrenalineDurationCalculation() {
        double factor = 0.5;
        assertEquals(100, SoldierEffects.effectiveAdrenalineDuration(200, factor));
        assertEquals(50, SoldierEffects.effectiveAdrenalineDuration(100, factor));
        assertEquals(1, SoldierEffects.effectiveAdrenalineDuration(1, factor));
        assertEquals(0, SoldierEffects.effectiveAdrenalineDuration(0, factor));
    }

    @Test
    void adrenalineTrackerWindowAndCooldownExitCriteria() {
        SoldierEffects.AdrenalineTracker tracker = new SoldierEffects.AdrenalineTracker();
        UUID player = UUID.randomUUID();
        int windowSeconds = 4;
        int cooldownMinutes = 5;

        // 1. In-window: First negative effect at t = 0 ms -> TRIGGERED
        assertTrue(tracker.tryTrigger(player, 0L, windowSeconds, cooldownMinutes),
                "First negative effect must trigger Adrenaline window");

        // 2. Second effect inside the same window at t = 2000 ms (2s <= 4s) -> TRIGGERED
        assertTrue(tracker.tryTrigger(player, 2000L, windowSeconds, cooldownMinutes),
                "Second effect received at 2s is within the 4s window and must trigger");

        // 2b. Third effect right at the end of the window at t = 4000 ms (4s <= 4s) -> TRIGGERED
        assertTrue(tracker.tryTrigger(player, 4000L, windowSeconds, cooldownMinutes),
                "Effect at 4s boundary must trigger");

        // 3. Out-of-window (during 5-min cooldown) at t = 5000 ms (5s) -> BLOCKED
        assertFalse(tracker.tryTrigger(player, 5000L, windowSeconds, cooldownMinutes),
                "Effect received after 4s window must be blocked by cooldown");

        // 3b. Still in cooldown at t = 100,000 ms -> BLOCKED
        assertFalse(tracker.tryTrigger(player, 100_000L, windowSeconds, cooldownMinutes),
                "Effect received mid-cooldown must be blocked");

        // 3c. Still in cooldown at t = 303,999 ms (cooldown ends at 4s + 300s = 304,000 ms) -> BLOCKED
        assertFalse(tracker.tryTrigger(player, 303_999L, windowSeconds, cooldownMinutes),
                "Effect right before cooldown expiry must be blocked");

        // 4. Post-cooldown at t = 304,000 ms (4s + 300s) -> TRIGGERED, starts new burst window
        assertTrue(tracker.tryTrigger(player, 304_000L, windowSeconds, cooldownMinutes),
                "Effect after cooldown has expired must trigger and start new window");

        // 4b. Second effect inside the new burst window at t = 306,000 ms (2s into new window) -> TRIGGERED
        assertTrue(tracker.tryTrigger(player, 306_000L, windowSeconds, cooldownMinutes),
                "Effect inside the new burst window must trigger");

        // 4c. Out of new window at t = 309,000 ms (5s after new window start at 304,000) -> BLOCKED
        assertFalse(tracker.tryTrigger(player, 309_000L, windowSeconds, cooldownMinutes),
                "Effect after the second 4s window must be blocked by new cooldown");
    }

    @Test
    void adrenalineGatingChecksMasterLevel(@TempDir Path tempDir) {
        ProfessionStore store = new ProfessionStore(tempDir.resolve("professions.json"), WallClock.SYSTEM);
        store.select(SOLDIER_BOB, ProfessionId.SOLDIER);

        // Apprentice level
        assertEquals(ProfessionLevel.APPRENTICE, store.levelOf(SOLDIER_BOB));
        assertNotEquals(ProfessionLevel.MASTER, store.levelOf(SOLDIER_BOB));

        // Reach Master
        store.addProgress(SOLDIER_BOB, 100L);
        assertEquals(ProfessionLevel.MASTER, store.levelOf(SOLDIER_BOB));

        // If rusted, levelOf returns RUSTED, which fails the Master gate
        store.select(SOLDIER_BOB, ProfessionId.BUILDER);
        store.select(SOLDIER_BOB, ProfessionId.SOLDIER);
        assertEquals(ProfessionLevel.RUSTED, store.levelOf(SOLDIER_BOB));
        assertNotEquals(ProfessionLevel.MASTER, store.levelOf(SOLDIER_BOB));
    }
}
