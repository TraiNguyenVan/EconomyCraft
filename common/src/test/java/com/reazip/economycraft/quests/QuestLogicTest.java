package com.reazip.economycraft.quests;

import com.reazip.economycraft.config.QuestsSection;
import com.reazip.economycraft.orders.OrderManager;
import com.reazip.economycraft.orders.OrderRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the quest board's arithmetic, which is deliberately free of Minecraft types.
 *
 * <p>The property these tests exist to protect is the dust guard: a quest's per-item reward must
 * never round to zero, because the order system turns a zero per-item reward into a
 * full-amount-only fulfillment lock — a bounty nobody can fill in part.
 */
class QuestLogicTest {

    // --- questUnit ---

    @Test
    void buyPathPaysHalfOfEffectiveBuy() {
        assertEquals(50L, QuestLogic.questUnit(100L, 0L, 0.5, 3.3));
    }

    @Test
    void buyPathRoundsHalfUp() {
        assertEquals(8L, QuestLogic.questUnit(15L, 0L, 0.5, 3.3));
    }

    @Test
    void buyPathFloorsAtOneCoin() {
        assertEquals(1L, QuestLogic.questUnit(1L, 0L, 0.5, 3.3));
    }

    @Test
    void sellOnlyFallsBackToBuyOverSellConvention() {
        // 6 * 3.3 * 0.5 = 9.9 -> 10.
        assertEquals(10L, QuestLogic.questUnit(0L, 6L, 0.5, 3.3));
    }

    @Test
    void worthlessEntryPricesToZero() {
        assertEquals(0L, QuestLogic.questUnit(0L, 0L, 0.5, 3.3));
    }

    @Test
    void buybackChargesFourFifthsOfEffectiveBuy() {
        // The buyback discount: 100 * 0.8 = 80.
        assertEquals(80L, QuestLogic.questUnit(100L, 0L, 0.8, 3.3));
    }

    @Test
    void buybackSellOnlyUsesTheSameFallback() {
        // 6 * 3.3 * 0.8 = 15.84 -> 16.
        assertEquals(16L, QuestLogic.questUnit(0L, 6L, 0.8, 3.3));
    }

    // --- eligible ---

    @Test
    void unitWindowAndBlacklist() {
        Set<String> blacklist = Set.of("minecraft:dirt");
        assertTrue(QuestLogic.eligible("minecraft:oak_log", 31L, 3L, 100L, blacklist, true, true));
        assertFalse(QuestLogic.eligible("minecraft:dirt", 31L, 3L, 100L, blacklist, true, true));
        assertFalse(QuestLogic.eligible("minecraft:diamond", 1088L, 3L, 100L, blacklist, true, true));
        assertFalse(QuestLogic.eligible("minecraft:stick", 2L, 3L, 100L, blacklist, true, true));
        assertFalse(QuestLogic.eligible("  ", 31L, 3L, 100L, blacklist, true, true));
    }

    @Test
    void shopGateRejectsSellOnlyEntriesOnlyWhenRequired() {
        Set<String> blacklist = Set.of();
        // A sell-only entry inside the window: gated when required, welcome when not.
        assertFalse(QuestLogic.eligible("minecraft:cake", 72L, 3L, 100L, blacklist, false, true));
        assertTrue(QuestLogic.eligible("minecraft:cake", 72L, 3L, 100L, blacklist, false, false));
        // A shop-priced entry passes either way.
        assertTrue(QuestLogic.eligible("minecraft:oak_log", 22L, 3L, 100L, blacklist, true, true));
        assertTrue(QuestLogic.eligible("minecraft:oak_log", 22L, 3L, 100L, blacklist, true, false));
    }

    // --- questAmount ---

    @Test
    void amountSplitsTheShare() {
        assertEquals(150, QuestLogic.questAmount(1200L, 8L));
        assertEquals(171, QuestLogic.questAmount(1200L, 7L));
    }

    @Test
    void expensiveUnitStillAsksForOne() {
        assertEquals(1, QuestLogic.questAmount(1200L, 5000L));
    }

