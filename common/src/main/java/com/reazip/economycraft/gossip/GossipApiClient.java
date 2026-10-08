package com.reazip.economycraft.gossip;

import com.google.gson.*;
import com.mojang.logging.LogUtils;
import com.reazip.economycraft.time.WallClock;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import com.reazip.economycraft.gossip.identity.VillagerSeeder;
import com.reazip.economycraft.gossip.memory.IndividualDialogueResult;
import com.reazip.economycraft.gossip.memory.VillagerDialoguePromptBuilder;
import com.reazip.economycraft.gossip.storage.PlayerMemory;
import com.reazip.economycraft.gossip.storage.VillagerProfile;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Asynchronous HTTP/2 client for LLM providers generating villager gossip.
 *
 * <p>Auto-detects the provider from {@code baseUrl}:
 * <ul>
 *   <li><b>Google Gemini:</b> Uses Generative Language REST API with {@code x-goog-api-key} and structured {@code response_schema}.</li>
 *   <li><b>OpenAI-compatible:</b> Uses standard {@code /chat/completions} endpoint with {@code Authorization: Bearer <key>}
 *       and {@code response_format: {"type": "json_object"}}. Compatible with OpenAI, OpenRouter, DeepSeek, Groq, Ollama, etc.</li>
 * </ul>
 *
 * <p>Protected by a 3-consecutive-failure / 30-minute quiet period circuit breaker.
 */
public class GossipApiClient {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();
    public static final String DEFAULT_BASE_URL = GossipConfig.DEFAULT_BASE_URL;
    private static final long QUIET_PERIOD_MILLIS = 30 * 60 * 1000L; // 30 minutes
    private static final int FAILURE_THRESHOLD = 3;

    static {
        // Docker networks and certain hosting environments may lack IPv6 global routing;
        // ensure dual-stack hostnames prefer IPv4 to prevent ConnectException timeouts.
        try {
            System.setProperty("java.net.preferIPv6Addresses", "false");
        } catch (Throwable ignored) {}
    }

    private final GossipConfig config;
    private final HttpClient httpClient;
    private final String baseUrl;
    private final WallClock clock;

    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicLong circuitOpenUntilMillis = new AtomicLong(0L);

