package com.reazip.economycraft;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.mojang.logging.LogUtils;
import com.reazip.economycraft.auction.AuctionManager;
import com.reazip.economycraft.db.Documents;
import com.reazip.economycraft.db.EconomyDatabase;
import com.reazip.economycraft.util.ChatCompat;
import com.reazip.economycraft.util.EconomyPaths;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Chat messages for a player who may not be online yet.
 *
 * <p>Most notices are plain text. A notice that has an action (a new {@code /ah} listing, for example)
 * also carries the command behind its clickable button and, for listings, the id of the listing it is
 * about — which is how a queued notice for an item that has since sold is dropped at delivery instead of
 * announcing a listing that no longer exists.
 */
public final class NotificationManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /** Newest win: an offline player never opens their inbox to more than this many listing notices. */
    public static final int MAX_LISTING_NOTICES = 5;

    /**
     * @param text      the message body, always present
     * @param command   command run by the {@code [View]} button, or null for a plain text line
     * @param listingId the listing this notice is about, or null when it is not about one
     */
    public record Notification(String text, @Nullable String command, @Nullable Integer listingId) {
        public Notification(String text) {
            this(text, null, null);
        }
    }

    private final MinecraftServer server;
    private final Path file;
    private final EconomyDatabase db;
    private final Map<UUID, List<Notification>> pending = new ConcurrentHashMap<>();
    private final AtomicBoolean dirty = new AtomicBoolean(false);

    public NotificationManager(MinecraftServer server) {
        this(server, null);
    }

    /** Production constructor: persists to the shared database document instead of the file. */
    public NotificationManager(MinecraftServer server, EconomyDatabase db) {
        this.server = server;
        this.db = db;
        this.file = EconomyPaths.dataDir(server).resolve("notifications.json");
        load();
    }

    public void notify(UUID player, String message) {
        notify(player, new Notification(message));
    }

    public void notify(UUID player, Notification notification) {
        if (player == null || notification == null) return;

        ServerPlayer online = server.getPlayerList().getPlayer(player);
        if (online != null) {
            send(online, notification);
            return;
        }
        List<Notification> queued = pending.computeIfAbsent(player, k -> new CopyOnWriteArrayList<>());
        queued.add(notification);
        pruneListingNotices(queued);
        dirty.set(true);
    }

    public void flush() {
        if (dirty.compareAndSet(true, false)) save();
    }

    public void sendPending(ServerPlayer player) {
        List<Notification> queued = pending.remove(player.getUUID());
        if (queued == null || queued.isEmpty()) return;

        AuctionManager auctions = liveAuctions();
        for (Notification notification : queued) {
            if (isStale(notification, id -> auctions == null || auctions.getListing(id) != null)) continue;
            send(player, notification);
        }
        save();
    }

    /** A listing notice whose listing sold, expired or was cancelled while the player was away. */
    static boolean isStale(Notification notification, java.util.function.IntPredicate listingExists) {
        return notification.listingId() != null && !listingExists.test(notification.listingId());
    }

    private @Nullable AuctionManager liveAuctions() {
        // peek, not get: this runs during join setup, and constructing a manager from here would
        // rebuild the whole economy while its own constructor is still finishing on a first boot.
        EconomyManager eco = EconomyCraft.peekManager(server);
        return eco == null ? null : eco.getAuctions();
    }

    private void send(ServerPlayer player, Notification notification) {
        MutableComponent line = Component.literal(notification.text()).withStyle(ChatFormatting.YELLOW);
        String command = notification.command();
        if (command != null && !command.isBlank()) {
            line.append(Component.literal("  "))
                    .append(Component.literal("[View]").withStyle(style -> style
                            .withUnderlined(true)
                            .withColor(ChatFormatting.GREEN)
                            .withClickEvent(ChatCompat.runCommandEvent(command))));
        }
        player.sendSystemMessage(line);
    }

    /** Keeps the newest {@link #MAX_LISTING_NOTICES} listing notices; every other kind is untouched. */
    static void pruneListingNotices(List<Notification> queued) {
        int listings = 0;
        for (Notification notification : queued) {
            if (notification.listingId() != null) listings++;
        }
        for (int i = 0; listings > MAX_LISTING_NOTICES && i < queued.size(); ) {
            if (queued.get(i).listingId() != null) {
                queued.remove(i);
                listings--;
            } else {
                i++;
            }
        }
    }

    private void load() {
        String json = Documents.read(db, file, "notifications.json");
        if (json == null) return;
        try {
            pending.putAll(decode(json));
        } catch (Exception ex) {
            LOGGER.error("[EconomyCraft] Failed to load {}", file, ex);
        }
    }

    private void save() {
        Documents.write(db, file, "notifications.json", encode(new LinkedHashMap<>(pending)));
    }

    /**
     * Entries written before notices could carry an action are bare strings and still load as text-only,
     * so contracts, offers and expiry messages keep their existing shape in the file.
     */
    static Map<UUID, List<Notification>> decode(String json) {
        Map<UUID, List<Notification>> out = new LinkedHashMap<>();
        if (json == null || json.isBlank()) return out;

        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
            UUID player;
            try {
                player = UUID.fromString(entry.getKey());
            } catch (IllegalArgumentException notAUuid) {
                continue;
            }
            if (!entry.getValue().isJsonArray()) continue;

            List<Notification> queued = new ArrayList<>();
            for (JsonElement element : entry.getValue().getAsJsonArray()) {
                Notification notification = decodeEntry(element);
                if (notification != null) queued.add(notification);
            }
            if (!queued.isEmpty()) out.put(player, queued);
        }
        return out;
    }

    private static @Nullable Notification decodeEntry(JsonElement element) {
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            return new Notification(element.getAsString());
        }
        if (!element.isJsonObject()) return null;

        JsonObject object = element.getAsJsonObject();
        if (!object.has("text") || object.get("text").isJsonNull()) return null;
        String command = object.has("command") && !object.get("command").isJsonNull()
                ? object.get("command").getAsString()
                : null;
        Integer listingId = object.has("listingId") && !object.get("listingId").isJsonNull()
                ? object.get("listingId").getAsInt()
                : null;
        return new Notification(object.get("text").getAsString(), command, listingId);
    }

    static String encode(Map<UUID, List<Notification>> pending) {
        JsonObject root = new JsonObject();
        for (Map.Entry<UUID, List<Notification>> entry : pending.entrySet()) {
            if (entry.getValue() == null || entry.getValue().isEmpty()) continue;
            JsonArray array = new JsonArray();
            for (Notification notification : entry.getValue()) array.add(encodeEntry(notification));
            root.add(entry.getKey().toString(), array);
        }
        return GSON.toJson(root);
    }

    private static JsonElement encodeEntry(Notification notification) {
        if (notification.command() == null && notification.listingId() == null) {
            return new JsonPrimitive(notification.text());
        }
        JsonObject object = new JsonObject();
        object.addProperty("text", notification.text());
        if (notification.command() != null) object.addProperty("command", notification.command());
        if (notification.listingId() != null) object.addProperty("listingId", notification.listingId());
        return object;
    }
}
