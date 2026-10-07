package com.reazip.economycraft.gossip;

import com.sun.net.httpserver.HttpServer;
import com.reazip.economycraft.time.MutableClock;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class GossipApiClientTest {

    private HttpServer server;
    private int port;
    private MutableClock clock;
    private final AtomicInteger requestCounter = new AtomicInteger(0);
    private final AtomicReference<String> lastCapturedBody = new AtomicReference<>("");
    private final AtomicReference<String> lastCapturedApiKey = new AtomicReference<>("");
    private final AtomicReference<String> lastCapturedAuthHeader = new AtomicReference<>("");
    private final AtomicReference<String> lastCapturedUri = new AtomicReference<>("");

    private int responseStatusCode = 200;
    private String responsePayload = "";

    @BeforeEach
    void setUp() throws IOException {
        requestCounter.set(0);
        lastCapturedBody.set("");
        lastCapturedApiKey.set("");
        lastCapturedAuthHeader.set("");
        lastCapturedUri.set("");
        responseStatusCode = 200;
        responsePayload = "";
        clock = new MutableClock(1_000_000_000L);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/", exchange -> {
            requestCounter.incrementAndGet();
            lastCapturedUri.set(exchange.getRequestURI().toString());
            lastCapturedApiKey.set(exchange.getRequestHeaders().getFirst("x-goog-api-key"));
            lastCapturedAuthHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
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

    private GossipApiClient createGeminiClient(String apiKey) {
        GossipConfig config = new GossipConfig(true, apiKey, "gemini-3.8-flash", 20, 3, true, 0.85, false,
                GossipConfig.DEFAULT_SYSTEM_INSTRUCTION, 3, "http://127.0.0.1:" + port);
        // Note: For gemini protocol, baseUrl must match generativelanguage or default
        return new GossipApiClient(config, java.net.http.HttpClient.newHttpClient(), "http://127.0.0.1:" + port, clock) {
            @Override
            public boolean isOpenAiCompatible() {
                return false; // Force Gemini mode in this test client
            }
        };
    }

    private GossipApiClient createOpenAiClient(String apiKey) {
        GossipConfig config = new GossipConfig(true, apiKey, "gpt-4o-mini", 20, 3, true, 0.85, false,
                GossipConfig.DEFAULT_SYSTEM_INSTRUCTION, 3, "http://127.0.0.1:" + port + "/v1");
        return new GossipApiClient(config, java.net.http.HttpClient.newHttpClient(), "http://127.0.0.1:" + port + "/v1", clock);
    }

    @Test
    void testGeminiRequestHeadersAndPayloadStructure() throws Exception {
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

        GossipApiClient client = createGeminiClient("test-api-key-123");
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
    void testOpenAiCompatibleRequestHeadersAndPayloadStructure() throws Exception {
        responseStatusCode = 200;
        responsePayload = """
        {
          "choices": [{
            "message": {
              "content": "{\\"farmer\\":[\\"Fresh bread!\\"],\\"blacksmith\\":[\\"Swords ready!\\"]}"
            }
          }]
        }
        """;

        GossipApiClient client = createOpenAiClient("sk-test-openai-key");
        Optional<GossipPool> pool = client.generateRumors("<context>economy</context>").get();

        assertTrue(pool.isPresent());
        assertEquals("Bearer sk-test-openai-key", lastCapturedAuthHeader.get());
        assertTrue(lastCapturedUri.get().endsWith("/chat/completions"));

        String captured = lastCapturedBody.get();
        assertTrue(captured.contains("\"model\":\"gpt-4o-mini\""));
        assertTrue(captured.contains("\"json_object\""));
        assertTrue(captured.contains("role"));
        assertTrue(captured.contains("system"));
        assertTrue(captured.contains("user"));

        GossipPool res = pool.get();
        assertEquals(1, res.getRumors(GossipCategory.FARMER).size());
        assertEquals("Fresh bread!", res.getRumors(GossipCategory.FARMER).get(0));
    }

    @Test
    void testSuccessfulResponseParsingIntoGossipPool() throws Exception {
        responseStatusCode = 200;
        responsePayload = """
        {
          "candidates": [{
            "content": {
              "parts": [{
                "text": "{\\"farmer\\":[\\"Apples!\\"],\\"cleric\\":[\\"Potions!\\"],\\"general\\":[\\"Economy booming!\\"]}"
              }]
            }
          }]
        }
        """;

        GossipApiClient client = createGeminiClient("test-key");
        Optional<GossipPool> pool = client.generateRumors("ctx").get();

        assertTrue(pool.isPresent());
        GossipPool p = pool.get();
        assertEquals(3, p.totalRumors());
        assertEquals(List.of("Apples!"), p.getRumors(GossipCategory.FARMER));
        assertEquals(List.of("Potions!"), p.getRumors(GossipCategory.CLERIC));
        assertEquals(List.of("Economy booming!"), p.getRumors(GossipCategory.GENERAL));
        assertTrue(p.getRumors(GossipCategory.BLACKSMITH).isEmpty());
    }

    @Test
    void testCircuitBreakerTripsAfterThreeConsecutiveFailures() throws Exception {
        responseStatusCode = 500;
        responsePayload = "Internal Server Error";

        GossipApiClient client = createGeminiClient("test-key");
        assertFalse(client.isCircuitOpen());

        // 1st failure
        client.generateRumors("ctx").get();
        assertEquals(1, client.getConsecutiveFailures());
        assertFalse(client.isCircuitOpen());

        // 2nd failure
        client.generateRumors("ctx").get();
        assertEquals(2, client.getConsecutiveFailures());
        assertFalse(client.isCircuitOpen());

        // 3rd failure - should trip circuit breaker
        client.generateRumors("ctx").get();
        assertEquals(3, client.getConsecutiveFailures());
        assertTrue(client.isCircuitOpen());

        // 4th call: circuit breaker should immediately block without sending HTTP request
        int requestsBefore = requestCounter.get();
        Optional<GossipPool> blocked = client.generateRumors("ctx").get();
        assertTrue(blocked.isEmpty());
        assertEquals(requestsBefore, requestCounter.get());
    }

    @Test
    void testCircuitBreakerResetsAfterQuietPeriod() throws Exception {
        responseStatusCode = 500;
        responsePayload = "Internal Server Error";

        GossipApiClient client = createGeminiClient("test-key");

        client.generateRumors("ctx").get();
        client.generateRumors("ctx").get();
        client.generateRumors("ctx").get();
        assertTrue(client.isCircuitOpen());

        // Advance clock by 31 minutes (quiet period is 30m)
        clock.advanceMinutes(31);
        assertFalse(client.isCircuitOpen());

        // Next successful request should reset consecutive failures
        responseStatusCode = 200;
        responsePayload = """
        {
          "candidates": [{
            "content": {
              "parts": [{
                "text": "{\\"general\\":[\\"Peace restored!\\"]}"
              }]
            }
          }]
        }
        """;

        Optional<GossipPool> recovered = client.generateRumors("ctx").get();
        assertTrue(recovered.isPresent());
        assertEquals(0, client.getConsecutiveFailures());
        assertFalse(client.isCircuitOpen());
    }

    @Test
    void testTransientNetworkFailureHandling() throws Exception {
        responseStatusCode = 503;
        responsePayload = "Service Unavailable";

        GossipApiClient client = createGeminiClient("test-key");
        Optional<GossipPool> pool = client.generateRumors("ctx").get();

        assertTrue(pool.isEmpty());
        assertEquals(1, client.getConsecutiveFailures());
        assertFalse(client.isCircuitOpen());
    }

    @Test
    void testUnconfiguredApiKeySilent() throws Exception {
        GossipApiClient client = createGeminiClient("");
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

        GossipApiClient client = createGeminiClient("test-key");
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

        GossipApiClient client = createGeminiClient("test-key");
        Optional<GossipPool> pool = client.generateRumors("ctx").get();

        assertTrue(pool.isEmpty());
        assertEquals(1, client.getConsecutiveFailures());
    }

    @Test
    void testHttp429QuotaExhaustion() throws Exception {
        responseStatusCode = 429;
        responsePayload = "Too Many Requests";

        GossipApiClient client = createGeminiClient("test-key");
        Optional<GossipPool> pool = client.generateRumors("ctx").get();

        assertTrue(pool.isEmpty());
        assertEquals(1, client.getConsecutiveFailures());
    }

    @Test
    void testStripMarkdownFences() {
        assertEquals("", GossipApiClient.stripMarkdownFences(null));
        assertEquals("", GossipApiClient.stripMarkdownFences(""));
        assertEquals("{}", GossipApiClient.stripMarkdownFences("```json\n{}\n```"));
        assertEquals("{}", GossipApiClient.stripMarkdownFences("```JSON\n{}\n```"));
        assertEquals("{\"a\":1}", GossipApiClient.stripMarkdownFences("```\n{\"a\":1}\n```"));
        assertEquals("{\"a\":1}", GossipApiClient.stripMarkdownFences("{\"a\":1}"));
    }
}
