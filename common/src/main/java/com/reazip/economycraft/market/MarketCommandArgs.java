package com.reazip.economycraft.market;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.reazip.economycraft.EconomyManager;
import com.reazip.economycraft.util.ItemPickerUi;
import com.reazip.economycraft.util.ListingDescriptions;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.function.IntUnaryOperator;

/**
 * The arguments of one {@code POST /economycraft/v1/command} body, parsed and validated with no
 * Minecraft type in reach — the same deliberate split as {@link MarketSnapshotModel}, and for the
 * same reason: it is what makes the route's validation testable at all. The listener keeps only the
 * work that genuinely needs the server, and every reason the route can return about an argument is
 * decided here.
 *
 * <p>Each parser is total: absent, malformed and out-of-range arguments all come back as a refusal
 * carrying the reason, so a caller never has to reason about a partially-filled argument set. The
 * fields that can only be judged against live state are not here — a slot's contents and a
 * listing's existence belong to the server thread — but the count's bound is, because the parser is
 * told what the slot holds rather than looking it up.
 */
public final class MarketCommandArgs {
    private MarketCommandArgs() {}

    /**
     * Slots 0–35: the whole of what an offline player's inventory can name, and the part of the live
     * container that means the same thing. The online container is 41 slots — 36–39 are armour and 40
     * is the offhand — so a slot-addressed command that accepted 36 or more would escrow a player's
     * chestplate offline and mean something else entirely online. Reusing the picker UI's own constant
     * keeps the bound and the in-game sell affordance the same number.
     */
    public static final int MAIN_INVENTORY_SLOTS = ItemPickerUi.MAIN_INVENTORY_SLOTS;

    /**
     * Either the parsed arguments or the reason they were refused — never both, never neither.
     * {@code value} is non-null exactly when {@code error} is null.
     */
    public record Parsed<T>(@Nullable T value, @Nullable String error) {
        public boolean ok() {
            return error == null;
        }
    }

    /** {@code claimDelivery}'s arguments: the stable id of the caller's own delivery. */
    public record ClaimDelivery(UUID deliveryId) {}

    /**
     * {@code listItem}'s arguments.
     *
     * @param item        the encoded stack exactly as {@code you.inventory[].stack} emits it. Kept as
     *                    a JSON object here and parsed with {@code ItemStack.CODEC} by the listener,
     *                    because only the server thread holds the registries that resolves.
     * @param description already sanitised, or null when the seller wrote none
     */
    public record ListItem(int slot, int count, long price, JsonObject item, @Nullable String description) {}

    /** The stable {@code deliveryId} of {@code claimDelivery}. */
    public static Parsed<ClaimDelivery> claimDelivery(JsonObject body) {
        JsonElement requested = body.get("deliveryId");
        if (requested == null || !requested.isJsonPrimitive() || !requested.getAsJsonPrimitive().isString()) {
            return refused("claimDelivery requires a deliveryId UUID");
        }
        try {
            return ok(new ClaimDelivery(UUID.fromString(requested.getAsString())));
        } catch (IllegalArgumentException malformed) {
            return refused("deliveryId must be a UUID");
        }
    }

