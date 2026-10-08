package com.reazip.economycraft.gossip;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Empirical stress tests and adversarial edge cases for GossipConfig.
 */
class GossipEmpiricalStressTest {

    private static final Gson GSON = new GsonBuilder().create();

    // ---------------------------------------------------------------------------------------------
    // 1. GossipConfig Edge Cases & Clamping
    // ---------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("GossipConfig Adversarial & Clamping Tests")
    class ConfigTests {
        @Test
        @DisplayName("Clamping extreme negative and large cooldown minutes")
        void testCooldownMinutesExtremeBounds() {
            GossipConfig negative = GSON.fromJson("{\"cooldown_minutes\": -100}", GossipConfig.class);
            assertEquals(1, negative.cooldownMinutes(), "Negative cooldown must clamp to min 1");

            GossipConfig zero = GSON.fromJson("{\"cooldown_minutes\": 0}", GossipConfig.class);
            assertEquals(1, zero.cooldownMinutes(), "Zero cooldown must clamp to min 1");

            GossipConfig exactMin = GSON.fromJson("{\"cooldown_minutes\": 1}", GossipConfig.class);
            assertEquals(1, exactMin.cooldownMinutes(), "1 must remain 1");

            GossipConfig exactMax = GSON.fromJson("{\"cooldown_minutes\": 60}", GossipConfig.class);
            assertEquals(60, exactMax.cooldownMinutes(), "60 must remain 60");

            GossipConfig justAboveMax = GSON.fromJson("{\"cooldown_minutes\": 61}", GossipConfig.class);
            assertEquals(60, justAboveMax.cooldownMinutes(), "61 must clamp to 60");

            GossipConfig huge = GSON.fromJson("{\"cooldown_minutes\": 9999999}", GossipConfig.class);
            assertEquals(60, huge.cooldownMinutes(), "Huge cooldown must clamp to max 60");
        }

        @Test
        @DisplayName("Clamping extreme temperature bounds including negative, zero, > 2.0, and huge values")
        void testTemperatureExtremeBounds() {
            GossipConfig negative = GSON.fromJson("{\"temperature\": -50.0}", GossipConfig.class);
            assertEquals(0.0, negative.temperature(), 1e-9, "Negative temperature must clamp to 0.0");

            GossipConfig negativeSmall = GSON.fromJson("{\"temperature\": -0.0001}", GossipConfig.class);
            assertEquals(0.0, negativeSmall.temperature(), 1e-9, "-0.0001 must clamp to 0.0");

            GossipConfig exactZero = GSON.fromJson("{\"temperature\": 0.0}", GossipConfig.class);
            assertEquals(0.0, exactZero.temperature(), 1e-9, "0.0 must remain 0.0");

            GossipConfig exactTwo = GSON.fromJson("{\"temperature\": 2.0}", GossipConfig.class);
            assertEquals(2.0, exactTwo.temperature(), 1e-9, "2.0 must remain 2.0");

            GossipConfig justAboveTwo = GSON.fromJson("{\"temperature\": 2.0001}", GossipConfig.class);
            assertEquals(2.0, justAboveTwo.temperature(), 1e-9, "2.0001 must clamp to 2.0");

            GossipConfig hugeTemp = GSON.fromJson("{\"temperature\": 999999.9}", GossipConfig.class);
            assertEquals(2.0, hugeTemp.temperature(), 1e-9, "Huge temperature must clamp to 2.0");
        }

        @Test
        @DisplayName("Model normalization with empty, whitespace, and custom values")
        void testModelNormalization() {
            GossipConfig empty = GSON.fromJson("{\"model\": \"\"}", GossipConfig.class);
            assertEquals("gemini-3.8-flash", empty.model(), "Empty string model must default to gemini-3.8-flash");

            GossipConfig spaces = GSON.fromJson("{\"model\": \"   \\t   \"}", GossipConfig.class);
            assertEquals("gemini-3.8-flash", spaces.model(), "Whitespace model must default to gemini-3.8-flash");

            GossipConfig customPadded = GSON.fromJson("{\"model\": \"  gemini-2.5-flash  \"}", GossipConfig.class);
            assertEquals("gemini-2.5-flash", customPadded.model(), "Custom model must be trimmed");

            GossipConfig manualNull = new GossipConfig(true, "k", null, 3, true, 0.85, 0.5,
                    GossipConfig.DEFAULT_DIALOGUE_SYSTEM_INSTRUCTION, GossipConfig.DEFAULT_BASE_URL);
            assertEquals("gemini-3.8-flash", manualNull.model(), "Null model in constructor must default");
        }

