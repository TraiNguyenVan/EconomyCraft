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
 * Configuration for the LLM-powered villager dialogue system.
 *
 * <p>Maps to the {@code "gemini_gossip"} section in {@code config.json}. Villager speech is private
 * only; there is no server-wide broadcast option.
 */
@JsonAdapter(GossipConfig.Adapter.class)
public record GossipConfig(
        @SerializedName("enabled") boolean enabled,
        @SerializedName("api_key") String apiKey,
        @SerializedName("model") String model,
        @SerializedName("cooldown_minutes") int cooldownMinutes,
        @SerializedName("anonymize_players") boolean anonymizePlayers,
        @SerializedName("temperature") double temperature,
        @SerializedName("private_chat_chance") double privateChatChance,
        @SerializedName("dialogue_system_instruction") String dialogueSystemInstruction,
        @SerializedName("base_url") String baseUrl
) {
    private static final Logger LOGGER = LogUtils.getLogger();

    public static final boolean DEFAULT_ENABLED = true;
    public static final String DEFAULT_API_KEY = "";
    public static final String DEFAULT_MODEL = "gemini-3.8-flash";
    public static final String DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com";
    public static final int DEFAULT_COOLDOWN_MINUTES = 3;
    public static final boolean DEFAULT_ANONYMIZE_PLAYERS = true;
    public static final double DEFAULT_TEMPERATURE = 0.85;
    public static final double DEFAULT_PRIVATE_CHAT_CHANCE = 0.5;
    public static final double MIN_CHANCE = 0.0;
    public static final double MAX_CHANCE = 1.0;
    public static final String DEFAULT_DIALOGUE_SYSTEM_INSTRUCTION =
            "Dialogue Instructions:\n" +
            "1. Keep it concise (12 to 25 words). Avoid overly verbose prose, but don't be so brief that you omit item details.\n" +
            "2. Speak in exactly 1 natural, conversational sentence matching your personality, quirk, and relationship with this player.\n" +
            "3. MANDATORY SALES PITCH & ITEM AWARENESS: Greet the customer and pitch, mention, or offer a specific item or deal from your stall's current trade inventory (for example: an enchanted book by its exact enchantment name like 'Fortune III' or 'Efficiency V', tools, weapons, armor, or goods you sell). If they have traded with you before, you may also reference their past purchase.\n" +
            "4. Item Specificity: Always refer to your actual stock items by name. Do not speak in vague generalities like 'my stock' or 'something'—name a real item you have for sale!\n" +
            "5. Villagers have quirky mannerisms: occasionally mutter or hum ('Hmm...', 'Huh?', 'Haah...'), but vary how you speak and DO NOT start every line with 'Hrmm...'.\n" +
            "6. Respond strictly with valid JSON with fields:\n" +
            "   {\n" +
            "     \"dialogue\": \"<your concise line>\",\n" +
            "     \"sentiment_delta\": <-2 to 5 integer>\n" +
            "   }";

    public static final int MIN_COOLDOWN_MINUTES = 1;
    public static final int MAX_COOLDOWN_MINUTES = 60;
    public static final double MIN_TEMPERATURE = 0.0;
    public static final double MAX_TEMPERATURE = 2.0;

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
        cooldownMinutes = clampInt("gemini_gossip.cooldown_minutes", cooldownMinutes,
                MIN_COOLDOWN_MINUTES, MAX_COOLDOWN_MINUTES);
        temperature = clampDouble("gemini_gossip.temperature", temperature,
                MIN_TEMPERATURE, MAX_TEMPERATURE);
        privateChatChance = clampDouble("gemini_gossip.private_chat_chance", privateChatChance,
                MIN_CHANCE, MAX_CHANCE);
        if (dialogueSystemInstruction == null || dialogueSystemInstruction.isBlank()) {
            dialogueSystemInstruction = DEFAULT_DIALOGUE_SYSTEM_INSTRUCTION;
        } else {
            dialogueSystemInstruction = dialogueSystemInstruction.trim();
        }
    }

    public static GossipConfig createDefault() {
        return new GossipConfig(
                DEFAULT_ENABLED,
                DEFAULT_API_KEY,
                DEFAULT_MODEL,
                DEFAULT_COOLDOWN_MINUTES,
                DEFAULT_ANONYMIZE_PLAYERS,
                DEFAULT_TEMPERATURE,
                DEFAULT_PRIVATE_CHAT_CHANCE,
                DEFAULT_DIALOGUE_SYSTEM_INSTRUCTION,
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
                cooldownMinutes,
                anonymizePlayers,
                temperature,
                privateChatChance,
                dialogueSystemInstruction,
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
            out.name("cooldown_minutes").value(value.cooldownMinutes());
            out.name("anonymize_players").value(value.anonymizePlayers());
            out.name("temperature").value(value.temperature());
            out.name("private_chat_chance").value(value.privateChatChance());
            out.name("dialogue_system_instruction").value(value.dialogueSystemInstruction());
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
            int cooldownMinutes = DEFAULT_COOLDOWN_MINUTES;
            boolean anonymizePlayers = DEFAULT_ANONYMIZE_PLAYERS;
            double temperature = DEFAULT_TEMPERATURE;
            double privateChatChance = DEFAULT_PRIVATE_CHAT_CHANCE;
            String dialogueSystemInstruction = DEFAULT_DIALOGUE_SYSTEM_INSTRUCTION;
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
                    case "cooldown_minutes" -> cooldownMinutes = in.nextInt();
                    case "anonymize_players" -> anonymizePlayers = in.nextBoolean();
                    case "temperature" -> temperature = in.nextDouble();
                    case "private_chat_chance" -> privateChatChance = in.nextDouble();
                    case "dialogue_system_instruction" -> dialogueSystemInstruction = in.nextString();
                    case "base_url" -> baseUrl = in.nextString();
                    default -> in.skipValue();
                }
            }
            in.endObject();

            return new GossipConfig(
                    enabled,
                    apiKey,
                    model,
                    cooldownMinutes,
                    anonymizePlayers,
                    temperature,
                    privateChatChance,
                        dialogueSystemInstruction,
                        baseUrl
            );
        }
    }
}
