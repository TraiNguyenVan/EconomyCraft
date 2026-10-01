package com.reazip.economycraft.profession;

import com.reazip.economycraft.time.MutableClock;
import com.reazip.economycraft.util.AsyncFileWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Profession selection, progress and the {@code Lụt nghề} rust rule (spec §3).
 *
 * <p>The rust rule is the whole reason this store has the shape it does, so most of these cases are about the
 * transitions: leaving at Master, coming back, and the timer that follows.
 */
class ProfessionStoreTest {

    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();
    private static final long LOCKOUT_HOURS = 30L;
    private static final int RUST_MINUTES = 45;

    @TempDir
    Path dir;

    private final MutableClock clock = new MutableClock(1_700_000_000_000L);

    private ProfessionStore store() {
        return new ProfessionStore(dir.resolve("professions.json"), clock);
    }

    private void master(ProfessionStore store, UUID player, ProfessionId profession) {
        store.select(player, profession);
        assertTrue(store.addProgress(player, ProfessionStore.levelUpCountFor(profession)));
    }

    @Test
    void noProfessionByDefault() {
        assertNull(store().professionOf(ALICE), "there is no safe default profession: every one grants buffs");
    }

    @Test
    void noRecordIsWrittenUntilAChoiceIsMade() {
        ProfessionStore store = store();

        store.professionOf(ALICE);

        assertNull(store.existingProgressOf(ALICE));
        assertNull(store.levelOf(ALICE));
    }

    @Test
    void aChoiceIsRecordedAsApprenticeWithNoProgress() {
        ProfessionStore store = store();

        store.select(ALICE, ProfessionId.MINER);

        assertEquals(ProfessionId.MINER, store.professionOf(ALICE));
        assertEquals(ProfessionLevel.APPRENTICE, store.levelOf(ALICE));
        assertEquals(0L, store.progressOf(ALICE).progress);
    }

    @Test
    void reselectingTheSameProfessionIsIgnored() {
        ProfessionStore store = store();
        store.select(ALICE, ProfessionId.BUILDER);
        store.addProgress(ALICE, 10L);

        store.select(ALICE, ProfessionId.BUILDER);

        assertEquals(10L, store.progressOf(ALICE).progress,
                "a double-clicked confirm button must not wipe a player's progress");
    }

    @Test
    void reachingTheCountPromotesToMaster() {
        ProfessionStore store = store();
        store.select(ALICE, ProfessionId.BUILDER);
        int needed = ProfessionStore.levelUpCountFor(ProfessionId.BUILDER);

        assertFalse(store.addProgress(ALICE, needed - 1L));
        assertEquals(ProfessionLevel.APPRENTICE, store.levelOf(ALICE));

        assertTrue(store.addProgress(ALICE, 1L));
        assertEquals(ProfessionLevel.MASTER, store.levelOf(ALICE));
    }

    @Test
    void progressIsCappedAtTheLevelUpCount() {
        ProfessionStore store = store();
        store.select(ALICE, ProfessionId.SOLDIER);

        store.addProgress(ALICE, 1_000_000L);

        assertEquals(ProfessionStore.levelUpCountFor(ProfessionId.SOLDIER), store.progressOf(ALICE).progress);
    }

    @Test
    void aMasterEarnsNoFurtherProgress() {
        ProfessionStore store = store();
        master(store, ALICE, ProfessionId.FARMER);

        assertFalse(store.addProgress(ALICE, 5_000L));
        assertEquals(ProfessionLevel.MASTER, store.levelOf(ALICE));
    }

    @Test
    void switchingProfessionStartsAFreshApprentice() {
        ProfessionStore store = store();
        master(store, ALICE, ProfessionId.BUILDER);

        store.select(ALICE, ProfessionId.FARMER);

        assertEquals(ProfessionId.FARMER, store.professionOf(ALICE));
        assertEquals(ProfessionLevel.APPRENTICE, store.levelOf(ALICE), "the new job starts from nothing");
    }

    @Test
    void returningToAMasteredProfessionIsRusty() {
        ProfessionStore store = store();
        master(store, ALICE, ProfessionId.BUILDER);

        store.select(ALICE, ProfessionId.FARMER);
        store.select(ALICE, ProfessionId.BUILDER);

        assertEquals(ProfessionLevel.RUSTED, store.levelOf(ALICE));
    }

