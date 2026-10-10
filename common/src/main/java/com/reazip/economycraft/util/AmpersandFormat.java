package com.reazip.economycraft.util;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

import java.util.ArrayList;
import java.util.List;

/**
 * Translates {@code &x} Bedrock-style format codes in raw player text into a styled
 * {@link Component} or legacy formatted string.
 *
 * <p><strong>Scoping rule (Bedrock style):</strong> a format code applies from the
 * {@code &x} position until the next space character, newline, or end of string.
 * This matches the behaviour players expect from Minecraft Bedrock Edition.
 *
 * <p>Supported codes (case-insensitive):
 * <ul>
 *   <li>Standard colors: {@code &0}-{@code &9}, {@code &a}-{@code &f}</li>
 *   <li>Bedrock special colors:
 *     <ul>
 *       <li>{@code &g} - Minecoin Gold (#DDD605)</li>
 *       <li>{@code &h} - Material Quartz (#E3D4D1)</li>
 *       <li>{@code &i} - Material Iron (#CECACA)</li>
 *       <li>{@code &j} - Material Netherite (#443A3B)</li>
 *       <li>{@code &p} - Material Gold (#DEB12D)</li>
 *       <li>{@code &q} - Material Emerald (#47A036)</li>
 *       <li>{@code &s} - Material Diamond (#2CBAA8)</li>
 *       <li>{@code &t} - Material Lapis (#21497B)</li>
 *       <li>{@code &u} - Material Amethyst (#9A5CC6)</li>
 *     </ul>
 *   </li>
 *   <li>Formatting:
 *     <ul>
 *       <li>{@code &k} - Obfuscated</li>
 *       <li>{@code &l} - Bold</li>
 *       <li>{@code &m} - Strikethrough</li>
 *       <li>{@code &n} - Underline</li>
 *       <li>{@code &o} - Italic</li>
 *       <li>{@code &r} - Reset to default</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <p>Unknown codes (e.g. {@code &z}) and standalone ampersands (e.g. {@code cat & dog})
 * are passed through verbatim.
 *
 * <p>The resulting component never contains literal {@code &x} text, so the visual
 * character count on a sign equals the number of actual displayable characters —
 * exactly the sign capacity players would have had without any codes.
 */
public final class AmpersandFormat {

    private AmpersandFormat() {}

    /**
     * Returns {@code true} if the character is a recognised Bedrock format code.
     */
    public static boolean isFormatCode(char code) {
        char c = Character.toLowerCase(code);
        return (c >= '0' && c <= '9')
                || (c >= 'a' && c <= 'f')
                || c == 'g' || c == 'h' || c == 'i' || c == 'j'
                || c == 'k' || c == 'l' || c == 'm' || c == 'n' || c == 'o'
                || c == 'p' || c == 'q' || c == 'r' || c == 's' || c == 't' || c == 'u';
    }

