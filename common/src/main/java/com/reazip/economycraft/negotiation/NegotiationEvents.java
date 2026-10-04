package com.reazip.economycraft.negotiation;

import com.reazip.economycraft.EconomyCraft;
import com.reazip.economycraft.EconomyManager;
import com.reazip.economycraft.auction.AuctionListing;
import com.reazip.economycraft.orders.OrderRequest;
import com.reazip.economycraft.quests.QuestManager;
import com.reazip.economycraft.util.ChatCompat;
import com.reazip.economycraft.util.MenuUiSupport;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

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
        NegotiationStore.Kind kind = auction ? NegotiationStore.Kind.AH : NegotiationStore.Kind.ORDER;
        eco.getNotifications().notify(owner, proposerName + " offered "
                + EconomyCraft.formatMoney(price) + " for your " + itemDesc
                + " (" + (auction ? "listing #" : "request #") + targetId + "). Open it to review, edit your price, or accept.");
        eco.getNotifications().flush();
        sendReviewPrompt(eco, owner, kind, targetId);
    }

    /**
     * The clickable shortcut to the offers hub, for owners who are online right now. It carries
     * the target, so the click lands on that one listing's offers instead of on the hub overview.
     * Offline owners are covered on login: the queued text above plus the aggregate prompt. This
     * lives outside the queued notification because the queue stores plain text only.
     */
    private static void sendReviewPrompt(EconomyManager eco, UUID owner, NegotiationStore.Kind kind,
                                          int targetId) {
        MinecraftServer server = eco.getServer();
        if (server == null) return;
        ServerPlayer player = server.getPlayerList().getPlayer(owner);
        if (player == null) return;
        String command = hubCommand(kind, targetId);
        ClickEvent ev = ChatCompat.runCommandEvent(command);
        if (ev != null) {
            Component msg = Component.literal("Review it now: ")
                    .withStyle(ChatFormatting.YELLOW)
                    .append(Component.literal("[Review]")
                            .withStyle(s -> s.withUnderlined(true).withColor(ChatFormatting.GREEN)
                                    .withClickEvent(ev)));
            player.sendSystemMessage(msg);
        } else {
            ChatCompat.sendRunCommandTellraw(player, "Review it now: ", "[Review]", command);
        }
    }

    /**
     * The offers-hub deep link for one target. Always the {@code /eco} root, never the standalone
     * alias: standalone commands can be switched off in config, and a notification link that
     * silently does nothing is worse than a slightly longer command.
     */
    public static String hubCommand(NegotiationStore.Kind kind, int targetId) {
        return "/eco offers " + (kind == NegotiationStore.Kind.AH ? "ah " : "order ") + targetId;
    }

    /** The offers-hub overview link, for the aggregate login prompt. */
    public static String hubCommand() {
        return "/eco offers";
    }

    /** The offerer pulled their offer; the owner is told, since they were reviewing a queue. */
    public static void notifyWithdrawn(EconomyManager eco, UUID owner, UUID withdrawer,
                                       String itemDesc, long price) {
        eco.getNotifications().notify(owner, displayName(eco.getServer(), withdrawer)
                + " withdrew their offer of " + EconomyCraft.formatMoney(price)
                + " for " + itemDesc + ".");
        eco.getNotifications().flush();
    }

    public static void notifyAccepted(EconomyManager eco, UUID proposer, String itemDesc, long price) {
        eco.getNotifications().notify(proposer, "Your offer of " + EconomyCraft.formatMoney(price)
                + " for " + itemDesc + " was accepted — the price is now "
                + EconomyCraft.formatMoney(price) + ". Buy it before someone else does.");
        eco.getNotifications().flush();
    }

    /** Binding AH acceptance: the buyer was charged and the item is theirs (or in deliveries). */
    public static void notifySoldToBuyer(EconomyManager eco, UUID buyer, String itemDesc, long totalPaid,
                                         boolean stored) {
        eco.getNotifications().notify(buyer, "Your offer for " + itemDesc + " was accepted — you bought it for "
                + EconomyCraft.formatMoney(totalPaid)
                + (stored ? ". It was stored: type /eco deliveries to claim it." : "."));
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

    public static String displayName(MinecraftServer server, UUID player) {
        String name = MenuUiSupport.resolvePlayerName(server, player);
        return name != null ? name : "A player";
    }
}
