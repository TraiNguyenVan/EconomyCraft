package com.reazip.economycraft.motd;

import com.reazip.economycraft.util.ChatCompat;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses and formats MOTD lines into Minecraft chat components with color codes,
 * dynamic placeholders, and clickable/hoverable URLs.
 */
public final class MotdFormatter {
    private static final Pattern URL_PATTERN = Pattern.compile(
            "https?://[a-zA-Z0-9.-]+(?:\\.[a-zA-Z]{2,})+(?::\\d+)?(?:/[^\\s]*)?",
            Pattern.CASE_INSENSITIVE
    );

    private static final Map<Character, ChatFormatting> COLOR_CODES = Map.ofEntries(
            Map.entry('0', ChatFormatting.BLACK),
            Map.entry('1', ChatFormatting.DARK_BLUE),
            Map.entry('2', ChatFormatting.DARK_GREEN),
            Map.entry('3', ChatFormatting.DARK_AQUA),
            Map.entry('4', ChatFormatting.DARK_RED),
            Map.entry('5', ChatFormatting.DARK_PURPLE),
            Map.entry('6', ChatFormatting.GOLD),
            Map.entry('7', ChatFormatting.GRAY),
            Map.entry('8', ChatFormatting.DARK_GRAY),
            Map.entry('9', ChatFormatting.BLUE),
            Map.entry('a', ChatFormatting.GREEN),
            Map.entry('b', ChatFormatting.AQUA),
            Map.entry('c', ChatFormatting.RED),
            Map.entry('d', ChatFormatting.LIGHT_PURPLE),
            Map.entry('e', ChatFormatting.YELLOW),
            Map.entry('f', ChatFormatting.WHITE),
            Map.entry('k', ChatFormatting.OBFUSCATED),
            Map.entry('l', ChatFormatting.BOLD),
            Map.entry('m', ChatFormatting.STRIKETHROUGH),
            Map.entry('n', ChatFormatting.UNDERLINE),
            Map.entry('o', ChatFormatting.ITALIC),
            Map.entry('r', ChatFormatting.RESET)
    );

    private MotdFormatter() {}

    /**
     * Resolves placeholders in a line of text.
     */
    public static String resolvePlaceholders(String raw, ServerPlayer player) {
        if (raw == null) return "";
        boolean needsPlayer = raw.contains("{player}") || raw.contains("%player%");
        boolean needsOnline = raw.contains("{online}") || raw.contains("%online%");
        boolean needsServer = raw.contains("{server}") || raw.contains("%server%");
        if (!needsPlayer && !needsOnline && !needsServer) return raw;

        String playerName = needsPlayer ? com.reazip.economycraft.util.IdentityCompat.of(player).name() : "";
        var server = needsOnline || needsServer ? player.level().getServer() : null;
        String onlineCount = needsOnline ? String.valueOf(server.getPlayerCount()) : "";
        String serverName = needsServer ? server.getServerModName() : "";

        return raw.replace("{player}", playerName)
                .replace("%player%", playerName)
                .replace("{online}", onlineCount)
                .replace("%online%", onlineCount)
                .replace("{server}", serverName)
                .replace("%server%", serverName);
    }

    /**
     * Formats a line of text into a Component supporting color codes (& or §) and auto-clickable URLs.
     */
    public static Component formatLine(String lineWithPlaceholders) {
        if (lineWithPlaceholders == null || lineWithPlaceholders.isEmpty()) {
            return Component.empty();
        }

        Matcher matcher = URL_PATTERN.matcher(lineWithPlaceholders);
        MutableComponent root = Component.empty();
        int lastEnd = 0;

        Style currentStyle = Style.EMPTY;

        while (matcher.find()) {
            int start = matcher.start();
            int end = matcher.end();

            if (start > lastEnd) {
                String segment = lineWithPlaceholders.substring(lastEnd, start);
                currentStyle = appendFormattedText(root, segment, currentStyle);
            }

            String url = matcher.group();
            MutableComponent linkComp = Component.literal(url);
            Style linkStyle = currentStyle
                    .withUnderlined(true)
                    .withClickEvent(ChatCompat.openUrlEvent(url))
                    .withHoverEvent(ChatCompat.showTextHoverEvent(
                            Component.literal("Click to open link in browser").withStyle(ChatFormatting.GRAY)
                    ));
            linkComp.setStyle(linkStyle);
            root.append(linkComp);

            lastEnd = end;
        }

        if (lastEnd < lineWithPlaceholders.length()) {
            String segment = lineWithPlaceholders.substring(lastEnd);
            appendFormattedText(root, segment, currentStyle);
        }

        return root;
    }

    /**
     * Appends text with legacy & / § formatting codes to the parent component, updating the style state.
     *
     * @return the style state at the end of this segment
     */
    private static Style appendFormattedText(MutableComponent parent, String text, Style initialStyle) {
        Style style = initialStyle;
        StringBuilder currentBuffer = new StringBuilder();

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c == '&' || c == '§') && i + 1 < text.length()) {
                char code = Character.toLowerCase(text.charAt(i + 1));
                ChatFormatting format = COLOR_CODES.get(code);
                if (format != null) {
                    if (currentBuffer.length() > 0) {
                        parent.append(Component.literal(currentBuffer.toString()).setStyle(style));
                        currentBuffer.setLength(0);
                    }
                    if (format == ChatFormatting.RESET) {
                        style = Style.EMPTY;
                    } else if (format == ChatFormatting.BOLD) {
                        style = style.withBold(true);
                    } else if (format == ChatFormatting.ITALIC) {
                        style = style.withItalic(true);
                    } else if (format == ChatFormatting.UNDERLINE) {
                        style = style.withUnderlined(true);
                    } else if (format == ChatFormatting.STRIKETHROUGH) {
                        style = style.withStrikethrough(true);
                    } else if (format == ChatFormatting.OBFUSCATED) {
                        style = style.withObfuscated(true);
                    } else {
                        // It is a color (0-9, a-f)
                        style = Style.EMPTY.withColor(format);
                    }
                    i++; // skip code char
                    continue;
                }
            }
            currentBuffer.append(c);
        }

        if (currentBuffer.length() > 0) {
            parent.append(Component.literal(currentBuffer.toString()).setStyle(style));
        }

        return style;
    }
}
