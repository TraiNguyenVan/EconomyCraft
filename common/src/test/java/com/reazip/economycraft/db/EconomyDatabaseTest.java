package com.reazip.economycraft.db;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the single-file store: document round-trips, one-time legacy import with archival,
 * idempotent re-import, and villagers.db consolidation. No Minecraft classes involved.
 */
class EconomyDatabaseTest {

    @Test
    void documentsRoundTrip() throws Exception {
        try (EconomyDatabase db = EconomyDatabase.createInMemory()) {
            db.initialize();
            assertNull(db.readDocument("balances.json"));
            assertFalse(db.hasDocument("balances.json"));

            db.writeDocument("balances.json", "{\"a\":1}");
            assertTrue(db.hasDocument("balances.json"));
            assertEquals("{\"a\":1}", db.readDocument("balances.json"));

            db.writeDocument("balances.json", "{\"a\":2}");
            assertEquals("{\"a\":2}", db.readDocument("balances.json"));
        }
    }

    @Test
    void legacyImportMovesFilesIntoDocuments(@TempDir Path dataDir) throws Exception {
        Files.writeString(dataDir.resolve("balances.json"), "{\"uuid-1\":100}");
        Files.writeString(dataDir.resolve("tolls.json"), "{}");
        Files.writeString(dataDir.resolve("config.json"), "{}"); // not a document: must be ignored

        try (EconomyDatabase db = EconomyDatabase.createInMemory()) {
            db.initialize();
            int imported = db.importLegacy(dataDir);
            assertEquals(2, imported);
            assertEquals("{\"uuid-1\":100}", db.readDocument("balances.json"));
            assertEquals("{}", db.readDocument("tolls.json"));
            assertNull(db.readDocument("config.json"));

            // Originals are archived, never deleted.
            assertFalse(Files.exists(dataDir.resolve("balances.json")));
            assertFalse(Files.exists(dataDir.resolve("tolls.json")));
            assertTrue(Files.exists(dataDir.resolve("config.json")));
            try (var archived = Files.list(dataDir.resolve("_migrated-json"))) {
                assertEquals(2, archived.count());
            }

            // Re-import is a no-op.
            assertEquals(0, db.importLegacy(dataDir));
        }
    }

    @Test
    void legacyShopJsonFeedsAuctionsDocument(@TempDir Path dataDir) throws Exception {
        Files.writeString(dataDir.resolve("shop.json"), "{\"nextId\":3}");
        try (EconomyDatabase db = EconomyDatabase.createInMemory()) {
            db.initialize();
            assertEquals(1, db.importLegacy(dataDir));
            assertEquals("{\"nextId\":3}", db.readDocument("auctions.json"));
            assertFalse(Files.exists(dataDir.resolve("shop.json")));
        }
    }

    @Test
    void failedDocumentWriteRollsBackTheWholeTransaction(@TempDir Path dataDir) throws Exception {
        Path dbFile = dataDir.resolve(EconomyDatabase.DB_FILE_NAME);
        try (EconomyDatabase db = new EconomyDatabase(dbFile)) {
            db.initialize();
            db.writeDocument("stock.json", "{\"units\":10}");
            db.writeDocument("auctions.json", "{\"listings\":0}");
            failUpdatesTo(dbFile, "auctions.json");

            // The second write fails at the database, after the first already ran in the transaction.
            assertThrows(RuntimeException.class, () -> db.runInTransaction(() -> {
                db.writeDocument("stock.json", "{\"units\":0}");
                db.writeDocument("auctions.json", "{\"listings\":1}");
            }));

            assertEquals("{\"units\":10}", db.readDocument("stock.json"),
                    "the write before the failure rolls back with it");
            assertEquals("{\"listings\":0}", db.readDocument("auctions.json"),
                    "the failed write leaves its document unchanged");
        }
    }

    @Test
    void caughtDocumentWriteFailureStillRollsBackTheTransaction(@TempDir Path dataDir) throws Exception {
        Path dbFile = dataDir.resolve(EconomyDatabase.DB_FILE_NAME);
        try (EconomyDatabase db = new EconomyDatabase(dbFile)) {
            db.initialize();
            db.writeDocument("stock.json", "{\"units\":10}");
            db.writeDocument("auctions.json", "{\"listings\":0}");
            failUpdatesTo(dbFile, "auctions.json");

            // A body that swallows the failure must not turn it into a commit of the other writes.
            assertThrows(RuntimeException.class, () -> db.runInTransaction(() -> {
                db.writeDocument("stock.json", "{\"units\":0}");
                try {
                    db.writeDocument("auctions.json", "{\"listings\":1}");
                } catch (RuntimeException ignored) {
                    // Deliberately swallowed: the transaction must still refuse to commit.
                }
            }));

            assertEquals("{\"units\":10}", db.readDocument("stock.json"));
        }
    }

