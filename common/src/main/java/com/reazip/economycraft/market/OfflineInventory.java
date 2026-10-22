package com.reazip.economycraft.market;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.JsonOps;
import com.reazip.economycraft.util.ItemPickerUi;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.Container;
import net.minecraft.world.ItemStackWithSlot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;

/**
 * A player's inventory as the server stores it when they are logged out — the server's own
 * player-data NBT — plus the one item-stack encoding the {@code market} package shares and the
 * live-container read that has to produce rows of the same shape.
 *
 * <p>It is built from a {@link Path} and a registry provider rather than a {@code MinecraftServer},
 * which is the whole point: the player-data machinery, which until now had no tests at all, can be
 * exercised against a real temporary {@code .dat} file. Nothing here uses the server thread
 * directly, so the caller owns that rule.
 *
 * <p>An online player is never written to. Their authoritative inventory is in memory, so a write
 * here would be silently overwritten by their next save and the item would vanish with nothing to
 * report it. Every method that changes the file is therefore documented as requiring a player the
 * caller has already resolved as offline.
 *
 * <p>Writes go through a temp file and an atomic rename, so a crash mid-write cannot leave a
 * truncated player behind and a refusal leaves the file exactly as it was.
 */
public final class OfflineInventory {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** The player-data NBT keys, spelled as the server's own save/load spell them. */
    private static final String INVENTORY_KEY = "Inventory";
    private static final String ENDER_ITEMS_KEY = "EnderItems";
    private static final String SLOT_KEY = "Slot";
    /**
     * Slots 0–35 of a player's inventory. Armour and offhand are 36–40 live, but the server stores
     * them outside the {@code Inventory} list, so an offline player's own list can only ever name
     * these — and the same 36 slots are the ones the mod's own picker UI addresses, which is what
     * makes one slot index mean the same thing online and offline.
     */
    public static final int MAIN_INVENTORY_SLOTS = ItemPickerUi.MAIN_INVENTORY_SLOTS;

    private final Path playerDataDir;
    private final HolderLookup.Provider registries;

    public OfflineInventory(Path playerDataDir, HolderLookup.Provider registries) {
        this.playerDataDir = playerDataDir;
        this.registries = registries;
    }

    /** Where the server keeps a player's data, named as the server itself names it. */
    public Path playerDataFile(UUID player) {
        return playerDataDir.resolve(player + ".dat");
    }

    /**
     * An item stack as the market publishes it. Lives here because the slot rows are the one place
     * the offline NBT read and the live container read must agree exactly, and they both encode
     * through this. Encoding produces a detached tree, so nothing live is reachable from the result.
     */
    static JsonElement encode(HolderLookup.Provider registries, @Nullable ItemStack stack) {
        if (stack == null) return new JsonObject();
        return ItemStack.CODEC.encodeStart(RegistryOps.create(JsonOps.INSTANCE, registries), stack)
                .result()
                .orElseGet(JsonObject::new);
    }

