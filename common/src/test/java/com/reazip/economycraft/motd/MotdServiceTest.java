package com.reazip.economycraft.motd;

import com.reazip.economycraft.EconomyConfig;
import com.reazip.economycraft.config.MotdBlock;
import com.reazip.economycraft.config.MotdSection;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MotdServiceTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @AfterEach
    void clearPending() {
        MotdService.clearPending();
    }

    @Test
    void joinDelayPrecedesBlockOneAndZeroDelaySuccessorArrivesOnNextTick() {
        MotdSection section = section(2, List.of(block("first", 0), block("second", 30)));
        UUID id = UUID.randomUUID();
        ServerPlayer player = mock(ServerPlayer.class);
        when(player.getUUID()).thenReturn(id);
        MinecraftServer server = serverWith(id, player);

        try (MockedStatic<EconomyConfig> config = mockStatic(EconomyConfig.class)) {
            config.when(EconomyConfig::get).thenReturn(configWith(section));
            MotdService.scheduleOnJoin(player);

            MotdService.tick(server);
            verify(player, never()).sendSystemMessage(org.mockito.ArgumentMatchers.any(Component.class));
            MotdService.tick(server);
            verify(player).sendSystemMessage(org.mockito.ArgumentMatchers.argThat(c -> c.getString().equals("first")));
            verify(player, never()).sendSystemMessage(org.mockito.ArgumentMatchers.argThat(c -> c.getString().equals("second")));
            MotdService.tick(server);
            verify(player).sendSystemMessage(org.mockito.ArgumentMatchers.argThat(c -> c.getString().equals("second")));
            MotdService.tick(server);
            verify(player, times(2)).sendSystemMessage(org.mockito.ArgumentMatchers.any(Component.class));
        }
    }

    @Test
    void configuredDelayGatesOnlyItsSuccessorAndClearCancelsPendingSequence() {
        MotdSection section = section(0, List.of(block("first", 1), block("second", 0)));
        UUID id = UUID.randomUUID();
        ServerPlayer player = mock(ServerPlayer.class);
        when(player.getUUID()).thenReturn(id);
        MinecraftServer server = serverWith(id, player);

        try (MockedStatic<EconomyConfig> config = mockStatic(EconomyConfig.class)) {
            config.when(EconomyConfig::get).thenReturn(configWith(section));
            MotdService.scheduleOnJoin(player);
            verify(player).sendSystemMessage(org.mockito.ArgumentMatchers.argThat(c -> c.getString().equals("first")));
            for (int i = 0; i < 19; i++) MotdService.tick(server);
            verify(player, never()).sendSystemMessage(org.mockito.ArgumentMatchers.argThat(c -> c.getString().equals("second")));
            MotdService.tick(server);
            verify(player).sendSystemMessage(org.mockito.ArgumentMatchers.argThat(c -> c.getString().equals("second")));

            MotdService.scheduleOnJoin(player);
            MotdService.clearPending();
            for (int i = 0; i < 25; i++) MotdService.tick(server);
            verify(player, times(2)).sendSystemMessage(org.mockito.ArgumentMatchers.argThat(c -> c.getString().equals("first")));
            verify(player, times(1)).sendSystemMessage(org.mockito.ArgumentMatchers.argThat(c -> c.getString().equals("second")));
        }
    }

    @Test
    void disconnectAndRejoinRestartsAtBlockOneWithFreshJoinDelay() {
        MotdSection section = section(2, List.of(block("first", 0), block("second", 30)));
        UUID id = UUID.randomUUID();
        ServerPlayer player = mock(ServerPlayer.class);
        when(player.getUUID()).thenReturn(id);
        MinecraftServer server = serverWith(id, player);

        try (MockedStatic<EconomyConfig> config = mockStatic(EconomyConfig.class)) {
            config.when(EconomyConfig::get).thenReturn(configWith(section));
            MotdService.scheduleOnJoin(player);
            MotdService.tick(server);
            MotdService.onPlayerQuit(player);
            MotdService.scheduleOnJoin(player);
            MotdService.tick(server);
            verify(player, never()).sendSystemMessage(org.mockito.ArgumentMatchers.any(Component.class));
            MotdService.tick(server);
            verify(player).sendSystemMessage(org.mockito.ArgumentMatchers.argThat(c -> c.getString().equals("first")));
        }
    }

    @Test
    void previewSendsEveryBlockImmediatelyWithSeparator() {
        MotdSection section = section(40, List.of(block("first", 3600), block("second", 30)));
        ServerPlayer player = mock(ServerPlayer.class);
        try (MockedStatic<EconomyConfig> config = mockStatic(EconomyConfig.class)) {
            config.when(EconomyConfig::get).thenReturn(configWith(section));
            MotdService.sendMotd(player);
            var captor = org.mockito.ArgumentCaptor.forClass(Component.class);
            org.mockito.Mockito.verify(player, times(3)).sendSystemMessage(captor.capture());
            assertEquals(List.of("first", "──── MOTD block 2 ────", "second"),
                    captor.getAllValues().stream().map(Component::getString).toList());
        }
    }

    private static MotdBlock block(String text, int delay) {
        MotdBlock block = new MotdBlock(List.of(text));
        block.nextDelaySeconds = delay;
        return block;
    }

    private static MotdSection section(int joinDelay, List<MotdBlock> blocks) {
        MotdSection section = new MotdSection();
        section.delayTicks = joinDelay;
        section.blocks = blocks;
        return section;
    }

    private static EconomyConfig configWith(MotdSection section) {
        EconomyConfig config = new EconomyConfig();
        config.motd = section;
        return config;
    }

    private static MinecraftServer serverWith(UUID id, ServerPlayer player) {
        MinecraftServer server = mock(MinecraftServer.class);
        PlayerList players = mock(PlayerList.class);
        when(server.getPlayerList()).thenReturn(players);
        when(players.getPlayer(id)).thenReturn(player);
        return server;
    }
}
