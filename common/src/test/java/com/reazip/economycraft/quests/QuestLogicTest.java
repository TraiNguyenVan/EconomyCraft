package com.reazip.economycraft.quests;

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

    // --- eligible ---

    @Test
    void unitWindowAndBlacklist() {
        Set<String> blacklist = Set.of("minecraft:dirt");
        assertTrue(QuestLogic.eligible("minecraft:oak_log", 31L, 3L, 100L, blacklist));
        assertFalse(QuestLogic.eligible("minecraft:dirt", 31L, 3L, 100L, blacklist));
        assertFalse(QuestLogic.eligible("minecraft:diamond", 1088L, 3L, 100L, blacklist));
        assertFalse(QuestLogic.eligible("minecraft:stick", 2L, 3L, 100L, blacklist));
        assertFalse(QuestLogic.eligible("  ", 31L, 3L, 100L, blacklist));
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
}
