package com.reazip.economycraft.util;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.mojang.logging.LogUtils;
import com.reazip.economycraft.db.Documents;
import com.reazip.economycraft.db.EconomyDatabase;
import org.slf4j.Logger;

import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class UuidLongMapStore {
    private UuidLongMapStore() {}

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();
    private static final Type TYPE = new TypeToken<Map<UUID, Long>>(){}.getType();

    public static void load(Path file, Map<UUID, Long> target) {
        load(null, file, file != null ? file.getFileName().toString() : "", target);
    }

    /**
     * Loads a UUID-to-long map: database document first, legacy file as fallback.
     * The {@code key} is the legacy file name (e.g. {@code "daily.json"}).
     */
    public static void load(EconomyDatabase db, Path file, String key, Map<UUID, Long> target) {
        String json = Documents.read(db, file, key);
        if (json == null) return;
        try {
            Map<UUID, Long> map = GSON.fromJson(json, TYPE);
            if (map != null) {
                for (Map.Entry<UUID, Long> e : map.entrySet()) {
                    if (e.getValue() != null) target.put(e.getKey(), e.getValue());
                }
            }
        } catch (Exception ex) {
            LOGGER.error("[EconomyCraft] Failed to read {}", file, ex);
        }
    }

    public static void persist(Path file, Map<UUID, Long> map) {
        persist(null, file, file != null ? file.getFileName().toString() : "", map);
    }

    /** Persists a UUID-to-long map: database document in production, legacy file write in tests. */
    public static void persist(EconomyDatabase db, Path file, String key, Map<UUID, Long> map) {
        Documents.write(db, file, key, GSON.toJson(new HashMap<>(map), TYPE));
    }
}
