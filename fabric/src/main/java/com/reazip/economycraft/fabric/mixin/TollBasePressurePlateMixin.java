package com.reazip.economycraft.fabric.mixin;

import com.reazip.economycraft.TollManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BasePressurePlateBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BasePressurePlateBlock.class)
abstract class TollBasePressurePlateMixin {
    @Inject(method = "entityInside", at = @At("HEAD"))
    private void economycraft$processTollEntry(BlockState state, Level level, BlockPos pos, Entity entity,
                                               InsideBlockEffectApplier effectApplier, boolean flag, CallbackInfo callback) {
        if (level instanceof ServerLevel serverLevel && entity instanceof ServerPlayer player
                && player.isAlive() && !player.isSpectator()) {
            TollManager.of(serverLevel.getServer()).pressurePlateSignal(serverLevel, pos, 0);
        }
    }
}
