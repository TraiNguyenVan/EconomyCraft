package com.reazip.economycraft.market;

import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The offline player-data machinery against a real temporary {@code .dat} — the half of the market
 * that has never had a test, and the only half a web listing can move an item out of.
 *
 * <p>Every test opens the file again after a write rather than trusting the return value, because the
 * thing that matters is what the server will read on its next login, not what this call believed.
 * The refusals assert byte-for-byte that the file is untouched: a refusal that rewrote a player's
 * data would be a data-loss bug wearing a "no" on its face.
 */
class OfflineInventoryTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-4000-8000-000000000053");

    @TempDir
    Path playerData;

    private RegistryAccess registries;
    private OfflineInventory inventory;
    private Path file;

    @BeforeAll
    static void bootstrap() {
        // Same order as StorePersistenceTest: the version has to be detected before anything touches
        // DataFixers, and item components have to be readable before any stack is encoded.
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(VanillaRegistries.createWorldLookup())
                .forEach(components -> components.apply());
    }

    @BeforeEach
    void setUp() throws IOException {
        registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        inventory = new OfflineInventory(playerData, registries);
        file = inventory.playerDataFile(PLAYER);
        // A player who has never logged in still has a data file once the server saves them; an empty
        // compound is the smallest honest stand-in for one.
        NbtIo.writeCompressed(new CompoundTag(), file);
    }

    /** The raw entries of the server's own {@code Inventory} list — what the server will read back. */
    private ListTag rawInventory() throws IOException {
        return NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()).getListOrEmpty("Inventory");
    }

    @Test
    @DisplayName("an added stack is readable again from the file, at a slot it names")
    void addIsVisibleToAReRead() throws IOException {
        assertTrue(inventory.add(PLAYER, new ItemStack(Items.DIAMOND, 5)));

        ItemStack readBack = inventory.readSlot(PLAYER, 0);
        assertEquals(Items.DIAMOND, readBack.getItem());
        assertEquals(5, readBack.getCount());

        List<MarketSnapshotModel.Slot> inventoryRows = new ArrayList<>();
        inventory.read(PLAYER, inventoryRows, new ArrayList<>());
        assertEquals(1, inventoryRows.size());
        assertEquals(0, inventoryRows.get(0).slot());
        assertEquals(5, inventoryRows.get(0).stack().getAsJsonObject().get("count").getAsInt());
    }

    @Test
    @DisplayName("a partial removal leaves the rest of the stack in the slot")
    void partialRemovalKeepsTheRemainder() throws IOException {
        assertTrue(inventory.add(PLAYER, new ItemStack(Items.DIAMOND, 5)));

        assertTrue(inventory.removeFromSlot(PLAYER, 0, 3));

        ItemStack left = inventory.readSlot(PLAYER, 0);
        assertEquals(Items.DIAMOND, left.getItem());
        assertEquals(2, left.getCount(), "the slot must still hold what was not taken");
        assertEquals(1, rawInventory().size(), "a partial removal replaces the entry, it does not add one");
    }

    @Test
    @DisplayName("taking the whole stack deletes the entry rather than leaving a zero-count one")
    void fullRemovalDeletesTheEntryEntirely() throws IOException {
        assertTrue(inventory.add(PLAYER, new ItemStack(Items.DIAMOND, 5)));

        assertTrue(inventory.removeFromSlot(PLAYER, 0, 5));

        assertTrue(inventory.readSlot(PLAYER, 0).isEmpty());
        // The distinction that matters: readSlots skips a zero-count entry, so one left behind would
        // read as a slot that is occupied and invisible — and the next add() would append beside it.
        assertEquals(0, rawInventory().size(), "the entry must be gone, not shrunk to zero");
        assertTrue(inventory.add(PLAYER, new ItemStack(Items.EMERALD, 1)), "the freed slot is reusable");
        assertEquals(1, rawInventory().size());
    }

    @Test
    @DisplayName("removing more than the slot holds is refused and leaves the file byte-identical")
    void removingMoreThanHeldRefusesAndTouchesNothing() throws IOException {
        assertTrue(inventory.add(PLAYER, new ItemStack(Items.DIAMOND, 5)));
        byte[] before = Files.readAllBytes(file);

        assertFalse(inventory.removeFromSlot(PLAYER, 0, 6));

        assertArrayEquals(before, Files.readAllBytes(file));
        assertEquals(5, inventory.readSlot(PLAYER, 0).getCount());
    }

    @Test
    @DisplayName("an empty slot, a slot past the main inventory and a non-positive count are all no-ops")
    void impossibleRemovalsAreNoOps() throws IOException {
        assertTrue(inventory.add(PLAYER, new ItemStack(Items.DIAMOND, 5)));

        byte[] before = Files.readAllBytes(file);
        assertFalse(inventory.removeFromSlot(PLAYER, 3, 1), "nothing is in slot 3");
        assertFalse(inventory.removeFromSlot(PLAYER, 36, 1), "armour has no offline slot here");
        assertFalse(inventory.removeFromSlot(PLAYER, -1, 1));
        assertFalse(inventory.removeFromSlot(PLAYER, 0, 0), "taking nothing is not a removal");
        assertArrayEquals(before, Files.readAllBytes(file));
        assertEquals(5, inventory.readSlot(PLAYER, 0).getCount(), "the refused slot is untouched");
    }

    @Test
    @DisplayName("a missing player file is empty, not an error, for every entry point")
    void missingFileDegradesToEmpty() {
        UUID neverJoined = UUID.fromString("00000000-0000-4000-8000-000000000099");
        OfflineInventory absent = new OfflineInventory(playerData, registries);
        Path wouldBeFile = absent.playerDataFile(neverJoined);

        assertTrue(absent.readSlot(neverJoined, 0).isEmpty());
        assertFalse(absent.removeFromSlot(neverJoined, 0, 1));
        assertFalse(absent.add(neverJoined, new ItemStack(Items.DIAMOND, 1)));
        assertFalse(Files.exists(wouldBeFile), "a refusal must not conjure a player file");
    }

    @Test
    @DisplayName("a removal re-read through a second instance sees the same file")
    void aReReadThroughANewInstanceSeesTheChange() {
        assertTrue(inventory.add(PLAYER, new ItemStack(Items.DIAMOND, 5)));
        assertTrue(inventory.removeFromSlot(PLAYER, 0, 2));

        OfflineInventory reopened = new OfflineInventory(playerData, registries);
        assertEquals(3, reopened.readSlot(PLAYER, 0).getCount(),
                "the write has to be on disk, not only in this instance");
    }

    @Test
    @DisplayName("the ender chest is read from its own key and is never written by a slot removal")
    void enderChestIsSeparate() throws IOException {
        CompoundTag tag = new CompoundTag();
        tag.put("EnderItems", new ListTag());
        NbtIo.writeCompressed(tag, file);

        assertTrue(inventory.add(PLAYER, new ItemStack(Items.DIAMOND, 5)));
        assertTrue(inventory.removeFromSlot(PLAYER, 0, 5));

        List<MarketSnapshotModel.Slot> ender = new ArrayList<>();
        inventory.read(PLAYER, new ArrayList<>(), ender);
        assertTrue(ender.isEmpty());
    }

    @Test
    @DisplayName("the main inventory is addressed in the same 36 slots the picker UI uses")
    void theBoundIsThePickersOwn() {
        assertEquals(36, OfflineInventory.MAIN_INVENTORY_SLOTS);
        assertEquals(MarketCommandArgs.MAIN_INVENTORY_SLOTS, OfflineInventory.MAIN_INVENTORY_SLOTS,
                "a slot index has to mean the same thing online and offline");
    }
}
