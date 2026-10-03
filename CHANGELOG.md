# Changelog

All notable changes to EconomyCraft are documented here. This file is the `changelog-file` consumed by
`.github/workflows/release.yml`, so a release published without updating it ships an empty changelog body.

## Unreleased — Container locks removed

The `Cộng đồng` chest lock (spec D10 / P9-T14) has been **removed in full**, at the server owner's request.
Chests are vanilla again: no `/eco lock`, no `/lock`, no sneak-click menu, no action-bar lock status, and no
refusal to open or break a container. **ShopGuard claim protection and tolls are untouched** and remain the
only two ways a chest can be closed to someone.

### Removed
- `ContainerLockMode`, `ContainerLockPolicy`, `ContainerLockStore`, `ContainerLockUi`, `ContainerLockSection`.
- The `container_lock` config section and `factions.communism.container_lock_mode`, from the bundled default.
- `/lock` and `/eco lock [menu|info|private|party|unlock|clear]`, and the sneak-right-click lock menu.
- The `/eco admin` → **Clear Container Locks** button.
- `ConfigClamp.choice`, whose only caller was the lock mode key.
- The `InteractionEvent.RIGHT_CLICK_BLOCK` and `BlockEvent.BREAK` handlers in `EconomyCraft` — both existed
  only to enforce locks, so the break-side protection those provided is gone with them. Toll break protection
  (registered separately in `EconomyCraftFabric`) and ShopGuard's claim protection are unaffected.
- `data/container_locks.json` is no longer read or written. A copy of the live file, including one `PARTY_ONLY`
  entry, was kept on the server under `config/economycraft/data/.removed-20261003/` before the restart.

**Migration:** none needed. Gson drops the two now-unknown config keys silently, and the keys were removed from
the live `config.json` by hand anyway. Historic entries below are left as written.

## Unreleased — Faction & Profession System

Planning is tracked in `TODO.md`. **Phases 3 through 9 have landed gameplay behaviour**; Phases 0–2 were pure
infrastructure and shipped with none. The baseline is now 313 passing tests on 26.3, both loaders green.

### Added
- Single central tax policy (`tax` package), replacing 19 duplicated `Math.round(base * taxRate)` sites.
- Party tags: Communism, Capitalism, Monarchy, Anarchism (default when nothing is chosen).
- Profession tags: Builder, Farmer, Miner, Merchant, Soldier.
- Online-time accumulator (`OnlineTimeService`), wall-clock cooldown service (`CooldownService`), and the
  `FactionStore` / `ProfessionStore` pair, persisted to `online_time.json`, `cooldowns.json`, `parties.json` and
  `professions.json`. Data only when Phases 0–2 landed; Phases 3–4 added the readers.
- `BlockTags`: the config-driven building-block, ore, double-value-ore and Haste-trigger sets, with tags
  resolved lazily so a `/reload` is honoured and a bad entry is dropped with a warning instead of failing.
- `factions` and `professions` config sections, with the spec's defaults and clamping for every key.
- `container_lock` section and `ContainerLockMode`, with the defaults above. Enforcement landed in Phase 9.

### Changed
- Config merge now covers nested sections, so an existing server gains the new keys without losing any
  hand-tuned values (`EconomyConfigMergeTest`).
- Phases 3–4 are user-visible: see the Phase 3 and Phase 4 entries below. Phases 0–2 changed no behaviour.

### Fixed
- A hand-edited `"factions": null` (or `"container_lock": null`) no longer leaves the section null; it is rebuilt
  from defaults and the mistake is named in the log.
- Phase 3 corrected the reasoning behind its own nametag decision: the nametag is drawn by **client** code, so a
  server-side display-name mixin could never have reached it. The tag is delivered as a synced scoreboard team
  prefix instead — see D21 in `TODO.md`.

### Known gaps
- The 30-hour party and profession lockouts are independent, and each writes nothing until the player actually
  chooses, so "never chose" stays distinguishable from "chose Anarchism" on disk.
- `/eco settings` does not expose the new keys yet; they are file-only.

### Added (Phase 3 — the tag surfaces)
- Party and profession tags now render on a vanilla client, with no client mod: the full coloured word in the tab
  list (`[Communism][Builder] Steve`), the icons above the head (`[☭][⚒] Steve`) and in front of the player's own
  chat. Colours come from the existing per-faction and per-profession config keys.