    @Test
    void aRustedPlayerEarnsNoProgress() {
        ProfessionStore store = store();
        master(store, ALICE, ProfessionId.BUILDER);
        store.select(ALICE, ProfessionId.FARMER);
        store.select(ALICE, ProfessionId.BUILDER);

        assertFalse(store.addProgress(ALICE, 100L), "D13: working is not allowed to skip the rust timer");
    }

    @Test
    void rustClearsAfterTheConfiguredOnlineTime() {
        ProfessionStore store = store();
        master(store, ALICE, ProfessionId.BUILDER);
        store.select(ALICE, ProfessionId.FARMER);
        store.select(ALICE, ProfessionId.BUILDER);

        assertFalse(store.completeRustIfEarned(ALICE, 44L * 60_000L, RUST_MINUTES));
        assertEquals(ProfessionLevel.RUSTED, store.levelOf(ALICE));

        assertTrue(store.completeRustIfEarned(ALICE, 1L * 60_000L, RUST_MINUTES));
        assertEquals(ProfessionLevel.MASTER, store.levelOf(ALICE));
    }

    @Test
    void rustTimeAccumulatesAcrossCalls() {
        ProfessionStore store = store();
        master(store, ALICE, ProfessionId.MINER);
        store.select(ALICE, ProfessionId.SOLDIER);
        store.select(ALICE, ProfessionId.MINER);

        for (int i = 0; i < 44; i++) {
            assertFalse(store.completeRustIfEarned(ALICE, 60_000L, RUST_MINUTES), "minute " + i);
        }
        assertTrue(store.completeRustIfEarned(ALICE, 60_000L, RUST_MINUTES));
    }

    @Test
    void rustIsOnlineTimeNotWallClock() {
        ProfessionStore store = store();
        master(store, ALICE, ProfessionId.BUILDER);
        store.select(ALICE, ProfessionId.FARMER);
        store.select(ALICE, ProfessionId.BUILDER);

        // A week passes. Nobody earned any online time, so nothing changed.
        clock.advanceHours(24L * 7L);

        assertEquals(ProfessionLevel.RUSTED, store.levelOf(ALICE));
        assertEquals(0L, store.rustOnlineMillis(ALICE));
    }

    @Test
    void anApprenticeHasNoRustTimer() {
        ProfessionStore store = store();
        store.select(ALICE, ProfessionId.SOLDIER);

        assertFalse(store.completeRustIfEarned(ALICE, 60L * 60_000L, RUST_MINUTES));
        assertEquals(ProfessionLevel.APPRENTICE, store.levelOf(ALICE));
    }

    @Test
    void afterRustClearsLeavingAgainReArmsIt() {
        ProfessionStore store = store();
        master(store, ALICE, ProfessionId.BUILDER);
        store.select(ALICE, ProfessionId.FARMER);
        store.select(ALICE, ProfessionId.BUILDER);
        store.completeRustIfEarned(ALICE, 45L * 60_000L, RUST_MINUTES);
        assertEquals(ProfessionLevel.MASTER, store.levelOf(ALICE));

        store.select(ALICE, ProfessionId.MINER);
        store.select(ALICE, ProfessionId.BUILDER);

        assertEquals(ProfessionLevel.RUSTED, store.levelOf(ALICE),
                "leaving and returning is what rust is; clearing it once must not disarm it forever");
    }

    @Test
    void masterOfTwoDifferentProfessionsDoesNotRust() {
        ProfessionStore store = store();
        master(store, ALICE, ProfessionId.BUILDER);

        store.select(ALICE, ProfessionId.MINER);
        master(store, ALICE, ProfessionId.MINER);

        assertEquals(ProfessionLevel.MASTER, store.levelOf(ALICE), "the Miner was mastered here, not merely returned to");
    }

    @Test
    void merchantProgressDoesNotGoThroughTheSingleCounterPath() {
        ProfessionStore store = store();
        store.select(ALICE, ProfessionId.MERCHANT);

        assertFalse(store.addProgress(ALICE, 1_000L),
                "the Merchant needs both counters; half of its condition must not be reachable by accident");
        assertFalse(ProfessionStore.usesSingleProgressCounter(ProfessionId.MERCHANT));
        assertTrue(ProfessionStore.usesSingleProgressCounter(ProfessionId.BUILDER));
    }

