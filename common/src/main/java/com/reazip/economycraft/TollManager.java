package com.reazip.economycraft;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.reazip.economycraft.db.Documents;
import com.reazip.economycraft.db.EconomyDatabase;
import com.reazip.economycraft.tax.TaxPolicy;
import com.reazip.economycraft.tax.TaxScope;
import com.reazip.economycraft.util.EconomyPaths;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Persistent player owned toll points. */
public final class TollManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type TYPE = new TypeToken<Map<String, Toll>>() {}.getType();
    private static final Map<MinecraftServer, TollManager> INSTANCES = new WeakHashMap<>();
    private final MinecraftServer server;
    private final Path file;
    private final EconomyDatabase db;
    private final Map<String, Toll> tolls = new ConcurrentHashMap<>();
    private final Map<String, Long> cooldowns = new ConcurrentHashMap<>();
    private final Map<String, Set<UUID>> pressurePlatePresent = new HashMap<>();
    private final Map<String, Set<UUID>> pressurePlateGranted = new HashMap<>();

    public static synchronized TollManager of(MinecraftServer server) {
        return INSTANCES.computeIfAbsent(server, s -> new TollManager(s, sharedDatabase(s)));
    }

    /**
     * The one {@code economycraft.db} connection, owned by {@link EconomyManager}. TollManager never
     * opens its own: a second connection to the same file is the ownership problem this avoids.
     */
    private static EconomyDatabase sharedDatabase(MinecraftServer server) {
        EconomyManager manager = EconomyCraft.peekManager(server);
        EconomyDatabase db = manager != null ? manager.getDatabase() : null;
        if (db == null) {
            throw new IllegalStateException(
                    "Toll storage needs the live EconomyManager's shared database, and there is none for this server");
        }
        return db;
    }
    private TollManager(MinecraftServer server, EconomyDatabase db) {
        this.server = server;
        this.db = db;
        file = EconomyPaths.dataDir(server).resolve("tolls.json");
        try {
            String json = Documents.read(db, file, "tolls.json");
            if (json != null) {
                Map<String, Toll> read = GSON.fromJson(json, TYPE);
                if (read != null) {
                    read.values().forEach(Toll::normalizeShares);
                    tolls.putAll(read);
                }
            }
        } catch (Exception e) { throw new IllegalStateException("Failed to load tolls at " + file, e); }
    }
    private static String key(String dimension, BlockPos pos) { return dimension + "|" + pos.getX()+","+pos.getY()+","+pos.getZ(); }
    public Toll get(String dimension, BlockPos pos) {
        for (String candidate : storageKeys(dimension, pos)) {
            Toll toll = tolls.get(candidate);
            if (toll != null) return toll;
        }
        return null;
    }
    public int count(UUID owner) { return (int)tolls.values().stream().filter(t -> t.owner.equals(owner.toString())).count(); }
    public int recipientCount(String dimension, BlockPos pos) {
        Toll toll = get(dimension, pos);
        return toll == null ? 0 : toll.shares.size();
    }
    public void put(String dimension, BlockPos pos, UUID owner, long fee) {
        BlockPos canonical = canonicalPos(dimension, pos);
        Toll previous = get(dimension, pos);
        Map<String, Integer> shares = previous != null && previous.owner.equals(owner.toString())
                ? new LinkedHashMap<>(previous.shares) : singleShare(owner);
        for (String candidate : storageKeys(dimension, pos)) tolls.remove(candidate);
        tolls.put(key(dimension, canonical), new Toll(dimension,canonical.getX(),canonical.getY(),canonical.getZ(),owner.toString(),fee,shares));
        save();
    }
    public boolean remove(String dimension, BlockPos pos, UUID owner) {
        Toll toll = get(dimension, pos);
        if (toll == null || !toll.owner.equals(owner.toString())) return false;
        for (String candidate : storageKeys(dimension, pos)) {
            tolls.remove(candidate);
            clearPressurePlateState(candidate);
        }
        save();
        return true;
    }
    public synchronized boolean transfer(String dimension, BlockPos pos, UUID currentOwner, UUID newOwner) {
        Toll toll = get(dimension, pos);
        if (toll == null || !toll.owner.equals(currentOwner.toString()) || currentOwner.equals(newOwner)) return false;
        BlockPos canonical = canonicalPos(dimension, pos);
        for (String candidate : storageKeys(dimension, pos)) tolls.remove(candidate);
        int oldShare = toll.shares.getOrDefault(currentOwner.toString(), 0);
        if (toll.shares.containsKey(newOwner.toString())) {
            toll.shares.put(newOwner.toString(), toll.shares.get(newOwner.toString()) + oldShare);
            toll.shares.remove(currentOwner.toString());
        } else {
            toll.shares.remove(currentOwner.toString());
            toll.shares.put(newOwner.toString(), oldShare);
        }
        toll.owner = newOwner.toString();
        toll.x = canonical.getX(); toll.y = canonical.getY(); toll.z = canonical.getZ();
        toll.normalizeShares();
        tolls.put(key(dimension, canonical), toll);
        save();
        return true;
    }

    public synchronized boolean updateShares(String dimension, BlockPos pos, UUID owner,
                                              Map<UUID, Integer> requested) {
        Toll toll = get(dimension, pos);
        if (toll == null || !toll.owner.equals(owner.toString())) return false;
        Map<String, Integer> next = new LinkedHashMap<>();
        for (Map.Entry<UUID, Integer> entry : requested.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getValue() < 0 || entry.getValue() > 100)
                return false;
            next.put(entry.getKey().toString(), entry.getValue());
        }
        if (!next.containsKey(owner.toString()) || next.values().stream().mapToInt(Integer::intValue).sum() != 100)
            return false;
        toll.shares = next;
        save();
        return true;
    }

    public synchronized boolean promote(String dimension, BlockPos pos, UUID currentOwner, UUID newOwner) {
        Toll toll = get(dimension, pos);
        if (toll == null || !toll.owner.equals(currentOwner.toString()) || currentOwner.equals(newOwner)
                || !toll.shares.containsKey(newOwner.toString())) return false;
        int oldShare = toll.shares.getOrDefault(currentOwner.toString(), 0);
        toll.shares.remove(currentOwner.toString());
        redistribute(toll.shares, oldShare);
        toll.owner = newOwner.toString();
        toll.normalizeShares();
        save();
        return true;
    }

    public synchronized boolean removeRecipient(String dimension, BlockPos pos, UUID owner, UUID recipient) {
        Toll toll = get(dimension, pos);
        if (toll == null || !toll.owner.equals(owner.toString()) || recipient.equals(owner)
                || !toll.shares.containsKey(recipient.toString())) return false;
        int removed = toll.shares.remove(recipient.toString());
        redistribute(toll.shares, removed);
        toll.normalizeShares();
        save();
        return true;
    }

    public Map<UUID, Integer> shares(String dimension, BlockPos pos) {
        Toll toll = get(dimension, pos);
        if (toll == null) return Map.of();
        LinkedHashMap<UUID, Integer> result = new LinkedHashMap<>();
        toll.shares.forEach((id, share) -> {
            try { result.put(UUID.fromString(id), share); } catch (IllegalArgumentException ignored) { }
        });
        return Map.copyOf(result);
    }
    public void broken(String dimension, BlockPos pos) {
        boolean changed = false;
        for (String candidate : storageKeys(dimension, pos)) {
            changed |= tolls.remove(candidate) != null;
            clearPressurePlateState(candidate);
        }
        if (changed) save();
    }

    /** Hoppers must not extract items from a toll-registered chest without a player payment. */
    public boolean blocksHopperExtraction(ServerLevel level, BlockPos hopperPos) {
        BlockPos sourcePos = hopperPos.above();
        BlockState sourceState = level.getBlockState(sourcePos);
        if (sourceState == null || !(sourceState.getBlock() instanceof ChestBlock)) return false;
        return get(level.dimension().identifier().toString(), sourcePos) != null;
    }

    private List<String> storageKeys(String dimension, BlockPos pos) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        BlockPos canonical = canonicalPos(dimension, pos);
        keys.add(key(dimension, canonical));
        keys.add(key(dimension, pos));
        BlockPos partner = connectedChest(dimension, pos);
        if (partner != null) keys.add(key(dimension, partner));
        return List.copyOf(keys);
    }

    private BlockPos canonicalPos(String dimension, BlockPos pos) {
        BlockPos partner = connectedChest(dimension, pos);
        if (partner == null) return pos;
        return comparePositions(pos, partner) <= 0 ? pos : partner;
    }

    private BlockPos connectedChest(String dimension, BlockPos pos) {
        ServerLevel level = null;
        for (ServerLevel candidate : server.getAllLevels()) {
            if (candidate.dimension().identifier().toString().equals(dimension)) {
                level = candidate;
                break;
            }
        }
        if (level == null) return null;
        BlockState state = level.getBlockState(pos);
        if (state == null || !(state.getBlock() instanceof ChestBlock)
                || state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) return null;
        BlockPos partner = ChestBlock.getConnectedBlockPos(pos, state);
        BlockState partnerState = level.getBlockState(partner);
        return partnerState != null && partnerState.getBlock() == state.getBlock() ? partner : null;
    }

    private static int comparePositions(BlockPos a, BlockPos b) {
        int x = Integer.compare(a.getX(), b.getX());
        if (x != 0) return x;
        int y = Integer.compare(a.getY(), b.getY());
        return y != 0 ? y : Integer.compare(a.getZ(), b.getZ());
    }
    public synchronized InteractionResult interact(MinecraftServer server, net.minecraft.server.level.ServerPlayer visitor, String dimension, BlockPos pos) {
        Toll toll=get(dimension,pos); if(toll==null) return InteractionResult.NOT_TOLL;
        return charge(server, visitor, dimension, pos, toll);
    }
    private InteractionResult charge(MinecraftServer server, ServerPlayer visitor, String dimension, BlockPos pos, Toll toll) {
        UUID owner=UUID.fromString(toll.owner);
        if(toll.shares.containsKey(visitor.getUUID().toString())) return InteractionResult.GRANTED;
        String cooldown=visitor.getUUID()+"|"+key(dimension,pos); long now=server.getTickCount();
        if(cooldowns.getOrDefault(cooldown,Long.MIN_VALUE)>now) { visitor.sendSystemMessage(net.minecraft.network.chat.Component.literal("This toll is on cooldown for you.")); return InteractionResult.DENIED; }
        long tax = TaxPolicy.tax(TaxScope.TOLL, toll.fee, visitor.getUUID(), EconomyCraft.getManager(server));
        if(toll.fee<=0 || toll.fee>EconomyManager.MAX-tax) { visitor.sendSystemMessage(net.minecraft.network.chat.Component.literal("This toll has an invalid fee.")); return InteractionResult.DENIED; }
        Map<UUID, Long> credits = new LinkedHashMap<>();
        long distributed = 0;
        for (Map.Entry<UUID, Integer> entry : toll.recipientShares().entrySet()) {
            long share = (toll.fee / 100) * entry.getValue()
                    + ((toll.fee % 100) * entry.getValue()) / 100;
            if (share > 0) credits.put(entry.getKey(), share);
            distributed += share;
        }
        credits.merge(owner, toll.fee - distributed, Long::sum);
        var result=EconomyCraft.getManager(server).transferMoneyMany(visitor.getUUID(), credits, toll.fee+tax,
                EconomySources.TOLL_PAYMENT, "Toll at "+dimension+" "+pos.getX()+","+pos.getY()+","+pos.getZ());
        if(!result.successful()) { visitor.sendSystemMessage(net.minecraft.network.chat.Component.literal("You don't have enough money to use this toll.")); return InteractionResult.DENIED; }
        cooldowns.put(cooldown,now+100);
        visitor.sendSystemMessage(net.minecraft.network.chat.Component.literal("Paid $"+toll.fee+" toll (tax "+EconomyCraft.formatMoney(tax)+")."));
        return InteractionResult.GRANTED;
    }

    /** Called by the Fabric pressure-plate hook while vanilla calculates a plate's signal. */
    public synchronized int pressurePlateSignal(ServerLevel level, BlockPos pos, int vanillaSignal) {
        String dimension = level.dimension().identifier().toString();
        String tollKey = key(dimension, pos);
        Toll toll = tolls.get(tollKey);
        if (toll == null) {
            clearPressurePlateState(tollKey);
            return vanillaSignal;
        }

        AABB contactArea = Block.column(14.0, 0.0, 0.5).bounds().move(pos);
        List<ServerPlayer> players = level.getEntitiesOfClass(ServerPlayer.class, contactArea,
                player -> player.isAlive() && !player.isSpectator());
        Set<UUID> current = new HashSet<>();
        for (ServerPlayer player : players) current.add(player.getUUID());

        Set<UUID> previous = pressurePlatePresent.computeIfAbsent(tollKey, ignored -> new HashSet<>());
        Set<UUID> granted = pressurePlateGranted.computeIfAbsent(tollKey, ignored -> new HashSet<>());
        previous.retainAll(current);
        granted.retainAll(current);
        for (ServerPlayer player : players) {
            UUID playerId = player.getUUID();
            if (previous.add(playerId) && charge(level.getServer(), player, dimension, pos, toll) == InteractionResult.GRANTED) {
                granted.add(playerId);
            }
        }
        if (previous.isEmpty()) clearPressurePlateState(tollKey);
        return granted.isEmpty() ? 0 : Math.max(1, vanillaSignal);
    }

    /**
     * Pressure plates do not run signal checks while they are unpowered. Sweep
     * visitors that were denied on entry anyway, so leaving (including by
     * teleport) clears their entry state and lets them retry when they return.
     */
    public synchronized void tickPressurePlateDepartures(MinecraftServer server) {
        if (pressurePlatePresent.isEmpty()) return;

        Iterator<Map.Entry<String, Set<UUID>>> plates = pressurePlatePresent.entrySet().iterator();
        while (plates.hasNext()) {
            Map.Entry<String, Set<UUID>> entry = plates.next();
            String tollKey = entry.getKey();
            Toll toll = tolls.get(tollKey);
            if (toll == null) {
                pressurePlateGranted.remove(tollKey);
                plates.remove();
                continue;
            }

            ServerLevel level = null;
            for (ServerLevel candidate : server.getAllLevels()) {
                if (candidate.dimension().identifier().toString().equals(toll.dimension)) {
                    level = candidate;
                    break;
                }
            }
            if (level == null) continue;

            BlockPos pos = new BlockPos(toll.x, toll.y, toll.z);
            AABB contactArea = Block.column(14.0, 0.0, 0.5).bounds().move(pos);
            Set<UUID> current = new HashSet<>();
            for (ServerPlayer player : level.getEntitiesOfClass(ServerPlayer.class, contactArea,
                    player -> player.isAlive() && !player.isSpectator())) {
                current.add(player.getUUID());
            }

            entry.getValue().retainAll(current);
            Set<UUID> granted = pressurePlateGranted.get(tollKey);
            if (granted != null) granted.retainAll(current);
            if (entry.getValue().isEmpty()) {
                plates.remove();
                pressurePlateGranted.remove(tollKey);
            }
        }
    }

    private void clearPressurePlateState(String key) {
        pressurePlatePresent.remove(key);
        pressurePlateGranted.remove(key);
    }
    private synchronized void save() {
        Documents.write(db, file, "tolls.json", GSON.toJson(tolls));
    }
    public static final class Toll {
        public String dimension, owner; public int x,y,z; public long fee;
        public Map<String, Integer> shares = new LinkedHashMap<>();
        public Toll(String dimension,int x,int y,int z,String owner,long fee){this(dimension,x,y,z,owner,fee,singleShare(UUID.fromString(owner)));}
        public Toll(String dimension,int x,int y,int z,String owner,long fee,Map<String,Integer> shares){this.dimension=dimension;this.x=x;this.y=y;this.z=z;this.owner=owner;this.fee=fee;this.shares=new LinkedHashMap<>(shares);normalizeShares();}
        public void normalizeShares() {
            if (shares == null) shares = new LinkedHashMap<>();
            shares.entrySet().removeIf(entry -> entry.getKey() == null || entry.getValue() == null || entry.getValue() < 0 || entry.getValue() > 100);
            if (shares.isEmpty()) shares.put(owner, 100);
            int total = shares.values().stream().mapToInt(Integer::intValue).sum();
            if (!shares.containsKey(owner) || total != 100) {
                shares.clear();
                shares.put(owner, 100);
            }
        }
        public Map<UUID, Integer> recipientShares() {
            LinkedHashMap<UUID, Integer> result = new LinkedHashMap<>();
            shares.forEach((id, share) -> {
                try { result.put(UUID.fromString(id), share); } catch (IllegalArgumentException ignored) { }
            });
            return result;
        }
    }

    private static Map<String, Integer> singleShare(UUID owner) {
        LinkedHashMap<String, Integer> shares = new LinkedHashMap<>();
        shares.put(owner.toString(), 100);
        return shares;
    }

    private static void redistribute(Map<String, Integer> shares, int amount) {
        if (amount <= 0 || shares.isEmpty()) return;
        int base = shares.values().stream().mapToInt(Integer::intValue).sum();
        if (base <= 0) {
            String first = shares.keySet().iterator().next();
            shares.put(first, shares.get(first) + amount);
            return;
        }
        int allocated = 0;
        String largest = shares.keySet().iterator().next();
        for (Map.Entry<String, Integer> entry : shares.entrySet()) {
            int extra = (int) ((long) amount * entry.getValue() / base);
            entry.setValue(entry.getValue() + extra);
            allocated += extra;
            if (entry.getValue() > shares.get(largest)) largest = entry.getKey();
        }
        shares.put(largest, shares.get(largest) + amount - allocated);
    }

    public enum InteractionResult { NOT_TOLL, GRANTED, DENIED }
}
