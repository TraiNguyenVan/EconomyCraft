package com.reazip.economycraft.market;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.reazip.economycraft.EconomyManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The route's argument validation, tested as a pure function. This is the whole reason the parsing
 * lives outside the listener: every refusal a caller can be handed about a body is decided and
 * asserted here, with no server and no registry in reach.
 *
 * <p>The reason strings are pinned verbatim because the service and the client both document them.
 */
class MarketCommandArgsTest {

    /** A body whose item is the shape {@code you.inventory[].stack} emits. */
    private static JsonObject listItemBody() {
        return JsonParser.parseString("""
                { "cmd": "listItem", "slot": 12, "count": 8, "price": 250,
                  "item": { "id": "minecraft:diamond", "count": 8 }, "description": "spare" }
                """).getAsJsonObject();
    }

    private static String refusal(MarketCommandArgs.Parsed<?> parsed) {
        assertFalse(parsed.ok(), "expected a refusal, got " + parsed.value());
        assertNotNull(parsed.error());
        assertNull(parsed.value(), "a refusal must not also carry a value");
        return parsed.error();
    }

    private static void set(JsonObject body, String field, String rawJson) {
        body.add(field, JsonParser.parseString(rawJson));
    }

    // === claimDelivery: stable delivery IDs keep retries on the same item ===

    @Test
    @DisplayName("a missing or non-string delivery ID is refused")
    void claimDeliveryWithoutAnId() {
        assertEquals("claimDelivery requires a deliveryId UUID",
                refusal(MarketCommandArgs.claimDelivery(new JsonObject())));
        JsonObject body = new JsonObject();
        set(body, "index", "{\"nope\":1}");
        assertEquals("claimDelivery requires a deliveryId UUID", refusal(MarketCommandArgs.claimDelivery(body)));
    }

    @Test
    @DisplayName("a malformed UUID is refused and a valid delivery ID is parsed")
    void claimDeliveryIdMustBeUuid() {
        JsonObject malformed = new JsonObject();
        set(malformed, "deliveryId", "\"not-a-uuid\"");
        assertEquals("deliveryId must be a UUID", refusal(MarketCommandArgs.claimDelivery(malformed)));

        JsonObject valid = new JsonObject();
        set(valid, "deliveryId", "\"00000000-0000-4000-8000-000000000001\"");
        MarketCommandArgs.Parsed<MarketCommandArgs.ClaimDelivery> parsed = MarketCommandArgs.claimDelivery(valid);
        assertTrue(parsed.ok());
        assertEquals(java.util.UUID.fromString("00000000-0000-4000-8000-000000000001"),
                parsed.value().deliveryId());
    }

    // === listItem: the item fingerprint ===

    @Test
    @DisplayName("an item that is missing, null or not an object is refused")
    void listItemNeedsAnEncodedItem() {
        JsonObject missing = listItemBody();
        missing.remove("item");
        assertEquals("listItem requires an encoded item", refusal(MarketCommandArgs.listItem(missing, slot -> 8)));

        JsonObject notAnObject = listItemBody();
        set(notAnObject, "item", "\"minecraft:diamond\"");
        assertEquals("listItem requires an encoded item",
                refusal(MarketCommandArgs.listItem(notAnObject, slot -> 8)));

        JsonObject nulled = listItemBody();
        set(nulled, "item", "null");
        assertEquals("listItem requires an encoded item", refusal(MarketCommandArgs.listItem(nulled, slot -> 8)));
    }

    // === listItem: the slot ===

    @Test
    @DisplayName("slots 0 and 35 are the inventory; 36 and beyond are not addressable")
    void listItemSlotRangeIsTheMainInventoryOnly() {
        assertEquals(36, MarketCommandArgs.MAIN_INVENTORY_SLOTS,
                "the bound is the picker UI's own 36 main slots");

        JsonObject thirtyFive = listItemBody();
        set(thirtyFive, "slot", "35");
        assertTrue(MarketCommandArgs.listItem(thirtyFive, slot -> 8).ok(), "slot 35 is a real inventory slot");

        JsonObject thirtySix = listItemBody();
        set(thirtySix, "slot", "36");
        assertEquals("slot must be between 0 and 35",
                refusal(MarketCommandArgs.listItem(thirtySix, slot -> 8)),
                "slot 36 is armour online and nothing offline");

        JsonObject negative = listItemBody();
        set(negative, "slot", "-1");
        assertEquals("slot must be between 0 and 35", refusal(MarketCommandArgs.listItem(negative, slot -> 8)));
    }

