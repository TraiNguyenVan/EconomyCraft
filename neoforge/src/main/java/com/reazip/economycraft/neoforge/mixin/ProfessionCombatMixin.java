package com.reazip.economycraft.neoforge.mixin;

import com.reazip.economycraft.profession.ProfessionHooks;
import com.reazip.economycraft.profession.SoldierEffects;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Combat and damage mixin for Soldier profession effects (Phase 8):
 *
 * <ul>
 *   <li>Level-up tracking (spec 62): counts mob kills in {@code die(DamageSource)}.</li>
 *   <li>{@code Sắt được tôi thế đấy} (spec 63): modifies damage dealt and taken in {@code hurtServer}.</li>
 *   <li>{@code Andrenaline} (spec 65): modifies incoming negative effects in {@code addEffect}.</li>
 * </ul>
 */
@Mixin(LivingEntity.class)
abstract class ProfessionCombatMixin {

    @ModifyVariable(method = "hurtServer", at = @At("HEAD"), argsOnly = true)
    private float economycraft$modifyHurtServerDamage(float amount, ServerLevel level, DamageSource damageSource) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (damageSource != null && damageSource.getEntity() instanceof ServerPlayer attacker) {
            amount = SoldierEffects.modifyDamageDealt(attacker, amount);
        }
        if (self instanceof ServerPlayer victim) {
            amount = SoldierEffects.modifyDamageTaken(victim, amount);
        }
        return amount;
    }

    @Inject(method = "die", at = @At("HEAD"))
    private void economycraft$onDie(DamageSource damageSource, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self.level().isClientSide()) return;
        if (damageSource != null && damageSource.getEntity() instanceof ServerPlayer player) {
            ProfessionHooks.onMobKilled(player, self);
        }
    }

    @ModifyVariable(method = "addEffect(Lnet/minecraft/world/effect/MobEffectInstance;Lnet/minecraft/world/entity/Entity;)Z", at = @At("HEAD"), argsOnly = true)
    private MobEffectInstance economycraft$modifyIncomingEffect(MobEffectInstance effect) {
        if ((Object) this instanceof ServerPlayer player) {
            return SoldierEffects.onIncomingEffect(player, effect);
        }
        return effect;
    }
}
