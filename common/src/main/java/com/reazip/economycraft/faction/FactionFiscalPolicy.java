package com.reazip.economycraft.faction;

/**
 * Pure arithmetic for faction daily fiscal taxes and Communism income tax (spec §1, D3, D14, D19).
 *
 * <p>No Minecraft imports, no server state, no I/O. All methods accept observed numbers and return
 * deterministic decisions, allowing exhaustive unit testing in {@code FactionFiscalPolicyTest}.
 */
public final class FactionFiscalPolicy {

    private FactionFiscalPolicy() {}

    /**
     * Concentration multiplier for a party's wealth share (D14):
     * {@code clamp((share / referenceShare) ^ elasticity, minMult, maxMult)}.
     *
     * @param share           faction's share of total server wealth (0.0 to 1.0)
     * @param referenceShare  the share at which multiplier is exactly 1.0
     * @param elasticity      difficulty curve (>1 punishes concentration harder, <1 is sub-linear)
     * @param minMult         minimum multiplier floor
     * @param maxMult         maximum multiplier ceiling
     */
    public static double concentrationMultiplier(double share, double referenceShare,
                                                 double elasticity, double minMult, double maxMult) {
        if (share <= 0.0 || referenceShare <= 0.0) {
            return minMult;
        }
        double ratio = share / referenceShare;
        double raw = Math.pow(ratio, elasticity);
        return Math.clamp(raw, minMult, maxMult);
    }

    /**
     * Capitalism's inflation- and concentration-responsive rate (D14):
     * {@code baseRate * globalInflation * concentrationMult}.
     */
    public static double capitalismRate(double baseRate, double globalInflation, double concentrationMult) {
        if (baseRate <= 0.0) return 0.0;
        double effectiveInflation = Math.max(1.0, globalInflation);
        double rate = baseRate * effectiveInflation * concentrationMult;
        return Math.clamp(rate, 0.0, 1.0);
    }

    /**
     * Clamps daily rate changes to prevent griefing when wealth distribution shifts abruptly (D14).
     */
    public static double clampRateChange(double newRate, double previousRate, double maxRateChangePerDay) {
        if (previousRate <= 0.0 || maxRateChangePerDay <= 0.0) {
            return Math.clamp(newRate, 0.0, 1.0);
        }
        double minAllowed = Math.max(0.0, previousRate - maxRateChangePerDay);
        double maxAllowed = Math.min(1.0, previousRate + maxRateChangePerDay);
        return Math.clamp(newRate, minAllowed, maxAllowed);
    }

    /**
     * Monarchy's money supply inflation factor (D19):
     * {@code totalMoneyInCirculation / (activePlayers * referencePerPlayer)}, clamped to [1.0, maxInflation].
     */
    public static double monarchyMoneySupplyInflation(long totalMoneyInCirculation, int activePlayers,
                                                      double referencePerPlayer, double maxInflation) {
        if (activePlayers <= 0 || referencePerPlayer <= 0.0 || totalMoneyInCirculation <= 0) {
            return 1.0;
        }
        double denominator = activePlayers * referencePerPlayer;
        if (denominator <= 0.0) return 1.0;
        double factor = totalMoneyInCirculation / denominator;
        return Math.clamp(factor, 1.0, Math.max(1.0, maxInflation));
    }

    /**
     * Monarchy's corruption tax rate (D19):
     * {@code baseRate * moneySupplyInflation * concentrationMult}.
     */
    public static double monarchyRate(double baseRate, double moneySupplyInflation, double concentrationMult) {
        if (baseRate <= 0.0) return 0.0;
        double effectiveInflation = Math.max(1.0, moneySupplyInflation);
        double rate = baseRate * effectiveInflation * concentrationMult;
        return Math.clamp(rate, 0.0, 1.0);
    }

    /**
     * A party's share of all money on the server (D14).
     *
     * <p>{@code total == 0} gives {@code 0} rather than a division by zero, which D14 lists as one of the three
     * failure modes to handle: nobody in the party, so nobody is taxed.
     */
    public static double shareOf(long partyMoney, long totalMoney) {
        if (totalMoney <= 0L || partyMoney <= 0L) return 0.0;
        return Math.min(1.0, (double) partyMoney / (double) totalMoney);
    }

    /**
     * Days to charge for after a gap, capped so a server that was off for a month does not silently levy a
     * month's tax (D4: the faction pass has its own cadence and its own catch-up rule).
     */
    public static int catchUpDays(long lastFiscalDay, long today, int maxCatchupDays) {
        if (lastFiscalDay < 0 || today <= lastFiscalDay) return 0;
        long elapsed = today - lastFiscalDay;
        int cap = Math.max(0, maxCatchupDays);
        if (cap > 0 && elapsed > cap) return cap;
        return (int) Math.min(elapsed, Integer.MAX_VALUE);
    }

    /**
     * Tax amount owed on a balance, never driving the balance negative.
     */
    public static long taxAmount(long balance, double rate) {
        if (balance <= 0 || rate <= 0.0) return 0L;
        double clampedRate = Math.clamp(rate, 0.0, 1.0);
        double raw = balance * clampedRate;
        long tax = raw >= Long.MAX_VALUE ? Long.MAX_VALUE : Math.round(raw);
        return Math.max(0L, Math.min(tax, balance));
    }

    /**
     * The party fee a balance can actually pay (spec 14).
     *
     * <p>Capped at the balance rather than refused: a levy that can leave a player negative is a debt nobody
     * agreed to, and "the party takes what there is" is the same rule D3 sets for the income tax.
     */
    public static long partyFeeCharge(long balance, long partyFee) {
        if (balance <= 0L || partyFee <= 0L) return 0L;
        return Math.min(balance, partyFee);
    }

    /**
     * Communism anti-speculation income tax (D3, spec lines 15-18).
     * Single highest bracket matched, strictly greater than threshold, applied to remaining balance.
     */
    public static long incomeTaxAmount(long balance,
                                       long t1Threshold, double t1Rate,
                                       long t2Threshold, double t2Rate,
                                       long t3Threshold, double t3Rate) {
        if (balance <= 0) return 0L;
        double rate = 0.0;
        if (balance > t3Threshold) {
            rate = t3Rate;
        } else if (balance > t2Threshold) {
            rate = t2Rate;
        } else if (balance > t1Threshold) {
            rate = t1Rate;
        }
        return taxAmount(balance, rate);
    }
}
