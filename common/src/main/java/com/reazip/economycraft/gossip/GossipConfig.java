package com.reazip.economycraft.gossip;

import com.google.gson.TypeAdapter;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.annotations.SerializedName;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.function.Function;

/**
 * Configuration for the Gemini-powered Villager Gossip system.
 *
 * <p>Maps to the {@code "gemini_gossip"} section in {@code config.json}.
 */
@JsonAdapter(GossipConfig.Adapter.class)
public record GossipConfig(
        @SerializedName("enabled") boolean enabled,
        @SerializedName("api_key") String apiKey,
        @SerializedName("model") String model,
        @SerializedName("refresh_interval_minutes") int refreshIntervalMinutes,
        @SerializedName("cooldown_minutes") int cooldownMinutes,
        @SerializedName("anonymize_players") boolean anonymizePlayers,
        @SerializedName("temperature") double temperature,
        @SerializedName("public_chat") boolean publicChat,
        @SerializedName("public_chat_chance") double publicChatChance,
        @SerializedName("private_chat_chance") double privateChatChance,
        @SerializedName("system_instruction") String systemInstruction,
        @SerializedName("dialogue_system_instruction") String dialogueSystemInstruction,
        @SerializedName("pool_size_per_category") int poolSizePerCategory,
        @SerializedName("base_url") String baseUrl
) {
    private static final Logger LOGGER = LogUtils.getLogger();

    public static final boolean DEFAULT_ENABLED = true;
    public static final String DEFAULT_API_KEY = "";
    public static final String DEFAULT_MODEL = "gemini-3.8-flash";
    public static final String DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com";
    public static final int DEFAULT_REFRESH_INTERVAL_MINUTES = 20;
    public static final int DEFAULT_COOLDOWN_MINUTES = 3;
    public static final boolean DEFAULT_ANONYMIZE_PLAYERS = true;
    public static final double DEFAULT_TEMPERATURE = 0.85;
    public static final boolean DEFAULT_PUBLIC_CHAT = false;
    public static final double DEFAULT_PUBLIC_CHAT_CHANCE = 0.25;
    public static final double DEFAULT_PRIVATE_CHAT_CHANCE = 0.5;
    public static final double MIN_CHANCE = 0.0;
    public static final double MAX_CHANCE = 1.0;
    public static final int DEFAULT_POOL_SIZE_PER_CATEGORY = 3;
    public static final String DEFAULT_SYSTEM_INSTRUCTION =
            "You are a witty, satirical economic gossip for Minecraft villagers on an economy server. " +
            "Based on the provided transaction summary, write short, exaggerated gossip lines (1 sentence each) for each villager profession. " +
            "Villagers have quirky mannerisms: occasionally mutter, sigh, or hum (e.g. 'Hmm...', 'Hrmm...', 'Huh?', 'Haah...'), but vary how lines begin and do NOT start every line with 'Hrmm...' — many lines should begin directly. " +
            "Always refer to money in dollars ('$'). " +
            "Never mention real player usernames; use the given archetypes.";

    public static final String DEFAULT_DIALOGUE_SYSTEM_INSTRUCTION =
            "Dialogue Instructions:\n" +
            "1. Keep it short and easy to understand: most lines should be under 15 words. Avoid overly complex prose or purple vocabulary.\n" +
            "2. Speak in exactly 1 concise, conversational sentence matching your personality, quirk, and relationship with this player.\n" +
            "3. Address the player or your past memories directly when appropriate.\n" +
            "4. Villagers have quirky mannerisms: occasionally mutter or hum ('Hmm...', 'Huh?', 'Haah...'), but vary how you speak and DO NOT start every line with 'Hrmm...'.\n" +
            "5. Respond strictly with valid JSON with fields:\n" +
            "   {\n" +
            "     \"dialogue\": \"<your concise line>\",\n" +
            "     \"sentiment_delta\": <-2 to 5 integer>\n" +
            "   }";

    public static final int MIN_REFRESH_INTERVAL_MINUTES = 5;
    public static final int MAX_REFRESH_INTERVAL_MINUTES = 1440;
    public static final int MIN_COOLDOWN_MINUTES = 1;
    public static final int MAX_COOLDOWN_MINUTES = 60;
    public static final double MIN_TEMPERATURE = 0.0;
    public static final double MAX_TEMPERATURE = 2.0;
    public static final int MIN_POOL_SIZE_PER_CATEGORY = 3;
    public static final int MAX_POOL_SIZE_PER_CATEGORY = 10;

    public GossipConfig {
        apiKey = (apiKey == null) ? DEFAULT_API_KEY : apiKey.trim();
        if (model == null || model.isBlank()) {
            model = DEFAULT_MODEL;
        } else {
            model = model.trim();
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = DEFAULT_BASE_URL;
        } else {
            baseUrl = baseUrl.trim();
            if (baseUrl.endsWith("/")) {
                baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
            }
        }
        refreshIntervalMinutes = clampInt("gemini_gossip.refresh_interval_minutes", refreshIntervalMinutes,
                MIN_REFRESH_INTERVAL_MINUTES, MAX_REFRESH_INTERVAL_MINUTES);
        cooldownMinutes = clampInt("gemini_gossip.cooldown_minutes", cooldownMinutes,
                MIN_COOLDOWN_MINUTES, MAX_COOLDOWN_MINUTES);
        temperature = clampDouble("gemini_gossip.temperature", temperature,
                MIN_TEMPERATURE, MAX_TEMPERATURE);
        publicChatChance = clampDouble("gemini_gossip.public_chat_chance", publicChatChance,
                MIN_CHANCE, MAX_CHANCE);
        privateChatChance = clampDouble("gemini_gossip.private_chat_chance", privateChatChance,
                MIN_CHANCE, MAX_CHANCE);
        if (systemInstruction == null || systemInstruction.isBlank()) {
            systemInstruction = DEFAULT_SYSTEM_INSTRUCTION;
        } else {
            systemInstruction = systemInstruction.trim();
        }
        if (dialogueSystemInstruction == null || dialogueSystemInstruction.isBlank()) {
            dialogueSystemInstruction = DEFAULT_DIALOGUE_SYSTEM_INSTRUCTION;
        } else {
            dialogueSystemInstruction = dialogueSystemInstruction.trim();
        }
        poolSizePerCategory = clampInt("gemini_gossip.pool_size_per_category", poolSizePerCategory,
                MIN_POOL_SIZE_PER_CATEGORY, MAX_POOL_SIZE_PER_CATEGORY);
    }

    public GossipConfig(
            boolean enabled,
            String apiKey,
            String model,
            int refreshIntervalMinutes,
            int cooldownMinutes,
            boolean anonymizePlayers,
            double temperature,
            boolean publicChat
    ) {
        this(enabled, apiKey, model, refreshIntervalMinutes, cooldownMinutes, anonymizePlayers, temperature, publicChat, DEFAULT_PUBLIC_CHAT_CHANCE, DEFAULT_PRIVATE_CHAT_CHANCE, DEFAULT_SYSTEM_INSTRUCTION, DEFAULT_DIALOGUE_SYSTEM_INSTRUCTION, DEFAULT_POOL_SIZE_PER_CATEGORY, DEFAULT_BASE_URL);
    }

    public GossipConfig(
            boolean enabled,
            String apiKey,
            String model,
            int refreshIntervalMinutes,
            int cooldownMinutes,
            boolean anonymizePlayers,
            double temperature,
            boolean publicChat,
            String systemInstruction
    ) {
        this(enabled, apiKey, model, refreshIntervalMinutes, cooldownMinutes, anonymizePlayers, temperature, publicChat, DEFAULT_PUBLIC_CHAT_CHANCE, DEFAULT_PRIVATE_CHAT_CHANCE, systemInstruction, DEFAULT_DIALOGUE_SYSTEM_INSTRUCTION, DEFAULT_POOL_SIZE_PER_CATEGORY, DEFAULT_BASE_URL);
    }

    public GossipConfig(
            boolean enabled,
            String apiKey,
            String model,
            int refreshIntervalMinutes,
            int cooldownMinutes,
            boolean anonymizePlayers,
            double temperature,
            boolean publicChat,
            String systemInstruction,
            int poolSizePerCategory
    ) {
        this(enabled, apiKey, model, refreshIntervalMinutes, cooldownMinutes, anonymizePlayers, temperature, publicChat, DEFAULT_PUBLIC_CHAT_CHANCE, DEFAULT_PRIVATE_CHAT_CHANCE, systemInstruction, DEFAULT_DIALOGUE_SYSTEM_INSTRUCTION, poolSizePerCategory, DEFAULT_BASE_URL);
    }

    public GossipConfig(
            boolean enabled,
            String apiKey,
            String model,
            int refreshIntervalMinutes,
            int cooldownMinutes,
            boolean anonymizePlayers,
            double temperature,
            boolean publicChat,
            String systemInstruction,
            int poolSizePerCategory,
            String baseUrl
    ) {
        this(enabled, apiKey, model, refreshIntervalMinutes, cooldownMinutes, anonymizePlayers, temperature, publicChat, DEFAULT_PUBLIC_CHAT_CHANCE, DEFAULT_PRIVATE_CHAT_CHANCE, systemInstruction, DEFAULT_DIALOGUE_SYSTEM_INSTRUCTION, poolSizePerCategory, baseUrl);
    }

    public static GossipConfig createDefault() {
        return new GossipConfig(
                DEFAULT_ENABLED,
                DEFAULT_API_KEY,
                DEFAULT_MODEL,
                DEFAULT_REFRESH_INTERVAL_MINUTES,
                DEFAULT_COOLDOWN_MINUTES,
                DEFAULT_ANONYMIZE_PLAYERS,
                DEFAULT_TEMPERATURE,
                DEFAULT_PUBLIC_CHAT,
                DEFAULT_PUBLIC_CHAT_CHANCE,
                DEFAULT_PRIVATE_CHAT_CHANCE,
                DEFAULT_SYSTEM_INSTRUCTION,
                DEFAULT_DIALOGUE_SYSTEM_INSTRUCTION,
                DEFAULT_POOL_SIZE_PER_CATEGORY,
                DEFAULT_BASE_URL
        );
    }

    /**
     * Determines whether the endpoint is OpenAI-compatible based on the configured baseUrl and model.
     * If baseUrl contains "generativelanguage.googleapis.com", it is Google Gemini REST API.
     * If baseUrl is "https://api.openai.com", "openrouter", "deepseek", "groq", or ends with "/v1", it is OpenAI-compatible.
     * When baseUrl is a localhost or custom IP without explicit provider, it defaults to OpenAI-compatible
     * unless the model starts with "gemini-".
     */
    public boolean isOpenAiCompatible() {
        if (baseUrl == null || baseUrl.isBlank()) {
            return false;
        }
        String lower = baseUrl.toLowerCase();
        if (lower.contains("generativelanguage.googleapis.com")) {
            return false;
        }
        if (lower.contains("openai") || lower.contains("openrouter") || lower.contains("groq")
                || lower.contains("deepseek") || lower.contains("/v1") || lower.contains("ollama")) {
            return true;
        }
        // If user configured a Gemini model on a custom host/proxy (e.g. localhost test server for Gemini)
        if (model != null && model.toLowerCase().startsWith("gemini")) {
            return false;
        }
        return true;
    }

    /**
     * Resolves the active API key, falling back to the {@code GEMINI_API_KEY} or {@code OPENAI_API_KEY}
     * environment variable if the configured key is blank.
     *
     * @return non-null trimmed API key or empty string if not configured
     */
    public String getEffectiveApiKey() {
        return getEffectiveApiKey(System::getenv);
    }

    /**
     * Testable overload for resolving the active API key with a custom environment lookup.
     */
    public String getEffectiveApiKey(Function<String, String> envLookup) {
        if (!apiKey.isBlank()) {
            return apiKey;
        }
        if (isOpenAiCompatible()) {
            String openAiKey = envLookup.apply("OPENAI_API_KEY");
            if (openAiKey != null && !openAiKey.isBlank()) {
                return openAiKey.trim();
            }
        }
        String envKey = envLookup.apply("GEMINI_API_KEY");
        if (envKey != null && !envKey.isBlank()) {
            return envKey.trim();
        }
        return "";
    }

    /**
     * Returns a clamped copy of this configuration. Since the canonical constructor clamps on
     * creation, this produces an equivalent clamped record.
     */
    public GossipConfig clamped() {
        return new GossipConfig(
                enabled,
                apiKey,
                model,
                refreshIntervalMinutes,
                cooldownMinutes,
                anonymizePlayers,
                temperature,
                publicChat,
                publicChatChance,
                privateChatChance,
                systemInstruction,
                dialogueSystemInstruction,
                poolSizePerCategory,
                baseUrl
        );
    }

    private static int clampInt(String name, int value, int min, int max) {
        int clamped = Math.clamp(value, min, max);
        if (clamped != value) {
            LOGGER.warn("[EconomyCraft] {} ({}) is outside the valid {}-{} range; clamping to {}.",
                    name, value, min, max, clamped);
        }
        return clamped;
    }

    private static double clampDouble(String name, double value, double min, double max) {
        double clamped = Math.clamp(value, min, max);
        if (clamped != value) {
            LOGGER.warn("[EconomyCraft] {} ({}) is outside the valid {}-{} range; clamping to {}.",
                    name, value, min, max, clamped);
        }
        return clamped;
    }

    /**
     * Custom Gson adapter ensuring omitted keys in partial or empty JSON populate with
     * contract defaults rather than JVM zero values.
     */
    public static class Adapter extends TypeAdapter<GossipConfig> {
        @Override
        public void write(JsonWriter out, GossipConfig value) throws IOException {
            if (value == null) {
                out.nullValue();
                return;
            }
            out.beginObject();
            out.name("enabled").value(value.enabled());
            out.name("api_key").value(value.apiKey());
            out.name("model").value(value.model());
            out.name("refresh_interval_minutes").value(value.refreshIntervalMinutes());
            out.name("cooldown_minutes").value(value.cooldownMinutes());
            out.name("anonymize_players").value(value.anonymizePlayers());
            out.name("temperature").value(value.temperature());
            out.name("public_chat").value(value.publicChat());
            out.name("public_chat_chance").value(value.publicChatChance());
            out.name("private_chat_chance").value(value.privateChatChance());
            out.name("system_instruction").value(value.systemInstruction());
            out.name("dialogue_system_instruction").value(value.dialogueSystemInstruction());
            out.name("pool_size_per_category").value(value.poolSizePerCategory());
            out.name("base_url").value(value.baseUrl());
            out.endObject();
        }

        @Override
        public GossipConfig read(JsonReader in) throws IOException {
            if (in.peek() == com.google.gson.stream.JsonToken.NULL) {
                in.nextNull();
                return createDefault();
            }

            boolean enabled = DEFAULT_ENABLED;
            String apiKey = DEFAULT_API_KEY;
            String model = DEFAULT_MODEL;
            int refreshIntervalMinutes = DEFAULT_REFRESH_INTERVAL_MINUTES;
            int cooldownMinutes = DEFAULT_COOLDOWN_MINUTES;
            boolean anonymizePlayers = DEFAULT_ANONYMIZE_PLAYERS;
            double temperature = DEFAULT_TEMPERATURE;
            boolean publicChat = DEFAULT_PUBLIC_CHAT;
            double publicChatChance = DEFAULT_PUBLIC_CHAT_CHANCE;
            double privateChatChance = DEFAULT_PRIVATE_CHAT_CHANCE;
            String systemInstruction = DEFAULT_SYSTEM_INSTRUCTION;
            String dialogueSystemInstruction = DEFAULT_DIALOGUE_SYSTEM_INSTRUCTION;
            int poolSizePerCategory = DEFAULT_POOL_SIZE_PER_CATEGORY;
            String baseUrl = DEFAULT_BASE_URL;

            in.beginObject();
            while (in.hasNext()) {
                String name = in.nextName();
                if (in.peek() == com.google.gson.stream.JsonToken.NULL) {
                    in.nextNull();
                    continue;
                }
                switch (name) {
                    case "enabled" -> enabled = in.nextBoolean();
                    case "api_key" -> apiKey = in.nextString();
                    case "model" -> model = in.nextString();
                    case "refresh_interval_minutes" -> refreshIntervalMinutes = in.nextInt();
                    case "cooldown_minutes" -> cooldownMinutes = in.nextInt();
                    case "anonymize_players" -> anonymizePlayers = in.nextBoolean();
                    case "temperature" -> temperature = in.nextDouble();
                    case "public_chat" -> publicChat = in.nextBoolean();
                    case "public_chat_chance" -> publicChatChance = in.nextDouble();
                    case "private_chat_chance" -> privateChatChance = in.nextDouble();
                    case "system_instruction" -> systemInstruction = in.nextString();
                    case "dialogue_system_instruction" -> dialogueSystemInstruction = in.nextString();
                    case "pool_size_per_category" -> poolSizePerCategory = in.nextInt();
                    case "base_url" -> baseUrl = in.nextString();
                    default -> in.skipValue();
                }
            }
            in.endObject();

            return new GossipConfig(
                    enabled,
                    apiKey,
                    model,
                    refreshIntervalMinutes,
                    cooldownMinutes,
                    anonymizePlayers,
                    temperature,
                    publicChat,
                    publicChatChance,
                    privateChatChance,
                    systemInstruction,
                    dialogueSystemInstruction,
                    poolSizePerCategory,
                    baseUrl
            );
        }
    }
}
