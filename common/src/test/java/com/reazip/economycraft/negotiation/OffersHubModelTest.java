package com.reazip.economycraft.negotiation;

import com.reazip.economycraft.util.AsyncFileWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The selection rules behind the offers hub: one incoming row per target (not per offer),
 * one outgoing row per offer, incoming first, and no row for a target the viewer cannot act on
 * or that has already disappeared.
 */
class OffersHubModelTest {

    @AfterEach
    void flushWrites() {
        AsyncFileWriter.flush();
    }

    private static NegotiationStore storeIn(@TempDir Path dir) {
        return new NegotiationStore(dir.resolve("negotiations.json"));
    }

    private static OffersHubModel.Target listing(int id, UUID seller, long price, boolean negotiable) {
        return new OffersHubModel.Target(NegotiationStore.Kind.AH, id, seller, "listing " + id,
                price, negotiable);
    }

    private static OffersHubModel.Target request(int id, UUID owner, long price, boolean negotiable) {
        return new OffersHubModel.Target(NegotiationStore.Kind.ORDER, id, owner, "request " + id,
                price, negotiable);
    }

    @Test
    void sixOffersOnOneListingAreOneRowCarryingTheBestPrice(@TempDir Path dir) {
        NegotiationStore store = storeIn(dir);
        UUID seller = UUID.randomUUID();
        store.makeOffer(NegotiationStore.Kind.AH, 7, UUID.randomUUID(), 100);
        store.makeOffer(NegotiationStore.Kind.AH, 7, UUID.randomUUID(), 400);
        store.makeOffer(NegotiationStore.Kind.AH, 7, UUID.randomUUID(), 250);

        List<OffersHubModel.Row> rows = OffersHubModel.build(List.of(listing(7, seller, 500, true)),
                store, seller, id -> "buyer");

        assertEquals(1, rows.size());
        assertEquals(OffersHubModel.Side.INCOMING, rows.get(0).side());
        assertEquals(400, rows.get(0).offerPrice());
        assertEquals(3, rows.get(0).offerCount());
        assertEquals(500, rows.get(0).targetPrice());
        assertEquals(3, OffersHubModel.incomingOfferCount(rows));
    }

    @Test
    void incomingRowsSortByBestOfferAndPrecedeOutgoingOnes(@TempDir Path dir) throws Exception {
        NegotiationStore store = storeIn(dir);
        UUID seller = UUID.randomUUID();
        UUID buyer = UUID.randomUUID();
        store.makeOffer(NegotiationStore.Kind.AH, 1, buyer, 100);
        Thread.sleep(5);
        store.makeOffer(NegotiationStore.Kind.AH, 2, buyer, 900);

        List<OffersHubModel.Target> targets = List.of(
                listing(1, seller, 200, true),
                listing(2, seller, 1000, true));
        List<OffersHubModel.Row> rows = OffersHubModel.build(targets, store, buyer, id -> "seller");

        assertEquals(2, rows.size());
        // Both offers are the buyer's own, so from the buyer's side they are outgoing only, newest first.
        assertEquals(List.of(900L, 100L), rows.stream().map(OffersHubModel.Row::offerPrice).toList());
        assertTrue(rows.stream().allMatch(r -> r.side() == OffersHubModel.Side.OUTGOING));

        List<OffersHubModel.Row> sellerRows = OffersHubModel.build(targets, store, seller, id -> "buyer");
        assertEquals(900, sellerRows.get(0).offerPrice());
        assertTrue(sellerRows.stream().allMatch(r -> r.side() == OffersHubModel.Side.INCOMING));
    }

    @Test
    void botTargetsNeverProduceRows(@TempDir Path dir) {
        NegotiationStore store = storeIn(dir);
        UUID bot = UUID.randomUUID();
        UUID viewer = UUID.randomUUID();
        store.makeOffer(NegotiationStore.Kind.ORDER, 4, viewer, 500);

        List<OffersHubModel.Row> rows = OffersHubModel.build(
                List.of(request(4, bot, 600, false)), store, viewer, id -> "bot");

        assertTrue(rows.isEmpty());
        assertEquals(0, OffersHubModel.outgoingCount(rows));
    }

    @Test
    void anOfferOnAVanishedTargetIsSkipped(@TempDir Path dir) {
        NegotiationStore store = storeIn(dir);
        UUID viewer = UUID.randomUUID();
        store.makeOffer(NegotiationStore.Kind.AH, 9, viewer, 700);

        // The listing is gone from the live snapshot; the store row is a leftover.
        List<OffersHubModel.Row> rows = OffersHubModel.build(List.of(), store, viewer, id -> "seller");

        assertTrue(rows.isEmpty());
    }

    @Test
    void aTargetOwnedBySomeoneElseIsNeverAnIncomingRow(@TempDir Path dir) {
        NegotiationStore store = storeIn(dir);
        UUID viewer = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        store.makeOffer(NegotiationStore.Kind.AH, 3, other, 800);

        List<OffersHubModel.Row> rows = OffersHubModel.build(List.of(listing(3, other, 900, true)),
                store, viewer, id -> "someone");

        assertTrue(rows.stream().noneMatch(r -> r.side() == OffersHubModel.Side.INCOMING));
    }
}
