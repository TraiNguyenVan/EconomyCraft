package com.reazip.economycraft.db;

import com.mojang.logging.LogUtils;
import com.reazip.economycraft.util.AsyncFileWriter;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The single read/write seam between every persistent store and its bytes.
 *
 * <p>Production passes the server's {@link EconomyDatabase} and the store's legacy document key
 * (its old file name, e.g. {@code "balances.json"}): content is read from and written to the one
 * {@code economycraft.db} file, synchronously. When the database is {@code null} — unit tests that
 * construct stores with temp files — the legacy file path is used exactly as before, so no test
 * needs a database to exercise store logic.
 *
 * <p>Reads check the database first and fall back to the legacy file, which covers the one boot
 * where the importer has not run yet from a given code path. Writes go to exactly one place.
 */
public final class Documents {
    private Documents() {}

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Reads a whole document: database row first, legacy file as fallback, {@code null} if neither. */
    public static String read(EconomyDatabase db, Path legacyFile, String key) {
        if (db != null) {
            String content = db.readDocument(key);
            if (content != null) return content;
        }
        if (legacyFile != null && Files.exists(legacyFile)) {
            try {
                return Files.readString(legacyFile, StandardCharsets.UTF_8);
            } catch (Exception ex) {
                LOGGER.error("[EconomyCraft] Failed to read {}", legacyFile, ex);
            }
        }
        return null;
    }

    /** Writes a whole document: database row in production, legacy async file write in tests. */
    public static void write(EconomyDatabase db, Path legacyFile, String key, String content) {
        if (db != null) {
            db.writeDocument(key, content);
            return;
        }
        AsyncFileWriter.writeAsync(legacyFile, content);
    }
}
