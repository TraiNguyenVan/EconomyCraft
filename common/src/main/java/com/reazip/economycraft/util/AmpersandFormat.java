package com.reazip.economycraft.util;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

import java.util.ArrayList;
import java.util.List;

/**
 * Translates {@code &x} format codes in raw player text into styled {@link Component}s
 * or legacy formatted strings.
 *
 * <p><strong>Rules:</strong>
 * <ul>
 *   <li><strong>Standalone rule:</strong> An {@code &x} sequence only takes effect if it is
 *       immediately followed by characters (letters, digits, punctuation) — not whitespace or EOL.
 *       Standalone codes such as {@code "&1 "} or {@code "hello &1 world"} or trailing {@code "&1"}
 *       remain verbatim as {@code &x} with no formatting applied.</li>
 *   <li><strong>Scoping rule:</strong> A format code persists across spaces and words until it
 *       meets another color code (which changes color) or an explicit {@code &r} (reset).</li>
 * </ul>
 *
 * <p>Supported codes (case-insensitive):
 * <ul>
 *   <li>Standard colors: {@code &0}-{@code &9}, {@code &a}-{@code &f}</li>
 *   <li>Bedrock special material colors:
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
 */
public final class AmpersandFormat {

    private AmpersandFormat() {}

    /**
     * Returns {@code true} if the character is a recognised format code.
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
     * Returns {@code true} if the code character is a color (not a formatting flag or reset).
     */
    public static boolean isColorCode(char code) {
        char c = Character.toLowerCase(code);
        return (c >= '0' && c <= '9')
                || (c >= 'a' && c <= 'f')
                || c == 'g' || c == 'h' || c == 'i' || c == 'j'
                || c == 'p' || c == 'q' || c == 's' || c == 't' || c == 'u';
    }

    /**
     * Scans starting at {@code start} for consecutive {@code &x} codes.
     * Returns the index after the last valid code in the chain, or -1 if none found.
     */
    public static int scanCodeChain(String text, int start) {
        int i = start;
        while (i + 1 < text.length() && text.charAt(i) == '&' && isFormatCode(text.charAt(i + 1))) {
            i += 2;
        }
        return (i > start) ? i : -1;
    }

    /**
     * Returns {@code true} if there is at least one non-whitespace character after {@code chainEnd}.
     */
    public static boolean hasNonWhitespaceAfter(String text, int chainEnd) {
        if (chainEnd >= text.length()) return false;
        char next = text.charAt(chainEnd);
        return next != ' ' && next != '\t' && next != '\n' && next != '\r';
    }

    private static boolean containsResetCode(String text, int start, int end) {
        for (int k = start; k < end; k += 2) {
            if (Character.toLowerCase(text.charAt(k + 1)) == 'r') return true;
        }
        return false;
    }

    /**
     * Returns {@code true} if {@code text} contains any valid {@code &x} sequence
     * that takes effect (i.e. is not standalone).
     */
    public static boolean containsCodes(String text) {
        if (text == null || text.length() < 2) return false;
        for (int i = 0; i < text.length() - 1; i++) {
            if (text.charAt(i) == '&') {
                int chainEnd = scanCodeChain(text, i);
                if (chainEnd > i) {
                    if (hasNonWhitespaceAfter(text, chainEnd) || containsResetCode(text, i, chainEnd)) {
                        return true;
                    }
                    i = chainEnd - 1;
                }
            }
        }
        return false;
    }

    /**
     * Parses {@code raw} and returns a {@link Component} with formatting applied.
     */
    public static Component parse(String raw) {
        if (raw == null || raw.isEmpty()) return Component.empty();
        if (!containsCodes(raw)) return Component.literal(raw);

        List<Segment> segments = tokenise(raw);
        if (segments.isEmpty()) return Component.empty();

        // If only 1 segment, return it directly with its style
        if (segments.size() == 1) {
            Segment seg = segments.get(0);
            MutableComponent comp = Component.literal(seg.text);
            if (seg.style != null) comp.setStyle(seg.style);
            return comp;
        }

        MutableComponent result = Component.empty();
        for (Segment seg : segments) {
            if (seg.text.isEmpty()) continue;
            MutableComponent part = Component.literal(seg.text);
            if (seg.style != null) part.setStyle(seg.style);
            result.append(part);
        }
        return result;
    }

