package com.reazip.economycraft.market;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rules that decide what the snapshot does and does not say: quest-board rows are tagged rather
 * than dropped, and the private block exists for exactly one player, only when that player is
 * already published, and only with ids that exist in the public rows.
 *
 * <p>The bot uuid is written out here rather than imported from {@code QuestManager}, which is the
 * point of keeping this model free of Minecraft types — the same split {@code OffersHubModel} uses.
 */
class MarketSnapshotModelTest {

    private static final UUID BOT = new UUID(0L, 0L);
    private static final UUID ALICE = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID BOB = UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static final UUID STRANGER = UUID.fromString("00000000-0000-4000-8000-000000000009");

    private static JsonObject stack(String id) {
        return stack(id, 1);
    }

    private static JsonObject stack(String id, int count) {
        JsonObject json = new JsonObject();
        json.addProperty("id", id);
        json.addProperty("count", count);
        return json;
    }

    /** A container row: the encoded stack the caller supplies, and the slot it was read from. */
    private static MarketSnapshotModel.Slot slot(int index, String id, int count) {
        return new MarketSnapshotModel.Slot(index, stack(id, count));
    }

    private static MarketSnapshotModel.Auction auction(int id, UUID seller, long price) {
        return new MarketSnapshotModel.Auction(id, seller, "name-" + seller, price, 10L, 20L,
                "note " + id, false, stack("minecraft:stone"));
    }

    private static MarketSnapshotModel.Order order(int id, UUID requester, long price, int amount) {
        // unitPrice is the caller's value (OrderManager.rewardPerItem); restating the arithmetic
        // here keeps the test off Minecraft types, which is the whole point of the split.
        return new MarketSnapshotModel.Order(id, requester, "name-" + requester, price, amount,
                price, Math.round((double) price / amount), 10L, 20L, "note " + id, false,
                stack("minecraft:dirt"));
    }

    /** Fails the test if the private read happens at all — that is the property under test. */
    private static MarketSnapshotModel.PrivateRows refusing() {
        return player -> {
            throw new AssertionError("private rows were read for " + player);
        };
    }

    private static MarketSnapshotModel.PrivateRows owned(long balance, int deliveries) {
        return owned(balance, deliveries, List.of(), List.of());
    }

    private static MarketSnapshotModel.PrivateRows owned(long balance, int deliveries,
                                                         List<MarketSnapshotModel.Slot> inventory,
                                                         List<MarketSnapshotModel.Slot> enderChest) {
        return player -> {
            List<MarketSnapshotModel.Delivery> stacks = new ArrayList<>();
            for (int i = 0; i < deliveries; i++) {
                stacks.add(new MarketSnapshotModel.Delivery(stack("minecraft:apple")));
            }
            return new MarketSnapshotModel.PrivateState(balance, stacks, inventory, enderChest);
        };
    }

    /** The serialised {@code you} block, which is where the container contract lives. */
    private static JsonObject privateBlock(MarketSnapshotModel.PrivateRows rows) {
        return privateBlock(ALICE, rows);
    }

    private static JsonObject privateBlock(UUID scope, MarketSnapshotModel.PrivateRows rows) {
        return JsonParser.parseString(new Gson().toJson(build(scope, rows)))
                .getAsJsonObject()
                .getAsJsonObject("you");
    }

    private static List<MarketSnapshotModel.Player> roster() {
        return List.of(new MarketSnapshotModel.Player(BOB, "bob"),
                new MarketSnapshotModel.Player(ALICE, "alice"));
    }

    private static MarketSnapshotModel.Snapshot build(UUID scope, MarketSnapshotModel.PrivateRows rows) {
        return MarketSnapshotModel.build(1234L, 1.25, 0.1, BOT,
                List.of(auction(1, ALICE, 500L), auction(2, BOT, 900L)),
                List.of(order(7, ALICE, 300L, 4), order(8, BOT, 120L, 1)),
                roster(), scope, rows);
    }

    @Test
    @DisplayName("quest-board rows are tagged system, not filtered out of the market")
    void botRowsAreTaggedNotDropped() {
        MarketSnapshotModel.Snapshot snapshot = build(null, refusing());

        assertEquals(2, snapshot.auctions().size());
        assertFalse(snapshot.auctions().get(0).system());
        assertTrue(snapshot.auctions().get(1).system());
        assertEquals(2, snapshot.orders().size());
        assertFalse(snapshot.orders().get(0).system());
        assertTrue(snapshot.orders().get(1).system());
    }

    @Test
    @DisplayName("an unscoped request carries no private block at all")
    void unscopedRequestHasNoPrivateBlock() {
        assertNull(build(null, refusing()).you());
    }

    @Test
    @DisplayName("a scope that is not a published player never reaches the private read")
    void unknownScopeIsRefusedWithoutReading() {
        assertNull(build(STRANGER, refusing()).you());
    }

