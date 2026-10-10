package com.reazip.economycraft.util;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AmpersandFormatTest {

    @Test
    @DisplayName("containsCodes correctly identifies format codes")
    void containsCodesDetection() {
        assertFalse(AmpersandFormat.containsCodes(null));
        assertFalse(AmpersandFormat.containsCodes(""));
        assertFalse(AmpersandFormat.containsCodes("hello world"));
        assertFalse(AmpersandFormat.containsCodes("cat & dog")); // standalone &
        assertFalse(AmpersandFormat.containsCodes("&z unknown")); // unsupported letter

        assertTrue(AmpersandFormat.containsCodes("&1hello"));
        assertTrue(AmpersandFormat.containsCodes("&aGreen"));
        assertTrue(AmpersandFormat.containsCodes("&lBold"));
        assertTrue(AmpersandFormat.containsCodes("&sDiamond")); // Bedrock custom
        assertTrue(AmpersandFormat.containsCodes("&rReset"));
    }

    @Test
    @DisplayName("Bedrock scoping: color applies until space or EOL")
    void colorUntilSpaceOrEol() {
        Component comp = AmpersandFormat.parse("&1Hello world");
        List<Component> parts = comp.toFlatList();

        // Should have "Hello" styled dark blue, followed by " world" with no special color
        assertEquals("Hello world", comp.getString());
        assertFalse(comp.getString().contains("&1"));

        // Find part with text "Hello"
        Component helloPart = parts.stream().filter(c -> "Hello".equals(c.getString())).findFirst().orElseThrow();
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.DARK_BLUE), helloPart.getStyle().getColor());

        // Find part with text "world"
        Component worldPart = parts.stream().filter(c -> "world".equals(c.getString())).findFirst().orElseThrow();
        assertNull(worldPart.getStyle().getColor(), "Word after space should revert to default color");
    }

    @Test
    @DisplayName("Multiple codes on a word combine (color + bold)")
    void codeChaining() {
        Component comp = AmpersandFormat.parse("&1&lBoldBlue normal");
        assertEquals("BoldBlue normal", comp.getString());

        Component styledPart = comp.toFlatList().stream()
                .filter(c -> "BoldBlue".equals(c.getString()))
                .findFirst()
                .orElseThrow();

        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.DARK_BLUE), styledPart.getStyle().getColor());
        assertTrue(styledPart.getStyle().isBold());

        Component normalPart = comp.toFlatList().stream()
                .filter(c -> "normal".equals(c.getString()))
                .findFirst()
                .orElseThrow();

        assertNull(normalPart.getStyle().getColor());
        assertFalse(normalPart.getStyle().isBold());
    }

    @Test
    @DisplayName("toLegacyString converts codes with §r at space/EOL boundaries")
    void toLegacyStringConversion() {
        assertEquals("", AmpersandFormat.toLegacyString(null));
        assertEquals("", AmpersandFormat.toLegacyString(""));
        assertEquals("plain text", AmpersandFormat.toLegacyString("plain text"));
        assertEquals("cat & dog", AmpersandFormat.toLegacyString("cat & dog"));

        assertEquals("\u00A71Hello\u00A7r world", AmpersandFormat.toLegacyString("&1Hello world"));
        assertEquals("\u00A71\u00A7lHello\u00A7r world", AmpersandFormat.toLegacyString("&1&lHello world"));
        assertEquals("\u00A71Hello\u00A7r \u00A72World\u00A7r", AmpersandFormat.toLegacyString("&1Hello &2World"));
        assertEquals("\u00A7cEnd\u00A7r", AmpersandFormat.toLegacyString("&cEnd"));
    }

    @Test
    @DisplayName("Bedrock custom materials work (&s diamond, &q emerald)")
    void bedrockCustomMaterials() {
        Component comp = AmpersandFormat.parse("&sShiny");
        assertEquals("Shiny", comp.getString());

        Component part = comp.toFlatList().stream().filter(c -> "Shiny".equals(c.getString())).findFirst().orElseThrow();
        assertEquals(TextColor.fromRgb(0x2CBAA8), part.getStyle().getColor());
    }

    @Test
    @DisplayName("&r resets formatting immediately")
    void resetCode() {
        Component comp = AmpersandFormat.parse("&1dark&rplain");
        assertEquals("darkplain", comp.getString());

        Component plainPart = comp.toFlatList().stream().filter(c -> "plain".equals(c.getString())).findFirst().orElseThrow();
        assertNull(plainPart.getStyle().getColor());
    }
}
