package com.reazip.economycraft.fabric;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import com.reazip.economycraft.EconomyCraft;
import com.reazip.economycraft.TollManager;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import com.reazip.economycraft.profession.ProfessionHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.BasePressurePlateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import java.util.LinkedHashSet;
import java.util.Set;

public final class EconomyCraftFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        EconomyCraft.registerEvents();
        ServerTickEvents.END_SERVER_TICK.register(server -> TollManager.of(server).tickPressurePlateDepartures(server));
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
            if (!(level instanceof ServerLevel serverLevel)) return true;
            TollManager manager = TollManager.of(serverLevel.getServer());
            String dimension = level.dimension().identifier().toString();
            for (BlockPos tollPos : affectedTollPositions(serverLevel, pos, state, manager, dimension, false)) {
                TollManager.Toll toll = manager.get(dimension, tollPos);
                if (toll != null && !toll.owner.equals(player.getUUID().toString())) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            "Only the toll owner can break this block or its support."));
                    return false;
                }
            }
            return true;
        });
        PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) -> {
            if (!(level instanceof ServerLevel serverLevel)) return;
            if (player instanceof ServerPlayer serverPlayer) {
                // AFTER means the break already succeeded, and `state` is the pre-break one, which is the only
                // way to know a crop was mature or a stone was an ore. Counting lives in common.
                ProfessionHooks.onBlockBroken(serverPlayer, pos, state);
            }
            TollManager manager = TollManager.of(serverLevel.getServer());
            String dimension = level.dimension().identifier().toString();
            for (BlockPos tollPos : affectedTollPositions(serverLevel, pos, state, manager, dimension, true)) {
                if (serverLevel.getBlockState(tollPos).isAir()) manager.broken(dimension, tollPos);
            }
            if (state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
                manager.broken(dimension, pos);
                manager.broken(dimension, ChestBlock.getConnectedBlockPos(pos, state));
            }
        });
        EconomyCraftFabricPermissions.install();

        if (FabricLoader.getInstance().isModLoaded("placeholder-api")) {
            EconomyCraftFabricPlaceholders.register();
        }
    }

    private static Set<BlockPos> affectedTollPositions(ServerLevel level, BlockPos brokenPos, BlockState brokenState,
                                                       TollManager manager, String dimension, boolean afterBreak) {
        Set<BlockPos> affected = new LinkedHashSet<>();
        addIfToll(affected, manager, dimension, brokenPos);

        if (brokenState.getBlock() instanceof DoorBlock) {
            for (BlockPos halfPos : new BlockPos[]{brokenPos.above(), brokenPos.below()}) {
                if (afterBreak && level.getBlockState(halfPos).isAir()) {
                    addIfToll(affected, manager, dimension, halfPos);
                } else if (level.getBlockState(halfPos).getBlock() == brokenState.getBlock()) {
                    addIfToll(affected, manager, dimension, halfPos);
                }
            }
        }

        if (brokenState.getBlock() instanceof ChestBlock
                && brokenState.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            BlockPos otherHalf = ChestBlock.getConnectedBlockPos(brokenPos, brokenState);
            addIfToll(affected, manager, dimension, otherHalf);
            if (afterBreak) affected.add(otherHalf.immutable());
        }

        BlockPos above = brokenPos.above();
        BlockState aboveState = level.getBlockState(above);
        if (afterBreak && level.getBlockState(above).isAir()) {
            addIfToll(affected, manager, dimension, above);
        } else if (aboveState.getBlock() instanceof BasePressurePlateBlock) {
            addIfToll(affected, manager, dimension, above);
        } else if (aboveState.getBlock() instanceof DoorBlock
                && aboveState.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER) {
            addIfToll(affected, manager, dimension, above);
        }

        BlockPos upperDoor = brokenPos.above(2);
        BlockState upperDoorState = level.getBlockState(upperDoor);
        if (afterBreak && level.getBlockState(upperDoor).isAir()) {
            addIfToll(affected, manager, dimension, upperDoor);
        } else if (upperDoorState.getBlock() instanceof DoorBlock
                && upperDoorState.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER) {
            addIfToll(affected, manager, dimension, upperDoor);
        }
        return affected;
    }

    private static void addIfToll(Set<BlockPos> positions, TollManager manager, String dimension, BlockPos pos) {
        if (manager.get(dimension, pos) != null) positions.add(pos.immutable());
    }
}
