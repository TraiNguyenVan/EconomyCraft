package com.reazip.economycraft.motd;

import com.reazip.economycraft.EconomyConfig;
import com.reazip.economycraft.config.MotdBlock;
import com.reazip.economycraft.config.MotdSection;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Manages ordered, finite login MOTD sequences. */
public final class MotdService {
    private static final Map<UUID, PendingMotd> PENDING = new HashMap<>();

    private MotdService() {}

    private static final class PendingMotd {
        private int blockIndex;
        private int remainingTicks;

        private PendingMotd(int blockIndex, int remainingTicks) {
            this.blockIndex = blockIndex;
            this.remainingTicks = remainingTicks;
        }
    }

    public static void scheduleOnJoin(ServerPlayer player) {
        UUID uuid = player.getUUID();
        PENDING.remove(uuid);
        MotdSection cfg = currentConfig();
        if (cfg == null || !cfg.enabled || cfg.blocks.isEmpty()) return;
        if (cfg.delayTicks <= 0) {
            sendBlock(player, cfg.blocks.get(0));
            scheduleNext(uuid, cfg, 0);
        } else {
            PENDING.put(uuid, new PendingMotd(0, cfg.delayTicks));
        }
    }

    public static void onPlayerQuit(ServerPlayer player) {
        PENDING.remove(player.getUUID());
    }

    /** Cancels sequences so a reload can never mix old and new block definitions. */
    public static void clearPending() {
        PENDING.clear();
    }

    public static void tick(MinecraftServer server) {
        if (PENDING.isEmpty() || server == null) return;
        var iterator = PENDING.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, PendingMotd> entry = iterator.next();
            PendingMotd pending = entry.getValue();
            if (pending.remainingTicks > 0) pending.remainingTicks--;
            if (pending.remainingTicks > 0) continue;

            UUID uuid = entry.getKey();
            MotdSection cfg = currentConfig();
            if (cfg == null || !cfg.enabled || pending.blockIndex >= cfg.blocks.size()) {
                iterator.remove();
                continue;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player == null) {
                iterator.remove();
                continue;
            }
            int sentIndex = pending.blockIndex;
            sendBlock(player, cfg.blocks.get(sentIndex));
            if (sentIndex + 1 >= cfg.blocks.size()) {
                iterator.remove();
            } else {
                pending.blockIndex++;
                pending.remainingTicks = cfg.blocks.get(sentIndex).nextDelaySeconds * 20;
            }
        }
    }

    /** Sends every block immediately; used by the command preview. */
    public static void sendMotd(ServerPlayer player) {
        MotdSection cfg = currentConfig();
        if (cfg == null) return;
        for (int i = 0; i < cfg.blocks.size(); i++) {
            if (i > 0) player.sendSystemMessage(Component.literal("──── MOTD block " + (i + 1) + " ────")
                    .withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
            sendBlock(player, cfg.blocks.get(i));
        }
    }

    private static void scheduleNext(UUID uuid, MotdSection cfg, int sentIndex) {
        if (sentIndex + 1 < cfg.blocks.size()) {
            PENDING.put(uuid, new PendingMotd(sentIndex + 1,
                    cfg.blocks.get(sentIndex).nextDelaySeconds * 20));
        }
    }

    private static MotdSection currentConfig() {
        EconomyConfig config = EconomyConfig.get();
        return config == null ? null : config.motd;
    }

    private static void sendBlock(ServerPlayer player, MotdBlock block) {
        if (block == null || block.lines == null) return;
        for (String rawLine : block.lines) {
            String resolved = MotdFormatter.resolvePlaceholders(rawLine, player);
            player.sendSystemMessage(MotdFormatter.formatLine(resolved));
        }
    }
}
