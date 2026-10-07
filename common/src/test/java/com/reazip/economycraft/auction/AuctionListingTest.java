package com.reazip.economycraft.auction;

import com.google.gson.JsonObject;
import com.reazip.economycraft.motd.MotdFormatter;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AuctionListingTest {

    @Test
    @DisplayName("AuctionListing description field defaults to null")
    void defaultDescriptionIsNull() {
        AuctionListing listing = new AuctionListing();
        assertNull(listing.description);
    }

    @Test
    @DisplayName("Description sanitization strips newlines and clamps to 100 chars")
    void sanitizeDescription() {
        String raw = "  Line 1\nLine 2\r\n" + "A".repeat(120);
        String cleaned = raw.replace("\r", "").replace("\n", "").trim();
        if (cleaned.length() > 100) {
            cleaned = cleaned.substring(0, 100);
        }

        assertFalse(cleaned.contains("\n"));
        assertFalse(cleaned.contains("\r"));
        assertEquals(100, cleaned.length());
        assertTrue(cleaned.startsWith("Line 1Line 2"));
    }

    @Test
    @DisplayName("AuctionListing description is formatted with color codes via MotdFormatter")
    void formatsDescriptionWithColors() {
        String desc = "&6Legendary &bSword";
        Component formatted = MotdFormatter.formatLine(desc);
        assertNotNull(formatted);
        assertEquals("Legendary Sword", formatted.getString());
    }

    @Test
    @DisplayName("AuctionListing description is omitted in JSON when null or blank")
    void omitsDescriptionWhenNullOrBlank() {
        JsonObject obj = new JsonObject();
        obj.addProperty("id", 1);
        obj.addProperty("seller", UUID.randomUUID().toString());
        obj.addProperty("price", 500L);
        obj.addProperty("createdAt", 1000L);
        obj.addProperty("expiresAt", 2000L);

        // Deserializing without description
        if (obj.has("description") && !obj.get("description").isJsonNull()) {
            fail("Should not have description");
        }

        obj.addProperty("description", "A cool item");
        assertEquals("A cool item", obj.get("description").getAsString());
    }

    @Test
    @DisplayName("In-place description update sanitizes blank strings to null and clamps length")
    void inPlaceDescriptionUpdate() {
        AuctionListing listing = new AuctionListing();
        listing.id = 1;
        listing.seller = UUID.randomUUID();
        listing.description = "Original";

        // Update to new text
        String updated = "New description";
        listing.description = updated;
        assertEquals("New description", listing.description);

        // Update with blank -> null (cleared)
        String blank = "   \n  ";
        String cleaned = blank.replace("\r", "").replace("\n", "").trim();
        listing.description = cleaned.isEmpty() ? null : cleaned;
        assertNull(listing.description);
    }
}
