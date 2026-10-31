package com.reazip.economycraft.motd;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReleaseNotesServiceTest {
    private static final String REPOSITORY_URL = "https://github.com/TraiNguyenVan/EconomyCraft";

    @Test
    void releaseMessagesKeepGoldTitleLinkedToRepositoryWithoutExtraUrlLine() {
        List<Component> messages = ReleaseNotesService.createMessages(
                "1.12.1", List.of("Added a market feature"), 3);

        assertEquals("EconomyCraft v1.12.1", messages.get(0).getString());
        Style titleStyle = messages.get(0).getStyle();
        assertEquals(ChatFormatting.GOLD, titleStyle.getColor().getLegacyFormat());
        assertTrue(titleStyle.isBold());
        assertTrue(titleStyle.isUnderlined());
        assertOpenUrl(titleStyle.getClickEvent(), REPOSITORY_URL);

        assertEquals("• Added a market feature", messages.get(1).getString());
        assertEquals(2, messages.size());
        assertFalse(messages.stream().anyMatch(message -> message.getString().contains("https://")));
    }

    private static void assertOpenUrl(ClickEvent event, String expectedUrl) {
        assertTrue(event instanceof ClickEvent.OpenUrl);
        assertEquals(expectedUrl, ((ClickEvent.OpenUrl) event).uri().toString());
    }
}
