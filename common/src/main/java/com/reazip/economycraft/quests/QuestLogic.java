package com.reazip.economycraft.quests;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
     * Sizing with dynamic share balancing: distributes the currently available funding across the
     * remaining quest slots, ensuring rounding never overshoots the available headroom.
     *
     * @param availableFunding bot wallet balance plus remaining unminted budget
     * @param remainingSlots number of quest slots remaining to post in this draw (at least 1)
     * @param questUnit per-item quest price
     * @return how many items to request, or 0 if the funding cannot afford even 1 unit
     */
    public static int balancedAmount(long availableFunding, int remainingSlots, long questUnit) {
        if (questUnit <= 0 || availableFunding <= 0 || remainingSlots <= 0) return 0;
        long targetShare = Math.max(1, availableFunding / remainingSlots);
        int nominal = (int) Math.max(1, Math.round((double) targetShare / questUnit));
        long maxAffordable = availableFunding / questUnit;
        if (maxAffordable <= 0) return 0;
        return (int) Math.min(nominal, maxAffordable);
    }

    /**
     * Whether a quest's posted unit has drifted enough from the live unit to warrant cancelling and
     * reposting. A drift strictly below {@code thresholdPercent} is ignored to prevent chat spam and
     * order churn on hourly inflation adjustments.
     */
    public static boolean shouldReprice(long postedUnit, long liveUnit, double thresholdPercent) {
        if (postedUnit <= 0 || liveUnit <= 0) return postedUnit != liveUnit;
        if (thresholdPercent <= 0.0) return postedUnit != liveUnit;
        double drift = Math.abs((double) (liveUnit - postedUnit)) / (double) postedUnit;
        return drift >= thresholdPercent;
    }

    /**
     * How long one quest period lasts, in milliseconds.
     *
     * <p>Whole days only: the board's job is to give players a fixed, predictable span to work through
     * the same ten bounties, and an hour-granular dial is a knob nobody tunes well. The multiplication is
     * wide enough that {@code days <= 365} cannot overflow a long.
     */
    public static long periodMillis(int days) {
        return Math.max(1, days) * 24L * 60L * 60L * 1000L;
    }

    /**
     * Whether the current period is over and the board must roll over.
     *
     * <p>A {@code periodStartMs} of {@code 0} or less means "no period has ever started here" — a fresh
     * install or a state file that failed to load — and must count as elapsed, or the board would never
     * draw its first set.
     *
     * <p>Reading this live off the configured period rather than a value frozen at draw time is deliberate:
     * an admin who shortens the period mid-board wants the board to close, not to run to the length it
     * was posted under.
     */
    public static boolean periodElapsed(long periodStartMs, long now, int periodDays) {
        if (periodStartMs <= 0) return true;
        return now - periodStartMs >= periodMillis(periodDays);
    }

    /** Milliseconds until the current period rolls over; {@code 0} once it has already elapsed. */
    public static long millisUntilPeriodEnd(long periodStartMs, long now, int periodDays) {
        if (periodStartMs <= 0) return 0L;
        return Math.max(0L, periodMillis(periodDays) - (now - periodStartMs));
    }

    /**
     * The periodic draw: {@code count} distinct candidates in seeded-shuffle order.
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

    public record ScoredCandidate(String key, String category, double score) {}

    public record DrawResult(List<String> primary, List<String> backfill) {}

    /**
     * A stratified draw: picks candidates across distinct categories up to {@code maxPerCategory},
     * weighted by category priority and market score, using {@code seed} for reproducibility.
     * Returns an ordered list of primary keys up to {@code count}, and remaining backfill keys.
     */
    public static DrawResult stratifiedDraw(List<ScoredCandidate> candidates,
                                            Map<String, Integer> categoryWeights,
                                            int maxPerCategory,
                                            int count,
                                            long seed) {
        if (candidates == null || candidates.isEmpty() || count <= 0) {
            return new DrawResult(List.of(), List.of());
        }
        int perCatLimit = Math.max(1, maxPerCategory);

        Map<String, List<ScoredCandidate>> byCategory = new LinkedHashMap<>();
        for (ScoredCandidate c : candidates) {
            String cat = c.category() != null ? c.category() : "misc";
            byCategory.computeIfAbsent(cat, k -> new ArrayList<>()).add(c);
        }

        Random rng = new Random(seed);
        for (var entry : byCategory.entrySet()) {
            String cat = entry.getKey();
            int catWeight = categoryWeights != null ? categoryWeights.getOrDefault(cat, 10) : 10;
            Collections.shuffle(entry.getValue(), rng);
            entry.getValue().sort((a, b) -> Double.compare(b.score() * catWeight, a.score() * catWeight));
        }

        List<String> sortedCategories = new ArrayList<>(byCategory.keySet());
        sortedCategories.sort((catA, catB) -> {
            int wA = categoryWeights != null ? categoryWeights.getOrDefault(catA, 10) : 10;
            int wB = categoryWeights != null ? categoryWeights.getOrDefault(catB, 10) : 10;
            return Integer.compare(wB, wA);
        });

        List<String> selected = new ArrayList<>();
        Map<String, Integer> categoryUsage = new HashMap<>();
        Map<String, Integer> categoryIndex = new HashMap<>();

        boolean progress = true;
        while (selected.size() < count && progress) {
            progress = false;
            for (String cat : sortedCategories) {
                if (selected.size() >= count) break;
                int used = categoryUsage.getOrDefault(cat, 0);
                if (used >= perCatLimit) continue;
                List<ScoredCandidate> catList = byCategory.get(cat);
                int idx = categoryIndex.getOrDefault(cat, 0);
                if (idx < catList.size()) {
                    selected.add(catList.get(idx).key());
                    categoryIndex.put(cat, idx + 1);
                    categoryUsage.put(cat, used + 1);
                    progress = true;
                }
            }
        }

        List<ScoredCandidate> backfillCandidates = new ArrayList<>();
        for (String cat : sortedCategories) {
            List<ScoredCandidate> catList = byCategory.get(cat);
            int idx = categoryIndex.getOrDefault(cat, 0);
            for (int i = idx; i < catList.size(); i++) {
                backfillCandidates.add(catList.get(i));
            }
        }
        backfillCandidates.sort((a, b) -> {
            String catA = a.category() != null ? a.category() : "misc";
            String catB = b.category() != null ? b.category() : "misc";
            int wA = categoryWeights != null ? categoryWeights.getOrDefault(catA, 10) : 10;
            int wB = categoryWeights != null ? categoryWeights.getOrDefault(catB, 10) : 10;
            return Double.compare(b.score() * wB, a.score() * wA);
        });

        while (selected.size() < count && !backfillCandidates.isEmpty()) {
            selected.add(backfillCandidates.remove(0).key());
        }

        List<String> backfillKeys = new ArrayList<>();
        for (ScoredCandidate c : backfillCandidates) {
            backfillKeys.add(c.key());
        }

        return new DrawResult(List.copyOf(selected), List.copyOf(backfillKeys));
    }

/** Whether a merge would stay inside one lot: {@code take} more units fit on {@code open}. */
    public static boolean fitsInLot(int open, int lotSize) {
        return open >= 0 && open < Math.max(1, lotSize);
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
