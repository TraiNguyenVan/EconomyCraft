/**
 * The five professions: Builder, Farmer, Miner, Merchant, Soldier.
 *
 * <p>A player holds at most one profession, at one of two levels, with one transient third state:
 *
 * <ul>
 *   <li>{@code APPRENTICE} — freshly chosen.</li>
 *   <li>{@code MASTER} — the level-up condition was met without switching away.</li>
 *   <li>{@code RUSTED} ({@code Lụt nghề}) — the player previously reached MASTER, switched away, and
 *       returned. Effects run at 50 % until the 45-minute <em>online</em> timer fires, then the player is
 *       MASTER again. Rusted players earn no profession progress.</li>
 * </ul>
 *
 * <p><strong>Non-negotiable structural rule:</strong> a profession may read its own numbers
 * <em>only</em> through {@code ProfessionEffects}. That is what makes the rust rule impossible to forget —
 * reading {@code level} directly in one job would silently skip the 50 % reduction.
 *
 * <p>Progress counters, per-villager tallies and the rust state persist in {@code ProfessionStore}; vendor
 * data (the Builder's building-block set, the Miner's ore set, the Haste trigger sets) lives in
 * {@code BlockTags} and is resolved lazily, because tag keys are not bound at construction time on 26.3.
 *
 * <p>See {@code TODO.md} §7 Phases 4–8.
 */
package com.reazip.economycraft.profession;
