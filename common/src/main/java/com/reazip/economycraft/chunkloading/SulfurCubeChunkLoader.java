package com.reazip.economycraft.chunkloading;

import com.mojang.logging.LogUtils;
import com.reazip.economycraft.EconomyConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
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
 * <p>To support movement, this loader:
 * <ul>
 *   <li>Places tickets with {@code sulfur_cube_chunk_radius} (default 3), maintaining a 3×3 square of
 *       {@code ENTITY_TICKING} chunks (48×48 blocks) centered on the cube and loading chunks
 *       out to a 7×7 area.</li>
 *   <li>Preloads upcoming chunks along the movement vector, but only for chunks the current ticket
 *       radius does not already cover, and never further than twice the radius. Predicting inside the
 *       covered area would re-ticket the same chunk every tick for no benefit.</li>
 *   <li>Cleans up tickets when the cube is sheared, dies, leaves the level, changes dimension, or is
 *       picked up in a bucket.</li>
 * </ul>
 */
public final class SulfurCubeChunkLoader {
    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Fallback chunk loading radius, used while {@link EconomyConfig} is unavailable.
     *
     * <p>Radius 3 provides level 30 at center (ChunkLevel.byStatus(FULL) - 3 = 33 - 3 = 30),
     * so the center chunk and distance-1 neighbors (3×3 square) are ENTITY_TICKING (<= 31),
     * distance-2 neighbors are BLOCK_TICKING (<= 32), and distance-3 neighbors are FULL (<= 33).
     */
    public static final int DEFAULT_RADIUS = 3;
    public static final int MIN_RADIUS = 1;
    public static final int MAX_RADIUS = 6;

    public static final int DEFAULT_LOOKAHEAD_TICKS = 25;
    public static final int MAX_LOOKAHEAD_TICKS = 100;

    /** Default look-ahead trigger: 4 blocks/second. */
    public static final double DEFAULT_LOOKAHEAD_MIN_SPEED = 4.0;
    public static final double MAX_LOOKAHEAD_MIN_SPEED = 100.0;

    /** Minecraft runs 20 game ticks per second; config speeds are expressed in blocks/second. */
    private static final double TICKS_PER_SECOND = 20.0;

    private static final int FLAG_LOADING = 2;
    private static final int FLAG_SIMULATION = 4;
    private static final int FLAG_KEEP_DIMENSION_ACTIVE = 8;

    /**
     * Ticket type owned by this loader: LOADING | SIMULATION | KEEP_DIMENSION_ACTIVE, 300 tick timeout.
     *
     * <p>Deliberately unregistered and not {@code FLAG_PERSIST}:
     * <ul>
     *   <li>{@code TicketType} is a record, so its identity is the {@code (timeout, flags)} pair. Vanilla
     *       {@code PORTAL} is {@code (300, 15)} at radius 3, which is exactly what a player-created nether
     *       portal uses — sharing it means this loader's removal could strip a portal's ticket, and two
     *       cubes in one chunk would share a single ticket ({@code TicketStorage.addTicket} matches on
     *       type + level only). {@code (300, 14)} collides with no vanilla type.</li>
     *   <li>Without {@code FLAG_PERSIST} the tickets never reach {@code TicketStorage} on disk, so a crash
     *       or a missed shutdown cleanup cannot leave a chunk permanently loaded after restart.</li>
     * </ul>
     * The 300 tick timeout still bounds any ticket this loader fails to release to ~15 seconds.
     */
    public static final TicketType TICKET_TYPE =
            new TicketType(300L, FLAG_LOADING | FLAG_SIMULATION | FLAG_KEEP_DIMENSION_ACTIVE);

    /** How often {@link #onServerTick(MinecraftServer)} looks for cubes that are gone. */
    private static final int SWEEP_INTERVAL_TICKS = 20;

    /**
     * Backstop in case an entity stays in the level but stops ticking (for example a paused level).
     * Tickets also expire on their own after 300 ticks, so this only bounds the refresh loop.
     */
    private static final long IDLE_TIMEOUT_TICKS = 1200L;