    /**
     * {@code listItem}: which slot to take from, how many, and for how much.
     *
     * @param heldInSlot the number of items the slot holds right now, supplied by the caller because
     *                   this class cannot know it. Listing fewer than you hold is legitimate; listing
     *                   more is not, and that is the only cap a count has.
     */
    public static Parsed<ListItem> listItem(JsonObject body, IntUnaryOperator heldInSlot) {
        JsonElement item = body.get("item");
        if (item == null || !item.isJsonObject()) return refused("listItem requires an encoded item");

        JsonElement requestedSlot = body.get("slot");
        if (requestedSlot == null || !requestedSlot.isJsonPrimitive()) {
            return refused("listItem requires an integer slot");
        }
        Integer slot = wholeInt(requestedSlot);
        if (slot == null) return refused("slot must be an integer");
        if (slot < 0 || slot >= MAIN_INVENTORY_SLOTS) {
            return refused("slot must be between 0 and " + (MAIN_INVENTORY_SLOTS - 1));
        }

        JsonElement requestedCount = body.get("count");
        if (requestedCount == null || !requestedCount.isJsonPrimitive()) {
            return refused("listItem requires an integer count");
        }
        Integer count = wholeInt(requestedCount);
        if (count == null) return refused("count must be an integer");
        if (count < 1) return refused("count must be at least 1");
        int held = heldInSlot.applyAsInt(slot);
        if (count > held) return refused("you only have " + Math.max(held, 0) + " in that slot");

        JsonElement requestedPrice = body.get("price");
        if (requestedPrice == null || !requestedPrice.isJsonPrimitive()) {
            return refused("listItem requires an integer price");
        }
        Long price = wholeLong(requestedPrice);
        if (price == null) return refused("price must be an integer");
        if (price < 1) return refused("price must be at least 1");
        // The same ceiling /ah sell enforces (LongArgumentType.longArg(1, EconomyManager.MAX)), so a
        // price the game would refuse cannot arrive by the web route either.
        if (price > EconomyManager.MAX) return refused("price must be at most " + EconomyManager.MAX);

        String description = null;
        JsonElement requestedDescription = body.get("description");
        if (requestedDescription != null && !requestedDescription.isJsonNull()) {
            if (!requestedDescription.isJsonPrimitive() || !requestedDescription.getAsJsonPrimitive().isString()) {
                return refused("description must be text");
            }
            // The one sanitiser, rather than the inline copy /ah sell carries.
            description = ListingDescriptions.sanitize(requestedDescription.getAsString());
        }

        return ok(new ListItem(slot, count, price, item.getAsJsonObject(), description));
    }

    /**
     * The {@code id} of {@code buyListing} / {@code cancelListing}: the listing to act on. Named by
     * the caller so the refusal says which command wanted it.
     */
    public static Parsed<Integer> listingId(JsonObject body, String cmd) {
        JsonElement requested = body.get("id");
        if (requested == null || !requested.isJsonPrimitive()) {
            return refused(cmd + " requires an integer id");
        }
        Integer id = wholeInt(requested);
        if (id == null) return refused("id must be an integer");
        if (id < 1) return refused("id must be at least 1");
        return ok(id);
    }

    /** The element as a JSON number, or null when it is absent, not primitive, or not a number. */
    @Nullable
    private static JsonPrimitive number(@Nullable JsonElement element) {
        if (element == null || !element.isJsonPrimitive()) return null;
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        return primitive.isNumber() ? primitive : null;
    }

    /**
     * The value as a whole number, or null when it is not one. An integral value written as a JSON
     * float is accepted; {@code 12.5} and a value past {@code int} are not, because both would mean
     * something the caller did not intend.
     */
    @Nullable
    private static Integer wholeInt(@Nullable JsonElement element) {
        JsonPrimitive primitive = number(element);
        if (primitive == null) return null;
        try {
            return primitive.getAsBigDecimal().intValueExact();
        } catch (NumberFormatException | ArithmeticException notWhole) {
            return null;
        }
    }

    /** {@link #wholeInt(JsonElement)} for a value that may exceed an {@code int}, such as a price. */
    @Nullable
    private static Long wholeLong(@Nullable JsonElement element) {
        JsonPrimitive primitive = number(element);
        if (primitive == null) return null;
        try {
            return primitive.getAsBigDecimal().longValueExact();
        } catch (NumberFormatException | ArithmeticException notWhole) {
            return null;
        }
    }

    private static <T> Parsed<T> ok(T value) {
        return new Parsed<>(value, null);
    }

    private static <T> Parsed<T> refused(String error) {
        return new Parsed<>(null, error);
    }
}
