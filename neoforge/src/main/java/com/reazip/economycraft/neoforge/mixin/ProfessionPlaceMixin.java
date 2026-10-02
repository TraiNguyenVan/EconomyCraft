package com.reazip.economycraft.neoforge.mixin;

import com.reazip.economycraft.profession.ProfessionHooks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Placement hook for Builder and Farmer progress.
 *
 * <p>Neither portable layer covers this: Fabric API 0.160.5+26.3 ships {@code PlayerBlockBreakEvents} but no
 * placement counterpart, and Architectury's {@code BlockEvent.PLACE} fires <em>before</em> the block lands, so
 * crediting there would pay out on attempts that place nothing. {@code BlockItem#place} is the one vanilla
 * method both loaders agree on and its result says whether anything was actually placed.
 */
@Mixin(BlockItem.class)
abstract class ProfessionPlaceMixin {
    @Inject(method = "place", at = @At("RETURN"))
    private void economycraft$onPlace(BlockPlaceContext context, CallbackInfoReturnable<InteractionResult> callback) {
        // PASS is the "nothing happened" result, so requiring a consumed action is what makes this
        // unexploitable: a blocked or impossible placement never earns progress.
        if (!callback.getReturnValue().consumesAction()) return;
        if (!(context.getPlayer() instanceof ServerPlayer player)) return;
        ProfessionHooks.onBlockPlaced(player, context.getLevel().getBlockState(context.getClickedPos()));
    }
}
