package com.reazip.economycraft.fiscal;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure-math coverage for the fiscal policy. No server bootstrap required. */
class FiscalPolicyTest {

    private static final long DAY_MS = TimeUnit.DAYS.toMillis(1);
    private static final long STARTING_BALANCE = 1000L;

    // --- median ---

    @Test
    void medianOfEmptyInputIsZero() {
        assertEquals(0.0, FiscalPolicy.median(null));
        assertEquals(0.0, FiscalPolicy.median(List.of()));
    }

    @Test
    void medianOfAllNullsIsZero() {
        assertEquals(0.0, FiscalPolicy.median(Arrays.asList((Long) null, null)));
    }

    @Test
    void medianOfOddCountIsTheMiddleValue() {
        assertEquals(7339.0, FiscalPolicy.median(List.of(16100L, 968L, 7339L)));
    }

    @Test
    void medianOfEvenCountAveragesTheMiddlePair() {
        assertEquals(4611.0, FiscalPolicy.median(List.of(16100L, 968L, 7339L, 1883L, 12875L, 1090L)));
    }

    @Test
    void medianOfSingleValueIsThatValue() {
        assertEquals(500.0, FiscalPolicy.median(List.of(500L)));
    }

    @Test
    void medianSkipsNulls() {
        assertEquals(8991.5, FiscalPolicy.median(Arrays.asList(1883L, null, 16100L)));
    }

    @Test
    void medianOfNearMaxBalancesDoesNotOverflow() {
        long huge = EconomyMax();
        List<Long> balances = new ArrayList<>();
        balances.add(huge);
        balances.add(huge);
        assertEquals((double) huge, FiscalPolicy.median(balances));
    }

    private static long EconomyMax() {
        return 999_999_999_999L;
    }

    // --- floor ---

    @Test
    void zeroFactorAnchorsFloorToAbsoluteFloor() {
        assertEquals(1000L, FiscalPolicy.floor(4662.0, STARTING_BALANCE, 0.0));
    }

    @Test
    void halfFactorSplitsTheDifference() {
        assertEquals(2331L, FiscalPolicy.floor(4662.0, STARTING_BALANCE, 0.5));
    }

    @Test
    void fullFactorAnchorsFloorToTheMedian() {
        assertEquals(4662L, FiscalPolicy.floor(4662.0, STARTING_BALANCE, 1.0));
    }

    @Test
    void absoluteFloorWinsWhenMedianIsDepressed() {
        assertEquals(1000L, FiscalPolicy.floor(500.0, STARTING_BALANCE, 0.5));
    }

    @Test
    void zeroMedianFallsBackToAbsoluteFloor() {
        assertEquals(1000L, FiscalPolicy.floor(0.0, STARTING_BALANCE, 0.5));
        assertEquals(1000L, FiscalPolicy.floor(0.0, STARTING_BALANCE, 1.0));
    }

    @Test
    void negativeAbsoluteFloorIsClampedToZero() {
        assertEquals(0L, FiscalPolicy.floor(0.0, -50L, 0.0));
    }

    @Test
    void floorDoesNotOverflowOnAbsurdMedian() {
        long floor = FiscalPolicy.floor(Double.MAX_VALUE, STARTING_BALANCE, 10.0);
        assertTrue(floor > 0, "floor should saturate high, not wrap negative");
    }

    // --- rateFor ---

    @Test
    void activePlayerPaysTheBaseRate() {
        long now = 10 * DAY_MS;
        double rate = FiscalPolicy.rateFor(now - DAY_MS, now, 7, 0.015, 3.33);
        assertEquals(0.015, rate, 1e-9);
    }

    @Test
    void stalePlayerPaysTheIdleRate() {
        long now = 10 * DAY_MS;
        double rate = FiscalPolicy.rateFor(now - 8 * DAY_MS, now, 7, 0.015, 3.33);
        assertEquals(0.015 * 3.33, rate, 1e-9);
    }

    @Test
    void neverSeenPlayerIsTreatedAsIdle() {
        long now = 10 * DAY_MS;
        double rate = FiscalPolicy.rateFor(0L, now, 7, 0.015, 3.33);
        assertEquals(0.015 * 3.33, rate, 1e-9);
    }

    @Test
    void idleTierIsDisabledWhenWindowIsZero() {
        long now = 10 * DAY_MS;
        assertEquals(0.015, FiscalPolicy.rateFor(0L, now, 0, 0.015, 3.33), 1e-9);
    }

    @Test
    void idleTierIsSkippedWhenMultiplierIsNotAboveOne() {
        long now = 10 * DAY_MS;
        assertEquals(0.015, FiscalPolicy.rateFor(0L, now, 7, 0.015, 1.0), 1e-9);
        assertEquals(0.015, FiscalPolicy.rateFor(0L, now, 7, 0.015, 0.5), 1e-9);
    }

    @Test
    void rateIsClampedToTheHundredPercentCeiling() {
        assertEquals(1.0, FiscalPolicy.rateFor(0L, 10 * DAY_MS, 7, 0.5, 10.0), 1e-9);
    }

    @Test
    void negativeRateClampsToZero() {
        assertEquals(0.0, FiscalPolicy.rateFor(0L, 10 * DAY_MS, 7, -0.5, 1.0), 1e-9);
    }

