package com.reazip.economycraft.neoforge.mixin;

import com.reazip.economycraft.profession.ProfessionHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Break hook for Miner and Farmer progress on NeoForge.
 *
 * <p>NeoForge's {@code BreakBlockEvent} fires <em>before</em> the break is attempted, so paying out there would
 * let a player farm progress by repeatedly failing to break an unbreakable block. Fabric has a post-break
 * event ({@code PlayerBlockBreakEvents.AFTER}) but NeoForge has no equivalent, so this watches the vanilla
 * method instead and credits only on a confirmed {@code true}.
 *
 * <p>26.3's {@code destroyBlock} takes just a {@code BlockPos} and looks the state up internally, so the state
 * is captured on the way in: by the time the method returns the block is already gone from the world, but the
 * captured value is what the crop age or ore check needs.
 */
@Mixin(ServerPlayerGameMode.class)
abstract class ProfessionBreakMixin {
    // 'player' is the only field this mixin shadows. 26.3 declares it private final ServerPlayer, which
    // matches the @Final here; deriving the level from the player avoids shadowing the separate, non-final
    // 'level' field as well.
    @Shadow @Final private ServerPlayer player;

    @Unique private BlockState economycraft$preBreak;

    @Inject(method = "destroyBlock", at = @At("HEAD"))
    private void economycraft$capture(BlockPos pos, CallbackInfoReturnable<Boolean> callback) {
        economycraft$preBreak = player.level().getBlockState(pos);
    }

    @Inject(method = "destroyBlock", at = @At("RETURN"))
    private void economycraft$credit(BlockPos pos, CallbackInfoReturnable<Boolean> callback) {
        if (!Boolean.TRUE.equals(callback.getReturnValue())) return;
        BlockState preBreak = economycraft$preBreak;
        economycraft$preBreak = null;
        if (preBreak != null && !preBreak.isAir()) {
            ProfessionHooks.onBlockBroken(player, pos, preBreak);
        }
    }
}
