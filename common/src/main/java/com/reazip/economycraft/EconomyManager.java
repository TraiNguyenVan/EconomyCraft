package com.reazip.economycraft;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.mojang.logging.LogUtils;
import com.reazip.economycraft.api.v1.BalanceChangeEvent;
import com.reazip.economycraft.api.v1.BalanceEvents;
import com.reazip.economycraft.api.v1.BalanceMutationResult;
import com.reazip.economycraft.api.v1.BalanceMutationType;
import com.reazip.economycraft.api.v1.MutationSource;
import com.reazip.economycraft.api.v1.PaymentResult;
import com.reazip.economycraft.orders.OrderManager;
import com.reazip.economycraft.auction.AuctionManager;
import com.reazip.economycraft.faction.FactionId;
import com.reazip.economycraft.faction.FactionStore;
import com.reazip.economycraft.fiscal.FiscalPass;
import com.reazip.economycraft.profession.BlockTags;
import com.reazip.economycraft.profession.FarmerEffects;
import com.reazip.economycraft.profession.MinerEffects;
import com.reazip.economycraft.profession.ProfessionId;
import com.reazip.economycraft.profession.ProfessionLevel;
import com.reazip.economycraft.profession.ProfessionStore;
import com.reazip.economycraft.profession.ProfessionEffects;
import com.reazip.economycraft.tag.TagDisplayService;
import com.reazip.economycraft.time.CooldownService;
import com.reazip.economycraft.time.OnlineTimeService;
import com.reazip.economycraft.util.AsyncFileWriter;
import com.reazip.economycraft.util.EconomyPaths;
import com.reazip.economycraft.util.IdentityCompat;
import com.reazip.economycraft.util.ProfileCompat;
import com.reazip.economycraft.util.TransactionLogWriter;
import com.reazip.economycraft.util.UuidLongMapStore;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.numbers.FixedFormat;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.ScoreAccess;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.ToLongFunction;
import java.time.LocalDate;

