package com.reazip.economycraft.gossip;

import com.mojang.logging.LogUtils;
import com.reazip.economycraft.EconomyCraft;
import com.reazip.economycraft.EconomySources;
import com.reazip.economycraft.util.EconomyExecutors;
import com.reazip.economycraft.util.EconomyPaths;
import com.reazip.economycraft.util.TransactionEntry;
import com.reazip.economycraft.util.TransactionLogReader;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.DoubleSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Background daemon worker that periodically ingests transaction logs, filters
 * significant economic events, formats anonymized prompt context, queries Gemini,
 * and atomically updates the in-memory GossipPool.
 */
public class GossipDigestWorker {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int DEFAULT_MAX_EVENTS = 30;

    private final GossipConfig config;
    private final GeminiClient geminiClient;
    private final AtomicReference<GossipPool> poolRef;
    private final @Nullable CooldownTracker cooldownTracker;
    private final Supplier<Path> logsDirSupplier;
    private final DoubleSupplier inflationSupplier;
    private final @Nullable Function<UUID, String> factionResolver;

    private final ScheduledExecutorService executor;
    private final boolean ownsExecutor;
    private ScheduledFuture<?> scheduledFuture;
    private volatile boolean running = false;

    public GossipDigestWorker(
            MinecraftServer server,
            GossipConfig config,
            GeminiClient geminiClient,
            AtomicReference<GossipPool> poolRef,
            @Nullable CooldownTracker cooldownTracker
    ) {
        this(
                config,
                geminiClient,
                poolRef,
                cooldownTracker,
                () -> EconomyPaths.logsDir(server),
                () -> {
                    var mgr = EconomyCraft.getManager(server);
                    return mgr != null ? mgr.getDynamicPriceMultiplier() : 1.0;
                },
                uuid -> {
                    var mgr = EconomyCraft.getManager(server);
                    if (mgr == null) return null;
                    var fac = mgr.getFactions().factionOf(uuid);
                    return fac != null ? fac.key() : null;
                },
                EconomyExecutors.newSingleThreadScheduledExecutor("EconomyCraft-Gemini-Worker"),
                true
        );
    }

    public GossipDigestWorker(
            GossipConfig config,
            GeminiClient geminiClient,
            AtomicReference<GossipPool> poolRef,
            @Nullable CooldownTracker cooldownTracker,
            Supplier<Path> logsDirSupplier,
            DoubleSupplier inflationSupplier,
            @Nullable Function<UUID, String> factionResolver,
            ScheduledExecutorService executor,
            boolean ownsExecutor
    ) {
        this.config = config;
        this.geminiClient = geminiClient;
        this.poolRef = poolRef;
        this.cooldownTracker = cooldownTracker;
        this.logsDirSupplier = logsDirSupplier;
        this.inflationSupplier = inflationSupplier;
        this.factionResolver = factionResolver;
        this.executor = executor;
        this.ownsExecutor = ownsExecutor;
    }

    /**
     * Starts the periodic digest schedule.
     */
    public synchronized void start() {
        if (running) return;
        running = true;

        if (!config.enabled()) {
            LOGGER.info("[EconomyCraft-Gemini] Gossip worker is disabled in configuration.");
            return;
        }

        long intervalMinutes = Math.max(5, config.refreshIntervalMinutes());
        // Run first cycle with an initial delay of 10 seconds to allow world initialization, then repeat
        scheduledFuture = executor.scheduleAtFixedRate(
                this::runDigestCycle,
                10,
                intervalMinutes * 60,
                TimeUnit.SECONDS
        );
        LOGGER.info("[EconomyCraft-Gemini] Gossip digest worker started (interval: {}m)", intervalMinutes);
    }

    /**
     * Stops the worker and shuts down the executor if owned.
     */
    public synchronized void stop() {
        running = false;
        if (scheduledFuture != null) {
            scheduledFuture.cancel(false);
            scheduledFuture = null;
        }
        if (ownsExecutor && !executor.isShutdown()) {
            executor.shutdown();
        }
        LOGGER.info("[EconomyCraft-Gemini] Gossip digest worker stopped.");
    }

    public boolean isRunning() {
        return running;
    }

