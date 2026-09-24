package com.reazip.economycraft;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.reazip.economycraft.util.EconomyPaths;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.block.Block;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Persistent player owned toll points. */
public final class TollManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type TYPE = new TypeToken<Map<String, Toll>>() {}.getType();
    private static final Map<MinecraftServer, TollManager> INSTANCES = new WeakHashMap<>();
    private final Path file;
    private final Map<String, Toll> tolls = new ConcurrentHashMap<>();
    private final Map<String, Long> cooldowns = new ConcurrentHashMap<>();
    private final Map<String, Set<UUID>> pressurePlatePresent = new HashMap<>();
    private final Map<String, Set<UUID>> pressurePlateGranted = new HashMap<>();

    public static synchronized TollManager of(MinecraftServer server) {
        return INSTANCES.computeIfAbsent(server, TollManager::new);
    }
    private TollManager(MinecraftServer server) {
        file = EconomyPaths.dataDir(server).resolve("tolls.json");
        try {
            if (Files.isRegularFile(file)) {
                Map<String, Toll> read = GSON.fromJson(Files.readString(file), TYPE);
                if (read != null) tolls.putAll(read);
            }
        } catch (Exception e) { throw new IllegalStateException("Failed to load tolls at " + file, e); }
    }
    private static String key(String dimension, BlockPos pos) { return dimension + "|" + pos.getX()+","+pos.getY()+","+pos.getZ(); }
    public Toll get(String dimension, BlockPos pos) { return tolls.get(key(dimension,pos)); }
    public int count(UUID owner) { return (int)tolls.values().stream().filter(t -> t.owner.equals(owner.toString())).count(); }
    public void put(String dimension, BlockPos pos, UUID owner, long fee) { tolls.put(key(dimension,pos), new Toll(dimension,pos.getX(),pos.getY(),pos.getZ(),owner.toString(),fee)); save(); }
    public boolean remove(String dimension, BlockPos pos, UUID owner) {
        String k=key(dimension,pos); Toll t=tolls.get(k); if(t==null || !t.owner.equals(owner.toString())) return false;
        tolls.remove(k); clearPressurePlateState(k); save(); return true;
    }
    public synchronized boolean transfer(String dimension, BlockPos pos, UUID currentOwner, UUID newOwner) {
        String k=key(dimension,pos); Toll t=tolls.get(k);
        if(t==null || !t.owner.equals(currentOwner.toString()) || currentOwner.equals(newOwner)) return false;
        t.owner=newOwner.toString(); save(); return true;
    }
    public void broken(String dimension, BlockPos pos) {
        String k = key(dimension, pos);
        if (tolls.remove(k) != null) { clearPressurePlateState(k); save(); }
    }
    public synchronized InteractionResult interact(MinecraftServer server, net.minecraft.server.level.ServerPlayer visitor, String dimension, BlockPos pos) {
        Toll toll=get(dimension,pos); if(toll==null) return InteractionResult.NOT_TOLL;
        return charge(server, visitor, dimension, pos, toll);
    }
    private InteractionResult charge(MinecraftServer server, ServerPlayer visitor, String dimension, BlockPos pos, Toll toll) {
        UUID owner=UUID.fromString(toll.owner);
        if(owner.equals(visitor.getUUID())) return InteractionResult.GRANTED;
        String cooldown=visitor.getUUID()+"|"+key(dimension,pos); long now=server.getTickCount();
        if(cooldowns.getOrDefault(cooldown,Long.MIN_VALUE)>now) { visitor.sendSystemMessage(net.minecraft.network.chat.Component.literal("This toll is on cooldown for you.")); return InteractionResult.DENIED; }
        long tax=Math.round(toll.fee*EconomyConfig.get().taxRate);
        if(toll.fee<=0 || toll.fee>EconomyManager.MAX-tax) { visitor.sendSystemMessage(net.minecraft.network.chat.Component.literal("This toll has an invalid fee.")); return InteractionResult.DENIED; }
        var result=EconomyCraft.getManager(server).transferMoney(visitor.getUUID(), owner, toll.fee+tax, toll.fee, EconomySources.TOLL_PAYMENT,
                "Toll at "+dimension+" "+pos.getX()+","+pos.getY()+","+pos.getZ());
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

    private void clearPressurePlateState(String key) {
        pressurePlatePresent.remove(key);
        pressurePlateGranted.remove(key);
    }
    private synchronized void save() {
        try { Files.writeString(file,GSON.toJson(tolls),StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING); }
        catch(IOException e) { throw new IllegalStateException("Failed to save tolls at "+file,e); }
    }
    public static final class Toll {
        public String dimension, owner; public int x,y,z; public long fee;
        public Toll(String dimension,int x,int y,int z,String owner,long fee){this.dimension=dimension;this.x=x;this.y=y;this.z=z;this.owner=owner;this.fee=fee;}
    }

    public enum InteractionResult { NOT_TOLL, GRANTED, DENIED }
}
