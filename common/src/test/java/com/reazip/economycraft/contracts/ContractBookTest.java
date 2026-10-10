package com.reazip.economycraft.contracts;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Page-packing for the contract written-book view and its actions handoff. */
class ContractBookTest {

    @Test
    @DisplayName("empty input yields no pages")
    void empty() {
        assertTrue(ContractBook.paginate(List.of()).isEmpty());
    }

    @Test
    @DisplayName("short contracts fit on one page")
    void singlePage() {
        List<String> pages = ContractBook.paginate(List.of("#1 Fix the wall", "Reward: 100"));
        assertEquals(1, pages.size());
        assertEquals("#1 Fix the wall\nReward: 100", pages.get(0));
    }

    @Test
    @DisplayName("lines spill onto a second page past the line budget")
    void lineBudget() {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 10; i++) lines.add("line " + i);
        List<String> pages = ContractBook.paginate(lines);
        assertEquals(2, pages.size());
        assertEquals(9, pages.get(0).split("\n").length);
        assertEquals("line 9", pages.get(1));
    }

    @Test
    @DisplayName("a long line starts a fresh page instead of overflowing")
    void charBudget() {
        String filler = "x".repeat(250);
        List<String> pages = ContractBook.paginate(List.of("short", filler));
        assertEquals(2, pages.size());
        assertEquals("short", pages.get(0));
        assertEquals(filler, pages.get(1));
    }

    @Test
    @DisplayName("output never exceeds the vanilla page cap")
    void pageCap() {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 1000; i++) lines.add("line " + i);
        assertEquals(100, ContractBook.paginate(lines).size());
    }

    @Test
    @DisplayName("book handoff names the contract and offers the actions chest")
    void actionsHandoff() {
        String text = ContractsUi.handoffMessage(5).getString();
        assertTrue(text.contains("Contract #5"));
        assertTrue(text.contains("[View actions]"));
    }
}
