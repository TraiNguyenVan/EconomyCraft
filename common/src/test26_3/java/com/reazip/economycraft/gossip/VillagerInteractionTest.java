package com.reazip.economycraft.gossip;

import com.reazip.economycraft.gossip.memory.IndividualDialogueResult;
import com.reazip.economycraft.gossip.memory.TradeOfferSnapshot;
import com.reazip.economycraft.gossip.memory.VillagerMemoryService;
import com.reazip.economycraft.gossip.storage.PlayerMemory;
import com.reazip.economycraft.gossip.storage.TradeRecord;
import com.reazip.economycraft.gossip.storage.VillagerDatabase;
import com.reazip.economycraft.gossip.storage.VillagerDatabaseException;
import com.reazip.economycraft.gossip.storage.VillagerProfile;
import com.reazip.economycraft.util.EconomyExecutors;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerData;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.npc.villager.VillagerType;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The interaction path of the villager dialogue: the line must be written from the stored persona,
 * and it must not be written before the database answered with it.
 */
@DisplayName("Villager dialogue interaction")
class VillagerInteractionTest {

    private static final long TIMEOUT_SECONDS = 10L;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    @DisplayName("The first interaction waits for the stored persona and writes from it")
    void firstInteractionWaitsForTheStoredProfile() throws Exception {
        UUID villagerUuid = UUID.randomUUID();
        VillagerProfile stored = storedProfile(villagerUuid, "Barnaby");

        DelayedLookupDatabase database = new DelayedLookupDatabase();
        CapturingApiClient client = new CapturingApiClient();
        VillagerMemoryService service = service(database, client);

        AtomicReference<Component> nameplate = new AtomicReference<>();
        Villager villager = mockVillager(villagerUuid, nameplate);
        ServerPlayer player = mockPlayer();

        CompletableFuture<Optional<String>> interaction = service.handleInteraction(player, villager);

        // The lookup has not answered yet, so no dialogue request may have started.
        assertEquals(0, client.requests.get(), "a dialogue request started before the profile was known");

        database.answerNextLookup(Optional.of(stored));

        Optional<String> line = interaction.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue(line.isPresent());
        assertEquals(1, client.requests.get());

        // The line was written from the stored persona, not from the seeded one.
        assertNotNull(client.requestedProfile.get());
        assertEquals(stored, client.requestedProfile.get());
        assertEquals("Barnaby", client.requestedProfile.get().name());

        // The nameplate follows the stored persona too.
        assertNotNull(nameplate.get());
        assertEquals("Barnaby", nameplate.get().getString());
    }

    @Test
    @DisplayName("A nameplate the service applied is replaced by the stored name after a failed lookup")
    void seededNameplateIsCorrectedAfterARetry() throws Exception {
        UUID villagerUuid = UUID.randomUUID();
        VillagerProfile stored = storedProfile(villagerUuid, "Barnaby");

        DelayedLookupDatabase database = new DelayedLookupDatabase();
        CapturingApiClient client = new CapturingApiClient();
        VillagerMemoryService service = service(database, client);

        AtomicReference<Component> nameplate = new AtomicReference<>();
        Villager villager = mockVillager(villagerUuid, nameplate);
        ServerPlayer player = mockPlayer();

        // The first lookup fails: the villager keeps the seeded name.
        CompletableFuture<Optional<String>> failed = service.handleInteraction(player, villager);
        database.failNextLookup(new VillagerDatabaseException("simulated read failure"));
        assertTrue(failed.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).isEmpty());

        assertNotNull(nameplate.get());
        assertTrue(!nameplate.get().getString().isBlank(), "the villager has no nameplate at all");

