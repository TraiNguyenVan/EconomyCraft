package com.reazip.economycraft.auction;

import com.reazip.economycraft.EconomyCraft;

/**
 * Wording of the two {@code /ah} chat notices. Pure formatting on purpose: the broadcast path needs a
 * server, but the text itself is the part worth pinning down in a test.
 */
public final class AuctionNoticeText {
    private AuctionNoticeText() {}

    /**
     * Percentage off the listing's <em>first</em> price, rounded to a whole number and floored at 1 for
     * any positive drop, so a one-unit cut reads as {@code 1%} rather than {@code 0%}. Returns 0 when
     * there is no drop to advertise.
     */
    public static int percentOff(long originalPrice, long currentPrice) {
        if (originalPrice <= 0 || currentPrice >= originalPrice) return 0;
        long percent = Math.round((originalPrice - currentPrice) * 100.0 / originalPrice);
        return (int) Math.max(1L, percent);
    }

    public static String listing(String seller, int count, String itemName, long price) {
        return "[AH] " + seller + " listed " + count + "x " + itemName + " for " + EconomyCraft.formatMoney(price);
    }

    public static String priceDrop(int count, String itemName, long originalPrice, long currentPrice) {
        return "[AH] SALE " + percentOff(originalPrice, currentPrice) + "% off — " + count + "x " + itemName
                + " now " + EconomyCraft.formatMoney(currentPrice)
                + " (was " + EconomyCraft.formatMoney(originalPrice) + ")";
    }

    /** The command behind the {@code [View]} button: the pre-filtered board for this item. */
    public static String viewCommand(String itemName) {
        return "/ah search " + itemName;
    }
}
