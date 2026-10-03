package com.reazip.economycraft.tax;

import com.reazip.economycraft.EconomyConfig;
import com.reazip.economycraft.EconomyCraft;
import com.reazip.economycraft.EconomyManager;
import com.reazip.economycraft.profession.MerchantEffects;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * The single place any tax amount is decided.
 *
 * <p>Phase 1 is a <strong>pure refactor</strong>: this reproduces {@code Math.round(base * taxRate)}
 * bit-for-bit, so charges, lore text and affordability checks are unchanged.
 *
 * <p>Phase 7 (Merchants): The {@code Lưỡi không xương} discount reduces the pre-tax base cost by 5 %
 * (Apprentice) or 15 % (Master), scaled by 0.5 when rusty. Tax is then levied on the discounted base,
 * so both the purchase price and the tax are discounted through this single mechanism.
 *
 * <p>Phase 9 (Factions) arrives through a parameter, not through a lookup: {@link TaxExemption} carries the
 * party's decisions and {@link FactionTaxRules} is the implementation that reads the faction store. That
 * keeps this class free of faction state and keeps the rules testable, because the two rules that involve a
 * coin flip cannot be asserted if the coin is flipped here.
 *
 * <p>Every resolver ends in {@link #evaluate}, so a waived tax, a multiplied rate and an import surcharge
 * are decided in exactly one order for both the charge and the lore text that quotes it (R4).
 */
public final class TaxPolicy {

    private TaxPolicy() {
    }

    /** Prices {@code base} at the configured rate. Production entry point. */
    public static TaxQuote resolve(TaxScope scope, long base) {
        return quote(scope, base, EconomyConfig.get().taxRate);
    }

    /** Prices {@code base} at the configured rate, applying player discounts if eligible. */
    public static TaxQuote resolve(TaxScope scope, long base, @Nullable UUID player, @Nullable EconomyManager eco) {
        return resolve(scope, base, player, null, eco);
    }

    /**
     * Production entry point for a flow that has a counterparty as well as a payer — an auction purchase has
     * a buyer and a seller, and D8 keys the exemption on the seller.
     */
    public static TaxQuote resolve(TaxScope scope, long base, @Nullable UUID player, @Nullable UUID counterparty,
                                   @Nullable EconomyManager eco) {
        return resolve(scope, base, player, counterparty, eco, FactionTaxRules.forEconomy(eco));
    }

    /**
     * Prices one tax with the faction decisions supplied rather than looked up.
     *
     * <p>Overload for tests and for any caller that already knows the rules. {@code exemption} may be
     * {@code null}, which is the same as {@link TaxExemption#NONE}: the base rate applies, nothing is waived.
     */
    public static TaxQuote resolve(TaxScope scope, long base, @Nullable UUID player, @Nullable UUID counterparty,
                                   @Nullable EconomyManager eco, @Nullable TaxExemption exemption) {
        if (eco == null && player == null && exemption == null) {
            return quote(scope, base, EconomyConfig.get().taxRate);
        }

        double discountRate = 0.0;
        double taxRate = EconomyConfig.get().taxRate;
        if (eco != null && player != null) {
            discountRate = MerchantEffects.discountRate(player, eco);
        }
        return evaluate(scope, base, taxRate, discountRate, player, counterparty, exemption);
    }

    /**
     * The pure core every resolver ends in: a rate, a discount, the two parties and the faction rules.
     *
     * <p>No config, no server, no randomness of its own — the two dice rolls in Phase 9 live behind
     * {@link TaxExemption}, which is what lets a 50 % rule be asserted rather than approximated.
     *
     * <p>Order matters and is fixed: an exemption wins outright (the tax is zero and the base is untouched),
     * otherwise the rate multiplier applies, the tax is priced on the discounted base, and only then is a
     * surcharge added on top of the rounded tax.
     */
    public static TaxQuote evaluate(TaxScope scope, long base, double taxRate, double discountRate,
                                    @Nullable UUID payer, @Nullable UUID counterparty,
                                    @Nullable TaxExemption exemption) {
        if (exemption != null && exemption.exempts(scope, payer, counterparty)) {
            return new TaxQuote(base, 0.0, 0L, 0L, true, scope.source());
        }

        double rate = taxRate;
        if (exemption != null) {
            rate *= exemption.rateMultiplier(scope, payer);
        }

        TaxQuote quoted = quote(scope, base, rate, discountRate);

        if (exemption == null) return quoted;
        long surcharge = exemption.surcharge(scope, payer, quoted);
        if (surcharge <= 0L) return quoted;

        long total = quoted.amount() + surcharge;
        return new TaxQuote(quoted.base(), quoted.rate(), total, quoted.discount(), false, quoted.source());
    }

    /** Prices {@code base} at the configured rate, applying player discounts if eligible. */
    public static TaxQuote resolve(TaxScope scope, long base, @Nullable ServerPlayer player) {
        if (player == null) return resolve(scope, base);
        try {
            EconomyManager eco = EconomyCraft.getManager(player.level().getServer());
            return resolve(scope, base, player.getUUID(), eco);
        } catch (Exception ignored) {
            return resolve(scope, base);
        }
    }

    /** Pure core: prices {@code base} at an explicit rate with no discount. No server, no config, no I/O. */
    public static TaxQuote quote(TaxScope scope, long base, double rate) {
        long amount = Math.round(base * rate);
        return new TaxQuote(base, rate, amount, 0L, false, scope.source());
    }

    /** Pure core: prices {@code base} at an explicit tax rate and discount rate. */
    public static TaxQuote quote(TaxScope scope, long base, double rate, double discountRate) {
        if (discountRate <= 0.0) {
            return quote(scope, base, rate);
        }
        long discount = Math.round(base * discountRate);
        long discountedBase = Math.max(0L, base - discount);
        long amount = Math.round(discountedBase * rate);
        return new TaxQuote(base, rate, amount, discount, false, scope.source());
    }

    /** The tax amount alone — for lore text that mirrors a charge. */
    public static long tax(TaxScope scope, long base) {
        return resolve(scope, base).amount();
    }

    /**
     * Seller-side preview for a sale whose buyer is not yet known — the {@code /ah} listing screens and
     * confirmation chat.
     *
     * <p>The faction rules are keyed on the <em>seller</em> as counterparty with the payer unknown, which is
     * exactly the D8 auction rule: a purchase from a Capitalism seller is exempt no matter who buys. Every
     * buyer-side effect stays out by construction — with no payer there is no Merchant discount and no
     * Monarchy import surcharge roll. The number is therefore exact for every buyer except a Monarchy one,
     * whose surcharge cannot be known until the purchase happens.
     */
    public static TaxQuote quoteForSale(TaxScope scope, long base, @Nullable UUID counterparty,
                                        @Nullable EconomyManager eco) {
        return resolve(scope, base, null, counterparty, eco);
    }

    /**
     * {@link #quoteForSale(TaxScope, long, UUID, EconomyManager)} with the faction decisions supplied rather
     * than looked up — for tests and for any caller that already knows the rules.
     */
    public static TaxQuote quoteForSale(TaxScope scope, long base, @Nullable UUID counterparty,
                                        @Nullable EconomyManager eco, @Nullable TaxExemption exemption) {
        return resolve(scope, base, null, counterparty, eco, exemption);
    }

    public static long tax(TaxScope scope, long base, @Nullable UUID player, @Nullable EconomyManager eco) {
        return resolve(scope, base, player, eco).amount();
    }

    public static long tax(TaxScope scope, long base, @Nullable UUID player, @Nullable UUID counterparty, @Nullable EconomyManager eco) {
        return resolve(scope, base, player, counterparty, eco).amount();
    }

    /** What the recipient receives after tax — order fulfilment. */
    public static long net(TaxScope scope, long base) {
        return resolve(scope, base).net();
    }

    public static long net(TaxScope scope, long base, @Nullable UUID player, @Nullable EconomyManager eco) {
        return resolve(scope, base, player, eco).net();
    }

    public static long net(TaxScope scope, long base, @Nullable UUID player, @Nullable UUID counterparty, @Nullable EconomyManager eco) {
        return resolve(scope, base, player, counterparty, eco).net();
    }

    /** What the payer hands over including tax — toll and auction purchase. */
    public static long total(TaxScope scope, long base) {
        return resolve(scope, base).total();
    }

    public static long total(TaxScope scope, long base, @Nullable UUID player, @Nullable EconomyManager eco) {
        return resolve(scope, base, player, eco).total();
    }

    public static long total(TaxScope scope, long base, @Nullable UUID player, @Nullable UUID counterparty, @Nullable EconomyManager eco) {
        return resolve(scope, base, player, counterparty, eco).total();
    }

    /**
     * The post-tax unit rate as a {@code double}, for the order-sort/filter comparison only.
     *
     * <p>Kept as {@code base * (1 - rate)} rather than {@code base - tax(base)} so it stays bit-for-bit
     * identical to the pre-refactor expression at {@code OrderFulfillment:315}. The two differ by rounding —
     * for a base of 7 at 10 %, this yields 6.3 where {@code 7 - round(0.7)} yields 6 — and that value is only
     * used to filter and sort listings, never to move money.
     */
    public static double netRate(TaxScope scope, double base) {
        return netRate(base, EconomyConfig.get().taxRate);
    }

    /** Pure core for {@link #netRate(TaxScope, double)}. */
    public static double netRate(double base, double rate) {
        return base * (1.0 - rate);
    }
}
