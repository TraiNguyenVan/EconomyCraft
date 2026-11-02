package com.reazip.economycraft.db;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * The single SQLite file behind everything under {@code config/economycraft/data/}.
 *
 * <p>Every legacy JSON document (balances, auctions, quests, tolls, …) lives as one row in the
 * {@code documents} table, keyed by its legacy file name, with the exact same Gson payload the file
 * used to hold. The in-memory behavior of every store is unchanged — only the durability layer moved,
 * from ~20 JSON files plus {@code villagers.db} to this one file. Villager gossip memory was already
 * relational, so it keeps real tables in the same file instead of a document row.
 *
 * <p>All methods are synchronized and share one connection. Every caller runs on the server thread
 * except the villager executor, so contention is a non-issue; {@code busy_timeout} covers the overlap.
 * Writes are synchronous: a {@code save()} that returns has hit durable storage (WAL + NORMAL sync),
 * which is strictly stronger than the old fire-and-forget file queue.
 *
 * <p>First boot imports every legacy {@code *.json} document and {@code villagers.db} exactly once
 * (empty-table guard), then archives the originals under {@code data/_migrated-json/} so they are never
 * read again.
 */
public final class EconomyDatabase implements AutoCloseable {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** The one file. Lives directly inside {@code config/economycraft/data/}. */
    public static final String DB_FILE_NAME = "economycraft.db";

    /** Where imported legacy files are parked after a successful migration. */
    private static final String LEGACY_BACKUP_DIR = "_migrated-json";

    /**
     * Document keys, each the legacy file name it replaces. {@code shop.json} is read-only legacy:
     * it feeds the {@code auctions.json} document when no auctions file exists, then is archived.
     */
    public static final List<String> DOCUMENT_KEYS = List.of(
            "balances.json",
            "daily.json",
            "daily_sells.json",
            "stats.json",
            "player_names.json",
            "player_activity.json",
            "online_time.json",
            "cooldowns.json",
            "parties.json",
            "professions.json",
            "deliveries.json",
            "auctions.json",
            "orders.json",
            "notifications.json",
            "negotiations.json",
            "quests.json",
            "stock.json",
            "fiscal.json",
            "faction_fiscal.json",
            "tolls.json",
            "contracts.json"
    );

    private final Path dbPath;
    private final String jdbcUrl;
    private final boolean inMemory;
    private Connection connection;
    private boolean initialized;
    private boolean inTransaction;
    /**
     * The first document-write failure seen inside the open transaction, kept so the transaction can
     * never commit even if the body catches the exception.
     */
    private RuntimeException transactionWriteFailure;
    /** Actions to run once the open transaction commits; discarded when it rolls back. */
    private final List<Runnable> afterCommit = new ArrayList<>();

    public EconomyDatabase(Path dbPath) {
        this.dbPath = dbPath.toAbsolutePath();
        this.jdbcUrl = "jdbc:sqlite:" + this.dbPath;
        this.inMemory = false;
    }

    private EconomyDatabase(String jdbcUrl, Path dbPath, boolean inMemory) {
        this.dbPath = dbPath;
        this.jdbcUrl = jdbcUrl;
        this.inMemory = inMemory;
    }

    /** Ephemeral database for unit tests. Each instance is a fresh, empty database. */
    public static EconomyDatabase createInMemory() {
        return new EconomyDatabase("jdbc:sqlite::memory:", null, true);
    }

