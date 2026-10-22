package com.reazip.economycraft.gossip.memory;

import com.mojang.logging.LogUtils;
import com.reazip.economycraft.EconomyCraft;
import com.reazip.economycraft.gossip.*;
import com.reazip.economycraft.gossip.identity.VillagerSeeder;
import com.reazip.economycraft.gossip.storage.PlayerMemory;
import com.reazip.economycraft.gossip.storage.VillagerDatabase;
import com.reazip.economycraft.gossip.storage.VillagerProfile;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.villager.Villager;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.DoubleSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Service managing individual villager personalities, player relationship memories,
 * and the asynchronous dialogue generation pipeline.
 */
public class VillagerMemoryService {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final VillagerDatabase database;
    private final GossipApiClient apiClient;
    private final Supplier<GossipConfig> configSupplier;
    private final DoubleSupplier inflationSupplier;
    private final @Nullable Function<UUID, String> factionResolver;
    private final @Nullable com.reazip.economycraft.gossip.RecentSpokenTracker recentSpokenTracker;

    private final Map<UUID, VillagerProfile> profileCache = new ConcurrentHashMap<>();
    private final Map<UUID, VillagerProfile> fallbackProfiles = new ConcurrentHashMap<>();
    private final Map<UUID, CompletableFuture<VillagerProfile>> profileLoads = new ConcurrentHashMap<>();
    private final Map<UUID, String> serviceNameplates = new ConcurrentHashMap<>();
    private final Map<String, PlayerMemory> memoryCache = new ConcurrentHashMap<>();
    private final Map<String, Long> pairGenerations = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<PlayerMemory>> memoryLoads = new ConcurrentHashMap<>();
    private final Map<String, Object> pairLocks = new ConcurrentHashMap<>();
    private final Map<String, Long> memoryReadFailures = new ConcurrentHashMap<>();

    public VillagerMemoryService(
            VillagerDatabase database,
            GossipApiClient apiClient,
            Supplier<GossipConfig> configSupplier,
            DoubleSupplier inflationSupplier,
            @Nullable Function<UUID, String> factionResolver,
            @Nullable com.reazip.economycraft.gossip.RecentSpokenTracker recentSpokenTracker
    ) {
        this.database = database;
        this.apiClient = apiClient;
        this.configSupplier = configSupplier;
        this.inflationSupplier = inflationSupplier;
        this.factionResolver = factionResolver;
        this.recentSpokenTracker = recentSpokenTracker;
    }

    private String memoryKey(UUID villagerUuid, UUID playerUuid) {
        return villagerUuid.toString() + ":" + playerUuid.toString();
    }

    private Object pairLock(String key) {
        return pairLocks.computeIfAbsent(key, ignored -> new Object());
    }

    /**
     * Gets or synchronously creates the in-memory/seeded VillagerProfile for an entity,
     * ensuring that overhead nameplates and identity are immediately available without tick delay.
     *
     * <p>The seeded persona is a fallback only: it is never authoritative until the database
     * confirms that no profile exists, so it must not be used as dialogue context.
     */
    public VillagerProfile getOrInitializeProfile(Villager villager) {
        UUID uuid = villager.getUUID();
        VillagerProfile cached = profileCache.get(uuid);
        if (cached != null) return cached;

        VillagerProfile fallback = fallbackProfiles.get(uuid);
        if (fallback != null) return fallback;

        VillagerProfile seeded = seedProfile(villager);
        fallbackProfiles.put(uuid, seeded);

        // Asynchronously resolve the persisted profile. The seeded persona is only written once
        // the database confirms that this villager has no profile yet.
        loadProfile(uuid, seeded);

        return seeded;
    }

    /**
     * Resolves the persisted profile of a villager, waiting for the database lookup to finish.
     *
     * <p>The returned future completes with the stored persona when one exists, with the seeded
     * persona when the villager is genuinely new, and fails when the lookup itself failed. A
     * failed lookup never writes a profile and never leaves the seeded fallback cached as if it
     * were the persona, so the next attempt retries instead of overwriting saved data.
     *
     * @param uuid        the villager's identity
     * @param seedFactory builds the persona used for a villager that has no stored profile
     */
    public CompletableFuture<VillagerProfile> resolveProfile(UUID uuid, Supplier<VillagerProfile> seedFactory) {
        VillagerProfile cached = profileCache.get(uuid);
        if (cached != null) return CompletableFuture.completedFuture(cached);

        VillagerProfile seed = fallbackProfiles.computeIfAbsent(uuid, ignored -> seedFactory.get());
        return loadProfile(uuid, seed);
    }

