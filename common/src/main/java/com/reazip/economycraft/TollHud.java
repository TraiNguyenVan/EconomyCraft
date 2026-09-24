package com.reazip.economycraft;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.UUID;

/** Refreshes a vanilla action bar tip while a player aims at a toll block. */
public final class TollHud {
    private TollHud() {}

    private static final int CHECK_INTERVAL_TICKS = 10;
    private static final int REFRESH_INTERVAL_TICKS = 50;
    private static final Map<MinecraftServer, Map<UUID, Displayed>> DISPLAYED = new WeakHashMap<>();

    public static void tick(MinecraftServer server) {
        int tick = server.getTickCount();
        if (tick % CHECK_INTERVAL_TICKS != 0) return;

        Map<UUID, Displayed> displayed = DISPLAYED.computeIfAbsent(server, ignored -> new HashMap<>());
        Set<UUID> online = new HashSet<>();
        TollManager tolls = TollManager.of(server);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID id = player.getUUID();
            online.add(id);
            TollManager.Toll toll = aimedToll(player, tolls);
            Displayed previous = displayed.get(id);
            if (toll == null) {
                if (previous != null) {
                    player.sendSystemMessage(Component.empty(), true);
                    displayed.remove(id);
                }
                continue;
            }

            String message = "Toll: " + EconomyCraft.formatMoney(toll.fee) + " net (right-click or step on the block to pay)";
            if (previous == null || !previous.message().equals(message)
                    || tick - previous.lastSentTick() >= REFRESH_INTERVAL_TICKS) {
                player.sendSystemMessage(Component.literal(message).withStyle(ChatFormatting.GOLD), true);
                displayed.put(id, new Displayed(message, tick));
            }
        }

        displayed.keySet().retainAll(online);
    }

    private static TollManager.Toll aimedToll(ServerPlayer player, TollManager tolls) {
        var hit = player.pick(5.0, 1.0f, false);
        if (!(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK) return null;
        BlockPos pos = blockHit.getBlockPos();
        return tolls.get(player.level().dimension().identifier().toString(), pos);
    }

    private record Displayed(String message, int lastSentTick) {}
}
