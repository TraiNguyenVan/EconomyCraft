package com.reazip.economycraft.faction;

import com.reazip.economycraft.util.AsyncFileWriter;
import com.reazip.economycraft.time.MutableClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Party selection and the D17 lockout.
 *
 * <p>The cases worth protecting are the ones a player would notice: that "never chose" and "chose Anarchism"
 * stay distinguishable, and that the two 30-hour clocks really are independent.
 */
class FactionStoreTest {

    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();
    private static final long LOCKOUT_HOURS = 30L;

    @TempDir
    Path dir;

    private final MutableClock clock = new MutableClock(1_700_000_000_000L);

    private FactionStore store() {
        return new FactionStore(dir.resolve("parties.json"), clock);
    }

    @Test
    void defaultFactionForAPlayerWhoNeverChose() {
        assertEquals(FactionId.defaultFaction(), store().factionOf(ALICE));
    }

    @Test
    void defaultFactionIsTheOneThatTaxesLeast() {
        assertEquals(FactionId.ANARCHISM, FactionId.defaultFaction(),
                "defaulting a new player into a tax party is the one default that could actually hurt someone");
    }

    @Test
    void noRecordIsWrittenUntilAChoiceIsMade() {
        FactionStore store = store();

        store.factionOf(ALICE);

        assertFalse(store.hasChosen(ALICE), "reading the default must not create a choice");
        assertNull(store.selectionOf(ALICE));
    }

    @Test
    void aChoiceIsRecorded() {
        FactionStore store = store();

        store.select(ALICE, FactionId.COMMUNISM);

        assertEquals(FactionId.COMMUNISM, store.factionOf(ALICE));
        assertTrue(store.hasChosen(ALICE));
        assertNotNull(store.selectionOf(ALICE));
        assertEquals(clock.millis(), store.selectionOf(ALICE).selectedAtEpochMillis());
    }

    @Test
    void choosingAnarchismIsDistinguishableFromNeverChoosing() {
        FactionStore store = store();
        store.select(ALICE, FactionId.ANARCHISM);

        assertEquals(FactionId.ANARCHISM, store.factionOf(ALICE));
        assertTrue(store.hasChosen(ALICE), "a real choice must not look like 'no record' just because it matches the default");
    }

    @Test
    void lockoutRunsForThirtyHours() {
        FactionStore store = store();
        store.select(ALICE, FactionId.COMMUNISM);

        clock.advanceHours(29);
        assertFalse(store.canChange(ALICE, LOCKOUT_HOURS));

        clock.advanceHours(1);
        assertTrue(store.canChange(ALICE, LOCKOUT_HOURS));
    }

    @Test
    void lockoutRemainingIsReportedInMilliseconds() {
        FactionStore store = store();
        store.select(ALICE, FactionId.CAPITALISM);

        assertEquals(30L * 3_600_000L, store.remainingCooldownMillis(ALICE, LOCKOUT_HOURS));

        clock.advanceHours(5);
        assertEquals(25L * 3_600_000L, store.remainingCooldownMillis(ALICE, LOCKOUT_HOURS));
    }

    @Test
    void aPlayerWithNoRecordHasNoLockout() {
        assertEquals(0L, store().remainingCooldownMillis(ALICE, LOCKOUT_HOURS));
    }

    @Test
    void zeroHourLockoutDisablesIt() {
        FactionStore store = store();
        store.select(ALICE, FactionId.MONARCHY);

        assertTrue(store.canChange(ALICE, 0L), "0 hours is how an admin turns the lockout off");
    }

    @Test
    void aClockThatMovedBackwardsDoesNotLockForever() {
        FactionStore store = store();
        store.select(ALICE, FactionId.COMMUNISM);

        // An NTP correction, or an admin changing the system clock.
        java.util.concurrent.atomic.AtomicLong skewed = new java.util.concurrent.atomic.AtomicLong(clock.millis() - 10L * 3_600_000L);
        FactionStore skewedStore = new FactionStore(dir.resolve("skewed.json"), skewed::get);
        skewedStore.select(BOB, FactionId.CAPITALISM);

        assertEquals(30L * 3_600_000L, skewedStore.remainingCooldownMillis(BOB, LOCKOUT_HOURS),
                "the worst a backwards clock may do is the full lockout, not longer");
    }

    @Test
    void resetForgetsTheChoiceAndTheLockout() {
        FactionStore store = store();
        store.select(ALICE, FactionId.COMMUNISM);

        store.reset(ALICE);

        assertEquals(FactionId.defaultFaction(), store.factionOf(ALICE));
        assertEquals(0L, store.remainingCooldownMillis(ALICE, LOCKOUT_HOURS));
    }

    @Test
    void selectionsSurviveAReopen() {
        FactionStore first = store();
        first.select(ALICE, FactionId.MONARCHY);
        first.flush();
        AsyncFileWriter.flush();

        FactionStore reopened = store();

        assertEquals(FactionId.MONARCHY, reopened.factionOf(ALICE));
        assertEquals(clock.millis(), reopened.selectionOf(ALICE).selectedAtEpochMillis(),
                "the timestamp is the whole lockout, so it has to round-trip exactly");
    }

    @Test
    void anUnreadableSelectionFallsBackToTheDefaultInsteadOfThrowing() throws Exception {
        FactionStore store = store();
        store.select(ALICE, FactionId.COMMUNISM);
        store.flush();
        AsyncFileWriter.flush();

        Path file = dir.resolve("parties.json");
        String json = Files.readString(file);
        Files.writeString(file, json.replace("\"COMMUNISM\"", "\"EMPIRE\""));

        FactionStore reopened = store();

        assertEquals(FactionId.defaultFaction(), reopened.factionOf(ALICE));
    }

    @Test
    void aCorruptFileLeavesEverybodyUnchosen() throws Exception {
        FactionStore store = store();
        store.select(ALICE, FactionId.COMMUNISM);
        store.flush();
        AsyncFileWriter.flush();

        Files.writeString(dir.resolve("parties.json"), "{not json");

        FactionStore reopened = store();

        assertEquals(FactionId.defaultFaction(), reopened.factionOf(ALICE));
        assertFalse(reopened.hasChosen(ALICE));
    }

    @Test
    void fromKeyIsCaseInsensitiveAndRejectsNonsense() {
        assertEquals(FactionId.CAPITALISM, FactionId.fromKey("capitalism"));
        assertEquals(FactionId.CAPITALISM, FactionId.fromKey("  Capitalism "));
        assertNull(FactionId.fromKey("empire"));
        assertNull(FactionId.fromKey(null));
    }
}