    /**
     * Loads the persisted profile once and shares the result with every caller that asks for the
     * same villager while the load is running.
     */
    private CompletableFuture<VillagerProfile> loadProfile(UUID uuid, VillagerProfile seed) {
        CompletableFuture<VillagerProfile> load = profileLoads.computeIfAbsent(uuid, ignored -> createProfileLoad(uuid, seed));
        load.whenComplete((profile, error) -> profileLoads.remove(uuid, load));
        return load;
    }

    private CompletableFuture<VillagerProfile> createProfileLoad(UUID uuid, VillagerProfile seed) {
        return database.getVillager(uuid)
                .thenCompose(found -> found
                        .map(persisted -> {
                            profileCache.put(uuid, persisted);
                            fallbackProfiles.remove(uuid, seed);
                            return CompletableFuture.completedFuture(persisted);
                        })
                        .orElseGet(() -> database.saveVillager(seed).thenApply(saved -> {
                            profileCache.put(uuid, seed);
                            fallbackProfiles.remove(uuid, seed);
                            return seed;
                        })))
                .whenComplete((profile, error) -> {
                    if (error != null) {
                        // The stored persona is unknown: keep the fallback out of the cache so the
                        // next attempt retries, and never write a replacement over unread data.
                        fallbackProfiles.remove(uuid, seed);
                        LOGGER.warn("[EconomyCraft-AI] Could not resolve the persisted profile for villager {}: {}",
                                uuid, error.getMessage());
                    }
                });
    }

    private VillagerProfile seedProfile(Villager villager) {
        UUID uuid = villager.getUUID();

        // Create deterministic seed profile immediately as non-blocking baseline
        var profHolder = villager.getVillagerData().profession();
        com.reazip.economycraft.util.IdentifierCompat.Id profId = profHolder.unwrapKey()
                .map(com.reazip.economycraft.util.IdentifierCompat::fromResourceKey)
                .orElse(null);
        String profession = profId != null ? profId.path() : "general";

        var typeHolder = villager.getVillagerData().type();
        com.reazip.economycraft.util.IdentifierCompat.Id typeId = typeHolder.unwrapKey()
                .map(com.reazip.economycraft.util.IdentifierCompat::fromResourceKey)
                .orElse(null);
        String biome = typeId != null ? typeId.path() : "plains";

        return VillagerSeeder.createSeededProfile(uuid, profession, biome, System.currentTimeMillis());
    }

