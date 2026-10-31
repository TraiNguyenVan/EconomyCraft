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
 *   <li>Stops loading when the cube has not moved for {@code sulfur_cube_chunk_idle_seconds} (default
 *       120), so a parked cube does not hold chunks forever. The cube keeps being tracked and starts
 *       loading again the moment it moves; nearby players keep the area loaded as usual.</li>
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

    /**
     * Smallest radius that keeps the cube itself ticking: a ticket of radius {@code r} gives the center
     * chunk level {@code 33 - r}, and entities only tick at level 31 or lower, so radius 1 (level 32) is
     * block-ticking only. A cube in such a chunk would stop ticking, stop refreshing its ticket, and the
     * chunk would unload after the ticket expires.
     */
    public static final int MIN_RADIUS = 2;
    public static final int MAX_RADIUS = 6;

    public static final int DEFAULT_LOOKAHEAD_TICKS = 25;
    public static final int MAX_LOOKAHEAD_TICKS = 100;

    /**
     * Default idle window: a cube that has not moved for this long stops loading chunks and starts
     * again as soon as it moves. {@code 0} keeps loading as long as the cube is alive.
     */
    public static final int DEFAULT_IDLE_SECONDS = 120;
    public static final int MAX_IDLE_SECONDS = 86_400;

    /** Distance that counts as movement, so a cube resting on the ground is not "moving" by tiny nudges. */
    private static final double MOVE_EPSILON_SQR = 0.0625 * 0.0625;

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

    /** Keeps an existing ticket alive without changing the reference count. */
    private static void refreshTicket(ServerChunkCache chunkCache, ChunkPos pos, int radius) {
        chunkCache.addTicketWithRadius(TICKET_TYPE, pos, radius);
    }

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
        /** Position the cube was last seen at, used to decide whether it still moves. */
        Vec3 lastPosition;
        /** Position from the immediate previous tick, used exclusively to compute per-tick displacement speed. */
        Vec3 previousTickPosition;
        long lastMoveTick;
        /** False while an idle cube has released its tickets and waits to move again. */
        boolean loading = true;

        CubeTracker(UUID entityUuid, ServerLevel level, Vec3 position, long gameTime) {
            this.entityUuid = entityUuid;
            this.level = level;
            this.lastPosition = position;
            this.previousTickPosition = position;
            this.lastMoveTick = gameTime;
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
            // Already held by this cube: refresh the ticket without touching the shared reference count.
            refreshTicket(chunkCache, pos, radius);
        }

        void prune(ServerChunkCache chunkCache, ChunkPos current, List<ChunkPos> lookAhead) {
            Iterator<Map.Entry<ChunkPos, Integer>> iter = heldChunks.entrySet().iterator();
            while (iter.hasNext()) {
                Map.Entry<ChunkPos, Integer> entry = iter.next();
                ChunkPos pos = entry.getKey();
                if (pos.equals(current) || lookAhead.contains(pos)) {
                    continue;
                }
                int distToCurrent = pos.getChessboardDistance(current);
                boolean nearLookAhead = false;
                for (ChunkPos ahead : lookAhead) {
                    if (pos.getChessboardDistance(ahead) <= 1) {
                        nearLookAhead = true;
                        break;
                    }
                }
                if (distToCurrent > 1 && !nearLookAhead) {
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

    /** Ticks a cube may stand still before it stops loading chunks; {@code 0} disables the stop. */
    private static long configuredIdleTicks() {
        EconomyConfig cfg = EconomyConfig.get();
        int seconds = cfg == null ? DEFAULT_IDLE_SECONDS : Math.clamp(cfg.sulfurCubeChunkIdleSeconds, 0, MAX_IDLE_SECONDS);
        return (long) (seconds * TICKS_PER_SECOND);
    }

    /** Number of cubes currently holding chunk tickets (idle but tracked cubes do not count). */
    private static int loadingTrackerCount() {
        int count = 0;
        for (CubeTracker tracker : TRACKERS.values()) {
            if (tracker.loading) {
                count++;
            }
        }
        return count;
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
        long gameTime = serverLevel.getGameTime();
        Vec3 position = cube.position();
        ChunkPos currentChunk = cube.chunkPosition();

        CubeTracker tracker = TRACKERS.get(uuid);
        if (tracker != null && tracker.level != serverLevel) {
            // Dimension change. Chunk positions are not comparable across levels and the tracker can only
            // release tickets from the level it was created in, so release there and start over.
            tracker.releaseAll();
            TRACKERS.remove(uuid);
            tracker = null;
        }

        boolean moved = tracker == null || position.distanceToSqr(tracker.lastPosition) > MOVE_EPSILON_SQR;
        boolean resuming = tracker != null && moved && !tracker.loading;

        if (tracker == null || resuming) {
            int maxLoaders = configuredMaxLoaders();
            // TRACKERS.size() is a cheap pre-check: the loading count can only reach the cap when there are
            // at least that many trackers, so the scan below is skipped in the common uncapped case.
            if (maxLoaders > 0 && TRACKERS.size() >= maxLoaders && loadingTrackerCount() >= maxLoaders) {
                warnCapReached(serverLevel, maxLoaders);
                if (tracker == null) {
                    return;
                }
                // Idle cube that cannot resume yet: keep it tracked and keep it idle, but leave
                // lastPosition alone so the cube keeps asking for a slot on every later tick. Recording the
                // position here would make the cube look stationary again and it would never retry.
                tracker.lastTickCount = gameTime;
                return;
            }
            if (tracker == null) {
                tracker = new CubeTracker(uuid, serverLevel, position, gameTime);
                TRACKERS.put(uuid, tracker);
            } else {
                tracker.loading = true;
            }
        }

        Vec3 effectiveVelocity = resolveEffectiveVelocity(cube, position, tracker != null ? tracker.previousTickPosition : null);
        if (tracker != null) {
            tracker.previousTickPosition = position;
        }

        tracker.lastTickCount = gameTime;
        if (moved) {
            tracker.lastPosition = position;
            tracker.lastMoveTick = gameTime;
        }

        // A cube that has not moved for the idle window stops holding its chunk. Nearby players keep the
        // area loaded as usual, and the tracker stays alive so the cube loads chunks again when it moves.
        long idleTicks = configuredIdleTicks();
        if (tracker.loading && idleTicks > 0 && gameTime - tracker.lastMoveTick >= idleTicks) {
            tracker.releaseAll();
            tracker.loading = false;
            LOGGER.debug("[SulfurCubeChunkLoader] {} stopped loading chunks after {} ticks without movement.",
                    uuid, idleTicks);
            return;
        }
        if (!tracker.loading) {
            return;
        }

        List<ChunkPos> lookAhead = lookAheadChunks(position, currentChunk, radius, effectiveVelocity);
        ServerChunkCache chunkCache = serverLevel.getChunkSource();

        tracker.hold(chunkCache, currentChunk, radius);
        for (ChunkPos ahead : lookAhead) {
            tracker.hold(chunkCache, ahead, radius);
        }
        tracker.prune(chunkCache, currentChunk, lookAhead);
    }

    private static Vec3 resolveEffectiveVelocity(SulfurCube cube, Vec3 position, @Nullable Vec3 previousPosition) {
        Vec3 velocity = cube.getDeltaMovement();
        if (cube.isPassenger()) {
            Entity root = cube.getRootVehicle();
            if (root != null) {
                Vec3 vehicleVel = root.getDeltaMovement();
                if (vehicleVel.horizontalDistanceSqr() > velocity.horizontalDistanceSqr()) {
                    velocity = vehicleVel;
                }
            }
        }
        if (previousPosition != null) {
            Vec3 displacement = position.subtract(previousPosition);
            if (displacement.horizontalDistanceSqr() > velocity.horizontalDistanceSqr()) {
                velocity = displacement;
            }
        }
        return velocity;
    }

    /**
     * Chunks along the cube's forward movement vector that are outside the current chunk's
     * entity-ticking coverage (entity-ticking radius is {@code radius - 2}, because a ticket of
     * radius {@code r} only provides level 31 or lower up to distance {@code r - 2}).
     *
     * <p>Preloading tickets are placed along the path so that fast movement up to 30 blocks/sec
     * or higher always encounters chunks that are already in the {@code ENTITY_TICKING} state.
     */
    private static List<ChunkPos> lookAheadChunks(Vec3 position, ChunkPos currentChunk, int radius, Vec3 velocity) {
        int lookAheadTicks = configuredLookAheadTicks();
        if (lookAheadTicks <= 0) {
            return Collections.emptyList();
        }

        double horizontalSpeedSqr = velocity.x * velocity.x + velocity.z * velocity.z;
        double minSpeedPerTick = configuredLookAheadMinSpeed() / TICKS_PER_SECOND;
        if (horizontalSpeedSqr < minSpeedPerTick * minSpeedPerTick) {
            return Collections.emptyList();
        }

        double horizontalSpeed = Math.sqrt(horizontalSpeedSqr);
        if (horizontalSpeed <= 1e-6) {
            return Collections.emptyList();
        }

        double maxAheadBlocks = radius * 2.0 * 16.0;
        double aheadBlocks = Math.min(horizontalSpeed * lookAheadTicks, maxAheadBlocks);

        int entityTickingRadius = Math.max(0, radius - 2);
        List<ChunkPos> chunks = new ArrayList<>();

        double unitX = velocity.x / horizontalSpeed;
        double unitZ = velocity.z / horizontalSpeed;

        for (double dist = 16.0; dist <= aheadBlocks; dist += 16.0) {
            double sampleX = position.x + unitX * dist;
            double sampleZ = position.z + unitZ * dist;
            ChunkPos pos = ChunkPos.containing(BlockPos.containing(sampleX, position.y, sampleZ));
            if (!pos.equals(currentChunk) && pos.getChessboardDistance(currentChunk) > entityTickingRadius && !chunks.contains(pos)) {
                chunks.add(pos);
            }
        }

        double endX = position.x + unitX * aheadBlocks;
        double endZ = position.z + unitZ * aheadBlocks;
        ChunkPos endPos = ChunkPos.containing(BlockPos.containing(endX, position.y, endZ));
        if (!endPos.equals(currentChunk) && endPos.getChessboardDistance(currentChunk) > entityTickingRadius && !chunks.contains(endPos)) {
            chunks.add(endPos);
        }

        return chunks;
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