    @Test
    @DisplayName("a slot that is absent, fractional or not a number is refused")
    void listItemSlotMustBeAWholeNumber() {
        JsonObject missing = listItemBody();
        missing.remove("slot");
        assertEquals("listItem requires an integer slot", refusal(MarketCommandArgs.listItem(missing, slot -> 8)));

        JsonObject fractional = listItemBody();
        set(fractional, "slot", "1.5");
        assertEquals("slot must be an integer", refusal(MarketCommandArgs.listItem(fractional, slot -> 8)));

        JsonObject text = listItemBody();
        set(text, "slot", "\"twelve\"");
        assertEquals("slot must be an integer", refusal(MarketCommandArgs.listItem(text, slot -> 8)));
    }

    // === listItem: the count, and the cap the caller supplies ===

    @Test
    @DisplayName("the count is capped by what the slot actually holds")
    void listItemCountIsCappedByTheLiveStack() {
        JsonObject body = listItemBody();
        set(body, "count", "9");
        assertEquals("you only have 8 in that slot", refusal(MarketCommandArgs.listItem(body, slot -> 8)),
                "listing more than you hold is refused, with the number you do hold");

        set(body, "count", "8");
        assertTrue(MarketCommandArgs.listItem(body, slot -> 8).ok(),
                "listing exactly what you hold is legitimate");

        set(body, "count", "1");
        assertTrue(MarketCommandArgs.listItem(body, slot -> 8).ok(),
                "listing fewer than you hold is legitimate");

        // The cap follows the slot the caller named, not a constant.
        set(body, "count", "8");
        assertEquals("you only have 3 in that slot", refusal(MarketCommandArgs.listItem(body, slot -> 3)));
    }

    @Test
    @DisplayName("a count of zero or less is refused before the cap is even consulted")
    void listItemCountMustBePositive() {
        JsonObject zero = listItemBody();
        set(zero, "count", "0");
        assertEquals("count must be at least 1", refusal(MarketCommandArgs.listItem(zero, slot -> 8)));

        JsonObject negative = listItemBody();
        set(negative, "count", "-4");
        assertEquals("count must be at least 1", refusal(MarketCommandArgs.listItem(negative, slot -> 8)));

        JsonObject missing = listItemBody();
        missing.remove("count");
        assertEquals("listItem requires an integer count", refusal(MarketCommandArgs.listItem(missing, slot -> 8)));

        JsonObject fractional = listItemBody();
        set(fractional, "count", "2.5");
        assertEquals("count must be an integer", refusal(MarketCommandArgs.listItem(fractional, slot -> 8)));
    }

    // === listItem: the price ===

    @Test
    @DisplayName("the price is between 1 and the same ceiling /ah sell enforces")
    void listItemPriceBounds() {
        JsonObject zero = listItemBody();
        set(zero, "price", "0");
        assertEquals("price must be at least 1", refusal(MarketCommandArgs.listItem(zero, slot -> 8)));

        JsonObject negative = listItemBody();
        set(negative, "price", "-250");
        assertEquals("price must be at least 1", refusal(MarketCommandArgs.listItem(negative, slot -> 8)));

        JsonObject tooLarge = listItemBody();
        set(tooLarge, "price", Long.toString(EconomyManager.MAX + 1));
        assertEquals("price must be at most " + EconomyManager.MAX,
                refusal(MarketCommandArgs.listItem(tooLarge, slot -> 8)));

        JsonObject atTheCeiling = listItemBody();
        set(atTheCeiling, "price", Long.toString(EconomyManager.MAX));
        assertTrue(MarketCommandArgs.listItem(atTheCeiling, slot -> 8).ok());

        JsonObject missing = listItemBody();
        missing.remove("price");
        assertEquals("listItem requires an integer price", refusal(MarketCommandArgs.listItem(missing, slot -> 8)));

        JsonObject fractional = listItemBody();
        set(fractional, "price", "250.5");
        assertEquals("price must be an integer", refusal(MarketCommandArgs.listItem(fractional, slot -> 8)));
    }

