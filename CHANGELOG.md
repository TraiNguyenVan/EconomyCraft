# Changelog

All notable changes to EconomyCraft are documented here. This file is the `changelog-file` consumed by
`.github/workflows/release.yml`, so a release published without updating it ships an empty changelog body.

## Unreleased

### Player-to-player contracts

Players can now post paid work for each other. A contract reserves the full reward in escrow up
front, exactly one contractor accepts, submits the work, and the requester approves to release the
payment. Contracts are public or targeted at one player, carry a category, deadline and revision
budget, and run through a vanilla chest UI (`/contracts`, plus `/eco contracts`) with headless
single-action subcommands (`view`, `accept`, `submit`, `approve`, `revise`, `cancel`, `dispute`).

Cancelling an open contract refunds the escrow at once; after acceptance both sides must agree.
Missed deadlines expire and refund, unreviewed submissions auto-approve after the review window, and
either side can dispute submitted work — disputes freeze the contract until an admin resolves it
with `/contracts admin resolve <id> pay|refund`. Failed payouts and refunds stay recorded as
pending settlements and retry automatically without ever paying twice. New config keys:
`contracts_enabled`, `max_contract_reward`, `contract_default_duration_hours`,
`contract_max_duration_hours`, `contract_review_hours`, `contract_max_revisions`,
`contract_revision_extension_hours` and `max_active_contracts_per_player`; new permission nodes
`economycraft.command.contracts` and `economycraft.admin.contracts`.

### Single-file SQLite persistence (`economycraft.db`)

Everything under `config/economycraft/data/` now lives in one SQLite file, `economycraft.db`. Each
legacy JSON document (balances, auctions, orders, quests, tolls, …) is one row in a `documents`
table carrying the exact same Gson payload the file used to hold; villager gossip memory, already
relational, keeps its three tables in the same file instead of a separate `villagers.db`. Writes are
synchronous on the server thread. In-memory behavior is unchanged — only the durability layer moved.

**Your data needs no edit.** On first boot every legacy `*.json` document and `villagers.db` is
imported exactly once (empty-table guard), then archived under `data/_migrated-json/` — never
deleted, so a failed boot can always be reconstructed by hand. `/eco import` carries
`economycraft.db` like any other data file, and old shared folders full of JSON still import: the
legacy files are picked up on boot. This change sits atop the quest-engine market build, so its
quest fields (`backfillKeys`, `lastPeriodKeys`, `autoMarketBlacklist`, `recentPurchases`,
`unsoldExpiries`) persist like all the rest.

### Public villager gossip removed; dialogue is private only

Villager speech is now delivered **only** to the player who opened the trade interface, under every
configuration. There is no longer any way to let villagers talk in server chat.

This removes the whole shared rumor pipeline, not just the broadcast call:

- `public_chat`, `public_chat_chance`, `refresh_interval_minutes`, `system_instruction` and
  `pool_size_per_category` are gone from the `gemini_gossip` section (14 keys → 9).
- The rumor pool, its profession categories, the profession→category mapper, the transaction digest, the
  periodic digest worker and the on-demand single-rumor generator are deleted from the source tree.
- The private prompt loses its "Word from your fellow villagers across the realm" section, because the pool it
  drew from no longer exists. Everything else the prompt carries is untouched: the villager's personality and
  backstory, its memory of your visits and trades, what it currently sells, and repetition avoidance.
- `/eco gossip refresh` and `/eco gossip test` are removed — both existed only to populate and sample the
  pool. They now return *Unknown subcommand*. `/eco gossip status`, `/eco gossip dialogue`,
  `/eco gossip reload`, and `/eco gossip memory inspect|clear` are unchanged.
- `/eco gossip status` no longer reports a rumor count or a refresh interval, and reports circuit-breaker
  state from the client rather than from the deleted worker.

**Your `config.json` needs no edit.** Removed keys are skipped when reading, so an existing file boots
normally. The next time anything saves the config, the stale keys are pruned for you silently.

**Deliberately kept**, because the private path depends on them and they are not gossip-pool machinery:
player archetype anonymization, the prompt-injection sanitizer, and the recently-spoken-line tracker. The
last of these now records only real dialogue, since nothing else writes to it.

### An undecided player is an Anarchist

`FactionId.defaultFaction()` is Anarchism, so a player who has never run `/eco party` already *is* one as far
as the store is concerned. Until now three of the four party rules read the **record** instead of the id, so
that player paid full tax, wore no tag and got no speed bonus while being refused land — a state that reads as
a bug on the tab list. All four rules now key on the effective id.

