# EconomyCraft — Faction & Profession System (Implementation Plan)

**Status:** Phase 2 complete (data & save layer). No gameplay behaviour yet.
**Spec:** `/home/capcap/Git/Vibe code plugin.md` (67 lines, Vietnamese) — the single source of truth for *what*.
**This file:** the source of truth for *how and in what order*.

> **Anti-drift protocol.** Before starting any phase, read §0 and the phase's own section top-to-bottom.
> Every task has a stable ID (`P<n>-T<n>`). Do not reorder phases without updating §8 (traceability matrix).
> If a task's acceptance criteria cannot be met, stop and record the blocker in §5 — do not silently
> substitute a weaker implementation. If a spec line is ambiguous, do **not** guess: it must first be
> resolved in §4 (open decisions) or recorded as an explicit "Assumption" on the task.

---

## 0. How to use this document

1. Work strictly in phase order. Phases 0–2 are pure infrastructure/refactors with **no gameplay change**;
   they exist so that Phases 4–10 do not each reinvent a tax calculator, a timer, or a save file.
2. Every phase ends with a **verification command** that must go green before the next phase starts.
3. Do not skip ahead to "the fun parts". The profession jobs are trivial once §2 (data) and §3 (tag display)
   exist, and near-impossible to retrofit afterwards.
4. If you discover work that belongs to a later phase, add it as a note in that phase. Do not implement it early.

---

## 1. Ground rules (apply to every task)

- **Server-side only. No client mod, no custom packets, no registered `MenuType`.** EconomyCraft's entire
  UI is vanilla server-side `AbstractContainerMenu` over `MenuType.GENERIC_9xN`; `onInitializeClient()` is
  empty. The new feature must keep that property — it must work with an unmodified vanilla client.
- **All API entry points keep the `requireServerThread()` contract.** Anything reachable from the public
  `api/v1` surface must call it, exactly as `EconomyCraftApiImpl` does today.
- **New money movement uses `EconomyManager.transferMoney(from, to, debit, credit, source, detail)`** —
  the asymmetric form — for every levy (tax is *burned*, not credited). Never `addMoney` to a tax sink.
- **New `MutationSource` names must satisfy `MutationSource`'s charset rules**: lowercase only, namespace
  `[a-z0-9._-]+`, reason `[a-z0-9/._-]+`, exactly one `:`. Use the `economycraft:` namespace, except
  ShopGuard integration money which stays `shopguard:`.
- **Every new `MutationSource` that is a levy or a rebate must be added to `FISCAL_SOURCES`**
  (`EconomyManager.java:63`) or it will pollute leaderboard `earned`/`spent` and the scoreboard.
- **No player-facing balance may be changed by the new system except through the balance mutation engine**,
  so `/transactions`, the webhook and the daily log keep working.
- **Config is the only tuning surface.** All rates, thresholds, weights, durations and colours go into
  `EconomyConfig` (which auto-merges new keys from the bundled default) — never hard-coded literals in
  gameplay classes. `EconomyConfig`'s clamp-and-warn validation style is mandatory for every new numeric key.
- **Persist through `AsyncFileWriter` + the existing store conventions**, and save on
  `LifecycleEvent.SERVER_STOPPING` alongside `manager.save()`. Reserve synchronous writes (as
  `TollManager.save()` does) for state that must survive an immediate crash.

---

## 2. Architecture baseline — what exists today

Verified by reading the code, not by assumption.

| Area | Reality |
|---|---|
| Build | Architectury multi-project: `api/`, `common/`, `fabric/`, `neoforge/`. `build.gradle:30-36` `ext.mcTargets` drives 1.21.1 / 1.21.11 / 26.1.2 / 26.2 / 26.3. Default `gradle.properties`: `minecraft_version=26.3`, `mod_version=1.10.0`, Java 25 for 26.x. |
| Source sets | `main` + `modern`\|`legacy121` + `unobfuscated`\|`obfuscated` compat forks. `test26_3` added to `test` only for 26.3. **Any vanilla API that differs across versions goes in a compat fork, following `util/*Compat.java`.** |
| Public API | `api/v1`, 16 types, accessed via `EconomyCraftApi.get(server)`; provider installed once by `EconomyCraftApiBootstrap`. `inflationMultiplier()` and `medianActiveBalance()` were added for ShopGuard — precedent for extending v1 additively. |
| Persistence | Gson JSON only, no NBT/DB. `EconomyPaths` → dedicated: `config/economycraft/{config,prices,webhook}.json` + `data/*.json` + `logs/*.log`. ShopGuard uses compressed NBT in `<world>/shopguard/claims.dat`. **EconomyCraft never uses NBT; follow that.** |
| Tax | **18 independent sites** each doing `Math.round(x * EconomyConfig.get().taxRate)`: `TollManager:151`, `AuctionUi:69,130,165,313,463,561`, `AuctionTrade:48`, `OrdersUi:84,121,154,270,437,537`, `OrderFulfillment:158,309,315`, `EconomyCommands:844,978`. **There is no central tax policy.** ⚠️ *Every one of these taxes works correctly today — this is a structural gap, not a bug.* Phase 1 is a zero-behaviour-change refactor that exists only so faction tax rules have a single place to live. |
| Tax incidence | **The `/ah` buyer pays the tax** (`AuctionTrade:48` — buyer debits `cost+tax`, seller is credited `cost`). There is **no seller-side listing fee**. Shop/orders work the same way. |
| Recurring levies | Only `FiscalPass`/`FiscalPolicy` (`wealth_tax`, off by default, daily, back-fillable). `player_activity.json` stores **last-seen millis** for dynamic pricing — it is *not* accumulated online time. |
| Online-time tracking | **Does not exist.** |
| Land claims | **Not in EconomyCraft.** Live in ShopGuard (`ClaimStore.claimAt(dim,x,z)`, `Claim.mayBuild(uuid)`, `ClaimTool.add`). ShopGuard depends on EconomyCraft one-way via `ClaimEconomy.Backend` (compileOnly jar + `FabricLoader.isModLoaded` gate). EconomyCraft references ShopGuard nowhere. |
| Villager trading | **Not in EconomyCraft at all.** Zero `Merchant`/`Villager` references. Needs new mixins. |
| Networking | None. Zero custom payloads. Server→client is vanilla chat, action bar, particles, and vanilla menus. |
| Vanilla events hooked | Architectury `PlayerEvent.PLAYER_JOIN`, `TickEvent.SERVER_POST`, `CommandRegistrationEvent`, `LifecycleEvent`; Fabric `UseBlockCallback`, `PlayerBlockBreakEvents`, `ServerTickEvents`. |
| Mixins | 3, **Fabric only** (`TollPressurePlateMixin`, `TollBasePressurePlateMixin`, `TollHopperMixin`). NeoForge has none — an existing parity gap. |
| Display | Vanilla scoreboard objective `eco_balance` (top 5, `balance/1000` + `FixedFormat` short-money override); `TollHud` action bar; `setDisplayName` is **unused today**. |
| Player identity | 3-tier resolution: online → `ProfileCompat` cached → NeoForge `UsernameCache` (reflective), with async v4 profile lookup + 5-min retry. **Never** mints synthetic `OfflinePlayer:` UUIDs. |
| Tests | Only 2 classes: `fiscal/FiscalPolicyTest` (~40 pure JUnit) and `test26_3/TollUiTest` (488 lines, mocks server + bootstraps 26.3 component registries). |
| Verify command | `./gradlew -Pfilter_platforms=fabric -Pminecraft_version=26.3 :common:test :fabric:build` |

---

## 3. Interpretation of the spec (read this before §4)

- `thuần trên server` = **server-side only, vanilla-client-compatible.** It does *not* mean "Fabric loader only".
  So: implement in `common` + thin per-loader adapters, matching the repo. (Still requires D10 to be closed.)
- `X phút online` — the convention at spec line 1: a counter that **accumulates while the player is online,
  stops while offline, and only resets once it passes X**. Reset triggers the steps in the enclosing section.
  This is a first-class primitive (§P2), used by: Communism party fee, Communism income tax, and the
  `Lụt nghề` (rust) debuff. It is **not** the wall-clock cooldown used by Farmer/Miner/Soldier effects.
- `Tài trợ` (subsidy, Communism buff) is explicitly **read-only** in the spec — implement nothing. See §9.
- Every number in the spec (1000 blocks, 270 ores, 45 min, 30 h, $10 000, 0.5 %…) is a **default in
  `EconomyConfig`**, not a constant.

---

## 4. Open decisions — MUST be closed before the named phase

Blocking decisions are marked 🔴. Nothing in the blocking phase may start until they are answered.