- `/tag` opens the selection menu, and `/tag <player>` shows another player's tags read-only. Choosing a Party or a
  Profession goes through an explicit confirmation that states the 30-hour lockout before you commit, and a
  locked option shows its remaining time rather than only refusing the click.

### Added (Phase 4 — profession framework + Builder)
- `/eco job` opens the profession menu; `/eco job <profession>` and `/eco job leave` work directly from the
  command line. Choosing a job starts the spec's 30-hour lockout, shown as remaining time; ops bypass it.
- **Builder `Thành thạo`** — placement progress toward Master at 1000 building blocks placed, and a reach bonus
  of +1 block (Apprentice) / +2 (Master) over vanilla's 4.5.
- **Builder `Sửa lỗi`** — Haste I while breaking a trigger block, removed the first tick they are not.

### Known gaps (Phase 4)
- ⚠️ **The reach bonus widens *block interaction* range, not building range.** A Master Builder also reaches 6.5
  blocks to open a chest, read a sign or click an item frame. This is inherent to how the effect is
  implemented (one vanilla attribute both sides already read) and is intended, but it is a real side effect.
- The spec's "only while holding a building block" is deliberately **not** implemented: an attribute modifier
  cannot be conditional on the held item, and gating it on one would kill the reach at the exact moment a
  block is placed, because the held item is then the block that was just placed.
### Added (Phase 5 — Farmer)
- **Farmer progression** — 300 events counting crop planting, mature crop harvesting, animal feeding, and offspring breeding.
- **Farmer `Tươi tốt`** — every 4 minutes, online Farmers trigger a 24-block radius bonemeal boost with a 10% (Apprentice) / 20% (Master) chance per crop.
- **Farmer `Chăm sóc`** — parent breeding cooldown reduced by 10% (Apprentice) / 20% (Master); offspring grow 15% (Apprentice) / 30% (Master) faster (starting age shortened proportionally at birth).
- **Farmer `Khéo léo`** — 1% (Apprentice) / 5% (Master) chance to gain +2 bonus items when taking crafted or cooked edible food (`DataComponents.FOOD`).

### Added (Phase 6 — Miner)
- **Miner progression** — 270 ores total; diamond and gold ores count as 2.
- **Miner `Lanh lợi`** — conditional Haste II when mining trigger blocks (stone, deepslate, tuff, netherrack, and ores), removed when mining stops.
- **Miner `Khéo tay`** — 5% (Apprentice) / 15% (Master) chance of doubled ore drops upon mining any ore block.
- **Miner `Bảo hộ lao động`** — Master only: touching lava grants Regeneration II for 4 s on a 5-minute cooldown.

### Added (Phase 7 — Merchant)
- **Merchant progression** — 50 villager trades (excluding trades with sticks in cost or result) capped at 20 trades per villager (keyed by persistent UUID) AND 5 auction house (`/ah`) purchases.
- **Merchant `Lưỡi không xương`** — 5% (Apprentice) / 15% (Master) discount applied directly to villager trading offers (just like Hero of the Village effect) when right-clicking a villager with a job. Minimum 1 item discount on trades of 2+ items; cost never drops below 1. Discount applies exclusively to villager trades (removed from player-to-player transfers, auction house and orders).

### Added (Phase 8 — Soldier)
- **Soldier progression** — 100 mob kills by the player (excluding players and passive mobs) tracked via `LivingEntity.die` and `ProfessionHooks.onMobKilled`.
- **Soldier `Sắt được tôi thế đấy`** — −5% damage taken / +5% damage dealt (Apprentice), ±15% (Master), scaled by 0.5 when rusty. Applied server-side via `LivingEntity.hurtServer` mixin with `@ModifyVariable` on damage amount.
- **Soldier `Andrenaline`** — Master only: on receiving any harmful status effect (`MobEffectCategory.HARMFUL`), halves duration of incoming and active negative effects if within 4 s of the first negative effect, followed by a 5-minute cooldown. Intercepted via `LivingEntity.addEffect`.