    @Test
    void zeroUnitAsksForNothing() {
        assertEquals(0, QuestLogic.questAmount(1200L, 0L));
    }

    // --- draw ---

    @Test
    void drawIsDeterministicPerSeed() {
        List<String> candidates = IntStream.range(0, 50).mapToObj(i -> "item:" + i).toList();
        assertEquals(QuestLogic.draw(candidates, 42L, 10), QuestLogic.draw(candidates, 42L, 10));
    }

    @Test
    void drawPicksDistinctItemsAndCapsAtCatalogSize() {
        List<String> candidates = IntStream.range(0, 50).mapToObj(i -> "item:" + i).toList();
        List<String> drawn = QuestLogic.draw(candidates, 7L, 10);
        assertEquals(10, drawn.size());
        assertEquals(10, drawn.stream().collect(Collectors.toSet()).size());

        List<String> thin = QuestLogic.draw(List.of("a", "b"), 7L, 10);
        assertEquals(List.of("a", "b").stream().sorted().toList(),
                thin.stream().sorted().toList());
    }

    @Test
    void drawRejectsEmptyInput() {
        assertTrue(QuestLogic.draw(List.of(), 1L, 10).isEmpty());
        assertTrue(QuestLogic.draw(null, 1L, 10).isEmpty());
        assertTrue(QuestLogic.draw(List.of("a"), 1L, 0).isEmpty());
    }

    // --- lot sizing (oversized-stack guard) ---

    @Test
    void mergeOnlyFitsWhileTheLotHasRoom() {
        assertTrue(QuestLogic.fitsInLot(0, 64));
        assertTrue(QuestLogic.fitsInLot(63, 64));
        assertFalse(QuestLogic.fitsInLot(64, 64));
        assertFalse(QuestLogic.fitsInLot(240, 64));
        // A zero stack size must not read as "always room": it clamps to one lot.
        assertFalse(QuestLogic.fitsInLot(1, 0));
        assertTrue(QuestLogic.fitsInLot(0, 0));
        assertFalse(QuestLogic.fitsInLot(1, -5));
    }

    // --- mintNeeded ---

    @Test
    void mintCoversShortfallUpToHeadroom() {
        // Absent bot balance funds as zero: no starting grant may leak past the cap.
        assertEquals(1200L, QuestLogic.mintNeeded(0L, 1200L, 0L, 12000L));
        assertEquals(200L, QuestLogic.mintNeeded(1000L, 1200L, 0L, 12000L));
        assertEquals(0L, QuestLogic.mintNeeded(1200L, 1200L, 0L, 12000L));
        assertEquals(0L, QuestLogic.mintNeeded(1500L, 1200L, 0L, 12000L));
    }

    @Test
    void mintStopsAtBudgetExhaustion() {
        assertEquals(1140L, QuestLogic.mintNeeded(0L, 1200L, 10860L, 12000L));
        assertEquals(0L, QuestLogic.mintNeeded(0L, 1200L, 12000L, 12000L));
        assertEquals(0L, QuestLogic.mintNeeded(0L, 1200L, 13000L, 12000L));
    }

    @Test
    void exactFitStillFits() {
        assertTrue(QuestLogic.fitsBudget(10800L, 1200L, 12000L));
        assertFalse(QuestLogic.fitsBudget(10801L, 1200L, 12000L));
        // The live week-1 shape: nine ~1200 quests consume 10860, the tenth stops fitting.
        assertFalse(QuestLogic.fitsBudget(10860L, 1200L, 12000L));
    }

    @Test
    void anAbsurdPeriodClampsToTheCeiling() {
        QuestsSection quests = new QuestsSection();
        quests.periodDays = 10_000;
        quests.clamp();
        assertEquals(QuestsSection.MAX_PERIOD_DAYS, quests.periodDays);
    }

    @Test
    void aNegativePeriodClampsToOneDay() {
        QuestsSection quests = new QuestsSection();
        quests.periodDays = -3;
        quests.clamp();
        assertEquals(1, quests.periodDays);
    }

    // --- the reset period ---

