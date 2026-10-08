package com.reazip.economycraft.gossip;

import com.reazip.economycraft.gossip.memory.IndividualDialogueResult;
import com.reazip.economycraft.gossip.storage.PlayerMemory;
import com.reazip.economycraft.gossip.storage.VillagerProfile;
import com.reazip.economycraft.time.MutableClock;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the private dialogue request path, the circuit breaker, and silent degradation.
 * The shared rumor-pool path was removed; these tests now drive generateIndividualDialogue.
 */
class GossipApiClientTest {

    private HttpServer server;
    private int port;
    private MutableClock clock;
    private final AtomicInteger requestCounter = new AtomicInteger(0);
    private final AtomicReference<String> lastCapturedBody = new AtomicReference<>("");
    private final AtomicReference<String> lastCapturedApiKey = new AtomicReference<>("");
    private final AtomicReference<String> lastCapturedAuthHeader = new AtomicReference<>("");

    private int responseStatusCode = 200;
    private String responsePayload = "";

    @BeforeEach
    void setUp() throws IOException {
        requestCounter.set(0);
        lastCapturedBody.set("");
        lastCapturedApiKey.set("");
        lastCapturedAuthHeader.set("");
        responseStatusCode = 200;
        responsePayload = "";
        clock = new MutableClock(1_000_000_000L);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/", exchange -> {
            requestCounter.incrementAndGet();
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

    private VillagerProfile testProfile() {
        return new VillagerProfile(
                UUID.randomUUID(), "Barnaby", "armorer", "plains",
                List.of("grumpy", "shrewd"), "Obsessed with iron purity.",
                "Left the capital after tax disputes.", 0, 0
        );
    }

    private PlayerMemory testMemory(VillagerProfile profile) {
        return new PlayerMemory(profile.uuid(), UUID.randomUUID(), 25, 4, 1500L, 0,
                List.of("Visited stall"));
    }

    private GossipConfig testConfig(String apiKey, String model, String baseUrl) {
        return new GossipConfig(true, apiKey, model, 3, true, 0.85, 0.5,
                GossipConfig.DEFAULT_DIALOGUE_SYSTEM_INSTRUCTION, baseUrl);
    }

    private GossipApiClient createGeminiClient(String apiKey) {
        return new GossipApiClient(testConfig(apiKey, "gemini-3.8-flash", "http://127.0.0.1:" + port),
                java.net.http.HttpClient.newHttpClient(), "http://127.0.0.1:" + port, clock) {
            @Override
            public boolean isOpenAiCompatible() {
                return false; // Force Gemini mode in this test client
            }
        };
    }

    private GossipApiClient createOpenAiClient(String apiKey) {
        return new GossipApiClient(testConfig(apiKey, "gpt-4o-mini", "http://127.0.0.1:" + port + "/v1"),
                java.net.http.HttpClient.newHttpClient(), "http://127.0.0.1:" + port + "/v1", clock);
    }

    private java.util.concurrent.CompletableFuture<Optional<IndividualDialogueResult>> requestDialogue(
            GossipApiClient client) {
        VillagerProfile profile = testProfile();
        return client.generateIndividualDialogue(profile, testMemory(profile), "The Feudal Lord", 7.5);
    }

    @Test
    void testGeminiRequestHeadersAndPayloadStructure() throws Exception {
        responseStatusCode = 200;
        responsePayload = """
        {
          "candidates": [{
            "content": {
              "parts": [{
                "text": "{\\"dialogue\\":\\"Careful, that blade is sharper than your purse.\\",\\"sentiment_delta\\":1}"
              }]
            }
          }]
        }
        """;

        GossipApiClient client = createGeminiClient("test-api-key-123");
        Optional<IndividualDialogueResult> result = requestDialogue(client).get();

        assertTrue(result.isPresent());
        assertEquals("test-api-key-123", lastCapturedApiKey.get());

        String captured = lastCapturedBody.get();
        assertTrue(captured.contains("\"thinkingBudget\":0"));
        assertTrue(captured.contains("response_schema"));
        assertTrue(captured.contains("sentiment_delta"));
        assertTrue(captured.contains("dialogue"));
        assertTrue(captured.contains("system_instruction"));
        // The persona reaches the provider in the system prompt, never as a player identity.
        assertTrue(captured.contains("Barnaby"));
    }

    @Test
    void testOpenAiCompatibleRequestHeadersAndPayloadStructure() throws Exception {
        responseStatusCode = 200;
        responsePayload = """
        {
          "choices": [{
            "message": {
              "content": "{\\"dialogue\\":\\"Mind the shop prices today.\\",\\"sentiment_delta\\":0}"
            }
          }]
        }
        """;

        GossipApiClient client = createOpenAiClient("sk-test-openai-key");
        Optional<IndividualDialogueResult> result = requestDialogue(client).get();

        assertTrue(result.isPresent());
        assertEquals("Bearer sk-test-openai-key", lastCapturedAuthHeader.get());

        String captured = lastCapturedBody.get();
        assertTrue(captured.contains("messages"));
        assertTrue(captured.contains("sentiment_delta"));
        assertTrue(captured.contains("Barnaby"));
    }

    @Test
    void testCircuitBreakerTripsAfterThreeConsecutiveFailures() throws Exception {
        responseStatusCode = 500;
        responsePayload = "Internal Server Error";

        GossipApiClient client = createGeminiClient("test-key");
        assertFalse(client.isCircuitOpen());

        requestDialogue(client).get();
        assertEquals(1, client.getConsecutiveFailures());
        assertFalse(client.isCircuitOpen());

        requestDialogue(client).get();
        assertEquals(2, client.getConsecutiveFailures());
        assertFalse(client.isCircuitOpen());

        requestDialogue(client).get();
        assertEquals(3, client.getConsecutiveFailures());
        assertTrue(client.isCircuitOpen());

        // 4th call: circuit breaker should immediately block without sending an HTTP request
        int requestsBefore = requestCounter.get();
        assertTrue(requestDialogue(client).get().isEmpty());
        assertEquals(requestsBefore, requestCounter.get());
    }

    @Test
    void testCircuitBreakerResetsAfterQuietPeriod() throws Exception {
        responseStatusCode = 500;
        responsePayload = "Internal Server Error";

        GossipApiClient client = createGeminiClient("test-key");

        requestDialogue(client).get();
        requestDialogue(client).get();
        requestDialogue(client).get();
        assertTrue(client.isCircuitOpen());

        // Advance clock by 31 minutes (quiet period is 30m)
        clock.advanceMinutes(31);
        assertFalse(client.isCircuitOpen());

        responseStatusCode = 200;
        responsePayload = """
        {
          "candidates": [{
            "content": {
              "parts": [{
                "text": "{\\"dialogue\\":\\"Peace restored, friend.\\",\\"sentiment_delta\\":2}"
              }]
            }
          }]
        }
        """;

        Optional<IndividualDialogueResult> recovered = requestDialogue(client).get();
        assertTrue(recovered.isPresent());
        assertEquals(0, client.getConsecutiveFailures());
        assertFalse(client.isCircuitOpen());
    }

    @Test
    void testTransientNetworkFailureHandling() throws Exception {
        responseStatusCode = 503;
        responsePayload = "Service Unavailable";

        GossipApiClient client = createGeminiClient("test-key");
        assertTrue(requestDialogue(client).get().isEmpty());
        assertEquals(1, client.getConsecutiveFailures());
        assertFalse(client.isCircuitOpen());
    }

    @Test
    void testUnconfiguredApiKeySilent() throws Exception {
        GossipApiClient client = createGeminiClient("");
        assertTrue(requestDialogue(client).get().isEmpty());
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
        assertTrue(requestDialogue(client).get().isEmpty());
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
        assertTrue(requestDialogue(client).get().isEmpty());
        assertEquals(1, client.getConsecutiveFailures());
    }

    @Test
    void testHttp429QuotaExhaustion() throws Exception {
        responseStatusCode = 429;
        responsePayload = "Too Many Requests";

        GossipApiClient client = createGeminiClient("test-key");
        assertTrue(requestDialogue(client).get().isEmpty());
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

    @Test
    void testExtractJsonObjectWithReasoningPreamble() {
        String reasoningOutput = """
                Here is a thinking process:
                1. The user wants villager dialogue.
                2. I will generate one line.
                ```json
                {
                  "dialogue": "Wheat is golden!",
                  "sentiment_delta": 1
                }
                ```
                I hope this helps!
                """;
        String extracted = GossipApiClient.extractJsonObject(reasoningOutput);
        assertTrue(extracted.startsWith("{"));
        assertTrue(extracted.endsWith("}"));
        assertTrue(extracted.contains("\"dialogue\""));
    }
}