    private static final Map<UUID, CubeTracker> TRACKERS = new ConcurrentHashMap<>();

    /**
     * Reference count per (level, chunk, radius) so several cubes in one chunk share a single ticket
     * without one of them releasing it while the others still need it.
     *
     * <p>Vanilla ticket identity is the ticket type plus its level, so the loader cannot give each cube
     * its own ticket; it has to own the bookkeeping itself. Touched from the server thread only.
     */
    private static final Map<TicketKey, Integer> TICKET_REFS = new HashMap<>();

    private record TicketKey(ServerLevel level, long chunkPos, int radius) {}

    /** Registers one more cube for this ticket, loading the chunk when it is the first one. */
    private static void acquireTicket(ServerChunkCache chunkCache, ServerLevel level, ChunkPos pos, int radius) {
        TicketKey key = new TicketKey(level, pos.pack(), radius);
        Integer refs = TICKET_REFS.get(key);
        if (refs == null) {
            chunkCache.addTicketAndLoadWithRadius(TICKET_TYPE, pos, radius);
            TICKET_REFS.put(key, 1);
        } else {
            chunkCache.addTicketWithRadius(TICKET_TYPE, pos, radius);
            TICKET_REFS.put(key, refs + 1);
        }
    }

    /** Drops one reference; the ticket is only removed when the last cube lets go. */
    private static void releaseTicket(ServerChunkCache chunkCache, ServerLevel level, ChunkPos pos, int radius) {
        TicketKey key = new TicketKey(level, pos.pack(), radius);
        Integer refs = TICKET_REFS.get(key);
        if (refs == null) {
            // Not ours (or already released): never remove a ticket we did not add.
            return;
        }
        if (refs <= 1) {
            TICKET_REFS.remove(key);
            chunkCache.removeTicketWithRadius(TICKET_TYPE, pos, radius);
        } else {
            TICKET_REFS.put(key, refs - 1);
        }
    }

    private static int sweepTicker;
    private static long lastCapWarningTick = -IDLE_TIMEOUT_TICKS;

    private SulfurCubeChunkLoader() {}

    /**
     * State tracker for an active Sulfur Cube chunk loader.
     */
    private static final class CubeTracker {
        final UUID entityUuid;
        final ServerLevel level;
        /** Held chunk -> radius (ticket level) used for it, so removals always match the added ticket. */
        final Map<ChunkPos, Integer> heldChunks = new HashMap<>();
        long lastTickCount;

        CubeTracker(UUID entityUuid, ServerLevel level) {
            this.entityUuid = entityUuid;
            this.level = level;
        }

        void hold(ServerChunkCache chunkCache, ChunkPos pos, int radius) {
            Integer previousRadius = heldChunks.get(pos);
            if (previousRadius == null) {
                acquireTicket(chunkCache, level, pos, radius);
                heldChunks.put(pos, radius);
                return;
            }
            if (previousRadius != radius) {
                // Radius changed at runtime: drop the old level before adding the new one, otherwise the
                // removal on shutdown no longer matches and the old ticket is left to time out.
                releaseTicket(chunkCache, level, pos, previousRadius);
                acquireTicket(chunkCache, level, pos, radius);
                heldChunks.put(pos, radius);
                return;
            }
            acquireTicket(chunkCache, level, pos, radius);
        }

        void prune(ServerChunkCache chunkCache, ChunkPos current, @Nullable ChunkPos lookAhead) {
            Iterator<Map.Entry<ChunkPos, Integer>> iter = heldChunks.entrySet().iterator();
            while (iter.hasNext()) {
                Map.Entry<ChunkPos, Integer> entry = iter.next();
                ChunkPos pos = entry.getKey();
                if (pos.equals(current) || pos.equals(lookAhead)) {
                    continue;
                }
                int distToCurrent = pos.getChessboardDistance(current);
                int distToLookAhead = lookAhead != null
                        ? pos.getChessboardDistance(lookAhead)
                        : Integer.MAX_VALUE;
                if (distToCurrent > 1 && distToLookAhead > 1) {
                    releaseTicket(chunkCache, level, pos, entry.getValue());
                    iter.remove();
                }
            }
        }

