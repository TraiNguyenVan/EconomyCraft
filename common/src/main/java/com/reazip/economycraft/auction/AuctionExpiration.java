package com.reazip.economycraft.auction;

import com.reazip.economycraft.DeliveryManager;
import com.reazip.economycraft.EconomyManager;
import com.reazip.economycraft.PriceRegistry;
import com.reazip.economycraft.quests.QuestManager;
import com.reazip.economycraft.util.ExpirationUtil;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

public final class AuctionExpiration {
    private AuctionExpiration() {}

    private static final Logger LOGGER = LogUtils.getLogger();

    public static void expireOverdue(EconomyManager eco) {
        AuctionManager auctions = eco.getAuctions();
        DeliveryManager deliveries = eco.getDeliveries();
        long now = System.currentTimeMillis();
        boolean anyExpired = false;
        for (AuctionListing listing : auctions.getListings()) {
            if (!ExpirationUtil.isExpired(listing.expiresAt, now)) continue;

            AuctionListing removed = auctions.removeListing(listing.id, false);
            if (removed == null) continue;
            anyExpired = true;

            if (QuestManager.BOT_UUID.equals(removed.seller)) {
                returnBuybackToStock(eco, removed);
                continue;
            }

            ItemStack stack = removed.item.copy();
            auctions.addDelivery(removed.seller, stack, false);
            notifyExpired(eco, removed, stack);
        }
        if (anyExpired) {
            auctions.save();
            deliveries.save();
        }
        eco.getNotifications().flush();
    }

    private static void notifyExpired(EconomyManager eco, AuctionListing listing, ItemStack stack) {
        String itemName = stack.getHoverName().getString();
        String message = "Your auction listing for " + stack.getCount() + "x " + itemName
                + " expired; the item was returned to your deliveries.";
        eco.getNotifications().notify(listing.seller, message);
    }

    /**
     * Expired or admin-cleared buyback stock goes back on the ledger, not into a mailbox nobody
     * reads — the next quest sweep relists it, repriced. The bot is never notified: it never reads.
     */
    private static void returnBuybackToStock(EconomyManager eco, AuctionListing removed) {
        ItemStack stack = removed.item == null ? ItemStack.EMPTY : removed.item.copy();
        if (stack.isEmpty()) return;
        PriceRegistry.PriceEntry entry = eco.getPrices().resolve(stack);
        if (entry == null) {
            LOGGER.warn("[EconomyCraft] Buyback listing #{} expired for an unpriced item; parking it in the bot mailbox.",
                    removed.id);
            eco.getAuctions().addDelivery(QuestManager.BOT_UUID, stack, false);
            return;
        }
        eco.getQuestStock().deposit(entry.key(), stack.getCount());
        LOGGER.info("[EconomyCraft] Buyback listing #{} expired; {}x {} returned to quest stock and will relist.",
                removed.id, stack.getCount(), entry.key());
    }

    public static int clearAll(EconomyManager eco) {
        AuctionManager auctions = eco.getAuctions();
        DeliveryManager deliveries = eco.getDeliveries();
        int cleared = 0;
        for (AuctionListing listing : auctions.getListings()) {
            AuctionListing removed = auctions.removeListing(listing.id, false);
            if (removed == null) continue;
            cleared++;

            if (QuestManager.BOT_UUID.equals(removed.seller)) {
                returnBuybackToStock(eco, removed);
                continue;
            }

            ItemStack stack = removed.item.copy();
            auctions.addDelivery(removed.seller, stack, false);
            notifyCleared(eco, removed, stack);
        }
        if (cleared > 0) {
            auctions.save();
            deliveries.save();
        }
        eco.getNotifications().flush();
        return cleared;
    }

    private static void notifyCleared(EconomyManager eco, AuctionListing listing, ItemStack stack) {
        String itemName = stack.getHoverName().getString();
        String message = "Your auction listing for " + stack.getCount() + "x " + itemName
                + " was cleared by an admin; the item was returned to your deliveries.";
        eco.getNotifications().notify(listing.seller, message);
    }
}