    @Test
    void afterCommitActionsAreDiscardedWhenTheTransactionRollsBack(@TempDir Path dataDir) throws Exception {
        Path dbFile = dataDir.resolve(EconomyDatabase.DB_FILE_NAME);
        try (EconomyDatabase db = new EconomyDatabase(dbFile)) {
            db.initialize();
            db.writeDocument("auctions.json", "{\"listings\":0}");
            failUpdatesTo(dbFile, "auctions.json");

            int[] runs = {0};
            db.afterCommit(() -> runs[0]++);
            assertEquals(1, runs[0], "without a transaction the action runs immediately");

            db.runInTransaction(() -> db.afterCommit(() -> runs[0]++));
            assertEquals(2, runs[0], "a committed transaction runs its action");

            assertThrows(RuntimeException.class, () -> db.runInTransaction(() -> {
                db.afterCommit(() -> runs[0]++);
                db.writeDocument("auctions.json", "{\"listings\":1}");
            }));
            assertEquals(2, runs[0], "a rolled-back transaction discards its action");
        }
    }

    @Test
    void fileBackedDatabaseSurvivesReopen(@TempDir Path dataDir) throws Exception {
        Path dbFile = dataDir.resolve(EconomyDatabase.DB_FILE_NAME);
        try (EconomyDatabase db = new EconomyDatabase(dbFile)) {
            db.initialize();
            db.writeDocument("quests.json", "{\"week\":1}");
        }
        assertTrue(Files.exists(dbFile));
        try (EconomyDatabase reopened = new EconomyDatabase(dbFile)) {
            reopened.initialize();
            assertEquals("{\"week\":1}", reopened.readDocument("quests.json"));
        }
    }

    @Test
    void blankLegacyFilesAreArchivedWithoutImport(@TempDir Path dataDir) throws Exception {
        Files.writeString(dataDir.resolve("stock.json"), "   ");
        try (EconomyDatabase db = EconomyDatabase.createInMemory()) {
            db.initialize();
            assertEquals(0, db.importLegacy(dataDir));
            assertNull(db.readDocument("stock.json"));
            assertFalse(Files.exists(dataDir.resolve("stock.json")));
        }
    }

    @Test
    void villagersDbIsConsolidatedIntoTheSameFile(@TempDir Path dataDir) throws Exception {
        // Build a legacy villagers.db the way VillagerDatabase would have left it.
        Path legacy = dataDir.resolve("villagers.db");
        String url = "jdbc:sqlite:" + legacy.toAbsolutePath();
        try (java.sql.Connection conn = java.sql.DriverManager.getConnection(url);
             java.sql.Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE villagers (uuid TEXT PRIMARY KEY, name TEXT NOT NULL, profession TEXT NOT NULL, " +
                    "biome TEXT NOT NULL, traits TEXT NOT NULL, quirk TEXT NOT NULL, backstory TEXT NOT NULL, " +
                    "created_at INTEGER NOT NULL, last_seen INTEGER NOT NULL)");
            st.execute("CREATE TABLE player_memories (villager_uuid TEXT NOT NULL, player_uuid TEXT NOT NULL, " +
                    "sentiment INTEGER DEFAULT 0, interaction_count INTEGER DEFAULT 0, total_spent INTEGER DEFAULT 0, " +
                    "last_interaction INTEGER NOT NULL, recent_events TEXT NOT NULL, " +
                    "PRIMARY KEY (villager_uuid, player_uuid))");
            st.execute("CREATE TABLE villager_trades (id INTEGER PRIMARY KEY AUTOINCREMENT, villager_uuid TEXT NOT NULL, " +
                    "player_uuid TEXT NOT NULL, item_name TEXT NOT NULL, item_count INTEGER NOT NULL, " +
                    "price_paid INTEGER NOT NULL, timestamp INTEGER NOT NULL)");
            st.execute("INSERT INTO villagers VALUES ('v-1', 'Bob', 'farmer', 'plains', '{}', 'grumpy', 'story', 1, 2)");
        }

        Path dbFile = dataDir.resolve(EconomyDatabase.DB_FILE_NAME);
        try (EconomyDatabase db = new EconomyDatabase(dbFile)) {
            db.initialize();
            int rows = db.importVillagers(dataDir);
            assertEquals(1, rows);
            // Legacy file archived, sidecars dropped.
            assertFalse(Files.exists(legacy));
            // The row is really in the consolidated file, readable by the gossip store's own schema.
            try (java.sql.Connection check = java.sql.DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath());
                 java.sql.Statement st = check.createStatement();
                 java.sql.ResultSet rs = st.executeQuery("SELECT name FROM villagers WHERE uuid = 'v-1'")) {
                assertTrue(rs.next());
                assertEquals("Bob", rs.getString(1));
            }
        }
    }

    /**
     * Makes the next update of {@code key} fail inside SQLite itself, with {@code RAISE(ABORT)}, so the
     * failure reaches {@link EconomyDatabase#writeDocument} as a real {@code SQLException} rather than
     * an exception thrown above its catch.
     */
    private static void failUpdatesTo(Path dbFile, String key) throws Exception {
        String url = "jdbc:sqlite:" + dbFile.toAbsolutePath();
        try (java.sql.Connection conn = java.sql.DriverManager.getConnection(url);
             java.sql.Statement st = conn.createStatement()) {
            st.execute("CREATE TRIGGER fail_document_update BEFORE UPDATE ON documents " +
                    "WHEN OLD.name = '" + key + "' BEGIN SELECT RAISE(ABORT, 'simulated write failure'); END");
        }
    }
}
