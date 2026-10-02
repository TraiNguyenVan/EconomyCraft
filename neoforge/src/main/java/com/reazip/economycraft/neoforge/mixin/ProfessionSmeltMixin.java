package com.reazip.economycraft.neoforge.mixin;

import com.reazip.economycraft.profession.ProfessionHooks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.FurnaceResultSlot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Smelting result take hook, matching the Fabric mixin of the same name.
 * Used by Farmer's Khéo léo bonus food yield (spec line 50).
 */
@Mixin(FurnaceResultSlot.class)
abstract class ProfessionSmeltMixin {
    @Inject(method = "onTake", at = @At("RETURN"))
    private void economycraft$onSmeltTake(Player player, ItemStack stack, CallbackInfo ci) {
        if (player instanceof ServerPlayer serverPlayer) {
            ProfessionHooks.onFoodTaken(serverPlayer, stack);
        }
    }
}
