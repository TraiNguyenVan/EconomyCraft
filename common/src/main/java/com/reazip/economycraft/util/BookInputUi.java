package com.reazip.economycraft.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WritableBookContent;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.util.Prediction;

import org.jetbrains.annotations.Nullable;

/**
 * Free-text input through a real book and quill. The server plants a marked writable book in the
 * player's main hand and watches their inventory each tick. Vanilla's edit-book handling rebuilds
 * the {@code WritableBookContent} component on every Done press — even unchanged — so a component
 * that is no longer the planted instance means the player pressed Done; a freshly signed book
 * means the same. The edit screen sends no close event (the same client blind spot as the reading
 * screen), so dropping the book is the player's cancel.
 *
 * <p>The stashed original item is always restored: on harvest it replaces the book, on abort and
 * on disconnect it goes back to the book's slot, the main hand, or the inventory. Text cleaning is
 * the caller's job — {@link #onConfirm} hands over the raw pages joined with newlines.
 */
public final class BookInputUi {
    private BookInputUi() {}

    /** How long an unattended input stays armed before the book is reclaimed. */
    private static final long TIMEOUT_MS = 15 * 60_000L;

    private static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();

    private static final class Pending {
        final ItemStack original;
        /** The exact component instance planted — any Done press rebuilds it, which is the signal. */
        final WritableBookContent planted;
        final String bookName;
        /** Written books already in the inventory at plant time, so a freshly signed one is new. */
        final List<List<String>> existingWritten;
        final long plantedAt;
        final BiConsumer<ServerPlayer, String> onConfirm;
        final Consumer<ServerPlayer> onAbort;

        Pending(ItemStack original, WritableBookContent planted, String bookName,
                List<List<String>> existingWritten, long plantedAt,
                BiConsumer<ServerPlayer, String> onConfirm, Consumer<ServerPlayer> onAbort) {
            this.original = original;
            this.planted = planted;
            this.bookName = bookName;
            this.existingWritten = existingWritten;
            this.plantedAt = plantedAt;
            this.onConfirm = onConfirm;
            this.onAbort = onAbort;
        }
    }

    /**
     * Plants a marked writable book in the player's main hand and arms the watcher.
     * {@code onConfirm} receives the written pages joined with newlines (blank = skipped);
     * {@code onAbort} fires when the book is lost or the window expires.
     */
    public static void open(ServerPlayer player, String bookName, String initialText, String prompt,
                            BiConsumer<ServerPlayer, String> onConfirm, Consumer<ServerPlayer> onAbort) {
        Pending prior = PENDING.remove(player.getUUID());
        if (prior != null) restoreItem(player, prior, true);
        if (player.containerMenu != player.inventoryMenu) player.closeContainer();
        List<String> pages = packPages(initialText);
        if (pages.isEmpty()) pages = List.of("");   // one empty page so the first Done still lands
        WritableBookContent content = new WritableBookContent(
                pages.stream().map(Filterable::passThrough).toList());
        ItemStack book = new ItemStack(Items.WRITABLE_BOOK);
        book.set(DataComponents.CUSTOM_NAME, Component.literal(bookName));
        book.set(DataComponents.WRITABLE_BOOK_CONTENT, content);
        ItemStack original = player.getMainHandItem().copy();
        PENDING.put(player.getUUID(), new Pending(original, content, bookName, snapshotWritten(player),
                System.currentTimeMillis(), onConfirm, onAbort));
        player.setItemInHand(InteractionHand.MAIN_HAND, book);
        player.inventoryMenu.broadcastChanges();
        player.sendSystemMessage(Component.literal(bookName).withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
        player.sendSystemMessage(Component.literal(prompt).withStyle(ChatFormatting.GRAY));
        player.sendSystemMessage(Component.literal(
                "Press Done when finished; leave it empty to skip. Drop the book (Q) to cancel.")
                .withStyle(ChatFormatting.GRAY));
    }

    /**
     * Packs text into book-and-quill pages of at most {@link WritableBookContent#PAGE_EDIT_LENGTH}
     * chars, breaking at paragraph boundaries; a paragraph longer than a page is hard-split.
     */
    public static List<String> packPages(String text) {
        List<String> pages = new ArrayList<>();
        if (text == null || text.isBlank()) return pages;
        int limit = WritableBookContent.PAGE_EDIT_LENGTH;
        StringBuilder page = new StringBuilder();
        for (String paragraph : text.split("\n", -1)) {
            if (page.length() > 0 && page.length() + 1 + paragraph.length() > limit) {
                addPage(pages, page);
            }
            if (page.length() > 0) page.append('\n');
            page.append(paragraph);
            while (page.length() > limit) {
                pages.add(page.substring(0, limit));
                page.delete(0, limit);
            }
        }
        addPage(pages, page);
        return pages;
    }

    private static void addPage(List<String> pages, StringBuilder page) {
        String s = page.toString().stripTrailing();
        if (!s.isEmpty()) pages.add(s);
        page.setLength(0);
    }

    /** Per-tick watch: harvest when the book changed, abort when it is gone or timed out. */
    public static void tick(MinecraftServer server) {
        if (PENDING.isEmpty()) return;
        for (Map.Entry<UUID, Pending> entry : PENDING.entrySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) continue;   // the quit hook restores and removes
            Pending pending = entry.getValue();
            int bookSlot = findMarkedBook(player, pending.bookName);
            if (bookSlot >= 0) {
                WritableBookContent content = player.getInventory().getItem(bookSlot)
                        .get(DataComponents.WRITABLE_BOOK_CONTENT);
                if (content != null && content != pending.planted) {
                    // Done pressed — finish regardless of the clock.
                    harvest(player, entry.getKey(), pending, bookSlot, joinWritable(content));
                    continue;
                }
            } else {
                int signed = findSignedBook(player, pending);
                if (signed >= 0) {
                    harvest(player, entry.getKey(), pending, signed,
                            joinWritten(player.getInventory().getItem(signed)
                                    .get(DataComponents.WRITTEN_BOOK_CONTENT)));
                    continue;
                }
            }
            if (bookSlot < 0 || System.currentTimeMillis() - pending.plantedAt > TIMEOUT_MS) {
                reclaim(player, entry.getKey(), pending);
            }
        }
    }

