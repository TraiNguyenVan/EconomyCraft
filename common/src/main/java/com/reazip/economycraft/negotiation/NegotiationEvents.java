package com.reazip.economycraft.negotiation;

import com.reazip.economycraft.EconomyCraft;
import com.reazip.economycraft.EconomyManager;
import com.reazip.economycraft.auction.AuctionListing;
import com.reazip.economycraft.orders.OrderRequest;
import com.reazip.economycraft.quests.QuestManager;
import com.reazip.economycraft.util.MenuUiSupport;
import net.minecraft.server.MinecraftServer;

import java.util.List;
import java.util.UUID;

/**
 * The negotiable / notify half of price offers. The store owns persistence; this class owns the
 * two policy questions (which targets accept offers) and the fan-out messages (who gets told).
 *
 * <p>Bot entries are never negotiable: a buyback listing and a server bounty order carry
 * mint-policy prices, and the bot account never reads messages, so offering on one would be a
 * message nobody answers about a price nobody may change.
 */
public final class NegotiationEvents {
    private NegotiationEvents() {}

    public static boolean canNegotiateAuction(AuctionListing listing) {
        return listing != null && !QuestManager.BOT_UUID.equals(listing.seller);
    }

    public static boolean canNegotiateOrder(OrderRequest request) {
        return request != null && !QuestManager.BOT_UUID.equals(request.requester);
    }

    /** Tells the owner someone offered, immediately if online or queued for login otherwise. */
    public static void notifyNewOffer(EconomyManager eco, UUID owner, UUID proposer,
                                      String itemDesc, long price, int targetId, boolean auction) {
        String proposerName = displayName(eco.getServer(), proposer);
        String where = auction ? "listing #" + targetId : "request #" + targetId;
        eco.getNotifications().notify(owner, proposerName + " offered "
                + EconomyCraft.formatMoney(price) + " for your " + itemDesc
                + " (" + where + "). Open it to review, edit your price, or accept.");
        eco.getNotifications().flush();
    }

    public static void notifyAccepted(EconomyManager eco, UUID proposer, String itemDesc, long price) {
        eco.getNotifications().notify(proposer, "Your offer of " + EconomyCraft.formatMoney(price)
                + " for " + itemDesc + " was accepted — the price is now "
                + EconomyCraft.formatMoney(price) + ". Buy it before someone else does.");
        eco.getNotifications().flush();
    }

    public static void notifyDeclined(EconomyManager eco, UUID proposer, String itemDesc, long price) {
        eco.getNotifications().notify(proposer, "Your offer of " + EconomyCraft.formatMoney(price)
                + " for " + itemDesc + " was declined.");
        eco.getNotifications().flush();
    }

    public static void notifyRepriced(EconomyManager eco, UUID proposer, String itemDesc, long newPrice) {
        eco.getNotifications().notify(proposer, "The price of " + itemDesc
                + " you offered on changed to " + EconomyCraft.formatMoney(newPrice) + ".");
        eco.getNotifications().flush();
    }

    /**
     * Drops every offer on a target that just disappeared and tells each proposer why.
     * The {@code reason} is a past-tense fragment, e.g. "was sold" or "expired".
     */
    public static void invalidateTarget(EconomyManager eco, NegotiationStore.Kind kind,
                                        int targetId, String itemDesc, String reason) {
        List<NegotiationStore.Offer> removed = eco.getNegotiations().removeForTarget(kind, targetId);
        for (NegotiationStore.Offer offer : removed) {
            eco.getNotifications().notify(offer.proposer(), "Your offer of "
                    + EconomyCraft.formatMoney(offer.price()) + " for " + itemDesc + " " + reason + ".");
        }
        if (!removed.isEmpty()) eco.getNotifications().flush();
    }

    /**
     * How many open offers sit on this player's own negotiable targets — the login prompt count,
     * mirroring the unclaimed-deliveries check.
     */
    public static int countOffersOnPlayerTargets(EconomyManager eco, UUID player) {
        int count = 0;
        NegotiationStore store = eco.getNegotiations();
        for (AuctionListing listing : eco.getAuctions().getListings()) {
            if (player.equals(listing.seller) && canNegotiateAuction(listing)) {
                count += store.countFor(NegotiationStore.Kind.AH, listing.id);
            }
        }
        for (OrderRequest request : eco.getOrders().getRequests()) {
            if (player.equals(request.requester) && canNegotiateOrder(request)) {
                count += store.countFor(NegotiationStore.Kind.ORDER, request.id);
            }
        }
        return count;
    }

    public static String displayName(MinecraftServer server, UUID player) {
        String name = MenuUiSupport.resolvePlayerName(server, player);
        return name != null ? name : "A player";
    }
}
