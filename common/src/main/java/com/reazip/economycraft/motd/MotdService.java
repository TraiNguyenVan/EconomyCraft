package com.reazip.economycraft.motd;

import com.reazip.economycraft.EconomyConfig;
import com.reazip.economycraft.config.MotdSection;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Manages scheduling and dispatching MOTD messages to players on join or via command.
 */
public final class MotdService {
    private static final ConcurrentMap<java.util.UUID, Integer> PENDING_TICKS = new ConcurrentHashMap<>();

    private MotdService() {}

    /**
     * Schedules MOTD delivery for a player when joining.
     */
    public static void scheduleOnJoin(ServerPlayer player) {
        MotdSection cfg = EconomyConfig.get().motd;
        if (cfg == null || !cfg.enabled) {
            return;
        }

        if (cfg.delayTicks <= 0) {
            sendMotd(player);
        } else {
            PENDING_TICKS.put(player.getUUID(), cfg.delayTicks);
        }
    }

    /**
     * Cleans up tracking when a player disconnects.
     */
    public static void onPlayerQuit(ServerPlayer player) {
        PENDING_TICKS.remove(player.getUUID());
    }

    /**
     * Advances ticks for pending MOTDs. Called on server tick.
     */
    public static void tick(MinecraftServer server) {
        if (PENDING_TICKS.isEmpty()) return;

        for (java.util.Map.Entry<java.util.UUID, Integer> entry : PENDING_TICKS.entrySet()) {
            java.util.UUID uuid = entry.getKey();
            int current = entry.getValue();
            if (current <= 1) {
                PENDING_TICKS.remove(uuid);
                ServerPlayer player = server.getPlayerList().getPlayer(uuid);
                if (player != null && player.connection != null) {
                    sendMotd(player);
                }
            } else {
                PENDING_TICKS.put(uuid, current - 1);
            }
        }
    }

    /**
     * Sends the configured MOTD directly to a player.
     */
    public static void sendMotd(ServerPlayer player) {
        MotdSection cfg = EconomyConfig.get().motd;
        if (cfg == null) return;

        List<String> lines = cfg.lines;
        if (lines == null || lines.isEmpty()) return;

        for (String rawLine : lines) {
            String resolved = MotdFormatter.resolvePlaceholders(rawLine, player);
            Component formatted = MotdFormatter.formatLine(resolved);
            player.sendSystemMessage(formatted);
        }
    }
}
