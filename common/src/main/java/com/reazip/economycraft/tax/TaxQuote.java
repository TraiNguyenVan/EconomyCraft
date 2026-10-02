package com.reazip.economycraft.tax;

import com.reazip.economycraft.api.v1.MutationSource;

/**
 * The result of pricing one taxable amount.
 *
 * <p>{@link #net()} and {@link #total()} exist because the two sides of a trade charge in opposite
 * directions, and getting that backwards moves money the wrong way:
 * <ul>
 *   <li>{@code total()} — what the payer hands over. Toll and auction purchase: the buyer pays the listed
 *       price <em>plus</em> tax.</li>
 *   <li>{@code net()} — what the recipient receives. Order fulfilment: the fulfiller receives the payment
 *       <em>minus</em> tax.</li>
 * </ul>
 * In every case the difference is burned, not credited to anyone.
 *
 * @param exempt whether a faction rule waived this tax (always {@code false} until Phase 9)
 */
public record TaxQuote(long base, double rate, long amount, long discount, boolean exempt, MutationSource source) {

    public TaxQuote(long base, double rate, long amount, boolean exempt, MutationSource source) {
        this(base, rate, amount, 0L, exempt, source);
    }

    /** What the payer hands over: the discounted base plus tax. Callers must bounds-check before using it as a debit. */
    public long total() {
        return Math.max(0L, base - discount) + amount;
    }

    /** What the recipient receives: the discounted base minus tax. */
    public long net() {
        return Math.max(0L, base - discount) - amount;
    }

    public boolean taxed() {
        return !exempt && amount > 0;
    }

    public boolean discounted() {
        return discount > 0;
    }

    public long discountedBase() {
        return Math.max(0L, base - discount);
    }
}
