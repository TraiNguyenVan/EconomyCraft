package com.reazip.economycraft.fabric.mixin;

import com.reazip.economycraft.profession.ProfessionHooks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Crafting result take hook. Fires when a player takes a crafted item from a crafting result slot.
 * Used by Farmer's Khéo léo bonus food yield (spec line 50).
 */
@Mixin(ResultSlot.class)
abstract class ProfessionCraftMixin {
    @Inject(method = "onTake", at = @At("RETURN"))
    private void economycraft$onCraftTake(Player player, ItemStack stack, CallbackInfo ci) {
        if (player instanceof ServerPlayer serverPlayer) {
            ProfessionHooks.onFoodTaken(serverPlayer, stack);
        }
    }
}
