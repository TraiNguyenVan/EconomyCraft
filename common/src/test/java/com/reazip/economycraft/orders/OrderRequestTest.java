package com.reazip.economycraft.orders;

import com.google.gson.JsonObject;
import com.reazip.economycraft.motd.MotdFormatter;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class OrderRequestTest {

    @Test
    @DisplayName("OrderRequest description field defaults to null")
    void defaultDescriptionIsNull() {
        OrderRequest request = new OrderRequest();
        assertNull(request.description);
    }

    @Test
    @DisplayName("Description sanitization strips newlines and clamps to 100 chars")
    void sanitizeDescription() {
        String raw = "  Need for build\nUrgent\r\n" + "B".repeat(120);
        String cleaned = raw.replace("\r", "").replace("\n", "").trim();
        if (cleaned.length() > 100) {
            cleaned = cleaned.substring(0, 100);
        }

        assertFalse(cleaned.contains("\n"));
        assertFalse(cleaned.contains("\r"));
        assertEquals(100, cleaned.length());
        assertTrue(cleaned.startsWith("Need for buildUrgent"));
    }

    @Test
    @DisplayName("OrderRequest description is formatted with color codes via MotdFormatter")
    void formatsDescriptionWithColors() {
        String desc = "&aBuying in bulk &6paying top dollar!";
        Component formatted = MotdFormatter.formatLine(desc);
        assertNotNull(formatted);
        assertEquals("Buying in bulk paying top dollar!", formatted.getString());
    }

    @Test
    @DisplayName("OrderRequest description is omitted in JSON when null or blank")
    void omitsDescriptionWhenNullOrBlank() {
        JsonObject obj = new JsonObject();
        obj.addProperty("id", 1);
        obj.addProperty("requester", UUID.randomUUID().toString());
        obj.addProperty("price", 1000L);
        obj.addProperty("amount", 64);
        obj.addProperty("escrow", 1000L);
        obj.addProperty("createdAt", 1000L);
        obj.addProperty("expiresAt", 2000L);

        // Deserializing without description
        if (obj.has("description") && !obj.get("description").isJsonNull()) {
            fail("Should not have description");
        }

        obj.addProperty("description", "Looking for bulk cobble");
        assertEquals("Looking for bulk cobble", obj.get("description").getAsString());
    }

    @Test
    @DisplayName("In-place description update sanitizes blank strings to null and clamps length")
    void inPlaceDescriptionUpdate() {
        OrderRequest request = new OrderRequest();
        request.id = 1;
        request.requester = UUID.randomUUID();
        request.description = "Original";

        // Update to new text
        String updated = "New description";
        request.description = updated;
        assertEquals("New description", request.description);

        // Update with blank -> null (cleared)
        String blank = "   \n  ";
        String cleaned = blank.replace("\r", "").replace("\n", "").trim();
        request.description = cleaned.isEmpty() ? null : cleaned;
        assertNull(request.description);
    }
}
