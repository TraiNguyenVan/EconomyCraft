package com.reazip.economycraft.negotiation;

import com.reazip.economycraft.util.AsyncFileWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the store rules the whole feature leans on: one active offer per player per target,
 * highest-first review order, target-scoped wipe, and a reload that keeps what it can read.
 */
class NegotiationStoreTest {

    // The store persists asynchronously: without this barrier the background writer can still
    // hold a file inside the @TempDir while JUnit deletes it, failing teardown nondeterministically.
    @AfterEach
    void flushWrites() {
        AsyncFileWriter.flush();
    }

    private static NegotiationStore storeIn(@TempDir Path dir) {
        return new NegotiationStore(dir.resolve("negotiations.json"));
    }

    @Test
    void secondOfferFromSamePlayerReplacesFirst(@TempDir Path dir) {
        NegotiationStore store = storeIn(dir);
        UUID proposer = UUID.randomUUID();
        store.makeOffer(NegotiationStore.Kind.AH, 7, proposer, 100);
        store.makeOffer(NegotiationStore.Kind.AH, 7, proposer, 150);

        assertEquals(1, store.countFor(NegotiationStore.Kind.AH, 7));
        assertEquals(150, store.offerFrom(NegotiationStore.Kind.AH, 7, proposer).price());
    }

    @Test
    void offersOnDifferentTargetsDoNotInterfere(@TempDir Path dir) {
        NegotiationStore store = storeIn(dir);
        UUID proposer = UUID.randomUUID();
        store.makeOffer(NegotiationStore.Kind.AH, 7, proposer, 100);
        store.makeOffer(NegotiationStore.Kind.ORDER, 7, proposer, 200);

        assertEquals(1, store.countFor(NegotiationStore.Kind.AH, 7));
        assertEquals(1, store.countFor(NegotiationStore.Kind.ORDER, 7));
    }

    @Test
    void offersSortHighestFirst(@TempDir Path dir) {
        NegotiationStore store = storeIn(dir);
        store.makeOffer(NegotiationStore.Kind.AH, 7, UUID.randomUUID(), 50);
        store.makeOffer(NegotiationStore.Kind.AH, 7, UUID.randomUUID(), 300);
        store.makeOffer(NegotiationStore.Kind.AH, 7, UUID.randomUUID(), 150);

        List<NegotiationStore.Offer> offers = store.offersFor(NegotiationStore.Kind.AH, 7);
        assertEquals(List.of(300L, 150L, 50L),
                offers.stream().map(NegotiationStore.Offer::price).toList());
    }

    @Test
    void removeForTargetWipesOnlyThatTarget(@TempDir Path dir) {
        NegotiationStore store = storeIn(dir);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        store.makeOffer(NegotiationStore.Kind.AH, 7, a, 100);
        store.makeOffer(NegotiationStore.Kind.AH, 7, b, 200);
        store.makeOffer(NegotiationStore.Kind.AH, 8, a, 50);

        List<NegotiationStore.Offer> removed = store.removeForTarget(NegotiationStore.Kind.AH, 7);
        assertEquals(2, removed.size());
        assertEquals(0, store.countFor(NegotiationStore.Kind.AH, 7));
        assertEquals(1, store.countFor(NegotiationStore.Kind.AH, 8));
    }

    @Test
    void removeOfferWithdrawsOnePlayer(@TempDir Path dir) {
        NegotiationStore store = storeIn(dir);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        store.makeOffer(NegotiationStore.Kind.AH, 7, a, 100);
        store.makeOffer(NegotiationStore.Kind.AH, 7, b, 200);

        assertNotNull(store.removeOffer(NegotiationStore.Kind.AH, 7, a));
        assertNull(store.offerFrom(NegotiationStore.Kind.AH, 7, a));
        assertEquals(1, store.countFor(NegotiationStore.Kind.AH, 7));
        assertNull(store.removeOffer(NegotiationStore.Kind.AH, 7, a));
    }

    @Test
    void offersByProposerReturnsOnlyThatPlayerNewestFirst(@TempDir Path dir) throws Exception {
        NegotiationStore store = storeIn(dir);
        UUID mine = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        store.makeOffer(NegotiationStore.Kind.AH, 7, mine, 100);
        Thread.sleep(5);
        store.makeOffer(NegotiationStore.Kind.ORDER, 3, mine, 250);
        store.makeOffer(NegotiationStore.Kind.AH, 7, other, 999);

        List<NegotiationStore.Offer> mine2 = store.offersByProposer(mine);
        assertEquals(List.of(250L, 100L),
                mine2.stream().map(NegotiationStore.Offer::price).toList());
        assertTrue(store.offersByProposer(other).stream()
                .allMatch(offer -> offer.proposer().equals(other)));
    }

    @Test
    void reloadKeepsOffers(@TempDir Path dir) {
        Path file = dir.resolve("negotiations.json");
        UUID proposer = UUID.randomUUID();
        new NegotiationStore(file).makeOffer(NegotiationStore.Kind.ORDER, 3, proposer, 999);
        AsyncFileWriter.flush();

        NegotiationStore reloaded = new NegotiationStore(file);
        assertEquals(999, reloaded.offerFrom(NegotiationStore.Kind.ORDER, 3, proposer).price());
    }

    @Test
    void reloadSkipsGarbageRows(@TempDir Path dir) throws Exception {        Path file = dir.resolve("negotiations.json");
        UUID proposer = UUID.randomUUID();
        java.nio.file.Files.writeString(file,
                "[{\"kind\":\"AH\",\"target\":1,\"proposer\":\"" + proposer
                        + "\",\"price\":10,\"createdAt\":1},"
                        + "{\"kind\":\"BOGUS\",\"target\":2},"
                        + "{\"kind\":\"AH\",\"target\":3,\"proposer\":\"not-a-uuid\",\"price\":5}]");

        NegotiationStore reloaded = new NegotiationStore(file);
        assertEquals(1, reloaded.size());
        assertTrue(reloaded.offerFrom(NegotiationStore.Kind.AH, 1, proposer).price() == 10);
    }
}
