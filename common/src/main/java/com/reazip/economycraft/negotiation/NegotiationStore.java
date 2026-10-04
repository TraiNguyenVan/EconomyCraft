package com.reazip.economycraft.negotiation;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.reazip.economycraft.util.AsyncFileWriter;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Non-binding price offers on auction listings and order requests.
 *
 * <p>One active offer per player per target: a new offer from the same player replaces the old
 * one, so a seller can never be spammed with a stack of offers from one buyer. An offer lives
 * exactly as long as its target — when the listing or request is sold, cancelled, fulfilled or
 * expired, {@link #removeForTarget} drops every offer on it and the caller notifies the
 * proposers.
 *
 * <p>Deliberately free of Minecraft types (targets are referenced by id only) so the upsert,
 * sort and persistence rules are unit-testable. Quest bot entries are excluded by the caller
 * via {@link NegotiationEvents#canNegotiateAuction} /
 * {@link NegotiationEvents#canNegotiateOrder}: bot prices are mint policy, and the bot never
 * reads offers.
 */
public final class NegotiationStore {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();

    public enum Kind { AH, ORDER }

    public record Offer(Kind kind, int targetId, UUID proposer, long price, long createdAt) {}

    private final Path file;
    private final Map<String, Offer> offers = new ConcurrentHashMap<>();

    public NegotiationStore(Path file) {
        this.file = file;
        load();
    }

    private static String key(Kind kind, int targetId, UUID proposer) {
        return kind.name() + ":" + targetId + ":" + proposer;
    }

    private static String keyPrefix(Kind kind, int targetId) {
        return kind.name() + ":" + targetId + ":";
    }

    /** Places an offer, replacing any earlier offer from the same player on the same target. */
    public Offer makeOffer(Kind kind, int targetId, UUID proposer, long price) {
        Offer offer = new Offer(kind, targetId, proposer, price, System.currentTimeMillis());
        offers.put(key(kind, targetId, proposer), offer);
        save();
        return offer;
    }

    /** Offers on a target, highest price first — the order a seller reviews them in. */
    public List<Offer> offersFor(Kind kind, int targetId) {
        String prefix = keyPrefix(kind, targetId);
        List<Offer> out = new ArrayList<>();
        for (var entry : offers.entrySet()) {
            if (entry.getKey().startsWith(prefix)) out.add(entry.getValue());
        }
        out.sort(Comparator.comparingLong(Offer::price).reversed()
                .thenComparingLong(Offer::createdAt));
        return out;
    }

    public int countFor(Kind kind, int targetId) {
        String prefix = keyPrefix(kind, targetId);
        int count = 0;
        for (String k : offers.keySet()) {
            if (k.startsWith(prefix)) count++;
        }
        return count;
    }

    public Offer offerFrom(Kind kind, int targetId, UUID proposer) {
        return offers.get(key(kind, targetId, proposer));
    }

    /** Withdraws one player's offer; returns the removed row, or null. */
    public Offer removeOffer(Kind kind, int targetId, UUID proposer) {
        Offer removed = offers.remove(key(kind, targetId, proposer));
        if (removed != null) save();
        return removed;
    }

    /**
     * Drops every offer on a target that no longer exists. The returned rows are for the caller
     * to notify — the store never sends messages itself.
     */
    public List<Offer> removeForTarget(Kind kind, int targetId) {
        String prefix = keyPrefix(kind, targetId);
        List<Offer> removed = new ArrayList<>();
        for (var it = offers.entrySet().iterator(); it.hasNext();) {
            var entry = it.next();
            if (entry.getKey().startsWith(prefix)) {
                removed.add(entry.getValue());
                it.remove();
            }
        }
        if (!removed.isEmpty()) save();
        return removed;
    }

    public int size() {
        return offers.size();
    }

    public void save() {
        JsonArray arr = new JsonArray();
        for (Offer offer : offers.values()) {
            JsonObject obj = new JsonObject();
            obj.addProperty("kind", offer.kind().name());
            obj.addProperty("target", offer.targetId());
            obj.addProperty("proposer", offer.proposer().toString());
            obj.addProperty("price", offer.price());
            obj.addProperty("createdAt", offer.createdAt());
            arr.add(obj);
        }
        AsyncFileWriter.writeAsync(file, GSON.toJson(arr));
    }

    private void load() {
        if (!Files.exists(file)) return;
        try {
            String json = Files.readString(file);
            JsonArray arr = GSON.fromJson(json, JsonArray.class);
            if (arr == null) return;
            for (var el : arr) {
                try {
                    JsonObject obj = el.getAsJsonObject();
                    Kind kind = Kind.valueOf(obj.get("kind").getAsString());
                    int targetId = obj.get("target").getAsInt();
                    UUID proposer = UUID.fromString(obj.get("proposer").getAsString());
                    long price = obj.get("price").getAsLong();
                    if (price < 1) continue;
                    long createdAt = obj.has("createdAt") ? obj.get("createdAt").getAsLong()
                            : System.currentTimeMillis();
                    offers.put(key(kind, targetId, proposer),
                            new Offer(kind, targetId, proposer, price, createdAt));
                } catch (Exception ex) {
                    LOGGER.error("[EconomyCraft] Dropping an unreadable price offer in {}", file, ex);
                }
            }
        } catch (Exception ex) {
            LOGGER.error("[EconomyCraft] Failed to load {}", file, ex);
        }
    }
}
