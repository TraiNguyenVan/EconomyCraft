package com.reazip.economycraft.tax;

import com.reazip.economycraft.EconomySources;
import com.reazip.economycraft.api.v1.MutationSource;

/**
 * The incidence point of a tax — where in the economy it is levied.
 *
 * <p>One value per place the old code duplicated {@code Math.round(base * taxRate)}. The scope carries the
 * {@link MutationSource} the tax is attributed to, so a caller does not have to remember which source goes
 * with which flow (a mismatch here would silently mis-attribute every tax in {@code /transactions}).
 *
 * <p>Two values are reserved and have no call site yet, by design:
 * <ul>
 *   <li>{@link #TRANSACTION_SHOP} — the server shop levies no tax today; kept so a future shop tax cannot
 *       quietly reuse an auction source.</li>
 *   <li>{@link #AUCTION_LISTING} — the seller-side site needed by D8 (a Capitalism seller makes their
 *       buyer's purchase tax-exempt). It is separate from {@link #TRANSACTION_AUCTION_BUY} because D8 keys
 *       on the <em>seller's</em> faction, not the buyer's.</li>
 * </ul>
 */
public enum TaxScope {
    TRANSACTION_SHOP(EconomySources.SHOP_PURCHASE),
    TRANSACTION_AUCTION_BUY(EconomySources.AUCTION_PURCHASE),
    AUCTION_LISTING(EconomySources.AUCTION_PURCHASE),
    TRANSACTION_ORDER(EconomySources.ORDER_FULFILLMENT),
    TOLL(EconomySources.TOLL_PAYMENT);

    private final MutationSource source;

    TaxScope(MutationSource source) {
        this.source = source;
    }

    public MutationSource source() {
        return source;
    }
}