    /**
     * Fast check whether {@code text} contains any valid {@code &x} sequence.
     */
    public static boolean containsCodes(String text) {
        if (text == null || text.length() < 2) return false;
        for (int i = 0; i < text.length() - 1; i++) {
            if (text.charAt(i) == '&' && isFormatCode(text.charAt(i + 1))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Parses {@code raw} and returns a {@link Component} with the format codes applied.
     * If the string contains no recognised codes the returned component is a plain
     * literal wrapping the original string unchanged.
     */
    public static Component parse(String raw) {
        if (raw == null || raw.isEmpty()) return Component.empty();
        if (!containsCodes(raw)) return Component.literal(raw);

        List<Segment> segments = tokenise(raw);
        MutableComponent result = Component.empty();
        for (Segment seg : segments) {
            if (seg.text.isEmpty()) continue;
            MutableComponent part = Component.literal(seg.text);
            if (seg.style != null) part = part.withStyle(seg.style);
            result.append(part);
        }
        return result;
    }

    /**
     * Converts {@code &x} format codes to legacy {@code §x} formatting with Bedrock
     * space/EOL scoping (appending {@code §r} when reaching a space, newline, or EOL).
     */
    public static String toLegacyString(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        if (!containsCodes(raw)) return raw;

        StringBuilder out = new StringBuilder(raw.length() + 8);
        boolean hasActiveStyle = false;
        int i = 0;
        while (i < raw.length()) {
            char c = raw.charAt(i);
            if (c == '&' && i + 1 < raw.length()) {
                char next = raw.charAt(i + 1);
                if (isFormatCode(next)) {
                    char lower = Character.toLowerCase(next);
                    if (lower == 'r') {
                        out.append('\u00A7').append('r');
                        hasActiveStyle = false;
                    } else {
                        out.append('\u00A7').append(lower);
                        hasActiveStyle = true;
                    }
                    i += 2;
                    continue;
                }
            }
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                if (hasActiveStyle) {
                    out.append('\u00A7').append('r');
                    hasActiveStyle = false;
                }
                out.append(c);
                i++;
                continue;
            }
            out.append(c);
            i++;
        }
        if (hasActiveStyle) {
            out.append('\u00A7').append('r');
        }
        return out.toString();
    }

    // -------------------------------------------------------------------------
    // Tokeniser
    // -------------------------------------------------------------------------

    private static List<Segment> tokenise(String raw) {
        List<Segment> out = new ArrayList<>();
        Style currentStyle = null;
        StringBuilder pending = new StringBuilder();
        int i = 0;
        while (i < raw.length()) {
            char c = raw.charAt(i);
            if (c == '&' && i + 1 < raw.length()) {
                char next = raw.charAt(i + 1);
                if (isFormatCode(next)) {
                    if (!pending.isEmpty()) {
                        out.add(new Segment(pending.toString(), currentStyle));
                        pending.setLength(0);
                    }
                    char lower = Character.toLowerCase(next);
                    if (lower == 'r') {
                        currentStyle = null;
                    } else {
                        currentStyle = applyCode(currentStyle, lower);
                    }
                    i += 2;
                    continue;
                }
            }
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                if (!pending.isEmpty()) {
                    out.add(new Segment(pending.toString(), currentStyle));
                    pending.setLength(0);
                }
                out.add(new Segment(String.valueOf(c), null));
                currentStyle = null; // space/EOL resets formatting
                i++;
                continue;
            }
            pending.append(c);
            i++;
        }
        if (!pending.isEmpty()) {
            out.add(new Segment(pending.toString(), currentStyle));
        }
        return out;
    }

    private static Style applyCode(Style base, char code) {
        Style s = (base != null) ? base : Style.EMPTY;
        return switch (code) {
            case '0' -> s.withColor(ChatFormatting.BLACK);
            case '1' -> s.withColor(ChatFormatting.DARK_BLUE);
            case '2' -> s.withColor(ChatFormatting.DARK_GREEN);
            case '3' -> s.withColor(ChatFormatting.DARK_AQUA);
            case '4' -> s.withColor(ChatFormatting.DARK_RED);
            case '5' -> s.withColor(ChatFormatting.DARK_PURPLE);
            case '6' -> s.withColor(ChatFormatting.GOLD);
            case '7' -> s.withColor(ChatFormatting.GRAY);
            case '8' -> s.withColor(ChatFormatting.DARK_GRAY);
            case '9' -> s.withColor(ChatFormatting.BLUE);
            case 'a' -> s.withColor(ChatFormatting.GREEN);
            case 'b' -> s.withColor(ChatFormatting.AQUA);
            case 'c' -> s.withColor(ChatFormatting.RED);
            case 'd' -> s.withColor(ChatFormatting.LIGHT_PURPLE);
            case 'e' -> s.withColor(ChatFormatting.YELLOW);
            case 'f' -> s.withColor(ChatFormatting.WHITE);

            // Bedrock custom colors
            case 'g' -> s.withColor(TextColor.fromRgb(0xDDD605)); // Minecoin Gold
            case 'h' -> s.withColor(TextColor.fromRgb(0xE3D4D1)); // Quartz
            case 'i' -> s.withColor(TextColor.fromRgb(0xCECACA)); // Iron
            case 'j' -> s.withColor(TextColor.fromRgb(0x443A3B)); // Netherite
            case 'p' -> s.withColor(TextColor.fromRgb(0xDEB12D)); // Gold
            case 'q' -> s.withColor(TextColor.fromRgb(0x47A036)); // Emerald
            case 's' -> s.withColor(TextColor.fromRgb(0x2CBAA8)); // Diamond
            case 't' -> s.withColor(TextColor.fromRgb(0x21497B)); // Lapis
            case 'u' -> s.withColor(TextColor.fromRgb(0x9A5CC6)); // Amethyst

            // Formatting
            case 'k' -> s.withObfuscated(true);
            case 'l' -> s.withBold(true);
            case 'm' -> s.withStrikethrough(true);
            case 'n' -> s.withUnderlined(true);
            case 'o' -> s.withItalic(true);
            default  -> s;
        };
    }

    private record Segment(String text, Style style) {}
}
