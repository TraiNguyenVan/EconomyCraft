package com.reazip.economycraft.motd;

import com.reazip.economycraft.EconomyConfig;
import com.reazip.economycraft.config.MotdBlock;
import com.reazip.economycraft.config.MotdSection;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Manages ordered, finite login MOTD sequences. */
public final class MotdService {
    private static final Map<UUID, PendingMotd> PENDING = new HashMap<>();
    private static final int RELEASE_NOTES_STEP = -1;

    private MotdService() {}

    private static final class PendingMotd {
        private int stepIndex;
        private int remainingTicks;

        private PendingMotd(int stepIndex, int remainingTicks) {
            this.stepIndex = stepIndex;
            this.remainingTicks = remainingTicks;
        }
    }

    private record MotdStep(int blockIndex) {
        private boolean isReleaseNotes() {
            return blockIndex == RELEASE_NOTES_STEP;
        }
    }

    public static void scheduleOnJoin(ServerPlayer player) {
        UUID uuid = player.getUUID();
        PENDING.remove(uuid);
        MotdSection cfg = currentConfig();
        if (cfg == null || !cfg.enabled) return;
        List<MotdStep> steps = buildSequence(cfg);
        if (steps.isEmpty()) return;
        if (cfg.delayTicks <= 0) {
            sendStep(player, cfg, steps.get(0));
            scheduleNext(uuid, cfg, steps, 0);
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
            if (cfg == null || !cfg.enabled) {
                iterator.remove();
                continue;
            }
            List<MotdStep> steps = buildSequence(cfg);
            if (pending.stepIndex >= steps.size()) {
                iterator.remove();
                continue;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player == null) {
                iterator.remove();
                continue;
            }
            int sentIndex = pending.stepIndex;
            MotdStep step = steps.get(sentIndex);
            sendStep(player, cfg, step);
            if (sentIndex + 1 >= steps.size()) {
                iterator.remove();
            } else {
                pending.stepIndex++;
                pending.remainingTicks = delayAfter(cfg, step);
            }
        }
    }

    /** Sends every block immediately; used by the command preview. */
    public static void sendMotd(ServerPlayer player) {
        MotdSection cfg = currentConfig();
        if (cfg == null) return;
        int blockNumber = 0;
        for (MotdStep step : buildSequence(cfg)) {
            if (step.isReleaseNotes()) {
                ReleaseNotesService.sendTo(player, cfg.releaseNotes.maxChanges);
                continue;
            }
            if (blockNumber > 0) {
                player.sendSystemMessage(Component.literal("──── MOTD block " + (blockNumber + 1) + " ────")
                        .withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
            }
            sendBlock(player, cfg.blocks.get(step.blockIndex));
            blockNumber++;
        }
    }

    /** Previews configured MOTD and release-note ordering in the console without join delays. */
    public static void preview(CommandSourceStack source) {
        MotdSection cfg = currentConfig();
        if (cfg == null) return;
        int blockNumber = 0;
        for (MotdStep step : buildSequence(cfg)) {
            if (step.isReleaseNotes()) {
                for (Component message : ReleaseNotesService.createMessages(cfg.releaseNotes.maxChanges)) {
                    source.sendSuccess(() -> message, false);
                }
                continue;
            }
            if (blockNumber > 0) {
                int nextBlock = blockNumber + 1;
                source.sendSuccess(() -> Component.literal("──── MOTD block " + nextBlock + " ────")
                        .withStyle(net.minecraft.ChatFormatting.DARK_GRAY), false);
            }
            MotdBlock block = cfg.blocks.get(step.blockIndex);
            if (block.lines != null) {
                for (String rawLine : block.lines) {
                    source.sendSuccess(() -> MotdFormatter.formatLine(rawLine), false);
                }
            }
            blockNumber++;
        }
    }

    private static void scheduleNext(UUID uuid, MotdSection cfg, List<MotdStep> steps, int sentIndex) {
        if (sentIndex + 1 < steps.size()) {
            PENDING.put(uuid, new PendingMotd(sentIndex + 1, delayAfter(cfg, steps.get(sentIndex))));
        }
    }

    private static int delayAfter(MotdSection cfg, MotdStep step) {
        if (step.isReleaseNotes()) return 0;
        return cfg.blocks.get(step.blockIndex).nextDelaySeconds * 20;
    }

    private static void sendStep(ServerPlayer player, MotdSection cfg, MotdStep step) {
        if (step.isReleaseNotes()) {
            ReleaseNotesService.sendTo(player, cfg.releaseNotes.maxChanges);
        } else {
            sendBlock(player, cfg.blocks.get(step.blockIndex));
        }
    }

    private static List<MotdStep> buildSequence(MotdSection cfg) {
        int blockCount = cfg.blocks.size();
        int releasePosition = cfg.releaseNotes.enabled
                ? (cfg.releaseNotes.afterBlock == -1 ? blockCount : cfg.releaseNotes.afterBlock)
                : -1;
        List<MotdStep> steps = new ArrayList<>(blockCount + (releasePosition >= 0 ? 1 : 0));
        for (int blockIndex = 0; blockIndex <= blockCount; blockIndex++) {
            if (blockIndex == releasePosition) steps.add(new MotdStep(RELEASE_NOTES_STEP));
            if (blockIndex < blockCount) steps.add(new MotdStep(blockIndex));
        }
        return List.copyOf(steps);
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
