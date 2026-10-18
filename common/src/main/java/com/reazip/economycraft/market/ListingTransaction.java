package com.reazip.economycraft.market;

import com.mojang.logging.LogUtils;
import com.reazip.economycraft.auction.AuctionListing;
import com.reazip.economycraft.auction.AuctionManager;
import com.reazip.economycraft.util.ExpirationUtil;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.UUID;

/**
 * The second half of listing an item over HTTP: the item has already left the seller's inventory
 * and this either makes it a listing or puts it in the seller's deliveries mailbox.
 *
 * <p>There is no cross-store transaction between a player's inventory and the auction store, so the
 * steps are ordered so that failure is recoverable and the compensation cannot fail. By the time
 * {@link #list} is called the item belongs to nobody — it is out of the inventory and not yet in a
 * listing — and the invariant this class exists to hold is:
 *
 * <p><strong>The item is always in the inventory, in a listing, or in the mailbox.</strong> Never
 * lost, never duplicated. The mailbox is the compensation rather than putting the stack back into
 * the slot it came from, because re-encoding that write can itself fail (the slot may hold something
 * else by then, or the file may be gone) whereas {@code addDelivery} is a ledger append the player
 * can claim. This mirrors {@code AuctionTrade.purchase}, which removes the listing first and restores
 * it if the money movement fails.
 *
 * <p>Split out of the listener so that ordering is testable without an HTTP request: everything it
 * needs is an {@link AuctionManager}, an id and a stack.
 */
public final class ListingTransaction {
    private static final Logger LOGGER = LogUtils.getLogger();

    private ListingTransaction() {}

    /**
     * @param id     the new listing's id, or null when the listing could not be created
     * @param stored true when the item went to the deliveries mailbox instead
     * @param error  the refusal to pass through, or null on success
     */
    public record Result(@Nullable Integer id, boolean stored, @Nullable String error) {
        public boolean ok() {
            return error == null;
        }
    }

    /**
     * Creates the listing for an item that has already been taken out of its owner's inventory.
     *
     * @param item the stack the seller already lost. Ownership is transferred: it goes into the
     *             listing, or into the mailbox, and the caller must not use it again afterwards.
     */
    public static Result list(AuctionManager auctions, UUID sellerId, ItemStack item, long price,
                              @Nullable String description, int expirationHours) {
        AuctionListing listing = new AuctionListing();
        listing.seller = sellerId;
        listing.price = price;
        listing.item = item;
        long now = System.currentTimeMillis();
        listing.createdAt = now;
        listing.expiresAt = ExpirationUtil.expiresAt(now, expirationHours);
        listing.description = description;

        try {
            auctions.addListing(listing);
        } catch (Exception e) {
            // The one place this can fail is the store write, and the item is in the seller's hands
            // no longer. Handing it to the delivery ledger is the only remaining exit, and it cannot
            // fail; a listing that half-wrote its id costs nothing, because the item is not in it.
            LOGGER.error("[EconomyCraft] Could not create a listing for {}; the item goes to their deliveries instead",
                    sellerId, e);
            auctions.addDelivery(sellerId, item);
            return new Result(null, true, "could not list that item");
        }
        return new Result(listing.id, false, null);
    }
}