    @Test
    @DisplayName("the quest board's uuid is not a player and does not expose the pool")
    void botScopeIsRefused() {
        assertNull(build(BOT, refusing()).you());
    }

    @Test
    @DisplayName("a valid scope reads exactly that player's money, goods and ids")
    void validScopeReadsOnlyThatPlayer() {
        MarketSnapshotModel.You you = build(ALICE, owned(4242L, 2)).you();

        assertNotNull(you);
        assertEquals(4242L, you.balance());
        assertEquals(2, you.deliveries().size());
        assertEquals(List.of(1), you.listings());
        assertEquals(List.of(7), you.orders());
    }

    @Test
    @DisplayName("a player who owns nothing gets an empty id list, not a missing block")
    void playerWithNothingOwnedStillGetsTheirOwnEmptyLists() {
        MarketSnapshotModel.You you = build(BOB, owned(1L, 0)).you();

        assertNotNull(you);
        assertEquals(1L, you.balance());
        assertTrue(you.listings().isEmpty());
        assertTrue(you.orders().isEmpty());
    }

    @Test
    @DisplayName("inventory and ender chest rows carry the slot they were read from")
    void containerRowsNameTheirSlot() {
        JsonObject you = privateBlock(owned(20191L, 1,
                List.of(slot(0, "minecraft:diamond_hoe", 1)),
                List.of(slot(3, "minecraft:amethyst_shard", 7))));

        JsonObject held = you.getAsJsonArray("inventory").get(0).getAsJsonObject();
        assertEquals(List.of("slot", "stack"), held.keySet().stream().sorted().toList(),
                "an inventory row is a slot index and a stack, and nothing else");
        assertEquals(0, held.get("slot").getAsInt(), "the row must name the slot it came from");
        assertEquals(stack("minecraft:diamond_hoe", 1), held.get("stack"),
                "the container row's stack is the encoded stack the caller supplied, unwrapped");

        JsonObject stored = you.getAsJsonArray("enderChest").get(0).getAsJsonObject();
        assertEquals(List.of("slot", "stack"), stored.keySet().stream().sorted().toList());
        assertEquals(3, stored.get("slot").getAsInt());
        assertEquals(stack("minecraft:amethyst_shard", 7), stored.get("stack"));
    }

    @Test
    @DisplayName("the deliveries shape is unchanged beside the new container rows")
    void deliveriesShapeIsUnchanged() {
        JsonObject you = privateBlock(owned(4242L, 1, List.of(slot(9, "minecraft:stone", 4)), List.of()));

        JsonObject delivery = you.getAsJsonArray("deliveries").get(0).getAsJsonObject();
        assertEquals(List.of("stack"), delivery.keySet().stream().sorted().toList(),
                "a delivery row must still be a bare stack, with no slot added to it");
        assertEquals(stack("minecraft:apple", 1), delivery.get("stack"));
    }

    @Test
    @DisplayName("an empty container is an empty array, never null and never absent")
    void emptyContainersAreEmptyArrays() {
        JsonObject you = privateBlock(owned(4242L, 0));

        assertTrue(you.has("inventory"), "`you.inventory` must be present even when the player holds nothing");
        assertTrue(you.has("enderChest"), "`you.enderChest` must be present even when the ender chest is empty");
        assertFalse(you.get("inventory").isJsonNull(), "`you.inventory` must be an array, not null");
        assertFalse(you.get("enderChest").isJsonNull(), "`you.enderChest` must be an array, not null");
        assertEquals(0, you.getAsJsonArray("inventory").size());
        assertEquals(0, you.getAsJsonArray("enderChest").size());
    }

    @Test
    @DisplayName("a private block with no rows at all is still the documented six keys")
    void privateBlockWithoutRowsCarriesBothContainers() {
        // Bob sells nothing, orders nothing and holds nothing: every list here is empty, which is the
        // case a consumer reads before it has any reason to expect a container field to exist.
        JsonObject you = privateBlock(BOB, owned(0L, 0));

        assertEquals(List.of("balance", "deliveries", "enderChest", "inventory", "listings", "orders"),
                you.keySet().stream().sorted().toList(),
                "the private block's keys are the contract; a new or missing one is a breaking change");
        for (String list : List.of("deliveries", "listings", "orders", "inventory", "enderChest")) {
            assertTrue(you.has(list), "`you." + list + "` went missing");
            assertEquals(0, you.getAsJsonArray(list).size(), "`you." + list + "` should be empty");
        }
    }

    @Test
    @DisplayName("every id in the private block names a row this response actually carries")
    void privateIdsComeFromThePublicRows() {
        MarketSnapshotModel.Snapshot snapshot = build(ALICE, owned(4242L, 1));
        MarketSnapshotModel.You you = snapshot.you();

        assertNotNull(you);
        List<Integer> auctionIds = snapshot.auctions().stream()
                .map(MarketSnapshotModel.Auction::id).toList();
        List<Integer> orderIds = snapshot.orders().stream()
                .map(MarketSnapshotModel.Order::id).toList();
        assertTrue(auctionIds.containsAll(you.listings()));
        assertTrue(orderIds.containsAll(you.orders()));
    }

