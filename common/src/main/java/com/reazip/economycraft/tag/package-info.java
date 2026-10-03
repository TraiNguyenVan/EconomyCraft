/**
 * Rendering of the two player-visible tags — party and profession — next to a player's name.
 *
 * <p>Both tag types are drawn from the same primitives here so they can never drift apart visually.
 *
 * <p><strong>Three surfaces, three different answers — and only one of them is a mixin.</strong> All of this was
 * settled by reading 26.3 bytecode (see {@code TODO.md} P0-T3 rows 15-18e), because the obvious reading of the
 * class names was wrong twice:
 *
 * <ul>
 *   <li><strong>Tab list — a mixin, and it works.</strong> {@code PlayerTabOverlay#getNameForDisplay} calls
 *       {@code ServerPlayer#getTabListDisplayName()}, a vanilla stub that returns {@code null}; the client then
 *       falls back to the profile name. Supplying a component makes the client render that instead <em>and skip
 *       team formatting for the row</em>, so a tab row never double-prefixes. There is no public setter, so this
 *       one is reached by mixin, with the change pushed via
 *       {@code ClientboundPlayerInfoUpdatePacket$Action.UPDATE_DISPLAY_NAME} — no reconnect needed.</li>
 *   <li><strong>Nametag — not a mixin, ever.</strong> {@code EntityRenderer#getNameTag} is <em>client</em> code,
 *       so every client draws the nametag from its own entity and a server-side mixin on
 *       {@code Player#getDisplayName()} would change only what the server renders (death messages, titles).
 *       The one server-side lever is synced scoreboard data: the team prefix, via
 *       {@code PlayerTeam#setPlayerPrefix}, which {@code PlayerTeam#getFormattedName} renders as
 *       {@code prefix + name + suffix} and {@code ServerScoreboard} broadcasts to every client. Leaving
 *       {@code TeamColor} empty keeps the prefix's own RGB, because {@code applyColor} is then a no-op.</li>
 *   <li><strong>Chat sender name — impossible.</strong> The client passes {@code PlayerInfo#getProfile()} into
 *       {@code ChatType$Bound#decorate}, so {@code <Name>} is composed client-side from the account name and no
 *       server value reaches that slot. Only the message <em>content</em> is server-controlled, via
 *       {@code PlayerChatMessage#withUnsignedContent} — and doing that makes every line
 *       {@code ChatTrustLevel.MODIFIED}.</li>
 * </ul>
 *
 * <p>EconomyCraft's own messages are always free: they are server-built components.
 *
 * <p>Decisions live in {@code TODO.md} §4 — D1 for the display split (its nametag half is wrong and superseded
 * by D21), D21 for the nametag's colour trade-off and D22 for the chat badge trade-off, both currently open.
 */
package com.reazip.economycraft.tag;
