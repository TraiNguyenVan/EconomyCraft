package com.reazip.economycraft.auction;

import com.reazip.economycraft.EconomyCraft;
import com.reazip.economycraft.EconomyManager;
import com.reazip.economycraft.EconomySources;
import com.reazip.economycraft.api.v1.PaymentResult;
import com.reazip.economycraft.negotiation.NegotiationEvents;
import com.reazip.economycraft.negotiation.NegotiationStore;
import com.reazip.economycraft.profession.ProfessionHooks;
import com.reazip.economycraft.quests.QuestManager;
import com.reazip.economycraft.tax.TaxPolicy;
import com.reazip.economycraft.tax.TaxQuote;
import com.reazip.economycraft.tax.TaxScope;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public final class AuctionTrade {
    private AuctionTrade() {}

    public enum PurchaseStatus { OK, LISTING_GONE, OWN_LISTING, CANT_AFFORD, SELLER_CANT_RECEIVE }

    public record PurchaseResult(PurchaseStatus status, ItemStack item, long totalPaid, UUID seller, boolean stored) {
        public boolean success() {
            return status == PurchaseStatus.OK;
        }
    }

    public enum CancelStatus { OK, LISTING_GONE, NOT_OWNER }

    public record CancelResult(CancelStatus status, ItemStack item, boolean stored) {
        public boolean success() {
            return status == CancelStatus.OK;
        }
    }

    public enum AcceptStatus { OK, LISTING_GONE, NOT_OWNER, NOT_NEGOTIABLE, OFFER_GONE, CANT_AFFORD, SELLER_CANT_RECEIVE }

    public record AcceptResult(AcceptStatus status, ItemStack item, long price, long totalPaid, UUID buyer,
                               boolean stored) {
        public boolean success() {
            return status == AcceptStatus.OK;
        }
    }

    public static PurchaseResult purchase(EconomyManager eco, ServerPlayer buyer, int listingId) {
        AuctionManager auctions = eco.getAuctions();
        AuctionListing peek = auctions.getListing(listingId);
        if (peek == null) {
            return new PurchaseResult(PurchaseStatus.LISTING_GONE, ItemStack.EMPTY, 0, null, false);
        }
        if (peek.seller.equals(buyer.getUUID())) {
            return new PurchaseResult(PurchaseStatus.OWN_LISTING, peek.item.copy(), 0, peek.seller, false);
        }

        AuctionListing claimed = auctions.removeListing(listingId);
        if (claimed == null) {
            return new PurchaseResult(PurchaseStatus.LISTING_GONE, ItemStack.EMPTY, 0, null, false);
        }

        long cost = claimed.price;
        // A buyback listing is tax-free by design: the buyer pays the sticker price and the whole
        // of it refills the bot wallet outside the mint cap. Faction rules never see the bot, and
        // the bot never reads sale notifications, so both are skipped — not exempted, skipped.
        boolean buyback = QuestManager.BOT_UUID.equals(claimed.seller);
        long total = cost;
        if (!buyback) {
            TaxQuote quote = TaxPolicy.resolve(TaxScope.TRANSACTION_AUCTION_BUY, cost, buyer.getUUID(), claimed.seller, eco);
            total = quote.total();
        }

        String detail = EconomyCraft.describeItem(claimed.item.getCount(), claimed.item.getHoverName().getString());
        PaymentResult payment = eco.transferMoney(buyer.getUUID(), claimed.seller, total, cost,
                buyback ? EconomySources.QUEST_BUYBACK : EconomySources.AUCTION_PURCHASE, detail);
        if (!payment.successful()) {
            auctions.restoreListing(claimed);
            PurchaseStatus status = payment.status() == com.reazip.economycraft.api.v1.BalanceMutationStatus.MAX_BALANCE_EXCEEDED
                    ? PurchaseStatus.SELLER_CANT_RECEIVE : PurchaseStatus.CANT_AFFORD;
            return new PurchaseResult(status, claimed.item.copy(), 0, claimed.seller, false);
        }

        ProfessionHooks.onAuctionPurchase(buyer);

        if (!buyback) {
            auctions.notifySellerSale(claimed, buyer);
        }

        NegotiationEvents.invalidateTarget(eco, NegotiationStore.Kind.AH, claimed.id,
                EconomyCraft.describeItem(claimed.item.getCount(), claimed.item.getHoverName().getString()),
                "was sold");

        boolean stored = deliverOrStore(auctions, buyer, claimed.item.copy());
        return new PurchaseResult(PurchaseStatus.OK, claimed.item.copy(), total, claimed.seller, stored);
    }

    /**
     * Binding offer acceptance: sells the listing to the offerer immediately, at the offered
     * price, instead of merely repricing. Works whether the buyer is online or not — money
     * moves through balances either way, and the item lands in their inventory or, failing
     * that, in their deliveries mailbox.
     *
     * <p>Possible only because the listing already escrows the item. Order requests have no
     * such escrow (the goods sit in the fulfiller's inventory), so they keep the non-binding
     * reprice path.
     */
    public static AcceptResult acceptOffer(EconomyManager eco, ServerPlayer seller, int listingId, UUID buyerId) {
        AuctionManager auctions = eco.getAuctions();
        AuctionListing peek = auctions.getListing(listingId);
        if (peek == null) {
            return new AcceptResult(AcceptStatus.LISTING_GONE, ItemStack.EMPTY, 0, 0, buyerId, false);
        }
        if (!peek.seller.equals(seller.getUUID())) {
            return new AcceptResult(AcceptStatus.NOT_OWNER, ItemStack.EMPTY, 0, 0, buyerId, false);
        }
        if (!NegotiationEvents.canNegotiateAuction(peek)) {
            return new AcceptResult(AcceptStatus.NOT_NEGOTIABLE, ItemStack.EMPTY, 0, 0, buyerId, false);
        }
        NegotiationStore.Offer offer =
                eco.getNegotiations().offerFrom(NegotiationStore.Kind.AH, listingId, buyerId);
        if (offer == null) {
            return new AcceptResult(AcceptStatus.OFFER_GONE, peek.item.copy(), peek.price, 0, buyerId, false);
        }

        AuctionListing claimed = auctions.removeListing(listingId);
        if (claimed == null) {
            return new AcceptResult(AcceptStatus.LISTING_GONE, ItemStack.EMPTY, 0, 0, buyerId, false);
        }

        // The handshake price: everything downstream (tax quote, transfer, messages) must see
        // the agreed price, not the original ask.
        long asking = claimed.price;
        claimed.price = offer.price();

        ServerPlayer buyer = eco.getServer().getPlayerList().getPlayer(buyerId);
        Execution execution = execute(eco, buyerId, buyer, claimed, asking);
        AcceptStatus status = switch (execution.status()) {
            case OK -> AcceptStatus.OK;
            case CANT_AFFORD -> AcceptStatus.CANT_AFFORD;
            case SELLER_CANT_RECEIVE -> AcceptStatus.SELLER_CANT_RECEIVE;
            default -> AcceptStatus.LISTING_GONE;
        };
        return new AcceptResult(status, claimed.item.copy(), claimed.price, execution.totalPaid(), buyerId,
                execution.stored());
    }

    private record Execution(PurchaseStatus status, long totalPaid, boolean stored) {}

    /**
     * The shared money-and-delivery half of a sale: tax-quoted transfer from buyer to seller,
     * restoring the listing when payment fails, profession hook for online buyers, and
     * inventory-or-mailbox delivery. The buyback path does not come here — it is tax-free by
     * design and settles differently.
     */
    private static Execution execute(EconomyManager eco, UUID buyerId, @Nullable ServerPlayer buyer,
                                     AuctionListing claimed, long restorePrice) {
        TaxQuote quote = TaxPolicy.resolve(TaxScope.TRANSACTION_AUCTION_BUY, claimed.price, buyerId,
                claimed.seller, eco);
        long total = quote.total();

        String detail = EconomyCraft.describeItem(claimed.item.getCount(), claimed.item.getHoverName().getString());
        PaymentResult payment = eco.transferMoney(buyerId, claimed.seller, total, claimed.price,
                EconomySources.AUCTION_PURCHASE, detail);
        if (!payment.successful()) {
            // The sale failed but the offer stands: the listing goes back at the seller's
            // original ask, never at the price that just bounced.
            claimed.price = restorePrice;
            eco.getAuctions().restoreListing(claimed);
            PurchaseStatus status = payment.status() == com.reazip.economycraft.api.v1.BalanceMutationStatus.MAX_BALANCE_EXCEEDED
                    ? PurchaseStatus.SELLER_CANT_RECEIVE : PurchaseStatus.CANT_AFFORD;
            return new Execution(status, 0, false);
        }

        if (buyer != null) {
            ProfessionHooks.onAuctionPurchase(buyer);
        }

        boolean stored = deliverOrStore(eco.getAuctions(), buyerId, buyer, claimed.item.copy());
        return new Execution(PurchaseStatus.OK, total, stored);
    }

    public static CancelResult cancel(AuctionManager auctions, ServerPlayer seller, int listingId) {
        AuctionListing peek = auctions.getListing(listingId);
        if (peek == null) {
            return new CancelResult(CancelStatus.LISTING_GONE, ItemStack.EMPTY, false);
        }
        if (!peek.seller.equals(seller.getUUID())) {
            return new CancelResult(CancelStatus.NOT_OWNER, ItemStack.EMPTY, false);
        }

        AuctionListing removed = auctions.removeListing(listingId);
        if (removed == null) {
            return new CancelResult(CancelStatus.LISTING_GONE, ItemStack.EMPTY, false);
        }

        NegotiationEvents.invalidateTarget(EconomyCraft.getManager(auctions.getServer()),
                NegotiationStore.Kind.AH, removed.id,
                EconomyCraft.describeItem(removed.item.getCount(), removed.item.getHoverName().getString()),
                "was removed");

        boolean stored = deliverOrStore(auctions, seller, removed.item.copy());
        return new CancelResult(CancelStatus.OK, removed.item.copy(), stored);
    }

    private static boolean deliverOrStore(AuctionManager auctions, ServerPlayer player, ItemStack stack) {
        boolean stored = !player.getInventory().add(stack);
        if (stored) {
            auctions.addDelivery(player.getUUID(), stack);
        }
        return stored;
    }

    private static boolean deliverOrStore(AuctionManager auctions, UUID buyerId, @Nullable ServerPlayer buyer,
                                          ItemStack stack) {
        if (buyer == null) {
            auctions.addDelivery(buyerId, stack);
            return true;
        }
        return deliverOrStore(auctions, buyer, stack);
    }
}
