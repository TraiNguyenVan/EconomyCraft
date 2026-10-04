package com.reazip.economycraft.quests;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.reazip.economycraft.util.AsyncFileWriter;
import com.reazip.economycraft.util.EconomyPaths;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Where filled quest goods accumulate.
 *
 * <p>A bot-requested fulfillment never touches the deliveries mailbox — there is nobody to claim it —
 * so the items are booked here, keyed by price key, instead. Phase 1 only books: the Phase 2 buyback
 * market will spend this ledger down. Until then it is an audit trail with a total.
 */
public class QuestStock {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();

    private final Path file;
    private final Map<String, Long> stock = new LinkedHashMap<>();

    public QuestStock(MinecraftServer server) {
        this.file = EconomyPaths.dataDir(server).resolve("stock.json");
        load();
    }

    public synchronized void deposit(String key, long count) {
        if (key == null || key.isBlank() || count <= 0) return;
        stock.merge(key, count, Long::sum);
        save();
    }

    /**
     * Takes up to {@code amount} units off the ledger for a buyback listing, returning what was
     * actually taken. Clamped, never negative: the listing owns its items once taken.
     */
    public synchronized long withdraw(String key, long amount) {
        if (key == null || key.isBlank() || amount <= 0) return 0;
        Long held = stock.get(key);
        if (held == null || held <= 0) return 0;
        long take = Math.min(held, amount);
        long left = held - take;
        if (left > 0) stock.put(key, left);
        else stock.remove(key);
        save();
        return take;
    }

    public synchronized Map<String, Long> snapshot() {
        return Map.copyOf(stock);
    }

    public synchronized long total() {
        long total = 0;
        for (long count : stock.values()) total += count;
        return total;
    }

    private void load() {
        if (Files.notExists(file)) return;
        try {
            String json = Files.readString(file);
            JsonObject root = GSON.fromJson(json, JsonObject.class);
            if (root == null) return;
            for (var entry : root.entrySet()) {
                try {
                    long count = entry.getValue().getAsLong();
                    if (count > 0) stock.put(entry.getKey(), count);
                } catch (Exception ignored) {
                    LOGGER.warn("[EconomyCraft] Dropping an unreadable quest stock entry for {} in {}", entry.getKey(), file);
                }
            }
        } catch (Exception ex) {
            LOGGER.error("[EconomyCraft] Failed to load {}; starting with empty quest stock.", file, ex);
        }
    }

    public synchronized void save() {
        JsonObject root = new JsonObject();
        for (var entry : stock.entrySet()) root.addProperty(entry.getKey(), entry.getValue());
        AsyncFileWriter.writeAsync(file, GSON.toJson(root));
    }
}