    /**
     * Appends every non-empty slot of a live container, each naming its own container index. The
     * live counterpart of {@link #read}, kept beside it so the two readers cannot produce different
     * row shapes.
     */
    public void collectSlots(Container container, List<MarketSnapshotModel.Slot> out) {
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (!stack.isEmpty()) out.add(new MarketSnapshotModel.Slot(i, encode(registries, stack)));
        }
    }

    /**
     * Reads an offline player's inventory and ender chest out of the server's player-data NBT. A
     * missing file is not an error — it means the player has never joined this world, so both lists
     * stay empty.
     */
    public void read(UUID player, List<MarketSnapshotModel.Slot> inventory,
                     List<MarketSnapshotModel.Slot> enderChest) {
        Path file = playerDataFile(player);
        if (!Files.isRegularFile(file)) return;
        try {
            CompoundTag tag = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            readSlots(tag.getListOrEmpty(INVENTORY_KEY), inventory);
            readSlots(tag.getListOrEmpty(ENDER_ITEMS_KEY), enderChest);
        } catch (Exception e) {
            LOGGER.warn("[EconomyCraft] Could not read the offline inventory for {} from {}: {}",
                    player, file, e.toString());
        }
    }

    /**
     * The stack one slot of an offline player's inventory holds, or {@link ItemStack#EMPTY} when the
     * slot is free, the file is missing, or the entry cannot be read. A total function because a
     * caller validating a slot cannot tell those three apart and must refuse all of them the same
     * way.
     */
    public ItemStack readSlot(UUID player, int slot) {
        if (slot < 0 || slot >= MAIN_INVENTORY_SLOTS) return ItemStack.EMPTY;
        Path file = playerDataFile(player);
        if (!Files.isRegularFile(file)) return ItemStack.EMPTY;
        try {
            ListTag inventory = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap())
                    .getListOrEmpty(INVENTORY_KEY);
            for (int i = 0; i < inventory.size(); i++) {
                ItemStackWithSlot parsed = decode(inventory.getCompoundOrEmpty(i));
                if (parsed == null || parsed.stack().isEmpty()) continue;
                if (parsed.slot() == slot) return parsed.stack();
            }
        } catch (Exception e) {
            LOGGER.warn("[EconomyCraft] Could not read slot {} of the offline inventory for {} from {}: {}",
                    slot, player, file, e.toString());
        }
        return ItemStack.EMPTY;
    }

    /**
     * Appends one whole stack to an offline player's inventory, in the same player-data NBT the
     * server writes when they log out. The file is only rewritten once the stack has a slot to go
     * in, so a refusal leaves it as it was.
     *
     * <p>The caller must have resolved this player as offline on the same server-thread task. A
     * login is server-thread work too, so it cannot land in between, and the cost of being wrong is
     * somebody's inventory.
     */
    public boolean add(UUID player, ItemStack stack) {
        if (stack.isEmpty() || stack.getCount() > stack.getMaxStackSize()) return false;

        Path file = playerDataFile(player);
        if (!Files.isRegularFile(file)) return false;
        try {
            CompoundTag tag = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            Tag stored = tag.get(INVENTORY_KEY);
            if (stored != null && !(stored instanceof ListTag)) return false;
            ListTag inventory = tag.getListOrEmpty(INVENTORY_KEY);

            // The server writes only occupied slots, so a free slot is an index no entry claims. The
            // existing entries are left exactly as they are and the new one is appended: an entry this
            // build cannot read is still the player's data, and rewriting it would throw it away. The
            // slot is read as the raw byte rather than through the codec for that reason — an entry
            // that does not decode still occupies its slot, and appending over it would duplicate it.
            boolean[] occupied = new boolean[MAIN_INVENTORY_SLOTS];
            for (int i = 0; i < inventory.size(); i++) {
                int slot = inventory.getCompoundOrEmpty(i).getByteOr(SLOT_KEY, (byte) -1) & 0xFF;
                if (slot < occupied.length) occupied[slot] = true;
            }
            int free = -1;
            for (int i = 0; i < occupied.length && free < 0; i++) {
                if (!occupied[i]) free = i;
            }
            if (free < 0) return false;

            // Encoded by the server's own slot+stack codec, so the entry is byte-for-byte the shape
            // the server writes and reads: a Slot plus the item's fields beside it.
            Tag encoded = ItemStackWithSlot.CODEC
                    .encodeStart(nbtOps(registries), new ItemStackWithSlot(free, stack))
                    .result()
                    .orElse(null);
            if (!(encoded instanceof CompoundTag entry)) return false;

            inventory.add(entry);
            tag.put(INVENTORY_KEY, inventory);
            write(file, tag);
            return true;
        } catch (Exception e) {
            LOGGER.error("[EconomyCraft] Could not add an item to the offline inventory of {} ({}): {}",
                    player, file, e.toString());
            return false;
        }
    }

    /**
     * Takes {@code count} items out of one slot of an offline player's inventory — the inverse of
     * {@link #add}, and the only way an offline player can list an item.
     *
     * <p>Three outcomes, and the file is written only for the two that succeed:
     * <ul>
     *   <li>the whole entry is deleted when the whole stack is taken. Never shrunk to zero: an entry
     *       left at count 0 decodes empty, {@link #read} skips it, and the slot would read as
     *       occupied-and-invisible to anything that counts entries instead of asking them;</li>
     *   <li>the entry is re-encoded in place with the reduced count when part of the stack is
     *       taken;</li>
     *   <li>a slot that holds fewer than {@code count}, or nothing at all, is refused outright —
     *       the caller's item is still in the inventory and the file is left byte-for-byte as it was.
     * </ul>
     *
     * <p>The caller must have resolved this player as offline on the same server-thread task, for the
     * same reason {@link #add} says.
     */
    public boolean removeFromSlot(UUID player, int slot, int count) {
        if (slot < 0 || slot >= MAIN_INVENTORY_SLOTS || count < 1) return false;

        Path file = playerDataFile(player);
        if (!Files.isRegularFile(file)) return false;
        try {
            CompoundTag tag = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            Tag stored = tag.get(INVENTORY_KEY);
            if (stored != null && !(stored instanceof ListTag)) return false;
            ListTag inventory = tag.getListOrEmpty(INVENTORY_KEY);

            int index = -1;
            ItemStack held = ItemStack.EMPTY;
            for (int i = 0; i < inventory.size() && index < 0; i++) {
                ItemStackWithSlot parsed = decode(inventory.getCompoundOrEmpty(i));
                if (parsed == null || parsed.stack().isEmpty() || parsed.slot() != slot) continue;
                index = i;
                held = parsed.stack();
            }
            if (index < 0 || held.getCount() < count) return false;

            if (held.getCount() == count) {
                inventory.remove(index);
            } else {
                Tag encoded = ItemStackWithSlot.CODEC
                        .encodeStart(nbtOps(registries), new ItemStackWithSlot(slot, held.copyWithCount(held.getCount() - count)))
                        .result()
                        .orElse(null);
                if (!(encoded instanceof CompoundTag entry)) return false;
                inventory.set(index, entry);
            }
            tag.put(INVENTORY_KEY, inventory);
            write(file, tag);
            return true;
        } catch (Exception e) {
            LOGGER.error("[EconomyCraft] Could not remove {} item(s) from slot {} of the offline inventory of {} ({}): {}",
                    count, slot, player, file, e.toString());
            return false;
        }
    }

    /**
     * Parses the entries of one per-player container — each a compound of a {@code Slot} plus the item
     * — through the server's own {@code ItemStackWithSlot} codec, so the index and the stack are read
     * by the very code that writes them.
     *
     * <p>No special handling of the extra {@code Slot} key is needed, and that is not an assumption:
     * the codec feeds the whole entry, {@code Slot} and all, to {@code ItemStack.MAP_CODEC}, so the
     * server could not load a player's inventory at all if that key were rejected. An entry that does
     * not decode costs its own row and nothing else.
     */
    private void readSlots(ListTag slots, List<MarketSnapshotModel.Slot> out) {
        for (int i = 0; i < slots.size(); i++) {
            ItemStackWithSlot parsed = decode(slots.getCompoundOrEmpty(i));
            if (parsed == null || parsed.stack().isEmpty()) continue;
            out.add(new MarketSnapshotModel.Slot(parsed.slot(), encode(registries, parsed.stack())));
        }
    }

    /** One entry through the server's own codec, or null when it is empty or does not decode. */
    @Nullable
    private ItemStackWithSlot decode(CompoundTag entry) {
        if (entry.isEmpty()) return null;
        return ItemStackWithSlot.CODEC.parse(nbtOps(registries), entry).result().orElse(null);
    }

    /** Temp file plus atomic rename, shared by both writers, so neither can truncate a player. */
    private static void write(Path file, CompoundTag tag) throws IOException {
        Path temp = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
        try {
            NbtIo.writeCompressed(tag, temp);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** NBT with the registries in reach, so components resolve by id exactly as the server's loader does. */
    private static RegistryOps<Tag> nbtOps(HolderLookup.Provider registries) {
        return RegistryOps.create(NbtOps.INSTANCE, registries);
    }
}
