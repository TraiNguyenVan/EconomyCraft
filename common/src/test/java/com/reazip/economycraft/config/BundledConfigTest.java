package com.reazip.economycraft.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.annotations.SerializedName;
import com.reazip.economycraft.EconomyConfig;
import com.reazip.economycraft.util.EconomyPaths;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Keeps the bundled {@code config.json} and {@code EconomyConfig} describing the same thing.
 *
 * <p>Phase 2 added the first nested config records, and the failure mode is silent in the worst way: adding a
 * field to {@code EconomyConfig} and forgetting the bundled default breaks nothing. Gson ignores a key that no
 * field matches, and leaves a field the JSON does not mention at its Java initialiser — which for a primitive
 * is a plausible zero and for a nested record is {@code null}. The server starts happily, and the key simply
 * does not exist for the admin to edit. The merge tests cannot catch it, because they deliberately assert only
 * the merge mechanism rather than any typed field.
 *
 * <p>So this test asserts the inverse of that, in both directions at once:
 * <ul>
 *   <li>every key in the shipped file maps to a declared field — catches a typo, or a key left behind by a
 *       renamed field;</li>
 *   <li>every declared field has a key — catches a forgotten default. This is the dangerous one: the field
 *       loads at whatever its Java initialiser says, the server runs, and the admin has no key to edit.</li>
 *   <li>the value that loads equals the value in the file — catches a shipped default its own clamp would
 *       rewrite, which is a wrong number in the file that no warning will ever mention.</li>
 * </ul>
 *
 * <p>Note what this deliberately does <em>not</em> assert: that a shipped value equals the field's Java
 * initialiser. The file is the source of truth at runtime, so an initialiser is only a fallback for a key the
 * file lacks — which is exactly the case the second bullet is about.
 */
class BundledConfigTest {

    @TempDir Path world;

    private MinecraftServer server() {
        MinecraftServer server = mock(MinecraftServer.class);
        when(server.isDedicatedServer()).thenReturn(false);
        when(server.getWorldPath(LevelResource.ROOT)).thenReturn(world);
        return server;
    }

    @Test
    void bundledDefaultMatchesEveryConfigFieldInBothDirections() {
        EconomyConfig.load(server());

        List<String> problems = new ArrayList<>();
        compare(EconomyConfig.get(), bundled(), "config.json", problems);

        assertTrue(problems.isEmpty(), () -> problems.size() + " mismatch(es) between config.json and "
                + "EconomyConfig:\n  " + String.join("\n  ", problems));
    }

    @Test
    void shippedDefaultsSurviveTheirOwnClamp() {
        EconomyConfig.load(server());

        List<String> problems = new ArrayList<>();
        compare(EconomyConfig.get(), bundled(), "config.json", problems);

        // compare() reads the clamped instance, so a value the clamp rewrote is reported as a mismatch above.
        // This test exists as its own case because that is a shipping bug of a different kind: a default the
        // mod silently corrects on first boot, hiding the wrong number from whoever wrote the file.
        assertTrue(problems.stream().noneMatch(p -> p.contains("loaded as")),
                () -> "the bundled default contains a value that its own validation rewrites:\n  "
                        + String.join("\n  ", problems));
    }

    @Test
    void freshInstallWritesTheBundledDefaultUnchanged() throws IOException {
        Path file = EconomyPaths.configDir(server()).resolve("config.json");

        EconomyConfig.load(server());

        JsonElement written = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
        assertEquals(bundled(), written,
                "loading as a fresh install changed config.json, so the shipped default is incomplete or "
                        + "non-canonical");
    }

    // --- the bidirectional walk ---

    private static void compare(Object typed, JsonObject json, String path, List<String> problems) {
        Map<String, Field> fields = fieldsBy(typed.getClass());
        Map<String, JsonElement> members = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
            members.put(entry.getKey(), entry.getValue());
        }

        for (Map.Entry<String, JsonElement> entry : members.entrySet()) {
            if (!fields.containsKey(entry.getKey())) {
                problems.add(path + "." + entry.getKey() + " matches no field — typo, or the field was removed");
            }
        }

