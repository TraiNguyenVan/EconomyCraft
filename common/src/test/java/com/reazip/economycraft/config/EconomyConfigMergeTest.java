package com.reazip.economycraft.config;

import com.reazip.economycraft.EconomyConfig;
import com.reazip.economycraft.util.EconomyPaths;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Coverage for the recursive default merge (R7, P2-T7).
 *
 * <p>The merge was written when every config key was a flat scalar, and had therefore never been run against
 * a nested object. Phase 2 adds the first ones — {@code factions} and {@code professions}, each with records
 * inside them — so upgrading a server that already has a {@code config.json} now depends on three things
 * working: a whole missing section is added, a key is added <em>inside</em> a section the user already has,
 * and nothing the user typed is overwritten.
 *
 * <p>Written and run <strong>before</strong> the first nested key existed, and it asserts only the JSON that
 * was written back — never a typed field or a specific default — so it pins the merge mechanism itself and
 * stays valid whatever the sections later contain. Clamping of the new keys is covered separately in
 * {@code TagConfigClampTest}, which cannot exist until they do.
 */
class EconomyConfigMergeTest {

    @TempDir Path world;

    private Path configFile;

    private MinecraftServer server() {
        MinecraftServer server = mock(MinecraftServer.class);
        when(server.isDedicatedServer()).thenReturn(false);
        when(server.getWorldPath(LevelResource.ROOT)).thenReturn(world);
        return server;
    }

    private Path givenUserConfig(String json) {
        try {
            configFile = EconomyPaths.configDir(server()).resolve("config.json");
            Files.writeString(configFile, json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        return configFile;
    }

    private JsonObject loadAndReadBack() {
        EconomyConfig.load(server());
        try {
            return readObject(configFile);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static JsonObject readObject(Path file) throws IOException {
        JsonElement parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
        return parsed.getAsJsonObject();
    }

    // --- missing file ---

    @Test
    void missingFileIsCreatedFromTheBundledDefault() {
        Path created = EconomyPaths.configDir(server()).resolve("config.json");
        assertFalse(Files.exists(created));

        EconomyConfig.load(server());

        assertTrue(Files.isRegularFile(created), "config.json must be created from the bundled default");
        assertNotNull(EconomyConfig.get());
        assertTrue(EconomyConfig.get().startingBalance > 0, "the bundled default must parse into a usable config");
    }

    // --- a new nested section added to an existing user file ---

    @Test
    void missingNestedSectionIsAddedWithoutDisturbingUserValues() {
        givenUserConfig("""
                {
                  "startingBalance": 4321,
                  "taxRate": 0.2,
                  "dynamic_prices_enabled": true
                }
                """);

        JsonObject merged = loadAndReadBack();

        assertEquals(4321, merged.get("startingBalance").getAsInt(), "a typed value must survive the merge");
        assertEquals(0.2, merged.get("taxRate").getAsDouble(), 1.0e-9);
        assertTrue(merged.get("dynamic_prices_enabled").getAsBoolean());
        assertEquals(4321, EconomyConfig.get().startingBalance, "the parsed config must keep the user's value");

        // The sections Phase 2 adds are absent from this hand-written file, so the merge must add them whole,
        // including the records nested inside them.
        for (String section : new String[]{"factions", "professions"}) {
            assertTrue(merged.has(section), section + " must be added to an existing user config");
            assertTrue(merged.get(section).isJsonObject(), section + " must be added as a nested object");
        }
        assertTrue(merged.getAsJsonObject("factions").has("communism"),
                "the nested faction records must be added, not just the top-level section");
        assertTrue(merged.getAsJsonObject("professions").getAsJsonObject("builder").has("level_up_count"));
    }

    @Test
    void missingKeyInsideAnExistingNestedSectionIsAdded() {
        givenUserConfig("""
                {
                  "startingBalance": 2500,
                  "factions": {
                    "enabled": false,
                    "communism": {
                      "party_fee": 42
                    }
                  }
                }
                """);

        JsonObject merged = loadAndReadBack();

        JsonObject factions = merged.getAsJsonObject("factions");
        JsonObject communism = factions.getAsJsonObject("communism");

        assertFalse(factions.get("enabled").getAsBoolean(), "a value inside the section must survive the merge");
        assertEquals(42, communism.get("party_fee").getAsLong());
        assertTrue(communism.has("income_tax_tier1_rate"), "a sibling key must be added inside the section");
        assertTrue(communism.has("icon"), "the colour/icon keys must be added inside the section");
        assertTrue(factions.has("capitalism"), "a sibling faction record must be added");
        assertTrue(factions.has("selection_lockout_hours"), "a sibling section key must be added");
    }

    @Test
    void addedListKeysAreCopiedFromTheBundledDefault() {
        givenUserConfig("{\"professions\": {\"builder\": {}}}");

        JsonObject merged = loadAndReadBack();
        JsonObject builder = merged.getAsJsonObject("professions").getAsJsonObject("builder");

        assertTrue(builder.has("building_blocks"), "an array-valued key must be added too");
        assertTrue(builder.get("building_blocks").isJsonArray());
        assertFalse(builder.getAsJsonArray("building_blocks").isEmpty(), "the added array must be the real default");
    }

    @Test
    void userListsAreNeverMergedElementByElement() {
        givenUserConfig("""
                {
                  "professions": {
                    "builder": {
                      "building_blocks": ["minecraft:dirt"]
                    }
                  }
                }
                """);

        JsonObject merged = loadAndReadBack();
        var blocks = merged.getAsJsonObject("professions").getAsJsonObject("builder")
                .getAsJsonArray("building_blocks");

        assertEquals(1, blocks.size(), "an admin who trimmed a list must not have it refilled from defaults");
        assertEquals("minecraft:dirt", blocks.get(0).getAsString());
    }

    // --- idempotence ---

    @Test
    void loadingTwiceAddsNothingAndLeavesTheFileStable() throws IOException {
        givenUserConfig("{\"startingBalance\": 777}");
        JsonObject first = loadAndReadBack();
        String afterFirstLoad = Files.readString(configFile, StandardCharsets.UTF_8);

        JsonObject second = loadAndReadBack();
        String afterSecondLoad = Files.readString(configFile, StandardCharsets.UTF_8);

        assertEquals(first, second, "the merge must be idempotent");
        assertEquals(afterFirstLoad, afterSecondLoad, "a complete config must not be rewritten on load");
        assertEquals(777, EconomyConfig.get().startingBalance);
    }

    @Test
    void blankFileIsTreatedAsAnEmptyConfig() {
        givenUserConfig("   ");
        JsonObject merged = loadAndReadBack();

        assertTrue(merged.has("startingBalance"));
        assertTrue(merged.has("factions"));
        assertTrue(merged.has("professions"));
    }

    @Test
    void nonObjectRootIsNeitherMergedNorRewritten() throws IOException {
        Path file = givenUserConfig("[1, 2, 3]");

        // Documented pre-existing behaviour, pinned so a later refactor cannot quietly change it: the merge
        // gives up on a root it cannot merge into, and the parse that follows then rejects the file. Both
        // halves matter — rewriting here would destroy a hand-broken file an admin could still fix by hand.
        assertThrows(IllegalStateException.class, () -> EconomyConfig.load(server()));
        assertEquals("[1, 2, 3]", Files.readString(file, StandardCharsets.UTF_8),
                "a config that cannot be parsed must be left exactly as the admin wrote it");
    }
}