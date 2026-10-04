package com.reazip.economycraft.negotiation;

import com.reazip.economycraft.auction.AuctionListing;
import com.reazip.economycraft.orders.OrderRequest;
import com.reazip.economycraft.quests.QuestManager;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bot exclusion the feature's economy depends on: buyback listings and server bounty orders
 * carry mint-policy prices and the bot never reads messages, so neither may take offers.
 */
class NegotiationEventsTest {

    @Test
    void playerListingIsNegotiable() {
        AuctionListing listing = new AuctionListing();
        listing.seller = UUID.randomUUID();
        assertTrue(NegotiationEvents.canNegotiateAuction(listing));
    }

    @Test
    void buybackListingIsNotNegotiable() {
        AuctionListing listing = new AuctionListing();
        listing.seller = QuestManager.BOT_UUID;
        assertFalse(NegotiationEvents.canNegotiateAuction(listing));
    }

    @Test
    void nullListingIsNotNegotiable() {
        assertFalse(NegotiationEvents.canNegotiateAuction(null));
    }

    @Test
    void playerRequestIsNegotiable() {
        OrderRequest request = new OrderRequest();
        request.requester = UUID.randomUUID();
        assertTrue(NegotiationEvents.canNegotiateOrder(request));
    }

    @Test
    void bountyRequestIsNotNegotiable() {
        OrderRequest request = new OrderRequest();
        request.requester = QuestManager.BOT_UUID;
        assertFalse(NegotiationEvents.canNegotiateOrder(request));
    }

    @Test
    void nullRequestIsNotNegotiable() {
        assertFalse(NegotiationEvents.canNegotiateOrder(null));
    }
}
