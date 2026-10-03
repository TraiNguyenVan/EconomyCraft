package com.reazip.economycraft.time;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The online-time clock, tested without a server.
 *
 * <p>The service takes a tick count and an online set rather than a {@code MinecraftServer}, which is what makes
 * these cases expressible at all: the interesting behaviour is all in the bookkeeping between two ticks, and
 * driving a real server to reproduce "the player joined half a tick late" is not a test, it is a flaky incident.
 */
class OnlineTimeServiceTest {

    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();
    private static final long MINUTE = 60_000L;

    @TempDir
    Path dir;

    private OnlineTimeService service() {
        return new OnlineTimeService(dir.resolve("online_time.json"));
    }

    @Test
    void firstTickRecordsPresenceWithoutCreditingTime() {
        OnlineTimeService service = service();

        service.tick(0L, Set.of(ALICE));

        assertEquals(0L, service.totalMillis(ALICE),
                "arriving is recorded, not paid for: crediting the arrival tick would hand out free online time");
    }

    @Test
    void eachSubsequentTickCreditsFiftyMilliseconds() {
        OnlineTimeService service = service();
        service.tick(0L, Set.of(ALICE));

        service.tick(1L, Set.of(ALICE));

        assertEquals(50L, service.totalMillis(ALICE));
    }

    @Test
    void creditsTheWholeIntervalForATickThatSkipped() {
        OnlineTimeService service = service();
        service.tick(0L, Set.of(ALICE));

        service.tick(20L, Set.of(ALICE));

        assertEquals(20L * 50L, service.totalMillis(ALICE), "20 ticks is one second of online time");
    }

    @Test
    void joiningMidSessionIsNotCreditedForTheIntervalItMissed() {
        OnlineTimeService service = service();
        service.tick(0L, Set.of(ALICE));

        // Bob is not present at tick 0, so the 20 ticks he was away belong to nobody.
        service.tick(20L, Set.of(ALICE, BOB));

        assertEquals(0L, service.totalMillis(BOB));

        service.tick(21L, Set.of(ALICE, BOB));
        assertEquals(50L, service.totalMillis(BOB));
    }

    @Test
    void loggingOutStopsTheClockAndReturningDoesNotBackfill() {
        OnlineTimeService service = service();
        service.tick(0L, Set.of(ALICE));
        service.tick(1L, Set.of(ALICE));
        assertEquals(50L, service.totalMillis(ALICE));

        service.tick(2L, Set.of());
        assertEquals(0, service.trackedOnlineCount(), "an empty player list must drop the mid-interval entries");

        service.tick(3L, Set.of(ALICE));
        assertEquals(50L, service.totalMillis(ALICE), "no credit for the interval before the player was back");

        service.tick(4L, Set.of(ALICE));
        assertEquals(100L, service.totalMillis(ALICE));
    }

    @Test
    void aStallIsCappedRatherThanCreditedInFull() {
        OnlineTimeService service = service();
        service.tick(0L, Set.of(ALICE));

        // Ten hours of stalled ticks: the server owes nobody ten hours of "online" time.
        service.tick(10L * 60L * 60L * 20L, Set.of(ALICE));

        assertEquals(OnlineTimeService.MAX_INTERVAL_MILLIS, service.totalMillis(ALICE));
    }

    @Test
    void cappedIntervalIsStillReportedToConsumersOfTheSameClock() {
        OnlineTimeService service = service();
        service.tick(0L, Set.of(ALICE));

        service.tick(10L * 60L * 60L * 20L, Set.of(ALICE));

        assertEquals(OnlineTimeService.MAX_INTERVAL_MILLIS, service.lastCreditedIntervalMillis(),
                "the rust timer reads this, and must be clamped exactly like the total");
    }

    @Test
    void reportsTheCreditedIntervalSoSubThresholdsCannotDrift() {
        OnlineTimeService service = service();
        service.tick(0L, Set.of(ALICE));

        service.tick(4L, Set.of(ALICE));

        assertEquals(200L, service.lastCreditedIntervalMillis());
    }

    @Test
    void minutesAreWholeMinutes() {
        OnlineTimeService service = service();
        service.tick(0L, Set.of(ALICE));
        service.tick(1L, Set.of(ALICE));

        assertEquals(0L, service.totalMinutes(ALICE), "50 ms is not yet a minute");

        service.tick(45L * 60L * 20L, Set.of(ALICE));
        assertEquals(45L, service.totalMinutes(ALICE));
    }

    @Test
    void progressIsClampedBetweenZeroAndOne() {
        OnlineTimeService service = service();
        service.tick(0L, Set.of(ALICE));
        service.tick(45L * 60L * 20L, Set.of(ALICE));

        assertEquals(1.0, service.progressToward(ALICE, 45L * MINUTE), 1e-9);

        assertEquals(0.5, service.progressToward(ALICE, 90L * MINUTE), 1e-9);
        assertEquals(1.0, service.progressToward(ALICE, 0L), 1e-9, "a zero threshold is trivially complete");
    }

    @Test
    void thresholdIsConsumedAtExactlyTheThreshold() {
        OnlineTimeService service = service();
        service.tick(0L, Set.of(ALICE));
        service.tick(45L * 60L * 20L, Set.of(ALICE));

        assertTrue(service.consumeIfThresholdMet(ALICE, 45L * MINUTE),
                "a player sitting on exactly 45:00 has met a 45-minute threshold");
        assertEquals(0L, service.totalMillis(ALICE), "consuming resets the counter, so the next levy is another 45 minutes");
    }

    @Test
    void thresholdIsNotConsumedBelowIt() {
        OnlineTimeService service = service();
        service.tick(0L, Set.of(ALICE));
        service.tick(44L * 60L * 20L, Set.of(ALICE));

        assertFalse(service.consumeIfThresholdMet(ALICE, 45L * MINUTE));
        assertTrue(service.totalMillis(ALICE) > 0, "a failed check must not consume anything");
    }

    @Test
    void zeroThresholdNeverConsumes() {
        OnlineTimeService service = service();

        assertFalse(service.consumeIfThresholdMet(ALICE, 0L),
                "a 0 threshold means 'disabled', not 'always due'");
    }

    @Test
    void totalsSurviveAReopen() {
        OnlineTimeService first = service();
        first.tick(0L, Set.of(ALICE));
        first.tick(10L, Set.of(ALICE));
        first.flush();
        com.reazip.economycraft.util.AsyncFileWriter.flush();

        OnlineTimeService reopened = service();

        assertEquals(500L, reopened.totalMillis(ALICE), "online time is not a session counter: it must persist");
    }

    @Test
    void forgetDropsAPlayerEntirely() {
        OnlineTimeService service = service();
        service.tick(0L, Set.of(ALICE));
        service.tick(1L, Set.of(ALICE));

        service.forget(ALICE);

        assertEquals(0L, service.totalMillis(ALICE));
    }
}