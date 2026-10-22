package com.reazip.economycraft.gossip;

import com.reazip.economycraft.gossip.identity.VillagerSeeder;
import com.reazip.economycraft.gossip.memory.VillagerMemoryService;
import com.reazip.economycraft.gossip.storage.PlayerMemory;
import com.reazip.economycraft.gossip.storage.VillagerDatabase;
import com.reazip.economycraft.gossip.storage.VillagerDatabaseException;
import com.reazip.economycraft.gossip.storage.VillagerProfile;
import com.reazip.economycraft.util.EconomyExecutors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reliability of the villager profile lifecycle: a failed lookup must never look like a missing
 * profile, the first interaction must use the stored persona, and a shutdown must not race with
 * work the database already accepted.
 */
@DisplayName("Villager profile persistence reliability")
class VillagerProfileReliabilityTest {

    private static final long TIMEOUT_SECONDS = 10L;

    @Test
    @DisplayName("A failed lookup keeps the stored profile and writes no replacement")
    void failedLookupPreservesStoredProfile() throws Exception {
        VillagerDatabase store = VillagerDatabase.createInMemory();
        store.initialize();
        try {
            UUID uuid = UUID.randomUUID();
            VillagerProfile stored = profile(uuid, "Barnaby");
            store.saveVillager(stored).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            FailingReadDatabase failing = new FailingReadDatabase(store);
            VillagerMemoryService service = newService(failing);
            VillagerProfile seed = VillagerSeeder.createSeededProfile(uuid, "farmer", "plains", 3000L);

            CompletableFuture<VillagerProfile> lookup = service.resolveProfile(uuid, () -> seed);
            assertThrows(ExecutionException.class, () -> lookup.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));

            // The stored persona survived the failed read: it was neither overwritten nor dropped.
            Optional<VillagerProfile> after = store.getVillager(uuid).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertTrue(after.isPresent());
            assertEquals(stored, after.get());
            assertEquals("Barnaby", after.get().name());

            // The failed fallback is not cached as the answer, so the next attempt reads again.
            assertThrows(ExecutionException.class,
                    () -> service.resolveProfile(uuid, () -> seed).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertEquals(2, failing.readAttempts.get());
        } finally {
            store.close();
        }
    }

