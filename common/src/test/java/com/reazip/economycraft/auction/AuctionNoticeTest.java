package com.reazip.economycraft.auction;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The wording and the recipient rule behind the {@code /ah} notices. Both are pure functions, which is
 * the point: the server wiring around them is not what tends to break.
 */
class AuctionNoticeTest {

    @Test
    @DisplayName("percentage is measured against the first price, rounded, and never reads 0%")
    void percentOff() {
        assertEquals(33, AuctionNoticeText.percentOff(150, 100));
        assertEquals(20, AuctionNoticeText.percentOff(150, 120));
        assertEquals(67, AuctionNoticeText.percentOff(300, 100));
        assertEquals(1, AuctionNoticeText.percentOff(150, 149));
        assertEquals(0, AuctionNoticeText.percentOff(150, 150));
        assertEquals(0, AuctionNoticeText.percentOff(150, 200));
        assertEquals(0, AuctionNoticeText.percentOff(0, 100));
    }

    @Test
    @DisplayName("a new listing reads one line with seller, count, item and price")
    void listingText() {
        assertEquals("[AH] Mambo67 listed 5x Diamond for $120",
                AuctionNoticeText.listing("Mambo67", 5, "Diamond", 120));
    }

    @Test
    @DisplayName("a price drop carries the discount, the new price and the first price")
    void priceDropText() {
        assertEquals("[AH] SALE 20% off — 5x Diamond now $120 (was $150)",
                AuctionNoticeText.priceDrop(5, "Diamond", 150, 120));
    }

    @Test
    @DisplayName("the [View] button searches the board for the item")
    void viewCommand() {
        assertEquals("/ah search Diamond Sword", AuctionNoticeText.viewCommand("Diamond Sword"));
    }

    @Test
    @DisplayName("the offline queue skips the seller and anyone past the window")
    void queueAudience() {
        UUID seller = UUID.randomUUID();
        UUID buyer = UUID.randomUUID();
        long now = 1_800_000_000_000L;
        long recent = now - TimeUnit.DAYS.toMillis(3);
        long stale = now - TimeUnit.DAYS.toMillis(31);

        assertTrue(AuctionNoticeAudience.shouldQueue(buyer, seller, recent, now, 30));
        assertFalse(AuctionNoticeAudience.shouldQueue(seller, seller, recent, now, 30));
        assertFalse(AuctionNoticeAudience.shouldQueue(buyer, seller, stale, now, 30));
        assertFalse(AuctionNoticeAudience.shouldQueue(buyer, seller, 0L, now, 30));
        assertFalse(AuctionNoticeAudience.shouldQueue(buyer, seller, recent, now, 0));
    }
}
