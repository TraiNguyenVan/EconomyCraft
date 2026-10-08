# Architecture and contributor reference

Details here are useful when working in the named areas but are not required for every change. Check the
code when something may have changed.

## Loader and client boundaries

There is no client-side mod, custom payload, or custom menu type. The Fabric client entrypoint's
`onInitializeClient()` is empty. Vanilla display channels are chat, action bar, particles, menus, and a
scoreboard objective (`eco_balance`, top five, `balance/1000`, with `FixedFormat` short-money formatting).

Mixin lists are in `fabric/src/main/resources/economycraft.mixins.json` and
`neoforge/src/main/resources/economycraft.mixins.json`. Nine are shared; Fabric-only entries are
`TollPressurePlateMixin`, `TollBasePressurePlateMixin` and `TollHopperMixin`; `ProfessionBreakMixin` is
NeoForge-only.

## Public API

The 18 API v1 types are under `api/src/main/java/com/reazip/economycraft/api/v1/`. Consumers obtain the API
through `EconomyCraftApi.get(server)`; `EconomyCraftApiAccess` is package-private. The faction API is
read-only because faction selection has lockout, tag refresh and persistence side effects. `FactionIds`
provides stable identifiers for integrations. API v1 changes are additive only.

ShopGuard integration adds read-only `inflationMultiplier()` and `medianActiveBalance()` API methods.

## Config and data paths

The bundled config is `common/src/main/resources/assets/economycraft/config.json`. It is the source of truth
for keys and defaults; do not maintain a manually counted key total. Runtime files are stored under
`config/economycraft/` and its `data/` and `logs/` subdirectories. `EconomyPaths.java` determines which files
are importable.

`online_time.json` is intentionally excluded from `/eco import`: importing progression could assign players
fresh factions and professions. `player_activity.json` stores last-seen milliseconds for dynamic pricing;
it is not online time and must not be merged with it.

## Land claims and faction effects

Claims are implemented in ShopGuard, an external Fabric-only mod. EconomyCraft references it reflectively
through package-private `ClaimBridge` and `ReflectiveShopGuardBackend`; `FactionEffects` is the only consumer.
When ShopGuard is absent, claim rules are skipped. The Communism `Tài trợ` entry is deliberately read-only;
any future implementation belongs on the ShopGuard side of the seam.

## Villager dialogue and builder reach

Villager dialogue is private gossip: `/eco gossip dialogue [prof]` sends one line to the interacting player.
There is no shared rumor pool, transaction digest or public chat broadcast option. Villager trading is a
separate minimal feature.

The `Thành thạo` reach bonus modifies `player.block_interaction_range` with an attribute modifier. It affects
all block interactions (including chests, signs and item frames), and cannot be gated by held item because
the placed block is held at placement time.

## Invariants that need manual review

These are not all enforced by tests:

- With every player Anarchist and unemployed, and all new rates zero, `/ah`, `/orders`, `/shop`, `/sell`,
  `/toll`, `/daily` and `/transactions` retain their prior behavior.
- A player without faction or profession remains exempt from those systems and pays the pre-update tax.
- Existing balances, stats, auctions, orders and tolls load unchanged; no migration may alter them.
- A newly importable settings or data file changes `/eco import` behavior and requires an explicit design
  decision.