        @Test
        @DisplayName("Partial JSON deserialization preserves defaults across various permutations")
        void testPartialJsonPermutations() {
            // Only enabled
            GossipConfig p1 = GSON.fromJson("{\"enabled\": false}", GossipConfig.class);
            assertFalse(p1.enabled());
            assertEquals(3, p1.cooldownMinutes());
            assertEquals(0.85, p1.temperature(), 1e-9);
            assertEquals("gemini-3.8-flash", p1.model());

            // Only temperature
            GossipConfig p2 = GSON.fromJson("{\"temperature\": 1.5}", GossipConfig.class);
            assertTrue(p2.enabled());
            assertEquals(1.5, p2.temperature(), 1e-9);

            // Empty JSON
            GossipConfig p3 = GSON.fromJson("{}", GossipConfig.class);
            assertEquals(GossipConfig.createDefault(), p3);
        }

        @Test
        @DisplayName("Unknown extra JSON keys (scalars, arrays, objects) are ignored safely")
        void testUnknownExtraJsonKeys() {
            String json = """
                    {
                      "enabled": true,
                      "unknown_scalar": 42,
                      "unknown_string": "hello",
                      "unknown_array": [1, 2, 3],
                      "unknown_nested_obj": {
                        "nested_a": true,
                        "nested_b": [ "x", "y" ]
                      },
                      "model": "gemini-1.5-flash"
                    }
                    """;
            GossipConfig config = GSON.fromJson(json, GossipConfig.class);
            assertTrue(config.enabled());
            assertEquals("gemini-1.5-flash", config.model());
        }

        @Test
        @DisplayName("Behavior when JSON contains explicit null string fields")
        void testExplicitNullStringFieldsInJson() {
            // Null api_key
            assertDoesNotThrow(() -> {
                GossipConfig c = GSON.fromJson("{\"api_key\": null}", GossipConfig.class);
                assertEquals("", c.apiKey(), "Null api_key in JSON should fallback to default empty string");
            });

            // Null model
            assertDoesNotThrow(() -> {
                GossipConfig c = GSON.fromJson("{\"model\": null}", GossipConfig.class);
                assertEquals("gemini-3.8-flash", c.model(), "Null model in JSON should fallback to default model");
            });
        }

        @Test
        @DisplayName("Behavior when JSON contains explicit null numeric and boolean fields")
        void testExplicitNullNumericAndBooleanFieldsInJson() {
            assertDoesNotThrow(() -> {
                GossipConfig c = GSON.fromJson("{\"cooldown_minutes\": null}", GossipConfig.class);
                assertEquals(3, c.cooldownMinutes(), "Null cooldown_minutes should fallback to default");
            });

            assertDoesNotThrow(() -> {
                GossipConfig c = GSON.fromJson("{\"temperature\": null}", GossipConfig.class);
                assertEquals(0.85, c.temperature(), 1e-6, "Null temperature should fallback to default");
            });

            assertDoesNotThrow(() -> {
                GossipConfig c = GSON.fromJson("{\"enabled\": null}", GossipConfig.class);
                assertTrue(c.enabled(), "Null enabled should fallback to default true");
            });

            assertDoesNotThrow(() -> {
                GossipConfig c = GSON.fromJson("{\"anonymize_players\": null}", GossipConfig.class);
                assertTrue(c.anonymizePlayers(), "Null anonymize_players should fallback to default true");
            });

            assertDoesNotThrow(() -> {
                GossipConfig c = GSON.fromJson("{\"private_chat_chance\": null}", GossipConfig.class);
                assertEquals(0.5, c.privateChatChance(), 1e-6, "Null private_chat_chance should fallback to default 0.5");
            });
        }

        @Test
        @DisplayName("Removed public-gossip keys in a pre-existing config are ignored without failing the parse")
        void testStalePublicGossipKeysAreIgnored() {
            String json = """
                    {
                      "enabled": true,
                      "refresh_interval_minutes": 20,
                      "public_chat": true,
                      "public_chat_chance": 0.25,
                      "system_instruction": "You are a witty, satirical economic gossip for Minecraft villagers.",
                      "pool_size_per_category": 3,
                      "cooldown_minutes": 3
                    }
                    """;
            GossipConfig config = assertDoesNotThrow(() -> GSON.fromJson(json, GossipConfig.class));
            assertTrue(config.enabled());
            assertEquals(3, config.cooldownMinutes());
        }
    }

}