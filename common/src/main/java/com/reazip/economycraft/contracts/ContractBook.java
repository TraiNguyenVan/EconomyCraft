package com.reazip.economycraft.contracts;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;

import org.jetbrains.annotations.Nullable;

/**
 * Lays contract text out as written-book pages. The layout is deliberate: the cover page carries
 * identity first and the money and time facts after it, then every section (the work, submitted
 * work, feedback, dispute, resolution) starts on a fresh page, with continuation pages when a
 * body runs long — a section's header is never split from its body and two sections never share
 * a page.
 *
 * <p>Lines are pre-wrapped at {@link #WRAP_WIDTH} chars — the worst-case glyph advance that still
 * fits the vanilla book screen's 114px text area — so the client never re-wraps a line and the
 * {@link #LINES_PER_PAGE} budget never clips mid-sentence.
 */
public final class ContractBook {
    private ContractBook() {}

    /** Worst-case chars per line that still fit the 114px book text area (6px advance each). */
    static final int WRAP_WIDTH = 19;
    /** Lines per page; the vanilla screen physically fits 14, so 13 leaves slack. */
    static final int LINES_PER_PAGE = 13;
    static final int MAX_PAGES = 100;

    /** One styled line of book text. */
    public record Line(String text, ChatFormatting color, boolean bold) {
        public static Line of(String text) {
            return new Line(text, ChatFormatting.BLACK, false);
        }

        public static Line header(String text) {
            return new Line(text, ChatFormatting.DARK_BLUE, true);
        }
    }

    /** A titled block of lines; a section always starts on a fresh page. */
    public record Section(@Nullable String header, List<Line> lines) {}

    /**
     * Lays sections out one per fresh page: a headerless section is the cover, the rest get a
     * styled header, a blank line, then their body; a body longer than one page continues on
     * headerless pages.
     */
    public static List<Component> render(List<Section> sections) {
        List<Component> pages = new ArrayList<>();
        for (Section section : sections) {
            List<Line> lines = new ArrayList<>();
            if (section.header() != null) {
                lines.add(Line.header(section.header()));
                lines.add(Line.of(""));
            }
            lines.addAll(section.lines());
            for (int start = 0; start < lines.size(); start += LINES_PER_PAGE) {
                pages.add(page(lines.subList(start, Math.min(lines.size(), start + LINES_PER_PAGE))));
            }
        }
        if (pages.isEmpty()) pages.add(page(List.of()));
        return pages;
    }

    private static Component page(List<Line> lines) {
        MutableComponent page = Component.literal("");
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) page.append(Component.literal("\n"));
            Line line = lines.get(i);
            page.append(Component.literal(line.text()).withStyle(s -> s
                    .withColor(line.color() == null ? ChatFormatting.BLACK : line.color())
                    .withBold(line.bold())));
        }
        return page;
    }

    /** Word-wraps text into lines of at most {@link #WRAP_WIDTH} chars, hard-splitting longer words. */
    public static List<String> wrap(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        for (String paragraph : text.split("\n", -1)) {
            if (paragraph.isBlank()) {
                out.add("");
                continue;
            }
            StringBuilder line = new StringBuilder();
            for (String word : paragraph.trim().split("\\s+")) {
                while (word.length() > WRAP_WIDTH) {
                    if (line.length() > 0) {
                        out.add(line.toString());
                        line.setLength(0);
                    }
                    out.add(word.substring(0, WRAP_WIDTH));
                    word = word.substring(WRAP_WIDTH);
                }
                if (line.length() == 0) {
                    line.append(word);
                } else if (line.length() + 1 + word.length() <= WRAP_WIDTH) {
                    line.append(' ').append(word);
                } else {
                    out.add(line.toString());
                    line.setLength(0);
                    line.append(word);
                }
            }
            if (line.length() > 0) out.add(line.toString());
        }
        return out;
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