    /**
     * Handles player interaction with a villager, asynchronously requesting personalized dialogue
     * and delivering it via private chat upon completion.
     */
    public CompletableFuture<Optional<String>> handleInteraction(ServerPlayer player, Villager villager) {
        GossipConfig config = configSupplier.get();
        if (config == null || !config.enabled()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        UUID villagerUuid = villager.getUUID();
        UUID playerUuid = player.getUUID();
        String key = memoryKey(villagerUuid, playerUuid);

        // The seeded persona is enough for an immediate nameplate; the persisted persona replaces
        // it below once the database lookup finishes.
        VillagerProfile fallback = getOrInitializeProfile(villager);
        if (villager.getCustomName() == null) {
            villager.setCustomName(Component.literal(fallback.name()).withStyle(ChatFormatting.YELLOW));
            villager.setCustomNameVisible(false); // standard nametag visibility on look
            serviceNameplates.put(villagerUuid, fallback.name());
        }

        // Resolve player archetype
        String faction = factionResolver != null ? factionResolver.apply(playerUuid) : null;
        String playerName = player.getScoreboardName();
        String archetype = TransactionAnonymizer.resolveArchetype(playerUuid, faction, 0, playerName);

        double inflation = inflationSupplier.getAsDouble();
        List<String> recentSpoken = (recentSpokenTracker != null) ? recentSpokenTracker.getRecentSpoken() : List.of();

        // Safely extract main-thread trade snapshot from villager entity (up to 10 covers full Master tier)
        List<TradeOfferSnapshot> offers = TradeOfferSnapshot.fromOffers(villager.getOffers(), 10);

        // Fetch recent trade history asynchronously before requesting dialogue
        long generation = pairGenerations.getOrDefault(key, 0L);

        // The dialogue must not be written from the seeded persona: a stored profile may exist, so
        // wait for the lookup before treating any persona as the villager's identity.
        return resolveProfile(villagerUuid, () -> fallback)
                .thenCompose(profile -> {
                    applyNameplate(villager, villagerUuid, profile);
                    return loadMemory(villagerUuid, playerUuid, key, generation)
                            .thenCombine(database.getRecentTrades(villagerUuid, playerUuid, 5).exceptionally(error -> {
                                LOGGER.warn("[EconomyCraft-AI] Trade history read failed; continuing without trade context: {}", error.getMessage());
                                return List.of();
                            }), (memory, trades) -> Map.entry(memory, trades))
                            .thenCompose(context -> {
                                PlayerMemory memory = context.getKey();
                                List<com.reazip.economycraft.gossip.storage.TradeRecord> trades = context.getValue().stream()
                                        .filter(t -> !t.toPromptDescription().isBlank()).toList();
                                LOGGER.info("[EconomyCraft-AI] Villager {} ({}) interacting with player {}. Extracted {} offer(s), {} past trade(s).",
                                        profile.name(), profile.profession(), playerName, offers.size(), trades.size());
                                return apiClient.generateIndividualDialogue(
                                        profile, memory, archetype, inflation, recentSpoken, offers, trades)
                                        .thenApply(result -> new DialogueContext(result, memory, profile));
                            });
                })
                .thenApply(contextual -> {
                    Optional<IndividualDialogueResult> optResult = contextual.result();
                    PlayerMemory contextMemory = contextual.memory();
                    VillagerProfile profile = contextual.profile();
                    synchronized (pairLock(key)) {
                    if (pairGenerations.getOrDefault(key, 0L) != generation) return Optional.<String>empty();
                    if (optResult.isEmpty() || optResult.get().isEmpty()) {
                        return Optional.<String>empty();
                    }

                    IndividualDialogueResult result = optResult.get();
                    LOGGER.info("[EconomyCraft-AI] Villager {} ({}) replied to {}: \"{}\"",
                            profile.name(), profile.profession(), playerName, result.dialogue());

                    if (recentSpokenTracker != null) {
                        recentSpokenTracker.recordSpoken(result.dialogue());
                    }

                    // Update memory
                    PlayerMemory updatedMemory = contextMemory.withInteraction(
                            result.sentimentDelta(),
                            "Visited stall",
                            System.currentTimeMillis()
                    );
                    if (!Objects.equals(memoryReadFailures.get(key), generation)) {
                        memoryCache.put(key, updatedMemory);
                        database.savePlayerMemory(updatedMemory).exceptionally(error -> {
                            LOGGER.warn("[EconomyCraft-AI] Could not persist player memory: {}", error.getMessage());
                            return null;
                        });
                    }

                    // Deliver message to player
                    var server = player.level().getServer();
                    if (server != null) {
                        server.execute(() -> {
                            Component formatted = formatVillagerSpeech(profile, result.dialogue());
                            synchronized (pairLock(key)) {
                                if (pairGenerations.getOrDefault(key, 0L) == generation) player.sendSystemMessage(formatted);
                            }
                        });
                    }

                    return Optional.of(result.dialogue());
                    }
                })
                .exceptionally(error -> {
                    LOGGER.warn("[EconomyCraft-AI] Could not produce villager dialogue for {}: {}", villagerUuid, error.getMessage());
                    return Optional.<String>empty();
                });
    }

    /**
     * Applies the persisted persona's name to the villager's nameplate. The entity lives on the
     * server thread, so the update is scheduled there.
     *
     * <p>Only a nameplate this service put there is replaced. A name a player applied with a name
     * tag is left alone, and a name this service applied is replaced even when it is still the
     * seeded one, so a lookup that failed earlier does not pin the villager to its seed name.
     */
    private void applyNameplate(Villager villager, UUID villagerUuid, VillagerProfile profile) {
        var server = villager.level().getServer();
        if (server == null) return;
        server.execute(() -> {
            Component current = villager.getCustomName();
            String applied = serviceNameplates.get(villagerUuid);
            if (current != null && (applied == null || !applied.equals(current.getString()))) {
                // Somebody else named this villager; the name is theirs to keep.
                return;
            }
            villager.setCustomName(Component.literal(profile.name()).withStyle(ChatFormatting.YELLOW));
            villager.setCustomNameVisible(false);
            serviceNameplates.put(villagerUuid, profile.name());
        });
    }

    private CompletableFuture<PlayerMemory> loadMemory(UUID villagerUuid, UUID playerUuid, String key, long generation) {
        PlayerMemory cached = memoryCache.get(key);
        if (cached != null) return CompletableFuture.completedFuture(cached);
        return memoryLoads.computeIfAbsent(key, ignored -> database.getEligiblePlayerMemory(villagerUuid, playerUuid, System.currentTimeMillis())
                .handle((found, error) -> {
                    if (error != null) {
                        LOGGER.warn("[EconomyCraft-AI] Memory read failed; using an empty, non-persisted context: {}", error.getMessage());
                        synchronized (pairLock(key)) {
                            if (pairGenerations.getOrDefault(key, 0L) == generation) memoryReadFailures.put(key, generation);
                        }
                        return PlayerMemory.createDefault(villagerUuid, playerUuid, System.currentTimeMillis());
                    }
                    PlayerMemory memory = found.orElseGet(() -> PlayerMemory.createDefault(villagerUuid, playerUuid, System.currentTimeMillis()));
                    synchronized (pairLock(key)) {
                        if (pairGenerations.getOrDefault(key, 0L) == generation) {
                            memoryReadFailures.remove(key);
                            if (found.isPresent()) memoryCache.put(key, memory);
                        }
                    }
                    return memory;
                }).whenComplete((value, error) -> memoryLoads.remove(key)));
    }

    public CompletableFuture<VillagerDatabase.MemoryInspection> inspectMemory(UUID villagerUuid, UUID playerUuid) {
        return database.inspectMemoryPair(villagerUuid, playerUuid, System.currentTimeMillis())
                .thenApply(inspection -> new VillagerDatabase.MemoryInspection(
                        inspection.memory().map(memory -> memory.withValidatedEvents(memory.recentEvents().stream()
                                .filter(event -> "Visited stall".equalsIgnoreCase(event))
                                .limit(5).toList())),
                        inspection.trades().stream().filter(trade -> !trade.toPromptDescription().isBlank()).limit(5).toList()));
    }

    public CompletableFuture<Integer> clearMemory(UUID villagerUuid, UUID playerUuid) {
        String key = memoryKey(villagerUuid, playerUuid);
        synchronized (pairLock(key)) {
            pairGenerations.merge(key, 1L, Long::sum);
            memoryCache.remove(key);
            memoryLoads.remove(key);
            memoryReadFailures.remove(key);
            return database.clearMemoryPair(villagerUuid, playerUuid);
        }
    }

    /**
     * Records a completed trade transaction into the villager's episodic player memory
     * and persists the detailed record into the SQLite database.
     */
    public void recordTrade(UUID villagerUuid, UUID playerUuid, long amountSpent, @Nullable String itemDescription) {
        recordTrade(villagerUuid, playerUuid, amountSpent, itemDescription, 1);
    }

    /**
     * Records a completed trade transaction into the villager's episodic player memory
     * and persists the detailed record into the SQLite database.
     */
    public void recordTrade(UUID villagerUuid, UUID playerUuid, long amountSpent, @Nullable String itemDescription, int count) {
        String key = memoryKey(villagerUuid, playerUuid);
        long generation = pairGenerations.getOrDefault(key, 0L);
        loadMemory(villagerUuid, playerUuid, key, generation).thenCompose(memory -> {
            long now = System.currentTimeMillis();
            PlayerMemory updated = memory.withTrade(amountSpent, itemDescription, now);
            synchronized (pairLock(key)) {
                if (pairGenerations.getOrDefault(key, 0L) != generation) return CompletableFuture.completedFuture(null);
                String itemName = itemDescription != null && !itemDescription.isBlank() ? itemDescription : "Trade item";
                if (Objects.equals(memoryReadFailures.get(key), generation)) {
                    return database.recordTradeTransaction(villagerUuid, playerUuid, itemName, count, amountSpent, now);
                }
                memoryCache.put(key, updated);
                return database.recordTradeTransaction(updated,
                        itemName,
                        count, amountSpent, now);
            }
        }).exceptionally(error -> {
            LOGGER.warn("[EconomyCraft-AI] Could not persist completed trade memory: {}", error.getMessage());
            return null;
        });
    }

    /**
     * Everything the delivery step needs: the generated line, the memory it was written against,
     * and the persona the line was written from.
     */
    private record DialogueContext(
            Optional<IndividualDialogueResult> result,
            PlayerMemory memory,
            VillagerProfile profile
    ) {}

    /**
     * Formats the individual villager's speech into a styled chat component.
     */
    public static Component formatVillagerSpeech(VillagerProfile profile, String dialogue) {
        String profName = capitalize(profile.profession());
        return Component.literal("<")
                .withStyle(ChatFormatting.DARK_GREEN)
                .append(Component.literal(profile.name()).withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD))
                .append(Component.literal(" [" + profName + "]> ").withStyle(ChatFormatting.DARK_GREEN))
                .append(Component.literal(dialogue).withStyle(ChatFormatting.GOLD, ChatFormatting.ITALIC));
    }

    private static String capitalize(String str) {
        if (str == null || str.isEmpty()) return "Villager";
        return Character.toUpperCase(str.charAt(0)) + str.substring(1);
    }

    public VillagerDatabase getDatabase() {
        return database;
    }
}
