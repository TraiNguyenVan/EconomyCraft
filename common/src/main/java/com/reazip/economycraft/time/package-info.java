/**
 * Time primitives for the faction and profession systems.
 *
 * <p>This package deliberately keeps three unrelated clocks apart, because mixing them is the most
 * likely source of logic bugs in the whole feature (see {@code TODO.md} §5 R10):
 *
 * <ul>
 *   <li>{@code OnlineTimeService} — <strong>accumulates only while the player is online.</strong> Backs the
 *       spec's "X minutes online" convention: the counter starts on join, stops on quit, and resets only
 *       once it passes the threshold. Used by the Communism party fee, the Communism income tax, and the
 *       {@code Lụt nghề} (rust) debuff.</li>
 *   <li>{@code CooldownService} — <strong>wall-clock</strong> expiries. Used by the Farmer's 4-minute crop
 *       window, the Miner's and Soldier's 5-minute cooldowns, and the 30-hour party/profession lockout.</li>
 *   <li>Daily cadence — an epoch-day comparison, owned by the faction fiscal pass. Deliberately a
 *       <em>third</em> thing: it is neither of the above, and it is not the pre-existing wealth-tax
 *       cadence, which must stay untouched.</li>
 * </ul>
 *
 * <p>All three persist independently. All durations and thresholds come from {@code EconomyConfig}.
 */
package com.reazip.economycraft.time;
