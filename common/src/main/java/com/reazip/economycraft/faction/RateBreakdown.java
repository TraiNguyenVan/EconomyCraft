package com.reazip.economycraft.faction;

import java.util.Locale;

/**
 * Every factor that produced one faction daily rate, kept together so a charge can explain itself (D14).
 *
 * <p>D14 requires the message to state the base rate, the inflation factor, the party's share, the reference
 * share, the concentration multiplier, and whether the per-day clamp bound the result. Six numbers that
 * arrive from four different places is exactly the kind of thing that gets quietly half-implemented, so they
 * are one value here and {@link #describe()} is the only place they are formatted — which also means the
 * explanation is unit-testable instead of trusted.
 *
 * <p>The two parties' breakdowns differ in more than their numbers, which is the point D19 makes: Capitalism
 * reads server-wide player-activity inflation, Monarchy reads money supply per player.
 *
 * @param baseRate                 the configured rate before any factor
 * @param inflation                the party's own inflation factor, already clamped to its floor
 * @param share                    the party's share of all money on the server
 * @param referenceShare           the share at which the concentration multiplier is exactly 1.0
 * @param concentrationMultiplier  the resolved multiplier
 * @param rawRate                  base x inflation x concentration, before the per-day clamp
 * @param appliedRate              the rate actually charged, after the per-day clamp
 */
public record RateBreakdown(String party, double baseRate, double inflation, double share, double referenceShare,
                            double concentrationMultiplier, double rawRate, double appliedRate) {

    private static final double EPSILON = 1e-9;

    /** Whether the per-day rate-change clamp moved the rate, so the message has to say so. */
    public boolean clampBound() {
        return Math.abs(rawRate - appliedRate) > EPSILON;
    }

    /**
     * One line naming every factor, e.g.
     * {@code Capitalism: base 5.00% x inflation 1.20 x concentration 1.50 (wealth share 22.5% of 15.0% reference) = 9.00%}.
     *
     * <p>When the clamp bound the rate the raw figure is kept in the sentence rather than dropped, because
     * "the rate did not change as much as the formula says" is exactly what an admin needs to be able to see.
     */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append(party).append(": base ").append(percent(baseRate))
                .append(" x inflation ").append(factor(inflation))
                .append(" x concentration ").append(factor(concentrationMultiplier))
                .append(" (wealth share ").append(percent(share))
                .append(" vs ").append(percent(referenceShare)).append(" reference)")
                .append(" = ").append(percent(rawRate));
        if (clampBound()) {
            sb.append(", clamped to ").append(percent(appliedRate))
                    .append(" (max ").append(percent(Math.abs(rawRate - appliedRate))).append("/day)");
        }
        return sb.toString();
    }

    private static String percent(double ratio) {
        return String.format(Locale.ROOT, "%.2f%%", ratio * 100.0);
    }

    private static String factor(double multiplier) {
        return String.format(Locale.ROOT, "%.2f", multiplier);
    }
}