        // The retry finds the stored persona and replaces the name the service applied.
        CompletableFuture<Optional<String>> retry = service.handleInteraction(player, villager);
        database.answerNextLookup(Optional.of(stored));
        assertTrue(retry.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).isPresent());

        assertEquals("Barnaby", nameplate.get().getString());
    }

    @Test
    @DisplayName("A name a player applied is not overwritten by the stored name")
    void playerNameTagIsNotOverwritten() throws Exception {
        UUID villagerUuid = UUID.randomUUID();
        VillagerProfile stored = storedProfile(villagerUuid, "Barnaby");

        DelayedLookupDatabase database = new DelayedLookupDatabase();
        CapturingApiClient client = new CapturingApiClient();
        VillagerMemoryService service = service(database, client);

        AtomicReference<Component> nameplate = new AtomicReference<>();
        Villager villager = mockVillager(villagerUuid, nameplate);
        ServerPlayer player = mockPlayer();

        CompletableFuture<Optional<String>> interaction = service.handleInteraction(player, villager);

        // A player applies a name tag while the lookup is still pending.
        nameplate.set(Component.literal("Bob"));

        database.answerNextLookup(Optional.of(stored));
        assertTrue(interaction.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).isPresent());

        assertEquals("Bob", nameplate.get().getString());
    }

    @Test
    @DisplayName("An interaction survives a memory lookup that completes before the load is shared")
    void instantMemoryLookupDoesNotBreakTheInteraction() throws Exception {
        UUID villagerUuid = UUID.randomUUID();
        VillagerProfile stored = storedProfile(villagerUuid, "Barnaby");

        DelayedLookupDatabase database = new InstantMemoryLookupDatabase();
        CapturingApiClient client = new CapturingApiClient();
        VillagerMemoryService service = service(database, client);

        AtomicReference<Component> nameplate = new AtomicReference<>();
        Villager villager = mockVillager(villagerUuid, nameplate);
        ServerPlayer player = mockPlayer();

        CompletableFuture<Optional<String>> interaction = service.handleInteraction(player, villager);

        database.answerNextLookup(Optional.of(stored));

        Optional<String> line = interaction.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue(line.isPresent(), "the interaction was dropped: see the memory load cleanup in VillagerMemoryService.loadMemory");
        assertEquals(1, client.requests.get());
    }

    private static VillagerMemoryService service(VillagerDatabase database, GossipApiClient client) {
        return new VillagerMemoryService(
                database,
                client,
                GossipConfig::createDefault,
                () -> 1.0,
                null,
                null);
    }

    private static VillagerProfile storedProfile(UUID uuid, String name) {
        return new VillagerProfile(uuid, name, "blacksmith", "plains",
                List.of("grumpy"), "Inspects every emerald", "Former royal apprentice", 1000L, 2000L);
    }

    private static Villager mockVillager(UUID uuid, AtomicReference<Component> nameplate) {
        MinecraftServer server = mockServer();
        Level level = mock(Level.class);
        when(level.getServer()).thenReturn(server);

        Villager villager = mock(Villager.class);
        when(villager.getUUID()).thenReturn(uuid);
        when(villager.getVillagerData()).thenReturn(new VillagerData(
                BuiltInRegistries.VILLAGER_TYPE.getOrThrow(VillagerType.PLAINS),
                BuiltInRegistries.VILLAGER_PROFESSION.getOrThrow(VillagerProfession.FARMER),
                5));
        when(villager.getOffers()).thenReturn(null);
        doAnswer(invocation -> {
            nameplate.set(invocation.getArgument(0));
            return null;
        }).when(villager).setCustomName(any());
        when(villager.getCustomName()).thenAnswer(invocation -> nameplate.get());
        when(villager.level()).thenReturn(level);
        return villager;
    }

    private static ServerPlayer mockPlayer() {
        MinecraftServer server = mockServer();
        ServerLevel level = mock(ServerLevel.class);
        when(level.getServer()).thenReturn(server);

        ServerPlayer player = mock(ServerPlayer.class);
        when(player.getUUID()).thenReturn(UUID.randomUUID());
        when(player.getScoreboardName()).thenReturn("Steve");
        when(player.level()).thenReturn(level);
        return player;
    }

    /** A server whose scheduled work runs inline, so the test stays on one thread. */
    private static MinecraftServer mockServer() {
        MinecraftServer server = mock(MinecraftServer.class);
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(server).execute(any(Runnable.class));
        return server;
    }

    /**
     * A database whose profile lookup answers only when the test says so, so the interaction can
     * be observed while the persona is still unknown. Every lookup is answered on its own, so a
     * retry after a failure gets its own answer.
     */
    private static class DelayedLookupDatabase extends VillagerDatabase {
        private final Queue<CompletableFuture<Optional<VillagerProfile>>> lookups = new ConcurrentLinkedQueue<>();

        DelayedLookupDatabase() {
            super("jdbc:sqlite::memory:", EconomyExecutors.newSingleThreadScheduledExecutor("EconomyCraft-Villager-TestDB"));
        }

        @Override
        public CompletableFuture<Optional<VillagerProfile>> getVillager(UUID uuid) {
            CompletableFuture<Optional<VillagerProfile>> next = new CompletableFuture<>();
            lookups.add(next);
            return next;
        }

        void answerNextLookup(Optional<VillagerProfile> profile) {
            CompletableFuture<Optional<VillagerProfile>> next = lookups.poll();
            assertNotNull(next, "no profile lookup was started");
            next.complete(profile);
        }

        void failNextLookup(Throwable error) {
            CompletableFuture<Optional<VillagerProfile>> next = lookups.poll();
            assertNotNull(next, "no profile lookup was started");
            next.completeExceptionally(error);
        }
    }

    /**
     * A database whose memory read completes the moment it is asked, before the service can even
     * finish wiring its load bookkeeping. Reproduces the ConcurrentHashMap recursive update that
     * used to drop interactions when the cleanup callback was registered inside the
     * computeIfAbsent mapping function.
     */
    private static final class InstantMemoryLookupDatabase extends DelayedLookupDatabase {
        @Override
        public CompletableFuture<Optional<PlayerMemory>> getEligiblePlayerMemory(UUID villagerUuid, UUID playerUuid, long now) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
    }

    /** An API client that records the persona every dialogue request was written from. */
    private static final class CapturingApiClient extends GossipApiClient {
        private final AtomicInteger requests = new AtomicInteger();
        private final AtomicReference<VillagerProfile> requestedProfile = new AtomicReference<>();

        CapturingApiClient() {
            super(GossipConfig.createDefault());
        }

        @Override
        public CompletableFuture<Optional<IndividualDialogueResult>> generateIndividualDialogue(
                VillagerProfile profile,
                PlayerMemory memory,
                String playerArchetype,
                double inflation,
                @Nullable List<String> recentSpokenTopics,
                @Nullable List<TradeOfferSnapshot> currentOffers,
                @Nullable List<TradeRecord> tradeHistory
        ) {
            requests.incrementAndGet();
            requestedProfile.set(profile);
            return CompletableFuture.completedFuture(Optional.of(new IndividualDialogueResult("Mind the ledgers.", 1)));
        }
    }
}
