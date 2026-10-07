package com.reazip.economycraft.gossip.storage;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Immutable profile representing an individual villager's persona, identity, and traits.
 */
public record VillagerProfile(
        UUID uuid,
        String name,
        String profession,
        String biome,
        List<String> traits,
        String quirk,
        String backstory,
        long createdAt,
        long lastSeen
) {
    private static final Gson GSON = new Gson();
    private static final Type LIST_STRING_TYPE = new TypeToken<List<String>>() {}.getType();

    public VillagerProfile {
        if (uuid == null) throw new IllegalArgumentException("uuid cannot be null");
        name = (name == null || name.isBlank()) ? "Villager" : name.trim();
        profession = (profession == null || profession.isBlank()) ? "none" : profession.trim();
        biome = (biome == null || biome.isBlank()) ? "plains" : biome.trim();
        traits = (traits == null || traits.isEmpty()) ? List.of("stoic") : Collections.unmodifiableList(new ArrayList<>(traits));
        quirk = (quirk == null || quirk.isBlank()) ? "Quiet and observant." : quirk.trim();
        backstory = (backstory == null || backstory.isBlank()) ? "A quiet local villager." : backstory.trim();
    }

    public String traitsJson() {
        return GSON.toJson(traits);
    }

    public static List<String> parseTraitsJson(@Nullable String json) {
        if (json == null || json.isBlank()) return List.of("stoic");
        try {
            List<String> list = GSON.fromJson(json, LIST_STRING_TYPE);
            return list != null ? list : List.of("stoic");
        } catch (Exception e) {
            return List.of("stoic");
        }
    }
}
