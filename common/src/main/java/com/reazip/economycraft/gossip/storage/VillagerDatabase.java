package com.reazip.economycraft.gossip.storage;

import com.mojang.logging.LogUtils;
import com.reazip.economycraft.util.EconomyExecutors;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * High-performance, asynchronous SQLite repository for persisting individual villager
 * profiles, identities, and player episodic relationship memories.
 */
public class VillagerDatabase implements AutoCloseable {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final long MEMORY_RETENTION_MILLIS = java.time.Duration.ofDays(90).toMillis();

    /**
     * Busy timeout of the gossip connection. This connection is a second one on the shared
     * {@code economycraft.db} file, so it needs the same timeout the primary
     * {@code EconomyDatabase} connection uses; without it a write that overlaps the primary
     * connection's write fails immediately with {@code SQLITE_BUSY}.
     */
    private static final int BUSY_TIMEOUT_MILLIS = 5000;
    /** How long {@link #close()} waits for accepted work to drain before it stops waiting. */
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 10L;

    private final String jdbcUrl;
    private final ExecutorService dbExecutor;
    private final long shutdownTimeoutSeconds;
    private final Set<CompletableFuture<?>> acceptedWork = ConcurrentHashMap.newKeySet();
    private volatile Connection connection;
    private volatile boolean initialized = false;
    /** Set when {@link #close()} starts: no new work is accepted and no reconnect happens. */
    private volatile boolean closing = false;

    public VillagerDatabase(Path dbPath) {
        this("jdbc:sqlite:" + dbPath.toAbsolutePath(), EconomyExecutors.newSingleThreadScheduledExecutor("EconomyCraft-Villager-DB"));
    }

    public VillagerDatabase(String jdbcUrl, ExecutorService dbExecutor) {
        this(jdbcUrl, dbExecutor, SHUTDOWN_TIMEOUT_SECONDS);
    }

    /**
     * @param shutdownTimeoutSeconds how long {@link #close()} waits for accepted work to drain
     *                               before it abandons it
     */
    public VillagerDatabase(String jdbcUrl, ExecutorService dbExecutor, long shutdownTimeoutSeconds) {
        this.jdbcUrl = jdbcUrl;
        this.dbExecutor = dbExecutor;
        this.shutdownTimeoutSeconds = shutdownTimeoutSeconds;
    }

    public static VillagerDatabase createInMemory() {
        return new VillagerDatabase("jdbc:sqlite::memory:", EconomyExecutors.newSingleThreadScheduledExecutor("EconomyCraft-Villager-TestDB"));
    }

