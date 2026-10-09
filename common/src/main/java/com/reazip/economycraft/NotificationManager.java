package com.reazip.economycraft;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.logging.LogUtils;
import com.reazip.economycraft.db.Documents;
import com.reazip.economycraft.db.EconomyDatabase;
import com.reazip.economycraft.util.EconomyPaths;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

public final class NotificationManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type TYPE = new TypeToken<Map<UUID, List<String>>>(){}.getType();

    private final MinecraftServer server;
    private final Path file;
    private final EconomyDatabase db;
    private final Map<UUID, List<String>> pending = new ConcurrentHashMap<>();
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
        if (player == null) return;

        ServerPlayer online = server.getPlayerList().getPlayer(player);
        if (online != null) {
            send(online, message);
            return;
        }
        pending.computeIfAbsent(player, k -> new CopyOnWriteArrayList<>()).add(message);
        dirty.set(true);
    }

    public void flush() {
        if (dirty.compareAndSet(true, false)) save();
    }

    public void sendPending(ServerPlayer player) {
        List<String> messages = pending.remove(player.getUUID());
        if (messages == null || messages.isEmpty()) return;
        for (String message : messages) send(player, message);
        save();
    }

    private void send(ServerPlayer player, String message) {
        player.sendSystemMessage(Component.literal(message).withStyle(ChatFormatting.YELLOW));
    }

    private void load() {
        String json = Documents.read(db, file, "notifications.json");
        if (json == null) return;
        try {
            Map<UUID, List<String>> map = GSON.fromJson(json, TYPE);
            if (map != null) {
                for (var entry : map.entrySet()) {
                    if (entry.getValue() != null && !entry.getValue().isEmpty()) {
                        pending.put(entry.getKey(), new CopyOnWriteArrayList<>(entry.getValue()));
                    }
                }
            }
        } catch (Exception ex) {
            LOGGER.error("[EconomyCraft] Failed to load {}", file, ex);
        }
    }

    private void save() {
        Documents.write(db, file, "notifications.json", GSON.toJson(new HashMap<>(pending), TYPE));
    }
}