    @Test
    void periodMillisIsWholeDays() {
        assertEquals(86_400_000L, QuestLogic.periodMillis(1));
        assertEquals(604_800_000L, QuestLogic.periodMillis(7));
        assertEquals(2_592_000_000L, QuestLogic.periodMillis(30));
    }

    @Test
    void periodMillisSurvivesTheMaximumPeriod() {
        // 365 days: the widest value the clamp allows must not overflow into a negative period,
        // which would read as "always elapsed" and roll the board over every single sweep.
        assertEquals(31_536_000_000L, QuestLogic.periodMillis(365));
    }

    @Test
    void periodMillisNeverReturnsZeroForADegenerateCount() {
        // The clamp rejects 0 before this is reachable; a sub-day period here is still safer than a
        // zero, which would make every sweep rollover and burn the bot balance every minute.
        assertTrue(QuestLogic.periodMillis(0) >= 86_400_000L);
        assertTrue(QuestLogic.periodMillis(-5) >= 86_400_000L);
    }

    @Test
    void periodElapsedOnlyAtTheConfiguredBoundary() {
        long start = 1_000_000L;
        long sevenDays = QuestLogic.periodMillis(7);
        assertFalse(QuestLogic.periodElapsed(start, start, 7));
        assertFalse(QuestLogic.periodElapsed(start, start + sevenDays - 1, 7));
        assertTrue(QuestLogic.periodElapsed(start, start + sevenDays, 7));
    }

    @Test
    void shorteningThePeriodElapsesAnAlreadyLongRunningBoard() {
        // The behaviour an admin gets when they cut a board that is five days into a seven-day period
        // down to three days: it closes on the next sweep rather than running to the length it was
        // posted under.
        long start = 1_000_000L;
        long fiveDaysIn = start + 5L * 86_400_000L;
        assertFalse(QuestLogic.periodElapsed(start, fiveDaysIn, 7));
        assertTrue(QuestLogic.periodElapsed(start, fiveDaysIn, 3));
        assertTrue(QuestLogic.periodElapsed(start, fiveDaysIn, 1));
    }

    @Test
    void aNeverStartedPeriodCountsAsElapsed() {
        // Fresh install, or a state file that failed to load: the first sweep must draw a board.
        assertTrue(QuestLogic.periodElapsed(0L, 1_000_000L, 7));
        assertTrue(QuestLogic.periodElapsed(-1L, 1_000_000L, 7));
    }

    @Test
    void countdownNeverGoesNegative() {
        long start = 1_000_000L;
        assertEquals(QuestLogic.periodMillis(7), QuestLogic.millisUntilPeriodEnd(start, start, 7));
        assertEquals(86_400_000L, QuestLogic.millisUntilPeriodEnd(start, start + 6L * 86_400_000L, 7));
        assertEquals(0L, QuestLogic.millisUntilPeriodEnd(start, start + 7L * 86_400_000L, 7));
        assertEquals(0L, QuestLogic.millisUntilPeriodEnd(start, start + 99L * 86_400_000L, 7));
        assertEquals(0L, QuestLogic.millisUntilPeriodEnd(0L, start, 7));
    }

    // --- the dust guard, end to end ---

    @Test
    void sizedQuestsNeverTripTheFullAmountLock() {
        // Every unit in the drawable window, at the locked share, must produce an order whose
        // per-item reward is nonzero — otherwise the board posts bounties nobody can fill in part.
        for (long unit = 3L; unit <= 100L; unit++) {
            int amount = QuestLogic.questAmount(1200L, unit);
            long price = amount * unit;
            OrderRequest order = new OrderRequest();
            order.amount = amount;
            order.price = price;
            assertEquals(unit, OrderManager.rewardPerItem(price, amount));
            assertFalse(OrderManager.requiresCompleteFulfillment(order),
                    "unit=" + unit + " amount=" + amount);
        }
    }

    // --- shouldReprice (drift deadband) ---

