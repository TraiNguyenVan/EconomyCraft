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
    @DisplayName("Standalone &x does not format and shows verbatim &x")
    void standaloneCodesShowVerbatim() {
        assertFalse(AmpersandFormat.containsCodes(null));
        assertFalse(AmpersandFormat.containsCodes(""));
        assertFalse(AmpersandFormat.containsCodes("hello world"));
        assertFalse(AmpersandFormat.containsCodes("cat & dog"));
        assertFalse(AmpersandFormat.containsCodes("&z unknown"));

        // Standalone codes (followed by space or EOL)
        assertFalse(AmpersandFormat.containsCodes("hello &1 world"));
        assertFalse(AmpersandFormat.containsCodes("&1 "));
        assertFalse(AmpersandFormat.containsCodes("&1"));
        assertFalse(AmpersandFormat.containsCodes("&1&l "));
        assertFalse(AmpersandFormat.containsCodes("&1&l"));

        // When parsed, standalone codes remain unchanged literals
        assertEquals("hello &1 world", AmpersandFormat.parse("hello &1 world").getString());
        assertNull(AmpersandFormat.parse("hello &1 world").getStyle().getColor());

        assertEquals("&1 ", AmpersandFormat.parse("&1 ").getString());
        assertNull(AmpersandFormat.parse("&1 ").getStyle().getColor());

        assertEquals("&1", AmpersandFormat.parse("&1").getString());
        assertNull(AmpersandFormat.parse("&1").getStyle().getColor());

        assertEquals("&1&l ", AmpersandFormat.parse("&1&l ").getString());
        assertNull(AmpersandFormat.parse("&1&l ").getStyle().getColor());
    }

    @Test
    @DisplayName("Color persists across spaces until meeting another color or &r")
    void colorPersistsAcrossSpaces() {
        Component comp = AmpersandFormat.parse("&1Hello world this is blue");
        assertEquals("Hello world this is blue", comp.getString());
        assertFalse(comp.getString().contains("&1"));

        // The entire component text should have dark blue color
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.DARK_BLUE), comp.getStyle().getColor());
    }

    @Test
    @DisplayName("Color switches when meeting another color code")
    void colorSwitchesOnNewColor() {
        Component comp = AmpersandFormat.parse("&1Blue text &2Green text");
        assertEquals("Blue text Green text", comp.getString());

        List<Component> parts = comp.toFlatList();
        Component bluePart = parts.stream().filter(c -> c.getString().contains("Blue text ")).findFirst().orElseThrow();
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.DARK_BLUE), bluePart.getStyle().getColor());

        Component greenPart = parts.stream().filter(c -> c.getString().contains("Green text")).findFirst().orElseThrow();
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.DARK_GREEN), greenPart.getStyle().getColor());
    }

    @Test
    @DisplayName("Formatting resets when meeting &r")
    void resetCodeStopsColor() {
        Component comp = AmpersandFormat.parse("&1Blue text &rNormal text");
        assertEquals("Blue text Normal text", comp.getString());

        List<Component> parts = comp.toFlatList();
        Component bluePart = parts.stream().filter(c -> c.getString().contains("Blue text ")).findFirst().orElseThrow();
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.DARK_BLUE), bluePart.getStyle().getColor());

        Component normalPart = parts.stream().filter(c -> c.getString().contains("Normal text")).findFirst().orElseThrow();
        assertNull(normalPart.getStyle().getColor());
    }

    @Test
    @DisplayName("Code chaining combines color and formatting (&1&l)")
    void codeChaining() {
        Component comp = AmpersandFormat.parse("&1&lBold Blue world");
        assertEquals("Bold Blue world", comp.getString());
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.DARK_BLUE), comp.getStyle().getColor());
        assertTrue(comp.getStyle().isBold());
    }

    @Test
    @DisplayName("toLegacyString preserves standalone codes and wraps styled runs")
    void toLegacyStringConversion() {
        assertEquals("", AmpersandFormat.toLegacyString(null));
        assertEquals("", AmpersandFormat.toLegacyString(""));
        assertEquals("plain text", AmpersandFormat.toLegacyString("plain text"));
        assertEquals("cat & dog", AmpersandFormat.toLegacyString("cat & dog"));
        assertEquals("hello &1 world", AmpersandFormat.toLegacyString("hello &1 world"));
        assertEquals("&1 ", AmpersandFormat.toLegacyString("&1 "));

        assertEquals("\u00A71Hello world\u00A7r", AmpersandFormat.toLegacyString("&1Hello world"));
        assertEquals("\u00A71\u00A7lHello world\u00A7r", AmpersandFormat.toLegacyString("&1&lHello world"));
        assertEquals("\u00A71Hello \u00A72World\u00A7r", AmpersandFormat.toLegacyString("&1Hello &2World"));
    }

    @Test
    @DisplayName("Bedrock custom materials work (&s diamond)")
    void bedrockCustomMaterials() {
        Component comp = AmpersandFormat.parse("&sShiny diamond");
        assertEquals("Shiny diamond", comp.getString());
        assertEquals(TextColor.fromRgb(0x2CBAA8), comp.getStyle().getColor());
    }
}