public class EconomyManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Method NEOFORGE_NAME_LOOKUP = findNeoForgeNameLookup();
    private static final Gson GSON = new Gson();
    private static final Type TYPE = new TypeToken<Map<UUID, Long>>(){}.getType();
    private static final Type DAILY_SELL_TYPE = new TypeToken<Map<UUID, DailySellData>>(){}.getType();
    private static final Type STATS_TYPE = new TypeToken<Map<UUID, PlayerStats>>(){}.getType();
    private static final Type PLAYER_NAMES_TYPE = new TypeToken<Map<UUID, String>>(){}.getType();
    private static final Set<String> TRADE_SOURCES = Set.of(
            EconomySources.SHOP_PURCHASE.asString(),
            EconomySources.SHOP_SALE.asString(),
            EconomySources.AUCTION_PURCHASE.asString(),
            EconomySources.ORDER_FULFILLMENT.asString(),
            EconomySources.ORDER_ESCROW_HOLD.asString()
    );
    private static final Set<String> FISCAL_SOURCES = Set.of(
            EconomySources.WEALTH_TAX.asString(),
            EconomySources.WEALTH_REBATE.asString()
    );
    private static final String ECO_BALANCE_OBJECTIVE = "eco_balance";
    private static final int LEADERBOARD_SIZE = 5;
    private static final long SCOREBOARD_SCORE_SCALE = 1000L;
    private static final long LOOKUP_RETRY_COOLDOWN_MS = TimeUnit.MINUTES.toMillis(5);
    private static final ExecutorService PROFILE_LOOKUP_EXECUTOR = Executors.newFixedThreadPool(4, r -> {
        Thread thread = new Thread(r, "EconomyCraft-ProfileLookup");
        thread.setDaemon(true);
        return thread;
    });

    private final MinecraftServer server;
    private final Path file;
    private final Path dailyFile;
    private final Path dailySellFile;
    private final Path statsFile;
    private final Path playerNamesFile;
    private final Path logsDir;

    private final Map<UUID, Long> balances = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastDaily = new ConcurrentHashMap<>();
    private final Map<UUID, DailySellData> dailySells = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerStats> stats = new ConcurrentHashMap<>();
    private final Map<UUID, String> playerNames = new ConcurrentHashMap<>();
    private final PriceRegistry prices;
    private final BalanceEventDispatcher balanceEvents;
    private final BalanceMutationEngine balanceMutations;
    private final DynamicPriceEngine dynamicPrices;
    private final FiscalPass fiscalPass;

    /**
     * The tag phase's data layer (Phase 2). Data and tuning only: nothing here is wired to gameplay yet, so a
     * freshly-started server writes these files empty and reads them back unchanged.
     *
     * <p>They are owned here, and not by the phases that will use them, so that there is exactly one place that
     * loads them on startup and exactly one that flushes them on shutdown — and so a future phase cannot
     * accidentally create a second instance with its own copy of the state.
     */
    private final OnlineTimeService onlineTime;
    private final CooldownService cooldowns;
    private final FactionStore factions;
    private final ProfessionStore professions;
    private final TagDisplayService tagDisplay;
    private final BlockTags blockTags;

    /** Reused per tick so tracking online players does not allocate a new set twenty times a second. */
    private final Set<UUID> onlineScratch = new HashSet<>();

    private Objective objective;
    private final DeliveryManager deliveries;
    private final AuctionManager auctions;
    private final OrderManager orders;
    private final NotificationManager notifications;
    private final Map<UUID, String> displayed = new ConcurrentHashMap<>();
    private final Set<UUID> scheduledProfileLookups = ConcurrentHashMap.newKeySet();
    private final Set<UUID> loggedUnresolvedNames = ConcurrentHashMap.newKeySet();
    private volatile boolean active = true;
    private volatile List<LeaderboardEntry> leaderboardCache;

    /** Five seconds: fast enough that a stale tag is a non-event, slow enough to be invisible in a profiler. */
    private static final int TAG_SWEEP_INTERVAL_TICKS = 100;

    public static final long MAX = 999_999_999_999L;

    public EconomyManager(MinecraftServer server) {
        this.server = server;
        this.balanceEvents = BalanceEventDispatcher.forServer(server);
        Path dataDir = EconomyPaths.dataDir(server);

        this.file = dataDir.resolve("balances.json");
        this.dailyFile = dataDir.resolve("daily.json");
        this.dailySellFile = dataDir.resolve("daily_sells.json");
        this.statsFile = dataDir.resolve("stats.json");
        this.playerNamesFile = dataDir.resolve("player_names.json");

        load();
        loadDaily();
        loadDailySells();
        loadStats();
        loadPlayerNames();

        this.logsDir = EconomyPaths.logsDir(server);
        TransactionLogWriter.cleanup(logsDir, EconomyConfig.get().transactionLogRetentionDays);
        TransactionLogger transactionLogger = new TransactionLogger(logsDir, this::getBestName);

        this.balanceMutations = new BalanceMutationEngine(
                balances,
                () -> EconomyConfig.get().startingBalance,
                this::updateLeaderboard,
                () -> {
                    updateLeaderboard();
                    save();
                },
                balanceEvents,
                transactionLogger::onTransfer
        );

        this.deliveries = new DeliveryManager(server);
        this.auctions = new AuctionManager(server, deliveries);
        this.orders = new OrderManager(server, deliveries);
        this.notifications = new NotificationManager(server);
        this.prices = new PriceRegistry(server);
        this.dynamicPrices = new DynamicPriceEngine(dataDir);
        dynamicPrices.refresh(server, balances);
        this.fiscalPass = new FiscalPass(this, dataDir);

        // Phase 2. Built after EconomyConfig is loaded (SERVER_STARTING) so BlockTags can read the
        // professions section, and after the economy files so a config problem cannot leave a half-built
        // manager behind.
        this.onlineTime = new OnlineTimeService(dataDir.resolve("online_time.json"));
        this.cooldowns = new CooldownService(dataDir.resolve("cooldowns.json"));
        this.factions = new FactionStore(dataDir.resolve("parties.json"));
        this.professions = new ProfessionStore(dataDir.resolve("professions.json"));
        this.blockTags = BlockTags.fromConfig(EconomyConfig.get().professions);
        // The display service reads the two stores above and nothing else, so it is built last and holds them by
        // reference: every later phase that changes a selection or a level calls tagDisplay().refresh(...) and the
        // tab row, the nametag prefix and the chat icon all move together.
        this.tagDisplay = new TagDisplayService(new TagDisplayService.TagSource() {
            @Override
            public FactionId factionOf(UUID player) {
                return factions.factionOf(player);
            }

            @Override
            public ProfessionId professionOf(UUID player) {
                return professions.professionOf(player);
            }

            @Override
            public ProfessionLevel levelOf(UUID player) {
                return professions.levelOf(player);
            }
        });

        balanceEvents.register(transactionLogger::onBalanceChanged);
        balanceEvents.register(this::recordStats);

        scheduleProfileLookups(balances.keySet());
        applyScoreboardSettingOnStartup();
    }

    public MinecraftServer getServer() {
        return server;
    }

    public void detach() {
        teardownObjective(server.getScoreboard());
        deactivate();
    }

    public void deactivate() {
        active = false;
        BalanceEventDispatcher.release(server);
        tagDisplay.clear();
    }

    private @Nullable String resolveName(MinecraftServer server, UUID id) {
        String localName = resolveLocalName(server, id);
        if (localName != null) {
            rememberPlayerName(id, localName);
            return localName;
        }

        scheduleProfileLookup(id);
        return playerNames.get(id);
    }

    private void loadPlayerNames() {
        try {
            if (Files.exists(playerNamesFile)) {
                Map<UUID, String> loaded = GSON.fromJson(Files.readString(playerNamesFile), PLAYER_NAMES_TYPE);
                if (loaded != null) loaded.forEach((id, name) -> {
                    if (id != null && name != null && !name.isBlank()) playerNames.put(id, name);
                });
            }
        } catch (Exception e) {
            LOGGER.error("[EconomyCraft] Failed to load cached player names", e);
        }
    }

    public void rememberPlayerName(UUID id, String name) {
        if (id == null || name == null || name.isBlank()) return;
        String previous = playerNames.put(id, name);
        if (!name.equals(previous)) AsyncFileWriter.writeAsync(playerNamesFile, GSON.toJson(playerNames));
    }

    private static @Nullable String resolveLocalName(MinecraftServer server, UUID id) {
        if (server.isSameThread()) {
            ServerPlayer online = server.getPlayerList().getPlayer(id);
            if (online != null) return IdentityCompat.of(online).name();
        }

        String cached = safeResolveCachedName(server, id);
        if (cached != null) return cached;

        String loaderCached = getNeoForgeCachedName(id);
        if (loaderCached != null) return loaderCached;
        return null;
    }

    public @Nullable String getBestName(UUID id) {
        return resolveName(server, id);
    }

    public void refreshLeaderboard() {
        updateLeaderboard();
    }

    public UUID tryResolveUuidByName(String name) {
        if (name == null || name.isBlank()) return null;

        try {
            return UUID.fromString(name);
        } catch (IllegalArgumentException ignored) {}

        ServerPlayer online = server.getPlayerList().getPlayerByName(name);
        if (online != null) return online.getUUID();

        UUID match = null;
        for (UUID id : balances.keySet()) {
            String resolved = safeResolveCachedName(server, id);
            if (resolved == null) resolved = getNeoForgeCachedName(id);
            if (resolved == null) resolved = playerNames.get(id);
            if (resolved == null) continue;
            if (!name.equalsIgnoreCase(resolved)) continue;
            if (match != null && !match.equals(id)) return null;
            match = id;
        }
        return match;
    }

    private static @Nullable String safeResolveCachedName(MinecraftServer server, UUID id) {
        try {
            return ProfileCompat.resolveCachedName(server, id);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private void scheduleProfileLookup(UUID id) {
        scheduleProfileLookups(List.of(id));
    }

    private void scheduleProfileLookups(Collection<UUID> ids) {
        if (!active) return;

        List<UUID> unresolved = new ArrayList<>();
        for (UUID id : ids) {
            if (resolveLocalName(server, id) != null) continue;

            if (id.version() != 4) {
                logUnresolvedName(id);
            } else if (scheduledProfileLookups.add(id)) {
                unresolved.add(id);
            }
        }
        if (unresolved.isEmpty()) return;

        CompletableFuture
                .supplyAsync(() -> fetchProfiles(unresolved))
                .whenComplete((profiles, error) -> {
                    try {
                        server.execute(() -> finishProfileLookups(unresolved, profiles, error));
                    } catch (RuntimeException ignored) {
                        // The server is already shutting down.
                    }
                });
    }

    private Map<UUID, String> fetchProfiles(Collection<UUID> ids) {
        Map<UUID, String> profiles = new ConcurrentHashMap<>();
        List<CompletableFuture<Void>> fetches = new ArrayList<>();
        for (UUID id : ids) {
            fetches.add(CompletableFuture.runAsync(() -> {
                try {
                    Object profile = ProfileCompat.fetchProfile(server, id);
                    if (profile != null) {
                        var identity = IdentityCompat.fromUnknown(profile);
                        if (id.equals(identity.id()) && identity.name() != null && !identity.name().isBlank()) {
                            profiles.put(id, identity.name());
                        }
                    }
                } catch (RuntimeException ignored) {}
            }, PROFILE_LOOKUP_EXECUTOR));
        }
        fetches.forEach(CompletableFuture::join);
        return profiles;
    }

    private void finishProfileLookups(Collection<UUID> requested,
                                      @Nullable Map<UUID, String> profiles,
                                      @Nullable Throwable error) {
        if (!active) return;

        Set<UUID> resolved = new HashSet<>();
        if (error == null && profiles != null) {
            for (var entry : profiles.entrySet()) {
                UUID id = entry.getKey();
                try {
                    ServerPlayer online = server.getPlayerList().getPlayer(id);
                    String name = online != null ? IdentityCompat.of(online).name() : entry.getValue();
                    ProfileCompat.cacheName(server, id, name);
                    rememberPlayerName(id, name);
                    resolved.add(id);
                } catch (RuntimeException ignored) {}
            }
        }

        scheduledProfileLookups.removeAll(resolved);

        for (UUID id : requested) {
            if (resolveLocalName(server, id) == null) {
                logUnresolvedName(id);
                if (!resolved.contains(id)) {
                    Set<UUID> scheduledLookups = scheduledProfileLookups;
                    CompletableFuture.delayedExecutor(LOOKUP_RETRY_COOLDOWN_MS, TimeUnit.MILLISECONDS, PROFILE_LOOKUP_EXECUTOR)
                            .execute(() -> scheduledLookups.remove(id));
                }
            }
        }
        updateLeaderboard();
    }

    private void logUnresolvedName(UUID id) {
        if (loggedUnresolvedNames.add(id)) {
            LOGGER.warn("[EconomyCraft] Hiding unresolved player {} from name-based displays.", id);
        }
    }

    private static @Nullable String getNeoForgeCachedName(UUID id) {
        if (NEOFORGE_NAME_LOOKUP == null) return null;
        try {
            Object value = NEOFORGE_NAME_LOOKUP.invoke(null, id);
            return value instanceof String name && !name.isBlank() ? name : null;
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static @Nullable Method findNeoForgeNameLookup() {
        try {
            Class<?> cache = Class.forName("net.neoforged.neoforge.common.UsernameCache");
            return cache.getMethod("getLastKnownUsername", UUID.class);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    public Long getBalance(UUID player, boolean newBalanceIfNonExistent) {
        if (!newBalanceIfNonExistent) return balances.get(player);
        return balanceMutations.getBalance(player);
    }

    public void addMoney(UUID player, long amount) {
        addMoney(player, amount, null);
    }

    public BalanceMutationResult addMoney(UUID player, long amount, @Nullable MutationSource source) {
        return addMoney(player, amount, source, null);
    }

    public BalanceMutationResult addMoney(UUID player, long amount, @Nullable MutationSource source, @Nullable String detail) {
        requireServerThread();
        return balanceMutations.add(player, amount, source, detail);
    }

    public void setMoney(UUID player, long amount) {
        setMoney(player, amount, null);
    }

    public BalanceMutationResult setMoney(UUID player, long amount, @Nullable MutationSource source) {
        return setMoney(player, amount, source, null);
    }

    public BalanceMutationResult setMoney(UUID player, long amount, @Nullable MutationSource source, @Nullable String detail) {
        requireServerThread();
        return balanceMutations.set(player, amount, source, detail);
    }

    public boolean removeMoney(UUID player, long amount) {
        return removeMoney(player, amount, null).successful();
    }

    public BalanceMutationResult removeMoney(UUID player, long amount, @Nullable MutationSource source) {
        return removeMoney(player, amount, source, null);
    }

    public BalanceMutationResult removeMoney(UUID player, long amount, @Nullable MutationSource source, @Nullable String detail) {
        requireServerThread();
        return balanceMutations.remove(player, amount, source, detail);
    }

    public boolean pay(UUID from, UUID to, long amount) {
        return pay(from, to, amount, null).successful();
    }

    public PaymentResult pay(UUID from, UUID to, long amount, @Nullable MutationSource source) {
        return pay(from, to, amount, source, null);
    }

    public PaymentResult pay(UUID from, UUID to, long amount, @Nullable MutationSource source, @Nullable String detail) {
        requireServerThread();
        return balanceMutations.pay(from, to, amount, source, detail);
    }

    public PaymentResult transferMoney(UUID from, UUID to, long debitAmount, long creditAmount,
                                       MutationSource source) {
        return transferMoney(from, to, debitAmount, creditAmount, source, null);
    }

    public PaymentResult transferMoney(UUID from, UUID to, long debitAmount, long creditAmount,
                                       MutationSource source, @Nullable String detail) {
        requireServerThread();
        return balanceMutations.transfer(from, to, debitAmount, creditAmount, source, detail);
    }

    public BalanceEvents getBalanceEvents() {
        return balanceEvents;
    }

    public void load() {
        if (Files.exists(file)) {
            try {
                String json = Files.readString(file);
                Map<UUID, Double> map = GSON.fromJson(json, new TypeToken<Map<UUID, Double>>(){}.getType());
                if (map != null) {
                    for (Map.Entry<UUID, Double> e : map.entrySet()) {
                        if (e.getValue() == null) continue;
                        balances.put(e.getKey(), clamp(e.getValue().longValue()));
                    }
                }
            } catch (IOException ex) {
                LOGGER.error("[EconomyCraft] Failed to load {}", file, ex);
            }
        }
    }

    public void save() {
        AsyncFileWriter.writeAsync(file, GSON.toJson(new HashMap<>(balances), TYPE));
        UuidLongMapStore.persist(dailyFile, lastDaily);
        AsyncFileWriter.writeAsync(dailySellFile, GSON.toJson(new HashMap<>(dailySells), DAILY_SELL_TYPE));
        AsyncFileWriter.writeAsync(statsFile, GSON.toJson(new HashMap<>(stats), STATS_TYPE));
        dynamicPrices.flush();
        onlineTime.flush();
        cooldowns.flush();
        factions.flush();
        professions.flush();
    }

    private void loadDaily() {
        UuidLongMapStore.load(dailyFile, lastDaily);
    }

    private void loadStats() {
        if (Files.exists(statsFile)) {
            try {
                String json = Files.readString(statsFile);
                Map<UUID, PlayerStats> map = GSON.fromJson(json, STATS_TYPE);
                if (map != null) stats.putAll(map);
            } catch (IOException ex) {
                LOGGER.error("[EconomyCraft] Failed to load {}", statsFile, ex);
            }
        }
    }

    private void recordStats(BalanceChangeEvent event) {
        long diff = event.difference();
        if (diff == 0) return;

        String source = event.source().map(MutationSource::asString).orElse(null);
        if (FISCAL_SOURCES.contains(source)) return;

        boolean trade = TRADE_SOURCES.contains(source);
        stats.compute(event.playerId(), (id, current) -> {
            PlayerStats base = current != null ? current : new PlayerStats(0, 0, 0, 0);
            if (diff > 0) {
                return new PlayerStats(base.earned() + diff, base.spent(), base.sold() + (trade ? diff : 0), base.bought());
            }
            long amount = -diff;
            return new PlayerStats(base.earned(), base.spent() + amount, base.sold(), base.bought() + (trade ? amount : 0));
        });
    }

    private void loadDailySells() {
        if (Files.exists(dailySellFile)) {
            try {
                String json = Files.readString(dailySellFile);
                Map<UUID, DailySellData> map = GSON.fromJson(json, DAILY_SELL_TYPE);
                if (map != null) dailySells.putAll(map);
            } catch (IOException ex) {
                LOGGER.error("[EconomyCraft] Failed to load {}", dailySellFile, ex);
            }
        }
    }

    private void applyScoreboardSettingOnStartup() {
        Scoreboard board = server.getScoreboard();
        teardownObjective(board);

        if (EconomyConfig.get().scoreboardEnabled) {
            setupObjective();
        }
    }

    static Objective createBalanceObjective(Scoreboard board) {
        return board.addObjective(
                ECO_BALANCE_OBJECTIVE,
                ObjectiveCriteria.DUMMY,
                Component.literal("Balance"),
                ObjectiveCriteria.RenderType.INTEGER,
                true,
                null
        );
    }

    private void ensureObjective(Scoreboard board) {
        if (objective != null) return;

        objective = board.getObjective(ECO_BALANCE_OBJECTIVE);
        if (objective == null) {
            objective = createBalanceObjective(board);
        }
        board.setDisplayObjective(DisplaySlot.SIDEBAR, objective);
    }

    private void teardownObjective(Scoreboard board) {
        removeBalanceObjective(board);
        objective = null;
        displayed.clear();
    }

    static void removeBalanceObjective(Scoreboard board) {
        Objective existing = board.getObjective(ECO_BALANCE_OBJECTIVE);
        if (existing != null) board.removeObjective(existing);
    }

    private void setupObjective() {
        ensureObjective(server.getScoreboard());
        updateLeaderboard();
    }

    private void updateLeaderboard() {
        leaderboardCache = null;

        if (!server.isSameThread()) {
            try {
                server.execute(this::syncScoreboard);
            } catch (RuntimeException ignored) {
            }
            return;
        }

        syncScoreboard();
    }

    private void syncScoreboard() {
        Scoreboard board = server.getScoreboard();

        if (!EconomyConfig.get().scoreboardEnabled) {
            teardownObjective(board);
            return;
        }

        ensureObjective(board);

        syncLeaderboardScores(
                board,
                objective,
                displayed,
                computeLeaderboard(LEADERBOARD_SIZE)
        );
    }

    static void syncLeaderboardScores(
            Scoreboard board,
            Objective objective,
            Map<UUID, String> displayed,
            List<LeaderboardEntry> entries
    ) {
        Map<UUID, String> updated = new HashMap<>();
        for (LeaderboardEntry e : entries) {
            updated.put(e.id(), e.name());
        }

        for (var e : displayed.entrySet()) {
            if (!Objects.equals(e.getValue(), updated.get(e.getKey()))) {
                board.resetSinglePlayerScore(ScoreHolder.forNameOnly(e.getValue()), objective);
            }
        }

        for (LeaderboardEntry e : entries) {
            ScoreAccess score = board.getOrCreatePlayerScore(
                    ScoreHolder.forNameOnly(e.name()),
                    objective
            );
            score.set((int) Math.min(e.value() / SCOREBOARD_SCORE_SCALE, Integer.MAX_VALUE));
            score.numberFormatOverride(new FixedFormat(Component.literal(EconomyCraft.formatMoneyShort(e.value()))));
        }

        displayed.clear();
        displayed.putAll(updated);
    }

    private List<LeaderboardEntry> computeLeaderboard(int limit) {
        List<LeaderboardEntry> full = leaderboardCache;
        if (full == null) {
            full = new ArrayList<>();
            for (var entry : balances.entrySet()) {
                String name = resolveName(server, entry.getKey());
                if (name != null && !name.isBlank()) {
                    full.add(new LeaderboardEntry(entry.getKey(), name, entry.getValue()));
                }
            }
            sortLeaderboard(full);
            if (server.isSameThread()) {
                leaderboardCache = full;
            }
        }

        return new ArrayList<>(full.subList(0, Math.min(limit, full.size())));
    }

    private List<LeaderboardEntry> computeLeaderboardFrom(ToLongFunction<PlayerStats> metric, int limit) {
        List<LeaderboardEntry> full = new ArrayList<>();
        for (var entry : stats.entrySet()) {
            long value = metric.applyAsLong(entry.getValue());
            if (value <= 0) continue;

            String name = resolveName(server, entry.getKey());
            if (name != null && !name.isBlank()) {
                full.add(new LeaderboardEntry(entry.getKey(), name, value));
            }
        }
        sortLeaderboard(full);
        return new ArrayList<>(full.subList(0, Math.min(limit, full.size())));
    }

    private static void sortLeaderboard(List<LeaderboardEntry> entries) {
        entries.sort((a, b) -> {
            int c = Long.compare(b.value(), a.value());
            if (c != 0) return c;

            c = String.CASE_INSENSITIVE_ORDER.compare(a.name(), b.name());
            if (c != 0) return c;

            return a.id().compareTo(b.id());
        });
    }

    public List<LeaderboardEntry> getLeaderboardEntries(int limit) {
        return getLeaderboardEntries(LeaderboardCategory.BALANCE, limit);
    }

    public @Nullable LeaderboardEntry getLeaderboardEntry(int rank) {
        return getLeaderboardEntry(LeaderboardCategory.BALANCE, rank);
    }

    public List<LeaderboardEntry> getLeaderboardEntries(LeaderboardCategory category, int limit) {
        int safeLimit = Math.max(0, limit);
        return switch (category) {
            case BALANCE -> computeLeaderboard(safeLimit);
            case EARNED -> computeLeaderboardFrom(PlayerStats::earned, safeLimit);
            case SPENT -> computeLeaderboardFrom(PlayerStats::spent, safeLimit);
            case SOLD -> computeLeaderboardFrom(PlayerStats::sold, safeLimit);
            case BOUGHT -> computeLeaderboardFrom(PlayerStats::bought, safeLimit);
            case TRADED -> computeLeaderboardFrom(s -> s.sold() + s.bought(), safeLimit);
        };
    }

    public @Nullable LeaderboardEntry getLeaderboardEntry(LeaderboardCategory category, int rank) {
        if (rank < 1) return null;
        List<LeaderboardEntry> top = getLeaderboardEntries(category, rank);
        return top.size() < rank ? null : top.get(rank - 1);
    }

    public record LeaderboardEntry(UUID id, String name, long value) {}

    public boolean toggleScoreboard() {
        EconomyConfig.get().scoreboardEnabled = !EconomyConfig.get().scoreboardEnabled;
        EconomyConfig.save();

        if (EconomyConfig.get().scoreboardEnabled) {
            setupObjective();
        } else {
            teardownObjective(server.getScoreboard());
        }

        return EconomyConfig.get().scoreboardEnabled;
    }

    public AuctionManager getAuctions() {
        return auctions;
    }

    public OrderManager getOrders() {
        return orders;
    }

    public DeliveryManager getDeliveries() {
        return deliveries;
    }

    public NotificationManager getNotifications() {
        return notifications;
    }

    public PriceRegistry getPrices() {
        return prices;
    }

    /**
     * Every balance on the server, added up.
     *
     * <p>Added for D19: Monarchy's inflation factor is the server's money supply, so it needs the total, and
     * nothing else here keeps a running sum. Accumulated in {@code double} because the tax that consumes it is
     * a double anyway, and because summing enough longs to overflow is possible in principle and would wrap to
     * a negative supply — a negative inflation factor that would then be clamped, quietly.
     *
     * <p>O(players) and only called by the daily fiscal pass.
     */
    public double totalMoneyInCirculation() {
        double total = 0.0;
        for (long balance : balances.values()) {
            if (balance > 0L) total += balance;
        }
        return total;
    }

    /** Accumulated online time, the clock the spec's 45-minute thresholds read. */
    public OnlineTimeService getOnlineTime() {
        return onlineTime;
    }

    /** Shared wall-clock cooldowns for every job and party effect. */
    public CooldownService getCooldowns() {
        return cooldowns;
    }

    /** Party selections and their 30-hour lockouts. */
    public FactionStore getFactions() {
        return factions;
    }

    /** Profession selections, progress and rust state. */
    public ProfessionStore getProfessions() {
        return professions;
    }

    /** The tab row, nametag prefix and chat icon, and the cache that keeps them in sync. */
    public TagDisplayService getTagDisplay() {
        return tagDisplay;
    }

    /** The resolved building-block, ore and Haste trigger sets. */
    public BlockTags getBlockTags() {
        return blockTags;
    }

    /**
     * Advances the tag data layer by one tick. Called from {@code TickEvent.SERVER_POST}.
     *
     * <p>Two things happen, and neither is gameplay:
     *
     * <ol>
     *   <li>Online time is accumulated for everyone currently online. This runs even when the professions
     *       section is disabled, because online time is also the clock the party levies will read; switching
     *       the feature on should not start every player's counters from zero.</li>
     *   <li>Time is added towards clearing a rust timer, and a player who has served it is promoted back to
     *       Master. This is the one place Phase 2 writes to a store on a timer rather than on an event, and it
     *       is skipped entirely when {@code professions.enabled} is false.</li>
     * </ol>
     *
     * <p>Nothing here is on the hot path: the only per-tick cost is one map iteration over online players, and
     * {@link ProfessionStore#completeRustIfEarned} returns immediately for the overwhelming majority of them.
     */
    public void tickTagServices() {
        long tickCount = server.getTickCount();

        onlineScratch.clear();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            onlineScratch.add(player.getUUID());
        }

        onlineTime.tick(tickCount, onlineScratch);
        sweepTagDisplay();

        if (!EconomyConfig.get().professions.enabled) return;

        // Periodic crop boost for online Farmers (spec 48: Tươi tốt)
        if (tickCount % 20 == 0) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                FarmerEffects.tickCropBoost(this, player);
            }
        }

        // Miner lava contact check (spec 55: Bảo hộ lao động)
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.isInLava()) {
                MinerEffects.onLavaContact(this, player);
            }
        }

        long credited = onlineTime.lastCreditedIntervalMillis();
        if (credited <= 0L) return;

        int rustMinutes = EconomyConfig.get().professions.rustOnlineMinutes;
        for (UUID player : onlineScratch) {
            try {
                if (!professions.completeRustIfEarned(player, credited, rustMinutes)) continue;
                // The level just moved, so any persistent effect derived from it has to be re-applied. Only the
                // players whose timer actually completed do any work.
                ServerPlayer online = server.getPlayerList().getPlayer(player);
                if (online != null) ProfessionEffects.applyPersistent(online);
            } catch (Exception e) {
                LOGGER.error("[EconomyCraft] Failed to advance the rust timer for {}", player, e);
            }
        }
    }

    /**
     * The display sweep, on a deliberately slow interval.
     *
     * <p>Every tag surface is cached, and a cache that only refreshes when a caller remembers to call
     * {@code refresh} is a cache that shows a stale tag the first time a phase forgets. This compares a cheap
     * signature per online player and re-pushes only the ones that actually changed, so correctness does not
     * depend on that, at the cost of three hash lookups per player every five seconds.
     *
     * <p>Not gated on {@code professions.enabled} or {@code factions.enabled} on purpose: switching a feature off
     * has to <em>remove</em> tags, and this is the path that notices.
     */
    private void sweepTagDisplay() {
        if (server.getTickCount() % TAG_SWEEP_INTERVAL_TICKS != 0) return;
        tagDisplay.sweep(server.getPlayerList().getPlayers());
    }

    public void markActive(UUID player) {
        dynamicPrices.markActive(player);
    }

    public void maybeRefreshDynamicPrices() {
        dynamicPrices.maybeRefresh(server, balances);
    }

    public void refreshDynamicPrices() {
        dynamicPrices.refresh(server, balances);
    }

    public double getDynamicPriceMultiplier() {
        return dynamicPrices.getMultiplier();
    }

    public double getDynamicPriceMedian() {
        return dynamicPrices.getMedianBalance();
    }

    public long getLastSeenMs(UUID player) {
        return dynamicPrices.getLastSeenMs(player);
    }

    /** Runs the daily wealth-tax pass if the epoch day rolled over. Safe to call every tick. */
    public FiscalPass.Report runFiscalPassIfDue() {
        return fiscalPass.runIfDue();
    }

    /** Applies the fiscal pass immediately, ignoring the day rollover. */
    public FiscalPass.Report forceFiscalPass() {
        requireServerThread();
        return fiscalPass.runNow();
    }

    public void resetFiscalState() {
        requireServerThread();
        fiscalPass.resetState();
    }

    public boolean isDynamicPricingActive(String category, boolean itemEnabled) {
        return EconomyConfig.get().dynamicPricesEnabled && prices.isDynamicPricingEnabled(category, itemEnabled);
    }

    public long getEffectiveBuyPrice(long baseBuyPrice, boolean dynamicPricingActive) {
        if (baseBuyPrice <= 0 || !dynamicPricingActive) return baseBuyPrice;
        return dynamicPrices.applyMultiplier(baseBuyPrice);
    }

    public long getEffectiveBuyPrice(long baseBuyPrice, String category, boolean itemEnabled) {
        if (baseBuyPrice <= 0) return baseBuyPrice;
        return getEffectiveBuyPrice(baseBuyPrice, isDynamicPricingActive(category, itemEnabled));
    }

    public long getEffectiveBuyPrice(PriceRegistry.PriceEntry entry) {
        return getEffectiveBuyPrice(entry.unitBuy(), entry.category(), entry.dynamicPriceEnabled());
    }

    public Map<UUID, Long> getBalances() {
        return Map.copyOf(balances);
    }

    public Map<UUID, Long> getBalancesSnapshot() {
        requireServerThread();
        return Map.copyOf(balances);
    }

    public void removePlayer(UUID id) {
        requireServerThread();
        balanceMutations.delete(id);
    }

    public int resetAllBalances() {
        requireServerThread();
        long starting = clamp(EconomyConfig.get().startingBalance);
        int changed = 0;
        for (UUID id : new ArrayList<>(balances.keySet())) {
            long previous = balances.get(id);
            if (previous == starting) continue;
            balances.put(id, starting);
            balanceEvents.emit(new BalanceChangeEvent(id, previous, starting, BalanceMutationType.SET,
                    Optional.empty(), Optional.of(EconomySources.ADMIN_RESET), Optional.empty()));
            changed++;
        }
        updateLeaderboard();
        save();
        return changed;
    }

    public void resetDailyRewards() {
        requireServerThread();
        lastDaily.clear();
        save();
    }

    public void resetDailySellLimits() {
        requireServerThread();
        dailySells.clear();
        save();
    }

    public void resetStats() {
        requireServerThread();
        stats.clear();
        save();
    }

    /**
     * Drops one player's earned/spent/sold/bought history. Lets an admin clear out a stale
     * duplicate profile that would otherwise hold a place on the stats leaderboards without
     * ever having held a balance.
     *
     * @return true when a row was actually removed
     */
    public boolean clearStats(UUID id) {
        requireServerThread();
        if (stats.remove(id) == null) return false;
        updateLeaderboard();
        save();
        return true;
    }

    public void resetTransactionLog() {
        requireServerThread();
        TransactionLogWriter.clearAll(logsDir);
    }

    public boolean claimDaily(UUID player) {
        long today = LocalDate.now().toEpochDay();
        long last = lastDaily.getOrDefault(player, -1L);
        if (last == today) return false;
        BalanceMutationResult result = addMoney(player, EconomyConfig.get().dailyAmount, EconomySources.DAILY_REWARD);
        if (!result.successful()) return false;
        lastDaily.put(player, today);
        save();
        return true;
    }

    public boolean hasClaimedDailyToday(UUID player) {
        return lastDaily.getOrDefault(player, -1L) == LocalDate.now().toEpochDay();
    }

    public boolean tryRecordDailySell(UUID player, long saleAmount) {
        long limit = EconomyConfig.get().dailySellLimit;
        if (limit <= 0) return false;

        DailySellData data = getOrCreateTodaySellData(player);
        long newTotal = data.amount() + saleAmount;
        if (newTotal > limit) {
            return true;
        }

        dailySells.put(player, new DailySellData(data.day(), newTotal));
        save();
        return false;
    }

    public long getDailySellRemaining(UUID player) {
        long limit = EconomyConfig.get().dailySellLimit;
        if (limit <= 0) return Long.MAX_VALUE;

        DailySellData data = getOrCreateTodaySellData(player);
        return Math.max(0, limit - data.amount());
    }

    private DailySellData getOrCreateTodaySellData(UUID player) {
        long today = LocalDate.now().toEpochDay();
        DailySellData data = dailySells.get(player);
        if (data == null || data.day() != today) {
            data = new DailySellData(today, 0L);
            dailySells.put(player, data);
        }
        return data;
    }

    private long clamp(long value) {
        return Math.clamp(value, 0, MAX);
    }

    public void requireServerThread() {
        if (!server.isSameThread()) {
            throw new IllegalStateException("EconomyCraft API must be called from the server thread");
        }
    }

    private record DailySellData(long day, long amount) {}

    private record PlayerStats(long earned, long spent, long sold, long bought) {}
}