        for (Map.Entry<String, Field> entry : fields.entrySet()) {
            String key = entry.getKey();
            Object value = read(entry.getValue(), typed, path, problems);
            String childPath = path + "." + key;

            if (!members.containsKey(key)) {
                problems.add(childPath + " has no entry in the bundled default, so it would load as "
                        + describe(value));
                continue;
            }

            JsonElement jsonValue = members.get(key);
            if (value == null) {
                problems.add(childPath + " is null in EconomyConfig");
                continue;
            }
            if (isSection(value)) {
                if (!jsonValue.isJsonObject()) {
                    problems.add(childPath + " is a record in code but not an object in the file");
                    continue;
                }
                compare(value, jsonValue.getAsJsonObject(), childPath, problems);
                continue;
            }
            if (!matches(value, jsonValue)) {
                problems.add(childPath + " is " + jsonValue + " in the file but loads as " + describe(value)
                        + " — either the default disagrees with the code, or its own validation rewrites it");
            }
        }
    }

    /**
     * Whether a value is a nested object the file must describe key by key.
     *
     * <p>The two section classes are ordinary classes (they exist to be rebuilt by {@code requireSection} when a
     * hand-edited file nulls them), and each faction/profession record is a {@code TagSettings} subclass. A
     * {@code List}, a {@code String} and an enum are none of those, so they fall through to the leaf comparison.
     */
    private static boolean isSection(Object value) {
        return value instanceof FactionsSection || value instanceof ProfessionsSection
                || value instanceof TagSettings;
    }

    /**
     * Every instance field a class contributes, keyed by its JSON name.
     *
     * <p>Walks up the hierarchy: {@code color} and {@code icon} are declared once on {@code TagSettings} and
     * inherited by all nine faction/profession records, and Gson serialises inherited fields, so
     * {@code getDeclaredFields()} alone would report them as keys matching no field.
     */
    private static Map<String, Field> fieldsBy(Class<?> type) {
        Map<String, Field> byName = new LinkedHashMap<>();
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) continue;
                SerializedName annotation = field.getAnnotation(SerializedName.class);
                byName.putIfAbsent(annotation != null ? annotation.value() : field.getName(), field);
            }
        }
        return byName;
    }

    private static Object read(Field field, Object owner, String path, List<String> problems) {
        try {
            field.setAccessible(true);
            return field.get(owner);
        } catch (ReflectiveOperationException e) {
            problems.add(path + "." + field.getName() + " is unreadable: " + e);
            return null;
        }
    }

    /** Compares one leaf value, by numeric value for numbers so {@code 1} and {@code 1.0} agree. */
    private static boolean matches(Object value, JsonElement json) {
        if (value instanceof Number number) {
            return json.isJsonPrimitive() && json.getAsJsonPrimitive().isNumber()
                    && number.doubleValue() == json.getAsDouble();
        }
        if (value instanceof Boolean flag) {
            return json.isJsonPrimitive() && json.getAsBoolean() == flag;
        }
        if (value instanceof Enum<?> constant) {
            return json.isJsonPrimitive() && constant.name().equals(json.getAsString());
        }
        if (value instanceof String text) {
            return json.isJsonPrimitive() && text.equals(json.getAsString());
        }
        if (value instanceof List<?> list) {
            if (!json.isJsonArray() || json.getAsJsonArray().size() != list.size()) return false;
            for (int i = 0; i < list.size(); i++) {
                if (!matches(list.get(i), json.getAsJsonArray().get(i))) return false;
            }
            return true;
        }
        return false;
    }

    private static String describe(Object value) {
        return value == null ? "null" : String.valueOf(value);
    }

    /** The shipped resource: the only place the default keys live. */
    private static JsonObject bundled() {
        try (InputStream in = BundledConfigTest.class.getResourceAsStream("/assets/economycraft/config.json")) {
            assertNotNull(in, "the bundled default must be on the test classpath");
            return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }
}