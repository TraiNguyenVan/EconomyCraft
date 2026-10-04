package com.reazip.economycraft.auction;

import com.reazip.economycraft.EconomyCraft;
import com.reazip.economycraft.EconomyManager;
import com.reazip.economycraft.EconomySources;
import com.reazip.economycraft.api.v1.PaymentResult;
import com.reazip.economycraft.profession.ProfessionHooks;
import com.reazip.economycraft.quests.QuestManager;
import com.reazip.economycraft.tax.TaxPolicy;
import com.reazip.economycraft.tax.TaxQuote;
import com.reazip.economycraft.tax.TaxScope;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

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

        boolean stored = deliverOrStore(auctions, buyer, claimed.item.copy());
        return new PurchaseResult(PurchaseStatus.OK, claimed.item.copy(), total, claimed.seller, stored);
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
}
