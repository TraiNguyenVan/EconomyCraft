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
import com.reazip.economycraft.util.TransactionItems;
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

    /**
     * The GUI entry point for buying. Delegates: the sale itself is {@link #purchaseFor}, which is
     * the same code the HTTP route runs, so the menu and the web route cannot drift on the tax
     * quote, the buyback branch, the seller notification or the delivery fallback.
     */
    public static PurchaseResult purchase(EconomyManager eco, ServerPlayer buyer, int listingId) {
        return purchaseFor(eco, buyer.getUUID(), listingId);
    }

    /**
     * One auction purchase, addressed by uuid so it works for a buyer who is not logged in. Money
     * moves through balances either way, the item lands in their inventory if they are here and in
     * their deliveries mailbox otherwise, and the seller is told in both cases.
     *
     * <p>The steps are ordered so a refusal is a no-op: the listing is claimed before anything is
     * paid and is restored if the payment fails, and the item is delivered only after the money has
     * moved. Everything the private {@code execute} skips is here on purpose — this is the full
     * path, not the offer-acceptance half of it.
     */
    public static PurchaseResult purchaseFor(EconomyManager eco, UUID buyerId, int listingId) {
        AuctionManager auctions = eco.getAuctions();
        AuctionListing peek = auctions.getListing(listingId);
        if (peek == null) {
            return new PurchaseResult(PurchaseStatus.LISTING_GONE, ItemStack.EMPTY, 0, null, false);
        }
        if (peek.seller.equals(buyerId)) {
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
            TaxQuote quote = TaxPolicy.resolve(TaxScope.TRANSACTION_AUCTION_BUY, cost, buyerId, claimed.seller, eco);
            total = quote.total();
        }

        String detail = EconomyCraft.describeItem(claimed.item.getCount(), claimed.item.getHoverName().getString());
        PaymentResult payment = eco.transferMoney(buyerId, claimed.seller, total, cost,
                buyback ? EconomySources.QUEST_BUYBACK : EconomySources.AUCTION_PURCHASE, detail,
                TransactionItems.keyOf(claimed.item), claimed.item.getCount(), marketKeyOf(eco, claimed.item));
        if (!payment.successful()) {
            auctions.restoreListing(claimed);
            PurchaseStatus status = payment.status() == com.reazip.economycraft.api.v1.BalanceMutationStatus.MAX_BALANCE_EXCEEDED
                    ? PurchaseStatus.SELLER_CANT_RECEIVE : PurchaseStatus.CANT_AFFORD;
            return new PurchaseResult(status, claimed.item.copy(), 0, claimed.seller, false);
        }

        ServerPlayer online = eco.getServer().getPlayerList().getPlayer(buyerId);
        if (online != null) {
            // Profession progression is per online player and there is no profile here to advance for
            // somebody who is logged out, so the hook stays online-only on purpose. It is not dropped
            // silently: an offline buyer still pays, still gets the item, and still notifies the seller.
            ProfessionHooks.onAuctionPurchase(online);
        }

        if (!buyback) {
            // The buyer's own name is authoritative when they are here; when they are not, the only
            // source left is the economy manager's cached name, and a uuid it does not know reads as
            // "someone" rather than as a blank in the seller's message.
            if (online != null) {
                auctions.notifySellerSale(claimed, online);
            } else {
                auctions.notifySellerSale(claimed, buyerId, eco.getBestName(buyerId));
            }
        } else {
            com.reazip.economycraft.PriceRegistry.PriceEntry pe = eco.getPrices().resolve(claimed.item);
            if (pe != null) {
                eco.getQuests().onBuybackPurchased(pe.key());
            }
        }

        NegotiationEvents.invalidateTarget(eco, NegotiationStore.Kind.AH, claimed.id, detail, "was sold");

        boolean stored = deliverOrStore(auctions, buyerId, online, claimed.item.copy());
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

    private static @Nullable String marketKeyOf(EconomyManager eco, ItemStack stack) {
        com.reazip.economycraft.PriceRegistry.PriceEntry entry = eco.getPrices().resolve(stack);
        return entry == null || entry.customItem() != null ? null : entry.key();
    }

    /**
     * The money-and-delivery half of a <em>binding offer acceptance</em>, which is the only caller:
     * {@link #purchaseFor} is the full purchase path and does its own quoting, because a buyback
     * settles differently. What this deliberately skips is everything an acceptance already
     * established or does not apply to — the own-listing check (the seller is the caller), the
     * buyback branch (the bot is the seller, and the offer would never have been negotiable), the
     * sale notification (the seller is the one accepting, in their own menu) and the negotiation
     * invalidation (the offer being accepted is itself removed by the caller).
     */
    private static Execution execute(EconomyManager eco, UUID buyerId, @Nullable ServerPlayer buyer,
                                     AuctionListing claimed, long restorePrice) {
        TaxQuote quote = TaxPolicy.resolve(TaxScope.TRANSACTION_AUCTION_BUY, claimed.price, buyerId,
                claimed.seller, eco);
        long total = quote.total();

        String detail = EconomyCraft.describeItem(claimed.item.getCount(), claimed.item.getHoverName().getString());
        PaymentResult payment = eco.transferMoney(buyerId, claimed.seller, total, claimed.price,
                EconomySources.AUCTION_PURCHASE, detail, TransactionItems.keyOf(claimed.item),
                claimed.item.getCount(), marketKeyOf(eco, claimed.item));
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

    /**
     * The GUI entry point for un-listing. Delegates: the cancellation is {@link #cancelFor}, the same
     * code the HTTP route runs.
     */
    public static CancelResult cancel(AuctionManager auctions, ServerPlayer seller, int listingId) {
        return cancelFor(auctions, seller.getUUID(), listingId);
    }

    /**
     * One cancellation, addressed by uuid so an offline seller can un-list their own listing. The
     * ownership check and the negotiation invalidation are the same as the in-game path's — only
     * where the returned stack lands differs, because a seller who is not here has no inventory to
     * put it in and gets it in their deliveries mailbox instead.
     */
    public static CancelResult cancelFor(AuctionManager auctions, UUID sellerId, int listingId) {
        AuctionListing peek = auctions.getListing(listingId);
        if (peek == null) {
            return new CancelResult(CancelStatus.LISTING_GONE, ItemStack.EMPTY, false);
        }
        if (!peek.seller.equals(sellerId)) {
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

        ServerPlayer online = auctions.getServer().getPlayerList().getPlayer(sellerId);
        boolean stored = deliverOrStore(auctions, sellerId, online, removed.item.copy());
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