    /**
     * Converts {@code &x} format codes to legacy {@code §x} formatting.
     */
    public static String toLegacyString(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        if (!containsCodes(raw)) return raw;

        StringBuilder out = new StringBuilder(raw.length() + 8);
        boolean hasActiveStyle = false;
        int i = 0;
        while (i < raw.length()) {
            if (raw.charAt(i) == '&') {
                int chainEnd = scanCodeChain(raw, i);
                if (chainEnd > i) {
                    boolean hasLetters = hasNonWhitespaceAfter(raw, chainEnd);
                    boolean containsReset = containsResetCode(raw, i, chainEnd);
                    if (hasLetters || (containsReset && hasActiveStyle)) {
                        for (int k = i; k < chainEnd; k += 2) {
                            char code = Character.toLowerCase(raw.charAt(k + 1));
                            out.append('\u00A7').append(code);
                            if (code == 'r') {
                                hasActiveStyle = false;
                            } else {
                                hasActiveStyle = true;
                            }
                        }
                        i = chainEnd;
                        continue;
                    }
                }
            }
            out.append(raw.charAt(i));
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
            if (raw.charAt(i) == '&') {
                int chainEnd = scanCodeChain(raw, i);
                if (chainEnd > i) {
                    boolean hasLetters = hasNonWhitespaceAfter(raw, chainEnd);
                    boolean containsReset = containsResetCode(raw, i, chainEnd);
                    if (hasLetters || (containsReset && currentStyle != null)) {
                        if (!pending.isEmpty()) {
                            out.add(new Segment(pending.toString(), currentStyle));
                            pending.setLength(0);
                        }
                        for (int k = i; k < chainEnd; k += 2) {
                            char code = Character.toLowerCase(raw.charAt(k + 1));
                            if (code == 'r') {
                                currentStyle = null;
                            } else {
                                currentStyle = applyCode(currentStyle, code);
                            }
                        }
                        i = chainEnd;
                        continue;
                    }
                }
            }
            pending.append(raw.charAt(i));
            i++;
        }
        if (!pending.isEmpty()) {
            out.add(new Segment(pending.toString(), currentStyle));
        }
        return out;
    }

    private static Style applyCode(Style base, char code) {
        Style s = (base != null) ? base : Style.EMPTY;
        if (isColorCode(code)) {
            TextColor color = toColor(code);
            return Style.EMPTY.withColor(color);
        }
        return switch (code) {
            case 'k' -> s.withObfuscated(true);
            case 'l' -> s.withBold(true);
            case 'm' -> s.withStrikethrough(true);
            case 'n' -> s.withUnderlined(true);
            case 'o' -> s.withItalic(true);
            default  -> s;
        };
    }

    private static TextColor toColor(char code) {
        return switch (code) {
            case '0' -> TextColor.fromLegacyFormat(ChatFormatting.BLACK);
            case '1' -> TextColor.fromLegacyFormat(ChatFormatting.DARK_BLUE);
            case '2' -> TextColor.fromLegacyFormat(ChatFormatting.DARK_GREEN);
            case '3' -> TextColor.fromLegacyFormat(ChatFormatting.DARK_AQUA);
            case '4' -> TextColor.fromLegacyFormat(ChatFormatting.DARK_RED);
            case '5' -> TextColor.fromLegacyFormat(ChatFormatting.DARK_PURPLE);
            case '6' -> TextColor.fromLegacyFormat(ChatFormatting.GOLD);
            case '7' -> TextColor.fromLegacyFormat(ChatFormatting.GRAY);
            case '8' -> TextColor.fromLegacyFormat(ChatFormatting.DARK_GRAY);
            case '9' -> TextColor.fromLegacyFormat(ChatFormatting.BLUE);
            case 'a' -> TextColor.fromLegacyFormat(ChatFormatting.GREEN);
            case 'b' -> TextColor.fromLegacyFormat(ChatFormatting.AQUA);
            case 'c' -> TextColor.fromLegacyFormat(ChatFormatting.RED);
            case 'd' -> TextColor.fromLegacyFormat(ChatFormatting.LIGHT_PURPLE);
            case 'e' -> TextColor.fromLegacyFormat(ChatFormatting.YELLOW);
            case 'f' -> TextColor.fromLegacyFormat(ChatFormatting.WHITE);

            // Bedrock custom materials
            case 'g' -> TextColor.fromRgb(0xDDD605); // Minecoin Gold
            case 'h' -> TextColor.fromRgb(0xE3D4D1); // Quartz
            case 'i' -> TextColor.fromRgb(0xCECACA); // Iron
            case 'j' -> TextColor.fromRgb(0x443A3B); // Netherite
            case 'p' -> TextColor.fromRgb(0xDEB12D); // Gold
            case 'q' -> TextColor.fromRgb(0x47A036); // Emerald
            case 's' -> TextColor.fromRgb(0x2CBAA8); // Diamond
            case 't' -> TextColor.fromRgb(0x21497B); // Lapis
            case 'u' -> TextColor.fromRgb(0x9A5CC6); // Amethyst
            default  -> null;
        };
    }

    private record Segment(String text, Style style) {}
}
