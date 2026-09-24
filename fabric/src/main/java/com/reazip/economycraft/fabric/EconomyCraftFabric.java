package com.reazip.economycraft.fabric;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import com.reazip.economycraft.EconomyCraft;
import com.reazip.economycraft.TollManager;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.world.level.block.BasePressurePlateBlock;

public final class EconomyCraftFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        EconomyCraft.registerEvents();
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (level.isClientSide() || !(player instanceof net.minecraft.server.level.ServerPlayer serverPlayer))
                return net.minecraft.world.InteractionResult.PASS;
            if (level.getBlockState(hit.getBlockPos()).getBlock() instanceof BasePressurePlateBlock)
                return net.minecraft.world.InteractionResult.PASS;
            String dimension = level.dimension().identifier().toString();
            TollManager.InteractionResult result = TollManager.of(level.getServer())
                    .interact(level.getServer(), serverPlayer, dimension, hit.getBlockPos());
            return result == TollManager.InteractionResult.DENIED
                    ? net.minecraft.world.InteractionResult.SUCCESS : net.minecraft.world.InteractionResult.PASS;
        });
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) -> {
            if (level.isClientSide()) return true;
            TollManager.Toll toll = TollManager.of(level.getServer())
                    .get(level.dimension().identifier().toString(), pos);
            if (toll == null || toll.owner.equals(player.getUUID().toString())) return true;
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("Only the toll owner can break this block."));
            return false;
        });
        PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) -> {
            if (!level.isClientSide()) TollManager.of(level.getServer()).broken(level.dimension().identifier().toString(), pos);
        });
        EconomyCraftFabricPermissions.install();

        if (FabricLoader.getInstance().isModLoaded("placeholder-api")) {
            EconomyCraftFabricPlaceholders.register();
        }
    }
}
