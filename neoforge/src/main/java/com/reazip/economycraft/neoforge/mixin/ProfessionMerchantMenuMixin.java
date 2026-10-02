package com.reazip.economycraft.neoforge.mixin;

import com.reazip.economycraft.profession.MerchantEffects;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.trading.Merchant;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Trading menu hook: applies Lưỡi không xương discount when merchant opens trading screen,
 * and resets it when the menu is closed (spec line 59).
 */
@Mixin(MerchantMenu.class)
abstract class ProfessionMerchantMenuMixin {
    @Inject(method = "<init>(ILnet/minecraft/world/entity/player/Inventory;Lnet/minecraft/world/item/trading/Merchant;)V", at = @At("RETURN"))
    private void economycraft$onOpen(int id, Inventory inventory, Merchant trader, CallbackInfo ci) {
        if (inventory.player instanceof ServerPlayer player) {
            MerchantEffects.applyVillagerTradeDiscount(player, trader.getOffers());
        }
    }

    @Inject(method = "removed", at = @At("HEAD"))
    private void economycraft$onClose(Player player, CallbackInfo ci) {
        if (player instanceof ServerPlayer) {
            MerchantMenu self = (MerchantMenu) (Object) this;
            MerchantEffects.resetVillagerTradeDiscount(self.getOffers());
        }
    }
}
