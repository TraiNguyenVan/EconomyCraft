/**
 * The single place where tax is decided.
 *
 * <p>Before this package existed the formula {@code Math.round(base * EconomyConfig.get().taxRate)} was
 * copy-pasted into <strong>18 independent call sites</strong> across {@code TollManager}, {@code AuctionTrade},
 * {@code AuctionUi}, {@code OrdersUi}, {@code OrderFulfillment} and {@code EconomyCommands} — half of which
 * compute tax purely to render lore text, and so must never disagree with the real charge.
 *
 * <p>That was not a bug: every tax worked correctly. It was a structural gap that became one the moment
 * factions arrived, because a faction rule such as "Anarchism pays no tax", "Capitalism pays +25 % toll
 * tax" or "a purchase from a Capitalism seller is tax-exempt" has to apply at <em>every</em> site. Editing
 * 18 sites by hand guarantees a miss eventually, and a miss produces an economy that is inconsistent in a
 * way nobody notices because the two affected flows live far apart in the codebase.
 *
 * <p>So every one of those 18 sites is routed through one pure resolver here. {@code TaxScope} names the
 * incidence point, {@code TaxQuote} carries the result plus the {@code MutationSource} to attribute it with,
 * and {@code TaxPolicy} does the arithmetic with no server dependency so it is unit-testable.
 *
 * <p><strong>Invariant:</strong> no {@code taxRate} multiplication may exist outside this package. A
 * source-scanning assertion in the test suite enforces that.
 */
package com.reazip.economycraft.tax;