    public GossipApiClient(GossipConfig config) {
        this(config, HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10))
                .build(),
                config.baseUrl(),
                WallClock.SYSTEM);
    }

    public GossipApiClient(GossipConfig config, HttpClient httpClient, String baseUrl, WallClock clock) {
        this.config = config;
        this.httpClient = httpClient;
        this.baseUrl = (baseUrl != null && !baseUrl.isBlank()) ? baseUrl : config.baseUrl();
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
            LOGGER.warn("[EconomyCraft-AI] Circuit breaker opened after {} failures (last status: {}, reason: {}). Pausing requests for 30m.",
                    failures, statusCode, reason);
        } else {
            LOGGER.warn("[EconomyCraft-AI] Request failed (failures: {}/{}, status: {}, reason: {}).",
                    failures, FAILURE_THRESHOLD, statusCode, reason);
        }
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures.get();
    }

    public long getCircuitOpenUntilMillis() {
        return circuitOpenUntilMillis.get();
    }

    /**
     * Determines whether the configured endpoint speaks the OpenAI-compatible dialect.
     */
    public boolean isOpenAiCompatible() {
        return config.isOpenAiCompatible();
    }

    /**
     * Asynchronously generates personalized, in-character dialogue for an individual villager
     * based on their persistent persona, memory with the visiting player, and current stall stock.
     */
    public CompletableFuture<Optional<IndividualDialogueResult>> generateIndividualDialogue(
            VillagerProfile profile,
            PlayerMemory memory,
            String playerArchetype,
            double inflation
    ) {
        return generateIndividualDialogue(profile, memory, playerArchetype, inflation, null);
    }

    /**
     * Asynchronously generates personalized, in-character dialogue for an individual villager
     * based on their persistent persona and memory with the visiting player,
     * with negative prompting against recently spoken topics to prevent repetition.
     */
    public CompletableFuture<Optional<IndividualDialogueResult>> generateIndividualDialogue(
            VillagerProfile profile,
            PlayerMemory memory,
            String playerArchetype,
            double inflation,
            @Nullable List<String> recentSpokenTopics
    ) {
        return generateIndividualDialogue(profile, memory, playerArchetype, inflation, recentSpokenTopics, null, null);
    }

    /**
     * Asynchronously generates personalized, in-character dialogue for an individual villager
     * based on their persistent persona, memory with the visiting player,
     * recently spoken topics, current stall offers, and per-player trade history.
     */
    public CompletableFuture<Optional<IndividualDialogueResult>> generateIndividualDialogue(
            VillagerProfile profile,
            PlayerMemory memory,
            String playerArchetype,
            double inflation,
            @Nullable List<String> recentSpokenTopics,
            @Nullable List<com.reazip.economycraft.gossip.memory.TradeOfferSnapshot> currentOffers,
            @Nullable List<com.reazip.economycraft.gossip.storage.TradeRecord> tradeHistory
    ) {
        String apiKey = config.apiKey();
        if (!config.enabled() || apiKey == null || apiKey.isBlank() || isCircuitOpen()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        String systemInstruction = VillagerDialoguePromptBuilder.buildSystemInstruction(
                profile, memory, playerArchetype, inflation, config.dialogueSystemInstruction(), recentSpokenTopics, currentOffers, tradeHistory);

        LOGGER.info("[EconomyCraft-AI] Generating dialogue for villager {} ({}) with {} offer(s), {} trade history record(s).",
                profile.name(), profile.profession(),
                currentOffers != null ? currentOffers.size() : 0,
                tradeHistory != null ? tradeHistory.size() : 0);

        boolean isOpenAi = isOpenAiCompatible();
        String url;
        String requestJson;

        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json");

        if (isOpenAi) {
            url = baseUrl.endsWith("/chat/completions") ? baseUrl : baseUrl + "/chat/completions";
            requestJson = buildOpenAiIndividualDialogueRequestBody(systemInstruction);
            reqBuilder.header("Authorization", "Bearer " + apiKey);
        } else {
            url = String.format("%s/v1beta/models/%s:generateContent", baseUrl, config.model());
            requestJson = buildGeminiIndividualDialogueRequestBody(systemInstruction);
            reqBuilder.header("x-goog-api-key", apiKey);
        }

        HttpRequest request = reqBuilder
                .uri(URI.create(url))
                .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .<Optional<IndividualDialogueResult>>thenApply(response -> {
                    if (response.statusCode() == 200) {
                        Optional<IndividualDialogueResult> result = isOpenAi
                                ? parseOpenAiIndividualResponse(response.body())
                                : parseGeminiIndividualResponse(response.body());
                        if (result.isPresent()) {
                            recordSuccess();
                            return result;
                        } else {
                            recordFailure(200, "Empty individual dialogue response");
                            return Optional.empty();
                        }
                    } else {
                        recordFailure(response.statusCode(), "HTTP error in individual dialogue");
                        return Optional.empty();
                    }
                })
                .exceptionally(ex -> {
                    Throwable cause = (ex.getCause() != null) ? ex.getCause() : ex;
                    LOGGER.warn("[EconomyCraft-AI] Individual dialogue request failed: {}", cause.toString());
                    recordFailure(-1, cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName());
                    return Optional.empty();
                });
    }

    /**
     * Builds an OpenAI-compatible chat-completions request carrying the individual dialogue prompt.
     */
    private String buildOpenAiIndividualDialogueRequestBody(String systemPrompt) {
        JsonObject root = new JsonObject();
        root.addProperty("model", config.model());
        root.addProperty("temperature", Math.clamp(config.temperature(), 0.0, 1.5));
        root.addProperty("max_tokens", 1536);

        JsonObject reasoning = new JsonObject();
        reasoning.addProperty("effort", "none");
        root.add("reasoning", reasoning);

        JsonObject responseFormat = new JsonObject();
        responseFormat.addProperty("type", "json_object");
        root.add("response_format", responseFormat);

        JsonArray messages = new JsonArray();
        JsonObject sysMsg = new JsonObject();
        sysMsg.addProperty("role", "system");
        sysMsg.addProperty("content", systemPrompt);
        messages.add(sysMsg);

        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", "A customer approaches your stall. Greet them and pitch one of your specialty trade offers (mentioning the specific item or enchantment by name) matching your personality. Respond strictly in JSON: {\"dialogue\": \"...\", \"sentiment_delta\": <int>}");
        messages.add(userMsg);

        root.add("messages", messages);
        return GSON.toJson(root);
    }

    private String buildGeminiIndividualDialogueRequestBody(String systemPrompt) {
        JsonObject root = new JsonObject();

        JsonObject sysObj = new JsonObject();
        JsonArray sysParts = new JsonArray();
        JsonObject sysPart = new JsonObject();
        sysPart.addProperty("text", systemPrompt);
        sysParts.add(sysPart);
        sysObj.add("parts", sysParts);
        root.add("system_instruction", sysObj);

        JsonArray contents = new JsonArray();
        JsonObject contentObj = new JsonObject();
        JsonArray contentParts = new JsonArray();
        JsonObject textPart = new JsonObject();
        textPart.addProperty("text", "A customer approaches your stall. Greet them and pitch one of your specialty trade offers (mentioning the specific item or enchantment by name) matching your personality.");
        contentParts.add(textPart);
        contentObj.add("parts", contentParts);
        contents.add(contentObj);
        root.add("contents", contents);

        JsonObject genConfig = new JsonObject();
        genConfig.addProperty("response_mime_type", "application/json");
        genConfig.addProperty("temperature", Math.clamp(config.temperature(), 0.0, 1.5));
        genConfig.addProperty("maxOutputTokens", 512);

        JsonObject thinkingConfig = new JsonObject();
        thinkingConfig.addProperty("thinkingBudget", 0);
        genConfig.add("thinkingConfig", thinkingConfig);

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "OBJECT");
        JsonObject properties = new JsonObject();

        JsonObject dialogueProp = new JsonObject();
        dialogueProp.addProperty("type", "STRING");
        properties.add("dialogue", dialogueProp);

        JsonObject deltaProp = new JsonObject();
        deltaProp.addProperty("type", "INTEGER");
        properties.add("sentiment_delta", deltaProp);

        JsonArray required = new JsonArray();
        required.add("dialogue");
        required.add("sentiment_delta");

        schema.add("properties", properties);
        schema.add("required", required);
        genConfig.add("response_schema", schema);

        root.add("generationConfig", genConfig);
        return GSON.toJson(root);
    }

    private Optional<IndividualDialogueResult> parseOpenAiIndividualResponse(String responseBody) {
        try {
            JsonObject json = GSON.fromJson(responseBody, JsonObject.class);
            if (json == null || !json.has("choices")) return Optional.empty();
            JsonArray choices = json.getAsJsonArray("choices");
            if (choices.isEmpty()) return Optional.empty();
            JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
            if (message == null || !message.has("content")) return Optional.empty();
            return parseIndividualDialogueJson(message.get("content").getAsString());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private Optional<IndividualDialogueResult> parseGeminiIndividualResponse(String responseBody) {
        try {
            JsonObject json = GSON.fromJson(responseBody, JsonObject.class);
            if (json == null || !json.has("candidates")) return Optional.empty();
            JsonArray candidates = json.getAsJsonArray("candidates");
            if (candidates.isEmpty()) return Optional.empty();
            JsonObject first = candidates.get(0).getAsJsonObject();
            if (!first.has("content")) return Optional.empty();
            JsonArray parts = first.getAsJsonObject("content").getAsJsonArray("parts");
            if (parts == null || parts.isEmpty()) return Optional.empty();
            return parseIndividualDialogueJson(parts.get(0).getAsJsonObject().get("text").getAsString());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public Optional<IndividualDialogueResult> parseIndividualDialogueJson(String rawText) {
        try {
            String cleanText = extractJsonObject(rawText);
            JsonObject obj = GSON.fromJson(cleanText, JsonObject.class);
            if (obj == null || !obj.has("dialogue")) return Optional.empty();
            String dialogue = obj.get("dialogue").getAsString().trim();
            int delta = obj.has("sentiment_delta") ? obj.get("sentiment_delta").getAsInt() : 0;
            if (dialogue.isEmpty()) return Optional.empty();
            return Optional.of(new IndividualDialogueResult(dialogue, delta));
        } catch (Exception e) {
            LOGGER.warn("[EconomyCraft-AI] Failed to parse individual dialogue JSON: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Robust extractor that handles pure JSON, markdown fences, and reasoning/thinking model preambles
     * (e.g. "Here's a thinking process: ... { ... }").
     */
    public static String extractJsonObject(String raw) {
        if (raw == null) return "";
        String s = stripMarkdownFences(raw.trim());

        // Locate outermost matching JSON object brackets '{' ... '}'
        int firstBrace = s.indexOf('{');
        int lastBrace = s.lastIndexOf('}');
        if (firstBrace != -1 && lastBrace > firstBrace) {
            return s.substring(firstBrace, lastBrace + 1).trim();
        }
        return s;
    }

    public static String stripMarkdownFences(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        // Remove ```json / ``` markdown fences anywhere or wrap
        if (s.contains("```")) {
            int start = s.indexOf("```");
            int end = s.lastIndexOf("```");
            if (end > start) {
                String inner = s.substring(start + 3, end).trim();
                if (inner.regionMatches(true, 0, "json", 0, 4)) {
                    inner = inner.substring(4).trim();
                }
                return inner;
            }
        }
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
