package com.reazip.economycraft.chunkloading;

import com.reazip.economycraft.EconomyConfig;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.cubemob.SulfurCube;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class SulfurCubeChunkLoaderTest {

    private ServerLevel level;
    private ServerChunkCache chunkSource;
    private SulfurCube cube;
    private UUID cubeUuid;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(VanillaRegistries.createWorldLookup())
                .forEach(components -> components.apply());
    }

    @BeforeEach
    void setUp() {
        level = mock(ServerLevel.class);
        chunkSource = mock(ServerChunkCache.class);
        when(level.getChunkSource()).thenReturn(chunkSource);
        when(level.isClientSide()).thenReturn(false);
        when(level.getGameTime()).thenReturn(1000L);

        cube = mock(SulfurCube.class);
        cubeUuid = UUID.randomUUID();
        when(cube.getUUID()).thenReturn(cubeUuid);
        when(cube.level()).thenReturn(level);
        when(cube.isAlive()).thenReturn(true);
        when(cube.isRemoved()).thenReturn(false);

        // Ensure EconomyConfig is initialized with chunk loading enabled
        EconomyConfig cfg = new EconomyConfig();
        cfg.sulfurCubeChunkLoading = true;
        // Reset any leftover trackers
        SulfurCubeChunkLoader.clearAll();
    }

    @Test
    @DisplayName("Natural sulfur cube without body block does not load chunks")
    void naturalSulfurCubeDoesNotLoadChunks() {
        when(cube.hasBodyItem()).thenReturn(false);
        when(cube.getItemBySlot(EquipmentSlot.BODY)).thenReturn(ItemStack.EMPTY);

        assertFalse(SulfurCubeChunkLoader.hasBodyBlock(cube));

        SulfurCubeChunkLoader.onSulfurCubeTick(cube);

        assertEquals(0, SulfurCubeChunkLoader.activeTrackerCount());
        verify(chunkSource, never()).addTicketAndLoadWithRadius(any(), any(), anyInt());
        verify(chunkSource, never()).addTicketWithRadius(any(), any(), anyInt());
    }

    @Test
    @DisplayName("Sulfur cube with swallowed block loads center chunk with radius 3")
    void sulfurCubeWithBlockLoadsChunks() {
        when(cube.hasBodyItem()).thenReturn(true);
        when(cube.getItemBySlot(EquipmentSlot.BODY)).thenReturn(new ItemStack(Items.DIRT));
        when(cube.chunkPosition()).thenReturn(new ChunkPos(10, 20));
        when(cube.getDeltaMovement()).thenReturn(Vec3.ZERO);

        assertTrue(SulfurCubeChunkLoader.hasBodyBlock(cube));

        SulfurCubeChunkLoader.onSulfurCubeTick(cube);

        assertEquals(1, SulfurCubeChunkLoader.activeTrackerCount());
        verify(chunkSource).addTicketAndLoadWithRadius(
                eq(SulfurCubeChunkLoader.TICKET_TYPE),
                eq(new ChunkPos(10, 20)),
                eq(SulfurCubeChunkLoader.CHUNK_RADIUS)
        );

        Set<ChunkPos> held = SulfurCubeChunkLoader.getHeldChunks(cubeUuid);
        assertTrue(held.contains(new ChunkPos(10, 20)));
    }

    @Test
    @DisplayName("Fast moving sulfur cube (10 blocks/sec = 0.5 blocks/tick) preloads lookahead chunk")
    void fastMovingCubePreloadsAhead() {
        when(cube.hasBodyItem()).thenReturn(true);
        when(cube.getItemBySlot(EquipmentSlot.BODY)).thenReturn(new ItemStack(Items.DIRT));

        // Position: x=100, y=64, z=100 -> Chunk (6, 6)
        when(cube.getX()).thenReturn(100.0);
        when(cube.getY()).thenReturn(64.0);
        when(cube.getZ()).thenReturn(100.0);
        when(cube.chunkPosition()).thenReturn(new ChunkPos(6, 6));

        // Moving at 10 blocks/sec in +X direction: dx = 0.5 blocks/tick
        // Lookahead 25 ticks: targetX = 100 + 0.5 * 25 = 112.5 -> Block 112 -> Chunk 7 (112 >> 4 = 7)
        when(cube.getDeltaMovement()).thenReturn(new Vec3(0.5, 0.0, 0.0));

        SulfurCubeChunkLoader.onSulfurCubeTick(cube);

        // Center chunk (6, 6) loaded
        verify(chunkSource).addTicketAndLoadWithRadius(
                eq(SulfurCubeChunkLoader.TICKET_TYPE),
                eq(new ChunkPos(6, 6)),
                eq(SulfurCubeChunkLoader.CHUNK_RADIUS)
        );

        // Lookahead chunk (7, 6) preloaded ahead
        verify(chunkSource).addTicketAndLoadWithRadius(
                eq(SulfurCubeChunkLoader.TICKET_TYPE),
                eq(new ChunkPos(7, 6)),
                eq(SulfurCubeChunkLoader.CHUNK_RADIUS)
        );

        Set<ChunkPos> held = SulfurCubeChunkLoader.getHeldChunks(cubeUuid);
        assertTrue(held.contains(new ChunkPos(6, 6)));
        assertTrue(held.contains(new ChunkPos(7, 6)));
    }

    @Test
    @DisplayName("Shearing sulfur cube immediately releases all held chunk tickets")
    void shearingReleasesTickets() {
        when(cube.hasBodyItem()).thenReturn(true);
        when(cube.getItemBySlot(EquipmentSlot.BODY)).thenReturn(new ItemStack(Items.DIRT));
        when(cube.chunkPosition()).thenReturn(new ChunkPos(5, 5));
        when(cube.getDeltaMovement()).thenReturn(Vec3.ZERO);

        SulfurCubeChunkLoader.onSulfurCubeTick(cube);
        assertEquals(1, SulfurCubeChunkLoader.activeTrackerCount());

        // Now sheared: block removed
        when(cube.hasBodyItem()).thenReturn(false);
        when(cube.getItemBySlot(EquipmentSlot.BODY)).thenReturn(ItemStack.EMPTY);

        SulfurCubeChunkLoader.onSulfurCubeTick(cube);

        assertEquals(0, SulfurCubeChunkLoader.activeTrackerCount());
        verify(chunkSource).removeTicketWithRadius(
                eq(SulfurCubeChunkLoader.TICKET_TYPE),
                eq(new ChunkPos(5, 5)),
                eq(SulfurCubeChunkLoader.CHUNK_RADIUS)
        );
    }

    @Test
    @DisplayName("Old chunks left behind when moving are pruned without thrashing")
    void movingFarAwayPrunesOldTickets() {
        when(cube.hasBodyItem()).thenReturn(true);
        when(cube.getItemBySlot(EquipmentSlot.BODY)).thenReturn(new ItemStack(Items.DIRT));
        when(cube.getX()).thenReturn(0.0);
        when(cube.getY()).thenReturn(64.0);
        when(cube.getZ()).thenReturn(0.0);
        when(cube.chunkPosition()).thenReturn(new ChunkPos(0, 0));
        when(cube.getDeltaMovement()).thenReturn(Vec3.ZERO);

        SulfurCubeChunkLoader.onSulfurCubeTick(cube);
        assertTrue(SulfurCubeChunkLoader.getHeldChunks(cubeUuid).contains(new ChunkPos(0, 0)));

        // Move to chunk (10, 10), which is > 1 chunk away
        when(cube.chunkPosition()).thenReturn(new ChunkPos(10, 10));
        when(cube.getX()).thenReturn(160.0);
        when(cube.getZ()).thenReturn(160.0);

        SulfurCubeChunkLoader.onSulfurCubeTick(cube);

        // Old chunk (0, 0) should be removed
        verify(chunkSource).removeTicketWithRadius(
                eq(SulfurCubeChunkLoader.TICKET_TYPE),
                eq(new ChunkPos(0, 0)),
                eq(SulfurCubeChunkLoader.CHUNK_RADIUS)
        );

        // New chunk (10, 10) is now held
        assertTrue(SulfurCubeChunkLoader.getHeldChunks(cubeUuid).contains(new ChunkPos(10, 10)));
        assertFalse(SulfurCubeChunkLoader.getHeldChunks(cubeUuid).contains(new ChunkPos(0, 0)));
    }
}