### Added (Phase 9 — Party, the four factions' money effects)
- **Party selection** — `/eco party` opens the tag menu with the party page first; `/eco party <faction>` works
  directly, and `/eco party leave` exists. Choosing goes through the same explicit confirmation as `/tag` and the
  spec's **30-hour lockout**, which `leave` also respects — leaving *is* a party change, so a player who could
  leave and rejoin would have no lockout at all. Ops bypass it.
- **Communism `Đảng phí` + `Thuế thu nhập`** — every 45 minutes of **online** time (not wall clock): a $10 party
  fee, then the tiered income tax on what the fee left. Both amounts arrive in one message. A player who cannot
  afford either is charged what they have and is told the collection failed; the interval is consumed either way,
  so a broke player is never retried every tick. The money is **burned** — no receiver.
- **Communism `Đầu tư công`** — a 50 % chance the toll **tax** is waived. The toll owner still receives the fee,
  because the fee is not a tax.
- **Communism `Cộng đồng`** — the container lock. `/eco lock [info|private|party|unlock|clear]` sets your own
  choice, and `unlock` stores an explicit opt-out rather than deleting your record, because a deleted record
  cannot outrank the server default. Enforced on right-click and on breaking someone else's locked container.
- **Capitalism `Thị trường cạnh tranh`** — buying from a listing whose **seller** is a Capitalism member pays no
  tax at all: the buyer pays the listing price and the seller still receives it.
- **Capitalism `Nhà nước tư bản`** — the daily tax (below) and a 1.25× toll tax rate.
- **Capitalism daily tax** — 5 % of your balance each day, rising with how much of the server's money the party
  holds and with server inflation, with the daily move clamped by `capitalism.max_rate_change_per_day`. The
  charge message names every factor, including whether the clamp bound it, so any number can be explained.
- **Monarchy `Nhập khẩu`** — a 50 % chance of an extra tax of 50 % of the item's tax, on shop buys, `/ah` buys
  and order fulfilment. Never on a tax of 0, and rounded from the already-rounded base tax.
- **Monarchy `Cống nạp`** — a daily tax at 1.7 % of the balance, using **Monarchy's own** inflation signal (money
  in circulation per player against a reference) rather than Capitalism's, and the concentration multiplier
  shared with Capitalism. Burned on payment, as the spec requires — there is no king entity and no recipient.
- **Anarchism `Tự do`** — pays no tax of any kind, and **still pays toll fees and purchase prices**. Tax
  exemption only; the fee and the price are never touched.
- **New transaction sources** — `economycraft:party_fee`, `economycraft:income_tax`, `economycraft:import_tax`,
  `economycraft:daily_tax` and `economycraft:corruption_tax`, all in `FISCAL_SOURCES`, so every faction debit
  shows in `/transactions` and none of them can reach a leaderboard.
- **New config key** — `factions.daily_tax_max_catchup_days` (default `7`) caps how much a server that was off
  for a fortnight will charge on its first tick back; `0` means no cap.
- The daily faction pass is entirely separate from the pre-existing wealth tax: its own day marker in
  `data/faction_fiscal.json`, its own rates, its own clamp. A server's wealth tax is unaffected.

### Fixed
- The faction pass counts a refused debit as **failed**, never as collected, and says so in the log. A player
  who could not pay is not silently reported as having paid.
- **`/eco job` now exists.** Phase 4 documented it, but only the standalone `/job` was ever registered, so
  `/eco job` was an "unknown command" for every player who typed what the changelog said. `/eco tag` and
  `/eco party` are registered the same way.

### Added (Phase 10 — Land claims & claim-dependent faction effects)
- **`ClaimBridge` & ShopGuard integration:** EconomyCraft connects soft-dependently to ShopGuard using reflection. If ShopGuard is absent (or on NeoForge), the bridge logs a single startup warning and degrades cleanly.
- **FactionApi & FactionIds (`api/v1`):** Read-only API surface exposing player party info, party display names, and claim cost multipliers.
- **Monarchy `Phép vua thua lệ làng` (spec 28):** +15 % damage dealt and +15 % damage resistance while standing inside your own land claim, hooked into combat damage pipelines.
- **Anarchism `Thoải mái` (spec 35):** +15 % movement speed and +15 % horse speed on unclaimed wilderness land, applied via vanilla attribute modifiers.
- **ShopGuard-side faction rules:** Monarchy halved claim cost (`Tự trị`), Anarchism disallowed from claiming, receiving transfers, or being trusted (`Vô chính phủ`).

