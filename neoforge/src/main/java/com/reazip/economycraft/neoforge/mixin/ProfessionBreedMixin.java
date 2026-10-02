package com.reazip.economycraft.neoforge.mixin;

import com.reazip.economycraft.profession.ProfessionHooks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Breeding hook, matching the Fabric mixin of the same name. No portable event covers breeding in 26.3, so
 * both loaders mix {@code Animal#setInLove}. See {@code ProfessionHooks#onAnimalFed}.
 */
@Mixin(Animal.class)
abstract class ProfessionBreedMixin {
    @Inject(method = "setInLove", at = @At("RETURN"))
    private void economycraft$onFed(Player player, CallbackInfo callback) {
        if (player instanceof ServerPlayer serverPlayer) {
            ProfessionHooks.onAnimalFed(serverPlayer, (Animal) (Object) this);
        }
    }
}
