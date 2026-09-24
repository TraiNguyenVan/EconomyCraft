package com.reazip.economycraft;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.reazip.economycraft.util.EconomyPaths;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;

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
        tolls.remove(k); save(); return true;
    }
    public void broken(String dimension, BlockPos pos) { if(tolls.remove(key(dimension,pos))!=null) save(); }
    public synchronized boolean enter(MinecraftServer server, net.minecraft.server.level.ServerPlayer visitor, String dimension, BlockPos pos) {
        Toll toll=get(dimension,pos); if(toll==null) return false;
        UUID owner=UUID.fromString(toll.owner);
        if(owner.equals(visitor.getUUID())) return true;
        String cooldown=visitor.getUUID()+"|"+key(dimension,pos); long now=server.getTickCount();
        if(cooldowns.getOrDefault(cooldown,Long.MIN_VALUE)>now) { visitor.sendSystemMessage(net.minecraft.network.chat.Component.literal("This toll is on cooldown for you.")); return true; }
        long tax=Math.round(toll.fee*EconomyConfig.get().taxRate);
        if(toll.fee<=0 || toll.fee>EconomyManager.MAX-tax) { visitor.sendSystemMessage(net.minecraft.network.chat.Component.literal("This toll has an invalid fee.")); return true; }
        var result=EconomyCraft.getManager(server).transferMoney(visitor.getUUID(), owner, toll.fee+tax, toll.fee, EconomySources.TOLL_PAYMENT,
                "Toll at "+dimension+" "+pos.getX()+","+pos.getY()+","+pos.getZ());
        if(!result.successful()) { visitor.sendSystemMessage(net.minecraft.network.chat.Component.literal("You don't have enough money to use this toll.")); return true; }
        cooldowns.put(cooldown,now+100);
        visitor.sendSystemMessage(net.minecraft.network.chat.Component.literal("Paid $"+toll.fee+" toll (tax "+EconomyCraft.formatMoney(tax)+")."));
        return true;
    }
    private synchronized void save() {
        try { Files.writeString(file,GSON.toJson(tolls),StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING); }
        catch(IOException e) { throw new IllegalStateException("Failed to save tolls at "+file,e); }
    }
    public static final class Toll {
        public String dimension, owner; public int x,y,z; public long fee;
        public Toll(String dimension,int x,int y,int z,String owner,long fee){this.dimension=dimension;this.x=x;this.y=y;this.z=z;this.owner=owner;this.fee=fee;}
    }
}
