package com.reazip.economycraft.fabric.mixin;

import com.reazip.economycraft.EconomyCraft;
import com.reazip.economycraft.EconomyManager;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Puts a player's icons in front of their chat message (P3-T4, D22).
 *
 * <p>{@code ServerGamePacketListenerImpl#broadcastChatMessage(PlayerChatMessage)} is the single funnel every
 * player chat message passes through — signed, filtered and command-originated alike — and it lives on the
 * server, so this is where the server gets to change what the client will draw.
 *
 * <p><strong>Why the content and not the name.</strong> The client builds the {@code <Name>} slot itself from
 * {@code PlayerInfo#getProfile()} and hands it to {@code ChatType$Bound#decorate}; no server value reaches it
 * (hook #18c). The message <em>content</em> is server-controlled: {@code decoratedContent()} returns
 * {@code unsignedContent} when it is set and the signed body otherwise. Rewriting it through
 * {@link PlayerChatMessage#withUnsignedContent} leaves the signature untouched, so the line still renders and
 * nothing about who said what is lost.
 *
 * <p><strong>What it costs, on purpose.</strong> A rewritten line renders as
 * {@code ChatTrustLevel.MODIFIED}, so the signed-chat badge goes grey for it. D22 accepted that, with two limits
 * that keep the cost proportional: only a player who <em>has</em> a tag is rewritten, and a message that already
 * carries {@code unsignedContent} — another mod got there first — is passed through untouched rather than
 * double-prefixed.
 *
 * <p>Thin on purpose (D2): the decision of whether and how to rewrite lives in
 * {@code TagDisplayService#chatBody}; this class only supplies the current player.
 */
@Mixin(targets = "net.minecraft.server.network.ServerGamePacketListenerImpl")
abstract class ChatIconMixin {

    @Shadow
    @Final
    private ServerPlayer player;

    @ModifyVariable(method = "broadcastChatMessage(Lnet/minecraft/network/chat/PlayerChatMessage;)V",
            at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private PlayerChatMessage economycraft$prefixChatIcon(PlayerChatMessage message) {
        if (message.unsignedContent() != null) return message;

        var level = player.level();
        if (level == null || level.getServer() == null) return message;

        EconomyManager manager = EconomyCraft.getManager(level.getServer());
        if (manager == null || !manager.getTagDisplay().isTagged(player.getUUID())) return message;

        return message.withUnsignedContent(
                manager.getTagDisplay().chatBody(player.getUUID(), message.decoratedContent()));
    }
}