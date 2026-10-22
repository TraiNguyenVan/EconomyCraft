package com.reazip.economycraft.auction;

import com.mojang.logging.LogUtils;
import com.reazip.economycraft.EconomyConfig;
import com.reazip.economycraft.EconomyCraft;
import com.reazip.economycraft.EconomyManager;
import com.reazip.economycraft.NotificationManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.util.UUID;

/**
 * Turns auction events into chat notices.
 *
 * <p>Delivery is delegated to {@link NotificationManager} for both halves: it sends immediately to an
 * online player and queues for anyone else, so a notice reads the same whether the player was in game or
 * not — including the {@code [View]} button, which survives into the offline inbox.
 *
 * <p>Nothing here may fail an auction operation. A listing is already durable and a reprice already saved
 * by the time this runs, so a broken name lookup or a full queue must only cost the message.
 */
public final class AuctionNotifier {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final MinecraftServer server;

    public AuctionNotifier(MinecraftServer server) {
        this.server = server;
    }

    public void announceListing(AuctionListing listing) {
        EconomyConfig config = EconomyConfig.get();
        if (!config.auctionListingBroadcast) return;

        EconomyManager eco = EconomyCraft.peekManager(server);
        if (eco == null) return;

        String item = itemName(listing);
        int count = itemCount(listing);
        String text = AuctionNoticeText.listing(nameOf(eco, listing.seller), count, item, listing.price);
        publish(new NotificationManager.Notification(text, AuctionNoticeText.viewCommand(item), listing.id),
                listing.seller, eco, config);
        LOGGER.info("[EconomyCraft] Announced listing #{}: {}x {} for {}", listing.id, count, item, listing.price);
    }

    /**
     * @param previousPrice the price before this reprice; only the direction is used from it, because the
     *                      percentage is measured against the listing's first price
     */
    public void announcePriceDrop(AuctionListing listing, long previousPrice) {
        EconomyConfig config = EconomyConfig.get();
        if (!config.auctionListingBroadcast) return;
        if (listing.price >= previousPrice) return;

        long original = listing.originalPrice > 0 ? listing.originalPrice : previousPrice;
        if (AuctionNoticeText.percentOff(original, listing.price) <= 0) return;

        EconomyManager eco = EconomyCraft.peekManager(server);
        if (eco == null) return;

        String item = itemName(listing);
        int count = itemCount(listing);
        String text = AuctionNoticeText.priceDrop(count, item, original, listing.price);
        publish(new NotificationManager.Notification(text, AuctionNoticeText.viewCommand(item), listing.id),
                listing.seller, eco, config);
        LOGGER.info("[EconomyCraft] Announced price drop on listing #{}: {} -> {}", listing.id, original, listing.price);
    }

    private void publish(NotificationManager.Notification notification, UUID seller, EconomyManager eco,
                         EconomyConfig config) {
        NotificationManager notifications = eco.getNotifications();

        for (ServerPlayer online : server.getPlayerList().getPlayers()) {
            if (online.getUUID().equals(seller)) continue;
            notifications.notify(online.getUUID(), notification);
        }

        long now = System.currentTimeMillis();
        int activeDays = config.auctionListingNotifyActiveDays;
        if (activeDays > 0) {
            for (UUID player : eco.getBalances().keySet()) {
                if (server.getPlayerList().getPlayer(player) != null) continue;
                if (!AuctionNoticeAudience.shouldQueue(player, seller, eco.getLastSeenMs(player), now, activeDays)) {
                    continue;
                }
                notifications.notify(player, notification);
            }
        }
        notifications.flush();
    }

    private static String nameOf(EconomyManager eco, UUID seller) {
        String name = seller == null ? null : eco.getBestName(seller);
        return name == null || name.isBlank() ? "someone" : name;
    }

    private static String itemName(AuctionListing listing) {
        ItemStack stack = listing.item;
        return stack == null || stack.isEmpty() ? "item" : stack.getHoverName().getString();
    }

    private static int itemCount(AuctionListing listing) {
        ItemStack stack = listing.item;
        return stack == null || stack.isEmpty() ? 0 : stack.getCount();
    }
}
