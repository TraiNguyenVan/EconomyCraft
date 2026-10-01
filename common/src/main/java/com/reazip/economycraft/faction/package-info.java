/**
 * The four parties: Communism, Capitalism, Monarchy, Anarchism.
 *
 * <p>{@code ANARCHISM} is the default when a player has chosen nothing.
 *
 * <p><strong>Every party levy is either a clean burn or a clean exemption — there is no recipient anywhere
 * in this package.</strong> That is a deliberate simplification of the Monarchy {@code Cống nạp} ("corruption")
 * debuff: the king is flavour only, so the payment debits the player and credits nobody, via the asymmetric
 * {@code transferMoney(from, to, debit, credit = 0, …)}. Building an actual king entity would have
 * introduced four failure paths (king offline, king has no account, king at {@code EconomyManager.MAX},
 * partial transfer) for no gameplay benefit.
 *
 * <p>Money rules, by party:
 *
 * <ul>
 *   <li><strong>Communism</strong> — {@code Đảng phí}: $10 every 45 minutes <em>online</em>, then a tiered
 *       anti-speculation income tax on the remaining balance (D3: highest matched tier, strictly greater
 *       thresholds). Buffs: 50 % chance of no toll <em>tax</em> on toll payment. The {@code Tài trợ} subsidy
 *       buff is read-only in the spec and is deliberately not implemented.</li>
 *   <li><strong>Capitalism</strong> — daily tax at a rate that scales with how much wealth Capitalism holds
 *       (D14). Buff: a purchase from a Capitalism seller's {@code /ah} listing is tax-exempt for the buyer
 *       (D8). Debuff: toll tax +25 %.</li>
 *   <li><strong>Monarchy</strong> — daily tax plus an equal corruption payment. Buffs: halved claim cost,
 *       +15 % damage inside your own claim (both claim-dependent, so they live behind the ShopGuard bridge).
 *       Debuff: 50 % chance of an import tax.</li>
 *   <li><strong>Anarchism</strong> — exempt from every tax, but <strong>still pays toll fees and item
 *       prices</strong>. Getting that distinction right is the single easiest thing to over-apply here.</li>
 * </ul>
 *
 * <p>The faction fiscal pass is <strong>independent of the pre-existing wealth tax</strong>: {@code FiscalPass},
 * {@code FiscalPolicy}, {@code fiscal.json} and all {@code wealth_tax_*} keys are untouched (D4).
 *
 * <p>See {@code TODO.md} §7 Phases 9–10.
 */
package com.reazip.economycraft.faction;
