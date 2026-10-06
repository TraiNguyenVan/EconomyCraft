package com.reazip.economycraft.gossip;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Empirical stress tests and adversarial edge cases for GossipConfig, GossipPool, and GossipCategory.
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
        @DisplayName("Clamping extreme negative and large refresh intervals")
        void testRefreshIntervalExtremeBounds() {
            GossipConfig negative = GSON.fromJson("{\"refresh_interval_minutes\": -999999}", GossipConfig.class);
            assertEquals(5, negative.refreshIntervalMinutes(), "Extreme negative interval must clamp to min 5");

            GossipConfig zero = GSON.fromJson("{\"refresh_interval_minutes\": 0}", GossipConfig.class);
            assertEquals(5, zero.refreshIntervalMinutes(), "Zero interval must clamp to min 5");

            GossipConfig justBelowMin = GSON.fromJson("{\"refresh_interval_minutes\": 4}", GossipConfig.class);
            assertEquals(5, justBelowMin.refreshIntervalMinutes(), "4 must clamp to 5");

            GossipConfig exactMin = GSON.fromJson("{\"refresh_interval_minutes\": 5}", GossipConfig.class);
            assertEquals(5, exactMin.refreshIntervalMinutes(), "5 must remain 5");

            GossipConfig exactMax = GSON.fromJson("{\"refresh_interval_minutes\": 1440}", GossipConfig.class);
            assertEquals(1440, exactMax.refreshIntervalMinutes(), "1440 must remain 1440");

            GossipConfig justAboveMax = GSON.fromJson("{\"refresh_interval_minutes\": 1441}", GossipConfig.class);
            assertEquals(1440, justAboveMax.refreshIntervalMinutes(), "1441 must clamp to 1440");

            GossipConfig huge = GSON.fromJson("{\"refresh_interval_minutes\": 2000000000}", GossipConfig.class);
            assertEquals(1440, huge.refreshIntervalMinutes(), "Huge interval must clamp to max 1440");
        }

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

            GossipConfig manualNull = new GossipConfig(true, "k", null, 20, 3, true, 0.85, false);
            assertEquals("gemini-3.8-flash", manualNull.model(), "Null model in constructor must default");
        }

        @Test
        @DisplayName("Partial JSON deserialization preserves defaults across various permutations")
        void testPartialJsonPermutations() {
            // Only enabled
            GossipConfig p1 = GSON.fromJson("{\"enabled\": false}", GossipConfig.class);
            assertFalse(p1.enabled());
            assertEquals(20, p1.refreshIntervalMinutes());
            assertEquals(3, p1.cooldownMinutes());
            assertEquals(0.85, p1.temperature(), 1e-9);
            assertEquals("gemini-3.8-flash", p1.model());

            // Only temperature
            GossipConfig p2 = GSON.fromJson("{\"temperature\": 1.5}", GossipConfig.class);
            assertTrue(p2.enabled());
            assertEquals(1.5, p2.temperature(), 1e-9);
            assertEquals(20, p2.refreshIntervalMinutes());

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
            assertEquals(20, config.refreshIntervalMinutes());
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
                GossipConfig c = GSON.fromJson("{\"refresh_interval_minutes\": null}", GossipConfig.class);
                assertEquals(20, c.refreshIntervalMinutes(), "Null refresh_interval_minutes should fallback to default");
            });

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
                GossipConfig c = GSON.fromJson("{\"public_chat\": null}", GossipConfig.class);
                assertFalse(c.publicChat(), "Null public_chat should fallback to default false");
            });
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 2. GossipPool Edge Cases, Concurrency, and Uniformity
    // ---------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("GossipPool Concurrency, Fallback, and Uniformity Tests")
    class PoolTests {

        @Test
        @DisplayName("Thread safety under concurrent reads across multiple threads")
        void testConcurrentReadsThreadSafety() throws InterruptedException, ExecutionException {
            int threadCount = 16;
            int iterationsPerThread = 10_000;

            Map<GossipCategory, List<String>> rumors = Map.of(
                    GossipCategory.FARMER, List.of("Wheat prices up", "Carrots rotting", "Beetroot boom"),
                    GossipCategory.BLACKSMITH, List.of("Iron scarcity", "Diamond tools hot"),
                    GossipCategory.GENERAL, List.of("Inflation is high", "Market bustling", "Taxes levied")
            );
            GossipPool pool = new GossipPool(rumors, Instant.now());

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            List<Callable<Integer>> tasks = new ArrayList<>();

            for (int t = 0; t < threadCount; t++) {
                tasks.add(() -> {
                    int nonNullCount = 0;
                    for (int i = 0; i < iterationsPerThread; i++) {
                        GossipCategory category = GossipCategory.values()[i % GossipCategory.values().length];
                        String rumor = pool.getRandomRumor(category);
                        if (rumor != null) {
                            nonNullCount++;
                        }
                    }
                    return nonNullCount;
                });
            }

            List<Future<Integer>> futures = executor.invokeAll(tasks);
            executor.shutdown();
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));

            for (Future<Integer> future : futures) {
                // Every category in the enum has either specific rumors or falls back to GENERAL rumors.
                // So all calls must return a non-null rumor!
                assertEquals(iterationsPerThread, future.get().intValue(),
                        "Concurrent read returned null or failed");
            }
        }

        @Test
        @DisplayName("Empty category falls back to GENERAL category")
        void testEmptyCategoryFallbackToGeneral() {
            List<String> generalRumors = List.of("General rumor A", "General rumor B");
            GossipPool pool = new GossipPool(
                    Map.of(
                            GossipCategory.GENERAL, generalRumors,
                            GossipCategory.FARMER, List.of("Farmer exclusive")
                    ),
                    Instant.now()
            );

            // CLERIC has no rumors, so it must fall back to GENERAL
            RandomSource random = RandomSource.create(42L);
            for (int i = 0; i < 50; i++) {
                String rumor = pool.getRandomRumor(GossipCategory.CLERIC, random);
                assertNotNull(rumor);
                assertTrue(generalRumors.contains(rumor), "Cleric must fall back to one of the general rumors");
            }

            // FARMER has exclusive rumor, must NOT fall back to general
            for (int i = 0; i < 50; i++) {
                String rumor = pool.getRandomRumor(GossipCategory.FARMER, random);
                assertEquals("Farmer exclusive", rumor, "Farmer must return its own rumor");
            }
        }

        @Test
        @DisplayName("Empty general category returns null when requested category is also empty")
        void testEmptyGeneralReturnsNull() {
            GossipPool poolWithoutGeneral = new GossipPool(
                    Map.of(GossipCategory.BLACKSMITH, List.of("Swords are sharp")),
                    Instant.now()
            );

            RandomSource random = RandomSource.create(123L);

            // CLERIC is empty and GENERAL is empty -> must return null
            String clericRumor = poolWithoutGeneral.getRandomRumor(GossipCategory.CLERIC, random);
            assertNull(clericRumor, "Empty category without general fallback must return null");

            // GENERAL is empty -> must return null
            String generalRumor = poolWithoutGeneral.getRandomRumor(GossipCategory.GENERAL, random);
            assertNull(generalRumor, "Empty general category must return null");

            // Empty pool completely returns null
            GossipPool emptyPool = GossipPool.empty();
            assertTrue(emptyPool.isEmpty());
            assertEquals(0, emptyPool.totalRumors());
            for (GossipCategory cat : GossipCategory.values()) {
                assertNull(emptyPool.getRandomRumor(cat, random));
                assertNull(emptyPool.getRandomRumor(cat));
            }
        }

        @Test
        @DisplayName("Random rumor distribution uniformity conforms to uniform distribution")
        void testRandomRumorDistributionUniformity() {
            List<String> rumors = List.of("Rumor_0", "Rumor_1", "Rumor_2", "Rumor_3", "Rumor_4");
            GossipPool pool = new GossipPool(Map.of(GossipCategory.NITWIT, rumors), Instant.now());

            int sampleCount = 20_000;
            int rumorCount = rumors.size();
            double expectedCount = (double) sampleCount / rumorCount; // 4000 each

            Map<String, AtomicInteger> counts = new HashMap<>();
            for (String r : rumors) {
                counts.put(r, new AtomicInteger(0));
            }

            RandomSource random = RandomSource.create(987654321L);
            for (int i = 0; i < sampleCount; i++) {
                String selected = pool.getRandomRumor(GossipCategory.NITWIT, random);
                assertNotNull(selected);
                counts.get(selected).incrementAndGet();
            }

            // Compute Chi-Square statistic: sum ( (observed - expected)^2 / expected )
            double chiSquare = 0.0;
            for (String r : rumors) {
                int observed = counts.get(r).get();
                double diff = observed - expectedCount;
                chiSquare += (diff * diff) / expectedCount;
            }

            // For degrees of freedom df = 4 (5 categories), critical value at alpha=0.001 is 18.47.
            // If chiSquare < 18.47, the distribution is statistically indistinguishable from uniform.
            assertTrue(chiSquare < 18.47,
                    String.format("Chi-square statistic %.2f exceeded critical value 18.47 (counts: %s)",
                            chiSquare, counts));
        }

        @Test
        @DisplayName("Immutability: modifying original collections does not affect GossipPool")
        void testImmutabilityAndDefensiveCopy() {
            Map<GossipCategory, List<String>> mutableMap = new HashMap<>();
            List<String> mutableList = new ArrayList<>();
            mutableList.add("Initial rumor");
            mutableMap.put(GossipCategory.FARMER, mutableList);

            GossipPool pool = new GossipPool(mutableMap, Instant.now());

            // Mutate original list and map
            mutableList.add("Sneaky mutated rumor");
            mutableMap.put(GossipCategory.BLACKSMITH, List.of("Sneaky blacksmith"));

            assertEquals(1, pool.totalRumors());
            assertEquals(1, pool.getRumors(GossipCategory.FARMER).size());
            assertEquals(0, pool.getRumors(GossipCategory.BLACKSMITH).size());

            // Returned lists and map should be unmodifiable
            assertThrows(UnsupportedOperationException.class, () ->
                    pool.rumorsByCategory().put(GossipCategory.NITWIT, List.of("Fail")));
            assertThrows(UnsupportedOperationException.class, () ->
                    pool.getRumors(GossipCategory.FARMER).add("Fail"));
        }

        @Test
        @DisplayName("GossipPool handles null parameters in constructor safely")
        void testNullHandlingInPoolConstructor() {
            GossipPool nullPool = new GossipPool(null, null);
            assertTrue(nullPool.isEmpty());
            assertEquals(0, nullPool.totalRumors());
            assertEquals(Instant.EPOCH, nullPool.generatedAt());
            assertNull(nullPool.getRandomRumor(GossipCategory.GENERAL));
        }

        @Test
        @DisplayName("GossipPool handles null category in getRandomRumor by falling back to GENERAL or returning null")
        void testNullCategoryInGetRandomRumor() {
            GossipPool poolWithGeneral = new GossipPool(
                    Map.of(GossipCategory.GENERAL, List.of("General rumor")),
                    Instant.now()
            );
            // Passing null category should not throw NullPointerException
            assertDoesNotThrow(() -> {
                String rumor = poolWithGeneral.getRandomRumor(null);
                assertEquals("General rumor", rumor, "Null category should fall back to general rumor");
            });

            GossipPool poolWithoutGeneral = new GossipPool(
                    Map.of(GossipCategory.FARMER, List.of("Farmer rumor")),
                    Instant.now()
            );
            assertDoesNotThrow(() -> {
                String rumor = poolWithoutGeneral.getRandomRumor(null);
                assertNull(rumor, "Null category without general rumor should return null");
            });
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 3. GossipCategory Enum Tests
    // ---------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("GossipCategory Enum & Mapping Tests")
    class CategoryTests {

        @Test
        @DisplayName("fromJsonKey correctly resolves standard keys, case variations, whitespace, and unknowns")
        void testFromJsonKeyResolutions() {
            assertEquals(GossipCategory.FARMER, GossipCategory.fromJsonKey("farmer"));
            assertEquals(GossipCategory.FARMER, GossipCategory.fromJsonKey("FARMER"));
            assertEquals(GossipCategory.FARMER, GossipCategory.fromJsonKey("  FaRmEr  "));

            assertEquals(GossipCategory.BLACKSMITH, GossipCategory.fromJsonKey("blacksmith"));
            assertEquals(GossipCategory.CLERIC, GossipCategory.fromJsonKey("cleric"));
            assertEquals(GossipCategory.LIBRARIAN, GossipCategory.fromJsonKey("librarian"));
            assertEquals(GossipCategory.NITWIT, GossipCategory.fromJsonKey("nitwit"));
            assertEquals(GossipCategory.GENERAL, GossipCategory.fromJsonKey("general"));

            // Null, empty, blank, or unknown keys must all fall back to GENERAL
            assertEquals(GossipCategory.GENERAL, GossipCategory.fromJsonKey(null));
            assertEquals(GossipCategory.GENERAL, GossipCategory.fromJsonKey(""));
            assertEquals(GossipCategory.GENERAL, GossipCategory.fromJsonKey("   "));
            assertEquals(GossipCategory.GENERAL, GossipCategory.fromJsonKey("unknown_profession"));
            assertEquals(GossipCategory.GENERAL, GossipCategory.fromJsonKey("villager_custom_mod"));
        }

        @Test
        @DisplayName("All GossipCategory values have unique, non-empty jsonKeys")
        void testEnumJsonKeysUniqueAndValid() {
            Set<String> seenKeys = new HashSet<>();
            for (GossipCategory cat : GossipCategory.values()) {
                assertNotNull(cat.jsonKey());
                assertFalse(cat.jsonKey().isBlank());
                assertTrue(seenKeys.add(cat.jsonKey()), "Duplicate jsonKey: " + cat.jsonKey());
                assertEquals(cat, GossipCategory.fromJsonKey(cat.jsonKey()),
                        "jsonKey must roundtrip to category");
            }
        }
    }
}
