package com.reazip.economycraft;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The persisted shape of the offline inbox. Two things must not drift: entries written before notices
 * carried an action still load, and an offline player is never handed a wall of listing notices.
 */
class NotificationQueueTest {

    private static NotificationManager.Notification listingNotice(int id) {
        return new NotificationManager.Notification("listing " + id, "/ah search Diamond", id);
    }

    @Test
    @DisplayName("legacy plain-string entries still load as text-only notices")
    void decodesLegacyStrings() {
        UUID player = UUID.randomUUID();
        String json = "{\"" + player + "\":[\"old message\",\"another\"]}";

        Map<UUID, List<NotificationManager.Notification>> decoded = NotificationManager.decode(json);

        assertEquals(List.of(new NotificationManager.Notification("old message"),
                new NotificationManager.Notification("another")), decoded.get(player));
    }

    @Test
    @DisplayName("object entries round-trip text, command and listing id")
    void roundTripsActionableNotices() {
        UUID player = UUID.randomUUID();
        Map<UUID, List<NotificationManager.Notification>> pending = Map.of(player, List.of(
                new NotificationManager.Notification("plain"),
                new NotificationManager.Notification("listed", "/ah search Diamond", 7)));

        Map<UUID, List<NotificationManager.Notification>> decoded =
                NotificationManager.decode(NotificationManager.encode(pending));

        assertEquals(pending.get(player), decoded.get(player));
        assertNull(decoded.get(player).get(0).command());
        assertEquals(7, decoded.get(player).get(1).listingId());
    }

    @Test
    @DisplayName("text-only notices stay bare strings in the file")
    void textOnlyStaysPrimitive() {
        UUID player = UUID.randomUUID();
        String json = NotificationManager.encode(Map.of(player,
                List.of(new NotificationManager.Notification("plain"))));

        assertTrue(json.contains("\"plain\""), json);
        assertTrue(!json.contains("\"text\""), json);
    }

    @Test
    @DisplayName("pruning keeps the newest five listing notices and every other message")
    void pruningKeepsNewestListings() {
        List<NotificationManager.Notification> queued = new ArrayList<>();
        queued.add(new NotificationManager.Notification("contract message"));
        for (int id = 1; id <= 8; id++) queued.add(listingNotice(id));

        NotificationManager.pruneListingNotices(queued);

        assertEquals(1 + NotificationManager.MAX_LISTING_NOTICES, queued.size());
        assertEquals("contract message", queued.get(0).text());
        assertEquals(4, queued.get(1).listingId());
        assertEquals(8, queued.get(queued.size() - 1).listingId());
    }

    @Test
    @DisplayName("pruning an inbox under the cap changes nothing")
    void pruningUnderCapIsANoOp() {
        List<NotificationManager.Notification> queued = new ArrayList<>(List.of(listingNotice(1), listingNotice(2)));

        NotificationManager.pruneListingNotices(queued);

        assertEquals(2, queued.size());
    }

    @Test
    @DisplayName("a listing notice whose listing is gone is dropped; plain and live notices survive")
    void dropsStaleListingNotices() {
        assertTrue(NotificationManager.isStale(listingNotice(4), id -> false));
        assertFalse(NotificationManager.isStale(listingNotice(4), id -> true));
        assertFalse(NotificationManager.isStale(new NotificationManager.Notification("plain"), id -> false));
    }
}
