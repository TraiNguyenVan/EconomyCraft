package com.reazip.economycraft.fabric.mixin;

import com.reazip.economycraft.motd.ServerListReleaseNotes;
import net.minecraft.network.protocol.status.ServerStatus;
import net.minecraft.server.network.ServerHandshakePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(ServerHandshakePacketListenerImpl.class)
abstract class ServerListMotdMixin {
    @ModifyArg(
            method = "handleIntention",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/network/ServerStatusPacketListenerImpl;<init>(Lnet/minecraft/network/protocol/status/ServerStatus;Lnet/minecraft/network/Connection;)V"),
            index = 0)
    private ServerStatus economycraft$appendReleaseNotes(ServerStatus status) {
        return ServerListReleaseNotes.decorate(status);
    }
}
