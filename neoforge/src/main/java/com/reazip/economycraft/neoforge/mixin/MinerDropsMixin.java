package com.reazip.economycraft.neoforge.mixin;

import com.reazip.economycraft.profession.MinerEffects;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.event.level.BlockDropsEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.ArrayList;
import java.util.List;

@Mixin(value = CommonHooks.class, remap = false)
abstract class MinerDropsMixin {
    @Redirect(method = "handleBlockDrops", at = @At(value = "INVOKE", target = "Lnet/neoforged/neoforge/event/level/BlockDropsEvent;getDrops()Ljava/util/List;"))
    private static List<ItemEntity> economycraft$doubleDrops(BlockDropsEvent event, ServerLevel level, BlockPos pos,
            BlockState state, BlockEntity blockEntity, List<ItemEntity> original, Entity entity, ItemStack tool) {
        List<ItemEntity> drops = event.getDrops();
        List<ItemStack> stacks = drops.stream().map(ItemEntity::getItem).toList();
        List<ItemStack> doubled = MinerEffects.doubleOreDrops(entity, state, stacks);
        if (doubled == stacks) return drops;
        List<ItemEntity> result = new ArrayList<>(drops);
        for (int i = 0; i < drops.size(); i++) {
            ItemEntity drop = drops.get(i);
            result.add(new ItemEntity(level, drop.getX(), drop.getY(), drop.getZ(), doubled.get(i + drops.size())));
        }
        return result;
    }
}
