package com.reazip.economycraft.chunkloading;

import com.mojang.logging.LogUtils;
import com.reazip.economycraft.EconomyConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.cubemob.SulfurCube;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mobile chunk loader service for Sulfur Cubes containing swallowed blocks.
 *
 * <p>A "natural" Sulfur Cube has no body item (empty body equipment slot) and does not load
 * chunks. When a Sulfur Cube swallows or equips a block, this loader registers chunk tickets
 * around it so that chunks remain in an {@code ENTITY_TICKING} status.
 *
 * <p>To support high movement speeds (e.g. 10 blocks/sec or faster), this loader:
 * <ul>
 *   <li>Places tickets with {@link #CHUNK_RADIUS} = 3, maintaining a 3×3 square of
 *       {@code ENTITY_TICKING} chunks (48×48 blocks) centered on the cube and loading chunks
 *       out to a 7×7 area.</li>
 *   <li>Calculates forward velocity and preemptively loads upcoming chunks along the movement
 *       vector via {@link ServerChunkCache#addTicketAndLoadWithRadius} ahead of arrival.</li>
 *   <li>Gracefully expires and cleans up tickets when the cube moves away, dies, is sheared,
 *       or is picked up in a bucket.</li>
 * </ul>
 */
public final class SulfurCubeChunkLoader {
    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Chunk loading radius around the Sulfur Cube.
     *
     * <p>Radius 3 provides level 30 at center (ChunkLevel.byStatus(FULL) - 3 = 33 - 3 = 30),
     * ensuring the center chunk and distance-1 neighbors (3×3 square) are ENTITY_TICKING (<= 31),
     * distance-2 neighbors are BLOCK_TICKING (<= 32), and distance-3 neighbors are FULL (<= 33).
     */
    public static final int CHUNK_RADIUS = 3;

    /**
     * TicketType used for chunk loading. PORTAL tickets feature:
     * FLAG_PERSIST | FLAG_LOADING | FLAG_SIMULATION | FLAG_KEEP_DIMENSION_ACTIVE,
     * maintaining entity simulation and keeping the dimension active even without nearby players.
     */
    public static final TicketType TICKET_TYPE = TicketType.PORTAL;

    /**
     * Look-ahead prediction window in game ticks when moving at high speeds.
     * 25 ticks = 1.25 seconds advance loading.
     */
    public static final double LOOKAHEAD_TICKS = 25.0;

    /**
     * Minimum horizontal speed squared (blocks/tick)^2 to trigger look-ahead loading.
     * 0.04 = 0.2 blocks/tick = 4 blocks/sec.
     */
    public static final double MIN_LOOKAHEAD_SPEED_SQR = 0.04;

    private static final Map<UUID, CubeTracker> TRACKERS = new ConcurrentHashMap<>();

    private SulfurCubeChunkLoader() {}

    /**
     * State tracker for an active Sulfur Cube chunk loader.
     */
    private static final class CubeTracker {
        final UUID entityUuid;
        final ServerLevel level;
        final Set<ChunkPos> heldChunks = new HashSet<>();
        ChunkPos lastChunk;
        ChunkPos lastLookAheadChunk;
        long lastTickCount;

        CubeTracker(UUID entityUuid, ServerLevel level) {
            this.entityUuid = entityUuid;
            this.level = level;
        }

        void releaseAll() {
            ServerChunkCache chunkSource = level.getChunkSource();
            for (ChunkPos pos : heldChunks) {
                try {
                    chunkSource.removeTicketWithRadius(TICKET_TYPE, pos, CHUNK_RADIUS);
                } catch (Exception e) {
                    LOGGER.debug("[SulfurCubeChunkLoader] Failed to remove ticket at {}: {}", pos, e.getMessage());
                }
            }
            heldChunks.clear();
        }
    }

    /**
     * Checks if the Sulfur Cube holds a block item in its body slot.
     * Natural sulfur cubes without any block return {@code false}.
     */
    public static boolean hasBodyBlock(SulfurCube cube) {
        if (!cube.hasBodyItem()) {
            return false;
        }
        ItemStack bodyItem = cube.getItemBySlot(EquipmentSlot.BODY);
        return !bodyItem.isEmpty();
    }

    /**
     * Invoked from {@link SulfurCube#tick()} on the server side.
     */
    public static void onSulfurCubeTick(SulfurCube cube) {
        var cfg = EconomyConfig.get();
        if (cfg != null && !cfg.sulfurCubeChunkLoading) {
            clearTracker(cube.getUUID());
            return;
        }

        if (cube.level().isClientSide() || !(cube.level() instanceof ServerLevel serverLevel)) {
            return;
        }

        UUID uuid = cube.getUUID();

        // If dead, removed, or no longer has a body block (e.g. sheared): clean up
        if (!cube.isAlive() || cube.isRemoved() || !hasBodyBlock(cube)) {
            clearTracker(uuid);
            return;
        }

        ChunkPos currentChunk = cube.chunkPosition();
        Vec3 velocity = cube.getDeltaMovement();

        // Calculate lookahead chunk if moving fast
        ChunkPos lookAheadChunk = null;
        double horizontalSpeedSqr = velocity.x * velocity.x + velocity.z * velocity.z;
        if (horizontalSpeedSqr >= MIN_LOOKAHEAD_SPEED_SQR) {
            double targetX = cube.getX() + velocity.x * LOOKAHEAD_TICKS;
            double targetZ = cube.getZ() + velocity.z * LOOKAHEAD_TICKS;
            ChunkPos predicted = ChunkPos.containing(BlockPos.containing(targetX, cube.getY(), targetZ));
            if (!predicted.equals(currentChunk)) {
                lookAheadChunk = predicted;
            }
        }

        CubeTracker tracker = TRACKERS.computeIfAbsent(uuid, id -> new CubeTracker(id, serverLevel));
        tracker.lastTickCount = serverLevel.getGameTime();

        ServerChunkCache chunkCache = serverLevel.getChunkSource();

        // Refresh or add ticket for current chunk
        if (!tracker.heldChunks.contains(currentChunk)) {
            chunkCache.addTicketAndLoadWithRadius(TICKET_TYPE, currentChunk, CHUNK_RADIUS);
            tracker.heldChunks.add(currentChunk);
        } else {
            chunkCache.addTicketWithRadius(TICKET_TYPE, currentChunk, CHUNK_RADIUS);
        }
        tracker.lastChunk = currentChunk;

        // Advance load lookahead chunk if applicable
        if (lookAheadChunk != null) {
            if (!tracker.heldChunks.contains(lookAheadChunk)) {
                chunkCache.addTicketAndLoadWithRadius(TICKET_TYPE, lookAheadChunk, CHUNK_RADIUS);
                tracker.heldChunks.add(lookAheadChunk);
            } else {
                chunkCache.addTicketWithRadius(TICKET_TYPE, lookAheadChunk, CHUNK_RADIUS);
            }
        }
        tracker.lastLookAheadChunk = lookAheadChunk;

        // Clean up any held chunks that are more than 1 chunk away from currentChunk and lookAheadChunk
        Iterator<ChunkPos> iter = tracker.heldChunks.iterator();
        while (iter.hasNext()) {
            ChunkPos pos = iter.next();
            if (pos.equals(currentChunk) || (lookAheadChunk != null && pos.equals(lookAheadChunk))) {
                continue;
            }
            int distToCurrent = pos.getChessboardDistance(currentChunk);
            int distToLookAhead = lookAheadChunk != null
                    ? pos.getChessboardDistance(lookAheadChunk)
                    : Integer.MAX_VALUE;

            if (distToCurrent > 1 && distToLookAhead > 1) {
                chunkCache.removeTicketWithRadius(TICKET_TYPE, pos, CHUNK_RADIUS);
                iter.remove();
            }
        }
    }

    /**
     * Clears and removes any active tracker for the given entity UUID.
     */
    public static void clearTracker(UUID uuid) {
        CubeTracker tracker = TRACKERS.remove(uuid);
        if (tracker != null) {
            tracker.releaseAll();
        }
    }

    /**
     * Periodic sweep from server tick to prune stale or discarded entity trackers.
     */
    public static void onServerTick(MinecraftServer server) {
        long gameTime = server.overworld().getGameTime();
        if (gameTime % 100 != 0) return;

        Iterator<Map.Entry<UUID, CubeTracker>> iter = TRACKERS.entrySet().iterator();
        while (iter.hasNext()) {
            Map.Entry<UUID, CubeTracker> entry = iter.next();
            CubeTracker tracker = entry.getValue();
            if (gameTime - tracker.lastTickCount > 100) {
                tracker.releaseAll();
                iter.remove();
            }
        }
    }

    /**
     * Clears all active trackers and releases all held tickets.
     */
    public static void clearAll() {
        for (CubeTracker tracker : TRACKERS.values()) {
            tracker.releaseAll();
        }
        TRACKERS.clear();
    }

    /**
     * Server stopping cleanup.
     */
    public static void onServerStopping(@Nullable MinecraftServer server) {
        clearAll();
    }

    /**
     * For unit test assertions: returns the number of active tracked cubes.
     */
    public static int activeTrackerCount() {
        return TRACKERS.size();
    }

    /**
     * For unit test assertions: returns the set of held chunk positions for a cube UUID.
     */
    public static Set<ChunkPos> getHeldChunks(UUID uuid) {
        CubeTracker tracker = TRACKERS.get(uuid);
        return tracker != null ? Collections.unmodifiableSet(new HashSet<>(tracker.heldChunks)) : Collections.emptySet();
    }
}