    // --- taxFor ---

    @Test
    void balanceAtOrBelowFloorIsNeverTaxed() {
        assertEquals(0L, FiscalPolicy.taxFor(2331L, 2331L, 0.015));
        assertEquals(0L, FiscalPolicy.taxFor(1883L, 2331L, 0.015));
        assertEquals(0L, FiscalPolicy.taxFor(0L, 2331L, 0.015));
    }

    @Test
    void taxIsTheRateAppliedToTheSurplusOnly() {
        assertEquals(222L, FiscalPolicy.taxFor(17100L, 2331L, 0.015));
        assertEquals(158L, FiscalPolicy.taxFor(12875L, 2331L, 0.015));
        assertEquals(75L, FiscalPolicy.taxFor(7339L, 2331L, 0.015));
    }

    @Test
    void taxCanNeverPushABalanceBelowTheFloor() {
        long floor = 2331L;
        long balance = 2332L;
        long tax = FiscalPolicy.taxFor(balance, floor, 0.99);
        assertTrue(tax <= balance - floor, "tax " + tax + " must not exceed surplus");
        assertTrue(balance - tax >= floor);
    }

    @Test
    void zeroRateTaxesNothing() {
        assertEquals(0L, FiscalPolicy.taxFor(17100L, 2331L, 0.0));
    }

    @Test
    void rateAboveCeilingIsClamped() {
        assertEquals(14769L, FiscalPolicy.taxFor(17100L, 2331L, 5.0));
    }

    @Test
    void tinySurplusRoundsDownToZeroRatherThanGoingNegative() {
        assertEquals(0L, FiscalPolicy.taxFor(2332L, 2331L, 0.015));
    }

    // --- rebateFor ---

    @Test
    void balanceAtOrAboveFloorGetsNoRebate() {
        assertEquals(0L, FiscalPolicy.rebateFor(2331L, 2331L, 0.01));
        assertEquals(0L, FiscalPolicy.rebateFor(17100L, 2331L, 0.01));
    }

    @Test
    void rebateIsTheRateAppliedToTheShortfall() {
        assertEquals(4L, FiscalPolicy.rebateFor(1883L, 2331L, 0.01));
    }

    @Test
    void rebateCanNeverOverfillTheFloor() {
        long floor = 2331L;
        long balance = 2300L;
        long rebate = FiscalPolicy.rebateFor(balance, floor, 0.99);
        assertTrue(rebate <= floor - balance, "rebate " + rebate + " must not exceed shortfall");
        assertTrue(balance + rebate <= floor);
    }

    @Test
    void zeroRatePaysNoRebate() {
        assertEquals(0L, FiscalPolicy.rebateFor(500L, 2331L, 0.0));
    }

    // --- rebateTriggered ---

    @Test
    void healthyMedianDoesNotArmTheRebate() {
        assertFalse(FiscalPolicy.rebateTriggered(4662.0, STARTING_BALANCE, 1.0));
    }

    @Test
    void depressedMedianArmsTheRebate() {
        assertTrue(FiscalPolicy.rebateTriggered(500.0, STARTING_BALANCE, 1.0));
    }

    @Test
    void medianExactlyAtReferenceDoesNotArmTheRebate() {
        assertFalse(FiscalPolicy.rebateTriggered(1000.0, STARTING_BALANCE, 1.0));
    }

    @Test
    void unusableInputsNeverArmTheRebate() {
        assertFalse(FiscalPolicy.rebateTriggered(0.0, STARTING_BALANCE, 1.0));
        assertFalse(FiscalPolicy.rebateTriggered(500.0, 0L, 1.0));
        assertFalse(FiscalPolicy.rebateTriggered(500.0, STARTING_BALANCE, 0.0));
    }

    // --- accrual ---

    @Test
    void firstRunAppliesNothing() {
        FiscalPolicy.Accrual accrual = FiscalPolicy.accrual(-1L, 20723L, 7);
        assertEquals(0, accrual.days());
        assertFalse(accrual.clipped());
    }

    @Test
    void sameDayAppliesNothing() {
        assertEquals(0, FiscalPolicy.accrual(20723L, 20723L, 7).days());
        assertEquals(0, FiscalPolicy.accrual(20725L, 20723L, 7).days());
    }

    @Test
    void ordinaryGapIsAppliedInFull() {
        FiscalPolicy.Accrual accrual = FiscalPolicy.accrual(20722L, 20723L, 7);
        assertEquals(1, accrual.days());
        assertFalse(accrual.clipped());
    }

    @Test
    void longDowntimeIsCappedAndReported() {
        FiscalPolicy.Accrual accrual = FiscalPolicy.accrual(20700L, 20723L, 7);
        assertEquals(7, accrual.days());
        assertTrue(accrual.clipped(), "a clipped accrual must be reported so the caller can warn");
    }

    @Test
    void zeroCapMeansUnlimitedFollowingTheCodebaseConvention() {
        FiscalPolicy.Accrual accrual = FiscalPolicy.accrual(20700L, 20723L, 0);
        assertEquals(23, accrual.days());
        assertFalse(accrual.clipped());
    }
}
