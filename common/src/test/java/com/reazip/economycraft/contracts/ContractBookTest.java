package com.reazip.economycraft.contracts;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.reazip.economycraft.util.BookInputUi;
import com.reazip.economycraft.util.ClickKind;

import net.minecraft.world.item.component.WritableBookContent;

/** Book layout, text packing, and click routing for the contract written-book view. */
class ContractBookTest {

    @Test
    @DisplayName("wrap keeps every line within the book width")
    void wrapWidth() {
        List<String> lines = ContractBook.wrap("Build a castle wall around the northern keep courtyard");
        for (String line : lines) {
            assertTrue(line.length() <= ContractBook.WRAP_WIDTH, () -> "too long: " + line);
        }
        assertEquals("Build a castle wall", lines.get(0));
    }

    @Test
    @DisplayName("wrap hard-splits words longer than a line")
    void wrapLongWord() {
        assertEquals(List.of("supercalifragilisti", "c"), ContractBook.wrap("supercalifragilistic"));
    }

    @Test
    @DisplayName("wrap keeps paragraph breaks as blank lines")
    void wrapParagraphs() {
        assertEquals(List.of("first", "", "second"), ContractBook.wrap("first\n\nsecond"));
    }

    @Test
    @DisplayName("sanitizeMultiline collapses blank runs, drops blank input, and caps length")
    void sanitizeMultiline() {
        assertEquals("a\n\nb", Contract.sanitizeMultiline("a\n\r\n\n\nb", 100));
        assertNull(Contract.sanitizeMultiline("   \n  ", 100));
        assertEquals(50, Contract.sanitizeMultiline("x".repeat(80), 50).length());
    }

    @Test
    @DisplayName("packPages splits at paragraph boundaries within the edit-page limit")
    void packPages() {
        List<String> pages = BookInputUi.packPages("a\n\n" + "b".repeat(1100));
        assertEquals(3, pages.size());
        assertTrue(pages.get(0).startsWith("a"));
        for (String page : pages) {
            assertTrue(page.length() <= WritableBookContent.PAGE_EDIT_LENGTH, () -> "page too long: " + page.length());
        }
    }

    @Test
    @DisplayName("packPages keeps a short text on one page")
    void packPagesShort() {
        assertEquals(List.of("just a note"), BookInputUi.packPages("just a note"));
        assertTrue(BookInputUi.packPages("   ").isEmpty());
    }

    @Test
    @DisplayName("each section starts on a fresh page and long bodies continue without the header")
    void renderSections() {
        List<ContractBook.Section> sections = List.of(
                new ContractBook.Section(null, List.of(ContractBook.Line.of("cover line"))),
                new ContractBook.Section("The Work", paragraphs(3, 10)));
        List<net.minecraft.network.chat.Component> pages = ContractBook.render(sections);
        assertEquals(4, pages.size());
        assertTrue(pages.get(0).getString().contains("cover line"));
        assertTrue(pages.get(1).getString().startsWith("The Work"));
        assertFalse(pages.get(2).getString().contains("The Work"));
    }

    @Test
    @DisplayName("left-click opens actions, right-click reads")
    void clickRouting() {
        assertTrue(ContractsUi.isActionsClick(0, ClickKind.PICKUP));
        assertFalse(ContractsUi.isActionsClick(1, ClickKind.PICKUP));
        assertFalse(ContractsUi.isActionsClick(0, ClickKind.QUICK_MOVE));
        assertTrue(ContractsUi.isReadClick(1, ClickKind.PICKUP));
        assertFalse(ContractsUi.isReadClick(0, ClickKind.PICKUP));
    }

    private static List<ContractBook.Line> paragraphs(int count, int linesPerParagraph) {
        List<ContractBook.Line> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            if (i > 0) out.add(ContractBook.Line.of(""));
            for (int j = 0; j < linesPerParagraph; j++) out.add(ContractBook.Line.of("line " + j));
        }
        return out;
    }
}
