package com.reazip.economycraft.contracts;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure model tests: sanitization, lifecycle predicates, settlement records and JSON round-trips.
 * Financial behavior lives in {@link ContractServiceTest}.
 */
class ContractModelTest {

    @Test
    @DisplayName("sanitize rejects blank and clamps overlong text")
    void sanitize() {
        assertNull(Contract.sanitize(null, 40));
        assertNull(Contract.sanitize("   ", 40));
        assertNull(Contract.sanitize("", 40));
        assertEquals("Build a wall", Contract.sanitize("  Build a wall  ", 40));
        String over = "x".repeat(100);
        assertEquals(40, Contract.sanitize(over, 40).length());
        // Control characters and newlines never survive into persisted text.
        assertFalse(Contract.sanitize("line one\nline two\ttab", 100).contains("\n"));
    }

    @Test
    @DisplayName("terminal states are final, everything else is active")
    void lifecyclePredicates() {
        assertTrue(Contract.Status.COMPLETED.terminal());
        assertTrue(Contract.Status.CANCELLED.terminal());
        assertTrue(Contract.Status.EXPIRED.terminal());
        for (Contract.Status s : new Contract.Status[]{
                Contract.Status.OPEN, Contract.Status.IN_PROGRESS,
                Contract.Status.SUBMITTED, Contract.Status.DISPUTED}) {
            assertTrue(s.active(), s + " should be active");
            assertFalse(s.terminal(), s + " should not be terminal");
        }
    }

    @Test
    @DisplayName("settlement records distinguish pending obligations from done ones")
    void settlementStates() {
        UUID target = UUID.randomUUID();
        Contract.Settlement payout = Contract.Settlement.payout(1000, target, 1);
        assertTrue(payout.pending());
        assertFalse(payout.done().pending());

        Contract.Settlement refund = Contract.Settlement.refund(500, target, 2, Contract.Status.CANCELLED);
        assertTrue(refund.pending());
        assertEquals(Contract.Status.CANCELLED, refund.outcome);
        // A re-attempt carries a higher attempt count so retries are observable in the record.
        assertEquals(2, refund.attempt);
    }

    @Test
    @DisplayName("a full contract survives a JSON round-trip, including settlement and history")
    void jsonRoundTrip() {
        Contract c = new Contract();
        c.id = 7;
        c.requester = UUID.randomUUID();
        c.target = UUID.randomUUID();
        c.contractor = UUID.randomUUID();
        c.title = "Build a bridge";
        c.description = "Across the river";
        c.category = Contract.Category.BUILD;
        c.status = Contract.Status.SUBMITTED;
        c.reward = 5_000;
        c.escrow = 5_000;
        c.createdAt = 1_700_000_000_000L;
        c.workDeadline = 1_700_060_000_000L;
        c.acceptedAt = 1_700_001_000_000L;
        c.submittedAt = 1_700_002_000_000L;
        c.submissionNotes = "Done, come look";
        c.reviewDeadline = 1_700_010_000_000L;
        c.revisions = 1;
        c.reviewNotes = "Move the left pillar";
        Contract.RevisionEntry entry = new Contract.RevisionEntry();
        entry.reason = "Move the left pillar";
        entry.at = 1_700_003_000_000L;
        entry.by = c.requester;
        c.revisionHistory.add(entry);
        c.settlement = Contract.Settlement.payout(5_000, c.contractor, 1);

        JsonObject json = c.save();
        Contract back = Contract.load(json);
        assertNotNull(back);
        assertEquals(7, back.id);
        assertEquals(c.requester, back.requester);
        assertEquals(c.target, back.target);
        assertEquals(c.contractor, back.contractor);
        assertEquals("Build a bridge", back.title);
        assertEquals("Across the river", back.description);
        assertEquals(Contract.Category.BUILD, back.category);
        assertEquals(Contract.Status.SUBMITTED, back.status);
        assertEquals(5_000, back.reward);
        assertEquals(5_000, back.escrow);
        assertEquals(1_700_002_000_000L, back.submittedAt);
        assertEquals("Done, come look", back.submissionNotes);
        assertEquals(1, back.revisions);
        assertEquals(1, back.revisionHistory.size());
        assertEquals("Move the left pillar", back.revisionHistory.get(0).reason);
        assertNotNull(back.settlement);
        assertTrue(back.settlement.pending());
        assertEquals(Contract.Settlement.Kind.PAYOUT, back.settlement.kind);
    }

    @Test
    @DisplayName("untrusted JSON is validated: bad status, negative money and garbage ids are dropped")
    void invalidJsonLoadsNull() {
        JsonObject bad = new JsonObject();
        bad.addProperty("id", 1);
        bad.addProperty("requester", "not-a-uuid");
        bad.addProperty("title", "x");
        assertNull(Contract.load(bad), "a garbage requester id must fail the load");

        JsonObject negative = new JsonObject();
        negative.addProperty("id", 2);
        negative.addProperty("requester", UUID.randomUUID().toString());
        negative.addProperty("title", "x");
        negative.addProperty("reward", -100);
        assertNull(Contract.load(negative), "a negative reward must fail the load");

        JsonObject unknown = new JsonObject();
        unknown.addProperty("id", 3);
        unknown.addProperty("requester", UUID.randomUUID().toString());
        unknown.addProperty("title", "x");
        unknown.addProperty("status", "SOLD_TO_THE_HIGHEST_BIDDER");
        assertNull(Contract.load(unknown), "an unknown status must fail the load");
    }

    @Test
    @DisplayName("category parsing falls back to OTHER instead of failing")
    void categoryParseOrOther() {
        assertEquals(Contract.Category.BUILD, Contract.Category.parseOrOther("BUILD"));
        assertEquals(Contract.Category.OTHER, Contract.Category.parseOrOther("nope"));
        assertEquals(Contract.Category.OTHER, Contract.Category.parseOrOther(null));
    }
}
