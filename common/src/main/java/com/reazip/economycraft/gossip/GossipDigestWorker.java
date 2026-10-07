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
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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
    public static final Duration DEFAULT_MAX_LOOKBACK = Duration.ofHours(2);
    public static final double HALF_LIFE_MINUTES = 30.0;

    private final GossipConfig config;
    private final GossipApiClient apiClient;
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
            GossipApiClient apiClient,
            AtomicReference<GossipPool> poolRef,
            @Nullable CooldownTracker cooldownTracker
    ) {
        this(
                config,
                apiClient,
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
                EconomyExecutors.newSingleThreadScheduledExecutor("EconomyCraft-AI-Worker"),
                true
        );
    }

    public GossipDigestWorker(
            GossipConfig config,
            GossipApiClient apiClient,
            AtomicReference<GossipPool> poolRef,
            @Nullable CooldownTracker cooldownTracker,
            Supplier<Path> logsDirSupplier,
            DoubleSupplier inflationSupplier,
            @Nullable Function<UUID, String> factionResolver,
            ScheduledExecutorService executor,
            boolean ownsExecutor
    ) {
        this.config = config;
        this.apiClient = apiClient;
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
            LOGGER.info("[EconomyCraft-AI] Gossip worker is disabled in configuration.");
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
        LOGGER.info("[EconomyCraft-AI] Gossip digest worker started (interval: {}m)", intervalMinutes);
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
        LOGGER.info("[EconomyCraft-AI] Gossip digest worker stopped.");
    }

    public boolean isRunning() {
        return running;
    }

    /**
     * Executes a single digest cycle.
     */
    public CompletableFuture<Optional<GossipPool>> runDigestCycle() {
        try {
            if (!config.enabled() || config.getEffectiveApiKey().isBlank()) {
                LOGGER.debug("[EconomyCraft-AI] Worker skipped: disabled or API key unset");
                return CompletableFuture.completedFuture(Optional.empty());
            }

            if (apiClient.isCircuitOpen()) {
                LOGGER.debug("[EconomyCraft-AI] Worker skipped: circuit breaker is open");
                return CompletableFuture.completedFuture(Optional.empty());
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
                    DEFAULT_MAX_LOOKBACK,
                    config.anonymizePlayers(),
                    factionResolver
            );

            return apiClient.generateRumors(digest)
                    .thenApply(optionalPool -> {
                        if (optionalPool.isPresent()) {
                            GossipPool newPool = optionalPool.get();
                            poolRef.set(newPool);
                            LOGGER.info("[EconomyCraft-AI] Gossip pool updated atomically with {} categories",
                                    newPool.rumorsByCategory().size());
                        } else {
                            if (poolRef.get().isEmpty()) {
                                LOGGER.info("[EconomyCraft-AI] Pool is currently empty after cycle; scheduling retry in 30s...");
                                executor.schedule(this::runDigestCycle, 30, TimeUnit.SECONDS);
                            }
                        }
                        return optionalPool;
                    })
                    .exceptionally(t -> {
                        LOGGER.warn("[EconomyCraft-AI] Generation request failed: {}", t.getMessage());
                        if (poolRef.get().isEmpty()) {
                            LOGGER.info("[EconomyCraft-AI] Pool is currently empty after exception; scheduling retry in 30s...");
                            executor.schedule(this::runDigestCycle, 30, TimeUnit.SECONDS);
                        }
                        return Optional.empty();
                    });
        } catch (Throwable t) {
            LOGGER.warn("[EconomyCraft-AI] Error during digest cycle: {}", t.getMessage());
            return CompletableFuture.completedFuture(Optional.empty());
        }
    }

    /**
     * Triggers an immediate manual refresh asynchronously.
     */
    public CompletableFuture<Boolean> triggerManualRefresh() {
        return runDigestCycle().thenApply(opt -> opt.isPresent() && !opt.get().isEmpty());
    }

    public GossipApiClient getApiClient() {
        return apiClient;
    }

    public GossipConfig getConfig() {
        return config;
    }

    public AtomicReference<GossipPool> getPoolRef() {
        return poolRef;
    }

    /**
     * Asynchronously generates a fresh, unique single economic rumor on demand
     * using the latest time-decayed transactions.
     */
    public CompletableFuture<Optional<String>> generateDynamicRumor(GossipCategory category) {
        if (!config.enabled() || config.getEffectiveApiKey().isBlank() || apiClient.isCircuitOpen()) {
            return CompletableFuture.completedFuture(Optional.empty());
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
                DEFAULT_MAX_LOOKBACK,
                config.anonymizePlayers(),
                factionResolver
        );

        return apiClient.generateSingleRumor(category, digest.toPromptContext());
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
     * Computes the economic significance score decayed exponentially over time.
     * Transactions lose half their significance every 30 minutes, and transactions
     * older than the 2-hour lookback window receive a score of 0.0.
     */
    public static double calculateDecayedSignificance(@Nullable TransactionEntry entry, Instant now) {
        if (entry == null || entry.amount() == 0) return 0.0;
        Instant time = entry.time();
        if (time == null) {
            time = now;
        }
        Duration age = Duration.between(time, now);
        if (age.isNegative()) {
            age = Duration.ZERO;
        }
        if (age.compareTo(DEFAULT_MAX_LOOKBACK) > 0) {
            return 0.0;
        }
        double base = calculateSignificance(entry);
        double minutes = age.toMillis() / 60_000.0;
        double decayFactor = Math.pow(0.5, minutes / HALF_LIFE_MINUTES);
        return base * decayFactor;
    }

    /**
     * Filters and orders transactions by descending economic significance using current time decay.
     */
    public static List<TransactionEntry> filterSignificant(List<TransactionEntry> entries, int limit) {
        return filterSignificant(entries, limit, Instant.now());
    }

    /**
     * Filters and orders transactions by descending economic significance relative to a reference time.
     */
    public static List<TransactionEntry> filterSignificant(List<TransactionEntry> entries, int limit, Instant now) {
        if (entries == null || entries.isEmpty() || limit <= 0) {
            return List.of();
        }

        return entries.stream()
                .filter(e -> e != null && e.amount() != 0)
                .map(e -> Map.entry(e, calculateDecayedSignificance(e, now)))
                .filter(e -> e.getValue() > 0.0)
                .sorted(Comparator.<Map.Entry<TransactionEntry, Double>>comparingDouble(Map.Entry::getValue).reversed())
                .limit(limit)
                .map(Map.Entry::getKey)
                .toList();
    }

    /**
     * Builds an anonymized TransactionDigest from raw transaction logs using current time.
     */
    public static TransactionDigest buildDigest(
            List<TransactionEntry> rawEntries,
            int maxEvents,
            double inflation,
            Duration lookbackWindow,
            boolean anonymizePlayers,
            @Nullable Function<UUID, String> factionResolver
    ) {
        return buildDigest(rawEntries, maxEvents, inflation, lookbackWindow, anonymizePlayers, factionResolver, Instant.now());
    }

    /**
     * Builds an anonymized TransactionDigest from raw transaction logs relative to a reference time.
     */
    public static TransactionDigest buildDigest(
            List<TransactionEntry> rawEntries,
            int maxEvents,
            double inflation,
            Duration lookbackWindow,
            boolean anonymizePlayers,
            @Nullable Function<UUID, String> factionResolver,
            Instant now
    ) {
        List<TransactionEntry> topEvents = filterSignificant(rawEntries, maxEvents, now);
        List<String> formatted = TransactionAnonymizer.formatDigest(topEvents, anonymizePlayers, factionResolver);
        return new TransactionDigest(lookbackWindow, topEvents.size(), inflation, formatted);
    }
}
