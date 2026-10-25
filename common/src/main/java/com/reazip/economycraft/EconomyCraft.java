package com.reazip.economycraft;

import com.mojang.logging.LogUtils;
import com.mojang.brigadier.CommandDispatcher;
import com.reazip.economycraft.auction.AuctionExpiration;
import com.reazip.economycraft.orders.OrderFulfillment;
import com.reazip.economycraft.util.AsyncFileWriter;
import com.reazip.economycraft.util.BookInputUi;
import com.reazip.economycraft.api.v1.EconomyCraftApiBootstrap;
import com.reazip.economycraft.negotiation.NegotiationEvents;
import com.reazip.economycraft.negotiation.OffersHubUi;
import com.reazip.economycraft.util.ChatCompat;
import com.reazip.economycraft.util.EconomyPaths;
import com.reazip.economycraft.util.IdentityCompat;
import com.reazip.economycraft.util.ProfileCompat;
import com.reazip.economycraft.faction.FactionEffects;
import com.reazip.economycraft.profession.ProfessionHaste;
import com.reazip.economycraft.profession.ProfessionEffects;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import com.reazip.economycraft.gossip.*;
import java.util.Locale;
import java.util.regex.Pattern;

public final class EconomyCraft {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final String MOD_ID = "economycraft";
    private static final Object MANAGER_LOCK = new Object();
    private static volatile EconomyManager manager;
    private static volatile MinecraftServer lastServer;
    private static final int EXPIRATION_CHECK_INTERVAL_TICKS = 20 * 60;

    public static final CooldownTracker GOSSIP_COOLDOWN_TRACKER =
            new CooldownTracker();
    public static final com.reazip.economycraft.gossip.RecentSpokenTracker GOSSIP_RECENT_SPOKEN =
            new com.reazip.economycraft.gossip.RecentSpokenTracker(8);
    private static volatile com.reazip.economycraft.gossip.GossipApiClient gossipApiClient;
    private static volatile com.reazip.economycraft.gossip.storage.VillagerDatabase villagerDatabase;
    private static volatile com.reazip.economycraft.gossip.memory.VillagerMemoryService villagerMemoryService;

    public static @Nullable com.reazip.economycraft.gossip.memory.VillagerMemoryService getVillagerMemoryService() {
        return villagerMemoryService;
    }

    public static com.reazip.economycraft.gossip.RecentSpokenTracker getGossipRecentSpoken() {
        return GOSSIP_RECENT_SPOKEN;
    }

    public static void init() {
        if (EconomyCraftApiBootstrap.INITIALIZED == null) {
            throw new IllegalStateException("EconomyCraft API bootstrap failed");
        }
        VillagerGossipListener.init(
                GOSSIP_COOLDOWN_TRACKER,
                () -> EconomyConfig.get().geminiGossip,
                GOSSIP_RECENT_SPOKEN);
    }

    public static void onServerStarting(MinecraftServer server) {
        EconomyConfig.load(server);
        WebhookConfig.load(server);
    }