    @Test
    void merchantTradesRespectThePerVillagerCap() {
        ProfessionStore store = store();
        store.select(ALICE, ProfessionId.MERCHANT);
        int cap = com.reazip.economycraft.EconomyConfig.get().professions.merchant.maxTradesPerVillager;

        for (int i = 0; i < cap; i++) {
            assertTrue(store.recordVillagerTrade(ALICE, "villager-a"), "trade " + (i + 1));
        }
        assertFalse(store.recordVillagerTrade(ALICE, "villager-a"), "that villager is done");
        assertEquals(cap, store.tradesWith(ALICE, "villager-a"));
        assertTrue(store.recordVillagerTrade(ALICE, "villager-b"), "a different villager still counts");
    }

    @Test
    void auctionPurchasesAreCappedSeparately() {
        ProfessionStore store = store();
        store.select(ALICE, ProfessionId.MERCHANT);
        int cap = com.reazip.economycraft.EconomyConfig.get().professions.merchant.auctionPurchaseCount;

        for (int i = 0; i < cap; i++) {
            assertTrue(store.recordAuctionPurchase(ALICE), "purchase " + (i + 1));
        }
        assertFalse(store.recordAuctionPurchase(ALICE));
    }

    @Test
    void tradesAreIgnoredForNonMerchants() {
        ProfessionStore store = store();
        store.select(ALICE, ProfessionId.FARMER);

        assertFalse(store.recordVillagerTrade(ALICE, "villager-a"));
    }

    @Test
    void changingProfessionClearsVillagerTallies() {
        ProfessionStore store = store();
        store.select(ALICE, ProfessionId.MERCHANT);
        store.recordVillagerTrade(ALICE, "villager-a");

        store.select(ALICE, ProfessionId.FARMER);
        store.select(ALICE, ProfessionId.MERCHANT);

        assertEquals(0L, store.tradesWith(ALICE, "villager-a"),
                "the cap is per merchant career; carrying it across a switch would let it stack");
    }

    @Test
    void lockoutIsIndependentPerStore() {
        ProfessionStore store = store();
        store.select(ALICE, ProfessionId.BUILDER);

        assertFalse(store.canChange(ALICE, LOCKOUT_HOURS));

        clock.advanceHours(1L);
        assertEquals(29L * 3_600_000L, store.remainingCooldownMillis(ALICE, LOCKOUT_HOURS));
    }

    @Test
    void zeroHourLockoutDisablesIt() {
        ProfessionStore store = store();
        store.select(ALICE, ProfessionId.BUILDER);

        assertTrue(store.canChange(ALICE, 0L));
    }

    @Test
    void progressAndRustStateSurviveAReopen() {
        ProfessionStore first = store();
        master(first, ALICE, ProfessionId.MINER);
        first.select(ALICE, ProfessionId.BUILDER);
        first.select(ALICE, ProfessionId.MINER);
        first.flush();
        AsyncFileWriter.flush();

        ProfessionStore reopened = store();

        assertEquals(ProfessionLevel.RUSTED, reopened.levelOf(ALICE),
                "the rust marker is derived from stored fields; losing it would hand out a free Master");
    }

    @Test
    void resetForgetsEverything() {
        ProfessionStore store = store();
        store.select(ALICE, ProfessionId.BUILDER);

        store.reset(ALICE);

        assertNull(store.professionOf(ALICE));
        assertTrue(store.canChange(ALICE, LOCKOUT_HOURS), "a reset is an admin action; it must not leave a lockout behind");
    }

    @Test
    void anUnreadableProfessionFallsBackToNone() throws Exception {
        ProfessionStore store = store();
        store.select(ALICE, ProfessionId.MINER);
        store.flush();
        AsyncFileWriter.flush();

        Path file = dir.resolve("professions.json");
        Files.writeString(file, Files.readString(file).replace("\"MINER\"", "\"HERBALIST\""));

        ProfessionStore reopened = store();

        assertNull(reopened.professionOf(ALICE), "an unknown id must not silently become a valid profession");
    }

    @Test
    void rustedIsNeverPersistedAsALevel() {
        assertFalse(ProfessionLevel.RUSTED.persisted());
        assertTrue(ProfessionLevel.APPRENTICE.persisted());
        assertTrue(ProfessionLevel.MASTER.persisted());
    }

    @Test
    void fromKeyIsCaseInsensitiveAndRejectsNonsense() {
        assertEquals(ProfessionId.FARMER, ProfessionId.fromKey("farmer"));
        assertEquals(ProfessionId.FARMER, ProfessionId.fromKey(" Farmer "));
        assertNull(ProfessionId.fromKey("herbalist"));
        assertNull(ProfessionId.fromKey(null));
    }
}