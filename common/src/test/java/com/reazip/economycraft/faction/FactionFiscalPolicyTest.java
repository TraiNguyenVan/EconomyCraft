package com.reazip.economycraft.faction;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FactionFiscalPolicyTest {

    @Test
    void concentrationMultiplierCalculatesCorrectly() {
        // share == referenceShare -> multiplier is 1.0
        double mult = FactionFiscalPolicy.concentrationMultiplier(0.15, 0.15, 1.0, 0.0, 5.0);
        assertEquals(1.0, mult, 1e-9);

        // double share -> 2.0 with elasticity 1.0
        double multDouble = FactionFiscalPolicy.concentrationMultiplier(0.30, 0.15, 1.0, 0.0, 5.0);
        assertEquals(2.0, multDouble, 1e-9);

        // clamp max
        double multClamped = FactionFiscalPolicy.concentrationMultiplier(0.90, 0.15, 1.0, 0.0, 5.0);
        assertEquals(5.0, multClamped, 1e-9);

        // zero or negative share
        assertEquals(0.0, FactionFiscalPolicy.concentrationMultiplier(0.0, 0.15, 1.0, 0.0, 5.0), 1e-9);
        assertEquals(0.0, FactionFiscalPolicy.concentrationMultiplier(-0.1, 0.15, 1.0, 0.0, 5.0), 1e-9);
    }

    @Test
    void capitalismRateScalesWithInflationAndConcentration() {
        // base 0.05, inflation 1.2, concentration 1.5 -> 0.05 * 1.2 * 1.5 = 0.09
        double rate = FactionFiscalPolicy.capitalismRate(0.05, 1.2, 1.5);
        assertEquals(0.09, rate, 1e-9);

        // inflation clamped >= 1.0
        double subInflationRate = FactionFiscalPolicy.capitalismRate(0.05, 0.8, 1.0);
        assertEquals(0.05, subInflationRate, 1e-9);

        // zero base
        assertEquals(0.0, FactionFiscalPolicy.capitalismRate(0.0, 2.0, 2.0), 1e-9);
    }

    @Test
    void clampRateChangeLimitsDrasticChanges() {
        // previous 0.05, max change 0.25 -> bounds [0.0, 0.30]
        double clampedHigh = FactionFiscalPolicy.clampRateChange(0.50, 0.05, 0.25);
        assertEquals(0.30, clampedHigh, 1e-9);

        // previous 0.50, max change 0.25 -> bounds [0.25, 0.75]
        double clampedLow = FactionFiscalPolicy.clampRateChange(0.10, 0.50, 0.25);
        assertEquals(0.25, clampedLow, 1e-9);

        // within bounds
        double unchanged = FactionFiscalPolicy.clampRateChange(0.20, 0.15, 0.25);
        assertEquals(0.20, unchanged, 1e-9);
    }

    @Test
    void monarchyMoneySupplyInflationFormula() {
        // total 50_000, 10 active players, ref 1000 -> 50_000 / 10_000 = 5.0, clamped to max 3.0
        double factor = FactionFiscalPolicy.monarchyMoneySupplyInflation(50_000, 10, 1000.0, 3.0);
        assertEquals(3.0, factor, 1e-9);

        // total 10_000, 10 players, ref 1000 -> 10_000 / 10_000 = 1.0
        double factorNormal = FactionFiscalPolicy.monarchyMoneySupplyInflation(10_000, 10, 1000.0, 3.0);
        assertEquals(1.0, factorNormal, 1e-9);

        // total 5_000, 10 players, ref 1000 -> 5_000 / 10_000 = 0.5 -> clamped to min 1.0
        double factorSub = FactionFiscalPolicy.monarchyMoneySupplyInflation(5_000, 10, 1000.0, 3.0);
        assertEquals(1.0, factorSub, 1e-9);
    }

    @Test
    void monarchyRateCalculatesCorrectly() {
        // base 0.017, inflation 2.0, concentration 1.0 -> 0.034
        double rate = FactionFiscalPolicy.monarchyRate(0.017, 2.0, 1.0);
        assertEquals(0.034, rate, 1e-9);
    }

    @Test
    void taxAmountNeverExceedsBalance() {
        assertEquals(50L, FactionFiscalPolicy.taxAmount(1000L, 0.05));
        assertEquals(1000L, FactionFiscalPolicy.taxAmount(1000L, 1.5));
        assertEquals(0L, FactionFiscalPolicy.taxAmount(0L, 0.05));
        assertEquals(0L, FactionFiscalPolicy.taxAmount(1000L, 0.0));
    }

    @Test
    void incomeTaxSingleHighestTierStrictlyGreaterThan() {
        long t1 = 10_000L; double r1 = 0.005;
        long t2 = 15_000L; double r2 = 0.0075;
        long t3 = 22_000L; double r3 = 0.0125;

        // Exactly 10_000 pays 0 (strictly greater)
        assertEquals(0L, FactionFiscalPolicy.incomeTaxAmount(10_000L, t1, r1, t2, r2, t3, r3));

        // 10_001 pays 0.5% (50.005 -> 50)
        assertEquals(50L, FactionFiscalPolicy.incomeTaxAmount(10_001L, t1, r1, t2, r2, t3, r3));

        // Exactly 15_000 pays tier 1 (0.5% of 15_000 = 75)
        assertEquals(75L, FactionFiscalPolicy.incomeTaxAmount(15_000L, t1, r1, t2, r2, t3, r3));

        // 15_001 pays tier 2 (0.75% of 15_001 = 113)
        assertEquals(113L, FactionFiscalPolicy.incomeTaxAmount(15_001L, t1, r1, t2, r2, t3, r3));

        // Exactly 22_000 pays tier 2 (0.75% of 22_000 = 165)
        assertEquals(165L, FactionFiscalPolicy.incomeTaxAmount(22_000L, t1, r1, t2, r2, t3, r3));

        // 30_000 pays tier 3 (1.25% of 30_000 = 375)
        assertEquals(375L, FactionFiscalPolicy.incomeTaxAmount(30_000L, t1, r1, t2, r2, t3, r3));
    }

    @Test
    void shareOfHandlesTheEmptyServer() {
        assertEquals(0.30, FactionFiscalPolicy.shareOf(30L, 100L), 1e-9);
        assertEquals(0.0, FactionFiscalPolicy.shareOf(0L, 100L), 1e-9, "nobody in the party");
        assertEquals(0.0, FactionFiscalPolicy.shareOf(50L, 0L), 1e-9, "D14: an empty server must not divide by zero");
        assertEquals(0.0, FactionFiscalPolicy.shareOf(50L, -5L), 1e-9);
        assertEquals(1.0, FactionFiscalPolicy.shareOf(100L, 100L), 1e-9, "one party holding everything is capped at 1");
        assertEquals(1.0, FactionFiscalPolicy.shareOf(500L, 100L), 1e-9, "saturation must not produce a share above 1");
    }

    @Test
    void catchUpIsCappedAndNeverNegative() {
        assertEquals(0, FactionFiscalPolicy.catchUpDays(-1L, 20_000L, 7), "a fresh pass charges nothing");
        assertEquals(0, FactionFiscalPolicy.catchUpDays(20_000L, 20_000L, 7), "same day, nothing due");
        assertEquals(0, FactionFiscalPolicy.catchUpDays(20_000L, 19_999L, 7), "a clock that moved backwards is not a gap");
        assertEquals(1, FactionFiscalPolicy.catchUpDays(19_999L, 20_000L, 7));
        assertEquals(7, FactionFiscalPolicy.catchUpDays(19_990L, 20_000L, 7), "ten days of silence is capped at seven");
        assertEquals(10, FactionFiscalPolicy.catchUpDays(19_990L, 20_000L, 0), "0 means no cap");
        assertEquals(3, FactionFiscalPolicy.catchUpDays(19_997L, 20_000L, 7));
    }

    @Test
    void theTwoPartiesReallyAreDifferentFormulas() {
        // D19: same concentration half, different inflation signal. At the same inputs they must not agree,
        // which is the whole reason they were not merged into one function.
        double globalInflation = 1.4;
        double moneySupply = 2.6;
        double concentration = 1.5;

        double capitalism = FactionFiscalPolicy.capitalismRate(0.05, globalInflation, concentration);
        double monarchy = FactionFiscalPolicy.monarchyRate(0.017, moneySupply, concentration);

        assertEquals(0.105, capitalism, 1e-9);
        assertEquals(0.0663, monarchy, 1e-9);
        assertNotEquals(capitalism, monarchy, 1e-9);
    }

    @Test
    void incomeTaxIsChargedOnWhatThePartyFeeLeft() {
        // D3's ordering, as a single number: 30 000, minus the 10 fee, is 29 990 and 1.25 % of that is 375.
        long balance = 30_000L;
        long fee = FactionFiscalPolicy.partyFeeCharge(balance, 10L);
        assertEquals(10L, fee);
        long remaining = balance - fee;
        long tax = FactionFiscalPolicy.incomeTaxAmount(remaining, 10_000L, 0.005, 15_000L, 0.0075, 22_000L, 0.0125);
        assertEquals(375L, tax);
    }

    @Test
    void partyFeeTakesWhatThereIsAndNeverGoesNegative() {
        assertEquals(10L, FactionFiscalPolicy.partyFeeCharge(1_000L, 10L));
        assertEquals(3L, FactionFiscalPolicy.partyFeeCharge(3L, 10L), "a broke player pays what they have");
        assertEquals(0L, FactionFiscalPolicy.partyFeeCharge(0L, 10L));
        assertEquals(0L, FactionFiscalPolicy.partyFeeCharge(100L, 0L), "a fee of 0 disables the levy, not inverts it");
        assertEquals(0L, FactionFiscalPolicy.partyFeeCharge(100L, -5L), "a negative fee never credits anyone");
        assertEquals(0L, FactionFiscalPolicy.partyFeeCharge(-100L, 10L));
    }

    @Test
    void incomeTaxBracketsAfterThePartyFee() {
        long fee = 10L;
        long t1 = 10_000L;
        long t2 = 15_000L;
        long t3 = 22_000L;

        // The realistic sequence: a 45-minute tick takes the fee first, then taxes what is left, so each
        // bracket boundary is one fee lower than the config threshold. 10 005 - 10 = 9 995, still under 10 000.
        assertEquals(10L, levy(10_005L, fee, t1, t2, t3), "9 995 left is under the threshold, so the fee is all it is");
        assertEquals(60L, levy(10_016L, fee, t1, t2, t3), "10 006 left pays tier 1: 50 + the fee");
        assertEquals(123L, levy(15_011L, fee, t1, t2, t3), "15 001 left is over 15 000, so tier 2 at 0.75 %");
        assertEquals(285L, levy(22_011L, fee, t1, t2, t3), "22 001 is over 22 000, so tier 3 at 1.25 %");
        assertEquals(385L, levy(30_011L, fee, t1, t2, t3), "30 001 left pays tier 3");
    }

    /** What one 45-minute tick costs a player with this balance: fee, then tax on the remainder. */
    private static long levy(long balance, long fee, long t1, long t2, long t3) {
        long charged = FactionFiscalPolicy.partyFeeCharge(balance, fee);
        long remaining = balance - charged;
        return charged + FactionFiscalPolicy.incomeTaxAmount(
                remaining, t1, 0.005, t2, 0.0075, t3, 0.0125);
    }

    @Test
    void aBalanceJustUnderAThresholdPaysNothing() {
        long t1 = 10_000L;
        assertEquals(0L, FactionFiscalPolicy.incomeTaxAmount(9_999L, t1, 0.005, 15_000L, 0.0075, 22_000L, 0.0125));
        assertEquals(0L, FactionFiscalPolicy.incomeTaxAmount(0L, t1, 0.005, 15_000L, 0.0075, 22_000L, 0.0125));
        assertEquals(0L, FactionFiscalPolicy.incomeTaxAmount(-500L, t1, 0.005, 15_000L, 0.0075, 22_000L, 0.0125),
                "a negative balance is not taxed");
    }
}
