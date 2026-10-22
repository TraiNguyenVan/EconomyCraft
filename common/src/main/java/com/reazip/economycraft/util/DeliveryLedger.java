package com.reazip.economycraft.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class DeliveryLedger {
    private static final Logger LOGGER = LogUtils.getLogger();
    private final Map<UUID, List<Entry>> deliveries = new ConcurrentHashMap<>();

    public record Entry(UUID id, ItemStack stack) {}

    public void add(UUID player, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        List<Entry> list = deliveries.computeIfAbsent(player, k -> new ArrayList<>());
        list.add(new Entry(uniqueId(), stack));
    }

    public void clear() {
        deliveries.clear();
    }

    public List<ItemStack> get(UUID player) {
        return getEntries(player).stream().map(Entry::stack).toList();
    }

    public List<Entry> getEntries(UUID player) {
        List<Entry> list = deliveries.get(player);
        return list == null ? List.of() : List.copyOf(list);
    }

    public Entry get(UUID player, UUID deliveryId) {
        for (Entry entry : getEntries(player)) if (entry.id().equals(deliveryId)) return entry;
        return null;
    }

    public boolean remove(UUID player, ItemStack stack) {
        List<Entry> list = deliveries.get(player);
        if (list == null) return false;
        boolean removed = false;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).stack() == stack) {
                list.remove(i);
                removed = true;
                break;
            }
        }
        if (list.isEmpty()) deliveries.remove(player);
        return removed;
    }

    public boolean remove(UUID player, UUID deliveryId) {
        List<Entry> list = deliveries.get(player);
        if (list == null) return false;
        boolean removed = list.removeIf(entry -> entry.id().equals(deliveryId));
        if (list.isEmpty()) deliveries.remove(player);
        return removed;
    }

    public boolean has(UUID player) {
        List<Entry> list = deliveries.get(player);
        return list != null && !list.isEmpty();
    }

    public JsonObject save(HolderLookup.Provider provider) {
        JsonObject dObj = new JsonObject();
        for (Map.Entry<UUID, List<Entry>> e : deliveries.entrySet()) {
            JsonArray arr = new JsonArray();
            for (Entry entry : e.getValue()) {
                JsonObject o = new JsonObject();
                o.addProperty("id", entry.id().toString());
                o.add("stack", ItemStack.CODEC.encodeStart(RegistryOps.create(JsonOps.INSTANCE, provider), entry.stack()).result().orElse(new JsonObject()));
                arr.add(o);
            }
            dObj.add(e.getKey().toString(), arr);
        }
        return dObj;
    }

    public boolean load(JsonObject dObj, HolderLookup.Provider provider) {
        deliveries.clear();
        return mergeFrom(dObj, provider);
    }

    public boolean mergeFrom(JsonObject dObj, HolderLookup.Provider provider) {
        if (dObj == null) return false;
        boolean migrated = false;
        Set<UUID> ids = new HashSet<>();
        for (List<Entry> entries : deliveries.values()) {
            for (Entry entry : entries) ids.add(entry.id());
        }
        for (String key : dObj.keySet()) {
            UUID id;
            try {
                id = UUID.fromString(key);
            } catch (IllegalArgumentException ex) {
                LOGGER.error("[EconomyCraft] Dropping deliveries for invalid player id '{}'", key);
                continue;
            }

            List<Entry> list = deliveries.computeIfAbsent(id, k -> new ArrayList<>());
            try {
                for (var sEl : dObj.getAsJsonArray(key)) {
                    try {
                        JsonObject o = sEl.getAsJsonObject();
                        if (!o.has("stack")) {
                            LOGGER.error("[EconomyCraft] Dropping a delivery for {} with no 'stack' field", key);
                            continue;
                        }
                        ItemStack stack = ItemStack.CODEC.parse(RegistryOps.create(JsonOps.INSTANCE, provider), o.get("stack")).result().orElse(ItemStack.EMPTY);
                        if (stack.isEmpty()) {
                            LOGGER.error("[EconomyCraft] Dropping a delivery for {} with an unreadable item", key);
                            continue;
                        }
                        UUID deliveryId = parseId(o);
                        if (deliveryId == null || !ids.add(deliveryId)) {
                            deliveryId = uniqueId(ids);
                            ids.add(deliveryId);
                            migrated = true;
                        }
                        list.add(new Entry(deliveryId, stack));
                    } catch (Exception ex) {
                        LOGGER.error("[EconomyCraft] Dropping an unreadable delivery entry for {}", key, ex);
                    }
                }
            } catch (Exception ex) {
                LOGGER.error("[EconomyCraft] Failed to read deliveries for {}", key, ex);
            }
        }
        return migrated;
    }

    private static UUID parseId(JsonObject entry) {
        if (!entry.has("id") || !entry.get("id").isJsonPrimitive()) return null;
        try {
            return UUID.fromString(entry.get("id").getAsString());
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    private UUID uniqueId() {
        Set<UUID> ids = new HashSet<>();
        for (List<Entry> entries : deliveries.values()) {
            for (Entry entry : entries) ids.add(entry.id());
        }
        return uniqueId(ids);
    }

    private static UUID uniqueId(Set<UUID> ids) {
        UUID id;
        do {
            id = UUID.randomUUID();
        } while (ids.contains(id));
        return id;
    }
}
