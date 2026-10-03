package com.reazip.economycraft.api.v1;

import java.util.UUID;

/**
 * Read-only access to a player's party.
 *
 * <p>Added in Phase 10 because ShopGuard needs it and cannot import EconomyCraft's internals: a Monarchy
 * owner pays half for a claim ({@code Tự trị}) and an Anarchist may not claim, receive a transfer, or be
 * trusted ({@code Vô chính phủ}). Both rules live in ShopGuard, so the party has to be readable from
 * outside.
 *
 * <p><strong>Deliberately read-only.</strong> There is no {@code select()} here. Choosing a party is a
 * player-facing action with a 30-hour lockout, a tag refresh and a save-file write, and it belongs to
 * EconomyCraft's own command and UI layers. An API that could write would be a second, unvalidated path to
 * the same state.
 *
 * <p>Like the rest of the API, every method must be called from the server thread.
 */
public interface FactionApi {
    /**
     * The party a player belongs to, as one of the {@link FactionIds} constants.
     *
     * <p>This is the <em>effective</em> party: a player who has never chosen returns
     * {@link FactionIds#DEFAULT}, because that is what every rule in the mod applies to them. It never
     * returns {@code null}, and it never returns a string outside {@link FactionIds} — an unknown id
     * (a hand-edited save file) falls back to the default rather than propagating, so one corrupt record
     * cannot make a consumer's faction rules throw.
     */
    String factionId(UUID playerId);

    /**
     * The party's English display name for that player, e.g. {@code "Monarchy"}. Provided so a consumer can
     * name the party in a refusal message without keeping its own copy of the display strings.
     */
    String factionDisplayName(UUID playerId);

    /** The party treated as unselected, i.e. what {@link #factionId} returns for a player with no choice. */
    String defaultFactionId();

    /**
     * The multiplier a claim-pricing consumer should apply to this player's claim cost, from
     * {@code factions.monarchy.claim_cost_multiplier} — {@code 1.0} (no change) for every other party,
     * and {@code 1.0} for everybody when the party system is disabled.
     *
     * <p>This lives in the faction API rather than in the consumer because <strong>the faction owns the
     * benefit</strong>. A consumer that hard-coded {@code 0.5} would keep charging full price after an
     * admin retuned the key, and nothing would report the drift — the discount would simply stop existing.
     *
     * <p>The value applies to the <em>whole</em> charge, flat fee included. It is inert without a
     * claim system installed.
     */
    double claimCostMultiplier(UUID playerId);
}
