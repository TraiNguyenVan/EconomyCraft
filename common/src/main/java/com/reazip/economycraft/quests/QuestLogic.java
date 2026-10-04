package com.reazip.economycraft.quests;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * The quest board's arithmetic, kept free of Minecraft types so it is unit-testable without a server.
 *
 * <p>Every method here is a pure function over longs, strings and lists: the manager resolves prices
 * and builds stacks, and this class decides what those numbers mean. Anything that moves money or
 * touches the world lives in {@link QuestManager}.
 */
public final class QuestLogic {
    private QuestLogic() {}

    /**
     * The per-item price a quest pays, in whole coins.
     *
     * @param effectiveBuy the live effective buy unit (base buy through the dynamic multiplier), or
     *                   {@code 0} when the entry is sell-only
     * @param unitSell the catalog sell unit, the fallback basis when there is no buy price
     * @param priceFactor the configured fraction of worth a quest pays
     * @param sellFallbackMultiplier the configured buy-over-sell convention multiple
     * @return the quest unit, or {@code 0} when the entry has no usable price at all
     */
    public static long questUnit(long effectiveBuy, long unitSell, double priceFactor, double sellFallbackMultiplier) {
        long base = effectiveBuy > 0 ? effectiveBuy : Math.round(unitSell * sellFallbackMultiplier);
        if (base <= 0) return 0;
        return Math.max(1, Math.round(base * priceFactor));
    }

    /**
     * Whether a quest unit may appear on the board at all.
     *
     * @param hasBuyPrice whether the entry has a buy price (is actually on the shop)
     * @param requireShopPrice when true, sell-only entries are rejected outright
     */
    public static boolean eligible(String key, long questUnit, long minUnit, long maxUnit, Set<String> blacklist,
                                   boolean hasBuyPrice, boolean requireShopPrice) {
        if (key == null || key.isBlank()) return false;
        if (requireShopPrice && !hasBuyPrice) return false;
        if (questUnit < minUnit || questUnit > maxUnit) return false;
        return blacklist == null || !blacklist.contains(key);
    }

    /**
     * How many items a quest asks for: the per-quest budget share divided by the unit, at least one.
     *
     * <p>The result multiplied back by the unit is the quest's gross, which always fits the share to
     * within half a unit of rounding — and because the unit window floors at a few coins, the
     * per-item reward can never round to zero, so a quest never trips the order system's
     * full-amount-only fulfillment lock.
     */
    public static int questAmount(long share, long questUnit) {
        if (questUnit <= 0) return 0;
        return (int) Math.max(1, Math.round((double) share / questUnit));
    }

    /**
     * The weekly draw: {@code count} distinct candidates in seeded-shuffle order.
     *
     * <p>Seeded (not random) so a week is reproducible from its seed in logs and tests, and capped at
     * the candidate count so a thin catalog posts a thin week instead of failing.
     */
    public static List<String> draw(List<String> candidates, long seed, int count) {
        if (candidates == null || candidates.isEmpty() || count <= 0) return List.of();
        List<String> shuffled = new ArrayList<>(candidates);
        Collections.shuffle(shuffled, new Random(seed));
        return List.copyOf(shuffled.subList(0, Math.min(count, shuffled.size())));
    }

    /** Whether a quest priced {@code price} still fits the week's remaining mint headroom. */
    public static boolean fitsBudget(long minted, long price, long budget) {
        return price >= 0 && minted + price <= budget;
    }

    /**
     * How many coins to mint for a quest: the shortfall between its price and the bot's free balance,
     * capped at the week's remaining headroom. Zero when the balance already covers it — and zero when
     * the budget is exhausted, which the caller reads as "stop posting" via the still-uncovered price.
     */
    public static long mintNeeded(long funded, long price, long minted, long budget) {
        long shortfall = price - funded;
        if (shortfall <= 0) return 0;
        return Math.max(0, Math.min(shortfall, budget - minted));
    }
}
