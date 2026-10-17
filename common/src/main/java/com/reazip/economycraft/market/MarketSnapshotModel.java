package com.reazip.economycraft.market;

import com.google.gson.JsonElement;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * The rows behind {@code GET /economycraft/v1/market}, kept free of Minecraft types so the assembly
 * rules are unit-testable — the same split as {@code OffersHubModel}.
 *
 * <p>Only three rules live here, and they are the three that are easy to get quietly wrong:
 * <ul>
 *   <li>quest-board rows are <em>tagged</em> {@code system}, not dropped. A consumer that wants only
 *       real players filters on the flag; one that cannot filter still sees the whole board instead
 *       of a silently truncated market;</li>
 *   <li>{@code you} exists for at most one player, and only when the scope names an account this
 *       snapshot already publishes in {@code players} — so the private block can never widen itself
 *       to a uuid the caller made up;</li>
 *   <li>{@code you.listings} / {@code you.orders} are derived from the rows in this very response,
 *       so an id in the private block always exists in the public one;</li>
 *   <li>{@code you.inventory} / {@code you.enderChest} are always present — empty arrays, never null
 *       and never absent — because a consumer that has to test for the field before reading it will
 *       eventually read it wrong. They are sparse: only occupied slots appear, each naming its own
 *       container index.</li>
 * </ul>
 *
 * <p>Stacks arrive already encoded, because the item codec needs registry access that only the
 * server thread has. Encoding produces a detached tree, so nothing live is reachable from here.
 */
public final class MarketSnapshotModel {
    private MarketSnapshotModel() {}

    /**
     * @param description free text from the seller; absent when unset
     * @param system      true for the quest board's own rows, never for a player's
     */
    public record Auction(int id, UUID seller, String sellerName, long price, long createdAt,
                          long expiresAt, @Nullable String description, boolean system,
                          JsonElement stack) {
        Auction asSystem(boolean isSystem) {
            return isSystem == system ? this : new Auction(id, seller, sellerName, price, createdAt,
                    expiresAt, description, isSystem, stack);
        }
    }

    /**
     * @param price     the total posted reward for the whole {@code amount}
     * @param escrow    how much of {@code price} is held right now
     * @param unitPrice {@code price} per single item, from {@code OrderManager.rewardPerItem}
     */
    public record Order(int id, UUID requester, String requesterName, long price, int amount,
                        long escrow, long unitPrice, long createdAt, long expiresAt,
                        @Nullable String description, boolean system, JsonElement stack) {
        Order asSystem(boolean isSystem) {
            return isSystem == system ? this : new Order(id, requester, requesterName, price, amount,
                    escrow, unitPrice, createdAt, expiresAt, description, isSystem, stack);
        }
    }

    /** One entry of the uuid/name roster, and deliberately the whole of it: no balance, no role. */
    public record Player(UUID uuid, String name) {}

    public record Delivery(JsonElement stack) {}

    /**
     * One occupied container slot: the index the item sits at, and the stack in it.
     *
     * <p>The index travels with the row because a container is <em>sparse</em>: only occupied slots are
     * sent, so the position of a row in the array is not the slot it came from and a consumer cannot
     * recover it by counting. Without the index, slot 3 of an ender chest is unaddressable.
     */
    public record Slot(int slot, JsonElement stack) {}

    /** The single player's private view. Absent unless the request scoped itself to a known account. */
    public record You(long balance, List<Delivery> deliveries, List<Integer> listings, List<Integer> orders,
                      List<Slot> inventory, List<Slot> enderChest) {}

    /**
     * @param you private view for {@code scope}, or null when the request named no known player
     */
    public record Snapshot(long generatedAt, double inflationMultiplier, double taxRate,
                           List<Auction> auctions, List<Order> orders, List<Player> players,
                           @Nullable You you) {}

    /**
     * The money and goods one player owns. Supplied through a callback rather than passed in, so the
     * caller reads them only after this class has decided the scope is legitimate.
     */
    @FunctionalInterface
    public interface PrivateRows {
        PrivateState forPlayer(UUID player);
    }

    /**
     * What {@link PrivateRows} hands back; the id lists are added here, not by the caller.
     *
     * <p>{@code inventory} and {@code enderChest} arrive as {@link Slot} rows already encoded and are
     * copied through as they are — empty, but never null, so the two arrays are always in the
     * response for a scope that has a {@code you} block at all.
     */
    public record PrivateState(long balance, List<Delivery> deliveries,
                              List<Slot> inventory, List<Slot> enderChest) {}

    /**
     * Assembles one snapshot.
     *
     * @param system     the uuid the quest board owns; its rows are tagged rather than filtered
     * @param scope      the {@code ?player=} value, or null when the request was unscoped
     * @param privateRows read at most once, and only for a scope that names a published player
     */
    public static Snapshot build(long generatedAt, double inflationMultiplier, double taxRate,
                                 UUID system, List<Auction> auctions, List<Order> orders,
                                 List<Player> players, @Nullable UUID scope,
                                 PrivateRows privateRows) {
        List<Auction> taggedAuctions = new ArrayList<>(auctions.size());
        for (Auction auction : auctions) {
            taggedAuctions.add(auction.asSystem(system.equals(auction.seller())));
        }
        List<Order> taggedOrders = new ArrayList<>(orders.size());
        for (Order order : orders) {
            taggedOrders.add(order.asSystem(system.equals(order.requester())));
        }

        // The roster comes out of a hash map, so unsorted it reorders between two snapshots of the
        // same instant and every diff of them reports a change that did not happen.
        List<Player> roster = new ArrayList<>(players);
        roster.sort(Comparator.comparing(player -> player.uuid().toString()));

        return new Snapshot(generatedAt, inflationMultiplier, taxRate, taggedAuctions, taggedOrders,
                roster, you(scope, system, taggedAuctions, taggedOrders, roster, privateRows));
    }

    @Nullable
    private static You you(@Nullable UUID scope, UUID system, List<Auction> auctions, List<Order> orders,
                           List<Player> roster, PrivateRows privateRows) {
        // The quest board's uuid is not a player: scoping to it would publish the pool's balance.
        if (scope == null || scope.equals(system) || !isPublished(scope, roster)) return null;

        List<Integer> listingIds = new ArrayList<>();
        for (Auction auction : auctions) if (scope.equals(auction.seller())) listingIds.add(auction.id());
        List<Integer> orderIds = new ArrayList<>();
        for (Order order : orders) if (scope.equals(order.requester())) orderIds.add(order.id());

        PrivateState state = privateRows.forPlayer(scope);
        return new You(state.balance(), List.copyOf(state.deliveries()),
                List.copyOf(listingIds), List.copyOf(orderIds),
                List.copyOf(state.inventory()), List.copyOf(state.enderChest()));
    }

    private static boolean isPublished(UUID player, List<Player> roster) {
        for (Player known : roster) if (player.equals(known.uuid())) return true;
        return false;
    }
}