    public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher,
                                        CommandBuildContext buildContext,
                                        Commands.CommandSelection selection) {
        EconomyCommands.register(dispatcher, buildContext, selection);
    }

    public static void onServerStarted(MinecraftServer server) {
        getManager(server);
        reloadGossipService(server);
        com.reazip.economycraft.market.MarketSnapshotServer.start(server);
    }

    public static void onServerStopping(MinecraftServer server) {
        com.reazip.economycraft.market.MarketSnapshotServer.stop(server);
        VillagerGossipListener.setMemoryService(null);
        if (villagerDatabase != null) {
            try {
                villagerDatabase.close();
            } catch (Exception e) {
                LOGGER.warn("[EconomyCraft] Error closing villager database", e);
            } finally {
                villagerDatabase = null;
                villagerMemoryService = null;
            }
        }
        gossipApiClient = null;
        if (manager != null && lastServer == server) {
            manager.deactivate();
            manager.save();
            manager.closeDatabase();
        }
        AsyncFileWriter.flush();
    }

    public static CooldownTracker getGossipCooldownTracker() {
        return GOSSIP_COOLDOWN_TRACKER;
    }

    public static @Nullable com.reazip.economycraft.gossip.GossipApiClient getGossipApiClient() {
        return gossipApiClient;
    }

    /**
     * Hot-reloads the AI gossip client and memory service from disk without restarting the server.
     */
    public static synchronized void reloadGossipService(MinecraftServer server) {
        try {
            EconomyConfig.load(server);
            var cfg = EconomyConfig.get();
            var config = cfg != null ? cfg.geminiGossip : null;

            gossipApiClient = null;

            if (config != null && config.enabled()) {
                var client = new com.reazip.economycraft.gossip.GossipApiClient(config);
                gossipApiClient = client;

                if (villagerDatabase == null) {
                    java.nio.file.Path dbPath = com.reazip.economycraft.util.EconomyPaths.dataDir(server).resolve(com.reazip.economycraft.db.EconomyDatabase.DB_FILE_NAME);
                    villagerDatabase = new com.reazip.economycraft.gossip.storage.VillagerDatabase(dbPath);
                    villagerDatabase.initialize();
                }

                villagerMemoryService = new com.reazip.economycraft.gossip.memory.VillagerMemoryService(
                        villagerDatabase,
                        client,
                        () -> EconomyConfig.get().geminiGossip,
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
                        GOSSIP_RECENT_SPOKEN
                );
                com.reazip.economycraft.gossip.VillagerGossipListener.setMemoryService(villagerMemoryService);
                com.reazip.economycraft.gossip.VillagerGossipListener.setRecentSpokenTracker(GOSSIP_RECENT_SPOKEN);

                LOGGER.info("[EconomyCraft-AI] Gossip service live reloaded! Model: {}, BaseUrl: {}", config.model(), config.baseUrl());
            } else {
                com.reazip.economycraft.gossip.VillagerGossipListener.setMemoryService(null);
                villagerMemoryService = null;
                gossipApiClient = null;
                LOGGER.info("[EconomyCraft-AI] Gossip service disabled.");
            }
        } catch (Exception e) {
            LOGGER.error("[EconomyCraft-AI] Failed to reload gossip service", e);
        }
    }

    public static void onServerTick(MinecraftServer server) {
        TollHud.tick(server);
        com.reazip.economycraft.motd.MotdService.tick(server);
        BookInputUi.tick(server);
        // D20's removal half: the break hook only fires on ticks where a mining packet arrived, so without this
        // a player who stops mid-block would keep the bridged window's worth of Haste indefinitely.
        ProfessionHaste.expireStale(server, server.getTickCount());
        try {
            EconomyCraft.getManager(server).runFiscalPassIfDue();
        } catch (Exception e) {
            LOGGER.error("[EconomyCraft] Failed to run the daily fiscal pass", e);
        }
        try {
            EconomyCraft.getManager(server).runFactionFiscalPassIfDue();
        } catch (Exception e) {
            LOGGER.error("[EconomyCraft] Failed to run the daily faction fiscal pass", e);
        }
        try {
            EconomyCraft.getManager(server).tickTagServices();
        } catch (Exception e) {
            LOGGER.error("[EconomyCraft] Failed to advance the tag data layer", e);
        }
        if (server.getTickCount() % EXPIRATION_CHECK_INTERVAL_TICKS != 0) return;

        EconomyManager eco = getManager(server);
        try {
            OrderFulfillment.expireOverdue(eco);
        } catch (Exception e) {
            LOGGER.error("[EconomyCraft] Failed to process order expirations", e);
        }
        try {
            com.reazip.economycraft.contracts.ContractService.processDeadlines(eco);
        } catch (Exception e) {
            LOGGER.error("[EconomyCraft] Failed to process contract deadlines", e);
        }
        try {
            eco.getQuests().sweep(eco);
        } catch (Exception e) {
            LOGGER.error("[EconomyCraft] Failed to process the quest board sweep", e);
        }
        try {
            AuctionExpiration.expireOverdue(eco);
        } catch (Exception e) {
            LOGGER.error("[EconomyCraft] Failed to process auction expirations", e);
        }
        try {
            eco.maybeRefreshDynamicPrices();
        } catch (Exception e) {
            LOGGER.error("[EconomyCraft] Failed to refresh dynamic shop prices", e);
        }
    }

    public static void onPlayerJoin(ServerPlayer player) {
        try {
            MinecraftServer server = player.level().getServer();
            ProfileCompat.cacheName(server, player.getUUID(), IdentityCompat.of(player).name());

            EconomyManager eco = getManager(server);
            eco.rememberPlayerName(player.getUUID(), IdentityCompat.of(player).name());
            if (eco.getBalance(player.getUUID(), false) == null) {
                eco.getBalance(player.getUUID(), true);
            } else {
                eco.refreshLeaderboard();
            }

            eco.markActive(player.getUUID());
            eco.getTagDisplay().applyTo(player);
            eco.getNotifications().sendPending(player);
            // A join is the one moment a Builder's reach is guaranteed missing: attribute instances are rebuilt
            // per player, so a store entry saying "Master" with no modifier on them is a broken feature.
            ProfessionEffects.applyPersistent(player);

            if (eco.getDeliveries().hasDeliveries(player.getUUID())) {
                sendPrompt(player, "You have unclaimed items: ", "[Claim]", "/eco deliveries");
            }

            // Price offers work like deliveries: the per-event messages above already queued while
            // offline (sendPending), and this aggregate points at the hub. It used to link /ah,
            // which silently ignored order offers entirely. The negotiator side needs no aggregate
            // — every accept, decline, reprice and withdrawal notifies them directly.
            int[] offerCounts = OffersHubUi.counts(eco, player.getUUID());
            if (offerCounts[0] > 0) {
                sendPrompt(player, "You have " + offerCounts[0]
                                + " price offer(s) on your listings and requests: ",
                        "[Review]", NegotiationEvents.hubCommand());
            }

            if (EconomyPaths.hasSharedFolder(server)) {
                sendPrompt(player, "Found an older EconomyCraft setup. ", "[Import]", "/eco import");
            }

            com.reazip.economycraft.motd.MotdService.scheduleOnJoin(player);
        } catch (Exception e) {
            LOGGER.error("[EconomyCraft] Failed to set up {} on join", player.getName().getString(), e);
        }
    }

    /**
     * Drops the player from their tag team and out of the display cache.
     *
     * <p>Needed because the cache is keyed by UUID and would otherwise keep an entry — and a team membership — for
     * everyone who has ever connected. Deliberately does not save anything: the stores already own the persisted
     * state, and a tag is derived from it.
     */
    public static void onPlayerQuit(ServerPlayer player) {
        com.reazip.economycraft.motd.MotdService.onPlayerQuit(player);
        // Restore the stashed item of an unfinished book input before the inventory is saved.
        BookInputUi.forget(player);
        // Drop the Haste refresh bookkeeping; a reconnect starts clean rather than inheriting a stale window.
        ProfessionHaste.forget(player.getUUID());
        FactionEffects.forget(player);
        try {
            EconomyManager eco = getManager(player.level().getServer());
            eco.getTagDisplay().forget(player);
        } catch (Exception e) {
            LOGGER.error("[EconomyCraft] Failed to release tag display state for {}", player.getName().getString(), e);
        }
    }

    private static void sendPrompt(ServerPlayer player, String text, String label, String command) {
        ClickEvent ev = ChatCompat.runCommandEvent(command);

        if (ev != null) {
            Component msg = Component.literal(text)
                    .withStyle(ChatFormatting.YELLOW)
                    .append(Component.literal(label)
                            .withStyle(s -> s.withUnderlined(true).withColor(ChatFormatting.GREEN).withClickEvent(ev)));
            player.sendSystemMessage(msg);
        } else {
            ChatCompat.sendRunCommandTellraw(player, text, label, command);
        }
    }

    public static EconomyManager getManager(MinecraftServer server) {
        synchronized (MANAGER_LOCK) {
            if (manager == null || lastServer != server) {
                if (manager != null) manager.deactivate();
                manager = new EconomyManager(server);
                lastServer = server;
            }
            return manager;
        }
    }

    /** Returns the live manager for this server without creating one, or null. */
    public static EconomyManager peekManager(MinecraftServer server) {
        synchronized (MANAGER_LOCK) {
            return lastServer == server ? manager : null;
        }
    }

    public static boolean canImportSharedFolder() {
        MinecraftServer server = lastServer;
        return server != null && EconomyPaths.hasSharedFolder(server);
    }

    public static void reloadFromDisk(MinecraftServer server) {
        synchronized (MANAGER_LOCK) {
            com.reazip.economycraft.motd.MotdService.clearPending();
            if (manager != null && lastServer == server) {
                manager.detach();
            }
            manager = null;
            lastServer = null;

            EconomyConfig.load(server);
            WebhookConfig.load(server);
            getManager(server);
        }
    }

    public static String formatMoney(long amount) {
        return withCurrency(amount < 0, groupedNumber(Math.abs(amount)));
    }

    public static String formatMoneyChat(long amount) {
        String name = EconomyConfig.get().display.currencyName;
        if (name.isEmpty()) return formatMoney(amount);
        return (amount < 0 ? "-" : "") + groupedNumber(Math.abs(amount)) + " " + name;
    }

    private static String groupedNumber(long value) {
        DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(Locale.ROOT);
        symbols.setGroupingSeparator(EconomyConfig.get().display.balanceSeparator.charAt(0));
        return new DecimalFormat("#,##0", symbols).format(value);
    }

    private static String withCurrency(boolean negative, String number) {
        EconomyConfig.Display display = EconomyConfig.get().display;
        String symbol = display.currencySymbol;
        String sign = negative ? "-" : "";
        if (symbol.isEmpty()) return sign + number;
        if (display.currencySymbolPosition == EconomyConfig.CurrencyPosition.SUFFIX) {
            return sign + number + (Character.isLetter(symbol.codePointAt(0)) ? " " : "") + symbol;
        }
        return sign + symbol + (Character.isLetter(symbol.codePointBefore(symbol.length())) ? " " : "") + number;
    }

    public static String describeItem(int count, String name) {
        return count == 1 ? name : count + "x " + name;
    }

    public static String signedMoney(long amount) {
        return (amount < 0 ? "-" : "+") + formatMoney(Math.abs(amount));
    }

    public static String formatMultiplier(double multiplier) {
        DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(Locale.ROOT);
        return new DecimalFormat("0.##", symbols).format(multiplier) + "x";
    }

    private static final String[] SHORT_MONEY_SUFFIXES = {"", "k", "M", "B", "T", "Qa", "Qi"};
    private static final Pattern SHORT_MONEY_NUMBER = Pattern.compile("\\d+\\.?\\d*|\\.\\d+");

    public static boolean isShortMoneySuffix(String text) {
        if (text == null) return false;
        for (String suffix : SHORT_MONEY_SUFFIXES) {
            if (!suffix.isEmpty() && suffix.equalsIgnoreCase(text)) return true;
        }
        return false;
    }

    public static String formatMoneyShort(long amount) {
        long value = Math.abs(amount);
        int magnitude = 0;
        double scaled = value;
        while (scaled >= 1000 && magnitude < SHORT_MONEY_SUFFIXES.length - 1) {
            scaled /= 1000;
            magnitude++;
        }
        DecimalFormat format = new DecimalFormat("0.#", DecimalFormatSymbols.getInstance(Locale.ROOT));
        String number = format.format(scaled);
        if (magnitude < SHORT_MONEY_SUFFIXES.length - 1 && Double.parseDouble(number) >= 1000) {
            magnitude++;
            number = format.format(scaled / 1000);
        }
        return withCurrency(amount < 0, number + SHORT_MONEY_SUFFIXES[magnitude]);
    }

    public static @Nullable Long parseMoneyShort(String input) {
        if (input == null) return null;
        String s = input.trim();
        if (s.isEmpty()) return null;

        Long parsed = parsePlainMoneyShort(s);
        if (parsed != null) return parsed;
        String stripped = stripCurrency(s);
        return stripped.equals(s) ? null : parsePlainMoneyShort(stripped);
    }

    private static String stripCurrency(String input) {
        EconomyConfig.Display display = EconomyConfig.get().display;
        String s = stripAffix(input, display.currencyName);
        return stripAffix(s, display.currencySymbol);
    }

    private static String stripAffix(String input, String affix) {
        if (affix.isEmpty() || input.length() <= affix.length()) return input;
        if (input.regionMatches(true, 0, affix, 0, affix.length())) {
            return input.substring(affix.length()).trim();
        }
        int start = input.length() - affix.length();
        if (input.regionMatches(true, start, affix, 0, affix.length())) {
            return input.substring(0, start).trim();
        }
        return input;
    }

    private static @Nullable Long parsePlainMoneyShort(String s) {
        if (s.isEmpty()) return null;
        for (int magnitude = SHORT_MONEY_SUFFIXES.length - 1; magnitude >= 1; magnitude--) {
            String suffix = SHORT_MONEY_SUFFIXES[magnitude];
            if (s.length() > suffix.length()
                    && s.regionMatches(true, s.length() - suffix.length(), suffix, 0, suffix.length())) {
                String number = s.substring(0, s.length() - suffix.length()).trim();
                if (!SHORT_MONEY_NUMBER.matcher(number).matches()) return null;
                double scaled = Double.parseDouble(number) * Math.pow(1000, magnitude);
                if (!Double.isFinite(scaled) || scaled > Long.MAX_VALUE) return null;
                return Math.round(scaled);
            }
        }
        Long grouped = parseGroupedWhole(s);
        if (grouped != null) return grouped;
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Accepts the exact grouped-number form shown by {@link #formatMoney(long)}. */
    private static @Nullable Long parseGroupedWhole(String input) {
        String separator = EconomyConfig.get().display.balanceSeparator;
        if (separator == null || separator.length() != 1 || input.indexOf(separator) < 0) return null;
        String[] groups = input.split(Pattern.quote(separator), -1);
        if (groups.length < 2 || groups[0].isEmpty() || groups[0].length() > 3
                || !groups[0].chars().allMatch(Character::isDigit)) return null;
        for (int i = 1; i < groups.length; i++) {
            if (groups[i].length() != 3 || !groups[i].chars().allMatch(Character::isDigit)) return null;
        }
        try {
            return Long.parseLong(String.join("", groups));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