### Changed
- **Taxes** — an undecided player is exempt from every scope, exactly like a member of Anarchism. Previously
  they paid full tax in all of them.
- **Tags** — an undecided player wears `Ⓐ` on the tab list and the nametag. The tag follows the party a player
  is actually subject to; it is the one surface every player always sees, so it must not disagree with the
  rules. `TagDisplayService.TagSource` loses `hasChosenFaction` for this reason.
- **Speed** — Anarchism's +15 % on unclaimed land and horses applies to an undecided player.

Choosing a party lifts all of it, and `/eco party reset` restores the default on purpose.

### Unchanged, deliberately
- `FactionStore.hasChosen` and `FactionApi.hasChosen` still exist. The store's rule 1 — reading a player's
  party must not create a choice — depends on them, as does the party menu's
  `current: Vô chế [mặc định]` line, which is a statement about the *choice* rather than about the party.
- A corrupt save still fails open: `hasChosen` is `false` for an unreadable record, and the tag layer no longer
  consults it.

The land side of this — an undecided player being refused claims, and land they already hold being released and
refunded — lives in `shopguard`, which was changed to match.

### Price offers hub

Price offers — the non-binding bids a player can leave on someone else's auction listing or order
request — now have one screen instead of two hunt-downs, and the messages that announce an offer
link straight into it.

### Added
- **Offers hub** at `/eco offers` (standalone alias `/offers`), and as a **Price offers** button in the
  `/eco` menu showing how many offers are waiting on your items and how many of yours are open.
  Incoming rows come first, one per target, carrying the best offer and the offer count; a row opens
  the existing per-listing / per-request review screen, which still owns accept, decline and the
  notify fan-out. Outgoing rows are one per offer you made and offer **Withdraw**, which tells the
  owner their queue changed.
- **Deep links**: `/eco offers ah <id>` and `/eco offers order <id>` open one target's offers directly.
  The "Review it now" click in an offer notification runs exactly that command, so the click lands on
  the offers it is about. The login prompt for players who were offline when an offer arrived now
  links the hub too — it used to link `/ah`, which silently ignored offers on order requests.
- `NegotiationStore.offersByProposer` — the offerer's own view, newest first (the mirror of
  `offersFor`, which answers for the target's owner).
- `OffersHubModel` — the row selection rules, kept free of Minecraft types and unit-tested: one row
  per target for incoming, one per offer for outgoing, incoming first, bot targets excluded, and no row
  for a target that has already vanished.
- Permission node `economycraft.command.offers`.

### Changed
- `NegotiationEvents.notifyWithdrawn` — the owner is now told when an offerer withdraws, instead of a
  row silently disappearing from their review queue.
- `NegotiationEvents.countOffersOnPlayerTargets` removed; the hub is the single place that counts.

### Container locks removed

The `Cộng đồng` chest lock has been **removed in full**, at the server owner's request.
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

### Faction & Profession System

This section records what landed. The baseline at the time of writing was 313 passing tests on 26.3, both
loaders green.

### Added
- Single central tax policy (`tax` package), replacing 19 duplicated `Math.round(base * taxRate)` sites.
- Party tags: Communism, Capitalism, Monarchy, Anarchism (default when nothing is chosen).
- Profession tags: Builder, Farmer, Miner, Merchant, Soldier.
- Online-time accumulator (`OnlineTimeService`), wall-clock cooldown service (`CooldownService`), and the
  `FactionStore` / `ProfessionStore` pair, persisted to `online_time.json`, `cooldowns.json`, `parties.json` and
  `professions.json`. The files landed as data first; the gameplay readers came after.
- `BlockTags`: the config-driven building-block, ore, double-value-ore and Haste-trigger sets, with tags
  resolved lazily so a `/reload` is honoured and a bad entry is dropped with a warning instead of failing.
- `factions` and `professions` config sections, with the spec's defaults and clamping for every key.
- The `container_lock` section and `ContainerLockMode`. Both were subsequently removed; see "Container locks
  removed" above.

### Changed
- Config merge now covers nested sections, so an existing server gains the new keys without losing any
  hand-tuned values (`EconomyConfigMergeTest`).
- The later entries below are user-visible. The initial infrastructure changed no behaviour on its own.

### Fixed
- A hand-edited `"factions": null` (or `"container_lock": null`) no longer leaves the section null; it is rebuilt
  from defaults and the mistake is named in the log.
- The reasoning behind the nametag decision was itself corrected: the nametag is drawn by **client** code, so a
  server-side display-name mixin could never have reached it. The tag is delivered as a synced scoreboard team
  prefix instead.