    /**
     * Executes a single digest cycle.
     */
    public void runDigestCycle() {
        try {
            if (!config.enabled() || config.getEffectiveApiKey().isBlank()) {
                LOGGER.debug("[EconomyCraft-Gemini] Worker skipped: disabled or API key unset");
                return;
            }

            if (geminiClient.isCircuitOpen()) {
                LOGGER.debug("[EconomyCraft-Gemini] Worker skipped: circuit breaker is open");
                return;
            }

            // Prune expired interaction cooldowns
            if (cooldownTracker != null) {
                cooldownTracker.pruneExpired();
            }

            Path logsDir = logsDirSupplier.get();
            List<TransactionEntry> rawEntries = logsDir != null
                    ? TransactionLogReader.readRecent(logsDir, 200)
                    : List.of();

            double inflation = inflationSupplier.getAsDouble();
            TransactionDigest digest = buildDigest(
                    rawEntries,
                    DEFAULT_MAX_EVENTS,
                    inflation,
                    Duration.ofHours(24),
                    config.anonymizePlayers(),
                    factionResolver
            );

            geminiClient.generateRumors(digest)
                    .thenAccept(optionalPool -> {
                        if (optionalPool.isPresent()) {
                            GossipPool newPool = optionalPool.get();
                            poolRef.set(newPool);
                            LOGGER.info("[EconomyCraft-Gemini] Gossip pool updated atomically with {} categories",
                                    newPool.rumorsByCategory().size());
                        }
                    })
                    .exceptionally(t -> {
                        LOGGER.warn("[EconomyCraft-Gemini] Generation request failed: {}", t.getMessage());
                        return null;
                    });
        } catch (Throwable t) {
            LOGGER.warn("[EconomyCraft-Gemini] Error during digest cycle: {}", t.getMessage());
        }
    }

    /**
     * Computes the economic significance score of a transaction for prompt prioritization.
     */
    public static double calculateSignificance(@Nullable TransactionEntry entry) {
        if (entry == null) return 0.0;
        double amount = Math.abs(entry.amount());
        String source = entry.source() != null ? entry.source() : "";

        double weight = 1.0;
        if (source.equals(EconomySources.AUCTION_PURCHASE.asString())) {
            weight = 2.0;
        } else if (source.equals(EconomySources.WEALTH_TAX.asString())
                || source.equals(EconomySources.CORRUPTION_TAX.asString())) {
            weight = 2.5;
        } else if (source.equals(EconomySources.QUEST_FUNDING.asString())
                || source.equals(EconomySources.QUEST_FORFEIT.asString())) {
            weight = 1.8;
        } else if (source.equals(EconomySources.TOLL_PAYMENT.asString())) {
            weight = 1.3;
        } else if (source.equals(EconomySources.ORDER_FULFILLMENT.asString())) {
            weight = 1.5;
        } else if (source.equals(EconomySources.VILLAGER_TRADE.asString())) {
            weight = 1.4;
        }

        return amount * weight;
    }

    /**
     * Filters and orders transactions by descending economic significance.
     */
    public static List<TransactionEntry> filterSignificant(List<TransactionEntry> entries, int limit) {
        if (entries == null || entries.isEmpty() || limit <= 0) {
            return List.of();
        }

        return entries.stream()
                .filter(e -> e != null && e.amount() != 0)
                .sorted(Comparator.comparingDouble(GossipDigestWorker::calculateSignificance).reversed())
                .limit(limit)
                .toList();
    }

    /**
     * Builds an anonymized TransactionDigest from raw transaction logs.
     */
    public static TransactionDigest buildDigest(
            List<TransactionEntry> rawEntries,
            int maxEvents,
            double inflation,
            Duration lookbackWindow,
            boolean anonymizePlayers,
            @Nullable Function<UUID, String> factionResolver
    ) {
        List<TransactionEntry> topEvents = filterSignificant(rawEntries, maxEvents);
        List<String> formatted = TransactionAnonymizer.formatDigest(topEvents, anonymizePlayers, factionResolver);
        return new TransactionDigest(lookbackWindow, topEvents.size(), inflation, formatted);
    }
}
