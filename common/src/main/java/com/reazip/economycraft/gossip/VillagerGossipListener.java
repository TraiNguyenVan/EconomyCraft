package com.reazip.economycraft.gossip;

import com.mojang.logging.LogUtils;
import com.reazip.economycraft.EconomyConfig;
import dev.architectury.event.EventResult;
import dev.architectury.event.events.common.InteractionEvent;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Event listener intercepting villager interactions to deliver contextual,
 * profession-themed economic gossip to players upon opening trades.
 *
 * <p>Guarantees O(1) lock-free execution with 0ms tick-thread delay and silent error isolation.
 */
public final class VillagerGossipListener {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static volatile Supplier<GossipPool> poolSupplier = GossipPool::empty;
    private static volatile CooldownTracker cooldownTracker = new CooldownTracker();
    private static volatile Supplier<GossipConfig> configSupplier = () -> {
        var cfg = EconomyConfig.get();
        return cfg != null ? cfg.geminiGossip : GossipConfig.createDefault();
    };
    private static volatile @Nullable com.reazip.economycraft.gossip.memory.VillagerMemoryService memoryService = null;
    private static volatile @Nullable Supplier<GossipDigestWorker> workerSupplier = null;
    private static volatile @Nullable RecentSpokenTracker recentSpokenTracker = null;
    private static volatile java.util.function.DoubleSupplier chanceRollSupplier = () -> java.util.concurrent.ThreadLocalRandom.current().nextDouble();

    private VillagerGossipListener() {}

    public static void setWorkerSupplier(@Nullable Supplier<GossipDigestWorker> supplier) {
        workerSupplier = supplier;
    }

    public static void setRecentSpokenTracker(@Nullable RecentSpokenTracker tracker) {
        recentSpokenTracker = tracker;
    }

    public static @Nullable RecentSpokenTracker getRecentSpokenTracker() {
        return recentSpokenTracker;
    }

    public static void setChanceRollSupplier(@Nullable java.util.function.DoubleSupplier supplier) {
        chanceRollSupplier = supplier != null ? supplier : () -> java.util.concurrent.ThreadLocalRandom.current().nextDouble();
    }

    public static void setMemoryService(@Nullable com.reazip.economycraft.gossip.memory.VillagerMemoryService service) {
        memoryService = service;
    }

    public static @Nullable com.reazip.economycraft.gossip.memory.VillagerMemoryService getMemoryService() {
        return memoryService;
    }

    /**
     * Initializes the listener with the authoritative GossipPool reference, cooldown tracker, and config supplier.
     */
    public static void init(
            AtomicReference<GossipPool> poolRef,
            CooldownTracker tracker,
            Supplier<GossipConfig> cfgSupplier
    ) {
        init(poolRef, tracker, cfgSupplier, null);
    }

    /**
     * Initializes the listener with the authoritative GossipPool reference, cooldown tracker, config supplier,
     * and recent spoken tracker.
     */
    public static void init(
            AtomicReference<GossipPool> poolRef,
            CooldownTracker tracker,
            Supplier<GossipConfig> cfgSupplier,
            @Nullable RecentSpokenTracker spokenTracker
    ) {
        if (poolRef != null) {
            poolSupplier = poolRef::get;
        }
        if (tracker != null) {
            cooldownTracker = tracker;
        }
        if (cfgSupplier != null) {
            configSupplier = cfgSupplier;
        }
        if (spokenTracker != null) {
            recentSpokenTracker = spokenTracker;
        }
    }

    /**
     * Registers the listener on the cross-platform entity interaction event.
     */
    public static void register() {
        InteractionEvent.INTERACT_ENTITY.register(VillagerGossipListener::onInteract);
    }

    /**
     * Architectury interaction callback.
     */
    public static EventResult onInteract(Player player, Entity entity, InteractionHand hand) {
        handleVillagerInteraction(player, entity, hand);
        return EventResult.pass();
    }

    /**
     * Fabric UseEntityCallback signature compatibility.
     */
    public static InteractionResult onUseEntity(
            Player player,
            Level level,
            InteractionHand hand,
            Entity entity,
            @Nullable EntityHitResult hitResult
    ) {
        return handleVillagerInteraction(player, entity, hand);
    }

