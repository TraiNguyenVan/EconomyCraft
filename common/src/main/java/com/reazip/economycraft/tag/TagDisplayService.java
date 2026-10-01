package com.reazip.economycraft.tag;

import com.reazip.economycraft.faction.FactionId;
import com.reazip.economycraft.profession.ProfessionId;
import com.reazip.economycraft.profession.ProfessionLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Team;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Turns "what has this player chosen" into the three things a client can render, and keeps them in sync.
 *
 * <p><strong>Three surfaces, three transports, one builder.</strong> {@link TagStyle} builds the text; this class
 * decides when it is rebuilt and how it reaches the client:
 *
 * <ul>
 *   <li><strong>Tab list</strong> — {@link #tabDisplayName}, pushed with
 *       {@link ClientboundPlayerInfoUpdatePacket.Action#UPDATE_DISPLAY_NAME} so a change lands without a
 *       reconnect. Returning {@code null} is meaningful: it restores vanilla, and the client then falls back to
 *       the profile name.</li>
 *   <li><strong>Nametag</strong> — a scoreboard <em>team prefix</em>, because the nametag is drawn client-side
 *       and synced scoreboard data is the only thing that can reach it (D21). No team colour is set, which keeps
 *       the player's own name rendering exactly as before.</li>
 *   <li><strong>Chat</strong> — {@link #chatBody}, used by the mixin on the server's chat funnel to rewrite the
 *       message content (D22).</li>
 * </ul>
 *
 * <p><strong>Cache correctness does not depend on remembering to call anything.</strong> {@link #refresh} exists
 * for the instant case, but the guarantee comes from {@link #sweep}: a cheap signature (the two chosen ids plus
 * the profession level) is compared against the cached one for every online player on a slow interval, so a
 * level-up or rust transition that forgot to call {@code refresh} still corrects itself instead of leaving a
 * stale tag until the player relogs. Chat reads only the cached tags, never the stores, because it is the one
 * path that runs per message (P3-T4).
 *
 * <p><strong>Server-thread only.</strong> Every method here is called from the server thread — the join and quit
 * events, the tick sweep and the chat funnel. The caches are therefore plain {@link HashMap}s, and none of the
 * concurrency that exists elsewhere in the mod (webhooks, the async file writer) can reach them.
 */
public final class TagDisplayService {

    /**
     * Namespace for the teams this class owns, so {@link #forget} can tell "a team we created" from "a team
     * another plugin assigned" and leave the latter alone.
     */
    private static final String TEAM_NAMESPACE = "ec_";

    /** The code used in a team name for a tag the player does not have. */
    private static final String NO_TAG_CODE = "no";

    /** One player's rendered tags, plus the state they were built from, for staleness detection. */
    private record Rendered(String signature, List<TagStyle.Tagged> tags, String teamKey) {
    }

    private final TagSource source;
    private final Map<UUID, Rendered> cache = new HashMap<>();
    private final Map<String, PlayerTeam> ownedTeams = new HashMap<>();

    /** The three store lookups this class needs, kept behind an interface so tests need no live server. */
    public interface TagSource {

        FactionId factionOf(UUID player);

        ProfessionId professionOf(UUID player);

        ProfessionLevel levelOf(UUID player);
    }

    public TagDisplayService(TagSource source) {
        this.source = source;
    }

    // ---------------------------------------------------------------- tab list

    /**
     * The component the client should draw in the tab list for this player, or {@code null} for vanilla.
     *
     * <p>The name is used plain, without {@code PlayerTeam#formatNameForTeam}, because the client applies no team
     * formatting of its own once a display name is present (hook #18b), and the one team this player is in is the
     * tag team from D21 — formatting with it would print the icon twice.
     */
    public Component tabDisplayName(ServerPlayer player) {
        Rendered rendered = rendered(player.getUUID());
        if (rendered.tags().isEmpty()) return null;
        return TagStyle.tabRow(rendered(player.getUUID()).tags(), player.getName());
    }

    /** Pushes the current tab row to every client that can see this player. */
    public void pushTabUpdate(ServerPlayer player) {
        player.connection.send(new ClientboundPlayerInfoUpdatePacket(
                ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME, player));
    }

    // ---------------------------------------------------------------- nametag

    /** The team prefix the nametag needs, or {@link Component#empty()} when the player has no tags. */
    public Component teamPrefix(ServerPlayer player) {
        return teamPrefixForTest(player.getUUID());
    }

    /** The same prefix by id. Package-visible so the composition is testable without a live player. */
    Component teamPrefixForTest(UUID player) {
        return TagStyle.teamPrefix(rendered(player).tags());
    }

    // ---------------------------------------------------------------- chat

    /**
     * The message body with this player's icons in front, or the body untouched.
     *
     * <p>Returns the body unchanged when there is nothing to add, so the caller can leave the message alone. This
     * is D22's "only rewrite a tagged player's message": an untagged player's chat is never touched and keeps its
     * signed badge.
     */
    public Component chatBody(UUID speaker, Component body) {
        return TagStyle.chatBody(rendered(speaker).tags(), body);
    }

    /** Whether this player's messages are rewritten at all. The cheap half of D22's rule. */
    public boolean isTagged(UUID speaker) {
        return !rendered(speaker).tags().isEmpty();
    }

    // ---------------------------------------------------------------- lifecycle

    /**
     * Applies everything for one player: rebuilds the cache, pushes the tab row and syncs the team.
     *
     * <p>Called on join. On join the tab push is redundant — the client's first tab entry already reads the
     * display name through the same stub — but it is harmless, and it means a player whose selections load after
     * the join event still ends up correct without a second code path.
     */
    public void applyTo(ServerPlayer player) {
        revalidate(player.getUUID());
        pushTabUpdate(player);
        syncTeam(player);
    }

    /** Rebuilds and re-pushes one player's tags. The instant path, for a selection or level change. */
    public void refresh(ServerPlayer player) {
        applyTo(player);
    }

    /**
     * Drops the cache entry and removes the player from a team <em>this class owns</em>.
     *
     * <p>Deliberately leaves a foreign team alone: a player put on a team by another plugin is not ours to un-tag.
     * Cleaning our own membership here is still necessary, or {@link PlayerTeam#getPlayers()} would accumulate
     * the names of everyone who has ever joined.
     */
    public void forget(ServerPlayer player) {
        UUID id = player.getUUID();
        Rendered rendered = cache.remove(id);
        Scoreboard scoreboard = scoreboardOf(player);
        PlayerTeam current = scoreboard.getPlayerTeam(player.getScoreboardName());
        boolean wasOurs = current != null && isOurs(current.getName());
        if (rendered == null && !wasOurs) return;
        if (wasOurs) scoreboard.removePlayerFromTeam(player.getScoreboardName());
    }

    /** Drops every cache entry and forgets every team handle. Called when the server stops. */
    public void clear() {
        cache.clear();
        ownedTeams.clear();
    }

    // ---------------------------------------------------------------- staleness

    /**
     * Corrects any player whose rendered tags no longer match their stored state.
     *
     * <p>The signature is two ids and a level, so this costs a few hash lookups per player; the server tick calls
     * it on a slow interval. This is what makes the cache self-healing rather than a thing every later phase has
     * to remember to maintain.
     */
    public void sweep(List<ServerPlayer> onlinePlayers) {
        for (ServerPlayer player : onlinePlayers) {
            if (!isStale(player.getUUID())) continue;
            applyTo(player);
        }
    }

    /**
     * Whether this player's cached tags no longer match their stored state.
     *
     * <p>Package-private rather than private because it is the part worth testing: it is the whole self-healing
     * guarantee, and it can be asserted with a fake {@link TagSource} and no live {@link ServerPlayer}. Everything
     * {@link #sweep} then does with the answer — pushing a packet, moving a team — needs a real server and is not
     * unit-testable by construction.
     */
    boolean isStale(UUID player) {
        Rendered cached = cache.get(player);
        return cached == null || !cached.signature().equals(signatureOf(player));
    }

    // ---------------------------------------------------------------- rendering

    private Rendered rendered(UUID player) {
        Rendered cached = cache.get(player);
        return cached != null ? cached : revalidate(player);
    }

    /**
     * Rebuilds one player's tags and replaces the cached entry.
     *
     * <p>This is the cache half of {@link #applyTo}, split out because it is the only part that does not need a live
     * player: {@code applyTo} is this, then a packet push, then a team sync. Package-private so a test can drive the
     * real refresh path without a server, which is the difference between testing the staleness rule and
     * re-implementing it in the test.
     *
     * <p>Reads never call this — that is the point of the cache — so a caller that wants current data must
     * invalidate deliberately, which is what {@link #isStale} and the sweep are for.
     */
    Rendered revalidate(UUID player) {
        List<TagStyle.Tagged> tags = buildTags(player);
        Rendered rendered = new Rendered(signatureOf(player), tags, teamKeyOf(player));
        cache.put(player, rendered);
        return rendered;
    }

    /**
     * This player's tags, in the order they are drawn: party first, then profession.
     *
     * <p>Public because {@code /tag <player>} renders exactly this for the read-only view (P3-T5), so the command
     * and the three display surfaces cannot describe a player differently.
     */
    public List<TagStyle.Tagged> tagsOf(UUID player) {
        return rendered(player).tags();
    }

    private List<TagStyle.Tagged> buildTags(UUID player) {
        List<TagStyle.Tagged> tags = new ArrayList<>(2);
        FactionId faction = source.factionOf(player);
        if (faction != null) tags.add(TagStyle.Tagged.of(faction.settings(), faction.displayName()));
        ProfessionId profession = source.professionOf(player);
        if (profession != null) {
            tags.add(TagStyle.Tagged.of(profession.settings(), professionLabel(profession, source.levelOf(player))));
        }
        return tags;
    }

    /**
     * The tab list shows the level only when it is worth interrupting the name for: an Apprentice is the default
     * and saying so on every row is noise, whereas Master and Rusted are the two states that change what a player
     * can do, and a rusted player in particular has to be recognisable at a glance (Phase 9's rust penalty).
     */
    private static String professionLabel(ProfessionId profession, ProfessionLevel level) {
        if (level == null || level == ProfessionLevel.APPRENTICE) return profession.displayName();
        return profession.displayName() + ": " + level.displayName();
    }

    /**
     * The cheap staleness check: two ids and a level as one string. Never shown to a player, so ordinal-friendly
     * names are enough and there is nothing to keep in sync with the display names.
     */
    private String signatureOf(UUID player) {
        return source.factionOf(player) + "/" + source.professionOf(player) + "/" + source.levelOf(player);
    }

    // ---------------------------------------------------------------- teams

    /**
     * Puts the player in the team matching their tags, or removes them from one of ours when they have no tags.
     *
     * <p>Teams are per (faction, profession) pair because a player can only be in one at a time, so the pair has
     * to be encoded in the name. Codes are the first two letters of the enum constant rather than the display
     * name, so renaming a party in config cannot orphan a team, and {@link #TEAM_NAMESPACE} plus two codes stays
     * comfortably inside the length vanilla's scoreboard command allows.
     */
    private void syncTeam(ServerPlayer player) {
        Rendered rendered = rendered(player.getUUID());
        if (rendered.tags().isEmpty()) {
            forget(player);
            return;
        }
        Scoreboard scoreboard = scoreboardOf(player);
        PlayerTeam current = scoreboard.getPlayerTeam(player.getScoreboardName());
        if (current != null && current.getName().equals(rendered.teamKey())) return;

        // A foreign team is left in place when the player's tags already match, and only displaced when the tag
        // genuinely has to change: `addPlayerToTeam` moves a player off any team, which would silently strip
        // another plugin's rank from anyone who joins EconomyCraft.
        PlayerTeam team = ownedTeams.get(rendered.teamKey());
        if (team == null) {
            team = createTeam(scoreboard, rendered.teamKey(), rendered);
            ownedTeams.put(rendered.teamKey(), team);
        }
        scoreboard.addPlayerToTeam(player.getScoreboardName(), team);
    }

    /**
     * Creates one tag team, setting two things explicitly that would otherwise be a bug.
     *
     * <p>The collision rule must be {@link Team.CollisionRule#ALWAYS}, or a tag would quietly make same-party
     * players unable to hurt each other (D21). The colour is left empty, which is what preserves both the
     * prefix's own RGB and the player's untouched name colour.
     *
     * <p>{@link Scoreboard#addPlayerTeam} is get-or-create but logs a warning when the team already exists, so
     * {@link #ownedTeams} is what keeps that log quiet.
     */
    private PlayerTeam createTeam(Scoreboard scoreboard, String name, Rendered rendered) {
        PlayerTeam team = scoreboard.addPlayerTeam(name);
        team.setPlayerPrefix(TagStyle.teamPrefix(rendered.tags()));
        team.setPlayerSuffix(Component.empty());
        team.setColor(Optional.empty());
        team.setCollisionRule(Team.CollisionRule.ALWAYS);
        scoreboard.onTeamChanged(team);
        return team;
    }

    private String teamKeyOf(UUID player) {
        return TEAM_NAMESPACE + code(source.factionOf(player)) + "_" + code(source.professionOf(player));
    }

    private static String code(Enum<?> id) {
        if (id == null) return NO_TAG_CODE;
        String name = id.name().toLowerCase(Locale.ROOT);
        return name.substring(0, Math.min(2, name.length()));
    }

    private static boolean isOurs(String teamName) {
        return teamName != null && teamName.startsWith(TEAM_NAMESPACE);
    }

    private static ServerScoreboard scoreboardOf(ServerPlayer player) {
        return player.level().getServer().getScoreboard();
    }
}