### Known gaps
- The 30-hour party and profession lockouts are independent, and each writes nothing until the player actually
  chooses, so "never chose" stays distinguishable from "chose Anarchism" on disk.
- `/eco settings` does not expose the new keys yet; they are file-only.

### Added (tag surfaces)
- Party and profession tags now render on a vanilla client, with no client mod: the full coloured word in the tab
  list (`[Communism][Builder] Steve`), the icons above the head (`[☭][⚒] Steve`) and in front of the player's own
  chat. Colours come from the existing per-faction and per-profession config keys.
- `/tag` opens the selection menu, and `/tag <player>` shows another player's tags read-only. Choosing a Party or a
  Profession goes through an explicit confirmation that states the 30-hour lockout before you commit, and a
  locked option shows its remaining time rather than only refusing the click.

### Added (profession framework + Builder)
- `/eco job` opens the profession menu; `/eco job <profession>` and `/eco job leave` work directly from the
  command line. Choosing a job starts the spec's 30-hour lockout, shown as remaining time; ops bypass it.
- **Builder `Thành thạo`** — placement progress toward Master at 1000 building blocks placed, and a reach bonus
  of +1 block (Apprentice) / +2 (Master) over vanilla's 4.5.
- **Builder `Sửa lỗi`** — Haste I while breaking a trigger block, removed the first tick they are not.

### Known gaps (profession framework)
- ⚠️ **The reach bonus widens *block interaction* range, not building range.** A Master Builder also reaches 6.5
  blocks to open a chest, read a sign or click an item frame. This is inherent to how the effect is
  implemented (one vanilla attribute both sides already read) and is intended, but it is a real side effect.
- The spec's "only while holding a building block" is deliberately **not** implemented: an attribute modifier
  cannot be conditional on the held item, and gating it on one would kill the reach at the exact moment a
  block is placed, because the held item is then the block that was just placed.
### Added (Farmer)
- **Farmer progression** — 300 events counting crop planting, mature crop harvesting, animal feeding, and offspring breeding.
- **Farmer `Tươi tốt`** — every 4 minutes, online Farmers trigger a 24-block radius bonemeal boost with a 10% (Apprentice) / 20% (Master) chance per crop.
- **Farmer `Chăm sóc`** — parent breeding cooldown reduced by 10% (Apprentice) / 20% (Master); offspring grow 15% (Apprentice) / 30% (Master) faster (starting age shortened proportionally at birth).
- **Farmer `Khéo léo`** — 1% (Apprentice) / 5% (Master) chance to gain +2 bonus items when taking crafted or cooked edible food (`DataComponents.FOOD`).

### Added (Miner)
- **Miner progression** — 270 ores total; diamond and gold ores count as 2.
- **Miner `Lanh lợi`** — conditional Haste II when mining trigger blocks (stone, deepslate, tuff, netherrack, and ores), removed when mining stops.
- **Miner `Khéo tay`** — 5% (Apprentice) / 15% (Master) chance of doubled ore drops upon mining any ore block.
- **Miner `Bảo hộ lao động`** — Master only: touching lava grants Regeneration II for 4 s on a 5-minute cooldown.

### Added (Merchant)
- **Merchant progression** — 50 villager trades (excluding trades with sticks in cost or result) capped at 20 trades per villager (keyed by persistent UUID) AND 5 auction house (`/ah`) purchases.
- **Merchant `Lưỡi không xương`** — a 5% (Apprentice) / 15% (Master) reduction of the **tax** on the merchant's
  transactions, applied through the central `TaxPolicy` resolver rather than as a second pricing mechanism. It
  covers villager trades with a job and `/ah` purchases; it does not lower the price paid, only the tax on it.

### Added (Soldier)
- **Soldier progression** — 100 mob kills by the player (excluding players and passive mobs) tracked via `LivingEntity.die` and `ProfessionHooks.onMobKilled`.
- **Soldier `Sắt được tôi thế đấy`** — −5% damage taken / +5% damage dealt (Apprentice), ±15% (Master), scaled by 0.5 when rusty. Applied server-side via `LivingEntity.hurtServer` mixin with `@ModifyVariable` on damage amount.
- **Soldier `Andrenaline`** — Master only: on receiving any harmful status effect (`MobEffectCategory.HARMFUL`), halves duration of incoming and active negative effects if within 4 s of the first negative effect, followed by a 5-minute cooldown. Intercepted via `LivingEntity.addEffect`.

