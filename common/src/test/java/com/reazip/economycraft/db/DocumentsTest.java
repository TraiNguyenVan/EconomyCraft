package com.reazip.economycraft.db;

import com.reazip.economycraft.util.AsyncFileWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the seam every persistent store reads and writes through: the database row wins, the
 * legacy file is the read-only fallback, and a write lands in exactly one place. Both halves matter
 * — a write that also touched the legacy file would resurrect stale data after the next import.
 */
class DocumentsTest {

    @Test
    void databaseRowWinsOverStaleLegacyFile(@TempDir Path dir) throws Exception {
        Path legacy = dir.resolve("auctions.json");
        Files.writeString(legacy, "{\"nextId\":1}");
        try (EconomyDatabase db = EconomyDatabase.createInMemory()) {
            db.initialize();
            db.writeDocument("auctions.json", "{\"nextId\":9}");

            assertEquals("{\"nextId\":9}", Documents.read(db, legacy, "auctions.json"));
        }
    }

    @Test
    void missingRowFallsBackToLegacyFile(@TempDir Path dir) throws Exception {
        Path legacy = dir.resolve("tolls.json");
        Files.writeString(legacy, "{\"legacy\":true}");
        try (EconomyDatabase db = EconomyDatabase.createInMemory()) {
            db.initialize();
            assertEquals("{\"legacy\":true}", Documents.read(db, legacy, "tolls.json"));
        }
    }

    @Test
    void missingEverywhereReadsNull(@TempDir Path dir) throws Exception {
        Path legacy = dir.resolve("never-written.json");
        try (EconomyDatabase db = EconomyDatabase.createInMemory()) {
            db.initialize();
            assertNull(Documents.read(db, legacy, "never-written.json"));
        }
        assertNull(Documents.read(null, legacy, "never-written.json"));
    }

    @Test
    void writeGoesToDatabaseOnly(@TempDir Path dir) throws Exception {
        Path legacy = dir.resolve("orders.json");
        try (EconomyDatabase db = EconomyDatabase.createInMemory()) {
            db.initialize();
            Documents.write(db, legacy, "orders.json", "{\"nextId\":2}");

            assertEquals("{\"nextId\":2}", db.readDocument("orders.json"));
            assertFalse(Files.exists(legacy), "a database write must not also create the legacy file");
        }
    }

    @Test
    void writeWithoutDatabaseFallsBackToLegacyFile(@TempDir Path dir) {
        Path legacy = dir.resolve("quests.json");
        Documents.write(null, legacy, "quests.json", "{\"week\":1}");
        AsyncFileWriter.flush();

        assertTrue(Files.exists(legacy));
        assertEquals("{\"week\":1}", readQuietly(legacy));
    }

    private static String readQuietly(Path path) {
        try {
            return Files.readString(path);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    }