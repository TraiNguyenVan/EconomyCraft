package com.reazip.economycraft.config;

import com.google.gson.annotations.SerializedName;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.List;

/**
 * The {@code quests} section: the automatic weekly server bounty board.
 *
 * <p>Once a week the server draws {@code weekly_count} distinct priced items and posts them all at once
 * as buy orders from a reserved bot account, each priced at {@code price_factor} of its effective buy
 * unit. Fills pay through the ordinary order path (including the order tax), and the bought items are
 * diverted into a stock ledger instead of a deliveries mailbox. The week is one-shot: a filled or
 * expired quest never reposts, and whatever budget is left unspent simply never mints.
 *
 * <p>Money enters only through a capped weekly mint ({@code weekly_budget}): the bot tops its balance up
 * to each quest's escrow and every minted coin is counted. An expired quest's escrow refunds to the bot
 * wallet, but the mint cap stays consumed — and at the week rollover any leftover bot balance is burned,
 * so refunds never carry purchasing power into the next week.
 */
public class QuestsSection {
    private static final Logger LOGGER = LogUtils.getLogger();

    @SerializedName("enabled")
    public boolean enabled = true;

    /**
     * How many coins the bot may mint per rolling 7-day week. Posting stops when the next quest would
     * exceed it; {@code 0} posts nothing.
     */
    @SerializedName("weekly_budget")
    public long weeklyBudget = 12_000L;

    /**
     * Fraction of the effective buy unit a quest pays per item. Sell-only entries (no buy price) fall
     * back to {@code price_factor * sell_fallback_multiplier * unit_sell}, mirroring the catalog
     * convention that a buy price is roughly three times its sell price.
     */
    @SerializedName("price_factor")
    public double priceFactor = 0.5;

    /** How many distinct items the weekly draw picks. */
    @SerializedName("weekly_count")
    public int weeklyCount = 10;

    /**
     * Safety ceiling on simultaneously open quest orders. Equals {@code weekly_count} by default, so
     * the whole draw posts at once as the spec requires.
     */
    @SerializedName("max_concurrent")
    public int maxConcurrent = 10;

    /**
     * Quest-unit price window an entry must fall in to be drawable. The floor kills zero-value junk,
     * the ceiling kills quests that would pay a fortune for a single item (a diamond at these factors
     * prices to a 1-count quest a stockpile clears sight unseen).
     */
    @SerializedName("min_quest_unit")
    public long minQuestUnit = 3L;

    @SerializedName("max_quest_unit")
    public long maxQuestUnit = 100L;

    /**
     * Multiplier applied to {@code unit_sell} for entries with no buy price. Kept explicit rather than
     * hardcoded so a future repricing of the catalog convention does not need a code change.
     */
    @SerializedName("sell_fallback_multiplier")
    public double sellFallbackMultiplier = 3.3;

    /** Price keys that are never drawable, even inside the unit window. */
    @SerializedName("blacklist")
    public List<String> blacklist = List.of();

    /**
     * When true, only entries with a buy price (actually on the shop) can be drawn for quests or
     * listed by the buyback. Sell-only catalog entries never touch the board or {@code /ah}.
     * Buyback stock already banked for a buy-less item is voided on the next sweep.
     */
    @SerializedName("require_shop_price")
    public boolean requireShopPrice = true;

    /** Display name the bot account resolves to in order lists and lore. */
    @SerializedName("bot_name")
    public String botName = "Server Quests";

    @SerializedName("buyback")
    public BuybackSettings buyback = new BuybackSettings();

    public void clamp() {
        weeklyBudget = ConfigClamp.nonNegative("quests.weekly_budget", weeklyBudget);
        priceFactor = ConfigClamp.percentage("quests.price_factor", priceFactor);
        weeklyCount = clampAtLeastOne("quests.weekly_count", weeklyCount);
        maxConcurrent = clampAtLeastOne("quests.max_concurrent", maxConcurrent);
        minQuestUnit = ConfigClamp.nonNegative("quests.min_quest_unit", minQuestUnit);
        maxQuestUnit = ConfigClamp.nonNegative("quests.max_quest_unit", maxQuestUnit);
        if (maxQuestUnit < minQuestUnit) {
            LOGGER.warn("[EconomyCraft] quests.max_quest_unit ({}) is below quests.min_quest_unit ({}); raising it to match.",
                    maxQuestUnit, minQuestUnit);
            maxQuestUnit = minQuestUnit;
        }
        if (!(sellFallbackMultiplier > 0)) {
            LOGGER.warn("[EconomyCraft] quests.sell_fallback_multiplier ({}) is not positive; resetting to 3.3.",
                    sellFallbackMultiplier);
            sellFallbackMultiplier = 3.3;
        }
        blacklist = ConfigClamp.cleanList("quests.blacklist", blacklist);
        if (botName == null || botName.isBlank()) {
            LOGGER.warn("[EconomyCraft] quests.bot_name is blank; using 'Server Quests'.");
            botName = "Server Quests";
        } else {
            botName = botName.trim();
        }
        buyback.clamp();
    }

    private static int clampAtLeastOne(String fieldName, int value) {
        if (value < 1) {
            LOGGER.warn("[EconomyCraft] {} ({}) is below 1; clamping to 1.", fieldName, value);
            return 1;
        }
        return value;
    }

    /**
     * The Phase 2 buyback market: resells accumulated quest stock to players through ordinary
     * {@code /ah} listings owned by the bot account, one listing per stocked item.
     *
     * <p>Unlisted stock lists on the next quest sweep and new fills merge into the open listing
     * with the whole stack repriced at the current unit; open listings reprice weekly at the
     * rollover; an expired listing's items return to the ledger and relist on the next sweep.
     * Purchases are tax-free both ways — the buyer pays the sticker price and the whole of it
     * refills the bot wallet outside the mint cap (leftover bot balance still burns at rollover).
     */
    public static class BuybackSettings {
        @SerializedName("enabled")
        public boolean enabled = true;

        /** Fraction of the effective buy unit the buyback charges. {@code 0.8} sells stock back discounted. */
        @SerializedName("price_factor")
        public double priceFactor = 0.8;

        public void clamp() {
            priceFactor = ConfigClamp.percentage("quests.buyback.price_factor", priceFactor);
        }
    }
}
