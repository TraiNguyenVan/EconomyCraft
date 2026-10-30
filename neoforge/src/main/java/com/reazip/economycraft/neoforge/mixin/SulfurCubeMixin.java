package com.reazip.economycraft.neoforge.mixin;

import com.reazip.economycraft.chunkloading.SulfurCubeChunkLoader;
import net.minecraft.world.entity.monster.cubemob.SulfurCube;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SulfurCube.class)
abstract class SulfurCubeMixin {
    @Inject(method = "tick", at = @At("TAIL"))
    private void economycraft$onSulfurCubeTick(CallbackInfo ci) {
        SulfurCubeChunkLoader.onSulfurCubeTick((SulfurCube) (Object) this);
    }
}
