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
 *
 * <p>{@link #TRANSACTION_CONTRACT} is the service-contract payout scope. It is quoted and charged with the
 * plain rate (no player resolver), so a service payout inherits neither the Merchant purchase discount nor
 * any faction import surcharge — service work is not an item trade.
 */
public enum TaxScope {
    TRANSACTION_SHOP(EconomySources.SHOP_PURCHASE),
    TRANSACTION_AUCTION_BUY(EconomySources.AUCTION_PURCHASE),
    AUCTION_LISTING(EconomySources.AUCTION_PURCHASE),
    TRANSACTION_ORDER(EconomySources.ORDER_FULFILLMENT),
    TRANSACTION_CONTRACT(EconomySources.CONTRACT_PAYOUT),
    TOLL(EconomySources.TOLL_PAYMENT),
    TRANSACTION_PAY(EconomySources.PLAYER_PAYMENT),
    TRANSACTION_VILLAGER(EconomySources.VILLAGER_TRADE);

    private final MutationSource source;

    TaxScope(MutationSource source) {
        this.source = source;
    }

    public MutationSource source() {
        return source;
    }
}
