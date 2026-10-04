package com.reazip.economycraft.negotiation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The row model behind the offers hub, kept free of Minecraft types so the selection rules are
 * unit-testable.
 *
 * <p>One row per <em>target</em> for incoming offers — a seller with six offers on one listing
 * gets one row, not six, and the review screen behind it still lists them highest-first — and one
 * row per offer for outgoing ones, because an offerer is tracking several unrelated items and
 * each has to be withdrawable on its own.
 *
 * <p>Both sides in one list, incoming first: the owner side is the one that moves money, and a
 * player who is only offering should not have to page past someone else's queue to find their own
 * row.
 */
public final class OffersHubModel {
    private OffersHubModel() {}

    public enum Side { INCOMING, OUTGOING }

    /**
     * @param kind        which market the target lives on
     * @param id          the listing or request id
     * @param owner       who may accept on it
     * @param desc        human item description, already formatted for display
     * @param price       the current asking price — the sticker or the posted reward
     * @param negotiable  false for bot entries, which never read offers
     */
    public record Target(NegotiationStore.Kind kind, int id, UUID owner, String desc,
                         long price, boolean negotiable) {}

    /**
     * @param otherParty  the counterparty: the buyer on an incoming row, the seller on an outgoing one
     * @param offerPrice  the best incoming offer, or this offer's own price when outgoing
     * @param offerCount  incoming only — how many offers sit on the target
     * @param createdAt   outgoing only, for the "how fresh is this" lore
     */
    public record Row(Side side, NegotiationStore.Kind kind, int targetId, String targetDesc,
                      long targetPrice, UUID otherParty, long offerPrice, int offerCount,
                      long createdAt) {}

    /** Resolves a uuid to a display name, or null when the account cannot be resolved. */
    public interface NameResolver {
        String name(UUID playerId);
    }

    /**
     * Builds both halves of the hub.
     *
     * <p>Targets whose owner is not the viewer are ignored even if the store still holds offers on
     * them: a stale row is worse than a missing one, because the row behind it would let the
     * viewer accept on a listing that is not theirs. {@code targets} is the live snapshot the
     * caller took this tick.
     */
    public static List<Row> build(List<Target> targets, NegotiationStore store, UUID viewer,
                                 NameResolver names) {
        Map<String, Target> byKey = new HashMap<>();
        for (Target target : targets) {
            if (target.negotiable()) byKey.put(key(target.kind(), target.id()), target);
        }

        List<Row> incoming = new ArrayList<>();
        List<Row> outgoing = new ArrayList<>();

        for (Target target : byKey.values()) {
            if (!target.owner().equals(viewer)) continue;
            List<NegotiationStore.Offer> offers =
                    store.offersFor(target.kind(), target.id());
            if (offers.isEmpty()) continue;
            NegotiationStore.Offer best = offers.get(0);
            incoming.add(new Row(Side.INCOMING, target.kind(), target.id(), target.desc(),
                    target.price(), best.proposer(), best.price(), offers.size(), best.createdAt()));
        }

        for (NegotiationStore.Offer offer : store.offersByProposer(viewer)) {
            Target target = byKey.get(key(offer.kind(), offer.targetId()));
            // An offer on a target that has since been sold, cancelled or removed is dropped by
            // the caller that removed the target; a survivor here means the two views disagree,
            // so the row is skipped rather than rendered as a button that cannot work.
            if (target == null) continue;
            if (target.owner().equals(viewer)) continue;
            outgoing.add(new Row(Side.OUTGOING, target.kind(), target.id(), target.desc(),
                    target.price(), target.owner(), offer.price(), 1, offer.createdAt()));
        }

        // Incoming by best offer, so the most money on the table is the first thing seen.
        incoming.sort(Comparator.comparingLong(Row::offerPrice).reversed()
                .thenComparing(row -> row.kind().name())
                .thenComparingInt(Row::targetId));
        // Outgoing newest first: these are the rows a player is actively waiting on.
        outgoing.sort(Comparator.comparingLong(Row::createdAt).reversed());

        List<Row> rows = new ArrayList<>(incoming.size() + outgoing.size());
        rows.addAll(incoming);
        rows.addAll(outgoing);
        return rows;
    }

    /** Total offer count behind the incoming rows — the number a menu button shows. */
    public static int incomingOfferCount(List<Row> rows) {
        int count = 0;
        for (Row row : rows) {
            if (row.side() == Side.INCOMING) count += row.offerCount();
        }
        return count;
    }

    public static int outgoingCount(List<Row> rows) {
        int count = 0;
        for (Row row : rows) {
            if (row.side() == Side.OUTGOING) count++;
        }
        return count;
    }

    private static String key(NegotiationStore.Kind kind, int targetId) {
        return kind.name() + ":" + targetId;
    }
}
