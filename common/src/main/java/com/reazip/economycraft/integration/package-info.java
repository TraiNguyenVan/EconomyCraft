/**
 * Bridges to mods this one must work alongside but must never require.
 *
 * <p>All cross-mod coupling is isolated here so that the single EconomyCraft-touching class stays
 * package-private and lazily loaded — the same pattern ShopGuard already uses with
 * {@code ClaimEconomy.Backend}, and the reason ShopGuard can be absent without breaking anything.
 *
 * <p><strong>ShopGuard is Fabric-only</strong> ({@code fabric-loom}, {@code ModInitializer}, no NeoForge
 * port). Consequently, on NeoForge the land-claim bridge finds no backend and four faction features are
 * inert: Monarchy {@code Tự trị} and {@code Phép vua}, Anarchism {@code Thoải mái} and {@code Vô chính phủ}.
 * That is a documented per-loader limitation, and the bridge must warn <strong>once</strong> at startup rather
 * than silently no-op — a player who pays a corruption tax deserves to know a buff is not applying.
 *
 * <p>The mirror-image seam also lives here: the claim effects that must be coded <em>inside</em> ShopGuard
 * (halved claim cost, and blocking claim / transfer / trust for Anarchists). Those cannot be delivered from
 * here alone, so Phase 10 spans both repositories.
 */
package com.reazip.economycraft.integration;
