package com.reazip.economycraft.tag;

import com.reazip.economycraft.config.ProfessionSettings;
import com.reazip.economycraft.config.TagSettings;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.List;

/**
 * The one place a tag becomes text. Every surface builds its components here, so the tab list, the nametag and
 * chat cannot disagree about what a party's icon looks like.
 *
 * <p><strong>Two lengths, deliberately.</strong> The tab list has room for the full word, so it gets
 * {@code [Communism]} coloured from config. The nametag sits in the world and chat is a narrow column, so those
 * get {@code [☭]} only — D1's reason for splitting them.
 *
 * <p><strong>A Master is framed, not relabelled.</strong> {@link Tagged#mastered} draws the same tag inside
 * {@code << >>} in the job's {@code mastered_color}, so a Master is recognisable without the word "Master"
 * having to interrupt the player's name. Rusted keeps its word, because it is a penalty to be warned about.
 *
 * <p><strong>Colours are raw RGB ints, never {@code ChatFormatting} names.</strong> A {@link TagSettings#color}
 * is a 24-bit value because the sixteen vanilla formatting colours do not include the yellow-green the icon set
 * wants. That is fine here because every caller below serialises a style to the client, and it is why
 * {@link #teamPrefix} colours the prefix component itself and leaves the team uncoloured — see D21.
 *
 * <p>All literals, no translation keys. Labels come from {@code FactionId#displayName()} /
 * {@code ProfessionId#displayName()} — the English names, because the spec writes its own examples that way
 * ({@code [Communism]}, {@code [☭]}). The Vietnamese names on those enums are for the wiki and other prose
 * (D18), not for the tag itself.
 */
public final class TagStyle {

    private TagStyle() {
    }

    /** {@code [☭]} — the icon alone, in the tag's colour. For the nametag and for chat. */
    public static Component shortTag(TagSettings settings) {
        return bracket(settings.icon, settings.color);
    }

    /** {@code [Communism]} — the full label, in the tag's colour. For the tab list, which has the room. */
    public static Component fullTag(TagSettings settings, String label) {
        return bracket(label, settings.color);
    }

    /**
     * The tab-list row: every full tag, then the player's own name.
     *
     * <p>The name is passed in already resolved so the caller decides whether team formatting applies — and it
     * must not, because {@code PlayerTabOverlay} skips its own team formatting once a display name is present,
     * so formatting here with the tag team would duplicate the icon.
     */
    public static Component tabRow(List<Tagged> tags, Component name) {
        if (tags.isEmpty()) return name;
        Mutable out = new Mutable();
        for (Tagged tag : tags) out.add(tag.full());
        out.add(" ");
        out.add(name);
        return out.build();
    }

    /**
     * The team prefix for the nametag (D21): every icon, bracketed and coloured, then a space.
     *
     * <p>This is synced to clients as scoreboard data rather than as a display name, so it is built here but
     * pushed by a different packet — {@link #teamPrefix} must not be folded into {@link #tabRow}.
     */
    public static Component teamPrefix(List<Tagged> tags) {
        Mutable out = new Mutable();
        for (Tagged tag : tags) out.add(tag.icon());
        if (!tags.isEmpty()) out.add(" ");
        return out.build();
    }

    /** {@code [x]} with bracket and glyph in one colour, so a tag never renders half-tinted. */
    private static Component bracket(String text, int rgb) {
        return Component.literal("[" + text + "]").withStyle(style -> style.withColor(rgb));
    }

    /**
     * The Master treatment: {@code <<[x]>>}, bold, in the job's {@code mastered_color}.
     *
     * <p>Unicode has no laurel-wreath glyph — and no font here that could carry a drawn one — so the wreath is
     * stood in by chevrons around the bracket, which is the one piece of framing the vanilla font does have. It
     * stays <em>one</em> tag: the same {@code [x]} the Apprentice gets, with a frame round it, rather than a
     * second element the player has to read alongside the first.
     *
     * <p>The whole component is one colour and bold, because a tag that re-colours only its bracket reads as two
     * different tags at nametag size.
     */
    private static Component framed(String text, int rgb) {
        return Component.literal("<<[" + text + "]>>").withStyle(style -> style.withColor(rgb).withBold(true));
    }

    /** The colour a tag is drawn in: the Master colour when framed, the tag's own colour otherwise. */
    private static int colourOf(TagSettings settings, boolean mastered) {
        if (!mastered || !(settings instanceof ProfessionSettings profession)) return settings.color;
        return profession.masteredColor;
    }

    /**
     * One tag to draw: an icon, a colour and a label. Built by the caller from config, never stored.
     *
     * @param mastered whether this is a job at {@code MASTER}, which selects the {@code << >>} framing and the
     *                 {@code mastered_color}. A party is never mastered, so it passes {@code false}
     */
    public record Tagged(TagSettings settings, String label, boolean mastered) {

        public static Tagged of(TagSettings settings, String label) {
            return new Tagged(settings, label, false);
        }

        public static Tagged mastered(TagSettings settings, String label) {
            return new Tagged(settings, label, true);
        }

        public Component icon() {
            int rgb = colourOf(settings, mastered);
            return mastered ? framed(settings.icon, rgb) : bracket(settings.icon, rgb);
        }

        public Component full() {
            int rgb = colourOf(settings, mastered);
            return mastered ? framed(label, rgb) : bracket(label, rgb);
        }
    }

    /**
     * A small append helper, because {@code MutableComponent#append} returns {@code Component} in 26.3 and
     * chaining three tags through it loses the concrete type.
     */
    private static final class Mutable {

        private final List<Component> parts = new ArrayList<>(4);

        void add(Component component) {
            parts.add(component);
        }

        void add(String literal) {
            parts.add(Component.literal(literal));
        }

        Component build() {
            MutableComponent target = Component.empty();
            for (Component part : parts) target.append(part);
            return target;
        }
    }
}