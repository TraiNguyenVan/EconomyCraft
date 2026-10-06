package com.reazip.economycraft.gossip;

import com.reazip.economycraft.EconomySources;
import com.reazip.economycraft.api.v1.BalanceMutationType;
import com.reazip.economycraft.api.v1.FactionIds;
import com.reazip.economycraft.util.TransactionEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("GossipDigestWorker Unit Tests")
class GossipDigestWorkerTest {

    private TransactionEntry createEntry(long amount, String source, String detail) {
        return new TransactionEntry(
                Instant.now(),
                BalanceMutationType.PAYMENT_SENT,
                UUID.randomUUID(),
                "Alice",
                UUID.randomUUID(),
                "Bob",
                amount,
                100_000L,
                100_000L - amount,
                source,
                detail
        );
    }

    @Test
    @DisplayName("T017: Transaction significance scoring gives higher priority to high-value and impactful sources")
    void testSignificanceScoring() {
        TransactionEntry smallNormal = createEntry(100, "generic", "bread");
        TransactionEntry bigNormal = createEntry(50_000, "generic", "wheat");
        TransactionEntry smallAuction = createEntry(100, EconomySources.AUCTION_PURCHASE.asString(), "diamond_sword");
        TransactionEntry wealthTax = createEntry(50_000, EconomySources.WEALTH_TAX.asString(), "levy");

        assertTrue(GossipDigestWorker.calculateSignificance(bigNormal) > GossipDigestWorker.calculateSignificance(smallNormal));
        assertTrue(GossipDigestWorker.calculateSignificance(smallAuction) > GossipDigestWorker.calculateSignificance(smallNormal));
        assertTrue(GossipDigestWorker.calculateSignificance(wealthTax) > GossipDigestWorker.calculateSignificance(bigNormal));
        assertEquals(0.0, GossipDigestWorker.calculateSignificance(null));
    }

    @Test
    @DisplayName("T017: filterSignificant sorts descending and honors limits")
    void testFilterSignificant() {
        TransactionEntry e1 = createEntry(10, "generic", "apple");
        TransactionEntry e2 = createEntry(10_000, "generic", "netherite");
        TransactionEntry e3 = createEntry(0, "generic", "worthless");
        TransactionEntry e4 = createEntry(500, EconomySources.AUCTION_PURCHASE.asString(), "elytra");

        List<TransactionEntry> list = List.of(e1, e2, e3, e4);

        List<TransactionEntry> filtered = GossipDigestWorker.filterSignificant(list, 2);
        assertEquals(2, filtered.size());
        assertEquals(e2, filtered.get(0), "Most significant item first");
        assertEquals(e4, filtered.get(1), "Second most significant");

        // Zero amounts are excluded
        List<TransactionEntry> allFiltered = GossipDigestWorker.filterSignificant(list, 10);
        assertEquals(3, allFiltered.size());
        assertFalse(allFiltered.contains(e3));
    }

    @Test
    @DisplayName("T017: buildDigest constructs complete prompt digest with anonymization")
    void testBuildDigest() {
        UUID playerId = UUID.randomUUID();
        TransactionEntry entry = new TransactionEntry(
                Instant.now(),
                BalanceMutationType.PAYMENT_SENT,
                playerId,
                "PlayerNameShouldBeAnonymized",
                UUID.randomUUID(),
                "SellerPlayer",
                25_000L,
                150_000L,
                125_000L,
                EconomySources.AUCTION_PURCHASE.asString(),
                "Diamond Boots §6Protection IV"
        );

        TransactionDigest digest = GossipDigestWorker.buildDigest(
                List.of(entry),
                10,
                3.1415,
                Duration.ofHours(24),
                true,
                uuid -> FactionIds.CAPITALISM
        );

        assertEquals(1, digest.eventCount());
        assertEquals(3.1415, digest.currentInflation(), 1e-4);
        assertEquals(1, digest.formattedLines().size());

        String line = digest.formattedLines().get(0);
        assertFalse(line.contains("PlayerNameShouldBeAnonymized"), "Must not leak raw player name");
        assertFalse(line.contains("§6"), "Must strip formatting codes");
        assertTrue(line.contains("Diamond Boots Protection IV"));

        String promptContext = digest.toPromptContext();
        assertTrue(promptContext.contains("3.1415x"));
        assertTrue(promptContext.contains("Diamond Boots"));
    }

    @Test
    @DisplayName("T017: runDigestCycle queries Gemini and updates AtomicReference<GossipPool> atomically")
    void testRunDigestCycleUpdatesPool() {
        GossipConfig config = new GossipConfig(true, "test-api-key", "gemini-3.8-flash", 20, 3, true, 0.85, false);
        GeminiClient mockClient = mock(GeminiClient.class);
        when(mockClient.isCircuitOpen()).thenReturn(false);

        Map<GossipCategory, List<String>> rumors = Map.of(
                GossipCategory.FARMER, List.of("Wheat prices are skyrocketing!"),
                GossipCategory.BLACKSMITH, List.of("Steel is in short supply!")
        );
        GossipPool expectedPool = new GossipPool(rumors, Instant.now());
        when(mockClient.generateRumors(any(TransactionDigest.class)))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(expectedPool)));

        AtomicReference<GossipPool> poolRef = new AtomicReference<>(GossipPool.empty());
        CooldownTracker tracker = new CooldownTracker();

        GossipDigestWorker worker = new GossipDigestWorker(
                config,
                mockClient,
                poolRef,
                tracker,
                () -> null, // No log files for unit test
                () -> 2.50,
                null,
                mock(java.util.concurrent.ScheduledExecutorService.class),
                false
        );

        assertEquals(GossipPool.empty(), poolRef.get());
        worker.runDigestCycle();

        assertEquals(expectedPool, poolRef.get(), "GossipPool should be atomically updated");
        verify(mockClient, times(1)).generateRumors(any(TransactionDigest.class));
    }

    @Test
    @DisplayName("T017: runDigestCycle skips silently when API key is missing or circuit breaker is open")
    void testRunDigestCycleSkipsWhenUnconfiguredOrOpen() {
        GossipConfig noKeyConfig = new GossipConfig(true, "", "gemini-3.8-flash", 20, 3, true, 0.85, false);
        GeminiClient mockClient = mock(GeminiClient.class);
        AtomicReference<GossipPool> poolRef = new AtomicReference<>(GossipPool.empty());

        GossipDigestWorker worker = new GossipDigestWorker(
                noKeyConfig,
                mockClient,
                poolRef,
                null,
                () -> null,
                () -> 1.0,
                null,
                mock(java.util.concurrent.ScheduledExecutorService.class),
                false
        );

        worker.runDigestCycle();
        verify(mockClient, never()).generateRumors(any(TransactionDigest.class));

        // When circuit breaker is open
        GossipConfig validConfig = new GossipConfig(true, "key", "gemini-3.8-flash", 20, 3, true, 0.85, false);
        when(mockClient.isCircuitOpen()).thenReturn(true);

        GossipDigestWorker workerBreaker = new GossipDigestWorker(
                validConfig,
                mockClient,
                poolRef,
                null,
                () -> null,
                () -> 1.0,
                null,
                mock(java.util.concurrent.ScheduledExecutorService.class),
                false
        );

        workerBreaker.runDigestCycle();
        verify(mockClient, never()).generateRumors(any(TransactionDigest.class));
    }
}
