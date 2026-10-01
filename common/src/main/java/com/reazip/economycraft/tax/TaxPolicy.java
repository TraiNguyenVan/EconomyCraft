package com.reazip.economycraft.tax;

import com.reazip.economycraft.EconomyConfig;

/**
 * The single place any tax amount is decided.
 *
 * <p>Phase 1 is a <strong>pure refactor</strong>: this reproduces {@code Math.round(base * taxRate)}
 * bit-for-bit, so charges, lore text and affordability checks are unchanged. The point is structural — the
 * 18 duplicated call sites now all pass through here, so Phase 9's faction rules ("Anarchism pays no tax",
 * "purchase from a Capitalism seller is exempt", "Monarchy halves claim cost") have exactly one place to
 * land instead of eighteen that must not be missed.
 *
 * <p>{@link #quote(TaxScope, long, double)} is the pure core and takes the rate explicitly, so it is
 * testable without touching {@link EconomyConfig}. {@link #resolve(TaxScope, long)} is the production entry
 * point and reads the configured rate.
 *
 * <p><strong>Parity note.</strong> The old formula applied {@code Math.round} <em>unconditionally</em>,
 * including for non-positive bases. This class does the same, deliberately: "reject negative" would be a
 * behaviour change, and Phase 1 is not allowed one. Every real call site validates its base as positive
 * beforehand (the toll checks {@code fee > 0} at {@code TollManager.java:152}), so the point is moot in
 * practice and is pinned by a test instead.
 */
public final class TaxPolicy {

    private TaxPolicy() {
    }

    /** Prices {@code base} at the configured rate. Production entry point. */
    public static TaxQuote resolve(TaxScope scope, long base) {
        return quote(scope, base, EconomyConfig.get().taxRate);
    }

    /** Pure core: prices {@code base} at an explicit rate. No server, no config, no I/O. */
    public static TaxQuote quote(TaxScope scope, long base, double rate) {
        long amount = Math.round(base * rate);
        return new TaxQuote(base, rate, amount, false, scope.source());
    }

    /** The tax amount alone — for lore text that mirrors a charge. */
    public static long tax(TaxScope scope, long base) {
        return resolve(scope, base).amount();
    }

    /** What the recipient receives after tax — order fulfilment. */
    public static long net(TaxScope scope, long base) {
        return resolve(scope, base).net();
    }

    /** What the payer hands over including tax — toll and auction purchase. */
    public static long total(TaxScope scope, long base) {
        return resolve(scope, base).total();
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
