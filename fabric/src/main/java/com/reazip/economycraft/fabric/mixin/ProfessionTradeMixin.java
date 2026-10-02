package com.reazip.economycraft.fabric.mixin;

import com.reazip.economycraft.profession.ProfessionHooks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.trading.MerchantOffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Villager trade hook. Fires when a villager trade completes (spec line 58).
 */
@Mixin(AbstractVillager.class)
abstract class ProfessionTradeMixin {
    @Inject(method = "notifyTrade", at = @At("RETURN"))
    private void economycraft$onNotifyTrade(MerchantOffer offer, CallbackInfo ci) {
        AbstractVillager self = (AbstractVillager) (Object) this;
        Player player = self.getTradingPlayer();
        if (player instanceof ServerPlayer serverPlayer) {
            ProfessionHooks.onVillagerTrade(serverPlayer, self, offer);
        }
    }
}
