package com.reazip.economycraft.contracts;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;

/**
 * Renders a contract as a written-book view: plain strings packed into page-sized
 * chunks, then stamped onto a {@link Items#WRITTEN_BOOK} stack the server
 * force-opens via {@code ServerPlayer.openItemGui}. The viewer keeps nothing;
 * a chat button opens the actions chest once the book is closed.
 */
public final class ContractBook {
    private ContractBook() {}

    // shortcut: fixed line/char budget instead of real font metrics; upgrade if text overflows visually.
    static final int MAX_LINES_PER_PAGE = 9;
    static final int MAX_CHARS_PER_PAGE = 240;
    static final int MAX_PAGES = 100;

    /** Packs lines into book pages without splitting a line across pages. */
    public static List<String> paginate(List<String> lines) {
        List<String> pages = new ArrayList<>();
        StringBuilder page = new StringBuilder();
        int lineCount = 0;
        for (String line : lines) {
            String text = line == null ? "" : line;
            boolean overflow = lineCount >= MAX_LINES_PER_PAGE
                    || (lineCount > 0 && page.length() + text.length() + 1 > MAX_CHARS_PER_PAGE);
            if (overflow) {
                pages.add(page.toString());
                page.setLength(0);
                lineCount = 0;
            }
            if (lineCount > 0) page.append('\n');
            page.append(text);
            lineCount++;
        }
        if (lineCount > 0) pages.add(page.toString());
        if (pages.size() > MAX_PAGES) return new ArrayList<>(pages.subList(0, MAX_PAGES));
        return pages;
    }

    /** Stamps pages onto a read-only written-book stack (generation 0, original). */
    public static ItemStack makeWrittenBook(List<Component> pages, String title, String author) {
        String cleanTitle = title == null ? "" : title;
        if (cleanTitle.length() > WrittenBookContent.TITLE_MAX_LENGTH) {
            cleanTitle = cleanTitle.substring(0, WrittenBookContent.TITLE_MAX_LENGTH);
        }
        List<Component> capped = pages.size() > MAX_PAGES
                ? new ArrayList<>(pages.subList(0, MAX_PAGES))
                : pages;
        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        book.set(DataComponents.WRITTEN_BOOK_CONTENT,
                new WrittenBookContent(Filterable.passThrough(cleanTitle),
                        author == null ? "" : author, 0,
                        capped.stream().map(Filterable::passThrough).toList(), false));
        return book;
    }
}
