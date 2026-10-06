package com.reazip.economycraft.gossip;

import com.google.gson.*;
import com.mojang.logging.LogUtils;
import com.reazip.economycraft.time.WallClock;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Asynchronous HTTP/2 client for the Google Gemini Generative Language REST API.
 *
 * <p>Enforces header authentication ({@code x-goog-api-key}), {@code BLOCK_ONLY_HIGH} safety thresholds,
 * low-latency {@code thinkingBudget: 0}, and a 3-consecutive-failure / 30-minute quiet period circuit breaker.
 */
public class GeminiClient {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();
    public static final String DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com";
    private static final long QUIET_PERIOD_MILLIS = 30 * 60 * 1000L; // 30 minutes
    private static final int FAILURE_THRESHOLD = 3;

    private final GossipConfig config;
    private final HttpClient httpClient;
    private final String baseUrl;
    private final WallClock clock;

    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicLong circuitOpenUntilMillis = new AtomicLong(0L);

    public GeminiClient(GossipConfig config) {
        this(config, HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(10))
                .build(),
                DEFAULT_BASE_URL,
                WallClock.SYSTEM);
    }

    public GeminiClient(GossipConfig config, HttpClient httpClient, String baseUrl, WallClock clock) {
        this.config = config;
        this.httpClient = httpClient;
        this.baseUrl = baseUrl;
        this.clock = clock;
    }

    public boolean isCircuitOpen() {
        if (consecutiveFailures.get() < FAILURE_THRESHOLD) {
            return false;
        }
        return clock.millis() < circuitOpenUntilMillis.get();
    }

    public void recordSuccess() {
        consecutiveFailures.set(0);
        circuitOpenUntilMillis.set(0L);
    }

    public void recordFailure() {
        recordFailure(-1, "unspecified");
    }

    public void recordFailure(int statusCode, String reason) {
        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= FAILURE_THRESHOLD) {
            circuitOpenUntilMillis.set(clock.millis() + QUIET_PERIOD_MILLIS);
            LOGGER.warn("[EconomyCraft-Gemini] Circuit breaker opened after {} failures (last status: {}, reason: {}). Pausing requests for 30m.",
                    failures, statusCode, reason);
        } else {
            LOGGER.warn("[EconomyCraft-Gemini] Request failed (failures: {}/{}, status: {}, reason: {}).",
                    failures, FAILURE_THRESHOLD, statusCode, reason);
        }
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures.get();
    }

    public CompletableFuture<Optional<GossipPool>> generateRumors(@org.jetbrains.annotations.Nullable TransactionDigest digest) {
        if (digest == null) {
            return generateRumors("The village market is quiet with standard trade activity.");
        }
        return generateRumors(digest.toPromptContext());
    }

    public CompletableFuture<Optional<GossipPool>> generateRumors(String economicContext) {
        String apiKey = config.getEffectiveApiKey();
        if (!config.enabled() || apiKey == null || apiKey.isBlank()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        if (isCircuitOpen()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        String url = String.format("%s/v1beta/models/%s:generateContent", baseUrl, config.model());
        String requestJson = buildRequestBody(economicContext);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("x-goog-api-key", apiKey)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(25))
                .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .<Optional<GossipPool>>thenApply(response -> {
                    if (response.statusCode() == 200) {
                        Optional<GossipPool> pool = parseResponse(response.body());
                        if (pool.isPresent()) {
                            recordSuccess();
                            return pool;
                        } else {
                            recordFailure(200, "Empty or blocked content");
                            return Optional.<GossipPool>empty();
                        }
                    } else {
                        recordFailure(response.statusCode(), "HTTP error");
                        return Optional.<GossipPool>empty();
                    }
                })
                .exceptionally(ex -> {
                    recordFailure(-1, ex.getMessage());
                    return Optional.<GossipPool>empty();
                });
    }

    public String buildRequestBody(String economicContext) {
        JsonObject root = new JsonObject();

        // system_instruction
        JsonObject systemInstruction = new JsonObject();
        JsonArray sysParts = new JsonArray();
        JsonObject sysPart = new JsonObject();
        sysPart.addProperty("text", "You are an economic town chronicler and satirical peasant gossip writer for a medieval Minecraft village. Based on the provided transaction summary, write 2-3 short, witty, exaggerated gossip lines (1 sentence each) for each villager profession. Include typical villager 'Hrmm...' mannerisms. Never mention real player usernames; use the given archetypes.");
        sysParts.add(sysPart);
        systemInstruction.add("parts", sysParts);
        root.add("system_instruction", systemInstruction);

        // contents
        JsonArray contents = new JsonArray();
        JsonObject contentObj = new JsonObject();
        JsonArray contentParts = new JsonArray();
        JsonObject textPart = new JsonObject();
        textPart.addProperty("text", economicContext != null ? economicContext : "<economic_context>No recent activity</economic_context>");
        contentParts.add(textPart);
        contentObj.add("parts", contentParts);
        contents.add(contentObj);
        root.add("contents", contents);

        // safetySettings
        JsonArray safetySettings = new JsonArray();
        String[] categories = {
                "HARM_CATEGORY_HARASSMENT",
                "HARM_CATEGORY_HATE_SPEECH",
                "HARM_CATEGORY_SEXUALLY_EXPLICIT",
                "HARM_CATEGORY_DANGEROUS_CONTENT"
        };
        for (String cat : categories) {
            JsonObject safety = new JsonObject();
            safety.addProperty("category", cat);
            safety.addProperty("threshold", "BLOCK_ONLY_HIGH");
            safetySettings.add(safety);
        }
        root.add("safetySettings", safetySettings);

        // generationConfig
        JsonObject genConfig = new JsonObject();
        genConfig.addProperty("response_mime_type", "application/json");
        genConfig.addProperty("temperature", Math.clamp(config.temperature(), 0.0, 2.0));
        genConfig.addProperty("maxOutputTokens", 1024);

        JsonObject thinkingConfig = new JsonObject();
        thinkingConfig.addProperty("thinkingBudget", 0);
        genConfig.add("thinkingConfig", thinkingConfig);

        // response_schema
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "OBJECT");
        JsonObject properties = new JsonObject();
        JsonArray required = new JsonArray();

        for (GossipCategory cat : GossipCategory.values()) {
            JsonObject prop = new JsonObject();
            prop.addProperty("type", "ARRAY");
            JsonObject itemType = new JsonObject();
            itemType.addProperty("type", "STRING");
            prop.add("items", itemType);

            properties.add(cat.jsonKey(), prop);
            required.add(cat.jsonKey());
        }
        schema.add("properties", properties);
        schema.add("required", required);
        genConfig.add("response_schema", schema);

        root.add("generationConfig", genConfig);
        return GSON.toJson(root);
    }

    public Optional<GossipPool> parseResponse(String responseBody) {
        try {
            JsonObject json = GSON.fromJson(responseBody, JsonObject.class);
            if (json == null || !json.has("candidates")) {
                return Optional.empty();
            }
            JsonArray candidates = json.getAsJsonArray("candidates");
            if (candidates.isEmpty()) {
                return Optional.empty();
            }
            JsonObject first = candidates.get(0).getAsJsonObject();
            if (first.has("finishReason") && "SAFETY".equalsIgnoreCase(first.get("finishReason").getAsString())) {
                LOGGER.warn("[EconomyCraft-Gemini] Generation blocked by safety filter.");
                return Optional.empty();
            }
            if (!first.has("content")) {
                return Optional.empty();
            }
            JsonObject content = first.getAsJsonObject("content");
            JsonArray parts = content.getAsJsonArray("parts");
            if (parts == null || parts.isEmpty()) {
                return Optional.empty();
            }
            String text = parts.get(0).getAsJsonObject().get("text").getAsString();
            String cleanText = stripMarkdownFences(text);

            JsonObject rumorsObj = GSON.fromJson(cleanText, JsonObject.class);
            if (rumorsObj == null) {
                return Optional.empty();
            }

            Map<GossipCategory, List<String>> map = new EnumMap<>(GossipCategory.class);
            for (GossipCategory cat : GossipCategory.values()) {
                if (rumorsObj.has(cat.jsonKey())) {
                    JsonElement elem = rumorsObj.get(cat.jsonKey());
                    if (elem.isJsonArray()) {
                        List<String> lines = new ArrayList<>();
                        for (JsonElement item : elem.getAsJsonArray()) {
                            lines.add(item.getAsString());
                        }
                        if (!lines.isEmpty()) {
                            map.put(cat, lines);
                        }
                    }
                }
            }

            return Optional.of(new GossipPool(map, Instant.now()));
        } catch (Exception e) {
            LOGGER.warn("[EconomyCraft-Gemini] Failed to parse Gemini response: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public static String stripMarkdownFences(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.regionMatches(true, 0, "```json", 0, 7)) {
            s = s.substring(7);
        } else if (s.startsWith("```")) {
            s = s.substring(3);
        }
        if (s.endsWith("```")) {
            s = s.substring(0, s.length() - 3);
        }
        return s.trim();
    }
}
