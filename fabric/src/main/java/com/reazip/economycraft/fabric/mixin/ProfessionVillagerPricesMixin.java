package com.reazip.economycraft.fabric.mixin;

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
 * <p>{@code updateSpecialPrices} is the one place vanilla recomputes every offer's
 * {@code specialPriceDiff}: it zeroes the whole field, re-applies the reputation and Hero of the Village
 * discounts, and then <em>sends the offers to the client</em>. Hooking anywhere other than between those
 * two steps is wrong in both directions — before the reset and the discount is wiped, after the send and
 * the client has already been told the undiscounted prices. So the injection sits immediately before the
 * {@code getTradingPlayer()} call that guards the send: after vanilla's reset, before the packet.
 *
 * <p>The {@code @At} target names the method without an owner: javac emits a bare
 * {@code getTradingPlayer()} for this call site, so a target qualified with the declaring class
 * ({@code AbstractVillager}) scans zero instructions and the game refuses to boot with an
 * InjectionError. Verified against the decompiled bytecode rather than assumed.
 *
 * <p>That placement also fixes the buff lasting only one trade. Vanilla re-runs this method on every
 * restock, gossip transfer, reputation change and level-up; applying here means the discount is re-added
 * each time rather than needing the trading menu reopened.
 *
 * <p>Vanilla zeroes first and this only <em>adds</em>, so the reputation, Hero of the Village and
 * Lưỡi không xương discounts stack rather than overwrite one another, and
 * {@code MerchantOffer#getModifiedCostCount} still clamps a stacked cost at 1 item.
 */
@Mixin(Villager.class)
abstract class ProfessionVillagerPricesMixin {
    @Inject(
            method = "updateSpecialPrices",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/npc/villager/Villager;getTradingPlayer()Lnet/minecraft/world/entity/player/Player;",
                    shift = At.Shift.BEFORE
            )
    )
    private void economycraft$applyMerchantDiscount(Player player, CallbackInfo ci) {
        if (player instanceof ServerPlayer serverPlayer) {
            MerchantEffects.applyVillagerTradeDiscount(serverPlayer, (Villager) (Object) this);
        }
    }
}