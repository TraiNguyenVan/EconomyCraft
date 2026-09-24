package com.reazip.economycraft.fabric.mixin;

import com.reazip.economycraft.TollManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.Hopper;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Stops a hopper directly beneath a tolled chest from extracting its inventory. */
@Mixin(HopperBlockEntity.class)
abstract class TollHopperMixin {
    @Inject(method = "suckInItems", at = @At("HEAD"), cancellable = true)
    private static void economycraft$blockTollChestExtraction(Level level, Hopper hopper,
                                                              CallbackInfoReturnable<Boolean> callback) {
        if (!(level instanceof ServerLevel serverLevel)) return;
        BlockPos hopperPos = BlockPos.containing(hopper.getLevelX(), hopper.getLevelY(), hopper.getLevelZ());
        if (TollManager.of(serverLevel.getServer()).blocksHopperExtraction(serverLevel, hopperPos)) {
            callback.setReturnValue(true);
        }
    }
}
