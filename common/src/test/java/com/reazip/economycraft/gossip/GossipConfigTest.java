package com.reazip.economycraft.gossip;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link GossipConfig} deserialization, defaults, clamping, and API key resolution.
 */
class GossipConfigTest {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    @Test
    @DisplayName("Default factory creates values exactly matching contract specification")
    void defaultValuesMatchSpecification() {
        GossipConfig config = GossipConfig.createDefault();

        assertTrue(config.enabled(), "enabled must default to true");
        assertEquals("", config.apiKey(), "apiKey must default to empty string");
        assertEquals("gemini-3.8-flash", config.model(), "model must default to gemini-3.8-flash");
        assertEquals(20, config.refreshIntervalMinutes(), "refreshIntervalMinutes must default to 20");
        assertEquals(3, config.cooldownMinutes(), "cooldownMinutes must default to 3");
        assertTrue(config.anonymizePlayers(), "anonymizePlayers must default to true");
        assertEquals(0.85, config.temperature(), 1e-6, "temperature must default to 0.85");
        assertFalse(config.publicChat(), "publicChat must default to false");
    }

    @Test
    @DisplayName("Deserializing an empty JSON object falls back to contract defaults")
    void emptyJsonDeserializationFallsBackToDefaults() {
        GossipConfig config = GSON.fromJson("{}", GossipConfig.class);

        assertEquals(GossipConfig.createDefault(), config,
                "deserializing empty JSON must produce the exact default configuration");
    }

    @Test
    @DisplayName("Deserializing complete JSON properly sets all custom fields")
    void fullCustomJsonDeserialization() {
        String json = """
                {
                  "enabled": false,
                  "api_key": "AIzaSyCustomKey123",
                  "model": "gemini-1.5-pro",
                  "refresh_interval_minutes": 60,
                  "cooldown_minutes": 10,
                  "anonymize_players": false,
                  "temperature": 0.4,
                  "public_chat": true
                }
                """;

        GossipConfig config = GSON.fromJson(json, GossipConfig.class);

        assertFalse(config.enabled());
        assertEquals("AIzaSyCustomKey123", config.apiKey());
        assertEquals("gemini-1.5-pro", config.model());
        assertEquals(60, config.refreshIntervalMinutes());
        assertEquals(10, config.cooldownMinutes());
        assertFalse(config.anonymizePlayers());
        assertEquals(0.4, config.temperature(), 1e-6);
        assertTrue(config.publicChat());
    }

    @Test
    @DisplayName("Partial JSON deserialization preserves defaults for omitted fields")
    void partialJsonDeserializationPreservesDefaults() {
        String json = """
                {
                  "api_key": "my-secret-key",
                  "refresh_interval_minutes": 45
                }
                """;

        GossipConfig config = GSON.fromJson(json, GossipConfig.class);

        assertTrue(config.enabled(), "omitted enabled must remain true");
        assertEquals("my-secret-key", config.apiKey());
        assertEquals("gemini-3.8-flash", config.model(), "omitted model must remain default");
        assertEquals(45, config.refreshIntervalMinutes());
        assertEquals(3, config.cooldownMinutes(), "omitted cooldown must remain default");
        assertTrue(config.anonymizePlayers(), "omitted anonymizePlayers must remain true");
        assertEquals(0.85, config.temperature(), 1e-6);
        assertFalse(config.publicChat());
    }

    @Test
    @DisplayName("refresh_interval_minutes clamps to bounds [5, 1440]")
    void clampingRefreshIntervalMinutes() {
        GossipConfig belowMin = GSON.fromJson("{\"refresh_interval_minutes\": 2}", GossipConfig.class);
        assertEquals(5, belowMin.refreshIntervalMinutes(), "values below 5 must clamp to 5");

        GossipConfig negative = GSON.fromJson("{\"refresh_interval_minutes\": -10}", GossipConfig.class);
        assertEquals(5, negative.refreshIntervalMinutes(), "negative values must clamp to 5");

        GossipConfig exactMin = GSON.fromJson("{\"refresh_interval_minutes\": 5}", GossipConfig.class);
        assertEquals(5, exactMin.refreshIntervalMinutes());

        GossipConfig exactMax = GSON.fromJson("{\"refresh_interval_minutes\": 1440}", GossipConfig.class);
        assertEquals(1440, exactMax.refreshIntervalMinutes());

        GossipConfig aboveMax = GSON.fromJson("{\"refresh_interval_minutes\": 3000}", GossipConfig.class);
        assertEquals(1440, aboveMax.refreshIntervalMinutes(), "values above 1440 must clamp to 1440");
    }

    @Test
    @DisplayName("cooldown_minutes clamps to bounds [1, 60]")
    void clampingCooldownMinutes() {
        GossipConfig belowMin = GSON.fromJson("{\"cooldown_minutes\": 0}", GossipConfig.class);
        assertEquals(1, belowMin.cooldownMinutes(), "values below 1 must clamp to 1");

        GossipConfig negative = GSON.fromJson("{\"cooldown_minutes\": -5}", GossipConfig.class);
        assertEquals(1, negative.cooldownMinutes(), "negative values must clamp to 1");

        GossipConfig exactMin = GSON.fromJson("{\"cooldown_minutes\": 1}", GossipConfig.class);
        assertEquals(1, exactMin.cooldownMinutes());

        GossipConfig exactMax = GSON.fromJson("{\"cooldown_minutes\": 60}", GossipConfig.class);
        assertEquals(60, exactMax.cooldownMinutes());

        GossipConfig aboveMax = GSON.fromJson("{\"cooldown_minutes\": 120}", GossipConfig.class);
        assertEquals(60, aboveMax.cooldownMinutes(), "values above 60 must clamp to 60");
    }

