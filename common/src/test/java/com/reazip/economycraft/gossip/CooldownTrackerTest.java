package com.reazip.economycraft.gossip;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CooldownTracker Unit Tests")
class CooldownTrackerTest {

    @Test
    @DisplayName("T010: Basic cooldown setting and expiry check")
    void testBasicCooldownAndExpiry() {
        AtomicLong clock = new AtomicLong(1000L);
        CooldownTracker tracker = new CooldownTracker(clock::get);

        UUID player = UUID.randomUUID();
        UUID villager = UUID.randomUUID();

        assertFalse(tracker.isOnCooldown(player, villager));
        assertEquals(0L, tracker.getRemainingMillis(player, villager));

        // Set 3-minute cooldown (180,000 ms)
        tracker.setCooldown(player, villager, 180_000L);
        assertTrue(tracker.isOnCooldown(player, villager));
        assertEquals(180_000L, tracker.getRemainingMillis(player, villager));
        assertEquals(1, tracker.size());

        // Advance clock by 1 minute
        clock.addAndGet(60_000L);
        assertTrue(tracker.isOnCooldown(player, villager));
        assertEquals(120_000L, tracker.getRemainingMillis(player, villager));

        // Advance clock past expiration
        clock.addAndGet(120_001L);
        assertFalse(tracker.isOnCooldown(player, villager));
        assertEquals(0L, tracker.getRemainingMillis(player, villager));
        assertEquals(0, tracker.size(), "Expired entry should be lazily pruned on lookup");
    }

    @Test
    @DisplayName("T010: Composite key isolation across players and villagers")
    void testCompositeKeyIsolation() {
        AtomicLong clock = new AtomicLong(1000L);
        CooldownTracker tracker = new CooldownTracker(clock::get);

        UUID player1 = UUID.randomUUID();
        UUID player2 = UUID.randomUUID();
        UUID villager1 = UUID.randomUUID();
        UUID villager2 = UUID.randomUUID();

        tracker.setCooldown(player1, villager1, 60_000L);

        // Player 1 on Villager 1 is on cooldown
        assertTrue(tracker.isOnCooldown(player1, villager1));

        // Different player on same villager is NOT on cooldown
        assertFalse(tracker.isOnCooldown(player2, villager1));

        // Same player on different villager is NOT on cooldown
        assertFalse(tracker.isOnCooldown(player1, villager2));

        // Different player on different villager is NOT on cooldown
        assertFalse(tracker.isOnCooldown(player2, villager2));
    }

    @Test
    @DisplayName("T010: Active pruning removes all expired entries")
    void testActivePruning() {
        AtomicLong clock = new AtomicLong(10_000L);
        CooldownTracker tracker = new CooldownTracker(clock::get);

        UUID p1 = UUID.randomUUID();
        UUID v1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        UUID v2 = UUID.randomUUID();
        UUID p3 = UUID.randomUUID();
        UUID v3 = UUID.randomUUID();

        tracker.setCooldown(p1, v1, 10_000L); // expires at 20,000
        tracker.setCooldown(p2, v2, 30_000L); // expires at 40,000
        tracker.setCooldown(p3, v3, 50_000L); // expires at 60,000

        assertEquals(3, tracker.size());

        // Advance to 25,000: p1/v1 expired, others active
        clock.set(25_000L);
        tracker.pruneExpired();

        assertEquals(2, tracker.size());
        assertFalse(tracker.isOnCooldown(p1, v1));
        assertTrue(tracker.isOnCooldown(p2, v2));
        assertTrue(tracker.isOnCooldown(p3, v3));

        // Advance to 70,000: all expired
        clock.set(70_000L);
        tracker.pruneExpired();
        assertEquals(0, tracker.size());
    }

    @Test
    @DisplayName("T010: Boundary values and null safety")
    void testBoundaryAndNullSafety() {
        CooldownTracker tracker = new CooldownTracker();
        UUID p = UUID.randomUUID();
        UUID v = UUID.randomUUID();

        // Null checks
        assertFalse(tracker.isOnCooldown(null, v));
        assertFalse(tracker.isOnCooldown(p, null));
        assertFalse(tracker.isOnCooldown(null, null));
        assertEquals(0L, tracker.getRemainingMillis(null, v));

        // Non-positive cooldown
        tracker.setCooldown(p, v, 0);
        assertFalse(tracker.isOnCooldown(p, v));
        tracker.setCooldown(p, v, -5000);
        assertFalse(tracker.isOnCooldown(p, v));
        assertEquals(0, tracker.size());

        // Null player/villager in setCooldown should no-op
        assertDoesNotThrow(() -> tracker.setCooldown(null, v, 60_000L));
        assertDoesNotThrow(() -> tracker.setCooldown(p, null, 60_000L));
        assertEquals(0, tracker.size());
    }

    @Test
    @DisplayName("T010: Lock-free concurrency stress test")
    void testConcurrentAccess() throws InterruptedException {
        CooldownTracker tracker = new CooldownTracker();
        int threadCount = 8;
        int operationsPerThread = 500;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        UUID[] players = new UUID[10];
        UUID[] villagers = new UUID[5];
        for (int i = 0; i < players.length; i++) players[i] = UUID.randomUUID();
        for (int i = 0; i < villagers.length; i++) villagers[i] = UUID.randomUUID();

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    for (int i = 0; i < operationsPerThread; i++) {
                        UUID p = players[(threadId + i) % players.length];
                        UUID v = villagers[(threadId + i) % villagers.length];

                        if (i % 3 == 0) {
                            tracker.setCooldown(p, v, 100L);
                        } else if (i % 3 == 1) {
                            tracker.isOnCooldown(p, v);
                        } else {
                            tracker.pruneExpired();
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Concurrent test should finish promptly");
        executor.shutdown();
    }
}
