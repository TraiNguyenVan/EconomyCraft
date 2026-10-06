package com.reazip.economycraft.neoforge.mixin;

import com.reazip.economycraft.profession.MerchantEffects;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Villager price hook: applies the Lưỡi không xương discount (spec line 59).
 *
 * <p>Injected at the RETURN of {@code updateSpecialPrices}, which is the one place vanilla recomputes
 * every offer's {@code specialPriceDiff}. Hooking it there — rather than the trading menu — is what makes
 * the buff last more than a single trade: vanilla re-runs this method on every restock, gossip transfer,
 * reputation change and level-up, and it zeroes the whole field before re-applying the reputation and
 * Hero of the Village discounts. A discount applied anywhere else is wiped by the next reprice.
 *
 * <p>Running after vanilla also means the mod's discount is <em>added</em> to the reputation and Hero of
 * the Village discounts rather than replacing them, so all three stack.
 */
@Mixin(Villager.class)
abstract class ProfessionVillagerPricesMixin {
    @Inject(method = "updateSpecialPrices", at = @At("RETURN"))
    private void economycraft$applyMerchantDiscount(Player player, CallbackInfo ci) {
        if (player instanceof ServerPlayer serverPlayer) {
            MerchantEffects.applyVillagerTradeDiscount(serverPlayer, (Villager) (Object) this);
        }
    }
}