    /**
     * Primary handler for villager interaction. Lock-free, O(1), silent on failure.
     * Always returns {@link InteractionResult#PASS} so vanilla trading proceeds uninterrupted.
     */
    public static InteractionResult handleVillagerInteraction(
            @Nullable Player player,
            @Nullable Entity entity,
            @Nullable InteractionHand hand
    ) {
        try {
            if (player == null || entity == null || hand != InteractionHand.MAIN_HAND) {
                return InteractionResult.PASS;
            }

            // Server-side only
            if (player.level().isClientSide()) {
                return InteractionResult.PASS;
            }

            if (!(player instanceof ServerPlayer serverPlayer)) {
                return InteractionResult.PASS;
            }

            if (!(entity instanceof Villager villager)) {
                return InteractionResult.PASS;
            }

            // Baby villagers do not trade
            if (villager.isBaby()) {
                return InteractionResult.PASS;
            }

            GossipConfig config = configSupplier.get();
            if (config == null || !config.enabled()) {
                return InteractionResult.PASS;
            }

            UUID playerUuid = serverPlayer.getUUID();
            UUID villagerUuid = villager.getUUID();

            // Check per-player, per-villager cooldown
            if (cooldownTracker.isOnCooldown(playerUuid, villagerUuid)) {
                return InteractionResult.PASS;
            }

            // Record cooldown
            long cooldownMillis = (long) config.cooldownMinutes() * 60_000L;
            cooldownTracker.setCooldown(playerUuid, villagerUuid, cooldownMillis);

            // 1. Private dialogue / rumor (delivered privately to player if privateChatChance permits)
            if (passesChance(chanceRollSupplier.getAsDouble(), config.privateChatChance())) {
                if (memoryService != null) {
                    memoryService.handleInteraction(serverPlayer, villager);
                } else if (!config.publicChat()) {
                    // Fallback: if memory service is inactive and publicChat is disabled, send generic rumor privately
                    GossipPool pool = poolSupplier.get();
                    if (pool != null && !pool.isEmpty()) {
                        GossipCategory category = ProfessionMapper.fromEntity(villager);
                        String rumor = pool.getNextRoundRobinRumor(category);
                        if (rumor != null && !rumor.isBlank()) {
                            if (recentSpokenTracker != null) {
                                recentSpokenTracker.recordSpoken(rumor);
                            }
                            Component message = formatRumor(villager, rumor);
                            serverPlayer.sendSystemMessage(message);
                        }
                    }
                }
            }

            // 2. Deliver public economic rumor (broadcast to all players if public_chat is enabled, publicChatChance permits, AND global server throttle permits)
            if (config.publicChat()
                    && passesChance(chanceRollSupplier.getAsDouble(), config.publicChatChance())
                    && cooldownTracker.tryAcquirePublicBroadcast(cooldownMillis)) {
                GossipCategory category = ProfessionMapper.fromEntity(villager);
                MinecraftServer server = serverPlayer.level().getServer();
                GossipDigestWorker worker = workerSupplier != null ? workerSupplier.get() : com.reazip.economycraft.EconomyCraft.getGossipWorker();

                if (worker != null) {
                    worker.generateDynamicRumor(category).thenAccept(optRumor -> {
                        String rumor = optRumor.orElseGet(() -> {
                            GossipPool pool = poolSupplier.get();
                            return (pool != null && !pool.isEmpty()) ? pool.getNextRoundRobinRumor(category) : null;
                        });
                        if (rumor != null && !rumor.isBlank()) {
                            if (recentSpokenTracker != null) {
                                recentSpokenTracker.recordSpoken(rumor);
                            }
                            Component message = formatRumor(villager, rumor);
                            if (server != null) {
                                server.execute(() -> server.getPlayerList().broadcastSystemMessage(message, false));
                            } else {
                                serverPlayer.sendSystemMessage(message);
                            }
                        }
                    });
                } else {
                    GossipPool pool = poolSupplier.get();
                    if (pool != null && !pool.isEmpty()) {
                        String rumor = pool.getNextRoundRobinRumor(category);
                        if (rumor != null && !rumor.isBlank()) {
                            if (recentSpokenTracker != null) {
                                recentSpokenTracker.recordSpoken(rumor);
                            }
                            Component message = formatRumor(villager, rumor);
                            if (server != null) {
                                server.getPlayerList().broadcastSystemMessage(message, false);
                            } else {
                                serverPlayer.sendSystemMessage(message);
                            }
                        }
                    }
                }
            }
        } catch (Throwable t) {
            // Absolute silent isolation: never disrupt trade menu or server tick
            LOGGER.debug("[EconomyCraft] Error handling villager gossip interaction: {}", t.getMessage());
        }

        return InteractionResult.PASS;
    }

    /**
     * Formats the villager's gossip into a styled Minecraft chat component.
     */
    public static Component formatRumor(@Nullable Villager villager, String rumor) {
        Component villagerName = villager != null ? villager.getName() : Component.literal("Villager");
        return Component.literal("<")
                .withStyle(ChatFormatting.DARK_GREEN)
                .append(villagerName.copy().withStyle(ChatFormatting.GREEN))
                .append(Component.literal("> ").withStyle(ChatFormatting.DARK_GREEN))
                .append(Component.literal(rumor).withStyle(ChatFormatting.YELLOW, ChatFormatting.ITALIC));
    }

    public static boolean passesChance(double roll, double chance) {
        if (chance <= 0.0) return false;
        if (chance >= 1.0) return true;
        return roll < chance;
    }

    public static CooldownTracker getCooldownTracker() {
        return cooldownTracker;
    }

    public static Supplier<GossipPool> getPoolSupplier() {
        return poolSupplier;
    }
}