    @Test
    @DisplayName("temperature clamps to bounds [0.0, 2.0]")
    void clampingTemperature() {
        GossipConfig belowMin = GSON.fromJson("{\"temperature\": -0.5}", GossipConfig.class);
        assertEquals(0.0, belowMin.temperature(), 1e-6, "temperatures below 0.0 must clamp to 0.0");

        GossipConfig exactZero = GSON.fromJson("{\"temperature\": 0.0}", GossipConfig.class);
        assertEquals(0.0, exactZero.temperature(), 1e-6);

        GossipConfig exactTwo = GSON.fromJson("{\"temperature\": 2.0}", GossipConfig.class);
        assertEquals(2.0, exactTwo.temperature(), 1e-6);

        GossipConfig aboveMax = GSON.fromJson("{\"temperature\": 4.5}", GossipConfig.class);
        assertEquals(2.0, aboveMax.temperature(), 1e-6, "temperatures above 2.0 must clamp to 2.0");
    }

    @Test
    @DisplayName("null or blank model falls back to gemini-3.8-flash")
    void nullOrBlankModelFallsBackToDefault() {
        GossipConfig emptyModel = GSON.fromJson("{\"model\": \"\"}", GossipConfig.class);
        assertEquals("gemini-3.8-flash", emptyModel.model());

        GossipConfig blankModel = GSON.fromJson("{\"model\": \"   \"}", GossipConfig.class);
        assertEquals("gemini-3.8-flash", blankModel.model());

        GossipConfig manualNull = new GossipConfig(true, "", null, 20, 3, true, 0.85, false);
        assertEquals("gemini-3.8-flash", manualNull.model());
    }

    @Test
    @DisplayName("apiKey trims whitespace and normalizes null to empty string")
    void apiKeyTrimmingAndNullHandling() {
        GossipConfig withSpaces = GSON.fromJson("{\"api_key\": \"  padded-key  \"}", GossipConfig.class);
        assertEquals("padded-key", withSpaces.apiKey());

        GossipConfig manualNull = new GossipConfig(true, null, "gemini-3.8-flash", 20, 3, true, 0.85, false);
        assertEquals("", manualNull.apiKey());
    }

    @Test
    @DisplayName("getEffectiveApiKey prioritizes configured apiKey over environment variable")
    void getEffectiveApiKeyPrioritizesConfiguredKey() {
        GossipConfig config = new GossipConfig(true, "configured-key", "gemini-3.8-flash", 20, 3, true, 0.85, false);
        Map<String, String> env = Map.of("GEMINI_API_KEY", "env-key");

        assertEquals("configured-key", config.getEffectiveApiKey(env::get));
    }

    @Test
    @DisplayName("getEffectiveApiKey falls back to GEMINI_API_KEY env var when apiKey is empty")
    void getEffectiveApiKeyFallsBackToEnvironment() {
        GossipConfig config = GossipConfig.createDefault();
        Map<String, String> env = Map.of("GEMINI_API_KEY", "secret-env-key");

        assertEquals("secret-env-key", config.getEffectiveApiKey(env::get));
    }

    @Test
    @DisplayName("getEffectiveApiKey returns empty string when neither config nor env var is provided")
    void getEffectiveApiKeyReturnsEmptyWhenUnset() {
        GossipConfig config = GossipConfig.createDefault();

        assertEquals("", config.getEffectiveApiKey(k -> null));
        assertEquals("", config.getEffectiveApiKey(k -> "   "));
    }

    @Test
    @DisplayName("Serialization and deserialization roundtrip preserves all fields identically")
    void serializationRoundTrip() {
        GossipConfig original = new GossipConfig(
                false,
                "roundtrip-key",
                "gemini-1.5-pro",
                40,
                8,
                false,
                1.2,
                true
        );

        String json = GSON.toJson(original);
        GossipConfig deserialized = GSON.fromJson(json, GossipConfig.class);

        assertEquals(original, deserialized);
        assertTrue(json.contains("\"api_key\""));
        assertTrue(json.contains("\"refresh_interval_minutes\""));
        assertTrue(json.contains("\"cooldown_minutes\""));
        assertTrue(json.contains("\"anonymize_players\""));
        assertTrue(json.contains("\"public_chat\""));
    }

    @Test
    @DisplayName("clamp is idempotent")
    void clampIsIdempotent() {
        GossipConfig config = new GossipConfig(
                true,
                "some-key",
                "gemini-3.8-flash",
                9999, // out of range
                9999, // out of range
                true,
                9.9,  // out of range
                false
        );

        assertEquals(1440, config.refreshIntervalMinutes());
        assertEquals(60, config.cooldownMinutes());
        assertEquals(2.0, config.temperature(), 1e-6);

        GossipConfig secondClamp = config.clamped();
        assertEquals(config, secondClamp, "clamping twice must yield identical values");
    }

    @Test
    @DisplayName("Unknown JSON keys are ignored safely without errors")
    void unknownKeysIgnored() {
        String json = """
                {
                  "unknown_random_key": "ignored_value",
                  "another_unexpected_object": { "nested": 123 },
                  "enabled": false
                }
                """;

        GossipConfig config = GSON.fromJson(json, GossipConfig.class);
        assertFalse(config.enabled());
        assertEquals("gemini-3.8-flash", config.model());
    }
}
