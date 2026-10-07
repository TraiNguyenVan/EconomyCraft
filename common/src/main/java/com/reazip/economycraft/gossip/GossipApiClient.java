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
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

    public CompletableFuture<Optional<GossipPool>> generateRumors(@Nullable TransactionDigest digest) {
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

        boolean isOpenAi = isOpenAiCompatible();
        String url;
        String requestJson;
        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .timeout(Duration.ofSeconds(25))
                .header("Content-Type", "application/json");

        if (isOpenAi) {
            url = baseUrl.endsWith("/chat/completions") ? baseUrl : baseUrl + "/chat/completions";
            requestJson = buildOpenAiRequestBody(economicContext);
            reqBuilder.header("Authorization", "Bearer " + apiKey);
        } else {
            url = String.format("%s/v1beta/models/%s:generateContent", baseUrl, config.model());
            requestJson = buildGeminiRequestBody(economicContext);
            reqBuilder.header("x-goog-api-key", apiKey);
        }

        HttpRequest request = reqBuilder
                .uri(URI.create(url))
                .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .<Optional<GossipPool>>thenApply(response -> {
                    if (response.statusCode() == 200) {
                        Optional<GossipPool> pool = isOpenAi
                                ? parseOpenAiResponse(response.body())
                                : parseGeminiResponse(response.body());
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
                    Throwable cause = (ex.getCause() != null) ? ex.getCause() : ex;
                    LOGGER.warn("[EconomyCraft-AI] Request failed with exception: {}", cause.toString());
                    recordFailure(-1, cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName());
                    return Optional.<GossipPool>empty();
                });
    }

    public boolean isOpenAiCompatible() {
        return config.isOpenAiCompatible();
    }

    /**
     * Asynchronously generates personalized, in-character dialogue for an individual villager
     * based on their persistent persona, memory with the visiting player, and profession grapevine news.
     */
    public CompletableFuture<Optional<IndividualDialogueResult>> generateIndividualDialogue(
            VillagerProfile profile,
            PlayerMemory memory,
            String playerArchetype,
            @Nullable List<String> grapevineRumors,
            double inflation
    ) {
        String apiKey = config.apiKey();
        if (!config.enabled() || apiKey == null || apiKey.isBlank() || isCircuitOpen()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        String systemInstruction = VillagerDialoguePromptBuilder.buildSystemInstruction(
                profile, memory, playerArchetype, grapevineRumors, inflation, config.dialogueSystemInstruction());

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
        userMsg.addProperty("content", "Customer approaches your stall. Speak to them in 1 sentence matching your persona and memories. Respond strictly in JSON: {\"dialogue\": \"...\", \"sentiment_delta\": <int>}");
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
        textPart.addProperty("text", "Customer approaches your stall. Speak to them in 1 sentence matching your persona and memories.");
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

    // --- Gemini Request / Response ---

    public String buildGeminiRequestBody(String economicContext) {
        JsonObject root = new JsonObject();

        // system_instruction
        JsonObject systemInstruction = new JsonObject();
        JsonArray sysParts = new JsonArray();
        JsonObject sysPart = new JsonObject();
        sysPart.addProperty("text", config.systemInstruction());
        sysParts.add(sysPart);
        systemInstruction.add("parts", sysParts);
        root.add("system_instruction", systemInstruction);

        // contents
        JsonArray contents = new JsonArray();
        JsonObject contentObj = new JsonObject();
        JsonArray contentParts = new JsonArray();
        JsonObject textPart = new JsonObject();
        String promptText = (economicContext != null ? economicContext : "<economic_context>No recent activity</economic_context>")
                + String.format("\n\nConstraint: Write exactly %d short, witty gossip lines for each villager profession category.", config.poolSizePerCategory());
        textPart.addProperty("text", promptText);
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
        genConfig.addProperty("maxOutputTokens", Math.max(1024, config.poolSizePerCategory() * 250));

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

    public Optional<GossipPool> parseGeminiResponse(String responseBody) {
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
                LOGGER.warn("[EconomyCraft-AI] Generation blocked by safety filter.");
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
            return parseGossipJson(text);
        } catch (Exception e) {
            LOGGER.warn("[EconomyCraft-AI] Failed to parse Gemini response: {}", e.getMessage());
            return Optional.empty();
        }
    }

    // --- OpenAI Compatible Request / Response ---

    public String buildOpenAiRequestBody(String economicContext) {
        JsonObject root = new JsonObject();
        root.addProperty("model", config.model());
        root.addProperty("temperature", Math.clamp(config.temperature(), 0.0, 2.0));
        // Increased token budget to comfortably accommodate reasoning models (e.g. DeepSeek-R1, Nemotron, etc.)
        root.addProperty("max_tokens", Math.max(3072, config.poolSizePerCategory() * 500));

        // Suppress extraneous chain-of-thought preambles so reasoning models output JSON immediately
        JsonObject reasoning = new JsonObject();
        reasoning.addProperty("effort", "none");
        root.add("reasoning", reasoning);

        JsonObject responseFormat = new JsonObject();
        responseFormat.addProperty("type", "json_object");
        root.add("response_format", responseFormat);

        JsonArray messages = new JsonArray();

        // System message
        JsonObject sysMsg = new JsonObject();
        sysMsg.addProperty("role", "system");
        sysMsg.addProperty("content", config.systemInstruction() + "\nRespond strictly with valid JSON containing keys for categories: farmer, blacksmith, cleric, librarian, nitwit, general. Each key maps to an array of rumor strings. Do not wrap with extra text.");
        messages.add(sysMsg);

        // User message
        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        String promptText = (economicContext != null ? economicContext : "<economic_context>No recent activity</economic_context>")
                + String.format("\n\nConstraint: Write exactly %d short, witty gossip lines for each villager profession category.", config.poolSizePerCategory());
        userMsg.addProperty("content", promptText);
        messages.add(userMsg);

        root.add("messages", messages);
        return GSON.toJson(root);
    }

    public Optional<GossipPool> parseOpenAiResponse(String responseBody) {
        try {
            JsonObject json = GSON.fromJson(responseBody, JsonObject.class);
            if (json == null || !json.has("choices")) {
                return Optional.empty();
            }
            JsonArray choices = json.getAsJsonArray("choices");
            if (choices.isEmpty()) {
                return Optional.empty();
            }
            JsonObject first = choices.get(0).getAsJsonObject();
            if (!first.has("message")) {
                return Optional.empty();
            }
            JsonObject message = first.getAsJsonObject("message");
            if (!message.has("content") || message.get("content").isJsonNull()) {
                return Optional.empty();
            }
            String content = message.get("content").getAsString();
            return parseGossipJson(content);
        } catch (Exception e) {
            LOGGER.warn("[EconomyCraft-AI] Failed to parse OpenAI response: {}", e.getMessage());
            return Optional.empty();
        }
    }

    // --- Common JSON Parsing ---

    public Optional<GossipPool> parseGossipJson(String rawText) {
        try {
            String cleanText = extractJsonObject(rawText);
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
                        List<String> diversified = diversifyRumors(lines);
                        if (!diversified.isEmpty()) {
                            map.put(cat, diversified);
                        }
                    }
                }
            }

            return Optional.of(new GossipPool(map, Instant.now()));
        } catch (Exception e) {
            LOGGER.warn("[EconomyCraft-AI] Failed to parse gossip JSON: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private static final Pattern LEADING_GRUNT_PATTERN =
            Pattern.compile("^(?:[Hh][Rr]?[Mm]+|[Hh]uh|[Hh]aah?)[.!?,… -]+(.*)$", Pattern.CASE_INSENSITIVE);

    /**
     * Prevents repetitive opening grunts (e.g. 'Hrmm...') from dominating every rumor in a category.
     * Preserves villager personality on the first grunt match, but strips repetitive opening grunts
     * from subsequent lines in the category so dialogue feels varied and natural.
     */
    static List<String> diversifyRumors(List<String> rawLines) {
        if (rawLines == null || rawLines.isEmpty()) return List.of();
        List<String> result = new ArrayList<>(rawLines.size());
        boolean gruntKept = false;
        for (String line : rawLines) {
            if (line == null || line.isBlank()) continue;
            String trimmed = line.trim();
            Matcher matcher = LEADING_GRUNT_PATTERN.matcher(trimmed);
            if (matcher.matches()) {
                if (!gruntKept) {
                    gruntKept = true;
                    result.add(trimmed);
                } else {
                    String remainder = matcher.group(1).trim();
                    if (!remainder.isEmpty()) {
                        result.add(Character.toUpperCase(remainder.charAt(0)) + remainder.substring(1));
                    } else {
                        result.add(trimmed);
                    }
                }
            } else {
                result.add(trimmed);
            }
        }
        return Collections.unmodifiableList(result);
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