    /**
     * Initializes the SQLite connection and ensures tables and indices exist.
     */
    public synchronized void initialize() throws SQLException {
        if (closing) {
            throw new VillagerDatabaseException("Villager database is closing");
        }
        if (initialized) return;

        // Ensure parent directory exists for file-based databases
        if (jdbcUrl.startsWith("jdbc:sqlite:") && !jdbcUrl.contains(":memory:")) {
            String pathStr = jdbcUrl.substring("jdbc:sqlite:".length());
            try {
                Path parent = Path.of(pathStr).getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
            } catch (IOException e) {
                LOGGER.warn("[EconomyCraft-DB] Could not create parent directories for SQLite DB: {}", e.getMessage());
            }
        }

        connection = DriverManager.getConnection(jdbcUrl);

        try (Statement st = connection.createStatement()) {
            // High concurrency & performance pragmas
            st.execute("PRAGMA journal_mode=WAL;");
            st.execute("PRAGMA synchronous=NORMAL;");
            st.execute("PRAGMA foreign_keys=ON;");
            st.execute("PRAGMA busy_timeout=" + BUSY_TIMEOUT_MILLIS + ";");

            // Villagers profile table
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

            // Player memories table
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

            // Villager trade history table
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

        initialized = true;
        LOGGER.info("[EconomyCraft-DB] Villager SQLite database initialized at {}", jdbcUrl);
        deleteExpiredMemoriesAndTrades(System.currentTimeMillis()).exceptionally(error -> {
            LOGGER.warn("[EconomyCraft-DB] Could not purge expired villager memories: {}", error.getMessage());
            return 0;
        });
    }

    // --- Asynchronous API ---

    public CompletableFuture<Optional<VillagerProfile>> getVillager(UUID uuid) {
        return submit(() -> {
            ensureOpen();
            String sql = "SELECT * FROM villagers WHERE uuid = ?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(mapVillager(rs));
                    }
                }
                return Optional.empty();
            } catch (SQLException e) {
                // A failed read is not a missing profile: report it so the caller retries
                // instead of creating a replacement over data it never saw.
                throw new VillagerDatabaseException("Error querying villager " + uuid, e);
            }
        });
    }

    public CompletableFuture<Void> saveVillager(VillagerProfile profile) {
        return run(() -> {
            ensureOpen();
            String sql = """
                INSERT INTO villagers (uuid, name, profession, biome, traits, quirk, backstory, created_at, last_seen)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(uuid) DO UPDATE SET
                    name = excluded.name,
                    profession = excluded.profession,
                    biome = excluded.biome,
                    traits = excluded.traits,
                    quirk = excluded.quirk,
                    backstory = excluded.backstory,
                    last_seen = excluded.last_seen;
            """;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, profile.uuid().toString());
                ps.setString(2, profile.name());
                ps.setString(3, profile.profession());
                ps.setString(4, profile.biome());
                ps.setString(5, profile.traitsJson());
                ps.setString(6, profile.quirk());
                ps.setString(7, profile.backstory());
                ps.setLong(8, profile.createdAt());
                ps.setLong(9, profile.lastSeen());
                ps.executeUpdate();
            } catch (SQLException e) {
                // Surface the failure through the future instead of logging it and completing
                // normally: a caller that cannot tell a failed write from a successful one will
                // keep a profile it believes was persisted.
                throw new VillagerDatabaseException("Error saving villager " + profile.uuid(), e);
            }
        });
    }

    public CompletableFuture<Optional<PlayerMemory>> getPlayerMemory(UUID villagerUuid, UUID playerUuid) {
        return submit(() -> {
            ensureOpen();
            String sql = "SELECT * FROM player_memories WHERE villager_uuid = ? AND player_uuid = ?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, villagerUuid.toString());
                ps.setString(2, playerUuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(mapPlayerMemory(rs));
                    }
                }
                return Optional.empty();
            } catch (SQLException e) {
                throw new VillagerDatabaseException("Error querying player memory", e);
            }
        });
    }

    public CompletableFuture<Optional<PlayerMemory>> getEligiblePlayerMemory(UUID villagerUuid, UUID playerUuid, long now) {
        return getPlayerMemory(villagerUuid, playerUuid).thenApply(memory -> memory
                .filter(value -> value.lastInteraction() >= retentionCutoff(now)));
    }

    public CompletableFuture<Void> savePlayerMemory(PlayerMemory memory) {
        return run(() -> {
            ensureOpen();
            String sql = """
                INSERT INTO player_memories (villager_uuid, player_uuid, sentiment, interaction_count, total_spent, last_interaction, recent_events)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(villager_uuid, player_uuid) DO UPDATE SET
                    sentiment = excluded.sentiment,
                    interaction_count = excluded.interaction_count,
                    total_spent = excluded.total_spent,
                    last_interaction = excluded.last_interaction,
                    recent_events = excluded.recent_events;
            """;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, memory.villagerUuid().toString());
                ps.setString(2, memory.playerUuid().toString());
                ps.setInt(3, memory.sentiment());
                ps.setInt(4, memory.interactionCount());
                ps.setLong(5, memory.totalSpent());
                ps.setLong(6, memory.lastInteraction());
                ps.setString(7, memory.eventsJson());
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new VillagerDatabaseException("Error saving player memory for " + memory.villagerUuid() + "/" + memory.playerUuid(), e);
            }
        });
    }

    public CompletableFuture<Void> recordTradeTransaction(
            UUID villagerUuid,
            UUID playerUuid,
            String itemName,
            int itemCount,
            long pricePaid,
            long timestamp
    ) {
        return run(() -> {
            ensureOpen();
            String sql = """
                INSERT INTO villager_trades (villager_uuid, player_uuid, item_name, item_count, price_paid, timestamp)
                VALUES (?, ?, ?, ?, ?, ?);
            """;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, villagerUuid.toString());
                ps.setString(2, playerUuid.toString());
                ps.setString(3, itemName);
                ps.setInt(4, itemCount);
                ps.setLong(5, pricePaid);
                ps.setLong(6, timestamp);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new VillagerDatabaseException("Error recording trade transaction for " + villagerUuid + "/" + playerUuid, e);
            }
        });
    }

    public CompletableFuture<Void> recordTradeTransaction(
            PlayerMemory memory, String itemName, int itemCount, long pricePaid, long timestamp
    ) {
        return run(() -> {
            ensureOpen();
            try {
                connection.setAutoCommit(false);
                try {
                    try (PreparedStatement ps = connection.prepareStatement("""
                            INSERT INTO player_memories (villager_uuid, player_uuid, sentiment, interaction_count, total_spent, last_interaction, recent_events)
                            VALUES (?, ?, ?, ?, ?, ?, ?)
                            ON CONFLICT(villager_uuid, player_uuid) DO UPDATE SET
                                sentiment = excluded.sentiment,
                                interaction_count = excluded.interaction_count,
                                total_spent = excluded.total_spent,
                                last_interaction = excluded.last_interaction,
                                recent_events = excluded.recent_events;
                            """)) {
                        ps.setString(1, memory.villagerUuid().toString());
                        ps.setString(2, memory.playerUuid().toString());
                        ps.setInt(3, memory.sentiment());
                        ps.setInt(4, memory.interactionCount());
                        ps.setLong(5, memory.totalSpent());
                        ps.setLong(6, memory.lastInteraction());
                        ps.setString(7, memory.eventsJson());
                        ps.executeUpdate();
                    }
                    try (PreparedStatement ps = connection.prepareStatement("""
                            INSERT INTO villager_trades (villager_uuid, player_uuid, item_name, item_count, price_paid, timestamp)
                            VALUES (?, ?, ?, ?, ?, ?);
                            """)) {
                        ps.setString(1, memory.villagerUuid().toString());
                        ps.setString(2, memory.playerUuid().toString());
                        ps.setString(3, itemName);
                        ps.setInt(4, itemCount);
                        ps.setLong(5, pricePaid);
                        ps.setLong(6, timestamp);
                        ps.executeUpdate();
                    }
                    connection.commit();
                } catch (SQLException e) {
                    connection.rollback();
                    throw e;
                } finally {
                    connection.setAutoCommit(true);
                }
            } catch (SQLException e) {
                throw new VillagerDatabaseException("Error recording completed villager trade", e);
            }
        });
    }

    public CompletableFuture<List<TradeRecord>> getRecentTrades(UUID villagerUuid, UUID playerUuid, int limit) {
        return getRecentTrades(villagerUuid, playerUuid, limit, System.currentTimeMillis());
    }

    public CompletableFuture<List<TradeRecord>> getRecentTrades(UUID villagerUuid, UUID playerUuid, int limit, long now) {
        return submit(() -> {
            ensureOpen();
            String sql = """
                SELECT id, villager_uuid, player_uuid, item_name, item_count, price_paid, timestamp
                FROM villager_trades
                WHERE villager_uuid = ? AND player_uuid = ? AND timestamp >= ?
                ORDER BY timestamp DESC
                LIMIT ?;
            """;
            List<TradeRecord> list = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, villagerUuid.toString());
                ps.setString(2, playerUuid.toString());
                ps.setLong(3, retentionCutoff(now));
                ps.setInt(4, Math.max(1, Math.min(5, limit)));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        list.add(new TradeRecord(
                                rs.getLong("id"),
                                UUID.fromString(rs.getString("villager_uuid")),
                                UUID.fromString(rs.getString("player_uuid")),
                                rs.getString("item_name"),
                                rs.getInt("item_count"),
                                rs.getLong("price_paid"),
                                rs.getLong("timestamp")
                        ));
                    }
                }
            } catch (SQLException e) {
                throw new VillagerDatabaseException("Error querying recent trades", e);
            }
            return list;
        });
    }

    public static long retentionCutoff(long now) {
        return now - MEMORY_RETENTION_MILLIS;
    }

    public CompletableFuture<Integer> deleteExpiredMemoriesAndTrades(long now) {
        return submit(() -> {
            ensureOpen();
            int deleted;
            try (PreparedStatement trades = connection.prepareStatement("DELETE FROM villager_trades WHERE timestamp < ?");
                 PreparedStatement memories = connection.prepareStatement("DELETE FROM player_memories WHERE last_interaction < ?")) {
                connection.setAutoCommit(false);
                try {
                    trades.setLong(1, retentionCutoff(now));
                    deleted = trades.executeUpdate();
                    memories.setLong(1, retentionCutoff(now));
                    deleted += memories.executeUpdate();
                    connection.commit();
                } catch (SQLException e) {
                    connection.rollback();
                    throw e;
                } finally {
                    connection.setAutoCommit(true);
                }
            } catch (SQLException e) {
                throw new VillagerDatabaseException("Error purging expired villager memories", e);
            }
            return deleted;
        });
    }

    public CompletableFuture<Integer> clearMemoryPair(UUID villagerUuid, UUID playerUuid) {
        return submit(() -> {
            ensureOpen();
            try (PreparedStatement memory = connection.prepareStatement("DELETE FROM player_memories WHERE villager_uuid = ? AND player_uuid = ?");
                 PreparedStatement trades = connection.prepareStatement("DELETE FROM villager_trades WHERE villager_uuid = ? AND player_uuid = ?")) {
                connection.setAutoCommit(false);
                try {
                    memory.setString(1, villagerUuid.toString());
                    memory.setString(2, playerUuid.toString());
                    int count = memory.executeUpdate();
                    trades.setString(1, villagerUuid.toString());
                    trades.setString(2, playerUuid.toString());
                    count += trades.executeUpdate();
                    connection.commit();
                    return count;
                } catch (SQLException e) {
                    connection.rollback();
                    throw e;
                } finally {
                    connection.setAutoCommit(true);
                }
            } catch (SQLException e) {
                throw new VillagerDatabaseException("Error clearing villager memory pair", e);
            }
        });
    }

    public CompletableFuture<MemoryInspection> inspectMemoryPair(UUID villagerUuid, UUID playerUuid, long now) {
        return getEligiblePlayerMemory(villagerUuid, playerUuid, now)
                .thenCombine(getRecentTrades(villagerUuid, playerUuid, 5, now), MemoryInspection::new);
    }

    public record MemoryInspection(Optional<PlayerMemory> memory, List<TradeRecord> trades) {}

    public CompletableFuture<Integer> getVillagerCount() {
        return submit(() -> {
            ensureOpen();
            try (Statement st = connection.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM villagers")) {
                if (rs.next()) return rs.getInt(1);
            } catch (SQLException e) {
                // A failed count is not "there are no villagers": report it instead of returning 0.
                throw new VillagerDatabaseException("Error counting villagers", e);
            }
            return 0;
        });
    }

    /**
     * Makes sure the connection is usable. Work that was accepted before the shutdown started may
     * still run against the open connection; once the shutdown began no new connection is made.
     */
    private synchronized void ensureOpen() {
        if (initialized && connection != null && !isClosed(connection)) {
            return;
        }
        if (closing) {
            throw new VillagerDatabaseException("Villager database is closing");
        }
        try {
            initialize();
        } catch (SQLException e) {
            throw new VillagerDatabaseException("Failed to open SQLite database", e);
        }
    }

    private static boolean isClosed(Connection connection) {
        try {
            return connection.isClosed();
        } catch (SQLException e) {
            return true;
        }
    }

    private VillagerProfile mapVillager(ResultSet rs) throws SQLException {
        return new VillagerProfile(
                UUID.fromString(rs.getString("uuid")),
                rs.getString("name"),
                rs.getString("profession"),
                rs.getString("biome"),
                VillagerProfile.parseTraitsJson(rs.getString("traits")),
                rs.getString("quirk"),
                rs.getString("backstory"),
                rs.getLong("created_at"),
                rs.getLong("last_seen")
        );
    }

    private PlayerMemory mapPlayerMemory(ResultSet rs) throws SQLException {
        return new PlayerMemory(
                UUID.fromString(rs.getString("villager_uuid")),
                UUID.fromString(rs.getString("player_uuid")),
                rs.getInt("sentiment"),
                rs.getInt("interaction_count"),
                rs.getLong("total_spent"),
                rs.getLong("last_interaction"),
                PlayerMemory.parseEventsJson(rs.getString("recent_events"))
        );
    }

    /**
     * Shuts the database down in order: new work is refused, work that was already accepted is
     * drained, and only then is the connection closed. Closing the connection first would let a
     * queued write run against a closed connection, and would let a late task reconnect to a
     * database that is going away.
     */
    @Override
    public void close() {
        closing = true;
        dbExecutor.shutdown();
        if (!awaitDrain()) {
            LOGGER.warn("[EconomyCraft-DB] Villager database work did not finish within {}s; interrupting it.",
                    shutdownTimeoutSeconds);
            dbExecutor.shutdownNow();
            failAbandonedWork();
        }
        synchronized (this) {
            closeConnection();
        }
        LOGGER.info("[EconomyCraft-DB] Villager SQLite database connection closed.");
    }

    /**
     * Fails the work that was accepted but will never report a result. A task the executor removed
     * from its queue during the shutdown never completes its future, so a caller waiting on it
     * would wait forever. Work that was still running is failed too: the database is going away,
     * so its outcome is unknown.
     */
    private void failAbandonedWork() {
        for (CompletableFuture<?> future : acceptedWork) {
            future.completeExceptionally(new VillagerDatabaseException(
                    "Villager database closed before the work finished"));
        }
        acceptedWork.clear();
    }

    private boolean awaitDrain() {
        try {
            return dbExecutor.awaitTermination(shutdownTimeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void closeConnection() {
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

    /**
     * Runs a read on the database thread. A database that is shutting down refuses the work with a
     * failed future instead of throwing at the call site.
     *
     * <p>The future is tracked while the work is in flight, so a shutdown that abandons it can
     * fail it instead of leaving the caller waiting for a result that never comes.
     */
    private <T> CompletableFuture<T> submit(Supplier<T> task) {
        if (closing) {
            return CompletableFuture.failedFuture(new VillagerDatabaseException("Villager database is closing"));
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        acceptedWork.add(future);
        future.whenComplete((result, error) -> acceptedWork.remove(future));
        try {
            dbExecutor.execute(() -> {
                try {
                    future.complete(task.get());
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            });
        } catch (RejectedExecutionException e) {
            future.completeExceptionally(new VillagerDatabaseException("Villager database is closing", e));
        }
        return future;
    }

    private CompletableFuture<Void> run(Runnable task) {
        return submit(() -> {
            task.run();
            return null;
        });
    }
}
