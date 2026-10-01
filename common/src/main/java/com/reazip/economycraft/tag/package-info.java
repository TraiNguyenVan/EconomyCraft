/**
 * Rendering of the two player-visible tags — party and profession — next to a player's name.
 *
 * <p>Both tag types are drawn from the same primitives here so they can never drift apart visually.
 *
 * <p><strong>Two separate vanilla paths, and this is load-bearing.</strong> On 26.3 the nametag above a
 * player's head and the row in the tab list are fed by <em>different</em> server-side methods, so a
 * server-side-only mod can legitimately render different text in each:
 *
 * <ul>
 *   <li>Nametag — {@code EntityRenderer#getNameTag} calls {@code Entity#getDisplayName()}, which
 *       {@code Player} overrides. {@code PlayerTeam#formatNameForTeam} is applied inside it.</li>
 *   <li>Tab list — {@code PlayerTabOverlay} calls {@code ServerPlayer#getTabListDisplayName()}, which is a
 *       vanilla stub that returns {@code null}; the client then falls back to the profile name.</li>
 * </ul>
 *
 * <p>Neither has a public setter on 26.3, so both are reached by mixin. Note that
 * {@code ClientboundPlayerInfoUpdatePacket$Action} already includes {@code UPDATE_DISPLAY_NAME}, so a
 * display-name change can be pushed without a reconnect.
 *
 * <p>Chat is a third, genuinely separate surface: EconomyCraft's own messages can be built directly, while
 * vanilla-generated chat needs a formatting hook.
 *
 * <p>Decisions live in {@code TODO.md} §4 — see D1 (display split) and the {@code displayName} findings
 * recorded under P0-T3, which corrected an earlier assumption that these surfaces were inseparable.
 */
package com.reazip.economycraft.tag;
