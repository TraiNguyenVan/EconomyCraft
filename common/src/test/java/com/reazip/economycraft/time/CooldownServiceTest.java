package com.reazip.economycraft.time;

import com.reazip.economycraft.util.AsyncFileWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The wall-clock cooldown service.
 *
 * <p>{@link MutableClock} is what makes the interesting property testable: a cooldown has to keep running while
 * the player is logged out, and "logged out" cannot be simulated by sleeping in a unit test.
 */
class CooldownServiceTest {

    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();
    private static final String CROP_BOOST = "farmer.crop_boost";

    @TempDir
    Path dir;

    private final MutableClock clock = new MutableClock(1_700_000_000_000L);

    private CooldownService service() {
        return new CooldownService(dir.resolve("cooldowns.json"), clock);
    }

    @Test
    void isReadyWhenNothingWasEverStarted() {
        assertTrue(service().isReady(ALICE, CROP_BOOST));
    }

    @Test
    void notReadyWhileRunning() {
        CooldownService service = service();

        service.startMinutes(ALICE, CROP_BOOST, 4);

        assertFalse(service.isReady(ALICE, CROP_BOOST));
    }

    @Test
    void readyAgainOnceTheCooldownExpires() {
        CooldownService service = service();
        service.startMinutes(ALICE, CROP_BOOST, 4);

        clock.advanceMinutes(4);

        assertTrue(service.isReady(ALICE, CROP_BOOST));
    }

    @Test
    void keepsRunningWhileThePlayerIsOffline() {
        CooldownService service = service();
        service.startMinutes(ALICE, CROP_BOOST, 4);

        // Nothing happens for four minutes: no ticks, no joins, no logins.
        clock.advanceMinutes(3);

        assertFalse(service.isReady(ALICE, CROP_BOOST), "a 4-minute cooldown must be 4 minutes of wall clock");
    }

    @Test
    void cooldownsArePerPlayer() {
        CooldownService service = service();
        service.startMinutes(ALICE, CROP_BOOST, 4);

        assertTrue(service.isReady(BOB, CROP_BOOST));
    }

    @Test
    void cooldownsArePerKey() {
        CooldownService service = service();
        service.startMinutes(ALICE, CROP_BOOST, 4);

        assertTrue(service.isReady(ALICE, "miner.lava_regeneration"),
                "one shared service must not let one job's cooldown shadow another's");
    }

    @Test
    void restartingReplacesRatherThanStacks() {
        CooldownService service = service();
        service.startMinutes(ALICE, CROP_BOOST, 4);

        clock.advanceMinutes(3);
        service.startMinutes(ALICE, CROP_BOOST, 4);

        assertFalse(service.isReady(ALICE, CROP_BOOST));
        clock.advanceMinutes(3);
        assertFalse(service.isReady(ALICE, CROP_BOOST), "restarting must not shorten the new cooldown");
        clock.advanceMinutes(1);
        assertTrue(service.isReady(ALICE, CROP_BOOST), "the cooldown runs from the latest start, not the sum");
    }

    @Test
    void zeroDurationStartsNothing() {
        CooldownService service = service();

        service.startMinutes(ALICE, CROP_BOOST, 0);

        assertTrue(service.isReady(ALICE, CROP_BOOST),
                "a 0-minute cooldown is how an admin disables the effect, with no special case at the call site");
    }

    @Test
    void remainingCountsDownAndRoundsUpForDisplay() {
        CooldownService service = service();
        service.start(ALICE, CROP_BOOST, 90_000L);

        assertEquals(90_000L, service.remainingMillis(ALICE, CROP_BOOST));
        assertEquals(90L, service.remainingSeconds(ALICE, CROP_BOOST));

        clock.advanceMillis(30_400L);
        assertEquals(60L, service.remainingSeconds(ALICE, CROP_BOOST), "rounds up, so a countdown never shows 0 early");
    }

    @Test
    void expiredCooldownsAreDroppedRatherThanKept() {
        CooldownService service = service();
        service.startMinutes(ALICE, CROP_BOOST, 4);

        clock.advanceMinutes(5);
        assertTrue(service.isReady(ALICE, CROP_BOOST));

        assertEquals(0L, service.remainingMillis(ALICE, CROP_BOOST));
    }

    @Test
    void clearRemovesOneCooldown() {
        CooldownService service = service();
        service.startMinutes(ALICE, CROP_BOOST, 4);
        service.startMinutes(ALICE, "miner.lava_regeneration", 5);

        service.clear(ALICE, CROP_BOOST);

        assertTrue(service.isReady(ALICE, CROP_BOOST));
        assertFalse(service.isReady(ALICE, "miner.lava_regeneration"), "clearing one key must not clear the rest");
    }

    @Test
    void clearAllRemovesEverythingForOnePlayer() {
        CooldownService service = service();
        service.startMinutes(ALICE, CROP_BOOST, 4);
        service.startMinutes(BOB, CROP_BOOST, 4);

        service.clearAll(ALICE);

        assertTrue(service.isReady(ALICE, CROP_BOOST));
        assertFalse(service.isReady(BOB, CROP_BOOST));
    }

    @Test
    void cooldownsSurviveAReopenBecauseExpiryIsAnAbsoluteInstant() {
        CooldownService first = service();
        first.startMinutes(ALICE, CROP_BOOST, 4);
        first.flush();
        AsyncFileWriter.flush();

        CooldownService reopened = service();
        assertFalse(reopened.isReady(ALICE, CROP_BOOST));

        clock.advanceMinutes(4);
        assertTrue(reopened.isReady(ALICE, CROP_BOOST));
    }

    @Test
    void anUnreadableFileLeavesEveryCooldownReady() throws Exception {
        CooldownService service = service();
        service.startMinutes(ALICE, CROP_BOOST, 4);
        service.flush();
        AsyncFileWriter.flush();

        // Corrupt it the way a half-finished disk write would.
        java.nio.file.Files.writeString(dir.resolve("cooldowns.json"), "{not json");

        CooldownService reopened = service();

        assertTrue(reopened.isReady(ALICE, CROP_BOOST),
                "a corrupt cooldown file must fail open, not lock every job in the server out");
    }
}