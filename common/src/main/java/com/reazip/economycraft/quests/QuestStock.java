package com.reazip.economycraft.quests;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.reazip.economycraft.db.Documents;
import com.reazip.economycraft.db.EconomyDatabase;
import com.reazip.economycraft.util.EconomyPaths;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Where filled quest goods accumulate.
 *
 * <p>A bot-requested fulfillment never touches the deliveries mailbox — there is nobody to claim it —
 * so the items are booked here, keyed by price key. The stock shop spends this finite ledger down
 * when players purchase quest goods.
 */
public class QuestStock {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();

    private final Path file;
    private final EconomyDatabase db;
    private final Map<String, Long> stock = new LinkedHashMap<>();

    public QuestStock(MinecraftServer server) {
        this(server, null);
    }

    /** Production constructor: persists to the shared database document instead of the file. */
    public QuestStock(MinecraftServer server, EconomyDatabase db) {
        this.db = db;
        this.file = EconomyPaths.dataDir(server).resolve("stock.json");
        load();
    }

    public synchronized void deposit(String key, long count) {
        deposit(key, count, true);
    }

    public synchronized void deposit(String key, long count, boolean persist) {
        if (key == null || key.isBlank() || count <= 0) return;
        stock.merge(key, count, Long::sum);
        if (persist) save();
    }

    /**
     * Takes up to {@code amount} units off the ledger for a stock purchase, returning what was
     * actually taken. Clamped, never negative.
     */
    public synchronized long withdraw(String key, long amount) {
        return withdraw(key, amount, true);
    }

    public synchronized long withdraw(String key, long amount, boolean persist) {
        if (key == null || key.isBlank() || amount <= 0) return 0;
        Long held = stock.get(key);
        if (held == null || held <= 0) return 0;
        long take = Math.min(held, amount);
        long left = held - take;
        if (left > 0) stock.put(key, left);
        else stock.remove(key);
        if (persist) save();
        return take;
    }

    public synchronized long get(String key) {
        if (key == null) return 0;
        return stock.getOrDefault(key, 0L);
    }

    public synchronized Map<String, Long> snapshot() {
        return Map.copyOf(stock);
    }

    public synchronized long total() {
        long total = 0;
        for (long count : stock.values()) total += count;
        return total;
    }

    /** Re-reads the ledger after a stock purchase or legacy listing migration rolls back. */
    public synchronized void reload() {
        load();
    }

    private void load() {
        Documents.ReadResult result = Documents.readWithStatus(db, file, "stock.json");
        if (!result.successful()) return;

        Map<String, Long> loaded = parseStock(result.content());
        if (loaded == null) return;
        stock.clear();
        stock.putAll(loaded);
    }

    private Map<String, Long> parseStock(String json) {
        Map<String, Long> loaded = new LinkedHashMap<>();
        if (json == null) return loaded;
        try {
            JsonObject root = GSON.fromJson(json, JsonObject.class);
            if (root == null) {
                LOGGER.error("[EconomyCraft] Failed to parse quest stock in {}; keeping the current ledger.", file);
                return null;
            }
            for (var entry : root.entrySet()) {
                try {
                    long count = entry.getValue().getAsLong();
                    if (count > 0) loaded.put(entry.getKey(), count);
                } catch (Exception ignored) {
                    LOGGER.warn("[EconomyCraft] Dropping an unreadable quest stock entry for {} in {}", entry.getKey(), file);
                }
            }
            return loaded;
        } catch (Exception ex) {
            LOGGER.error("[EconomyCraft] Failed to load {}; keeping the current quest stock.", file, ex);
            return null;
        }
    }

    public synchronized void save() {
        JsonObject root = new JsonObject();
        for (var entry : stock.entrySet()) root.addProperty(entry.getKey(), entry.getValue());
        Documents.write(db, file, "stock.json", GSON.toJson(root));
    }
}
