package com.reazip.economycraft.gossip;

import com.sun.net.httpserver.HttpServer;
import com.reazip.economycraft.time.MutableClock;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class GeminiClientTest {

    private HttpServer server;
    private int port;
    private MutableClock clock;
    private final AtomicInteger requestCounter = new AtomicInteger(0);
    private final AtomicReference<String> lastCapturedBody = new AtomicReference<>("");
    private final AtomicReference<String> lastCapturedApiKey = new AtomicReference<>("");

    private int responseStatusCode = 200;
    private String responsePayload = "";

    @BeforeEach
    void setUp() throws IOException {
        requestCounter.set(0);
        lastCapturedBody.set("");
        lastCapturedApiKey.set("");
        responseStatusCode = 200;
        responsePayload = "";
        clock = new MutableClock(1_000_000_000L);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/", exchange -> {
            requestCounter.incrementAndGet();
            lastCapturedApiKey.set(exchange.getRequestHeaders().getFirst("x-goog-api-key"));
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            lastCapturedBody.set(body);

            byte[] bytes = responsePayload.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(responseStatusCode, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private GeminiClient createClient(String apiKey) {
        GossipConfig config = new GossipConfig(true, apiKey, "gemini-3.8-flash", 20, 3, true, 0.85, false);
        return new GeminiClient(config, java.net.http.HttpClient.newHttpClient(), "http://127.0.0.1:" + port, clock);
    }

    @Test
    void testRequestHeadersAndPayloadStructure() throws Exception {
        responseStatusCode = 200;
        responsePayload = """
        {
          "candidates": [{
            "content": {
              "parts": [{
                "text": "{\\"farmer\\":[\\"Good harvest!\\"],\\"blacksmith\\":[\\"Cheap iron!\\"]}"
              }]
            }
          }]
        }
        """;

        GeminiClient client = createClient("test-api-key-123");
        Optional<GossipPool> pool = client.generateRumors("<context>test</context>").get();

        assertTrue(pool.isPresent());
        assertEquals("test-api-key-123", lastCapturedApiKey.get());

        String captured = lastCapturedBody.get();
        assertTrue(captured.contains("BLOCK_ONLY_HIGH"));
        assertTrue(captured.contains("\"thinkingBudget\":0"));
        assertTrue(captured.contains("response_schema"));
        assertTrue(captured.contains("farmer"));
        assertTrue(captured.contains("blacksmith"));
    }

    @Test
    void testSuccessfulResponseParsingIntoGossipPool() throws Exception {
        responseStatusCode = 200;
        responsePayload = """
        {
          "candidates": [{
            "content": {
              "parts": [{
                "text": "{\\"farmer\\":[\\"Wheat prices soar!\\"],\\"general\\":[\\"Economy moves fast.\\"]}"
              }]
            }
          }]
        }
        """;

        GeminiClient client = createClient("test-key");
        Optional<GossipPool> result = client.generateRumors("ctx").get();

        assertTrue(result.isPresent());
        GossipPool pool = result.get();
        assertFalse(pool.isEmpty());
        assertEquals("Wheat prices soar!", pool.getRandomRumor(GossipCategory.FARMER));
        // Fallback to GENERAL for categories without specific rumors:
        assertEquals("Economy moves fast.", pool.getRandomRumor(GossipCategory.CLERIC));
    }

    @Test
    void testMarkdownCodeFenceStripping() throws Exception {
        responseStatusCode = 200;
        responsePayload = """
        {
          "candidates": [{
            "content": {
              "parts": [{
                "text": "```json\\n{\\"farmer\\":[\\"Fence test\\"]}\\n```"
              }]
            }
          }]
        }
        """;

        GeminiClient client = createClient("test-key");
        Optional<GossipPool> result = client.generateRumors("ctx").get();

        assertTrue(result.isPresent());
        assertEquals("Fence test", result.get().getRandomRumor(GossipCategory.FARMER));
    }

    @Test
    void testCircuitBreakerTripsAfterThreeFailures() throws Exception {
        responseStatusCode = 500;
        responsePayload = "Internal Server Error";

        GeminiClient client = createClient("test-key");
        assertFalse(client.isCircuitOpen());

        // 1st failure
        client.generateRumors("ctx").get();
        assertFalse(client.isCircuitOpen());

        // 2nd failure
        client.generateRumors("ctx").get();
        assertFalse(client.isCircuitOpen());

        // 3rd failure
        client.generateRumors("ctx").get();
        assertTrue(client.isCircuitOpen());
        assertEquals(3, requestCounter.get());

        // 4th call: circuit is OPEN, should NOT make HTTP request
        Optional<GossipPool> fourth = client.generateRumors("ctx").get();
        assertTrue(fourth.isEmpty());
        assertEquals(3, requestCounter.get(), "Circuit open must prevent further HTTP requests");
    }

    @Test
    void testCircuitBreakerQuietPeriodAndRecovery() throws Exception {
        responseStatusCode = 500;
        responsePayload = "Server Error";

        GeminiClient client = createClient("test-key");
        for (int i = 0; i < 3; i++) {
            client.generateRumors("ctx").get();
        }
        assertTrue(client.isCircuitOpen());

        // Advance clock by 30 minutes
        clock.advanceMinutes(30);
        assertFalse(client.isCircuitOpen(), "Circuit should allow requests after 30 minutes");

        // Now mock success
        responseStatusCode = 200;
        responsePayload = """
        {
          "candidates": [{
            "content": {
              "parts": [{
                "text": "{\\"general\\":[\\"Recovered!\\"]}"
              }]
            }
          }]
        }
        """;

        Optional<GossipPool> recovery = client.generateRumors("ctx").get();
        assertTrue(recovery.isPresent());
        assertFalse(client.isCircuitOpen());
        assertEquals(0, client.getConsecutiveFailures());
    }

    @Test
    void testIntermediateSuccessResetsFailureCounter() throws Exception {
        GeminiClient client = createClient("test-key");

        // 2 failures
        responseStatusCode = 500;
        responsePayload = "Error";
        client.generateRumors("ctx").get();
        client.generateRumors("ctx").get();
        assertEquals(2, client.getConsecutiveFailures());
        assertFalse(client.isCircuitOpen());

        // 1 success
        responseStatusCode = 200;
        responsePayload = """
        {
          "candidates": [{
            "content": {
              "parts": [{
                "text": "{\\"general\\":[\\"All good\\"]}"
              }]
            }
          }]
        }
        """;
        Optional<GossipPool> success = client.generateRumors("ctx").get();
        assertTrue(success.isPresent());
        assertEquals(0, client.getConsecutiveFailures());
        assertFalse(client.isCircuitOpen());

        // 1 failure again
        responseStatusCode = 500;
        responsePayload = "Error";
        client.generateRumors("ctx").get();
        assertEquals(1, client.getConsecutiveFailures());
        assertFalse(client.isCircuitOpen());
    }

    @Test
    void testUnconfiguredApiKeySilent() throws Exception {
        GeminiClient client = createClient("");
        Optional<GossipPool> pool = client.generateRumors("ctx").get();

        assertTrue(pool.isEmpty());
        assertEquals(0, requestCounter.get());
        assertFalse(client.isCircuitOpen());
    }

    @Test
    void testSafetyBlockHandling() throws Exception {
        responseStatusCode = 200;
        responsePayload = """
        {
          "candidates": [{
            "finishReason": "SAFETY"
          }]
        }
        """;

        GeminiClient client = createClient("test-key");
        Optional<GossipPool> pool = client.generateRumors("ctx").get();

        assertTrue(pool.isEmpty());
    }

    @Test
    void testMalformedJsonResponse() throws Exception {
        responseStatusCode = 200;
        responsePayload = """
        {
          "candidates": [{
            "content": {
              "parts": [{
                "text": "{broken-json"
              }]
            }
          }]
        }
        """;

        GeminiClient client = createClient("test-key");
        Optional<GossipPool> pool = client.generateRumors("ctx").get();

        assertTrue(pool.isEmpty());
        assertEquals(1, client.getConsecutiveFailures());
    }

    @Test
    void testHttp429QuotaExhaustion() throws Exception {
        responseStatusCode = 429;
        responsePayload = "Too Many Requests";

        GeminiClient client = createClient("test-key");
        Optional<GossipPool> pool = client.generateRumors("ctx").get();

        assertTrue(pool.isEmpty());
        assertEquals(1, client.getConsecutiveFailures());
    }

    @Test
    void testStripMarkdownFences() {
        assertEquals("", GeminiClient.stripMarkdownFences(null));
        assertEquals("", GeminiClient.stripMarkdownFences(""));
        assertEquals("{}", GeminiClient.stripMarkdownFences("```json\n{}\n```"));
        assertEquals("{}", GeminiClient.stripMarkdownFences("```JSON\n{}\n```"));
        assertEquals("{\"a\":1}", GeminiClient.stripMarkdownFences("```\n{\"a\":1}\n```"));
        assertEquals("{\"a\":1}", GeminiClient.stripMarkdownFences("{\"a\":1}"));
    }
}