    @Test
    @DisplayName("A failed lookup creates no profile for a villager that has none")
    void failedLookupCreatesNoProfile() throws Exception {
        VillagerDatabase store = VillagerDatabase.createInMemory();
        store.initialize();
        try {
            FailingReadDatabase failing = new FailingReadDatabase(store);
            VillagerMemoryService service = newService(failing);
            UUID uuid = UUID.randomUUID();
            VillagerProfile seed = VillagerSeeder.createSeededProfile(uuid, "farmer", "plains", 3000L);

            assertThrows(ExecutionException.class,
                    () -> service.resolveProfile(uuid, () -> seed).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));

            assertTrue(store.getVillager(uuid).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).isEmpty());
        } finally {
            store.close();
        }
    }

    @Test
    @DisplayName("The first interaction uses the stored persona, not the seeded one")
    void firstInteractionUsesStoredProfile() throws Exception {
        VillagerDatabase store = VillagerDatabase.createInMemory();
        store.initialize();
        try {
            UUID uuid = UUID.randomUUID();
            VillagerProfile stored = profile(uuid, "Barnaby");
            store.saveVillager(stored).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            VillagerMemoryService service = newService(store);
            VillagerProfile seed = VillagerSeeder.createSeededProfile(uuid, "farmer", "plains", 3000L);

            VillagerProfile resolved = service.resolveProfile(uuid, () -> seed).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            assertEquals(stored, resolved);
            assertEquals("Barnaby", resolved.name());
            assertEquals("blacksmith", resolved.profession());
            // The seeded persona never replaced the stored one.
            assertEquals(stored, storedProfile(store, uuid));
        } finally {
            store.close();
        }
    }

    @Test
    @DisplayName("A villager without a stored profile is created once and then reused")
    void newVillagerGetsItsProfilePersisted() throws Exception {
        VillagerDatabase store = VillagerDatabase.createInMemory();
        store.initialize();
        try {
            VillagerMemoryService service = newService(store);
            UUID uuid = UUID.randomUUID();
            VillagerProfile seed = VillagerSeeder.createSeededProfile(uuid, "farmer", "plains", 3000L);

            VillagerProfile resolved = service.resolveProfile(uuid, () -> seed).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            assertEquals(seed, resolved);
            assertEquals(seed, storedProfile(store, uuid));
            assertEquals(1, store.getVillagerCount().get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        } finally {
            store.close();
        }
    }

    @Test
    @DisplayName("A failed write is reported through the future")
    void writeFailuresPropagate(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("economycraft.db");
        VillagerDatabase store = new VillagerDatabase(file);
        store.initialize();
        try {
            // Break the schema behind the database's back so the next write fails.
            try (Connection raw = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
                 Statement st = raw.createStatement()) {
                st.execute("DROP TABLE villagers");
            }

            UUID uuid = UUID.randomUUID();
            ExecutionException writeError = assertThrows(ExecutionException.class,
                    () -> store.saveVillager(profile(uuid, "Barnaby")).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertInstanceOf(VillagerDatabaseException.class, writeError.getCause());

            // A failed read is reported as a failure, not as "no such villager".
            ExecutionException readError = assertThrows(ExecutionException.class,
                    () -> store.getVillager(uuid).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertInstanceOf(VillagerDatabaseException.class, readError.getCause());
        } finally {
            store.close();
        }
    }

    @Test
    @DisplayName("A memory write that breaks a foreign key is reported through the future")
    void memoryWriteFailuresPropagate() throws Exception {
        VillagerDatabase store = VillagerDatabase.createInMemory();
        store.initialize();
        try {
            UUID unknownVillager = UUID.randomUUID();
            UUID player = UUID.randomUUID();
            PlayerMemory memory = PlayerMemory.createDefault(unknownVillager, player, System.currentTimeMillis());

            ExecutionException error = assertThrows(ExecutionException.class,
                    () -> store.savePlayerMemory(memory).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertInstanceOf(VillagerDatabaseException.class, error.getCause());
        } finally {
            store.close();
        }
    }

    @Test
    @DisplayName("Shutdown drains accepted work before the connection closes")
    void shutdownDrainsQueuedWork() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        VillagerDatabase store = new VillagerDatabase("jdbc:sqlite::memory:", executor);
        store.initialize();

        // Occupy the database thread so the write below has to wait in the queue.
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        executor.execute(() -> {
            blockerStarted.countDown();
            try {
                releaseBlocker.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(blockerStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));

        CompletableFuture<Void> queued = store.saveVillager(profile(UUID.randomUUID(), "Barnaby"));

        Thread closer = new Thread(store::close, "villager-db-close");
        closer.start();
        closer.join(500L);
        assertTrue(closer.isAlive(), "close() returned before the queued write finished");

        releaseBlocker.countDown();
        closer.join(TIMEOUT_SECONDS * 1000L);
        assertFalse(closer.isAlive(), "close() did not return after the queued write finished");

        // The queued write ran against the still-open connection.
        assertDoesNotThrow(() -> queued.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));

        // A closed database refuses new work instead of reconnecting.
        ExecutionException error = assertThrows(ExecutionException.class,
                () -> store.saveVillager(profile(UUID.randomUUID(), "Barnaby")).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertInstanceOf(VillagerDatabaseException.class, error.getCause());
    }

    @Test
    @DisplayName("Work the shutdown abandons fails instead of waiting forever")
    void abandonedWorkFailsOnShutdown() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        VillagerDatabase store = new VillagerDatabase("jdbc:sqlite::memory:", executor, 1L);
        store.initialize();

        // Occupy the database thread so the write below stays queued when the shutdown starts.
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        executor.execute(() -> {
            blockerStarted.countDown();
            try {
                releaseBlocker.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(blockerStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));

        CompletableFuture<Void> queued = store.saveVillager(profile(UUID.randomUUID(), "Barnaby"));

        Thread closer = new Thread(store::close, "villager-db-abandon");
        closer.start();
        closer.join(TIMEOUT_SECONDS * 1000L);
        assertFalse(closer.isAlive(), "close() did not return after the drain timed out");

        // The queued write was abandoned, so its caller must learn that instead of waiting.
        ExecutionException error = assertThrows(ExecutionException.class,
                () -> queued.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertInstanceOf(VillagerDatabaseException.class, error.getCause());

        releaseBlocker.countDown();
    }

    @Test
    @DisplayName("Two connections to the same file can write at the same time")
    void concurrentWritesOnSharedFileSucceed(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("economycraft.db");
        VillagerDatabase first = new VillagerDatabase(file);
        VillagerDatabase second = new VillagerDatabase(file);
        try {
            first.initialize();
            second.initialize();

            // Hold the write lock on the shared file so both connections have to wait for it,
            // the way two overlapping writes on the gossip and the primary connection do.
            try (Connection holder = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath())) {
                holder.setAutoCommit(false);
                try (Statement st = holder.createStatement()) {
                    st.execute("INSERT INTO villagers (uuid, name, profession, biome, traits, quirk, backstory, created_at, last_seen)"
                            + " VALUES ('lock-holder', 'Lock', 'farmer', 'plains', '[]', '', '', 0, 0)");
                }

                CompletableFuture<Void> writeA = first.saveVillager(profile(UUID.randomUUID(), "Barnaby"));
                CompletableFuture<Void> writeB = second.saveVillager(profile(UUID.randomUUID(), "Cedric"));

                // The JDBC driver falls back to a three second busy timeout; the gossip connection
                // is configured with the five second timeout the primary connection uses. Holding
                // the lock past three seconds shows which one is in effect: a write that gives up
                // early fails instead of waiting for the lock to clear.
                Thread.sleep(3500L);
                assertFalse(writeA.isDone(), "the write gave up before the busy timeout expired");
                assertFalse(writeB.isDone(), "the write gave up before the busy timeout expired");

                holder.commit();
                holder.setAutoCommit(true);

                assertDoesNotThrow(() -> CompletableFuture.allOf(writeA, writeB).get(30L, TimeUnit.SECONDS));
            }

            assertEquals(3, first.getVillagerCount().get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertEquals(3, second.getVillagerCount().get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        } finally {
            first.close();
            second.close();
        }
    }

    private static VillagerProfile profile(UUID uuid, String name) {
        return new VillagerProfile(uuid, name, "blacksmith", "plains",
                List.of("grumpy", "perfectionist"), "Inspects every emerald", "Former royal apprentice", 1000L, 2000L);
    }

    private static VillagerProfile storedProfile(VillagerDatabase database, UUID uuid) throws Exception {
        Optional<VillagerProfile> found = database.getVillager(uuid).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue(found.isPresent(), "no profile was stored for " + uuid);
        return found.get();
    }

    private static VillagerMemoryService newService(VillagerDatabase database) {
        return new VillagerMemoryService(
                database,
                new GossipApiClient(GossipConfig.createDefault()),
                GossipConfig::createDefault,
                () -> 1.0,
                null,
                null);
    }

    /**
     * A database whose profile reads always fail while its writes go to a real database, so a
     * failed lookup can be told apart from a missing profile.
     */
    private static final class FailingReadDatabase extends VillagerDatabase {
        private final VillagerDatabase delegate;
        private final AtomicInteger readAttempts = new AtomicInteger();

        FailingReadDatabase(VillagerDatabase delegate) {
            super("jdbc:sqlite::memory:", EconomyExecutors.newSingleThreadScheduledExecutor("EconomyCraft-Villager-FailingReadDB"));
            this.delegate = delegate;
        }

        @Override
        public CompletableFuture<Optional<VillagerProfile>> getVillager(UUID uuid) {
            readAttempts.incrementAndGet();
            return CompletableFuture.failedFuture(new VillagerDatabaseException("simulated read failure"));
        }

        @Override
        public CompletableFuture<Void> saveVillager(VillagerProfile profile) {
            return delegate.saveVillager(profile);
        }
    }
}