    /**
     * Opens the connection and creates every table. Idempotent; safe to call on every access path.
     */
    public synchronized void initialize() throws SQLException {
        if (initialized && isOpen()) return;
        connect();
        try (Statement st = connection.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS documents (
                    name TEXT PRIMARY KEY,
                    content TEXT NOT NULL,
                    updated_at INTEGER NOT NULL
                );
            """);
            createVillagerTables(st);
            createPlayerShopTables(st);
        }
        initialized = true;
    }

    /** Player-sold shop stock and its exact paid-price history for the rolling price floor. */
    private static void createPlayerShopTables(Statement st) throws SQLException {
        st.execute("""
            CREATE TABLE IF NOT EXISTS player_shop_stock (
                market_key TEXT PRIMARY KEY,
                count INTEGER NOT NULL CHECK (count >= 0)
            );
        """);
        st.execute("""
            CREATE TABLE IF NOT EXISTS player_shop_paid_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                market_key TEXT NOT NULL,
                paid_unit INTEGER NOT NULL CHECK (paid_unit >= 0),
                recorded_at INTEGER NOT NULL,
                count INTEGER NOT NULL CHECK (count > 0)
            );
        """);
        st.execute("CREATE INDEX IF NOT EXISTS idx_player_shop_paid_history_cutoff " +
                "ON player_shop_paid_history(market_key, recorded_at);");
    }

    /**
     * Villager gossip tables. Same DDL as {@code VillagerDatabase} (the source of truth for the
     * column layout — keep the two in sync); {@code IF NOT EXISTS} makes the duplication harmless.
     */
    private static void createVillagerTables(Statement st) throws SQLException {
        st.execute("""
            CREATE TABLE IF NOT EXISTS villagers (
                uuid TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                profession TEXT NOT NULL,
                biome TEXT NOT NULL,
                traits TEXT NOT NULL,
                quirk TEXT NOT NULL,
                backstory TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                last_seen INTEGER NOT NULL
            );
        """);
        st.execute("""
            CREATE TABLE IF NOT EXISTS player_memories (
                villager_uuid TEXT NOT NULL,
                player_uuid TEXT NOT NULL,
                sentiment INTEGER DEFAULT 0,
                interaction_count INTEGER DEFAULT 0,
                total_spent INTEGER DEFAULT 0,
                last_interaction INTEGER NOT NULL,
                recent_events TEXT NOT NULL,
                PRIMARY KEY (villager_uuid, player_uuid),
                FOREIGN KEY (villager_uuid) REFERENCES villagers(uuid) ON DELETE CASCADE
            );
        """);
        st.execute("CREATE INDEX IF NOT EXISTS idx_memories_villager ON player_memories(villager_uuid);");
        st.execute("CREATE INDEX IF NOT EXISTS idx_memories_player ON player_memories(player_uuid);");
        st.execute("""
            CREATE TABLE IF NOT EXISTS villager_trades (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                villager_uuid TEXT NOT NULL,
                player_uuid TEXT NOT NULL,
                item_name TEXT NOT NULL,
                item_count INTEGER NOT NULL,
                price_paid INTEGER NOT NULL,
                timestamp INTEGER NOT NULL,
                FOREIGN KEY (villager_uuid) REFERENCES villagers(uuid) ON DELETE CASCADE
            );
        """);
        st.execute("CREATE INDEX IF NOT EXISTS idx_trades_villager_player ON villager_trades(villager_uuid, player_uuid);");
        st.execute("CREATE INDEX IF NOT EXISTS idx_trades_timestamp ON villager_trades(timestamp DESC);");
    }

    private void connect() throws SQLException {
        if (!inMemory && dbPath != null) {
            try {
                Path parent = dbPath.getParent();
                if (parent != null) Files.createDirectories(parent);
            } catch (IOException e) {
                LOGGER.warn("[EconomyCraft-DB] Could not create directories for {}: {}", dbPath, e.getMessage());
            }
        }
        if (!isOpen()) {
            connection = DriverManager.getConnection(jdbcUrl);
        }
        try (Statement st = connection.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL;");
            st.execute("PRAGMA synchronous=NORMAL;");
            st.execute("PRAGMA foreign_keys=ON;");
            st.execute("PRAGMA busy_timeout=5000;");
        }
    }

    private boolean isOpen() {
        try {
            return connection != null && !connection.isClosed();
        } catch (SQLException e) {
            return false;
        }
    }

    private synchronized void ensureOpen() {
        try {
            if (!initialized || !isOpen()) {
                if (inMemory && initialized) {
                    throw new IllegalStateException("In-memory EconomyDatabase was closed");
                }
                initialize();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to open EconomyCraft database at " + jdbcUrl, e);
        }
    }

    /** Reads one document, or {@code null} when it was never written. */
    public synchronized String readDocument(String key) {
        ensureOpen();
        try (PreparedStatement ps = connection.prepareStatement("SELECT content FROM documents WHERE name = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString(1);
            }
        } catch (SQLException e) {
            LOGGER.error("[EconomyCraft-DB] Failed to read document {}", key, e);
        }
        return null;
    }

    /** Reads one document while distinguishing a missing row from a failed read. */
    public synchronized DocumentRead readDocumentWithStatus(String key) {
        try {
            ensureOpen();
            try (PreparedStatement ps = connection.prepareStatement("SELECT content FROM documents WHERE name = ?")) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    return new DocumentRead(true, rs.next() ? rs.getString(1) : null);
                }
            }
        } catch (SQLException | RuntimeException e) {
            LOGGER.error("[EconomyCraft-DB] Failed to read document {}", key, e);
            return new DocumentRead(false, null);
        }
    }

    public record DocumentRead(boolean successful, String content) {}

    /** Whether the document has a row at all (used as the already-migrated guard). */
    public synchronized boolean hasDocument(String key) {
        ensureOpen();
        try (PreparedStatement ps = connection.prepareStatement("SELECT 1 FROM documents WHERE name = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            LOGGER.error("[EconomyCraft-DB] Failed to check document {}", key, e);
            return false;
        }
    }

    /**
     * Upserts one document. Synchronous: when this returns outside a transaction, the row is
     * committed.
     *
     * <p>Inside a transaction a failed write is thrown, not logged and swallowed: the transaction
     * would otherwise commit the other documents in the group while this one silently kept its old
     * content, which is exactly the half-written state atomic callers exist to prevent. The failure
     * is also remembered so a body that catches it still cannot let the transaction commit.
     */
    public synchronized void writeDocument(String key, String content) {
        ensureOpen();
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO documents (name, content, updated_at) VALUES (?, ?, ?) " +
                "ON CONFLICT(name) DO UPDATE SET content = excluded.content, updated_at = excluded.updated_at")) {
            ps.setString(1, key);
            ps.setString(2, content);
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            LOGGER.error("[EconomyCraft-DB] Failed to write document {}", key, e);
            if (!inTransaction) return;
            DocumentWriteException failure =
                    new DocumentWriteException("Failed to write document " + key, e);
            if (transactionWriteFailure == null) transactionWriteFailure = failure;
            throw failure;
        }
    }

    /** Returns the player-sold shop stock for {@code marketKey}, or zero when absent. */
    public synchronized long getPlayerShopStock(String marketKey) {
        ensureOpen();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT count FROM player_shop_stock WHERE market_key = ?")) {
            ps.setString(1, marketKey);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            throw playerShopFailure("read stock", marketKey, e);
        }
    }

    /** Adds player-sold stock and records its paid unit price at {@code recordedAt}. */
    public synchronized void depositPlayerShopStock(String marketKey, long count, long paidUnit, long recordedAt) {
        if (count <= 0) throw new IllegalArgumentException("count must be positive");
        if (paidUnit < 0) throw new IllegalArgumentException("paidUnit must not be negative");
        runInTransaction(() -> {
            try (PreparedStatement stock = connection.prepareStatement("""
                    INSERT INTO player_shop_stock (market_key, count) VALUES (?, ?)
                    ON CONFLICT(market_key) DO UPDATE SET count = count + excluded.count
                    """);
                 PreparedStatement history = connection.prepareStatement("""
                    INSERT INTO player_shop_paid_history (market_key, paid_unit, recorded_at, count) VALUES (?, ?, ?, ?)
                    """)) {
                stock.setString(1, marketKey);
                stock.setLong(2, count);
                stock.executeUpdate();
                history.setString(1, marketKey);
                history.setLong(2, paidUnit);
                history.setLong(3, recordedAt);
                history.setLong(4, count);
                history.executeUpdate();
            } catch (SQLException e) {
                throw playerShopFailure("deposit stock", marketKey, e);
            }
        });
    }

    /** Removes and returns up to {@code amount} units of player-sold stock. */
    public synchronized long withdrawPlayerShopStock(String marketKey, long amount) {
        if (amount <= 0) return 0L;
        final long[] withdrawn = {0L};
        runInTransaction(() -> {
            long available = getPlayerShopStock(marketKey);
            withdrawn[0] = Math.min(available, amount);
            if (withdrawn[0] == 0) return;
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE player_shop_stock SET count = count - ? WHERE market_key = ?")) {
                ps.setLong(1, withdrawn[0]);
                ps.setString(2, marketKey);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw playerShopFailure("withdraw stock", marketKey, e);
            }
        });
        return withdrawn[0];
    }

    /** Replaces the player-sold stock count for {@code marketKey}. */
    public synchronized void setPlayerShopStock(String marketKey, long count) {
        if (count < 0) throw new IllegalArgumentException("count must not be negative");
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO player_shop_stock (market_key, count) VALUES (?, ?)
                ON CONFLICT(market_key) DO UPDATE SET count = excluded.count
                """)) {
            ps.setString(1, marketKey);
            ps.setLong(2, count);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw playerShopFailure("set stock", marketKey, e);
        }
    }

    /** Removes a market key's stock and its paid-price history. */
    public synchronized void clearPlayerShopStock(String marketKey) {
        runInTransaction(() -> {
            try (PreparedStatement stock = connection.prepareStatement(
                         "DELETE FROM player_shop_stock WHERE market_key = ?");
                 PreparedStatement history = connection.prepareStatement(
                         "DELETE FROM player_shop_paid_history WHERE market_key = ?")) {
                stock.setString(1, marketKey);
                stock.executeUpdate();
                history.setString(1, marketKey);
                history.executeUpdate();
            } catch (SQLException e) {
                throw playerShopFailure("clear stock", marketKey, e);
            }
        });
    }

    /**
     * Prunes paid-price records older than {@code cutoffMillis} and returns the maximum paid unit
     * price remaining for the market key, or zero when no qualifying deposit remains.
     */
    public synchronized long playerShopHighWater(String marketKey, long cutoffMillis) {
        final long[] highWater = {0L};
        runInTransaction(() -> {
            try (PreparedStatement prune = connection.prepareStatement(
                         "DELETE FROM player_shop_paid_history WHERE market_key = ? AND recorded_at < ?");
                 PreparedStatement maximum = connection.prepareStatement(
                         "SELECT COALESCE(MAX(paid_unit), 0) FROM player_shop_paid_history " +
                                 "WHERE market_key = ? AND recorded_at >= ?")) {
                prune.setString(1, marketKey);
                prune.setLong(2, cutoffMillis);
                prune.executeUpdate();
                maximum.setString(1, marketKey);
                maximum.setLong(2, cutoffMillis);
                try (ResultSet rs = maximum.executeQuery()) {
                    highWater[0] = rs.next() ? rs.getLong(1) : 0L;
                }
            } catch (SQLException e) {
                throw playerShopFailure("read paid-price high-water", marketKey, e);
            }
        });
        return highWater[0];
    }

    private IllegalStateException playerShopFailure(String operation, String marketKey, SQLException cause) {
        IllegalStateException failure =
                new IllegalStateException("Failed to " + operation + " for player shop key " + marketKey, cause);
        if (inTransaction && transactionWriteFailure == null) transactionWriteFailure = failure;
        return failure;
    }

    /**
     * Runs {@code body} inside one SQLite transaction: every document write it performs commits
     * together or not at all. Callers who need a group of document writes to be atomic — the
     * settlement protocol's balance-plus-contract pair — wrap them here. Nested calls join the
     * outer transaction instead of opening one, since all writers share this one connection.
     */
    public synchronized void runInTransaction(Runnable body) {
        ensureOpen();
        if (inTransaction) {
            body.run();
            return;
        }
        inTransaction = true;
        transactionWriteFailure = null;
        afterCommit.clear();
        try {
            connection.setAutoCommit(false);
            body.run();
            // A body may catch a write failure and carry on; committing then would persist the rest
            // of the group beside a document that kept its old content. Refuse the commit instead.
            if (transactionWriteFailure != null) throw transactionWriteFailure;
            connection.commit();
            runAfterCommitActions();
        } catch (RuntimeException | SQLException e) {
            try {
                connection.rollback();
            } catch (SQLException rollbackError) {
                LOGGER.error("[EconomyCraft-DB] Failed to roll back a transaction", rollbackError);
            }
            throw e instanceof RuntimeException runtime ? runtime : new IllegalStateException(e);
        } finally {
            inTransaction = false;
            transactionWriteFailure = null;
            afterCommit.clear();
            try {
                connection.setAutoCommit(true);
            } catch (SQLException ignored) {
            }
        }
    }

    /**
     * Runs {@code action} once the open transaction commits, or immediately when none is open.
     *
     * <p>Stores use this to clear their dirty flags only after the data is durable: clearing them
     * inside the transaction would make a rolled-back write look saved, and the next flush would
     * skip it. Actions registered in a transaction that rolls back are discarded.
     */
    public synchronized void afterCommit(Runnable action) {
        if (inTransaction) {
            afterCommit.add(action);
        } else {
            action.run();
        }
    }

    private void runAfterCommitActions() {
        if (afterCommit.isEmpty()) return;
        List<Runnable> actions = new ArrayList<>(afterCommit);
        afterCommit.clear();
        for (Runnable action : actions) {
            try {
                action.run();
            } catch (RuntimeException e) {
                // The transaction is already durable; a bookkeeping failure must not look like a
                // failed commit to the caller.
                LOGGER.error("[EconomyCraft-DB] A post-commit action failed", e);
            }
        }
    }

    /** A document write failed inside a transaction, so the transaction cannot commit. */
    public static final class DocumentWriteException extends RuntimeException {
        DocumentWriteException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * One-time import of every legacy JSON document. A document is imported only when its row is
     * absent and a legacy file exists; imported files are archived, never deleted, so a failed boot
     * can always be reconstructed by hand.
     *
     * @return how many documents were imported
     */
    public synchronized int importLegacy(Path dataDir) {
        ensureOpen();
        int imported = 0;
        for (String key : DOCUMENT_KEYS) {
            Path file = dataDir.resolve(key);
            boolean exists = Files.isRegularFile(file);
            if ("auctions.json".equals(key) && !exists) {
                file = dataDir.resolve("shop.json");
                exists = Files.isRegularFile(file);
            }
            if (!exists) continue;
            if (hasDocument(key)) {
                archiveQuietly(dataDir, file);
                continue;
            }
            try {
                String content = Files.readString(file);
                if (content == null || content.isBlank()) {
                    archiveQuietly(dataDir, file);
                    continue;
                }
                writeDocument(key, content);
                archiveQuietly(dataDir, file);
                imported++;
                LOGGER.info("[EconomyCraft-DB] Imported {} into {}", file.getFileName(), DB_FILE_NAME);
            } catch (IOException e) {
                LOGGER.error("[EconomyCraft-DB] Could not import {}; it was left in place.", file, e);
            }
        }
        // A stray shop.json beside an imported auctions.json is dead weight: archive it too.
        Path strayShop = dataDir.resolve("shop.json");
        if (Files.isRegularFile(strayShop)) archiveQuietly(dataDir, strayShop);
        return imported;
    }

    /**
     * One-time consolidation of the standalone {@code villagers.db} into this file. Copies the three
     * gossip tables only when they are empty here and the legacy file exists, then archives it.
     *
     * @return how many villager rows were carried over
     */
    public synchronized int importVillagers(Path dataDir) {
        ensureOpen();
        Path legacy = dataDir.resolve("villagers.db");
        if (!Files.isRegularFile(legacy)) return 0;
        if (DB_FILE_NAME.equals(legacy.getFileName().toString())) return 0;
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM villagers")) {
            if (rs.next() && rs.getInt(1) > 0) {
                archiveQuietly(dataDir, legacy);
                return 0;
            }
        } catch (SQLException e) {
            LOGGER.error("[EconomyCraft-DB] Could not inspect villager tables; leaving {} in place.", legacy, e);
            return 0;
        }
        int rows = 0;
        try (Statement st = connection.createStatement()) {
            st.execute("ATTACH DATABASE '" + legacy.toAbsolutePath().toString().replace("'", "''") + "' AS legacy");
            try {
                rows += st.executeUpdate("INSERT OR IGNORE INTO villagers SELECT * FROM legacy.villagers");
                st.executeUpdate("INSERT OR IGNORE INTO player_memories SELECT * FROM legacy.player_memories");
                st.executeUpdate("INSERT OR IGNORE INTO villager_trades SELECT * FROM legacy.villager_trades");
            } finally {
                st.execute("DETACH DATABASE legacy");
            }
            archiveQuietly(dataDir, legacy);
            LOGGER.info("[EconomyCraft-DB] Consolidated {} villager rows from villagers.db into {}", rows, DB_FILE_NAME);
        } catch (SQLException e) {
            LOGGER.error("[EconomyCraft-DB] Could not consolidate {}; it was left in place.", legacy, e);
            return 0;
        }
        // -wal/-shm sidecars of the archived file are useless without it; drop them if orphaned.
        try {
            Files.deleteIfExists(dataDir.resolve("villagers.db-wal"));
            Files.deleteIfExists(dataDir.resolve("villagers.db-shm"));
        } catch (IOException ignored) {
        }
        return rows;
    }

    private void archiveQuietly(Path dataDir, Path file) {
        try {
            Path backupDir = dataDir.resolve(LEGACY_BACKUP_DIR);
            Files.createDirectories(backupDir);
            Path target = backupDir.resolve(file.getFileName() + "." + System.currentTimeMillis() + ".bak");
            Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOGGER.warn("[EconomyCraft-DB] Could not archive {}: {}", file, e.getMessage());
        }
    }

    @Override
    public synchronized void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
            } finally {
                connection = null;
                initialized = false;
            }
        }
    }
}