        void releaseAll() {
            ServerChunkCache chunkSource = level.getChunkSource();
            for (Map.Entry<ChunkPos, Integer> entry : heldChunks.entrySet()) {
                try {
                    releaseTicket(chunkSource, level, entry.getKey(), entry.getValue());
                } catch (Exception e) {
                    LOGGER.debug("[SulfurCubeChunkLoader] Failed to remove ticket at {}: {}",
                            entry.getKey(), e.getMessage());
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

    /** Configured chunk loading radius, clamped to the supported range. */
    public static int configuredRadius() {
        EconomyConfig cfg = EconomyConfig.get();
        if (cfg == null) return DEFAULT_RADIUS;
        return Math.clamp(cfg.sulfurCubeChunkRadius, MIN_RADIUS, MAX_RADIUS);
    }

    private static int configuredLookAheadTicks() {
        EconomyConfig cfg = EconomyConfig.get();
        if (cfg == null) return DEFAULT_LOOKAHEAD_TICKS;
        return Math.clamp(cfg.sulfurCubeChunkLookAheadTicks, 0, MAX_LOOKAHEAD_TICKS);
    }

    private static double configuredLookAheadMinSpeed() {
        EconomyConfig cfg = EconomyConfig.get();
        if (cfg == null) return DEFAULT_LOOKAHEAD_MIN_SPEED;
        return Math.clamp(cfg.sulfurCubeLookAheadMinSpeed, 0.0, MAX_LOOKAHEAD_MIN_SPEED);
    }

    private static int configuredMaxLoaders() {
        EconomyConfig cfg = EconomyConfig.get();
        return cfg == null ? 0 : Math.max(0, cfg.sulfurCubeChunkMaxLoaders);
    }

    /**
     * Invoked from {@link SulfurCube#tick()}.
     *
     * <p>Client levels are rejected first: the tracker map is static, so on a single-player or LAN-hosted
     * world a client-side tick must never touch server-side tickets from the client thread.
     */
    public static void onSulfurCubeTick(SulfurCube cube) {
        if (cube.level().isClientSide() || !(cube.level() instanceof ServerLevel serverLevel)) {
            return;
        }

        UUID uuid = cube.getUUID();

        EconomyConfig cfg = EconomyConfig.get();
        if (cfg != null && !cfg.sulfurCubeChunkLoading) {
            clearTracker(uuid);
            return;
        }

        // Dead, removed, or no longer has a body block (e.g. sheared): drop the tracker immediately.
        if (!cube.isAlive() || cube.isRemoved() || !hasBodyBlock(cube)) {
            clearTracker(uuid);
            return;
        }

        int radius = configuredRadius();
        ChunkPos currentChunk = cube.chunkPosition();
        ChunkPos lookAheadChunk = lookAheadChunk(cube, currentChunk, radius);

        CubeTracker tracker = TRACKERS.get(uuid);
        if (tracker != null && tracker.level != serverLevel) {
            // Dimension change. Chunk positions are not comparable across levels and the tracker can only
            // release tickets from the level it was created in, so release there and start over.
            tracker.releaseAll();
            TRACKERS.remove(uuid);
            tracker = null;
        }
        if (tracker == null) {
            int maxLoaders = configuredMaxLoaders();
            if (maxLoaders > 0 && TRACKERS.size() >= maxLoaders) {
                warnCapReached(serverLevel, maxLoaders);
                return;
            }
            tracker = new CubeTracker(uuid, serverLevel);
            TRACKERS.put(uuid, tracker);
        }

        tracker.lastTickCount = serverLevel.getGameTime();
        ServerChunkCache chunkCache = serverLevel.getChunkSource();

        tracker.hold(chunkCache, currentChunk, radius);
        if (lookAheadChunk != null) {
            tracker.hold(chunkCache, lookAheadChunk, radius);
        }
        tracker.prune(chunkCache, currentChunk, lookAheadChunk);
    }

    /**
     * Chunk the cube is about to enter, or {@code null} when no extra ticket is useful.
     *
     * <p>Returns {@code null} when the cube is slow, when the prediction stays inside the area the
     * current ticket already covers, and clamps the prediction to twice the radius. An unclamped
     * prediction turns one fast cube into a chunk generation request every tick, far away from where
     * the cube actually is.
     */
    @Nullable
    private static ChunkPos lookAheadChunk(SulfurCube cube, ChunkPos currentChunk, int radius) {
        int lookAheadTicks = configuredLookAheadTicks();
        if (lookAheadTicks <= 0) {
            return null;
        }

        Vec3 velocity = cube.getDeltaMovement();
        double horizontalSpeedSqr = velocity.x * velocity.x + velocity.z * velocity.z;
        double minSpeedPerTick = configuredLookAheadMinSpeed() / TICKS_PER_SECOND;
        if (horizontalSpeedSqr < minSpeedPerTick * minSpeedPerTick) {
            return null;
        }

        double maxAheadBlocks = radius * 2.0 * 16.0;
        double offsetX = velocity.x * lookAheadTicks;
        double offsetZ = velocity.z * lookAheadTicks;
        double longestAxis = Math.max(Math.abs(offsetX), Math.abs(offsetZ));
        if (longestAxis > maxAheadBlocks) {
            double scale = maxAheadBlocks / longestAxis;
            offsetX *= scale;
            offsetZ *= scale;
        }

        ChunkPos predicted = ChunkPos.containing(
                BlockPos.containing(cube.getX() + offsetX, cube.getY(), cube.getZ() + offsetZ));
        if (predicted.equals(currentChunk) || predicted.getChessboardDistance(currentChunk) <= radius) {
            return null; // already inside the loaded / ticking area
        }
        return predicted;
    }

    private static void warnCapReached(ServerLevel level, int maxLoaders) {
        long gameTime = level.getGameTime();
        if (gameTime - lastCapWarningTick < IDLE_TIMEOUT_TICKS) {
            return;
        }
        lastCapWarningTick = gameTime;
        LOGGER.warn("[SulfurCubeChunkLoader] sulfur_cube_chunk_max_loaders ({}) reached; "
                + "further sulfur cubes will not load chunks.", maxLoaders);
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
     * Periodic sweep from server tick to prune cubes that died, were removed, or unloaded while no
     * {@link #onSulfurCubeTick(SulfurCube)} ran for them.
     */
    public static void onServerTick(MinecraftServer server) {
        if (TRACKERS.isEmpty()) {
            return;
        }
        if (++sweepTicker % SWEEP_INTERVAL_TICKS != 0) {
            return;
        }

        Iterator<Map.Entry<UUID, CubeTracker>> iter = TRACKERS.entrySet().iterator();
        while (iter.hasNext()) {
            Map.Entry<UUID, CubeTracker> entry = iter.next();
            CubeTracker tracker = entry.getValue();
            Entity entity = tracker.level.getEntity(tracker.entityUuid);
            boolean gone = entity == null || !entity.isAlive() || entity.isRemoved();
            boolean idle = tracker.level.getGameTime() - tracker.lastTickCount > IDLE_TIMEOUT_TICKS;
            if (gone || idle) {
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
        TICKET_REFS.clear();
    }

    /**
     * Server stopping cleanup.
     */
    public static void onServerStopping(@Nullable MinecraftServer server) {
        clearAll();
    }

    /** For unit test assertions: returns the number of active tracked cubes. */
    public static int activeTrackerCount() {
        return TRACKERS.size();
    }

    /** For unit test assertions: returns the set of held chunk positions for a cube UUID. */
    public static Set<ChunkPos> getHeldChunks(UUID uuid) {
        CubeTracker tracker = TRACKERS.get(uuid);
        return tracker != null ? Collections.unmodifiableSet(new HashSet<>(tracker.heldChunks.keySet()))
                : Collections.emptySet();
    }
}
