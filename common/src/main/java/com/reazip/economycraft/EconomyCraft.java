package com.reazip.economycraft;

import com.mojang.logging.LogUtils;
import com.reazip.economycraft.auction.AuctionExpiration;
import com.reazip.economycraft.orders.OrderFulfillment;
import com.reazip.economycraft.util.AsyncFileWriter;
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
import dev.architectury.event.events.common.CommandRegistrationEvent;
import dev.architectury.event.events.common.LifecycleEvent;
import dev.architectury.event.events.common.PlayerEvent;
import dev.architectury.event.events.common.TickEvent;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

public final class EconomyCraft {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final String MOD_ID = "economycraft";
    private static final Object MANAGER_LOCK = new Object();
    private static volatile EconomyManager manager;
    private static volatile MinecraftServer lastServer;
    private static final int EXPIRATION_CHECK_INTERVAL_TICKS = 20 * 60;

    public static void registerEvents() {
        if (EconomyCraftApiBootstrap.INITIALIZED == null) {
            throw new IllegalStateException("EconomyCraft API bootstrap failed");
        }
        LifecycleEvent.SERVER_STARTING.register(EconomyConfig::load);
        LifecycleEvent.SERVER_STARTING.register(WebhookConfig::load);

        CommandRegistrationEvent.EVENT.register((dispatcher, registry, selection) -> {
            EconomyCommands.register(dispatcher, registry, selection);
        });

        LifecycleEvent.SERVER_STARTED.register(EconomyCraft::getManager);

        LifecycleEvent.SERVER_STOPPING.register(server -> {
            if (manager != null && lastServer == server) {
                manager.deactivate();
                manager.save();
            }
            AsyncFileWriter.flush();
        });

        PlayerEvent.PLAYER_JOIN.register(EconomyCraft::onPlayerJoin);
        PlayerEvent.PLAYER_QUIT.register(EconomyCraft::onPlayerQuit);
        TickEvent.SERVER_POST.register(EconomyCraft::onServerTick);
    }

    private static void onServerTick(MinecraftServer server) {
        TollHud.tick(server);
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

    private static void onPlayerJoin(ServerPlayer player) {
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
    private static void onPlayerQuit(ServerPlayer player) {
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

    public static boolean canImportSharedFolder() {
        MinecraftServer server = lastServer;
        return server != null && EconomyPaths.hasSharedFolder(server);
    }

    public static void reloadFromDisk(MinecraftServer server) {
        synchronized (MANAGER_LOCK) {
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
        DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(Locale.ROOT);
        symbols.setGroupingSeparator(EconomyConfig.get().balanceSeparator.charAt(0));
        return "$" + new DecimalFormat("#,##0", symbols).format(amount);
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

    private static final String[] SHORT_MONEY_SUFFIXES = {"", "k", "M", "B", "T"};

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
        return "$" + (amount < 0 ? "-" : "") + number + SHORT_MONEY_SUFFIXES[magnitude];
    }

    public static @Nullable Long parseMoneyShort(String input) {
        if (input == null) return null;
        String s = input.trim();
        if (s.isEmpty()) return null;

        for (int magnitude = SHORT_MONEY_SUFFIXES.length - 1; magnitude >= 1; magnitude--) {
            String suffix = SHORT_MONEY_SUFFIXES[magnitude];
            if (s.length() > suffix.length() && s.regionMatches(true, s.length() - suffix.length(), suffix, 0, suffix.length())) {
                double value;
                try {
                    value = Double.parseDouble(s.substring(0, s.length() - suffix.length()));
                } catch (NumberFormatException e) {
                    return null;
                }
                if (!Double.isFinite(value) || value < 0) return null;
                double scaled = value * Math.pow(1000, magnitude);
                if (scaled > Long.MAX_VALUE) return null;
                return Math.round(scaled);
            }
        }

        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