| ID | Question | Blocks | Recommendation |
|---|---|---|---|
| **D1** ✅ | **Tab list vs nametag.** I originally asserted these were inseparable. **P0-T3 disproved that.** On 26.3 the nametag is `EntityRenderer#getNameTag` → `Player#getDisplayName()`, while the tab row is `PlayerTabOverlay` → `ServerPlayer#getTabListDisplayName()` (a vanilla stub returning `null`). Different methods ⇒ a server-side-only mod **can** render different text in each. | P3 | ✅ **DECIDED — YOUR ORIGINAL SPEC, now implementable.** Full coloured word tag (`[Communism]` in red) in the **tab list**, via a `ServerPlayer#getTabListDisplayName()` mixin at `RETURN`, pushed with `ClientboundPlayerInfoUpdatePacket$Action.UPDATE_DISPLAY_NAME` so it applies without a reconnect. Short **icon only** (`[☭]`) in the **nametag** (`Player#getDisplayName()` mixin) and in **chat**, where long names would be unreadable. Still **no client mod, no custom packet, no registered menu** (§1 rule 1). Cost: two mixins instead of one. ⚠️ **The nametag half of this decision was wrong and is superseded by D21.** `EntityRenderer#getNameTag` is **client** code, so a server-side `Player#getDisplayName()` mixin changes only what the *server* renders (death messages, titles) and never what another client draws over a head. The tab-list half stands: `ServerPlayer#getTabListDisplayName()` is server-side and its client consumer does use it. **Do not implement the nametag mixin.** The icon now travels through a scoreboard team prefix instead. |
| **D2** ✅ | **Version + loader scope.** | P0 | ✅ **DECIDED — BOTH loaders, NeoForge parity required.** All gameplay logic in **`common`** with the repo's compat forks (`modern`/`legacy121`, `unobfuscated`/`obfuscated`). Vanilla hooks in thin per-loader adapters (`fabric/` + `neoforge/`). **NeoForge must be built out properly, not deferred.** Default dev target 26.3.<br>⚠️ **Accepted consequence of the loader split:** ShopGuard is **Fabric-only** (`fabric-loom`, `ModInitializer`, no NeoForge port), so on NeoForge `ClaimBridge` finds no backend and the **four claim-dependent faction features are inert** — Monarchy `Tự trị` + `Phép vua`, Anarchism `Thoải mái` + `Vô chính phủ`. That is documented as a per-loader limitation in the README's Limitations section (invariant: **never** silently no-op — if the bridge is absent, say so once at startup). The other ~10 faction features and all 5 professions work on both loaders.<br>**Cost of Fabric-only instead** (rejected, recorded so the option stays visible): only the *mixins* are loader-specific — Architectury already neutralises join/quit, tick, commands and lifecycle, and all `common` logic is loader-agnostic. So it halves the build matrix `2×5 → 5` in `release.yml` and defers ~10–13 mixin classes, but porting them later is **not** mechanical (NeoForge mappings differ, so each needs re-derivation + re-testing). The real trap: `common` compiles on both loaders, so Fabric-only wiring yields code that looks right and silently does nothing on NeoForge — which is worse than the feature being absent.
| **D3** ✅ | **Communism income-tax brackets** (0.5 % > $10 000, 0.75 % > $15 000, 1.25 % > $22 000): single-tier (highest matched) or cumulative/marginal? Are thresholds strictly greater, or inclusive? Applied to balance *after* the $10 party fee? | P9 | ✅ **DECIDED.** **Highest-matched single tier** (not cumulative, not marginal), thresholds are **strictly greater than** the stated amount (exactly $10 000 pays nothing), and the rate is applied to the balance **remaining after the $10 party fee is deducted**. Reuse `FiscalPolicy`'s invariants: the levy may never drive a balance below $0, and the whole collection is one operation so the player sees a single combined figure. |
| **D4** ✅ | **"Daily tax" is undefined** in the spec. Enumerating every `removeMoney`/`transferMoney` site and date comparison found exactly **one** recurring daily debit in either repo: `FiscalPass:161` (`"Daily wealth tax"`). | P9 | ✅ **DECIDED — leave `wealth_tax` alone; build our own.** `FiscalPass`, `FiscalPolicy`, `fiscal.json`, `wealth_tax_*` and `wealth_tax_rebate_*` are **pre-existing and not the spec's** ⇒ **untouched**: no faction flags, no added config keys, no new epoch-day compare inside them, no edits to `FiscalPolicyTest`. The spec's daily taxes (Capitalism's, Monarchy's corruption) are **ours**, so they get an **independent** pass: new `faction/FactionFiscalPass.java` (own epoch-day cadence + catch-up) and new `faction/FactionFiscalPolicy.java` (pure, own tests), with **its own** state file `data/faction_fiscal.json`. It may only **read** `EconomyCraftApi.inflationMultiplier()` / `medianActiveBalance()` — never write through them. ✅ Benefits: the pre-existing fiscal path cannot regress (invariant 2), and the earlier `inactive_multiplier 3.33 × 5 % = 16.65 %` compounding hazard **disappears** because we never touch `wealth_tax_inactive_multiplier`. ⚠️ Accepted consequence: `wealth_tax` and the faction daily tax are **independent**, so if an admin enables both, a player can pay both on the same day — correct and intended, but state it in the README. ⚠️ Also: our daily tax is **not** off by default, because it is the spec's feature — it is gated by a plain `factionSystemEnabled` flag instead. |
| **D5** ✅ | **Who is "the king"?** Monarchy `Cống nạp` pays "the king". There is no king role in the spec. | P9 | ✅ **DECIDED — the king is flavour only.** There is **no king entity, no king pointer, and no recipient**. The corruption payment is a **pure burn**: debit the player, credit nobody, via `transferMoney(player, …, debit, 0, source, …)`. This also deletes the whole failure-path class the decision would otherwise create (king offline, king has no account, king at `EconomyManager.MAX`, partial transfer). The only failure path left is "player cannot afford it" → follow the same rule as the other levies. |
| **D6** | **Merchants level-up: AND or OR?** Spec lists "50 villager trades" *and* "5 `/ah` purchases" under one heading. | P7 | AND (both), with two independent progress bars shown in the UI. |
| **D7** | **"Import" scope for Monarchy `Nhập khẩu`** — 50 % chance of an extra tax of 50 % of "the item's tax". Which flows are imports: fixed-price shop buy, `/ah` buy, order fulfilment, villager trade? | P9 | Shop buy + `/ah` buy + order fulfilment = imports. Exclude sells (the seller is exporting) and tolls. Confirm with the designer. |
| **D8** ✅ | **Capitalism `/ah` buff.** Today the **buyer** pays `/ah` tax; the seller pays nothing. The spec's buff gives the *seller* a selling-price advantage, which implies a seller-side levy that **does not exist today**. | P9 | ✅ **DECIDED.** **No seller-side listing fee is introduced — no new money sink is created.** Instead, the existing buyer-paid tax is **exempted when the *seller* is a Capitalism member**: the buyer pays exactly the listing price, the seller still receives the full price, and the seller's undercut price brings repeat buyers. Condition is checked on the **seller's** faction. Selling via `/ah` costs the seller nothing either way; non-Capitalism sellers are unaffected. |
| **D9** | **`Lụt nghề` (rust) semantics.** Player who had reached Master, switched away and returned: effects at 50 % "within 45 min online", then back to Master. Does the 45-min clock start on *re-selecting* the job, or on *becoming* rust? Is it per-job or global? | P4 | Per-job, started when the job is (re)selected while the player has a prior Master record. Progress is reset on switch (spec: "reset achievements"). |
| **D10** ✅ | **`Cộng đồng` chest lock.** "2 options: lock for yourself, or not lock" — what does *lock* actually restrict, and which container types? | P9 | ✅ **DECIDED.** Applies to **every `Container` block**: chest, trapped chest, ender chest, shulker box, barrel, furnace, blast furnace, smoker, brewing stand, hopper, dropper, dispenser, chest minecart, and any modded `Container` — i.e. a `blockEntity instanceof Container` test, not a block allow-list, so it composes with the existing shulker handling in `SellService`. Per-chest mode, stored in `data/container_locks.json`: **`PARTY_ONLY`** (non-members denied — the buff's stated intent, **default**), **`PRIVATE`** (everyone but the owner denied — the spec's literal "lock for self" option), **`UNLOCKED`**. **D10 closed by the designer (three answers, not one flag):** the **server default is `UNLOCKED`** — containers are not locked unless someone asks, because a lock that appears without being asked for is worse than no lock; a **player may choose `PRIVATE`** for their own container (the spec's literal "lock for yourself"); and **holding the Communism buff is what grants `PARTY_ONLY`**, via `factions.communism.container_lock_mode`, which is therefore the *buff's* mode rather than a default. Global default and whether players may opt in live in the new `container_lock` section (`mode`, `allow_private_choice`); the per-player choice is stored per container in `data/container_locks.json`. Keyed by canonical position with the same double-chest/hopper awareness as `TollManager.canonicalPos` (`TollManager.java:140`).
| **D11** ✅ | **Builder reach.** Can a server-side mod extend interaction reach for a vanilla client? | P4 | ✅ **ANSWERED BY MEASUREMENT — yes.** ⚠️ *This entry previously recorded the opposite conclusion, and was wrong: it claimed `handleUseItemOn` contains no distance validation, because that class does not do the check itself.* Re-verified against the 26.3 bytecode, four independent facts: (1) `player.block_interaction_range` exists as `Attributes.BLOCK_INTERACTION_RANGE` — a `RangedAttribute` with default `4.5`, bounds `0.0`–`64.0`, `setSyncable(true)`; (2) `Player.createAttributes()` registers it on every player, and `Player.blockInteractionRange()` returns the *attribute value*, not a constant; (3) the client's picking reads it — `LocalPlayer.raycastHitResult(float, Entity)` calls `blockInteractionRange()` for the raycast distance, and `Minecraft` calls that; (4) the **server** validates through it too — `handleUseItemOn` calls `isWithinBlockInteractionRange(BlockPos, double)`, which is an AABB-around-the-block distance test from the eye, and the same check also gates `ServerPlayerGameMode`, `AbstractContainerMenu`, `Container`, `SignBlockEntity` and `ItemCombinerMenu`. So one `AttributeModifier` widens the client and the server **consistently, with no mixin and no client mod**: `new AttributeModifier(id, amount, Operation.ADD_VALUE)` on the attribute instance, sent to the owner by vanilla's own `ClientboundUpdateAttributesPacket` path. `hasModifier(id)`/`removeModifier(id)` make the lifecycle idempotent. ⚠️ **Read the side effect, it is not cosmetic:** the attribute is *block interaction* range, not a building-specific one — a Master Builder would also reach 6.5 blocks to open a chest, read a sign, or click an item frame, and the same range check is what P9-T14's container lock composes with. Intended here ("`Thành thạo`" is plain reach) but it must be documented rather than discovered by a player. |
| **D12** | **Leaderboard treatment of profession freebies** (Farmer's bonus output, Miner's double ores, Builder's Haste). These are unlogged item duplication, not money. Confirm they must not affect money leaderboards — the spec implies yes. | P8 | Confirm: no money mutation, so nothing reaches `EconomyManager` and leaderboards are untouched. Log item-granting at `DEBUG` only. |
| **D13** | **Anti-abuse on progression.** A player can place/break/farm/trade to farm progress and then switch jobs. The 30 h lockout is the only brake. Is a per-job anti-abuse rule needed (e.g. no progress while rust)? | P4 | No extra rule. Rusted players earn **no** progress (they are at 50 % effect, not training) — this is a natural brake and consistent with D9. |
| **D14** ✅ | **Capitalism's daily tax scales with how much wealth Capitalism holds** (designer's request): "calculate the total money of people in Capitalism, and base the rate on that to multiply the base tax." Which signal — faction concentration, server-wide inflation, or both? | P9 | ✅ **DECIDED — both signals, multiplied**, per D4's independent-pass rule. `rate = baseRate × globalInflationMultiplier × concentrationMultiplier`. Global half **reuses the read-only `EconomyCraftApi.inflationMultiplier()`** (already public API, already hourly-recomputed from the **active-player** median, so "who is active" stays shared with pricing — do not create a fourth signal; the codebase already has three). Concentration half: `share = Σ balances(CAPITALISM) / Σ balances(all accounts)`, `concentration = clamp((share / referenceShare) ^ elasticity, minMult, maxMult)`. `referenceShare` default `0.15` = the share at which the multiplier is exactly `1.0`; `elasticity` default `1.0` is the single difficulty dial (`>1` punishes concentration harder, `<1` is gentle and sub-linear); `minMult` `0`, `maxMult` `5`. All config keys, clamp-and-warn validated. **Three failure modes, handled in P9-T0, not ignored:** **(1) Griefing** — a rich bloc leaving collapses `share` and drops everyone's rate ⇒ **per-day rate-change clamp** (`maxRateChangePerDay`, default `0.25`) persisted in `data/faction_fiscal.json`; the 30 h faction lockout blunts it further. **(2) Division by zero** — `serverAggregate == 0` ⇒ `share = 0` ⇒ multiplier at `minMult`; nobody in Capitalism ⇒ rate `0`. **(3) Addition overflow** — saturating sum (`EconomyManager.MAX` ≈ 10¹² per account). Use the **active-player** window for the global half but **all** faction accounts for the concentration half, else a member logging off lowers their own rate. Build it **faction-agnostic and config-driven** so Monarchy can adopt it later with no new code; only Capitalism uses it now. The charge message must state every factor. |
| **D15** ✅ | **MC target scope.** `build.gradle` declares 5 targets (1.21.1, 1.21.11, 26.1.2, 26.2, 26.3). P0-T3 measured hooks against **26.3 only**; `26.1.2` and `26.2` have never even been built, and 1.21.11/1.21.1 were never probed. Should this feature be verified per-target, or scoped to 26.3? | P0 | ✅ **DECIDED: 26.3 only for this feature.** *Consequences, all favourable:* **(1) Every compat fork in the P0-T5 table is deleted** — all 19 hooks go in `src/main` with no `*Compat` shim, because the renames/moves (`AgeableMob`, `AbstractHorse`, `BreedGoal`, `LavaFluid`, `hurtServer`, `destroyAndAck`, `NameAndId`) are all 26.x-relative and simply never need the old spelling. **(2) P0-T5's inference gap is closed by removal, not by measurement** — the unverified-target risk no longer exists. **(3) The Phase 1 tax centralisation must still stay version-clean**, because it touches shared code: `TaxPolicy` is pure arithmetic with no Minecraft imports, so it remains safe on every target regardless. ⚠️ *What this does NOT do:* the mod **still builds and runs on all 5 targets** — D2 (both loaders) is unaffected, and the pre-existing wealth tax, tolls and `/ah` must keep working everywhere. Only the **new** faction/profession feature is 26.3-only. Anyone on ≤1.21.11 gets those features silently absent rather than broken; a deliberate trade for a correct, tested 26.3 implementation. Record the supported range in `CHANGELOG.md` and the mod description. |
| **D16** ✅ | **How does a player choose a Party or Profession?** Spec line 5 says players choose two tag types and line 67 sets a 30 h lockout, but **the 67-line spec never states the mechanism** — no command, no menu, nothing. | P3 | ✅ **DECIDED: GUI menu.** `/tag` opens a menu, also reachable from the `/eco` hub; the player picks Party, then Profession, each behind an **explicit confirmation screen**. Chosen because the codebase is already GUI-first (`/ah`, `/order`, `/eco menu` all open menus) and a typed `/tag communism` would be both inconsistent and a way to lock yourself out of a faction for 30 h by mis-typing an autocomplete. ⚠️ *Consequences:* (a) P3-T5's `/tag` was **display-only** and is now split — `/tag` opens the menu, `/tag <player>` still shows another player's tags read-only. (b) The menu needs the 30 h cooldown rendered on every already-locked option, not just rejected on click, so the lockout is visible before choosing. (c) Reuse `MenuUiSupport`, `ConfirmUi`, `ItemPickerUi` — do not invent a new menu style. |
| **D17** ✅ | **The 30 h lockout — one timer or two, and does the first choice count?** Spec line 67 reads "cannot change Party/profession for 30 hours" without saying whether the clock covers both, or starts on the first pick. | P2/P3 | ✅ **DECIDED: two separate 30 h timers**, Party and Profession independently, **and the first choice does start its own timer** (spec-literal). A new player who picks a faction is locked for 30 h of real time — that is intended, and the confirm screen (D16) must state the duration before they commit. Consequence: `PartySelection` and `ProfessionProgress` each carry **their own** `selectedAtEpochMillis`; a Party swap never resets the Profession clock, so you can still change job after picking faction. Add a `remainingCooldown(uuid)` read to both so the menu can grey out locked options. |
| **D19** ✅ | **Monarchy's daily rate and its inflation source.** The spec gives `Cống nạp` as "an amount equal to the daily tax" but never states Monarchy's own rate, and never says whether its inflation signal is the same one Capitalism uses. | P9 | ✅ **DECIDED.** Monarchy runs **Capitalism's logic with one change**: the same concentration multiplier, but inflation read off the **server's total money** instead of the player-activity signal, at a **1.7 %** daily rate (`daily_tax_rate = 0.017`) rather than Capitalism's 5 %. Factor = `totalMoneyInCirculation / (activePlayers × money_supply_reference_per_player)`, clamped to `money_supply_inflation_max` (3.0). The reference is **per player** (default `1000.0`, i.e. `startingBalance`) rather than one absolute total, so the tax means the same thing on a 5-player and a 200-player server. `EconomyManager.totalMoneyInCirculation()` was added for it in Phase 2. The two parties' formulas must not be merged into one shared function: they differ in rate *and* in inflation source, and that difference is the point. |
| **D20** ✅ | **Haste as a held effect or a conditional one?** The spec gives amplifier levels (Builder I, Miner II) and no duration. Read literally as a timed potion, a Builder would walk around permanently Hasted while breaking anything. | P4/P5 | ✅ **DECIDED: conditional.** Haste applies **only while the player is breaking a block in that job's trigger set**, re-applied each tick while true and **removed on the first tick that they are not**. It is not something a player can carry, so a Builder breaking a chest and a Miner tunnelling with Haste II are both impossible. `haste_duration_seconds` is therefore gone, replaced by `haste_refresh_seconds` (default `1`), which exists only to cover the gap between two mining packets so consecutive qualifying blocks do not flicker — not to let the effect linger. The Builder's trigger set is the union of `haste_trigger_blocks` and `building_blocks` (`BlockTags`), because the spec names both and one list must not be copied into the other. Implementation is a server-side intercept of the destroy-progress path (P4-T5/P5-T4), not an effect grant. |

| **D21** 🟡 | **How does a tag reach the nametag above a head, given that the nametag is drawn client-side?** The only server-side lever is the scoreboard **team prefix** (hooks #16/#18e): `PlayerTeam#setPlayerPrefix(Component)` is broadcast to every client, and `PlayerTeam#getFormattedName` renders `prefix + name + suffix`. | P3 | 🟡 **OPEN — mechanics verified, the visual trade-off is yours.** Not negotiable, because the bytecode settles it: the prefix *is* the mechanism; no team colour is needed (leaving `TeamColor` empty keeps the prefix's own RGB, since `applyColor` is then a no-op); one team per (faction, profession) pair, ~30 of them, because a player can be in only one team; and `addPlayerToTeam` will silently move a player off a team another plugin assigned. The open part is visual: the icon alone is coloured, **or** you also set a team colour and the player's **name** is recoloured as well — and `TeamColor` is only 16 named values, so the name could not even match the config RGB. Options are laid out in the Phase 3 section. |
| **D22** 🟡 | **How does a tag reach chat?** The sender-name slot is **impossible**: the client builds it from `PlayerInfo#getProfile()`, so no server value reaches `<Name>` (hook #18c). The only server-side lever is the message *content* (hook #18d). | P3 | 🟡 **OPEN — needs your call; the options are not equal in cost.** **A:** only EconomyCraft's own messages (system messages, command feedback, join/leave notices) carry the icon — free, no side effects, but player chat lines show no faction at all. **B:** rewrite each player's content via `PlayerChatMessage#withUnsignedContent`, prefixing the icon. It does render (`decoratedContent()` prefers `unsignedContent`) and the signature stays valid, but **every** chat line becomes `ChatTrustLevel.MODIFIED`, so the signed-chat badge the client shows players goes grey server-wide. **C:** skip chat entirely. |
| **D18** ✅ | **Who reads the docs?** `wiki/` exists but `_Sidebar.md` is titled "EconomyCraft API v1" — every page is integrator documentation. `Tolls.md` is the only genuinely player-facing page and is **not linked from the sidebar at all** (pre-existing gap). | P11 | ✅ **DECIDED: Vietnamese player guides** under a new **Gameplay** section of the wiki sidebar — matching the spec's language and the likely reader. `Tolls.md` gets linked there too (fixing the pre-existing gap). Style is fixed by `Tolls.md`: second person, plain steps, no code, starting from the literal command or menu the player types. P11-T1/T2 already plan `Factions.md` / `Professions.md`; add a dedicated `Chon-tag.md` ("choosing a tag") page for D16/D17, since the 30 h lockout is the single most surprising rule in the feature and must be explained before first use, not discovered afterwards. |

---

## 5. Known technical risks / hard constraints

| ID | Risk | Handling |
|---|---|---|
| **R1** | Vanilla hooks must be *discovered* on 26.3, and the obvious way to look is wrong: **the deobfuscated 26.3 jar is in the Gradle cache** and is fully inspectable, but **there are no sources** — the `*-sources.jar` is an empty zip. So every hook question is answered by reading *bytecode*, and a method that "has no distance check" may simply not be the method that does it. ⚠️ **This already produced one wrong decision: D11** (see §4), where the check was looked for in `handleUseItemOn` instead of one call away. | **Verify with the recipe in P0-T3 before writing any hook, and follow the call one level deeper before concluding a check does not exist.** Do not write gameplay code against an unverified signature. |
| **R2** | NeoForge parity. Today's 3 toll mixins are Fabric-only. New mixins (crops, breeding, drops, recipes, reach, lava, damage, effects) will be the majority of the new logic. | Either duplicate the mixin config per loader, or explicitly accept Fabric-only for these hooks (then say so in the README). Decide in D2 and be honest in docs. |
| **R3** | Double-charging / double-tax risk. Because there are 18 tax sites, any missed site produces an inconsistent economy that is very hard to notice. | P1 replaces **all** 18 sites mechanically and adds a test that fails if a raw `EconomyConfig.get().taxRate` multiplication reappears (a source-scanning assertion is acceptable here). |
| **R4** | `AuctionUi` and `OrdersUi` compute tax for **display lore** as well as for the actual charge. Display and charge must use the same resolver or the UI will lie. | P1-T5. Enumerate display sites separately from charge sites in the task. |
| **R5** | ShopGuard's `ProtectionHandler` already returns `FAIL` for container use inside a claim. A chest-lock check in EconomyCraft must compose with that, not shadow it. | P10. Order the checks deliberately and test the combination. |
| **R6** | ShopGuard cannot even compile in this checkout: `libs/` is gitignored and absent, and `build.gradle` `dependsOn verifyEconomycraftJar`. Any P10 work needs the jar placed in `shopguard/libs/` first. | P0-T6. |
| **R7** | `EconomyConfig`'s recursive default-merge is untested. Adding nested objects (per-faction records, per-profession tuning) is the first time it must handle real nesting. | P2-T7 adds migration/merge tests **before** the first nested key lands. |
| **R8** | `player_activity.json` looks like online-time storage but is last-seen millis for dynamic pricing. Reusing it would silently break dynamic pricing. | P2-T1 creates a separate store. Add a note in `EconomyPaths` so nobody "optimises" them together later. |
| **R9** | `EconomyManager.MAX = 999_999_999_999`. A corrupt party/tax could push a balance over max; `transferMoney`'s refund path can fail with `MAX_BALANCE_EXCEEDED`. Every levy must handle a failed debit/rebate without corrupting its own bookkeeping. | Each levy task states its failure path explicitly. |
| **R10** | 30 h lockout is **wall-clock**, faction taxes are **online-time**, effect cooldowns are **wall-clock**. Three different clocks. Mixing them is the single most likely logic bug. | P2-T1/T2 implement them as separate, separately-persisted services with unambiguous names (`OnlineTimeService`, `CooldownService`). |

---

## 6. Invariants — must not regress (verify in every phase)

1. `./gradlew -Pfilter_platforms=fabric -Pminecraft_version=26.3 :common:test :fabric:build` stays green.
2. `FiscalPolicyTest` (40 tests) unchanged and green — the new faction tax system must not touch `FiscalPolicy`.
3. `TollUiTest` (26.3, 488 lines) unchanged and green — in particular
   `transferPickerCanSelectAnOfflineKnownAccountWithoutCreatingAnother`: **display helpers must never create a balance.**
4. `/ah`, `/orders`, `/shop`, `/sell`, `/toll`, `/daily`, `/transactions` behaviour is byte-identical when
   every player is Anarchist-and-unemployed **and** all new rates are set to 0 (see D4 — verify the inert case).
5. Existing balances, `stats.json`, `auctions.json`, `orders.json`, `tolls.json` load unchanged; no new
   migration touches existing files.
6. No new entry in `EconomyPaths.DATA_FILES` / `SETTINGS_FILES` causes `/eco import` to copy or delete anything.
7. A player with no faction and no profession is fully exempt from every new system and pays exactly the
   pre-update tax on every flow.

---

## 7. Phases

### Phase 0 — Setup project & foundations
*Goal: a green baseline, verified vanilla hooks, and an empty but wired package skeleton. No gameplay code.*

- **P0-T1 — Record the green baseline.** ✅ **DONE.** Both loaders green on 26.3.
  - `./gradlew -Pfilter_platforms=fabric -Pminecraft_version=26.3 :common:test :fabric:build` → **BUILD SUCCESSFUL in 5m 36s** (first run, including full Loom/Minecraft setup).
  - **Tests: 61 total, 0 failures, 0 errors, 0 skipped** — `FiscalPolicyTest` 40, `TollUiTest` 21. This is the regression floor every later phase must hold.
  - `./gradlew -Pfilter_platforms=neoforge -Pminecraft_version=26.3 :neoforge:build` → **BUILD SUCCESSFUL in 2m 15s**.
  - Environment: system JDKs are **JRE-only** (no `javac`); Gradle auto-provisions a real JDK 25 into `~/.gradle/jdks/eclipse_adoptium-25-amd64-linux.2`. EconomyCraft picks it up via its `jvm-toolchains` plugin.
- **P0-T2 — Close D2** (version + loader scope). Record the answer in §4 and in `gradle.properties`
  reasoning notes. Everything downstream depends on this.
- **P0-T3 — Hook verification spike — ✅ DONE, results below.** Verified by direct inspection of
  `~/.gradle/caches/fabric-loom/26.3/minecraft-merged.jar` (34,225 classes, official Mojang mappings) using
  `javap -p -c`, plus a real compile for the build-level checks. No probe package was needed in the end.

  **The recipe, for any future hook question** (the artefacts are re-downloaded by any build, so this is
  reproducible on a fresh machine — nothing here depends on this conversation):

  ```bash
  JAR=~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged-deobf/26.3/minecraft-merged-deobf-26.3.jar
  mkdir -p /tmp/mc && cd /tmp/mc && unzip -q "$JAR"                 # 11,383 classes, Mojang names

  # Which classes even mention a name? Fastest first step, and it answers "is this used at all":
  grep -rl 'blockInteractionRange' --include='*.class' .

  # Signatures, and which attribute a method really reads:
  JAVAP=~/.gradle/jdks/eclipse_adoptium-25-amd64-linux.2/bin/javap  # NOT on PATH; system java has no javap
  $JAVAP -p net/minecraft/world/entity/player/Player.class | grep -i interaction
  $JAVAP -p -c net/minecraft/server/network/ServerGamePacketListenerImpl.class   # bytecode, incl. callers
  ```

  ⚠️ Two traps this cost real time. (1) **There is no decompiled source** — the `*-sources.jar` in the same
  directory is an empty zip, so bytecode is the only evidence and a *method-level* grep proves nothing about
  behaviour. (2) **`javap` is not on `PATH`**; only the Gradle-provisioned JDK has it. And the general lesson
  from D11: `javap -c` on the class you *expect* to validate is not enough — follow the callee.

  | # | Need | 26.3 target | Status |
  |---|---|---|---|
  | 1 | Builder: block placed | `BlockItem#place`, `ServerPlayerGameMode#useItemOn` | ✅ |
  | 2 | Progress / Haste on break | `ServerPlayerGameMode#destroyAndAck(BlockPos,int,String)` | ✅ (note: not `destroyBlock`) |
  | 3 | Farmer: crop growth | `CropBlock#advanceStage`, `BonemealableBlock#performBonemeal` | ✅ |
  | 4 | Farmer: breeding | `Animal` (`getBreedIfReady`/`canBreed`/`loveTick`), `BreedGoal` | ✅ — ⚠️ **`AnimalBreed` does not exist**; it is `BreedGoal` |
  | 5 | Farmer: baby growth | **`net.minecraft.world.entity.AgeableMob`** | ⚠️ **MOVED** — was `…entity.mob.AgeableMob` in ≤1.21.11. `tickAge`/`getAge`/`setAge` all present |
  | 6 | Farmer: crafting output | `Recipe`, `ResultSlot` | ✅ |
  | 7 | Farmer: smelting output | `SmeltingRecipe` (+ `BlastingRecipe`, `SmokingRecipe`) | ✅ |
  | 8 | Miner: block drops | `Block#getDrops`, `BlockStateBase#getDrops` | ✅ |
  | 9 | Miner: lava contact | **`net.minecraft.world.level.material.LavaFluid`** | ⚠️ **`LavaBlock` no longer exists** — lava is a `Fluid` now (`LavaCauldronBlock` remains). Use a fluid check, never a block check |
  | 10 | Merchants: villager trade | `MerchantMenu`, `PoiTypes` | ✅ — but **no economy exists**; a trade-money subsystem is still new work (P7-T5) |
  | 11 | Soldier/Monarchy: damage taken | **`LivingEntity#hurtServer(ServerLevel, DamageSource, float) : boolean`** | ⚠️ **RENAMED** — 26.x has **no `hurt` method**; `hurt`/`applyItemBlocking` are gone or reshaped. This is the damage-taken hook |
  | 12 | Soldier: `Andrenaline` | `MobEffectInstance`, `MobEffect`, `LivingEntity#addEffect` | ✅ |
  | 13 | Anarchism: movement speed | `Attributes.MOVEMENT_SPEED` | ✅ |
  | 14 | Anarchism: horse speed | **`net.minecraft.world.entity.animal.equine.AbstractHorse`** | ⚠️ **MOVED** — was `…entity.animal.horse.AbstractHorse` in ≤1.21.11 |
  | 15 | Builder: reach | **`Player#blockInteractionRange()`** (`Attributes.BLOCK_INTERACTION_RANGE`) | ✅ **CORRECTED — the premise was wrong**, see D11: `handleUseItemOn` does not check distance itself, it calls `isWithinBlockInteractionRange`. A syncable attribute, so one `AttributeModifier` widens client picking *and* server validation |
  | 16 | Nametag above head | **scoreboard team prefix**, `PlayerTeam#setPlayerPrefix(Component)`, consumed by `PlayerTeam#formatNameForTeam` | ⚠️ **CORRECTED — a mixin here cannot work.** `Player#getDisplayName()` looked right, but its consumer `EntityRenderer#getNameTag` is **client-only code** (`…client.renderer.entity`), so every client draws the nametag from *its own* entity. A server-side mixin would change only what the *server* renders. The one server-side lever is the team prefix, which the client receives as data — `ServerScoreboard#onTeamAdded`/`onTeamChanged` → `ClientboundSetPlayerTeamPacket.createAddOrModifyPacket` → `PlayerList#broadcastAll`. See D21 |
  | 17 | Tab-list row | **`ServerPlayer#getTabListDisplayName()`**, consumed by `PlayerTabOverlay` | ✅ — **returns `null`** in vanilla (a stub), no public setter. **A different method from #16** |
  | 18 | Push a display-name change | `ClientboundPlayerInfoUpdatePacket$Action.UPDATE_DISPLAY_NAME` | ✅ — no reconnect needed |
  | 18b | Tab-list row detail | `PlayerTabOverlay#getNameForDisplay` | ✅ — if `getTabListDisplayName()` is **non-null** it is used and team formatting is **skipped**; only the `null` fallback applies `formatNameForTeam`. So our own tab row and a team prefix do **not** double up |
  | 18c | Chat: sender name | `ChatType$Bound#decorate` → `ChatTypeDecoration` SENDER parameter | ❌ **NOT SERVER-SETTABLE.** The client passes `PlayerInfo#getProfile()`, so the name in `<Name>` is composed **client-side** from the account name. No server value reaches that slot — see D22 |
  | 18d | Chat: message content | `PlayerChatMessage#withUnsignedContent(Component)`, which the client prefers: `decoratedContent()` is `requireNonNullElseGet(unsignedContent, signedBody)` | ⚠️ **Works, at a price**: the message keeps its signature but renders as `ChatTrustLevel.MODIFIED`, i.e. every player chat line loses its "signed" badge. See D22 |
  | 18e | Scoreboard team API names (26.3) | `Scoreboard#addPlayerTeam(String)` is get-or-create **by team name**; `Scoreboard#getPlayerTeam(String)` looks up **by player name**; `addPlayerToTeam(String, PlayerTeam)` moves a player off their old team; `PlayerTeam#setPlayerPrefix` / `setSuffix` / `setColor(Optional<TeamColor>)` / `setCollisionRule` | ✅ — but the two lookup methods are one letter apart and mean opposite things. `TeamColor` is a **16-value enum**, yet a team with **no** colour set leaves the prefix's own RGB intact, because `getFormattedName` appends the prefix first and `applyColor` is then a no-op |
  | 19 | Player identity in 26.3 | `net.minecraft.server.players.NameAndId` | ⚠️ note — `PlayerInfo` is **client-only** (`client.multiplayer.PlayerInfo`); there is no server-side equivalent. ShopGuard's v4 lookups must key on this |

  **Load-bearing conclusions.** (a) Five of the nineteen targets **moved or were renamed** in 26.x — writing
  these from memory would have failed at runtime, not at compile time, which is the expensive failure mode.
  (b) Findings 16 and 17 prove the nametag and the tab list are **separate server-side paths**, which
  **corrects the original D1 premise** (see D1). (c) Finding 15 **answers D11**: the server never validates
  interaction distance, so reach cannot be extended server-side.
- **P0-T4 — Package skeleton.** ✅ **DONE.** Six packages created, each with a `package-info.java` whose
  Javadoc states its responsibility **and** records the invariants that belong to it — deliberately not
  empty markers, because the invariants (one tax site in `tax`, one gate in `ProfessionEffects`, no recipient
  in `faction`, no hard dependency in `integration`) are exactly what later phases must not violate:
  `time`, `tax`, `tag`, `profession`, `faction`, `integration` (all under
  `common/src/main/java/com/reazip/economycraft/`). Compiles green on 26.3.
  ⚠️ Note for P8: prefer `package-info.java` over an empty directory — git cannot track an empty directory,
  and adding a real class in Phase 1+ is what will actually pull each package in.
- **P0-T5 — Compat-fork decision.** ✅ **DONE — and MOOT by D15 (26.3-only).** The fork boundaries come from
  `build.gradle` + `common/build.gradle`, which append `src/<legacy121|modern>/java` and
  `src/<obfuscated|unobfuscated>/java` to `main`: **legacy121 = 1.21.1**, **obfuscated = {1.21.1, 1.21.11}**,
  **unobfuscated = {26.1.2, 26.2, 26.3}**, **modern = {1.21.11, 26.1.2, 26.2, 26.3}**.

  We **would** have needed 7 forks, had we supported every target — each because a name moved or changed
  shape between ≤1.21.11 and 26.x:

  | # | Hook | 26.3 | ≤1.21.11 | Fork |
  |---|---|---|---|---|
  | 2 | block break | `ServerPlayerGameMode#destroyAndAck(BlockPos,int,String)` | `#destroyBlock(BlockPos)` | `ServerPlayerGameModeCompat` |
  | 4 | breeding goal | `BreedGoal` | `AnimalBreed` | `BreedGoalCompat` |
  | 5 | baby growth | `world.entity.AgeableMob` | `world.entity.mob.AgeableMob` | `AgeableMobCompat` |
  | 9 | lava contact | `LavaFluid` (no `LavaBlock`) | `LavaBlock` | `LavaCompat` |
  | 11 | damage taken | `LivingEntity#hurtServer(ServerLevel,DamageSource,float)` | `hurtServer(DamageSource)` / `hurt(DamageSource,float)` | `LivingEntityCompat` |
  | 14 | horse speed | `…animal.equine.AbstractHorse` | `…animal.horse.AbstractHorse` | `AbstractHorseCompat` |
  | 19 | player identity | `server.players.NameAndId` | `server.players.PlayerInfo` | extend existing `IdentityCompat` |

  The other 12 hooks (`BlockItem#place`, `CropBlock#advanceStage`, `BonemealableBlock#performBonemeal`,
  `Recipe`, `ResultSlot`, `SmeltingRecipe`, `Block#getDrops`, `MerchantMenu`, `PoiTypes`, `MobEffect`,
  `Attributes.MOVEMENT_SPEED`, `Player#getDisplayName`, `ServerPlayer#getTabListDisplayName`,
  `ClientboundPlayerInfoUpdatePacket$Action`) are `modern`-safe on every target.

  ✅ **D15 removes all seven.** The new feature is 26.3-only, so every hook lands in `src/main` directly and
  **no `*Compat` shim is written for it**. That also closes the honesty gap flagged in P0-T3: the fork list was
  inferred for the four non-26.3 targets rather than measured, and it no longer matters. ⚠️ **Two exceptions
  that keep their existing compat handling**, because they are pre-existing shared code rather than new
  feature code: `IdentityCompat` / `ItemsCompat` / `ClickKind` / `ProfileComponentCompat` stay exactly as they
  are, and Phase 1's `TaxPolicy` must be kept free of Minecraft imports so the tax refactor does not break the
  ≤1.21.11 build.
- **P0-T6 — ShopGuard buildability.** ✅ **DONE**, with one environment prerequisite found. The built jar is now
  at `shopguard/libs/economycraft-fabric-1.10.0_26.3.jar` (`libs/` is gitignored, so it never lands in a commit).
  `./gradlew test build` → **BUILD SUCCESSFUL, 35 tests, 0 failures** (`ClaimCostMathTest` 26, `RefundLedgerTest` 9).
  ⚠️ **ShopGuard's build requires `JAVA_HOME` pointing at a real JDK** — it has no `toolchain` block, so it
  falls back to the current JVM, which on this machine is a JRE and fails with
  `does not provide the required capabilities: [JAVA_COMPILER]`:
  ```sh
  cd shopguard && JAVA_HOME=~/.gradle/jdks/eclipse_adoptium-25-amd64-linux.2 ./gradlew test build
  ```
  Recorded as a known prerequisite, **not** patched — adding a toolchain block to the other repo is a
  code change outside this phase.
- **P0-T7 — Release hygiene.** ✅ **DONE.** `CHANGELOG.md` was empty while being wired into
  `.github/workflows/release.yml` as the `changelog-file`. It now has an Unreleased section describing the
  planned feature set and the "wealth tax untouched" note, so a release cannot ship an empty body.

**Exit criteria:** ✅ **MET** — baseline recorded (P0-T1); D2 closed (both loaders, NeoForge parity);
P0-T3 table complete with **no unverified hooks on 26.3**; skeleton compiles on both loaders.

**Phase 0 verification (re-run after the skeleton was added):**
- `:common:compileJava :fabric:compileJava :neoforge:compileJava` → **EXIT 0** (the only note is the
  pre-existing `IdentifierCompat` unchecked warning, which predates this phase).
- `:common:test :fabric:build` → **BUILD SUCCESSFUL**, **61 tests** (40 + 21), unchanged from P0-T1.
- `:neoforge:build` → **BUILD SUCCESSFUL**.
- Both `economycraft-fabric-1.10.0_26.3.jar` and `economycraft-neoforge-1.10.0_26.3.jar` produced.
- **No gameplay behaviour changed.** Phase 0 adds six empty packages plus `CHANGELOG.md`; the regression
  floor of 61 tests is the proof.

### ✅ Phase 0 closed — zero open items

Both questions asked at the Phase 0 gate were answered:

1. **D1 — decided, original spec.** Full coloured word tag (`[Communism]` in red) in the **tab list** via a
   `ServerPlayer#getTabListDisplayName()` `RETURN` mixin pushed with
   `ClientboundPlayerInfoUpdatePacket$Action.UPDATE_DISPLAY_NAME`; short **icon** (`[☭]`) in the **nametag**
   (`Player#getDisplayName()` mixin) and in **chat**. Two mixins, still no client mod.
2. **D15 — decided, 26.3 only.** All seven would-be compat forks deleted, and P0-T5's inference gap closed by
   removal rather than by measuring four more Minecraft versions.

**Phase 0 is ready to commit.** Working tree is exactly 8 paths — `CHANGELOG.md` (modified) plus `TODO.md` and
the six `package-info.java` files (new). No build output is staged: `.gitignore` already covers `/build/`,
`/.gradle/` and `logs/`, and `shopguard/libs/` is ignored by that repo. **No gameplay behaviour changed** —
the 61-test floor is unchanged from P0-T1.

---

### Phase 1 — Tax policy centralisation (pure refactor, zero behaviour change) — ✅ DONE
*Goal: one place that decides tax. Prerequisite for every faction tax, and the fix for R3/R4.*

- **P1-T1 — Define the vocabulary.** ✅ `tax/TaxScope.java` enum — `TRANSACTION_SHOP`, `TRANSACTION_AUCTION_BUY`,
  `TRANSACTION_ORDER`, `TOLL`, `AUCTION_LISTING` (the last two reserved for D8/future shop tax). Each value
  carries its `MutationSource`, so a caller cannot pick the wrong attribution. `tax/TaxQuote.java` record:
  `base`, `rate`, `amount`, `exempt`, `source`, with `total()` (payer hands over), `net()` (recipient
  receives) and `taxed()`.
- **P1-T2 — `tax/TaxPolicy.java`, the single resolver.** ✅ Production entry `resolve(scope, base)` reads
  `EconomyConfig.get().taxRate`; the pure core is `quote(scope, base, rate)` so tests need no server and no
  global mutation. Exact old formula: `amount = Math.round(base * rate)`. `tax()`, `net()`, `total()` and
  `netRate()` are the derived helpers.
- **P1-T3 — Replace all charge sites.** ✅ **19 sites, not 18** — the P1-T3 and P1-T4 lists in the original
  plan overlapped (the `AuctionUi`/`OrdersUi` "display" lines *were* part of the 18). Corrected count below.
  Every site now routes through `TaxPolicy`:
  `TollManager:152`; `AuctionTrade:48`; `AuctionUi:69,130,165,313,463,561`;
  `OrdersUi:84,121,154,270,437,537`; `OrderFulfillment:158,309,315`; `EconomyCommands:845,979`.
  `OrderFulfillment:315` (`netRatePerUnit`) is a `double` sort/filter value, not a charge — it now calls
  `TaxPolicy.netRate(...)`, which deliberately preserves the old `base * (1 - rate)` expression bit-for-bit
  (routing it through `base - round(base*rate)` would have changed 6.3 to 6 for a base of 7).
- **P1-T4 — Display/lore sites.** ✅ Folded into P1-T3; all six `AuctionUi` and six `OrdersUi` mirrors use the
  same `TaxPolicy` call as the charge they describe, so UI text cannot drift from the amount charged (R4).
- **P1-T5 — Parity tests.** ✅ `common/src/test/java/com/reazip/economycraft/tax/TaxPolicyTest.java`, **13 tests**:
  parity across a spread of bases × rates, half-up rounding, zero, negative-base parity, `Long.MAX_VALUE`
  saturation at rate 1.0, `net`/`total` consistency, `netRate` parity, per-scope source, config read via the
  production path, and the structural source-scan.
  ⚠️ *One deliberate deviation from the plan text:* it said "negative-**rejected**". Rejecting negatives would
  be a **behaviour change**, which Phase 1 forbids, so the test asserts negatives still match the old formula
  instead. Every real call site validates its base as positive first.
- **P1-T6 — Parity proof.** ✅ Automated instead of manual: `parityProofForTollAuctionAndOrderFlows` reproduces
  the toll, `/ah` and order-fulfilment arithmetic pre- and post-refactor and asserts identical amounts, and
  the base×rate matrix asserts every combination against the literal `Math.round(base * rate)`. This is
  stronger than a manual smoke run because it covers the whole input grid, not three sampled values.

**Exit criteria:** ✅ **MET** — `TaxPolicyTest` green (13/13); the source-scan asserts no `taxRate` survives
outside `tax/`, `EconomyConfig` and `AdminSettingsUi`; invariant 1 holds; amounts are provably identical.

**Phase 1 result:** **74 tests** (was 61): `FiscalPolicyTest` 40, `TaxPolicyTest` 13, `TollUiTest` 21 —
all green, both loaders. **Zero behaviour change:** the 61 pre-existing tests were untouched and still pass.

---

### Phase 2 — Data & save layer — ✅ DONE
*Goal: the three clocks (R10), the two stores, and the config surface — all persisted and tested.*

**Shipped:** P2-T1…P2-T9. New files: `config/ConfigClamp.java`, `config/TagSettings.java`,
`config/FactionsSection.java`, `config/ProfessionsSection.java`, `faction/FactionId.java`,
`faction/ContainerLockMode.java`, `faction/FactionStore.java`, `profession/ProfessionId.java`,
`profession/ProfessionLevel.java`, `profession/ProfessionStore.java`, `profession/BlockTags.java`,
`time/OnlineTimeService.java`, `time/CooldownService.java`, `time/WallClock.java`, `time/MutableClock.java`.
`BundledConfigTest` walks `config.json` and `EconomyConfig` in both directions, because the failure mode of a
forgotten default is invisible: Gson ignores an unmatched key and a missing key falls back to the field's Java
initialiser, so the server starts happily with a key the admin cannot see.
New data files: `online_time.json`, `cooldowns.json`, `parties.json`, `professions.json`.
192 tests green on 26.3 (was 61).

**Recorded assumptions** (each is a config key, so a designer can correct it without a code change):
- **Double-value ores and the trigger sets** — the spec enumerates neither; both are config lists, shipped with
  the spec's names and five reasonable ids. Builder reach was on this list until D11 was re-verified; it is
  implemented as an attribute modifier in Phase 4 and is no longer an open item.

**Closed by the designer after this phase was written**, and folded back into it:
- **Monarchy's tax (D19)** — Capitalism's logic, but inflation from the server's total money, at 1.7 %.
  `factions.monarchy.daily_tax_rate = 0.017`, plus `money_supply_reference_per_player` and
  `money_supply_inflation_max`; `EconomyManager.totalMoneyInCirculation()` added.
- **Haste (D20)** — conditional on breaking a trigger block, not a held effect. `haste_duration_seconds` became
  `haste_refresh_seconds` (1 s), and the Builder's triggers union in `building_blocks`.
- **Container lock (D10)** — server default `UNLOCKED`, player opt-in `PRIVATE`, Communism's buff grants
  `PARTY_ONLY`. The global half is the new `container_lock` section; the per-container store is Phase 10.
- **Double-value ores** — the spec says "each diamond/gold mined counts as 2 ores" without enumerating blocks.
  Config ships diamond ore, deepslate diamond ore, gold ore, deepslate gold ore and nether gold ore.
- **`use_global_inflation`** — D14's global half reads the existing read-only inflation signal rather than
  introducing a fourth independent measure of "who is active".

**Deliberate deviations from the task text above**, all behaviour-preserving:
- `consumeIfThresholdMet` fires at `>=`, not `>`. A player sitting on exactly 45:00 has met a "45 minutes"
  threshold, and the boundary is the case a player notices.
- The 30-hour lockout is a timestamp on the selection (`selectedAtEpochMillis`), not a `CooldownService` entry.
  The store needs that timestamp anyway — the `/tag` menu shows the remaining time — and the two locks are in
  two files on purpose, so "one clock disturbing the other" cannot happen by construction.
- `remainingCooldown(UUID)` takes the configured hours as a parameter rather than reading config inside, keeping
  both stores free of hidden config reads and the values testable without a loaded config.
- `everMastered` is one boolean plus `masteredProfessionLeftBehind` rather than a set of professions. For the
  player's *current* profession the two models produce identical answers, including "mastered two jobs, neither
  one is rusty" — see `ProfessionStoreTest`. `masteredAt` was dropped: nothing displays it yet.
- Clamping is covered by a dedicated `TagConfigClampTest` rather than inside `EconomyConfigMergeTest`, which
  stays about the merge mechanism.
- The Merchant's two level-up counters are exposed as `recordVillagerTrade` / `recordAuctionPurchase`, and
  `addProgress` refuses for `MERCHANT`, so half of its condition cannot be applied by accident (P5-T2).
- **`/eco settings` does not expose the new keys yet.** They are file-only until an AdminSettingsUi phase adds
  them; the README says so rather than claiming in-game editability.

- **P2-T1 — `time/OnlineTimeService.java` — online-time accumulation (spec line 1 convention).**
  Per player: accumulated online milliseconds + last-accounted tick. Tick-driven via the existing
  `TickEvent.SERVER_POST`; **only counts while the player is online**, flushes on quit and on
  `SERVER_STOPPING`. Exposes `progress(UUID)`, `consumeIfThresholdMet(UUID, long thresholdMs)` which
  resets to zero **only when the threshold is exceeded** and returns whether it fired, and
  `addOnlineTime` for migration. Persist to a **new** `data/online_time.json` — never `player_activity.json` (R8).
  Thresholds (45 min) live in `EconomyConfig`.
- **P2-T2 — `time/CooldownService.java` — wall-clock cooldowns.** Per player per key
  (`Map<UUID, Map<String, Long>>` of expiry epoch millis). `ready(UUID, key)`, `start(UUID, key, durationMs)`,
  `remaining(UUID, key)`. Used by Farmer's 4 min / Miner's 5 min / Soldier's 5 min, and by the 30 h
  faction/job lockout. Persist to `data/cooldowns.json`. Durations in `EconomyConfig`.
- **P2-T3 — `faction/FactionId.java` + `profession/ProfessionId.java` + their config-driven definitions.**
  Factions: `COMMUNISM`, `CAPITALISM`, `MONARCHY`, `ANARCHISM` (`ANARCHISM` = default when unset).
  Professions: `BUILDER`, `FARMER`, `MINER`, `MERCHANT`, `SOLDIER`. Each carries a colour and a short
  icon glyph, both from config. Single source of truth for both colour and display name.
- **P2-T4 — `faction/FactionStore.java`.** `Map<UUID, PartySelection>` where `PartySelection` = faction id +
  **`selectedAtEpochMillis` for this tag type's own 30 h lockout** (D17: the Party clock is independent of the
  Profession clock, and the *first* choice starts it). Expose `remainingCooldown(UUID)` for the menu to grey
  out locked options (D16). `ANARCHISM` is the default for a UUID with no entry — but **do not write an entry
  until the player actually chooses**, otherwise "never chose" and "chose Anarchism" become indistinguishable
  and D17's timer cannot tell them apart. Save via `AsyncFileWriter` + `dirty` flag, matching
  `NotificationManager`'s pattern. File `data/parties.json`.
- **P2-T5 — `profession/ProfessionStore.java`.** `Map<UUID, ProfessionProgress>`:
  `profession`, `level` (`APPRENTICE`/`MASTER`), `count` (progress counter), `everMastered` (set of
  professions), `masteredAt`, **`selectedAtEpochMillis`** (the *Profession* half of D17 — changing job must
  not disturb the Party clock), `rustStartedAtOnlineMs` (D9), and
  `Map<String, Integer> villagerTradeTally` (keyed by a stable villager identity — see P7-T2).
  `remainingCooldown(UUID)` here too. File `data/professions.json`.
- **P2-T6 — Vendor registry for "items/build blocks".** `profession/BlockTags.java` resolving the
  Builder building-block list (spec line 43: the `#minecraft:logs` … `minecraft:quartz_bricks` set) via
  `TagKey<Block>` where the spec gives a tag, and a literal set where it gives an id. Must be **data-driven
  from config**, so admins can extend it, and must resolve tag keys lazily (tags are not bound at
  construction time on 26.3). Also the Miner's ore set (all ores, +diamond/gold counted double) and the
  Haste trigger sets (Builder: stone/cobblestone/dirt/building blocks; Miner: stone/deepslate/tuff/netherack/ores).
- **P2-T7 — Config migration tests FIRST.** `common/src/test/.../config/EconomyConfigMergeTest.java`
  covering: missing file, new nested key added to an existing user file, key added inside an existing
  nested object, out-of-range clamping of each new numeric key with a warning. This directly de-risks R7
  and must land *before* the first nested config key.
- **P2-T8 — Wire persistence.** DONE. The four files are owned by `EconomyManager` (constructed from
  `EconomyPaths.dataDir(server)`, flushed in `manager.save()`, which `SERVER_STOPPING` already calls before
  `AsyncFileWriter.flush()`). `tickTagServices()` runs from `SERVER_POST` and advances online time plus any
  rust timer. **Invariant 6 confirmed:** the four files are deliberately absent from
  `EconomyPaths.DATA_FILES`/`SETTINGS_FILES`, because those lists drive `/eco import` — importing is meant to
  move a world economy (balances, prices), and copying who is in which party would hand every player a fresh
  party and profession on migration, while *deleting* the shared copy would destroy the source server's tag
  state.
- **P2-T9 — New config keys.** Every rate, threshold, duration, count, colour and icon from the spec, as
  clamped `EconomyConfig` fields with the bundled-default merge and matching entries in
  `common/src/main/resources/assets/economycraft/config.json`. Group under `factions` / `professions`
  sections. Document each in the README table.

**Exit criteria:** three stores round-trip across a simulated restart (extend the `TollUiTest` restart
pattern or add an equivalent test); `EconomyConfigMergeTest` green; no existing save file modified.

---

### Phase 3 — Tag & display pipeline (cross-cutting)
*Goal: the shared rendering layer both professions and factions use. Implements the **CLOSED D1** decision.*

- **P3-T1 — Display surfaces, as the bytecode actually allows.** ⚠️ **Replaces the original text of this task**,
  which said "nametag via a `Player#getDisplayName()` mixin" and was wrong (D1, D21):
  | Surface | Mechanism | Status |
  |---|---|---|
  | Tab list | `ServerPlayer#getTabListDisplayName()` mixin at `RETURN` (it is a `null`-returning stub) + push `ClientboundPlayerInfoUpdatePacket$Action.UPDATE_DISPLAY_NAME` so no reconnect is needed | ✅ **unblocked, build this** |
  | Nametag | scoreboard **team prefix** `PlayerTeam#setPlayerPrefix` — the nametag is drawn client-side, so nothing server-side can reach it except synced scoreboard data | 🟡 **blocked on D21** (which prefix/colour trade-off) |
  | Chat sender name | **impossible** — the client composes it from `PlayerInfo#getProfile()` | ❌ never build a mixin for this |
  | Chat content | `PlayerChatMessage#withUnsignedContent` — renders, but makes every line `MODIFIED` | 🟡 **blocked on D22** |
  Full coloured word tag (`[Đảng]` in the party's colour) in the **tab list**; short **icon** (`[☭]`) in the nametag
  and chat, where long names would be unreadable. Still **no client mod, no custom packet, no registered menu**
  (§1 rule 1). **One** mixin is needed (tab list only), not two.
- **P3-T2 — `tag/TagStyle.java`** — builds `Component`s from a `TagSettings` + label: `shortTag()` (`[☭]`),
  `fullTag()` (`[Đảng]`, coloured from config RGB), and `combined()`. All literals, no translation keys
  (matches ShopGuard and EconomyCraft chat style). Colours come from `TagSettings.color` as a raw RGB int via
  `Component#withColor(int)` — **not** a `ChatFormatting` name, because the sixteen vanilla ones cannot express
  the yellow-green the icon set needs (see `TagSettings`' javadoc).
- **P3-T3 — Tab list.** On join and on any selection change, set `getTabListDisplayName()` to `combined()`
  (tag + existing name) and push the packet. Restore vanilla (`null`, so the client falls back to the profile
  name) when all tags are removed. Must **not** affect UUID/name resolution (`ProfileCompat` path) or the
  leaderboard, which use names independently. Note from hook #18b: because the display name is non-null, the
  client **skips** team formatting for that row, so this composes cleanly with the D21 team prefix.
- **P3-T4 — Chat icon.** 🟡 **Blocked on D22.** Whatever is chosen, the cost must be cheap: no per-message store
  lookups on the hot path — cache the short tag per UUID and invalidate on selection change.
- **P3-T5 — `/tag`.** `/tag` (self) opens the D16 selection menu; `/tag <player>` (others, permission-gated,
  **read-only** — never offer to change another player's faction) prints their tags; plus the join message and
  an optional scoreboard/sidebar line. All read from the cached style.
- **P3-T6 — Colour/icon config — ✅ DONE in Phase 2.** `config/TagSettings.java` (abstract, `color` + `icon`)
  with `FactionsSection`/`ProfessionsSection` records per faction and per profession, validated by
  `ConfigClamp#color` (24-bit) and `ConfigClamp#icon` (single renderable glyph, with a fallback). `FactionId#settings()`
  and `ProfessionId#settings()` are the only accessors. **Nothing to add** — but `BundledConfigTest` must keep
  passing, so a new tag key has to land in the bundled `config.json` too.
- **P3-T7 — Cache invalidation.** Style cache keyed by UUID, invalidated on selection change, job change, rust
  transition, join and quit. Test: changing faction immediately changes the rendered component.
- **P3-T8 — Tag selection menu (D16/D17).** The spec never specifies how a player chooses, so this is design,
  not transcription. `/tag` opens a menu with two sections (Party, Profession); picking one shows a
  `ConfirmUi` confirmation that **states the 30 h lockout in plain words before the player commits** (D17).
  Options already on cooldown are rendered as visibly locked with their remaining time, not silently
  rejected on click — the point of a 30 h commitment is that it is never a surprise. Reuse `MenuUiSupport` /
  `ConfirmUi` / `ItemPickerUi`; do not invent a new menu style. `MenuUiSupport#openMenu` takes a `MenuProvider`,
  so this stays inside §1 rule 1 (no registered `MenuType`). Wiring goes in here, but the underlying
  `selectedAtEpochMillis` fields landed in Phase 2 (P2-T4/P2-T5).
- **P3-T9 — Team-prefix sync (nametag), if D21 says yes.** One cached `PlayerTeam` per (faction, profession)
  pair, named for what it is and kept short; `setPlayerPrefix(iconComponent)`, `setCollisionRule(ALWAYS)`
  explicitly so the tag cannot accidentally make same-party players immune to damage, and no team colour set
  unless D21 asks for a recoloured name. Cache the `PlayerTeam` handles by name — `Scoreboard#addPlayerTeam`
  is get-or-create but **logs a warning** when the team already exists, so calling it repeatedly would spam the
  log. On quit, remove the player from the team (`Scoreboard#removePlayerFromTeam(String)`).
  ⚠️ Two methods one letter apart, opposite meanings: `addPlayerTeam(String)` = get-or-create **by team name**,
  `getPlayerTeam(String)` = the team **a player** is on.

**Exit criteria:** ✅ **MET** — baseline recorded (P0-T1); D2 closed (both loaders, NeoForge parity);
P0-T3 table complete with **no unverified hooks on 26.3**; skeleton compiles on both loaders.

**Phase 0 verification (re-run after the skeleton was added):**
- `:common:compileJava :fabric:compileJava :neoforge:compileJava` → **EXIT 0** (the only note is the
  pre-existing `IdentifierCompat` unchecked warning, which predates this phase).
- `:common:test :fabric:build` → **BUILD SUCCESSFUL**, **61 tests** (40 + 21), unchanged from P0-T1.
- `:neoforge:build` → **BUILD SUCCESSFUL**.
- Both `economycraft-fabric-1.10.0_26.3.jar` and `economycraft-neoforge-1.10.0_26.3.jar` produced.
- **No gameplay behaviour changed.** Phase 0 adds six empty packages plus `CHANGELOG.md`; the regression
  floor of 61 tests is the proof.

### ✅ Phase 0 closed — zero open items

Both questions asked at the Phase 0 gate were answered:

1. **D1 — decided, original spec.** Full coloured word tag (`[Communism]` in red) in the **tab list** via a
   `ServerPlayer#getTabListDisplayName()` `RETURN` mixin pushed with
   `ClientboundPlayerInfoUpdatePacket$Action.UPDATE_DISPLAY_NAME`; short **icon** (`[☭]`) in the **nametag**
   (`Player#getDisplayName()` mixin) and in **chat**. Two mixins, still no client mod.
2. **D15 — decided, 26.3 only.** All seven would-be compat forks deleted, and P0-T5's inference gap closed by
   removal rather than by measuring four more Minecraft versions.

**Phase 0 is ready to commit.** Working tree is exactly 8 paths — `CHANGELOG.md` (modified) plus `TODO.md` and
the six `package-info.java` files (new). No build output is staged: `.gitignore` already covers `/build/`,
`/.gradle/` and `logs/`, and `shopguard/libs/` is ignored by that repo. **No gameplay behaviour changed** —
the 61-test floor is unchanged from P0-T1.

---

### Phase 1 — Tax policy centralisation (pure refactor, zero behaviour change) — ✅ DONE
*Goal: one place that decides tax. Prerequisite for every faction tax, and the fix for R3/R4.*

- **P1-T1 — Define the vocabulary.** ✅ `tax/TaxScope.java` enum — `TRANSACTION_SHOP`, `TRANSACTION_AUCTION_BUY`,
  `TRANSACTION_ORDER`, `TOLL`, `AUCTION_LISTING` (the last two reserved for D8/future shop tax). Each value
  carries its `MutationSource`, so a caller cannot pick the wrong attribution. `tax/TaxQuote.java` record:
  `base`, `rate`, `amount`, `exempt`, `source`, with `total()` (payer hands over), `net()` (recipient
  receives) and `taxed()`.
- **P1-T2 — `tax/TaxPolicy.java`, the single resolver.** ✅ Production entry `resolve(scope, base)` reads
  `EconomyConfig.get().taxRate`; the pure core is `quote(scope, base, rate)` so tests need no server and no
  global mutation. Exact old formula: `amount = Math.round(base * rate)`. `tax()`, `net()`, `total()` and
  `netRate()` are the derived helpers.
- **P1-T3 — Replace all charge sites.** ✅ **19 sites, not 18** — the P1-T3 and P1-T4 lists in the original
  plan overlapped (the `AuctionUi`/`OrdersUi` "display" lines *were* part of the 18). Corrected count below.
  Every site now routes through `TaxPolicy`:
  `TollManager:152`; `AuctionTrade:48`; `AuctionUi:69,130,165,313,463,561`;
  `OrdersUi:84,121,154,270,437,537`; `OrderFulfillment:158,309,315`; `EconomyCommands:845,979`.
  `OrderFulfillment:315` (`netRatePerUnit`) is a `double` sort/filter value, not a charge — it now calls
  `TaxPolicy.netRate(...)`, which deliberately preserves the old `base * (1 - rate)` expression bit-for-bit
  (routing it through `base - round(base*rate)` would have changed 6.3 to 6 for a base of 7).
- **P1-T4 — Display/lore sites.** ✅ Folded into P1-T3; all six `AuctionUi` and six `OrdersUi` mirrors use the
  same `TaxPolicy` call as the charge they describe, so UI text cannot drift from the amount charged (R4).
- **P1-T5 — Parity tests.** ✅ `common/src/test/java/com/reazip/economycraft/tax/TaxPolicyTest.java`, **13 tests**:
  parity across a spread of bases × rates, half-up rounding, zero, negative-base parity, `Long.MAX_VALUE`
  saturation at rate 1.0, `net`/`total` consistency, `netRate` parity, per-scope source, config read via the
  production path, and the structural source-scan.
  ⚠️ *One deliberate deviation from the plan text:* it said "negative-**rejected**". Rejecting negatives would
  be a **behaviour change**, which Phase 1 forbids, so the test asserts negatives still match the old formula
  instead. Every real call site validates its base as positive first.
- **P1-T6 — Parity proof.** ✅ Automated instead of manual: `parityProofForTollAuctionAndOrderFlows` reproduces
  the toll, `/ah` and order-fulfilment arithmetic pre- and post-refactor and asserts identical amounts, and
  the base×rate matrix asserts every combination against the literal `Math.round(base * rate)`. This is
  stronger than a manual smoke run because it covers the whole input grid, not three sampled values.

**Exit criteria:** ✅ **MET** — `TaxPolicyTest` green (13/13); the source-scan asserts no `taxRate` survives
outside `tax/`, `EconomyConfig` and `AdminSettingsUi`; invariant 1 holds; amounts are provably identical.

**Phase 1 result:** **74 tests** (was 61): `FiscalPolicyTest` 40, `TaxPolicyTest` 13, `TollUiTest` 21 —
all green, both loaders. **Zero behaviour change:** the 61 pre-existing tests were untouched and still pass.

---

### Phase 2 — Data & save layer — ✅ DONE
*Goal: the three clocks (R10), the two stores, and the config surface — all persisted and tested.*

**Shipped:** P2-T1…P2-T9. New files: `config/ConfigClamp.java`, `config/TagSettings.java`,
`config/FactionsSection.java`, `config/ProfessionsSection.java`, `faction/FactionId.java`,
`faction/ContainerLockMode.java`, `faction/FactionStore.java`, `profession/ProfessionId.java`,
`profession/ProfessionLevel.java`, `profession/ProfessionStore.java`, `profession/BlockTags.java`,
`time/OnlineTimeService.java`, `time/CooldownService.java`, `time/WallClock.java`, `time/MutableClock.java`.
`BundledConfigTest` walks `config.json` and `EconomyConfig` in both directions, because the failure mode of a
forgotten default is invisible: Gson ignores an unmatched key and a missing key falls back to the field's Java
initialiser, so the server starts happily with a key the admin cannot see.
New data files: `online_time.json`, `cooldowns.json`, `parties.json`, `professions.json`.
192 tests green on 26.3 (was 61).

**Recorded assumptions** (each is a config key, so a designer can correct it without a code change):
- **Double-value ores and the trigger sets** — the spec enumerates neither; both are config lists, shipped with
  the spec's names and five reasonable ids. Builder reach was on this list until D11 was re-verified; it is
  implemented as an attribute modifier in Phase 4 and is no longer an open item.

**Closed by the designer after this phase was written**, and folded back into it:
- **Monarchy's tax (D19)** — Capitalism's logic, but inflation from the server's total money, at 1.7 %.
  `factions.monarchy.daily_tax_rate = 0.017`, plus `money_supply_reference_per_player` and
  `money_supply_inflation_max`; `EconomyManager.totalMoneyInCirculation()` added.
- **Haste (D20)** — conditional on breaking a trigger block, not a held effect. `haste_duration_seconds` became
  `haste_refresh_seconds` (1 s), and the Builder's triggers union in `building_blocks`.
- **Container lock (D10)** — server default `UNLOCKED`, player opt-in `PRIVATE`, Communism's buff grants
  `PARTY_ONLY`. The global half is the new `container_lock` section; the per-container store is Phase 10.
- **Double-value ores** — the spec says "each diamond/gold mined counts as 2 ores" without enumerating blocks.
  Config ships diamond ore, deepslate diamond ore, gold ore, deepslate gold ore and nether gold ore.
- **`use_global_inflation`** — D14's global half reads the existing read-only inflation signal rather than
  introducing a fourth independent measure of "who is active".

**Deliberate deviations from the task text above**, all behaviour-preserving:
- `consumeIfThresholdMet` fires at `>=`, not `>`. A player sitting on exactly 45:00 has met a "45 minutes"
  threshold, and the boundary is the case a player notices.
- The 30-hour lockout is a timestamp on the selection (`selectedAtEpochMillis`), not a `CooldownService` entry.
  The store needs that timestamp anyway — the `/tag` menu shows the remaining time — and the two locks are in
  two files on purpose, so "one clock disturbing the other" cannot happen by construction.
- `remainingCooldown(UUID)` takes the configured hours as a parameter rather than reading config inside, keeping
  both stores free of hidden config reads and the values testable without a loaded config.
- `everMastered` is one boolean plus `masteredProfessionLeftBehind` rather than a set of professions. For the
  player's *current* profession the two models produce identical answers, including "mastered two jobs, neither
  one is rusty" — see `ProfessionStoreTest`. `masteredAt` was dropped: nothing displays it yet.
- Clamping is covered by a dedicated `TagConfigClampTest` rather than inside `EconomyConfigMergeTest`, which
  stays about the merge mechanism.
- The Merchant's two level-up counters are exposed as `recordVillagerTrade` / `recordAuctionPurchase`, and
  `addProgress` refuses for `MERCHANT`, so half of its condition cannot be applied by accident (P5-T2).
- **`/eco settings` does not expose the new keys yet.** They are file-only until an AdminSettingsUi phase adds
  them; the README says so rather than claiming in-game editability.

- **P2-T1 — `time/OnlineTimeService.java` — online-time accumulation (spec line 1 convention).**
  Per player: accumulated online milliseconds + last-accounted tick. Tick-driven via the existing
  `TickEvent.SERVER_POST`; **only counts while the player is online**, flushes on quit and on
  `SERVER_STOPPING`. Exposes `progress(UUID)`, `consumeIfThresholdMet(UUID, long thresholdMs)` which
  resets to zero **only when the threshold is exceeded** and returns whether it fired, and
  `addOnlineTime` for migration. Persist to a **new** `data/online_time.json` — never `player_activity.json` (R8).
  Thresholds (45 min) live in `EconomyConfig`.
- **P2-T2 — `time/CooldownService.java` — wall-clock cooldowns.** Per player per key
  (`Map<UUID, Map<String, Long>>` of expiry epoch millis). `ready(UUID, key)`, `start(UUID, key, durationMs)`,
  `remaining(UUID, key)`. Used by Farmer's 4 min / Miner's 5 min / Soldier's 5 min, and by the 30 h
  faction/job lockout. Persist to `data/cooldowns.json`. Durations in `EconomyConfig`.
- **P2-T3 — `faction/FactionId.java` + `profession/ProfessionId.java` + their config-driven definitions.**
  Factions: `COMMUNISM`, `CAPITALISM`, `MONARCHY`, `ANARCHISM` (`ANARCHISM` = default when unset).
  Professions: `BUILDER`, `FARMER`, `MINER`, `MERCHANT`, `SOLDIER`. Each carries a colour and a short
  icon glyph, both from config. Single source of truth for both colour and display name.
- **P2-T4 — `faction/FactionStore.java`.** `Map<UUID, PartySelection>` where `PartySelection` = faction id +
  **`selectedAtEpochMillis` for this tag type's own 30 h lockout** (D17: the Party clock is independent of the
  Profession clock, and the *first* choice starts it). Expose `remainingCooldown(UUID)` for the menu to grey
  out locked options (D16). `ANARCHISM` is the default for a UUID with no entry — but **do not write an entry
  until the player actually chooses**, otherwise "never chose" and "chose Anarchism" become indistinguishable
  and D17's timer cannot tell them apart. Save via `AsyncFileWriter` + `dirty` flag, matching
  `NotificationManager`'s pattern. File `data/parties.json`.
- **P2-T5 — `profession/ProfessionStore.java`.** `Map<UUID, ProfessionProgress>`:
  `profession`, `level` (`APPRENTICE`/`MASTER`), `count` (progress counter), `everMastered` (set of
  professions), `masteredAt`, **`selectedAtEpochMillis`** (the *Profession* half of D17 — changing job must
  not disturb the Party clock), `rustStartedAtOnlineMs` (D9), and
  `Map<String, Integer> villagerTradeTally` (keyed by a stable villager identity — see P7-T2).
  `remainingCooldown(UUID)` here too. File `data/professions.json`.
- **P2-T6 — Vendor registry for "items/build blocks".** `profession/BlockTags.java` resolving the
  Builder building-block list (spec line 43: the `#minecraft:logs` … `minecraft:quartz_bricks` set) via
  `TagKey<Block>` where the spec gives a tag, and a literal set where it gives an id. Must be **data-driven
  from config**, so admins can extend it, and must resolve tag keys lazily (tags are not bound at
  construction time on 26.3). Also the Miner's ore set (all ores, +diamond/gold counted double) and the
  Haste trigger sets (Builder: stone/cobblestone/dirt/building blocks; Miner: stone/deepslate/tuff/netherack/ores).
- **P2-T7 — Config migration tests FIRST.** `common/src/test/.../config/EconomyConfigMergeTest.java`
  covering: missing file, new nested key added to an existing user file, key added inside an existing
  nested object, out-of-range clamping of each new numeric key with a warning. This directly de-risks R7
  and must land *before* the first nested config key.
- **P2-T8 — Wire persistence.** DONE. The four files are owned by `EconomyManager` (constructed from
  `EconomyPaths.dataDir(server)`, flushed in `manager.save()`, which `SERVER_STOPPING` already calls before
  `AsyncFileWriter.flush()`). `tickTagServices()` runs from `SERVER_POST` and advances online time plus any
  rust timer. **Invariant 6 confirmed:** the four files are deliberately absent from
  `EconomyPaths.DATA_FILES`/`SETTINGS_FILES`, because those lists drive `/eco import` — importing is meant to
  move a world economy (balances, prices), and copying who is in which party would hand every player a fresh
  party and profession on migration, while *deleting* the shared copy would destroy the source server's tag
  state.
- **P2-T9 — New config keys.** Every rate, threshold, duration, count, colour and icon from the spec, as
  clamped `EconomyConfig` fields with the bundled-default merge and matching entries in
  `common/src/main/resources/assets/economycraft/config.json`. Group under `factions` / `professions`
  sections. Document each in the README table.

**Exit criteria:** three stores round-trip across a simulated restart (extend the `TollUiTest` restart
pattern or add an equivalent test); `EconomyConfigMergeTest` green; no existing save file modified.

---

### Phase 3 — Tag & display pipeline (cross-cutting)
*Goal: the shared rendering layer both professions and factions use. Implements the **CLOSED D1** decision.*

- **P3-T1 — Implement the CLOSED D1 (per-surface differentiation IS possible).** Full coloured word tag
  (`[Communism]` in red) in the **tab list** via a `ServerPlayer#getTabListDisplayName()` mixin at `RETURN`,
  pushed with `ClientboundPlayerInfoUpdatePacket$Action.UPDATE_DISPLAY_NAME`. Short **icon** (`[☭]`) in the
  **nametag** (`Player#getDisplayName()` mixin) and in **chat**. Two mixins, still no client mod.
  (Superseded the earlier "icon only, no differentiation" text after P0-T3 disproved its premise.)
- **P3-T2 — `tag/TagStyle.java`** — builds `Component`s from an id + config colour/icon: `shortTag()`
  (e.g. `[☭]`), `fullTag()` (e.g. `[Communism]` coloured), and `combined()`. All literals, no translation
  keys (matches ShopGuard and EconomyCraft chat style). Use `ChatFormatting`/colour ints from config.
- **P3-T3 — Nametag + tab list.** On join and on any selection change, set the player's display name to
  `combined()` (icon + existing name). Restore the vanilla display name when all tags are removed.
  Must **not** affect UUID/name resolution (`ProfileCompat` path) or the leaderboard, which use names
  independently. See D1 for the tab-list limitation.
- **P3-T4 — Chat icon.** Hook the server's chat formatting so player names inside messages carry the short
  icon (EconomyCraft's own `sendSystemMessage` sites get it directly; vanilla-generated messages need the
  P0-T3-verified hook). Must be cheap: no per-message store lookups on the hot path — cache per player and
  invalidate on selection change.
- **P3-T5 — Full tag surfaces.** `/tag` (self, opens the D16 selection menu), `/tag <player>` (others,
  permission-gated, **read-only** — never offer to change another player's faction), the join message, and an
  optional scoreboard/sidebar line. All read from the cached style.
- **P3-T8 — Tag selection menu (D16/D17).** The spec never specifies how a player chooses, so this is design,
  not transcription. `/tag` opens a menu with two sections (Party, Profession); picking one shows a
  `ConfirmUi` confirmation that **states the 30 h lockout in plain words before the player commits** (D17).
  Options already on cooldown are rendered as visibly locked with their remaining time, not silently
  rejected on click — the point of a 30 h commitment is that it is never a surprise. Reuse `MenuUiSupport` /
  `ConfirmUi` / `ItemPickerUi`; do not invent a new menu style. Wiring goes in here, but the underlying
  `selectedAtEpochMillis` fields land in Phase 2 (P2-T4/P2-T5).
- **P3-T6 — Colour/icon config.** Per-faction and per-profession colour + icon in `EconomyConfig`, validated
  (icon must be a single renderable glyph; colour must be a valid RGB int) with the usual clamp-and-warn style.
- **P3-T7 — Cache invalidation.** Style cache keyed by UUID, invalidated on selection change, job change,
  rust transition, join and quit. Test: changing faction immediately changes the rendered component.

**Exit criteria:** selecting a tag changes the tab row for a vanilla client without any client mod, and the
nametag wherever D21 allows it; `/tag` shows the full coloured tag; no NPE or stale cache across
join/quit/selection change.

⚠️ **If you are reading this in a new session:** D21 and D22 are open questions for the designer, not gaps in
the plan. Do not guess them. Everything else in this phase (P3-T2, T3, T5, T6, T7, T8) is unblocked — start at
P3-T2. And do not re-litigate D11 or D21 by re-reading the surface names: both were already disproved by
`javap`, and the findings are recorded in the P0-T3 table above (rows 15, 16, 18b-e).

---

### Phase 4 — Profession framework + Builder
*Goal: the shared profession machinery, then the first complete job end-to-end as the template.*

- **P4-T1 — `profession/ProfessionLevel.java` + `ProfessionState.java`.**
  `APPRENTICE` → `MASTER` on reaching the level-up count; job change resets to `APPRENTICE` with `count = 0`
  (unless the player has a prior `MASTER` record → `RUSTED`, per D9). Rust = effects × 0.5 while the
  45-min **online** timer from `OnlineTimeService` has not fired, then back to `MASTER`.
- **P4-T2 — `profession/ProfessionEffects.java` — the one place that reads level.**
  `apprenticeEffect(UUID, profession)`, `masterEffect(UUID, profession)`, and
  `resolve(UUID, profession)` returning the effective multiplier (1.0, the apprentice value, the master
  value, or `rust × 0.5` blended). All professions must read their numbers **only** through this, so the
  rust rule cannot be forgotten in one job. Status-effect applications go through
  `ServerPlayer#addEffect` with an explicit duration each time the source fires — never a permanent effect
  that would ignore the rust state.
- **P4-T3 — Selection & the 30 h lockout.** `/eco job` (menu), `/eco job <profession>`, `/eco job leave`.
  Enforce the 30 h wall-clock lockout from `CooldownService` after **any** party or profession change
  (spec: after choosing a Party/profession, neither can change for 30 h). Show remaining time. Ops bypass.
  Must be checked in **one** place so party and job share it.
- **P4-T4 — Builder: level-up tracking (spec 41).** 1000 building-block placements counted via the
  P0-T3-verified place hook, filtered by `BlockTags.isBuildingBlock`. Count from `BlockTags`, not a literal list.
- **P4-T5 — Builder: `Thành thạo` reach (spec 44), per D11 — an attribute modifier, not a hook.** No mixin is
  needed and no client mod is needed. Take the player's `Attributes.BLOCK_INTERACTION_RANGE` instance and add
  one modifier per level: `new AttributeModifier(Identifier, amount, Operation.ADD_VALUE)` with
  `reach_bonus_apprentice_blocks` (1.0) or `reach_bonus_master_blocks` (2.0), so the base 4.5 becomes 5.5 / 6.5.
  Use `addTransientModifier` (or `addOrUpdateTransientModifier`) so a respawn or dimension change cannot leave
  it stuck on, `hasModifier(id)` to stay idempotent, `removeModifier(id)` to take it away, and **remove-then-add**
  on a level change so the old amount never lingers. Two ids, not one — an Apprentice modifier and a Master
  modifier must be distinguishable, or a demotion through rust would leave the Master amount in place.
  Re-apply on join: the attribute instance is rebuilt per player, so the modifier does **not** survive a
  relog, and a `ProfessionStore` entry saying "Master" with no modifier on the player is a bug that looks
  exactly like "the feature is broken".
  ⚠️ **Documented side effect, not a bug:** the attribute is *block interaction* range, so it also widens
  container opening, signs and item frames — and it is the same range check P9-T14's container lock composes
  with. Say so in the README and in the code comment. Spec line 44's "only while holding a building block" is
  deliberately **not** implemented: a modifier cannot be conditional on held item, and hiding reach behind an
  item check would break the reach the moment the block is placed, since the held item is the block that was
  just placed.
- **P4-T6 — Builder: `Sửa lỗi` Haste I (spec 45), per D20.** **Conditional, not a timed grant:** Haste I
  applies *only* while the player is breaking a block in `BlockTags.triggersBuilderHaste` — stone, cobblestone,
  dirt and every building block — and must be **removed on the first tick they are not**. The break hook from
  P0-T3 gives the position and the block; re-apply each tick while the target qualifies, clear otherwise, and
  use `haste_refresh_seconds` (1 s) purely as the anti-flicker window. Two tests are mandatory: breaking a
  non-trigger block leaves no Haste on the player, and stopping mid-block removes it. Do **not** grant it on
  break completion alone — that is what produced the "permanently Hasted Builder" reading.
- **P4-T7 — Builder tests.** Progress reaches 1000 → `MASTER`; the buff tier changes with level; rust halves
  it; job change resets progress; the 30 h lockout blocks a switch. **Reach specifically:** the modifier is
  present at the right amount per level, absent with no Builder profession, and removing it returns the value to
  the vanilla 4.5 — assert on `blockInteractionRange()`, not on the modifier list, since that is what the client
  and the server check.

**Exit criteria:** Builder is complete and is the reference implementation; a second job should now need
no framework changes.

---

### Phase 5 — Farmer
- **P5-T1 — Level-up tracking (spec 47).** 300 events, counting plant / harvest / feed-animal / breed-offspring,
  each worth 1. Use the P0-T3-verified hooks. Feed = the vanilla breeding-food path (wheat, etc.), which is
  the same signal as breeding — **count it once, not twice**; decide and document whether breeding-with-food
  is 1 or 2.
- **P5-T2 — `Tươi tốt` (spec 48).** Every 4 min (wall-clock `CooldownService`), for each crop within 24 blocks,
  a 10 % (Apprentice) / 20 % (Master) chance to advance one growth step as if bone meal had been applied.
  Roll **once per crop per window**; use the P0-T3-verified crop-growth hook. Decide and document sphere vs
  cylinder and how high the scan goes (record as an Assumption).
- **P5-T3 — `Chăm sóc` breeding cooldown (spec 49).** −10 % / −20 % on the animal's breeding cooldown for
  animals bred by a Farmer. Needs a mixin on the cooldown value; make it read `ProfessionEffects.resolve`.
- **P5-T4 — `Chăm sóc` baby growth (spec 49).** Offspring grow 15 % / 30 % faster.
- **P5-T5 — `Khéo léo` (spec 50).** 1 % / 5 % chance of 2 extra same-type items when crafting or smelting an
  **edible** result. Hook the P0-T3-verified recipe-result path; do not mutate the recipe itself. Bonus items
  must not be tradeable back into an infinite loop without cost — note that item duplication is intended here
  (it is the reward) and keep it out of the money economy (D12).
- **P5-T6 — Farmer tests.** Each of the three effects at both levels, the 4-min cooldown, the rust halving,
  and the progress counter.

**Exit criteria:** all three Farmer effects work at both levels; cooldown prevents re-rolling within 4 min.

---

### Phase 6 — Miner
- **P6-T1 — Level-up tracking (spec 52).** 270 ores total; diamond and gold count as 2. Use `BlockTags.isOre`
  plus an explicit double-value set from config.
- **P6-T2 — `Lanh lợi` Haste II (spec 53), per D20.** Same conditional rule as P4-T6: Haste II only while the
  block being broken is in `BlockTags.triggersMinerHaste` (stone, deepslate, tuff, netherack or an ore),
  cleared on the first tick it is not. Reuse P4-T6's helper rather than writing a second implementation — the
  two jobs differ only in which set and which amplifier level they pass in.
- **P6-T3 — `Khéo tay` (spec 54).** 5 % / 15 % chance of a doubled ore drop, via the P0-T3-verified drop hook.
  Must not double non-ores and must not double XP behaviour inconsistently — document the XP decision.
- **P6-T4 — `Bảo hộ lao động` (spec 55).** **Master only.** On lava contact, Regeneration II for 4 s with a
  5-min cooldown. This is a lava-contact hook; decide explicitly whether it also **cancels** the lava damage
  (the spec does not say) and record it as an Assumption — recommend it does **not** cancel damage.
- **P6-T5 — Miner tests.** Both levels, the double-value ores, the cooldown, and the lava path.

**Exit criteria:** double-drop works and does not fire for stone; the lava cooldown is honoured.

---

### Phase 7 — Merchants
*This is the largest job: it needs an economy flow that does not exist yet.*

- **P7-T1 — Level-up tracking, part A (spec 58).** 50 villager trades, **excluding** stick trades
  (detect a `Stick` in the traded result stack via the P0-T3-verified trade hook), capped at 20 per villager.
- **P7-T2 — Stable villager identity.** The 20-trade cap needs a key that survives a server restart and
  distinguishes two villagers at the same coordinates after a rebuild. Prefer the villager's persistent UUID;
  if that is unavailable on 26.3, fall back to a composite of dimension + block position + profession and
  document the weakness. Decide here, because the key shape is persisted in `ProfessionStore` (P2-T5).
- **P7-T3 — Level-up tracking, part B (spec 58).** 5 purchases from `/ah`, hooked into `AuctionTrade`
  (success path only). Resolve D6 (AND vs OR).
- **P7-T4 — `Lưỡi không xương` discount (spec 59).** Reduce the buy/sell/trade cost to 5 % (Apprentice) / 15 %
  (Master) for: villager trades, player-to-player trades (`/pay`, orders), and `/ah`. Route **all** of it
  through `TaxPolicy` (Phase 1) and the price resolver — do **not** add a second discount mechanism. Decide
  and document the interaction with the tax itself (a discount on the pre-tax price, or on the total?).
- **P7-T5 — Villager trade economy (new system).** Selling to / buying from a villager for money does not
  exist today. Design it as a thin layer over `TaxPolicy` + `transferMoney`, with a price source for
  unpriced items. This is a genuinely new subsystem — expect to need a config table and a `/eco villager`
  (or reuse `/shop`) surface. **Flag as the largest scope item in this phase.**
- **P7-T6 — Merchants tests.** Stick trades excluded, the 20-per-villager cap, the `/ah` purchase counter,
  D6 semantics, and the discount at both levels.

**Exit criteria:** both progress halves count correctly; the discount applies through one mechanism only.

---

### Phase 8 — Soldier
- **P8-T1 — Level-up tracking (spec 62).** 100 mobs killed **by the player** (not deaths). Use the P0-T3-verified
  death event and check the killer.
- **P8-T2 — `Sắt được tôi thế đấy` (spec 63).** −5 % damage taken / +5 % damage dealt (Apprentice),
  ±15 % (Master). Use the P0-T3-verified damage hooks — attribute modifiers are **not** sufficient for
  "damage taken", which covers falls, fire, void, etc. Document the chosen hook.
- **P8-T3 — `Andrenaline` (spec 65).** **Master only.** On receiving a negative effect, halve the duration of
  the player's currently active negative effects, but only if the *first* negative effect was received less
  than 4 s ago; afterwards a 5-min cooldown blocks re-activation. Automatic, never manual.
  Implement with a single "first negative effect at" timestamp per player plus the 4 s window check — the
  window semantics are subtle and must be unit-tested against the wording.
- **P8-T4 — Soldier tests.** Player kills only; both damage percentages at both levels; the 4 s window,
  the 5-min cooldown, and that Master-only gating holds.

**Exit criteria:** `Andrenaline`'s window logic is covered by at least four tests (in-window, out-of-window,
post-cooldown, second effect inside the same window).

---

### Phase 9 — Party (factions)
*Non-claim faction behaviour. Claim-dependent parts are Phase 10.*

- **P9-T0 — Capitalism's inflation-responsive daily rate (D14, inside the independent pass from D4).**
  **Do NOT edit `FiscalPass`, `FiscalPolicy`, `fiscal.json`, `FiscalPolicyTest`, or any `wealth_tax*` config key**
  (invariant 2). Instead:
  - `faction/FactionFiscalPolicy.java` — **pure**, server-free: `concentrationMultiplier(share, params)` and
    `capitalismRate(base, globalInflation, concentration, params)`. All logic that can be pure lives here, so the
    D14 formula is exhaustively unit-testable in `common/src/test/.../faction/FactionFiscalPolicyTest.java`.
  - `faction/FactionFiscalPass.java` — the stateful daily driver, mirroring `FiscalPass`'s *structure* (epoch-day
    comparison, catch-up, `Report`-style summary) but with **its own** `lastDay` in
    `data/faction_fiscal.json`, its own computed-rate field, and its own per-day rate-change clamp.
  - Gather aggregates **once per day**, not per tick: `Σ balances(CAPITALISM)` and `Σ balances(all accounts)`
    with **saturating** addition (`EconomyManager.MAX` ≈ 10¹² per account). Read balances with
    **`getBalance(uuid, false)`** so the pass never lazily creates an account (invariant 3,
    `TollUiTest.transferPickerCanSelectAnOfflineKnownAccountWithoutCreatingAnother`).
  - Read the global signal via the **read-only** `EconomyCraftApi.inflationMultiplier()`; never write through it.
  - The charge message must state every factor — base rate, global inflation, share, reference share,
    concentration multiplier, and whether the per-day clamp bound it — so an admin can explain any number.

- **P9-T1 — Party selection.** `/eco party` menu, `/eco party <faction>`, shared 30 h lockout with P4-T3.
  `ANARCHISM` is the default when unset (spec 32) — and must be **explicitly stored** as `ANARCHISM` once
  the player first interacts, so the join message and `/tag` are unambiguous. Decide and record.
- **P9-T2 — Party display** via the Phase 3 pipeline, using each faction's configured colour (spec 5).
- **P9-T3 — New `MutationSource`s + `FISCAL_SOURCES`.**
  `economycraft:party_fee`, `economycraft:income_tax`, `economycraft:import_tax`, `economycraft:daily_tax`,
  `economycraft:corruption_tax`. **All added to `FISCAL_SOURCES`** (`EconomyManager.java:63`) per §1.
- **P9-T4 — Communism `Đảng phí` (spec 14).** Every 45 min **online** (`OnlineTimeService`, not wall clock),
  charge $10. Failure path: insufficient funds must not reset the timer incorrectly, and must not retry-loop.
  Money burned — no receiver.
- **P9-T5 — Communism `Thuế thu nhập` (spec 15-18).** On the same 45-min online reset, **after** the party fee,
  apply the tiered tax per D3. Reuse `FiscalPolicy`'s invariants as the model (never tax into negative;
  handle `MAX_BALANCE_EXCEEDED`/R9). Notify the player of both amounts in one message.
- **P9-T6 — Communism `Đầu tư công` (spec 12).** 50 % chance the toll tax is waived (owner still receives the
  fee). Implement inside the P1 toll scope — a 50 % chance means `TaxPolicy` must accept an externally
  supplied exemption decision, so pass a predicate/`TaxExemption` hook rather than reading the faction
  inside `TaxPolicy`.
- **P9-T7 — Capitalism `Thị trường cạnh tranh` (spec 21).** D8 is **CLOSED**: a purchase from a listing whose
  **seller is a Capitalism member** is tax-exempt — the buyer pays exactly the listing price, the seller still
  receives the full price. Implement as a **seller-side exemption** inside `TaxPolicy`'s auction-buy scope
  (one-line, thanks to Phase 1). **Do not** introduce a seller-side listing fee, do not exempt the toll or the
  item price, and do not exempt on the buyer's faction.
- **P9-T8 — Capitalism `Nhà nước tư bản` (spec 23).** Daily tax rate to 5 %; **toll** tax +25 %.
  "Toll tax +25 %" must be defined: 25 % more tax on a toll (multiplier 1.25 on the tax amount) or the rate
  +25 percentage points? Record as an Assumption — recommend a 1.25× multiplier on the toll tax amount.
- **P9-T9 — Monarchy `Nhập khẩu` (spec 31).** 50 % chance of an extra import tax equal to 50 % of that item's
  tax, on the import scopes agreed in D7. Rounded consistently with `TaxPolicy` (round half of an already-rounded
  tax — pin the exact order and unit-test it).
- **P9-T10 — Monarchy `Cống nạp` (spec 30), per D19.** The rate is no longer open: `daily_tax_rate = 0.017`,
  and the inflation factor is **Monarchy's own** — `totalMoneyInCirculation() / (activePlayers ×
  money_supply_reference_per_player)`, clamped to `money_supply_inflation_max` — *not* Capitalism's
  `inflationMultiplier()`. The concentration multiplier is shared with Capitalism's; do not fold the two
  formulas into one function, because they differ in both rate and source. Test the factor at 0.5×, 1× and
  above the ceiling. D5 is **CLOSED**:
  the corruption payment is a **pure burn** — debit the player, credit nobody, using
  `transferMoney(player, …, debit, 0, ECONOMYCRAFT:CORRUPTION_TAX, …)`. **Do not** build a king entity, a king
  pointer, or any recipient lookup, and do not credit the amount anywhere. Handle only one failure path:
  player cannot afford it.
- **P9-T11 — Monarchy `Tự trị` (spec 27) + `Phép vua` (spec 28).** `Tự trị` (halved claim cost) is
  **Phase 10** (it lives in ShopGuard). `Phép vua` (the +15 % damage / damage-resistance inside your own
  claim) is implemented here against the claim bridge, which must therefore be available — coordinate with
  P10-T1; implement the effect in Phase 10 if the bridge lands late, and say so.
- **P9-T12 — Anarchism `Tự do` (spec 34).** Pays no tax of any kind, **but still pays tolls and purchase
  fees**. So this is a *tax* exemption only: `TaxPolicy` returns `exempt` for every `TaxScope`, while the
  toll **fee** and the item price are untouched. Get this distinction right — it is the single easiest
  thing to over-apply here.
- **P9-T13 — Anarchism `Thoải mái` + `Vô chính phủ` (spec 35, 37).** Both need the claim bridge
  (`Thoải mái` needs "unclaimed land"; `Vô chính phủ` needs to block claiming and trust). Phase 10.
- **P9-T14 — `Cộng đồng` chest lock (spec 10).** **D10 is CLOSED:** the global default is `UNLOCKED`
  (`container_lock.mode`), a player may opt their own container into `PRIVATE` (unless
  `container_lock.allow_private_choice` is false), and the Communism buff grants `PARTY_ONLY`. Effective mode
  per container = buff if held, else the player's own choice, else the global default — resolve it that way and
  document the precedence, because "who is it locked to" has three inputs. Per-container mode stored in a new
  `data/container_locks.json`, keyed like `TollManager`'s canonical position (be aware of double chests and
  the existing hopper/pressure-plate canonicalisation, P2/P3 in `TollManager:canonicalPos`). Hook the
  container-open path. Must compose with ShopGuard's claim protection (R5) and with the `/eco admin` reset
  tooling (add a clear-all entry).
- **P9-T15 — Party tests.** Per faction: the 45-min party fee, the tiered income tax at each bracket boundary,
  toll-tax exemption and +25 %, the daily tax, the import tax, the corruption payment and its failure paths,
  the Anarchism tax exemption (and proof it does **not** exempt toll fees or item prices), and the 30 h lockout.

**Exit criteria:** every faction's money effects are attributable in `/transactions` with the new sources and
are **absent** from leaderboard `earned`/`spent`; invariant 7 holds.

---

### Phase 10 — Land claims, ShopGuard, and claim-dependent effects
- **P10-T1 — Choose the bridge direction.** Options:
  (a) EconomyCraft defines a claim-provider SPI (mirroring the existing `EconomyCraftApiAccess.install()`
  one-shot provider pattern) that ShopGuard registers into — clean, but **requires a ShopGuard release first**;
  (b) EconomyCraft reads ShopGuard reflectively / `compileOnly` against `ShopGuard.STORE.claimAt(...)`,
  matching ShopGuard's existing soft-dependency idiom — **EconomyCraft ships first**, degrading gracefully
  when ShopGuard is absent.
  Recommend **(b)** so this feature is not blocked on the other repo, and record the choice.
- **P10-T2 — `integration/ClaimBridge.java`** — `claimAt(dim,x,z)`, `isOwnClaim(player, dim, x, z)`,
  `isUnclaimed(dim, x, z)`, `mayBuild(player, dim, x, z)`; `null` backend when ShopGuard is absent, with
  a **single** startup warning (never per-tick spam).
- **P10-T3 — Monarchy `Tự trị`: halve claim cost.** Lives in ShopGuard: `ClaimCostMath.Params` /
  `ClaimPricing.params()` must be multiplied by 0.5 for Monarchy owners. Because `Config` is a mutable public-field
  singleton read at each call site, apply the factor in `ClaimPricing`, not in `Config`. **Recorded `paid` must
  stay the amount actually charged**, so refunds remain correct — verify against `RefundLedger`'s
  all-or-nothing allowance.
- **P10-T4 — Monarchy `Phép vua thua lệ làng`.** +15 % damage and damage resistance **while standing in your
  own claim**, re-evaluated on movement into/leave of a claim. Use the P8-T2 damage hooks with a claim-conditional
  modifier. Cache the "am I in my own claim" answer per player per tick, not per damage event.
- **P10-T5 — Anarchism `Thoải mái`.** +15 % movement speed and +15 % horse speed **on unclaimed land**
  (`claimAt == null`). Off claimed land, remove the modifier. Horse speed needs a separate hook for
  `AbstractHorse`/`Mob` — verify in P0-T3.
- **P10-T6 — Anarchism `Vô chính phủ` (spec 37).** Block: claiming (new), carving/release is allowed,
  **receiving** a transfer, and being added to another player's trust list. Enforced in ShopGuard's
  `ClaimTool.add`, `ShopGuardCommands.transfer` and `trustToggle` — so this **is** ShopGuard-side work.
  Confirm the designer wants Anarchists to be un-trustable rather than merely unable to claim (R5/D-note).
- **P10-T7 — ShopGuard tests.** Claim cost halved for Monarchy and recorded cost correct; the damage/speed
  modifiers apply only in the right regions; an Anarchist cannot claim, cannot receive a transfer, and cannot
  be trusted; everything still works with **no faction selected**.
- **P10-T8 — Interaction test.** Run EconomyCraft + ShopGuard together and verify the chest-lock (P9-T14) and
  claim protection compose without either swallowing the other's message.

**Exit criteria:** all four claim-dependent effects work with both mods installed, and EconomyCraft still
starts cleanly with ShopGuard absent.

---

### Phase 11 — Documentation, release
- **P11-T1 — README.** New sections: factions, professions, the online-time convention, the 30 h lockout (both
  timers, per D17), every new config key with its default and unit, and an explicit **"Limitations"** section
  covering D1 (tab list), D11 (reach), and any R2 loader asymmetry.
- **P11-T2 — `wiki/`, player-facing pages in Vietnamese (D18).** Add a **Gameplay** section to
  `_Sidebar.md` containing `Chon-tag.md` (choosing a tag — **must land before players can pick**, since the
  30 h lockout is the most surprising rule in the feature), `Factions.md` and `Professions.md`, and link the
  currently-orphaned `Tolls.md`. Voice and structure follow `Tolls.md`: second person, plain steps, no code,
  opening with the literal command or menu path. Leave the existing `API v1` integrator pages in English —
  their readers are mod developers, not players. Update `Tolls.md` if the toll tax behaviour changed.
- **P11-T3 — `api/v1` additions (optional but recommended).** `FactionApi` / `ProfessionApi` exposing read-only
  queries, following `BalanceApi`'s shape and the `requireServerThread()` rule. Additive, so v1 stays compatible.
  Skip if it is not needed by anything — do not add speculative API.
- **P11-T4 — `CHANGELOG.md`.** Populate for the release (P0-T7 created the structure); this file is the
  `changelog-file` consumed by the release workflow.
- **P11-T5 — CurseForge/Modrinth copy** for the new features if the feature set warrants it.
- **P11-T6 — Final verification.** The verify command on every supported `mcTargets` entry that Phase 0
  enabled, plus a manual in-game checklist per job and per faction, mirroring the style of `wiki/Tolls.md`.
- **P11-T7 — Fill in §8 traceability, confirm §6 invariants, and update §4 decisions with their final answers.**

---

## 8. Spec traceability matrix

Every spec line maps to a task. A line with no task is a gap. Re-check this table before declaring done.

| Spec line | Feature | Task(s) |
|---|---|---|
| 1 | `X phút online` convention | P2-T1 (primitive); used by P9-T4, P9-T5, P4-T1 |
| 3 | Fabric, server-side only, 26.3 | §1 rule 1; D2; P0-T2, P0-T3 |
| 5 | Two tag types, colours, chat icon, nametag icon, tab full tag | P2-T3, P3-T1…T6, P9-T2, P4-T3 |
| 7 | Tag Party | P2-T3, P9-T1 |
| 10 | Communism · `Cộng đồng` chest lock (2 options) | D10, P9-T14 |
| 11 | Communism · `Tài trợ` subsidy | **§9 — read-only, not implemented** |
| 12 | Communism · `Đầu tư công` 50 % no toll tax | P9-T6 |
| 14 | Communism · `Đảng phí` $10 / 45 min online | P9-T4 |
| 15–18 | Communism · income tax 0.5 / 0.75 / 1.25 % over 10 k / 15 k / 22 k | D3, P9-T5 |
| 19 | Capitalism | P2-T3, P9-T1 |
| 21 | Capitalism · `Thị trường cạnh tranh` `/ah` tax exempt | D8, P9-T7 |
| 23 | Capitalism · `Nhà nước tư bản` daily tax 5 %, toll tax +25 % | D4, P9-T8 |
| 25 | Monarchy | P2-T3, P9-T1 |
| 27 | Monarchy · `Tự trị` claim cost −50 % | P10-T3 |
| 28 | Monarchy · `Phép vua thua lệ làng` +15 % dmg in own claim | P9-T11, P10-T4 |
| 30 | Monarchy · `Cống nạp` corruption to the king | D4, D5, P9-T10 |
| 31 | Monarchy · `Nhập khẩu` 50 % chance of 50 % import tax | D7, P9-T9 |
| 32 | Anarchism = default | P9-T1 |
| 34 | Anarchism · `Tự do` no taxes (tolls/fees still paid) | P9-T12 |
| 35 | Anarchism · `Thoải mái` +15 % speed / horse speed on unclaimed land | P10-T5 |
| 37 | Anarchism · `Vô chính phủ` cannot claim / receive land / be trusted | P10-T6 |
| 38 | Tag nghề nghiệp (profession framework) | P4-T1…T3 |
| 39 | Apprentice → Master; job change resets; `Lụt nghề` 45 min online at 50 % | D9, P4-T1, P4-T2 |
| 40–45 | **Builder** (1000 blocks; block list; reach +1/+2; Haste I) | P2-T6, P4-T4…T7, D11 |
| 43 | Builder building-block tag list | P2-T6 |
| 46–50 | **Farmer** (300 events; 10/20 % crops 24-block 4-min; 10/20 % breeding cooldown, 15/30 % baby growth; 1/5 % bonus output) | P5-T1…T6 |
| 51–55 | **Miner** (270 ores, diamond/gold ×2; Haste II; 5/15 % double drop; Master lava Regen II 4 s / 5 min) | P6-T1…T5 |
| 57–59 | **Merchants** (50 villager trades, no sticks, 20 per villager; 5 `/ah` buys; cost → 5 %/15 %) | D6, P7-T1…T6 |
| 60–65 | **Soldier** (100 kills; −5/+5 % damage → ±15 %; Master `Andrenaline`) | P8-T1…T4 |
| 67 | 30 h real-world lockout on party **and** profession change | P4-T3, P9-T1 |

---

## 9. Out of scope (explicitly not building)

- **`Tài trợ` (subsidy, spec 11)** — the spec marks it read-only ("hiện tại option này chỉ để đọc"). No code.
  If it is later wanted, it is a **ShopGuard-side** feature (the Communism builder collecting from non-members
  at their own tolls), not an EconomyCraft feature.
- **Any client-side mod, custom packets, or registered `MenuType`.** Breaks the vanilla-client contract (§1).
- **Enderman/mob griefing, hoppers, breeding grief** — ShopGuard's documented gaps, unrelated to this feature.
- **Refactoring the untested persistence/config layer beyond what Phase 2 needs.** The `AsyncFileWriter`
  atomicity, `UuidLongMapStore` and `PriceRegistry` reload gaps are real (only 2 test classes exist for
  ~19 k lines) but are a separate workstream. Note them; do not absorb them here.
- **NeoForge toll-mixin parity** (today's existing gap). Only fix it if D2 says "both loaders".
- **Leaderboard/scoreboard redesign** beyond the one line that shows the player's tags.

---

## 10. Definition of done

1. Every task in §7 is complete, or its blocker is recorded in §5 with a stated scope impact.
2. §8 has no row without a task.
3. §6 invariants 1–7 verified, with the baseline counts recorded in P0-T1.
4. `./gradlew -Pfilter_platforms=fabric -Pminecraft_version=26.3 :common:test :fabric:build` green, and green
   for every `mcTargets` entry D2 enabled.
5. `cd shopguard && ./gradlew test` green (35 tests + whatever P10 added).
6. A manual in-game checklist passes for all 4 factions and all 5 professions, on a **vanilla client**.
7. Every new levy appears in `/transactions` with its own `MutationSource` and does **not** appear in the
   leaderboard `earned`/`spent` columns.
8. §4 decisions all have a recorded final answer.
9. README, `wiki/`, and `CHANGELOG.md` updated, with the Limitations section stating the D1/D11/R2 caveats honestly.