    @Test
    @DisplayName("the roster is ordered, so two snapshots of one instant do not look like a change")
    void rosterIsOrdered() {
        List<String> uuids = build(null, refusing()).players().stream()
                .map(player -> player.uuid().toString())
                .toList();

        assertEquals(uuids.stream().sorted().toList(), uuids);
    }

    @Test
    @DisplayName("the serialised shape is the documented one")
    void serialisedShapeIsTheDocumentedOne() {
        JsonObject json = JsonParser.parseString(new Gson().toJson(build(ALICE, owned(4242L, 1))))
                .getAsJsonObject();

        assertEquals(1234L, json.get("generatedAt").getAsLong());
        assertEquals(1.25, json.get("inflationMultiplier").getAsDouble(), 1e-9);
        assertEquals(0.1, json.get("taxRate").getAsDouble(), 1e-9);

        JsonObject auction = json.getAsJsonArray("auctions").get(0).getAsJsonObject();
        for (String key : List.of("id", "seller", "sellerName", "price", "createdAt", "expiresAt",
                "description", "system", "stack")) {
            assertTrue(auction.has(key), "auction row is missing " + key);
        }
        assertEquals("minecraft:stone", auction.getAsJsonObject("stack").get("id").getAsString());

        JsonObject order = json.getAsJsonArray("orders").get(0).getAsJsonObject();
        for (String key : List.of("id", "requester", "requesterName", "price", "amount", "escrow",
                "unitPrice", "createdAt", "expiresAt", "description", "system", "stack")) {
            assertTrue(order.has(key), "order row is missing " + key);
        }
        // 300 posted for 4 items, per OrderManager.rewardPerItem.
        assertEquals(75L, order.get("unitPrice").getAsLong());

        for (var element : json.getAsJsonArray("players")) {
            JsonObject player = element.getAsJsonObject();
            assertEquals(List.of("name", "uuid"),
                    player.keySet().stream().sorted().toList(),
                    "a roster entry must be a uuid/name pair and nothing else");
        }
    }

    @Test
    @DisplayName("the private block names only the requesting player's rows")
    void privateBlockNamesOnlyTheRequestingPlayersRows() {
        JsonObject json = JsonParser.parseString(new Gson().toJson(build(ALICE, owned(4242L, 1))))
                .getAsJsonObject();
        JsonObject you = json.getAsJsonObject("you");

        assertEquals(4242L, you.get("balance").getAsLong(), "`you` must carry Alice's balance");
        for (var element : you.getAsJsonArray("listings")) {
            JsonObject listing = findAuction(json, element.getAsInt());
            assertEquals(ALICE.toString(), listing.get("seller").getAsString(),
                    "a listing id in `you` belongs to somebody else");
        }
        for (var element : you.getAsJsonArray("orders")) {
            JsonObject order = findOrder(json, element.getAsInt());
            assertEquals(ALICE.toString(), order.get("requester").getAsString(),
                    "an order id in `you` belongs to somebody else");
        }
    }

    /** Bob's uuid is in the roster on purpose; what must never happen is it appearing anywhere else. */
    @Test
    @DisplayName("another player's uuid appears in the roster and nowhere else")
    void anotherPlayersUuidIsRosterOnly() {
        JsonObject json = JsonParser.parseString(new Gson().toJson(build(ALICE, owned(4242L, 1))))
                .getAsJsonObject();

        JsonObject rostered = null;
        for (var element : json.getAsJsonArray("players")) {
            JsonObject player = element.getAsJsonObject();
            if (BOB.toString().equals(player.get("uuid").getAsString())) rostered = player;
            assertEquals(List.of("name", "uuid"), player.keySet().stream().sorted().toList());
        }
        assertNotNull(rostered, "Bob's name is in the roster, so consumers can label his rows");
        assertEquals("bob", rostered.get("name").getAsString());
        assertFalse(json.getAsJsonObject("you").toString().contains(BOB.toString()),
                "Bob's uuid leaked into the private block");
    }

    private static JsonObject findAuction(JsonObject json, int id) {
        for (var element : json.getAsJsonArray("auctions")) {
            JsonObject row = element.getAsJsonObject();
            if (row.get("id").getAsInt() == id) return row;
        }
        throw new AssertionError("no auction with id " + id + " in the response");
    }

    private static JsonObject findOrder(JsonObject json, int id) {
        for (var element : json.getAsJsonArray("orders")) {
            JsonObject row = element.getAsJsonObject();
            if (row.get("id").getAsInt() == id) return row;
        }
        throw new AssertionError("no order with id " + id + " in the response");
    }
}