    /** Reclaims the book and restores the stashed item when a player disconnects mid-input. */
    public static void forget(ServerPlayer player) {
        Pending pending = PENDING.remove(player.getUUID());
        if (pending != null) restoreItem(player, pending, false);
    }

    private static void harvest(ServerPlayer player, UUID id, Pending pending, int slot, String raw) {
        player.getInventory().setItem(slot, pending.original);
        player.inventoryMenu.broadcastChanges();
        PENDING.remove(id, pending);
        EconomySounds.page(player);
        player.sendSystemMessage(Component.literal(raw.isBlank() ? "No details added." : "Details saved.")
                .withStyle(ChatFormatting.GREEN));
        pending.onConfirm.accept(player, raw);
    }

    private static void reclaim(ServerPlayer player, UUID id, Pending pending) {
        restoreItem(player, pending, true);
        PENDING.remove(id, pending);
        EconomySounds.failure(player);
        player.sendSystemMessage(Component.literal("Details input cancelled.").withStyle(ChatFormatting.RED));
        pending.onAbort.accept(player);
    }

    /** Puts the stashed item back where the book is, or in the hand, inventory, or at their feet. */
    private static void restoreItem(ServerPlayer player, Pending pending, boolean sync) {
        int slot = findMarkedBook(player, pending.bookName);
        if (slot >= 0) {
            player.getInventory().setItem(slot, pending.original);
        } else if (player.getMainHandItem().isEmpty()) {
            player.setItemInHand(InteractionHand.MAIN_HAND, pending.original);
        } else if (!player.getInventory().add(pending.original)) {
            player.drop(pending.original, false, Prediction.SERVER_ONLY);
        }
        if (sync) player.inventoryMenu.broadcastChanges();
    }

    private static int findMarkedBook(ServerPlayer player, String bookName) {
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.is(Items.WRITABLE_BOOK) && stack.getHoverName().getString().equals(bookName)) {
                return i;
            }
        }
        return -1;
    }

    /** The slot of a written book that was not in the inventory when the input started. */
    private static int findSignedBook(ServerPlayer player, Pending pending) {
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            WrittenBookContent content = inv.getItem(i).get(DataComponents.WRITTEN_BOOK_CONTENT);
            if (content == null) continue;
            if (!pending.existingWritten.contains(writtenPages(content))) return i;
        }
        return -1;
    }

    private static List<List<String>> snapshotWritten(ServerPlayer player) {
        List<List<String>> out = new ArrayList<>();
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            WrittenBookContent content = inv.getItem(i).get(DataComponents.WRITTEN_BOOK_CONTENT);
            if (content != null) out.add(writtenPages(content));
        }
        return out;
    }

    private static List<String> writtenPages(WrittenBookContent content) {
        List<String> pages = new ArrayList<>();
        for (Filterable<Component> page : content.pages()) pages.add(page.raw().getString());
        return pages;
    }

    private static String joinWritable(WritableBookContent content) {
        List<String> raw = new ArrayList<>();
        for (Filterable<String> page : content.pages()) raw.add(page.raw());
        return String.join("\n", raw);
    }

    private static String joinWritten(@Nullable WrittenBookContent content) {
        return content == null ? "" : String.join("\n", writtenPages(content));
    }
}
