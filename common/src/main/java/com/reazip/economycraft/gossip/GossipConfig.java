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
        @SerializedName("public_chat") boolean publicChat
) {
    private static final Logger LOGGER = LogUtils.getLogger();

    public static final boolean DEFAULT_ENABLED = true;
    public static final String DEFAULT_API_KEY = "";
    public static final String DEFAULT_MODEL = "gemini-3.8-flash";
    public static final int DEFAULT_REFRESH_INTERVAL_MINUTES = 20;
    public static final int DEFAULT_COOLDOWN_MINUTES = 3;
    public static final boolean DEFAULT_ANONYMIZE_PLAYERS = true;
    public static final double DEFAULT_TEMPERATURE = 0.85;
    public static final boolean DEFAULT_PUBLIC_CHAT = false;

    public static final int MIN_REFRESH_INTERVAL_MINUTES = 5;
    public static final int MAX_REFRESH_INTERVAL_MINUTES = 1440;
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
        refreshIntervalMinutes = clampInt("gemini_gossip.refresh_interval_minutes", refreshIntervalMinutes,
                MIN_REFRESH_INTERVAL_MINUTES, MAX_REFRESH_INTERVAL_MINUTES);
        cooldownMinutes = clampInt("gemini_gossip.cooldown_minutes", cooldownMinutes,
                MIN_COOLDOWN_MINUTES, MAX_COOLDOWN_MINUTES);
        temperature = clampDouble("gemini_gossip.temperature", temperature,
                MIN_TEMPERATURE, MAX_TEMPERATURE);
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
                DEFAULT_PUBLIC_CHAT
        );
    }

    /**
     * Resolves the active API key, falling back to the {@code GEMINI_API_KEY} environment variable
     * if the configured key is blank.
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
                publicChat
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

            in.beginObject();
            while (in.hasNext()) {
                String name = in.nextName();
                switch (name) {
                    case "enabled" -> enabled = in.nextBoolean();
                    case "api_key" -> apiKey = in.nextString();
                    case "model" -> model = in.nextString();
                    case "refresh_interval_minutes" -> refreshIntervalMinutes = in.nextInt();
                    case "cooldown_minutes" -> cooldownMinutes = in.nextInt();
                    case "anonymize_players" -> anonymizePlayers = in.nextBoolean();
                    case "temperature" -> temperature = in.nextDouble();
                    case "public_chat" -> publicChat = in.nextBoolean();
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
                    publicChat
            );
        }
    }
}
