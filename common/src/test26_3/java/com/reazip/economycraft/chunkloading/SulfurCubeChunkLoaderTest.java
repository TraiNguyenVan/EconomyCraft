package com.reazip.economycraft.chunkloading;

import com.reazip.economycraft.EconomyConfig;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.cubemob.SulfurCube;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.TicketStorage;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class SulfurCubeChunkLoaderTest {

    private ServerLevel level;
    private ServerChunkCache chunkSource;
    private SulfurCube cube;
    private UUID cubeUuid;
    private EconomyConfig config;
    private final AtomicLong gameTime = new AtomicLong(1000L);

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
        when(level.getGameTime()).thenAnswer(invocation -> gameTime.get());

        cube = mock(SulfurCube.class);
        cubeUuid = UUID.randomUUID();
        when(cube.getUUID()).thenReturn(cubeUuid);
        when(cube.level()).thenReturn(level);
        when(cube.isAlive()).thenReturn(true);
        when(cube.isRemoved()).thenReturn(false);
        when(cube.hasBodyItem()).thenReturn(true);
        when(cube.getItemBySlot(EquipmentSlot.BODY)).thenReturn(new ItemStack(Items.DIRT));
        when(cube.getDeltaMovement()).thenReturn(Vec3.ZERO);
        when(cube.chunkPosition()).thenReturn(new ChunkPos(0, 0));

        config = EconomyConfig.get();
        config.sulfurCubeChunkLoading = true;
        config.sulfurCubeChunkRadius = SulfurCubeChunkLoader.DEFAULT_RADIUS;
        config.sulfurCubeChunkLookAheadTicks = SulfurCubeChunkLoader.DEFAULT_LOOKAHEAD_TICKS;
        config.sulfurCubeLookAheadMinSpeed = SulfurCubeChunkLoader.DEFAULT_LOOKAHEAD_MIN_SPEED;
        config.sulfurCubeChunkMaxLoaders = 0;
        config.sulfurCubeChunkIdleSeconds = SulfurCubeChunkLoader.DEFAULT_IDLE_SECONDS;
        gameTime.set(1000L);
        SulfurCubeChunkLoader.clearAll();
    }

    @AfterEach
    void tearDown() {
        SulfurCubeChunkLoader.clearAll();
    }

    /** Places the cube and advances no time, so the loader sees a stationary cube. */
    private void place(SulfurCube target, double x, double y, double z) {
        when(target.chunkPosition()).thenReturn(new ChunkPos((int) Math.floor(x) >> 4, (int) Math.floor(z) >> 4));
        when(target.position()).thenReturn(new Vec3(x, y, z));
        when(target.getY()).thenReturn(y);
    }

    private void place(double x, double y, double z) {
        place(cube, x, y, z);
    }

    private void moveTo(ChunkPos pos, double x, double z) {
        when(cube.chunkPosition()).thenReturn(pos);
        when(cube.position()).thenReturn(new Vec3(x, 64.0, z));
        when(cube.getY()).thenReturn(64.0);
    }

    private void advanceTicks(long ticks) {
        gameTime.addAndGet(ticks);
    }

    /** Builds a second body-carrying cube standing at the given position. */
    private SulfurCube newCube(double x, double y, double z) {
        SulfurCube other = mock(SulfurCube.class);
        when(other.getUUID()).thenReturn(UUID.randomUUID());
        when(other.level()).thenReturn(level);
        when(other.isAlive()).thenReturn(true);
        when(other.isRemoved()).thenReturn(false);
        when(other.hasBodyItem()).thenReturn(true);
        when(other.getItemBySlot(EquipmentSlot.BODY)).thenReturn(new ItemStack(Items.DIRT));
        when(other.getDeltaMovement()).thenReturn(Vec3.ZERO);
        place(other, x, y, z);
        return other;
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
    @DisplayName("Sulfur cube with swallowed block loads center chunk with the configured radius")
    void sulfurCubeWithBlockLoadsChunks() {
        moveTo(new ChunkPos(10, 20), 168.0, 328.0);

        assertTrue(SulfurCubeChunkLoader.hasBodyBlock(cube));

        SulfurCubeChunkLoader.onSulfurCubeTick(cube);

        assertEquals(1, SulfurCubeChunkLoader.activeTrackerCount());
        verify(chunkSource).addTicketAndLoadWithRadius(
                eq(SulfurCubeChunkLoader.TICKET_TYPE),
                eq(new ChunkPos(10, 20)),
                eq(SulfurCubeChunkLoader.DEFAULT_RADIUS)
        );

        Set<ChunkPos> held = SulfurCubeChunkLoader.getHeldChunks(cubeUuid);
        assertTrue(held.contains(new ChunkPos(10, 20)));
    }

    @Test
    @DisplayName("Tickets are refreshed with the same radius instead of being re-added")
    void steadyCubeRefreshesInsteadOfReloading() {
        moveTo(new ChunkPos(2, 2), 40.0, 40.0);

        SulfurCubeChunkLoader.onSulfurCubeTick(cube);
        SulfurCubeChunkLoader.onSulfurCubeTick(cube);

        verify(chunkSource, times(1)).addTicketAndLoadWithRadius(any(), any(), anyInt());
        verify(chunkSource, times(1)).addTicketWithRadius(
                eq(SulfurCubeChunkLoader.TICKET_TYPE), eq(new ChunkPos(2, 2)), eq(SulfurCubeChunkLoader.DEFAULT_RADIUS));
    }

    @Test
    @DisplayName("10 blocks/sec stays inside the ticket radius, so no look-ahead ticket is created")
    void fastCubeInsideCoverageDoesNotPreload() {
        // x=100 at 0.5 blocks/tick: 25 tick look-ahead lands 12.5 blocks away, inside the radius-3 coverage.
        moveTo(new ChunkPos(6, 6), 100.0, 100.0);
        when(cube.getY()).thenReturn(64.0);
        when(cube.getDeltaMovement()).thenReturn(new Vec3(0.5, 0.0, 0.0));

        SulfurCubeChunkLoader.onSulfurCubeTick(cube);

        verify(chunkSource).addTicketAndLoadWithRadius(
                eq(SulfurCubeChunkLoader.TICKET_TYPE), eq(new ChunkPos(6, 6)), eq(SulfurCubeChunkLoader.DEFAULT_RADIUS));
        assertEquals(Set.of(new ChunkPos(6, 6)), SulfurCubeChunkLoader.getHeldChunks(cubeUuid));
    }

    @Test
    @DisplayName("Look-ahead is clamped to twice the radius, not unbounded")
    void lookAheadIsClamped() {
        // 5 blocks/tick = 100 blocks/sec. Unclamped, 25 ticks predicts 125 blocks (= 8 chunks) ahead.
        moveTo(new ChunkPos(0, 0), 8.0, 8.0);
        when(cube.getY()).thenReturn(64.0);
        when(cube.getDeltaMovement()).thenReturn(new Vec3(5.0, 0.0, 0.0));

        SulfurCubeChunkLoader.onSulfurCubeTick(cube);

        Set<ChunkPos> held = SulfurCubeChunkLoader.getHeldChunks(cubeUuid);
        int maxChunk = held.stream().mapToInt(ChunkPos::x).max().orElse(0);
        assertTrue(maxChunk <= SulfurCubeChunkLoader.DEFAULT_RADIUS * 2,
                "look-ahead must stay within twice the radius, held=" + held);
        assertTrue(maxChunk > SulfurCubeChunkLoader.DEFAULT_RADIUS, "a chunk beyond coverage is still preloaded");
        assertFalse(held.contains(new ChunkPos(8, 0)), "the unclamped prediction must not be ticketed");
    }

    @Test
    @DisplayName("Shearing sulfur cube immediately releases all held chunk tickets")
    void shearingReleasesTickets() {
        moveTo(new ChunkPos(5, 5), 88.0, 88.0);

        SulfurCubeChunkLoader.onSulfurCubeTick(cube);
        assertEquals(1, SulfurCubeChunkLoader.activeTrackerCount());

        when(cube.hasBodyItem()).thenReturn(false);
        when(cube.getItemBySlot(EquipmentSlot.BODY)).thenReturn(ItemStack.EMPTY);

        SulfurCubeChunkLoader.onSulfurCubeTick(cube);

        assertEquals(0, SulfurCubeChunkLoader.activeTrackerCount());
        verify(chunkSource).removeTicketWithRadius(
                eq(SulfurCubeChunkLoader.TICKET_TYPE),
                eq(new ChunkPos(5, 5)),
                eq(SulfurCubeChunkLoader.DEFAULT_RADIUS)
        );
    }

    @Test
    @DisplayName("Old chunks left behind when moving are pruned without thrashing")
    void movingFarAwayPrunesOldTickets() {
        moveTo(new ChunkPos(0, 0), 0.0, 0.0);

        SulfurCubeChunkLoader.onSulfurCubeTick(cube);
        assertTrue(SulfurCubeChunkLoader.getHeldChunks(cubeUuid).contains(new ChunkPos(0, 0)));

        moveTo(new ChunkPos(10, 10), 160.0, 160.0);
        SulfurCubeChunkLoader.onSulfurCubeTick(cube);

        verify(chunkSource).removeTicketWithRadius(
                eq(SulfurCubeChunkLoader.TICKET_TYPE),
                eq(new ChunkPos(0, 0)),
                eq(SulfurCubeChunkLoader.DEFAULT_RADIUS)
        );

        assertTrue(SulfurCubeChunkLoader.getHeldChunks(cubeUuid).contains(new ChunkPos(10, 10)));
        assertFalse(SulfurCubeChunkLoader.getHeldChunks(cubeUuid).contains(new ChunkPos(0, 0)));
    }

    @Test
    @DisplayName("Loader tickets never collide with vanilla portal tickets")
    void ticketTypeDoesNotCollideWithVanillaPortal() {
        assertNotEquals(TicketType.PORTAL, SulfurCubeChunkLoader.TICKET_TYPE);
        assertFalse(SulfurCubeChunkLoader.TICKET_TYPE.persist(), "loader tickets must not reach the on-disk ticket store");
        assertTrue(SulfurCubeChunkLoader.TICKET_TYPE.doesLoad());
        assertTrue(SulfurCubeChunkLoader.TICKET_TYPE.doesSimulate());
        assertTrue(SulfurCubeChunkLoader.TICKET_TYPE.hasTimeout());

        TicketStorage storage = new TicketStorage();
        ChunkPos pos = new ChunkPos(3, 4);
        storage.addTicketWithRadius(TicketType.PORTAL, pos, 3);          // vanilla: entity in a portal
        storage.addTicketWithRadius(SulfurCubeChunkLoader.TICKET_TYPE, pos, 3); // loader: cube present
        assertEquals(2, storage.getTickets(pos.pack()).size(), "each owner keeps its own ticket");

        storage.removeTicketWithRadius(SulfurCubeChunkLoader.TICKET_TYPE, pos, 3); // cube leaves
        List<Ticket> left = storage.getTickets(pos.pack());
        assertEquals(1, left.size(), "the vanilla portal ticket survives");
        assertEquals(TicketType.PORTAL, left.getFirst().getType());
    }

    @Test
    @DisplayName("Two cubes in one chunk share the ticket until the last one leaves")
    void twoCubesInOneChunkKeepTheirOwnTickets() {
        moveTo(new ChunkPos(7, 7), 120.0, 120.0);
        SulfurCubeChunkLoader.onSulfurCubeTick(cube);

        SulfurCube second = mock(SulfurCube.class);
        when(second.getUUID()).thenReturn(UUID.randomUUID());
        when(second.level()).thenReturn(level);
        when(second.isAlive()).thenReturn(true);
        when(second.hasBodyItem()).thenReturn(true);
        when(second.getItemBySlot(EquipmentSlot.BODY)).thenReturn(new ItemStack(Items.DIRT));
        when(second.getDeltaMovement()).thenReturn(Vec3.ZERO);
        when(second.chunkPosition()).thenReturn(new ChunkPos(7, 7));

        SulfurCubeChunkLoader.onSulfurCubeTick(second);
        assertEquals(2, SulfurCubeChunkLoader.activeTrackerCount());

        // Cube A is sheared: cube B still needs the chunk, so the ticket stays.
        when(cube.hasBodyItem()).thenReturn(false);
        SulfurCubeChunkLoader.onSulfurCubeTick(cube);
        verify(chunkSource, never()).removeTicketWithRadius(any(), any(), anyInt());
        assertEquals(1, SulfurCubeChunkLoader.activeTrackerCount());

        // Cube B leaves/has no block either: now the last reference drops.
        when(second.hasBodyItem()).thenReturn(false);
        SulfurCubeChunkLoader.onSulfurCubeTick(second);
        verify(chunkSource).removeTicketWithRadius(
                eq(SulfurCubeChunkLoader.TICKET_TYPE), eq(new ChunkPos(7, 7)), eq(SulfurCubeChunkLoader.DEFAULT_RADIUS));
        assertEquals(0, SulfurCubeChunkLoader.activeTrackerCount());
    }

    @Test
    @DisplayName("Dimension change releases tickets in the old level and tracks the new one")
    void dimensionChangeStartsFreshTracker() {
        ServerLevel nether = mock(ServerLevel.class);
        ServerChunkCache netherSource = mock(ServerChunkCache.class);
        when(nether.getChunkSource()).thenReturn(netherSource);
        when(nether.isClientSide()).thenReturn(false);
        when(nether.getGameTime()).thenReturn(1000L);

        moveTo(new ChunkPos(0, 0), 8.0, 8.0);
        SulfurCubeChunkLoader.onSulfurCubeTick(cube);
        verify(chunkSource).addTicketAndLoadWithRadius(
                eq(SulfurCubeChunkLoader.TICKET_TYPE), eq(new ChunkPos(0, 0)), eq(SulfurCubeChunkLoader.DEFAULT_RADIUS));

        when(cube.level()).thenReturn(nether);
        moveTo(new ChunkPos(50, 50), 808.0, 808.0);
        SulfurCubeChunkLoader.onSulfurCubeTick(cube);

        // The overworld ticket is released in the overworld, not in the nether.
        verify(chunkSource).removeTicketWithRadius(
                eq(SulfurCubeChunkLoader.TICKET_TYPE), eq(new ChunkPos(0, 0)), eq(SulfurCubeChunkLoader.DEFAULT_RADIUS));
        verify(netherSource, never()).removeTicketWithRadius(any(), any(), anyInt());
        verify(netherSource).addTicketAndLoadWithRadius(
                eq(SulfurCubeChunkLoader.TICKET_TYPE), eq(new ChunkPos(50, 50)), eq(SulfurCubeChunkLoader.DEFAULT_RADIUS));

        // Shutdown cleanup must release the live nether ticket.
        SulfurCubeChunkLoader.clearAll();
        verify(netherSource).removeTicketWithRadius(
                eq(SulfurCubeChunkLoader.TICKET_TYPE), eq(new ChunkPos(50, 50)), eq(SulfurCubeChunkLoader.DEFAULT_RADIUS));
    }

    @Test
    @DisplayName("A client-side tick never touches server-side trackers")
    void clientSideTickKeepsServerTracker() {
        moveTo(new ChunkPos(1, 1), 24.0, 24.0);
        SulfurCubeChunkLoader.onSulfurCubeTick(cube);
        assertEquals(1, SulfurCubeChunkLoader.activeTrackerCount());

        config.sulfurCubeChunkLoading = false;

        ServerLevel clientLevel = mock(ServerLevel.class);
        when(clientLevel.isClientSide()).thenReturn(true);
        SulfurCube clientCube = mock(SulfurCube.class);
        when(clientCube.getUUID()).thenReturn(cubeUuid);
        when(clientCube.level()).thenReturn(clientLevel);

        SulfurCubeChunkLoader.onSulfurCubeTick(clientCube);

        assertEquals(1, SulfurCubeChunkLoader.activeTrackerCount());
        verify(chunkSource, never()).removeTicketWithRadius(any(), any(), anyInt());
    }

    @Test
    @DisplayName("Disabling the feature on the server releases every ticket")
    void toggleOffReleasesTickets() {
        moveTo(new ChunkPos(4, 4), 72.0, 72.0);
        SulfurCubeChunkLoader.onSulfurCubeTick(cube);

        config.sulfurCubeChunkLoading = false;
        SulfurCubeChunkLoader.onSulfurCubeTick(cube);

        assertEquals(0, SulfurCubeChunkLoader.activeTrackerCount());
        verify(chunkSource).removeTicketWithRadius(
                eq(SulfurCubeChunkLoader.TICKET_TYPE), eq(new ChunkPos(4, 4)), eq(SulfurCubeChunkLoader.DEFAULT_RADIUS));
    }

    @Test
    @DisplayName("Server sweep releases the tracker of a cube that is gone")
    void sweepReleasesRemovedCube() {
        moveTo(new ChunkPos(9, 9), 152.0, 152.0);
        SulfurCubeChunkLoader.onSulfurCubeTick(cube);
        assertEquals(1, SulfurCubeChunkLoader.activeTrackerCount());

        when(level.getEntity(cubeUuid)).thenReturn(null);

        MinecraftServer server = mock(MinecraftServer.class);
        for (int i = 0; i < 40; i++) {
            SulfurCubeChunkLoader.onServerTick(server);
        }

        assertEquals(0, SulfurCubeChunkLoader.activeTrackerCount());
        verify(chunkSource).removeTicketWithRadius(
                eq(SulfurCubeChunkLoader.TICKET_TYPE), eq(new ChunkPos(9, 9)), eq(SulfurCubeChunkLoader.DEFAULT_RADIUS));
    }

    @Test
    @DisplayName("Max loader cap stops new trackers but keeps existing ones")
    void maxLoaderCapStopsNewTrackers() {
        config.sulfurCubeChunkMaxLoaders = 1;
        moveTo(new ChunkPos(0, 0), 8.0, 8.0);
        SulfurCubeChunkLoader.onSulfurCubeTick(cube);
        assertEquals(1, SulfurCubeChunkLoader.activeTrackerCount());

        SulfurCube second = mock(SulfurCube.class);
        when(second.getUUID()).thenReturn(UUID.randomUUID());
        when(second.level()).thenReturn(level);
        when(second.isAlive()).thenReturn(true);
        when(second.hasBodyItem()).thenReturn(true);
        when(second.getItemBySlot(EquipmentSlot.BODY)).thenReturn(new ItemStack(Items.DIRT));
        when(second.getDeltaMovement()).thenReturn(Vec3.ZERO);
        when(second.chunkPosition()).thenReturn(new ChunkPos(40, 40));

        SulfurCubeChunkLoader.onSulfurCubeTick(second);

        assertEquals(1, SulfurCubeChunkLoader.activeTrackerCount(), "the cap is enforced");

        // The already-tracked cube keeps loading.
        SulfurCubeChunkLoader.onSulfurCubeTick(cube);
        assertEquals(1, SulfurCubeChunkLoader.activeTrackerCount());
    }

    @Test
    @DisplayName("A cube that stands still for the idle window stops loading its chunk")
    void idleCubeStopsLoading() {
        place(40.0, 64.0, 40.0);
        SulfurCubeChunkLoader.onSulfurCubeTick(cube);
        assertTrue(SulfurCubeChunkLoader.getHeldChunks(cubeUuid).contains(new ChunkPos(2, 2)));

        long idleTicks = (long) SulfurCubeChunkLoader.DEFAULT_IDLE_SECONDS * 20L;

        advanceTicks(idleTicks - 1);
        SulfurCubeChunkLoader.onSulfurCubeTick(cube);
        assertFalse(SulfurCubeChunkLoader.getHeldChunks(cubeUuid).isEmpty(), "still inside the idle window");

        advanceTicks(1);
        SulfurCubeChunkLoader.onSulfurCubeTick(cube);

        verify(chunkSource).removeTicketWithRadius(
                eq(SulfurCubeChunkLoader.TICKET_TYPE), eq(new ChunkPos(2, 2)), eq(SulfurCubeChunkLoader.DEFAULT_RADIUS));
        assertTrue(SulfurCubeChunkLoader.getHeldChunks(cubeUuid).isEmpty(), "no ticket is held while idle");
        assertEquals(1, SulfurCubeChunkLoader.activeTrackerCount(), "the cube stays tracked so it can resume");

        // Remaining idle: never re-acquire, never refresh.
        advanceTicks(10_000);
        SulfurCubeChunkLoader.onSulfurCubeTick(cube);
        SulfurCubeChunkLoader.onSulfurCubeTick(cube);
        verify(chunkSource, times(1)).addTicketAndLoadWithRadius(any(), any(), anyInt());
        verify(chunkSource, times(1)).addTicketWithRadius(any(), any(), anyInt());
        verify(chunkSource, times(1)).removeTicketWithRadius(any(), any(), anyInt());
    }

    @Test
    @DisplayName("An idle cube starts loading again as soon as it moves")
    void idleCubeResumesWhenItMoves() {
        place(40.0, 64.0, 40.0);
        SulfurCubeChunkLoader.onSulfurCubeTick(cube);

        advanceTicks((long) SulfurCubeChunkLoader.DEFAULT_IDLE_SECONDS * 20L);
        SulfurCubeChunkLoader.onSulfurCubeTick(cube);
        assertTrue(SulfurCubeChunkLoader.getHeldChunks(cubeUuid).isEmpty());

        // Pushed one block: the chunk is loaded again immediately.
        place(41.0, 64.0, 40.0);
        SulfurCubeChunkLoader.onSulfurCubeTick(cube);

        verify(chunkSource, times(2)).addTicketAndLoadWithRadius(
                eq(SulfurCubeChunkLoader.TICKET_TYPE), eq(new ChunkPos(2, 2)), eq(SulfurCubeChunkLoader.DEFAULT_RADIUS));
        assertTrue(SulfurCubeChunkLoader.getHeldChunks(cubeUuid).contains(new ChunkPos(2, 2)));
    }

    @Test
    @DisplayName("A moving cube keeps loading past the idle window")
    void movingCubeNeverGoesIdle() {
        place(40.0, 64.0, 40.0);
        SulfurCubeChunkLoader.onSulfurCubeTick(cube);

        for (int step = 1; step <= 5; step++) {
            advanceTicks((long) SulfurCubeChunkLoader.DEFAULT_IDLE_SECONDS * 20L / 2);
            place(40.0 + step, 64.0, 40.0);
            SulfurCubeChunkLoader.onSulfurCubeTick(cube);
            assertTrue(SulfurCubeChunkLoader.getHeldChunks(cubeUuid).contains(new ChunkPos(2, 2)),
                    "movement resets the idle window at step " + step);
        }
        verify(chunkSource, never()).removeTicketWithRadius(any(), any(), anyInt());
    }

    @Test
    @DisplayName("sulfur_cube_chunk_idle_seconds = 0 keeps an idle cube loading")
    void idleTimeoutCanBeDisabled() {
        config.sulfurCubeChunkIdleSeconds = 0;
        place(40.0, 64.0, 40.0);
        SulfurCubeChunkLoader.onSulfurCubeTick(cube);

        advanceTicks(100_000);
        SulfurCubeChunkLoader.onSulfurCubeTick(cube);

        verify(chunkSource, never()).removeTicketWithRadius(any(), any(), anyInt());
        assertTrue(SulfurCubeChunkLoader.getHeldChunks(cubeUuid).contains(new ChunkPos(2, 2)));
    }

    @Test
    @DisplayName("An idle cube stops counting against the loader cap")
    void idleCubeFreesTheLoaderSlot() {
        config.sulfurCubeChunkMaxLoaders = 1;
        place(40.0, 64.0, 40.0);
        SulfurCubeChunkLoader.onSulfurCubeTick(cube);

        advanceTicks((long) SulfurCubeChunkLoader.DEFAULT_IDLE_SECONDS * 20L);
        SulfurCubeChunkLoader.onSulfurCubeTick(cube);

        SulfurCube second = newCube(800.0, 64.0, 800.0);

        SulfurCubeChunkLoader.onSulfurCubeTick(second);

        assertEquals(2, SulfurCubeChunkLoader.activeTrackerCount());
        assertTrue(SulfurCubeChunkLoader.getHeldChunks(second.getUUID()).contains(new ChunkPos(50, 50)),
                "a cube that stopped loading frees its cap slot");
    }

    @Test
    @DisplayName("sulfur_cube_chunk_radius below 2 is raised, because a radius-1 ticket does not tick entities")
    void radiusBelowEntityTickingIsRaised() {
        config.sulfurCubeChunkRadius = 1;
        place(40.0, 64.0, 40.0);

        SulfurCubeChunkLoader.onSulfurCubeTick(cube);

        verify(chunkSource).addTicketAndLoadWithRadius(
                eq(SulfurCubeChunkLoader.TICKET_TYPE), eq(new ChunkPos(2, 2)),
                eq(SulfurCubeChunkLoader.MIN_RADIUS));
        assertEquals(2, SulfurCubeChunkLoader.MIN_RADIUS);
    }

    @Test
    @DisplayName("An idle cube denied a loader slot retries and resumes once a slot frees up")
    void cappedIdleCubeRetriesResume() {
        config.sulfurCubeChunkMaxLoaders = 1;
        long idleTicks = (long) SulfurCubeChunkLoader.DEFAULT_IDLE_SECONDS * 20L;

        place(40.0, 64.0, 40.0);
        SulfurCubeChunkLoader.onSulfurCubeTick(cube);
        advanceTicks(idleTicks);
        SulfurCubeChunkLoader.onSulfurCubeTick(cube);
        assertTrue(SulfurCubeChunkLoader.getHeldChunks(cubeUuid).isEmpty(), "the first cube went idle");

        SulfurCube other = newCube(800.0, 64.0, 800.0);
        SulfurCubeChunkLoader.onSulfurCubeTick(other);
        assertTrue(SulfurCubeChunkLoader.getHeldChunks(other.getUUID()).contains(new ChunkPos(50, 50)));

        // Pushed while the only slot is taken: the resume is denied for now.
        place(41.0, 64.0, 40.0);
        SulfurCubeChunkLoader.onSulfurCubeTick(cube);
        assertTrue(SulfurCubeChunkLoader.getHeldChunks(cubeUuid).isEmpty(), "no slot was free");

        // The other cube goes idle and frees the slot. The pushed cube must still remember it wants to load,
        // even though it has not moved again since.
        advanceTicks(idleTicks);
        SulfurCubeChunkLoader.onSulfurCubeTick(other);
        SulfurCubeChunkLoader.onSulfurCubeTick(cube);

        assertTrue(SulfurCubeChunkLoader.getHeldChunks(cubeUuid).contains(new ChunkPos(2, 2)),
                "the pending resume went through after the slot freed up");
    }
}
