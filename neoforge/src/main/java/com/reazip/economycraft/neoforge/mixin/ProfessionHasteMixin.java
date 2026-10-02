package com.reazip.economycraft.neoforge.mixin;

import com.reazip.economycraft.profession.ProfessionHaste;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Haste hook (P4-T6 / P6-T2, per D20).
 *
 * <p>{@code incrementDestroyProgress} is the only method vanilla calls <em>while</em> a block is being mined, so
 * it is the signal D20's "only while breaking a trigger block" needs. Granting on break completion instead is
 * the mistake that produces a permanently Hasted Builder, and no portable event exists for this on either
 * loader, so the mixin is per-loader while the decision stays in {@code ProfessionHaste}.
 */
@Mixin(ServerPlayerGameMode.class)
abstract class ProfessionHasteMixin {
    // 'player' is declared private final ServerPlayer in 26.3, which matches the @Final here.
    @Shadow @Final private ServerPlayer player;

    @Inject(method = "incrementDestroyProgress", at = @At("RETURN"))
    private void economycraft$onBreakProgress(BlockState state, BlockPos pos, int progress,
                                              CallbackInfoReturnable<Float> callback) {
        ProfessionHaste.onBreakProgress(player, state);
    }
}