### Added (party, the four factions' money effects)
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
- **Capitalism daily tax** — 2.5 % of your balance each day, rising with how much of the server's money the party
  holds and with server inflation, with the daily move clamped by `capitalism.max_rate_change_per_day`. The
  charge message names every factor, including whether the clamp bound it, so any number can be explained.
- **Monarchy `Nhập khẩu`** — a 50 % chance of an extra tax of 50 % of the item's tax, on shop buys, `/ah` buys
  and order fulfilment. Never on a tax of 0, and rounded from the already-rounded base tax.
- **Monarchy `Cống nạp`** — a daily tax at 1 % of the balance, using **Monarchy's own** inflation signal (money
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
- **`/eco job` now exists.** It was documented here earlier, but only the standalone `/job` was ever registered, so
  `/eco job` was an "unknown command" for every player who typed what the changelog said. `/eco tag` and
  `/eco party` are registered the same way.

### Added (land claims & claim-dependent faction effects)
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
- **Builder reach** (corrected): the previous conclusion that a server-side mod cannot extend
  reach on a vanilla client was **wrong**, because it looked for the range check in the wrong class.
  `player.block_interaction_range` is a syncable vanilla attribute (default `4.5`, bounds `0.0`–`64.0`) that
  *both* the client's `LocalPlayer.raycastHitResult` and the server's `handleUseItemOn` already read, so
  `Thành thạo` is one `AttributeModifier` — no mixin, no client mod.
- **Monarchy's daily tax**: Capitalism's formula, one change — inflation read off the server's
  total money rather than the player-activity signal — at `1%` instead of `2.5%`. The reference is per player, so
  the tax means the same thing on a 5-player and a 200-player server.
- **Haste is conditional**: Builder I and Miner II apply only while breaking a block that job counts, and
  are removed the first tick they are not. `haste_duration_seconds` is replaced by `haste_refresh_seconds`,
  which is an anti-flicker window rather than a duration. The Builder's triggers union in `building_blocks`, so
  the 33-entry list is not duplicated into a second key.
- **Container locking was opt-in — removed.** It shipped as: the server default `UNLOCKED`, a player may lock
  their own container to `PRIVATE`, and Communism's `Cộng đồng` buff grants `PARTY_ONLY`, resolving **buff
  first, then the owner's own choice, then the server default**. It was removed in full; see "Container locks
  removed" above. The reasoning is kept because the same three-input resolution pattern is the reason toll and
  claim rules resolve in one place rather than at each call site.
- **"Never chose" is not "chose Anarchism"**: a player who picks Anarchism gets a real `ANARCHISM`
  record with a timestamp, like any other party, and a player who has never chosen has **no record at all** and
  reads as Anarchism through the fallback. The task plan suggested persisting the default on first
  interaction; that was not done, because it would make "deliberately chose Anarchism" and "never chose" the
  same row on disk — and every party added later would silently claim players who never opted in.
- **Monarchy's import tax is not a second transaction**: `economycraft:import_tax` is declared and
  listed in `FISCAL_SOURCES`, but Monarchy's surcharge is folded into the one tax it belongs to rather than
  charged as a separate debit, because splitting it would mean every tax site growing a second transfer and a
  second rollback path for a number that is an attribute of the same tax. It is attributed to the scope's own
  source and appears there as a larger amount.
- **"Toll tax +25 %" means a multiplier, not percentage points** (recorded as an Assumption): it
  multiplies the toll tax rate by 1.25. At the default 10 % base the two readings agree (12.5 %), so the
  difference only shows up after an admin changes `tax_rate` — at 4 % this ships a 5 % toll tax where
  percentage points would have shipped 29 %.
- **Which flows count as an "import"** (still open): shop buys, `/ah` buys and order fulfilment. Villager
  trades are **excluded** — a recommendation, not a confirmed answer, since the spec says "when buying and
  selling" without naming the flows.

### Notes
- The pre-existing wealth tax (`FiscalPass`, `FiscalPolicy`, `fiscal.json`, `wealth_tax_*`) is deliberately
  untouched by this feature.
- **Supported versions.** The faction and profession system targets **Minecraft 26.3 only**. It still builds
  and runs on the other declared targets (1.21.1, 1.21.11, 26.1.2, 26.2) and on both Fabric and NeoForge, but
  these features are absent there — several vanilla classes this feature hooks (`AgeableMob`,
  `AbstractHorse`, `BreedGoal`, `LavaFluid`, `LivingEntity#hurtServer`,
  `ServerPlayerGameMode#destroyAndAck`, `server.players.NameAndId`) were renamed or reshaped after 1.21.11,
  and supporting them would mean forking all of them. Existing features are unaffected.
