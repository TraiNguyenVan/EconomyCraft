package com.reazip.economycraft.fabric;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import com.reazip.economycraft.EconomyCraft;
import com.reazip.economycraft.TollManager;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;

public final class EconomyCraftFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        EconomyCraft.registerEvents();
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (level.isClientSide() || !(player instanceof net.minecraft.server.level.ServerPlayer serverPlayer))
                return net.minecraft.world.InteractionResult.PASS;
            String dimension = level.dimension().identifier().toString();
            return TollManager.of(level.getServer()).enter(level.getServer(), serverPlayer, dimension, hit.getBlockPos())
                    ? net.minecraft.world.InteractionResult.SUCCESS : net.minecraft.world.InteractionResult.PASS;
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
