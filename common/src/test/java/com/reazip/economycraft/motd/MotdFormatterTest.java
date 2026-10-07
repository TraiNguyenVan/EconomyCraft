package com.reazip.economycraft.motd;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MotdFormatterTest {

    @Test
    @DisplayName("formatLine handles empty or null strings")
    void handlesEmptyAndNull() {
        assertEquals(Component.empty(), MotdFormatter.formatLine(null));
        assertEquals(Component.empty(), MotdFormatter.formatLine(""));
    }

    @Test
    @DisplayName("formatLine parses legacy color codes (& and §)")
    void parsesColorCodes() {
        Component comp = MotdFormatter.formatLine("&6Hello &aWorld");
        assertNotNull(comp);
        String text = comp.getString();
        assertEquals("Hello World", text);
    }

    @Test
    @DisplayName("formatLine creates clickable link with open_url and hover text for URLs")
    void parsesUrlsIntoClickableComponents() {
        String line = "&7Visit &bhttps://github.com/TraiNguyenVan/EconomyCraft &7for updates";
        Component comp = MotdFormatter.formatLine(line);
        assertNotNull(comp);
        assertEquals("Visit https://github.com/TraiNguyenVan/EconomyCraft for updates", comp.getString());

        boolean foundUrlWithClickEvent = false;
        for (Component sibling : comp.toFlatList()) {
            if ("https://github.com/TraiNguyenVan/EconomyCraft".equals(sibling.getString())) {
                Style style = sibling.getStyle();
                assertNotNull(style, "URL component should have a style");
                assertTrue(style.isUnderlined(), "URL component should be underlined");
                ClickEvent clickEvent = style.getClickEvent();
                assertNotNull(clickEvent, "URL component should have a ClickEvent");
                HoverEvent hoverEvent = style.getHoverEvent();
                assertNotNull(hoverEvent, "URL component should have a HoverEvent");
                foundUrlWithClickEvent = true;
            }
        }
        assertTrue(foundUrlWithClickEvent, "Should find URL segment with click and hover events");
    }
}
