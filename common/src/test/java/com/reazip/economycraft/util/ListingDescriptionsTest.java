package com.reazip.economycraft.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one listing-description rule shared by auctions and orders. A blank description has to become
 * null (not an empty string) or the UI renders an empty lore line, and line breaks have to go or a
 * listing can forge extra chat lines.
 */
class ListingDescriptionsTest {

    @Test
    void nullStaysNull() {
        assertNull(ListingDescriptions.sanitize(null));
    }

    @Test
    void blankBecomesNull() {
        assertNull(ListingDescriptions.sanitize(""));
        assertNull(ListingDescriptions.sanitize("   \t  "));
        assertNull(ListingDescriptions.sanitize("\n\r\n"));
    }

    @Test
    void lineBreaksAreStrippedAndTextTrimmed() {
        String cleaned = ListingDescriptions.sanitize("  Line 1\nLine 2\r\n");

        assertEquals("Line 1Line 2", cleaned);
        assertFalse(cleaned.contains("\n"));
        assertFalse(cleaned.contains("\r"));
    }

    @Test
    void capsAtOneHundredChars() {
        String cleaned = ListingDescriptions.sanitize("A".repeat(120));

        assertEquals(100, cleaned.length());
        assertTrue(cleaned.chars().allMatch(c -> c == 'A'));
    }

    @Test
    void keepsExactlyOneHundredChars() {
        assertEquals(100, ListingDescriptions.sanitize("A".repeat(100)).length());
    }
}