### Known gaps
- **The daily pass and the levies are not unit-tested end to end.** They need an `EconomyManager` on a server
  thread, exactly like the pre-existing wealth pass. What *is* tested is every rule they apply — the rate
  formula, the tier boundaries, the fee-then-tax order, the caps and the rounding — through pure functions, so a
  bug would have to be in the plumbing, not the arithmetic.

### Design decisions taken after the spec
- **Builder reach** (`TODO.md` D11, corrected): the previous conclusion that a server-side mod cannot extend
  reach on a vanilla client was **wrong**, because it looked for the range check in the wrong class.
  `player.block_interaction_range` is a syncable vanilla attribute (default `4.5`, bounds `0.0`–`64.0`) that
  *both* the client's `LocalPlayer.raycastHitResult` and the server's `handleUseItemOn` already read, so
  `Thành thạo` is one `AttributeModifier` — no mixin, no client mod. Phase 4.
- **Monarchy's daily tax** (`TODO.md` D19): Capitalism's formula, one change — inflation read off the server's
  total money rather than the player-activity signal — at `1.7%` instead of `5%`. The reference is per player, so
  the tax means the same thing on a 5-player and a 200-player server.
- **Haste is conditional** (D20): Builder I and Miner II apply only while breaking a block that job counts, and
  are removed the first tick they are not. `haste_duration_seconds` is replaced by `haste_refresh_seconds`,
  which is an anti-flicker window rather than a duration. The Builder's triggers union in `building_blocks`, so
  the 33-entry list is not duplicated into a second key.
- **Container locking is opt-in** (D10 closed): the server default is `UNLOCKED`, a player may lock their own
  container to `PRIVATE`, and Communism's `Cộng đồng` buff is what grants `PARTY_ONLY`. The effective mode of a
  container resolves **buff first, then the owner's own choice, then the server default**, because "who is it
  locked to" has three inputs and resolving them in one place is the only way the answer is predictable.
  Enforced in Phase 9.
- **"Never chose" is not "chose Anarchism"** (P9-T1): a player who picks Anarchism gets a real `ANARCHISM`
  record with a timestamp, like any other party, and a player who has never chosen has **no record at all** and
  reads as Anarchism through the fallback. The task plan suggested persisting the default on first
  interaction; that was not done, because it would make "deliberately chose Anarchism" and "never chose" the
  same row on disk — and every party added later would silently claim players who never opted in.
- **Monarchy's import tax is not a second transaction** (P9-T3): `economycraft:import_tax` is declared and
  listed in `FISCAL_SOURCES`, but Monarchy's surcharge is folded into the one tax it belongs to rather than
  charged as a separate debit, because splitting it would mean every tax site growing a second transfer and a
  second rollback path for a number that is an attribute of the same tax. It is attributed to the scope's own
  source and appears there as a larger amount.
- **"Toll tax +25 %" means a multiplier, not percentage points** (P9-T8, recorded as an Assumption): it
  multiplies the toll tax rate by 1.25. At the default 10 % base the two readings agree (12.5 %), so the
  difference only shows up after an admin changes `tax_rate` — at 4 % this ships a 5 % toll tax where
  percentage points would have shipped 29 %.
- **Which flows count as an "import"** (P9-T9, D7 still open): shop buys, `/ah` buys and order fulfilment.
  Villager trades are **excluded** — D7's recommendation, not a confirmed answer, since the spec says "when
  buying and selling" without naming the flows.

### Notes
- The pre-existing wealth tax (`FiscalPass`, `FiscalPolicy`, `fiscal.json`, `wealth_tax_*`) is deliberately
  untouched by this feature.
- **Supported versions.** The faction and profession system targets **Minecraft 26.3 only**. It still builds
  and runs on the other declared targets (1.21.1, 1.21.11, 26.1.2, 26.2) and on both Fabric and NeoForge, but
  these features are absent there — several vanilla classes this feature hooks (`AgeableMob`,
  `AbstractHorse`, `BreedGoal`, `LavaFluid`, `LivingEntity#hurtServer`,
  `ServerPlayerGameMode#destroyAndAck`, `server.players.NameAndId`) were renamed or reshaped after 1.21.11,
  and supporting them would mean forking all of them. Existing features are unaffected.
