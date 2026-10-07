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
    private final Supplier<GossipPool> poolSupplier;
    private final DoubleSupplier inflationSupplier;
    private final @Nullable Function<UUID, String> factionResolver;
    private final @Nullable com.reazip.economycraft.gossip.RecentSpokenTracker recentSpokenTracker;

    private final Map<UUID, VillagerProfile> profileCache = new ConcurrentHashMap<>();
    private final Map<String, PlayerMemory> memoryCache = new ConcurrentHashMap<>();

    public VillagerMemoryService(
            VillagerDatabase database,
            GossipApiClient apiClient,
            Supplier<GossipConfig> configSupplier,
            Supplier<GossipPool> poolSupplier,
            DoubleSupplier inflationSupplier,
            @Nullable Function<UUID, String> factionResolver
    ) {
        this(database, apiClient, configSupplier, poolSupplier, inflationSupplier, factionResolver, null);
    }

    public VillagerMemoryService(
            VillagerDatabase database,
            GossipApiClient apiClient,
            Supplier<GossipConfig> configSupplier,
            Supplier<GossipPool> poolSupplier,
            DoubleSupplier inflationSupplier,
            @Nullable Function<UUID, String> factionResolver,
            @Nullable com.reazip.economycraft.gossip.RecentSpokenTracker recentSpokenTracker
    ) {
        this.database = database;
        this.apiClient = apiClient;
        this.configSupplier = configSupplier;
        this.poolSupplier = poolSupplier;
        this.inflationSupplier = inflationSupplier;
        this.factionResolver = factionResolver;
        this.recentSpokenTracker = recentSpokenTracker;
    }

    private String memoryKey(UUID villagerUuid, UUID playerUuid) {
        return villagerUuid.toString() + ":" + playerUuid.toString();
    }

    /**
     * Gets or synchronously creates the in-memory/seeded VillagerProfile for an entity,
     * ensuring that overhead nameplates and identity are immediately available without tick delay.
     */
    public VillagerProfile getOrInitializeProfile(Villager villager) {
        UUID uuid = villager.getUUID();
        VillagerProfile cached = profileCache.get(uuid);
        if (cached != null) return cached;

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

        VillagerProfile seeded = VillagerSeeder.createSeededProfile(uuid, profession, biome, System.currentTimeMillis());
        profileCache.put(uuid, seeded);

        // Asynchronously check SQLite database or persist seeded profile
        database.getVillager(uuid).thenAccept(optDbProfile -> {
            if (optDbProfile.isPresent()) {
                profileCache.put(uuid, optDbProfile.get());
            } else {
                database.saveVillager(seeded);
            }
        });

        return seeded;
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

        VillagerProfile profile = getOrInitializeProfile(villager);
        UUID villagerUuid = villager.getUUID();
        UUID playerUuid = player.getUUID();
        String key = memoryKey(villagerUuid, playerUuid);

        // Ensure nameplate is visually set if entity is not manually tagged
        if (villager.getCustomName() == null) {
            villager.setCustomName(Component.literal(profile.name()).withStyle(ChatFormatting.YELLOW));
            villager.setCustomNameVisible(false); // standard nametag visibility on look
        }

        PlayerMemory memory = memoryCache.computeIfAbsent(key, k ->
                PlayerMemory.createDefault(villagerUuid, playerUuid, System.currentTimeMillis()));

        // Resolve global grapevine rumors for this villager's category
        GossipCategory category = ProfessionMapper.fromEntity(villager);
        GossipPool pool = poolSupplier.get();
        List<String> grapevine = (pool != null) ? pool.getRumors(category) : List.of();

        // Resolve player archetype
        String faction = factionResolver != null ? factionResolver.apply(playerUuid) : null;
        String playerName = player.getScoreboardName();
        String archetype = TransactionAnonymizer.resolveArchetype(playerUuid, faction, 0, playerName);

        double inflation = inflationSupplier.getAsDouble();
        List<String> recentSpoken = (recentSpokenTracker != null) ? recentSpokenTracker.getRecentSpoken() : List.of();

        // Safely extract main-thread trade snapshot from villager entity
        List<TradeOfferSnapshot> offers = TradeOfferSnapshot.fromOffers(villager.getOffers(), 8);

        // Fetch recent trade history asynchronously before requesting dialogue
        return database.getRecentTrades(villagerUuid, playerUuid, 5)
                .thenCompose(trades -> apiClient.generateIndividualDialogue(
                        profile, memory, archetype, grapevine, inflation, recentSpoken, offers, trades))
                .thenApply(optResult -> {
                    if (optResult.isEmpty() || optResult.get().isEmpty()) {
                        return Optional.<String>empty();
                    }

                    IndividualDialogueResult result = optResult.get();

                    if (recentSpokenTracker != null) {
                        recentSpokenTracker.recordSpoken(result.dialogue());
                    }

                    // Update memory
                    PlayerMemory updatedMemory = memory.withInteraction(
                            result.sentimentDelta(),
                            "Visited stall",
                            System.currentTimeMillis()
                    );
                    memoryCache.put(key, updatedMemory);
                    database.savePlayerMemory(updatedMemory);

                    // Deliver message to player
                    var server = player.level().getServer();
                    if (server != null) {
                        server.execute(() -> {
                            Component formatted = formatVillagerSpeech(profile, result.dialogue());
                            player.sendSystemMessage(formatted);
                        });
                    }

                    return Optional.of(result.dialogue());
                });
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
        PlayerMemory memory = memoryCache.computeIfAbsent(key, k ->
                PlayerMemory.createDefault(villagerUuid, playerUuid, System.currentTimeMillis()));

        long now = System.currentTimeMillis();
        PlayerMemory updated = memory.withTrade(amountSpent, itemDescription, now);
        memoryCache.put(key, updated);
        database.savePlayerMemory(updated);

        String itemName = itemDescription != null && !itemDescription.isBlank() ? itemDescription : "Trade item";
        database.recordTradeTransaction(villagerUuid, playerUuid, itemName, count, amountSpent, now);
    }

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
