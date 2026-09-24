package com.reazip.economycraft.fabric.mixin;

import com.reazip.economycraft.TollManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.PressurePlateBlock;
import net.minecraft.world.level.block.WeightedPressurePlateBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin({PressurePlateBlock.class, WeightedPressurePlateBlock.class})
abstract class TollPressurePlateMixin {
    @Inject(method = "getSignalStrength", at = @At("RETURN"), cancellable = true)
    private void economycraft$applyToll(Level level, BlockPos pos, CallbackInfoReturnable<Integer> callback) {
        if (level instanceof ServerLevel serverLevel) {
            int result = TollManager.of(serverLevel.getServer())
                    .pressurePlateSignal(serverLevel, pos, callback.getReturnValue());
            callback.setReturnValue(result);
        }
    }
}