    @Test
    void shouldRepriceRespectsPercentageDeadband() {
        // At 10% threshold:
        assertFalse(QuestLogic.shouldReprice(100L, 100L, 0.10));
        assertFalse(QuestLogic.shouldReprice(100L, 105L, 0.10)); // +5%
        assertFalse(QuestLogic.shouldReprice(100L, 95L, 0.10));  // -5%
        assertFalse(QuestLogic.shouldReprice(100L, 109L, 0.10)); // +9%
        assertTrue(QuestLogic.shouldReprice(100L, 110L, 0.10));  // +10%
        assertTrue(QuestLogic.shouldReprice(100L, 90L, 0.10));   // -10%
        assertTrue(QuestLogic.shouldReprice(100L, 125L, 0.10));  // +25%
        assertTrue(QuestLogic.shouldReprice(100L, 75L, 0.10));   // -25%

        // Zero threshold reprices on any change
        assertFalse(QuestLogic.shouldReprice(100L, 100L, 0.0));
        assertTrue(QuestLogic.shouldReprice(100L, 101L, 0.0));
    }

    // --- balancedAmount (dynamic budget balancing) ---

    @Test
    void balancedAmountNeverOvershootsAvailableFunding() {
        assertEquals(0, QuestLogic.balancedAmount(0L, 1, 10L));
        assertEquals(0, QuestLogic.balancedAmount(50L, 1, 100L)); // cannot afford even 1 unit

        // Last slot takes entire remaining funding cleanly
        assertEquals(15, QuestLogic.balancedAmount(150L, 1, 10L));

        // Multiple slots distribute evenly and never overshoot availableFunding
        for (long funding = 100L; funding <= 12_000L; funding += 250L) {
            for (int slots = 1; slots <= 10; slots++) {
                for (long unit = 3L; unit <= 300L; unit += 17L) {
                    int amount = QuestLogic.balancedAmount(funding, slots, unit);
                    long cost = amount * unit;
                    assertTrue(cost <= funding,
                            "cost " + cost + " exceeded funding " + funding + " (slots=" + slots + ", unit=" + unit + ")");
                }
            }
        }
    }

    // --- stratifiedDraw (category quotas and weights) ---

    @Test
    void stratifiedDrawEnforcesCategoryQuotaAndWeights() {
        List<QuestLogic.ScoredCandidate> candidates = List.of(
                new QuestLogic.ScoredCandidate("stone:1", "blocks.stones", 1.0),
                new QuestLogic.ScoredCandidate("stone:2", "blocks.stones", 1.0),
                new QuestLogic.ScoredCandidate("stone:3", "blocks.stones", 1.0),
                new QuestLogic.ScoredCandidate("stone:4", "blocks.stones", 1.0),
                new QuestLogic.ScoredCandidate("food:1", "food", 2.0),
                new QuestLogic.ScoredCandidate("food:2", "food", 1.5),
                new QuestLogic.ScoredCandidate("food:3", "food", 1.0),
                new QuestLogic.ScoredCandidate("ore:1", "ores", 3.0),
                new QuestLogic.ScoredCandidate("ore:2", "ores", 2.5),
                new QuestLogic.ScoredCandidate("ore:3", "ores", 1.0)
        );

        java.util.Map<String, Integer> weights = java.util.Map.of(
                "ores", 30,
                "food", 25,
                "blocks.stones", 10
        );

        var result = QuestLogic.stratifiedDraw(candidates, weights, 2, 5, 42L);
        assertEquals(5, result.primary().size());
        assertEquals(5, result.backfill().size());

        // Assert at most 2 per category in primary
        long stones = result.primary().stream().filter(k -> k.startsWith("stone:")).count();
        long foods = result.primary().stream().filter(k -> k.startsWith("food:")).count();
        long ores = result.primary().stream().filter(k -> k.startsWith("ore:")).count();

        assertTrue(stones <= 2, "stones count: " + stones);
        assertTrue(foods <= 2, "foods count: " + foods);
        assertTrue(ores <= 2, "ores count: " + ores);

        // Highest weight category ores is picked first
        assertTrue(result.primary().get(0).startsWith("ore:"));
    }
}
