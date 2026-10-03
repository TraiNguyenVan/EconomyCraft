package com.reazip.economycraft.fabric.mixin;

import com.reazip.economycraft.profession.ProfessionHooks;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Breeding hook. No loader-neutral event exists for breeding in 26.3 (Architectury ships taming but not
 * breeding, and Fabric API has no {@code AnimalBreed} event), so the portable layer cannot cover this one and
 * each loader mixes {@code Animal#setInLove} and {@code Animal#finalizeSpawnChildFromBreeding}.
 * See {@code ProfessionHooks#onAnimalFed} and {@code ProfessionHooks#onOffspringBred}.
 */
@Mixin(Animal.class)
abstract class ProfessionBreedMixin {
    @Inject(method = "setInLove", at = @At("RETURN"))
    private void economycraft$onFed(Player player, CallbackInfo callback) {
        if (player instanceof ServerPlayer serverPlayer) {
            ProfessionHooks.onAnimalFed(serverPlayer, (Animal) (Object) this);
        }
    }

    @Inject(method = "finalizeSpawnChildFromBreeding", at = @At("RETURN"))
    private void economycraft$onChildSpawned(ServerLevel level, Animal partner, AgeableMob child, CallbackInfo ci) {
        Animal self = (Animal) (Object) this;
        ServerPlayer player = self.getLoveCause();
        if (player == null && partner != null) {
            player = partner.getLoveCause();
        }
        if (player != null) {
            ProfessionHooks.onOffspringBred(player, self, partner, child);
        }
    }
}