    // === listItem: the description ===

    @Test
    @DisplayName("the description is sanitised by the shared rule, not by a copy of it")
    void listItemDescriptionIsSanitised() {
        JsonObject blank = listItemBody();
        set(blank, "description", "\"   \\n  \"");
        MarketCommandArgs.Parsed<MarketCommandArgs.ListItem> parsed = MarketCommandArgs.listItem(blank, slot -> 8);
        assertTrue(parsed.ok());
        assertNull(parsed.value().description(), "a blank description is no description");

        JsonObject absent = listItemBody();
        absent.remove("description");
        assertTrue(MarketCommandArgs.listItem(absent, slot -> 8).ok());
        assertNull(MarketCommandArgs.listItem(absent, slot -> 8).value().description());

        JsonObject lineBreaks = listItemBody();
        set(lineBreaks, "description", "\"  line one\\nline two  \"");
        assertEquals("line oneline two",
                MarketCommandArgs.listItem(lineBreaks, slot -> 8).value().description());

        JsonObject tooLong = listItemBody();
        set(tooLong, "description", "\"" + "x".repeat(140) + "\"");
        assertEquals(100, MarketCommandArgs.listItem(tooLong, slot -> 8).value().description().length());

        JsonObject notText = listItemBody();
        set(notText, "description", "12");
        assertEquals("description must be text", refusal(MarketCommandArgs.listItem(notText, slot -> 8)));
    }

    @Test
    @DisplayName("a well-formed body parses to exactly the arguments it named")
    void listItemHappyPath() {
        MarketCommandArgs.Parsed<MarketCommandArgs.ListItem> parsed =
                MarketCommandArgs.listItem(listItemBody(), slot -> 8);

        assertTrue(parsed.ok());
        MarketCommandArgs.ListItem args = parsed.value();
        assertEquals(12, args.slot());
        assertEquals(8, args.count());
        assertEquals(250L, args.price());
        assertEquals("spare", args.description());
        assertEquals("minecraft:diamond", args.item().get("id").getAsString());
        assertEquals(8, args.item().get("count").getAsInt());
    }

    // === buyListing / cancelListing ===

    @Test
    @DisplayName("the listing id is a positive whole number, and the reason names the command")
    void listingIdMustBeAPositiveInteger() {
        JsonObject missing = JsonParser.parseString("{\"cmd\":\"buyListing\"}").getAsJsonObject();
        assertEquals("buyListing requires an integer id",
                refusal(MarketCommandArgs.listingId(missing, "buyListing")));

        JsonObject notANumber = JsonParser.parseString("{\"id\":\"293\"}").getAsJsonObject();
        assertEquals("id must be an integer", refusal(MarketCommandArgs.listingId(notANumber, "buyListing")));

        JsonObject fractional = JsonParser.parseString("{\"id\":293.5}").getAsJsonObject();
        assertEquals("id must be an integer", refusal(MarketCommandArgs.listingId(fractional, "buyListing")));

        JsonObject zero = JsonParser.parseString("{\"id\":0}").getAsJsonObject();
        assertEquals("id must be at least 1", refusal(MarketCommandArgs.listingId(zero, "buyListing")));

        JsonObject negative = JsonParser.parseString("{\"id\":-7}").getAsJsonObject();
        assertEquals("id must be at least 1", refusal(MarketCommandArgs.listingId(negative, "buyListing")));

        JsonObject good = JsonParser.parseString("{\"id\":293}").getAsJsonObject();
        MarketCommandArgs.Parsed<Integer> parsed = MarketCommandArgs.listingId(good, "buyListing");
        assertTrue(parsed.ok());
        assertEquals(293, parsed.value());

        // The same parser, so the two commands refuse identically but say which one asked.
        assertEquals("cancelListing requires an integer id",
                refusal(MarketCommandArgs.listingId(new JsonObject(), "cancelListing")));
    }
}
