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
 * <p><strong>A job tag is three pieces, not one string: bracket, content, bracket.</strong> The two brackets are
 * coloured separately from what they enclose, which is the whole of the Master treatment: a Master swaps the
 * grey square brackets for gold guillemets, while the job icon inside keeps its own colour so a Master Builder
 * still reads as a Builder. An Apprentice or a Rusted job keeps the grey brackets. Nothing is bold, because bold
 * at nametag size competes with the icon rather than emphasising it.
 *
 * <p><strong>A party tag is untouched by all that</strong> — one run, brackets included, in the party's own
 * colour. The rule above is a job-level rule and there is no party-level equivalent, because a party has no
 * levels to show.
 *
 * <p><strong>Why guillemets and not lenticular brackets.</strong> {@code 【} (U+3010) is what a wreath around
 * an icon wants to be, and it is not drawable here: vanilla ships {@code assets/minecraft/font/include/unifont.json}
 * with an empty provider list, and {@code include/default.json} never includes it, so the whole CJK punctuation
 * range is absent from the default font and 【 renders as a blank box. {@code «} (U+00AB) and {@code »} (U+00BB)
 * are in that font, and are the heaviest bracket pair it has.
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

    /**
     * The frame on every tag that is not a Master: ordinary square brackets in a neutral grey.
     *
     * <p>Deliberately a constant rather than a config key. It is not a taste knob — it is the "nothing to see
     * here" colour, and the point of it is to recede so that the Master brackets are the only thing on the
     * nametag drawing attention. A per-tag bracket colour is what {@code mastered_color} is for.
     */
    private static final int PLAIN_BRACKET_COLOR = 0x808080;

    private static final String PLAIN_OPEN = "[";
    private static final String PLAIN_CLOSE = "]";

    /** U+00AB / U+00BB. See the class note on why not U+3010. */
    private static final String MASTER_OPEN = "«";
    private static final String MASTER_CLOSE = "»";

    private TagStyle() {
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

    /** One text run in one colour. The unit both bracket and content are built from. */
    private static Component coloured(String text, int rgb) {
        return Component.literal(text).withStyle(style -> style.withColor(rgb));
    }

    /**
     * One tag to draw: a bracket pair, what it encloses, and whether that pair is a Master's.
     *
     * @param mastered whether this is a job at {@code MASTER}, which selects the guillemets and
     *                 {@code mastered_color} for the brackets. A party is never mastered, so it passes
     *                 {@code false} and gets the grey square brackets
     */
    public record Tagged(TagSettings settings, String label, boolean mastered) {

        public static Tagged of(TagSettings settings, String label) {
            return new Tagged(settings, label, false);
        }

        public static Tagged mastered(TagSettings settings, String label) {
            return new Tagged(settings, label, true);
        }

        /** {@code [☭]} or {@code «☭»} — the icon alone. For the nametag and for chat. */
        public Component icon() {
            return wrap(settings.icon);
        }

        /** {@code [Communism]} or {@code «Communism»} — the full label. For the tab list, which has the room. */
        public Component full() {
            return wrap(label);
        }

        /**
         * Bracket, content, bracket — as three sibling components, so the brackets can carry their own colour
         * without tinting the content.
         *
         * <p>Not one styled string: a single run can only be one colour, which would either grey out the job icon
         * along with its brackets or leave the brackets untinted.
         *
         * <p><strong>Job tags only.</strong> A party tag stays a single run in its own colour, brackets included,
         * so {@code [☭]} reads as one Communist mark rather than a grey frame with a red icon inside it. A party
         * has no levels and therefore nothing to distinguish, so it has no use for the bracket rule.
         */
        private Component wrap(String text) {
            if (!(settings instanceof ProfessionSettings)) {
                return coloured(PLAIN_OPEN + text + PLAIN_CLOSE, settings.color);
            }
            String open = mastered ? MASTER_OPEN : PLAIN_OPEN;
            String close = mastered ? MASTER_CLOSE : PLAIN_CLOSE;
            int bracketColor = mastered ? masteredColor() : PLAIN_BRACKET_COLOR;
            Mutable out = new Mutable();
            out.add(coloured(open, bracketColor));
            out.add(coloured(text, settings.color));
            out.add(coloured(close, bracketColor));
            return out.build();
        }

        /**
         * The colour of a Master's brackets, falling back to the tag's own colour for a settings block that has no
         * {@code mastered_color} of its own. The fallback is unreachable in practice — only a job is ever
         * {@code mastered} — and exists so this cannot throw on a hand-edited config that dropped the key.
         */
        private int masteredColor() {
            if (settings instanceof ProfessionSettings profession) return profession.masteredColor;
            return settings.color;
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
