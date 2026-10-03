package com.reazip.economycraft.tax;

import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * The faction decisions that change one tax, supplied from outside {@link TaxPolicy} (P9-T6).
 *
 * <p><strong>Why this is a parameter and not a lookup inside {@code TaxPolicy}.</strong> Three of the four
 * Phase 9 tax rules are <em>probabilistic</em> (spec 12's 50 % toll waiver, spec 31's 50 % import tax, and
 * the seller-side condition in D8 that keys on a party the payer may not even know). If {@code TaxPolicy}
 * read the faction store and rolled a die itself, none of those rules could be unit-tested: the tests would
 * have to stand up an {@code EconomyManager}, a server thread and a random seed, and a 50 % rule would be
 * either flaky or untested. With the decision injected, the arithmetic stays pure and the rolls are inputs.
 *
 * <p>Implementations must be side-effect free and cheap: one instance is consulted once per tax quote, and
 * the same instance serves the lore text in {@code /ah} and the charge in {@code AuctionTrade} (R4 — display
 * and charge must not disagree).
 *
 * @see FactionTaxRules the production implementation, backed by {@code FactionStore} and config
 */
public interface TaxExemption {

    /** No faction is involved: the base rate applies and nothing is waived. */
    TaxExemption NONE = new TaxExemption() {
        @Override
        public boolean exempts(TaxScope scope, @Nullable UUID payer, @Nullable UUID counterparty) {
            return false;
        }

        @Override
        public double rateMultiplier(TaxScope scope, @Nullable UUID payer) {
            return 1.0;
        }

        @Override
        public long surcharge(TaxScope scope, @Nullable UUID payer, TaxQuote quoted) {
            return 0L;
        }
    };

    /**
     * Whether this tax is waived entirely. The payer still pays the base amount — only the tax disappears
     * (spec 34: Anarchism pays no tax but still pays tolls and purchase prices).
     */
    boolean exempts(TaxScope scope, @Nullable UUID payer, @Nullable UUID counterparty);

    /**
     * A multiplier on the tax <em>rate</em> for this scope — Capitalism's {@code +25 %} toll tax (spec 23),
     * resolved as a 1.25x multiplier on the rate rather than +25 percentage points, so it composes with a
     * base rate that is not 10 %.
     */
    double rateMultiplier(TaxScope scope, @Nullable UUID payer);

    /**
     * An extra amount to add on top of {@link TaxQuote#amount()} — Monarchy's import tax (spec 31), which is
     * 50 % of the item's own tax.
     *
     * <p>Called with the already-rounded base tax, so implementations round the surcharge from that value
     * and the order of rounding is part of the contract rather than an accident of floating point.
     */
    long surcharge(TaxScope scope, @Nullable UUID payer, TaxQuote quoted);
}
