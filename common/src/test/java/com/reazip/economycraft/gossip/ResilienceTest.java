package com.reazip.economycraft.gossip;

import com.reazip.economycraft.time.WallClock;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Resilience & Silent Degradation Tests")
class ResilienceTest {

    private HttpServer mockServer;
    private int port;
    private final AtomicInteger requestCounter = new AtomicInteger(0);
    private final AtomicInteger mockStatusCode = new AtomicInteger(200);
    private final AtomicReference<String> mockResponseBody = new AtomicReference<>("{}");

    @BeforeEach
    void setUp() throws IOException {
        mockServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = mockServer.getAddress().getPort();
        requestCounter.set(0);

        mockServer.createContext("/v1beta/models/", exchange -> {
            requestCounter.incrementAndGet();
            int code = mockStatusCode.get();
            String response = mockResponseBody.get();
            byte[] bytes = response.getBytes();
            exchange.sendResponseHeaders(code, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        mockServer.start();
    }

    @AfterEach
    void tearDown() {
        if (mockServer != null) {
            mockServer.stop(0);
        }
    }

    @Test
    @DisplayName("T020: Missing or blank API key completes silently without throwing exceptions or making HTTP calls")
    void testMissingApiKeySilentFallback() {
        GossipConfig emptyKeyConfig = new GossipConfig(true, "", "gemini-3.8-flash", 20, 3, true, 0.85, false);
        GossipApiClient client = new GossipApiClient(
                emptyKeyConfig,
                HttpClient.newHttpClient(),
                "http://127.0.0.1:" + port,
                WallClock.SYSTEM
        );

        Optional<GossipPool> result = client.generateRumors("economic context").join();
        assertTrue(result.isEmpty(), "Must return Optional.empty()");
        assertEquals(0, requestCounter.get(), "No HTTP requests should be sent when API key is empty");
        assertFalse(client.isCircuitOpen(), "Circuit breaker should remain closed");
    }

    @Test
    @DisplayName("T020: HTTP 429 quota exhaustion degrades silently and trips circuit breaker after 3 failures")
    void testHttp429QuotaExhaustionAndCircuitBreaker() {
        mockStatusCode.set(429);
        mockResponseBody.set("{\"error\": {\"code\": 429, \"message\": \"RESOURCE_EXHAUSTED\"}}");

        AtomicLong time = new AtomicLong(1_000_000L);
        WallClock mutableClock = time::get;

        GossipConfig config = new GossipConfig(true, "valid-key", "gemini-3.8-flash", 20, 3, true, 0.85, false);
        GossipApiClient client = new GossipApiClient(
                config,
                HttpClient.newHttpClient(),
                "http://127.0.0.1:" + port,
                mutableClock
        );

        // Failure 1
        Optional<GossipPool> res1 = client.generateRumors("ctx 1").join();
        assertTrue(res1.isEmpty());
        assertFalse(client.isCircuitOpen());
        assertEquals(1, client.getConsecutiveFailures());

        // Failure 2
        Optional<GossipPool> res2 = client.generateRumors("ctx 2").join();
        assertTrue(res2.isEmpty());
        assertFalse(client.isCircuitOpen());
        assertEquals(2, client.getConsecutiveFailures());

        // Failure 3 -> Circuit breaker opens
        Optional<GossipPool> res3 = client.generateRumors("ctx 3").join();
        assertTrue(res3.isEmpty());
        assertTrue(client.isCircuitOpen(), "Circuit breaker must trip after 3 consecutive 429 failures");
        assertEquals(3, client.getConsecutiveFailures());
        assertEquals(3, requestCounter.get());

        // 4th call while circuit is open -> short-circuits immediately without HTTP call
        Optional<GossipPool> res4 = client.generateRumors("ctx 4").join();
        assertTrue(res4.isEmpty());
        assertEquals(3, requestCounter.get(), "No HTTP call should be made when circuit breaker is open");

        // Advance clock past 30-minute quiet period (30 * 60 * 1000 = 1,800,000 ms)
        time.addAndGet(1_800_001L);
        assertFalse(client.isCircuitOpen(), "Circuit breaker should reset after 30-minute quiet period");
    }

    @Test
    @DisplayName("T020: Safety block response (finishReason: SAFETY) returns empty pool without throwing")
    void testSafetyBlockedResponse() {
        mockStatusCode.set(200);
        mockResponseBody.set("""
                {
                  "candidates": [
                    {
                      "finishReason": "SAFETY"
                    }
                  ]
                }
                """);

        GossipConfig config = new GossipConfig(true, "valid-key", "gemini-3.8-flash", 20, 3, true, 0.85, false);
        GossipApiClient client = new GossipApiClient(
                config,
                HttpClient.newHttpClient(),
                "http://127.0.0.1:" + port,
                WallClock.SYSTEM
        );

        Optional<GossipPool> result = assertDoesNotThrow(() -> client.generateRumors("context").join());
        assertTrue(result.isEmpty(), "Safety blocked response must yield Optional.empty()");
        assertEquals(1, client.getConsecutiveFailures(), "Safety blocks count as failure");
    }

    @Test
    @DisplayName("T020: Malformed JSON payload degrades silently to empty pool")
    void testMalformedJsonPayload() {
        mockStatusCode.set(200);
        mockResponseBody.set("This is completely invalid non-JSON { [ syntax");

        GossipConfig config = new GossipConfig(true, "valid-key", "gemini-3.8-flash", 20, 3, true, 0.85, false);
        GossipApiClient client = new GossipApiClient(
                config,
                HttpClient.newHttpClient(),
                "http://127.0.0.1:" + port,
                WallClock.SYSTEM
        );

        Optional<GossipPool> result = assertDoesNotThrow(() -> client.generateRumors("context").join());
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("T020: VillagerGossipListener returns PASS silently on unconfigured or empty pool without throwing")
    void testListenerDegradesSilentlyOnNullOrEmptyState() {
        // Listener with unconfigured state
        AtomicReference<GossipPool> emptyPoolRef = new AtomicReference<>(GossipPool.empty());
        CooldownTracker tracker = new CooldownTracker();
        GossipConfig disabledConfig = new GossipConfig(false, "", "gemini-3.8-flash", 20, 3, true, 0.85, false);

        VillagerGossipListener.init(emptyPoolRef, tracker, () -> disabledConfig);

        // Null player / entity calls
        assertDoesNotThrow(() -> {
            var res = VillagerGossipListener.handleVillagerInteraction(null, null, null);
            assertEquals(net.minecraft.world.InteractionResult.PASS, res);
        });

        assertDoesNotThrow(() -> {
            var res = VillagerGossipListener.formatRumor(null, "A mysterious rumor");
            assertNotNull(res);
            assertTrue(res.getString().contains("A mysterious rumor"));
        });
    }

    @Test
    @DisplayName("VillagerGossipListener passesChance respects boundary conditions and probability")
    void testChanceGuard() {
        assertFalse(VillagerGossipListener.passesChance(0.0, 0.0));
        assertFalse(VillagerGossipListener.passesChance(0.5, 0.0));
        assertFalse(VillagerGossipListener.passesChance(0.0, -0.2));

        assertTrue(VillagerGossipListener.passesChance(0.0, 1.0));
        assertTrue(VillagerGossipListener.passesChance(0.999, 1.0));
        assertTrue(VillagerGossipListener.passesChance(0.5, 1.5));

        assertTrue(VillagerGossipListener.passesChance(0.24, 0.25));
        assertFalse(VillagerGossipListener.passesChance(0.25, 0.25));
        assertFalse(VillagerGossipListener.passesChance(0.26, 0.25));

        assertTrue(VillagerGossipListener.passesChance(0.49, 0.5));
        assertFalse(VillagerGossipListener.passesChance(0.50, 0.5));
    }

    @Test
    @DisplayName("T020: Worker runs safely and isolates errors during digest cycle")
    void testWorkerIsolatesErrorsDuringCycle() {
        GossipConfig config = new GossipConfig(true, "valid-key", "gemini-3.8-flash", 20, 3, true, 0.85, false);
        GossipApiClient client = new GossipApiClient(
                config,
                HttpClient.newHttpClient(),
                "http://127.0.0.1:" + port,
                WallClock.SYSTEM
        );
        AtomicReference<GossipPool> poolRef = new AtomicReference<>(GossipPool.empty());

        // Worker with throwing inflation supplier and bad directory
        GossipDigestWorker worker = new GossipDigestWorker(
                config,
                client,
                poolRef,
                null,
                () -> { throw new RuntimeException("Simulated disk error"); },
                () -> { throw new RuntimeException("Simulated inflation error"); },
                null,
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor(),
                true
        );

        // runDigestCycle should catch Throwable and not throw
        assertDoesNotThrow(worker::runDigestCycle);
        assertEquals(GossipPool.empty(), poolRef.get(), "Pool should remain safely empty");
    }
}
