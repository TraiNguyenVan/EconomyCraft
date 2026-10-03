package com.reazip.economycraft.faction;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.logging.LogUtils;
import com.reazip.economycraft.EconomyConfig;
import com.reazip.economycraft.EconomyManager;
import com.reazip.economycraft.util.AsyncFileWriter;
import com.reazip.economycraft.util.PermissionCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Stores per-container locks (spec line 10, D10).
 *
 * <p>Persisted in {@code data/container_locks.json}, keyed by canonical position so a double chest has one
 * lock rather than two. The mode that actually applies is <em>not</em> whatever is in this file: it is the
 * buff, then this record, then the server default, in that order — see {@link ContainerLockPolicy}, which is
 * where that rule and its consequences are written down.
 *
 * <h2>Lock modes</h2>
 * <ul>
 *   <li>{@link ContainerLockMode#UNLOCKED} — anyone can open.</li>
 *   <li>{@link ContainerLockMode#PRIVATE} — only the container owner can open.</li>
 *   <li>{@link ContainerLockMode#PARTY_ONLY} — members of the owner's party can open (spec: Communism buff).</li>
 * </ul>
 */
public final class ContainerLockStore {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type TYPE = new TypeToken<Map<String, LockEntry>>() { }.getType();

    private final Path file;
    private final Map<String, LockEntry> locks = new HashMap<>();
    private boolean dirty;

    public static final class LockEntry {
        public String dimension;
        public int x, y, z;
        public String owner;
        public ContainerLockMode mode;

        public LockEntry() {}

        public LockEntry(String dimension, int x, int y, int z, String owner, ContainerLockMode mode) {
            this.dimension = dimension;
            this.x = x;
            this.y = y;
            this.z = z;
            this.owner = owner;
            this.mode = mode;
        }
    }

    public ContainerLockStore(Path file) {
        this.file = file;
        load();
    }

    public static String key(String dimension, BlockPos pos) {
        return dimension + "|" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    public synchronized LockEntry get(String dimension, BlockPos pos, ServerLevel level) {
        BlockPos canonical = canonicalPos(level, pos);
        return locks.get(key(dimension, canonical));
    }

    /**
     * The mode that applies to one container, resolving all three of D10's inputs.
     *
     * <p>See {@link ContainerLockPolicy} for the precedence and for why it is that way round.
     */
    public synchronized ContainerLockMode effectiveMode(LockEntry entry, @Nullable UUID owner, EconomyManager eco) {
        EconomyConfig config = EconomyConfig.get();
        ContainerLockMode buff = buffModeFor(owner, eco, config);
        ContainerLockMode choice = entry == null ? null : entry.mode;
        return ContainerLockPolicy.effectiveMode(buff, choice, config.containerLock.mode);
    }

    /**
     * What the owner's faction buff grants their containers: {@code communism.container_lock_mode} for a
     * Communism member, nothing for anyone else.
     *
     * <p>An unknown owner is treated as having no buff rather than being guessed at, so a container whose
     * lock row names a player who has since left the server falls back to the owner's own choice.
     */
    private ContainerLockMode buffModeFor(@Nullable UUID owner, EconomyManager eco, EconomyConfig config) {
        if (owner == null || !config.factions.enabled || eco == null) return ContainerLockMode.UNLOCKED;
        if (eco.getFactions().factionOf(owner) != FactionId.COMMUNISM) return ContainerLockMode.UNLOCKED;
        return ContainerLockPolicy.parse(config.factions.communism.containerLockMode, ContainerLockMode.PARTY_ONLY);
    }

    public synchronized boolean canAccess(ServerPlayer player, ServerLevel level, BlockPos pos, EconomyManager eco) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null || !(be instanceof Container)) {
            // Not a container: nothing to lock. D10 makes this a Container test rather than a block list, so
            // shulkers, hoppers, barrels and modded chests are all covered by the same branch.
            return true;
        }

        String dimension = resolveDimension(level);
        LockEntry lock = get(dimension, pos, level);
        UUID owner = ownerOf(lock);

        ContainerLockMode mode = effectiveMode(lock, owner, eco);
        if (mode == ContainerLockMode.UNLOCKED) return true;

        FactionId ownerFaction = owner == null ? null : eco.getFactions().factionOf(owner);
        FactionId openerFaction = eco.getFactions().factionOf(player.getUUID());
        return ContainerLockPolicy.canOpen(mode, owner, player.getUUID(), ownerFaction, openerFaction,
                PermissionCompat.isAdmin(player));
    }

    /**
     * The lock's owner, or {@code null} when the row has no readable one.
     *
     * <p>A hand-edited or truncated save file must not crash an interaction, so an unparseable id is logged
     * and treated as no owner at all rather than propagated.
     */
    @Nullable
    public static UUID ownerOf(LockEntry entry) {
        if (entry == null || entry.owner == null) return null;
        try {
            return UUID.fromString(entry.owner);
        } catch (IllegalArgumentException ex) {
            LOGGER.warn("[EconomyCraft] A container lock has an unreadable owner id: {}", entry.owner);
            return null;
        }
    }

    private static String resolveDimension(ServerLevel level) {
        return level != null ? level.dimension().identifier().toString() : "minecraft:overworld";
    }

    public synchronized void setLock(ServerLevel level, BlockPos pos, UUID owner, ContainerLockMode mode) {
        String dimension = resolveDimension(level);
        BlockPos canonical = canonicalPos(level, pos);
        String k = key(dimension, canonical);
        locks.put(k, new LockEntry(dimension, canonical.getX(), canonical.getY(), canonical.getZ(), owner.toString(), mode));
        dirty = true;
        save();
    }

    public synchronized boolean removeLock(ServerLevel level, BlockPos pos, UUID caller, boolean admin) {
        String dimension = resolveDimension(level);
        BlockPos canonical = canonicalPos(level, pos);
        String k = key(dimension, canonical);
        LockEntry entry = locks.get(k);
        if (entry == null) return false;
        UUID owner = ownerOf(entry);
        if (!admin && (owner == null || !owner.equals(caller))) return false;
        locks.remove(k);
        dirty = true;
        save();
        return true;
    }

    public synchronized void broken(ServerLevel level, BlockPos pos) {
        String dimension = resolveDimension(level);
        BlockPos canonical = canonicalPos(level, pos);
        String k = key(dimension, canonical);
        if (locks.remove(k) != null) {
            dirty = true;
            save();
        }
    }

    public synchronized void clearAll() {
        locks.clear();
        dirty = true;
        save();
    }

    public synchronized int count() {
        return locks.size();
    }

    private BlockPos canonicalPos(ServerLevel level, BlockPos pos) {
        if (level == null) return pos;
        BlockState state = level.getBlockState(pos);
        if (state == null || !(state.getBlock() instanceof ChestBlock)
                || state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) {
            return pos;
        }
        BlockPos partner = ChestBlock.getConnectedBlockPos(pos, state);
        if (partner == null) return pos;
        BlockState partnerState = level.getBlockState(partner);
        if (partnerState == null || partnerState.getBlock() != state.getBlock()) return pos;
        return comparePositions(pos, partner) <= 0 ? pos : partner;
    }

    private static int comparePositions(BlockPos a, BlockPos b) {
        int x = Integer.compare(a.getX(), b.getX());
        if (x != 0) return x;
        int y = Integer.compare(a.getY(), b.getY());
        if (y != 0) return y;
        return Integer.compare(a.getZ(), b.getZ());
    }

    private void save() {
        if (!dirty) return;
        dirty = false;
        AsyncFileWriter.writeAsync(file, GSON.toJson(new HashMap<>(locks), TYPE));
    }

    private void load() {
        if (file == null || Files.notExists(file)) return;
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            Map<String, LockEntry> loaded = GSON.fromJson(json, TYPE);
            if (loaded != null) {
                locks.putAll(loaded);
            }
        } catch (Exception ex) {
            LOGGER.error("[EconomyCraft] Failed to read container locks from {}", file, ex);
        }
    }
}
