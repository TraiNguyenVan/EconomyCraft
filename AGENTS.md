# AGENTS.md

Guidance for AI agents and new contributors working in this repository.

Replaces `TODO.md`, which recorded the ground rules and architecture baseline for a subsystem that has since
shipped. Everything below was verified against the code on the stated date. If you find a claim here that
contradicts the code, **the code is right** — fix this file.

---

## 1. What this is

EconomyCraft is a **server-side** economy mod for Minecraft, built on Architectury. It runs on Fabric and
NeoForge from one codebase and requires no client-side mod.

It provides: balances and payments, a fixed-price shop, an auction house, player order books, price offers,
deliveries, leaderboards, block tolls, dynamic shop pricing, a daily wealth tax, server-funded quests,
LLM-driven villager economic gossip, a configurable login message, and a faction/profession tag system that
modifies taxation, land interaction and combat.

It is an enhanced fork of [PhilipB06/EconomyCraft](https://github.com/PhilipB06/EconomyCraft) (ReaZip).

| Remote | Repository | Role |
|---|---|---|
| `origin` | `TraiNguyenVan/EconomyCraft` | **this fork** |
| `upstream` | `PhilipB06/EconomyCraft` | the original |

---

## 2. Ground rules

These constrain every change. Each cites the code that enforces it.

### 2.1 Server-side only

No client mod. No custom packets. No registered `MenuType`. The entire UI is vanilla server-side
`AbstractContainerMenu` over `MenuType.GENERIC_9xN`. It must work with an unmodified vanilla client.

- `fabric/src/main/java/com/reazip/economycraft/fabric/client/EconomyCraftFabricClient.java` —
  `onInitializeClient()` is **empty**. Note the package is `...economycraft.fabric.client`, not
  `...economycraft.client`.
- Networking is vanilla only: chat, action bar, particles, menus. **Zero custom payloads.**
- Server→client display is a vanilla scoreboard objective (`eco_balance`, top 5, `balance/1000` with a
  `FixedFormat` short-money override) and a `TollHud` action bar.

### 2.2 Mixins are loader-specific, and the two loaders differ

| Loader | Count | Notes |
|---|---|---|
| Fabric | 12 | `fabric/src/main/resources/economycraft.mixins.json` |
| NeoForge | 10 | `neoforge/src/main/resources/economycraft.mixins.json` |

Both are `compatibilityLevel: JAVA_25`. They are **not** symmetric, and the asymmetry runs in both
directions — 9 mixins are shared, so the 12/10 counts differ by 4, not 2:

| Only on | Mixin |
|---|---|
| Fabric | `TollPressurePlateMixin`, `TollBasePressurePlateMixin`, `TollHopperMixin` |
| NeoForge | `ProfessionBreakMixin` |

Fabric therefore has no block-break profession hook, and NeoForge has no toll interaction hooks. A mixin
added to one loader usually needs an explicit decision about the other.

### 2.3 Public API keeps the thread contract

Anything reachable from the public `api/v1` surface must call `requireServerThread()`, exactly as
`EconomyCraftApiBootstrap` does
(`common/src/main/java/com/reazip/economycraft/api/v1/EconomyCraftApiBootstrap.java:49`).

Two naming traps here. There is no `EconomyCraftApiImpl` in this codebase — earlier drafts of this file
cited one. And the bootstrap is **not** in the `api/` module: the 18 public interfaces live in
`api/src/main/java/.../api/v1/`, while the implementation lives in
`common/src/main/java/.../api/v1/EconomyCraftApiBootstrap.java`, because it needs Minecraft server types.

### 2.4 Money moves only through the mutation engine

Use the **asymmetric** form, never a debit-then-credit pair:

```java
// common/src/main/java/com/reazip/economycraft/EconomyManager.java:502
PaymentResult transferMoney(UUID from, UUID to, long debitAmount, long creditAmount, source, detail)
```

Levies are **burned, not credited**. Never `addMoney` to a tax sink.

No player-facing balance may change except through this engine — that is what keeps `/transactions`, the
webhook and the daily log correct.

### 2.5 Register every fiscal source

A new levy or rebate **must** be added to `FISCAL_SOURCES`, or it will silently pollute leaderboard
`earned`/`spent` totals and the scoreboard.

```java
// common/src/main/java/com/reazip/economycraft/EconomyManager.java:81
private static final Set<String> FISCAL_SOURCES = Set.of(
    EconomySources.WEALTH_TAX.asString(),
    EconomySources.WEALTH_REBATE.asString(),
    EconomySources.PARTY_FEE.asString(),
    EconomySources.INCOME_TAX.asString(),
    EconomySources.IMPORT_TAX.asString(),
    EconomySources.DAILY_TAX.asString(),
    EconomySources.CORRUPTION_TAX.asString()
);
```

Note this file is at the **package root** (`com.reazip.economycraft`), not in `util/`.

### 2.6 Mutation source naming

`MutationSource` lives in `api/v1/`. Its charset rules: lowercase only, namespace `[a-z0-9._-]+`, reason
`[a-z0-9/._-]+`, exactly one `:`. Use the `economycraft:` namespace — except ShopGuard integration money,
which stays `shopguard:`.

### 2.7 Configuration is the only tuning surface

All rates, thresholds, weights, durations and colours go into `EconomyConfig` — **never** hard-coded literals
in gameplay classes. `EconomyConfig` auto-merges new keys from the bundled default. Its clamp-and-warn
validation style is mandatory for every new numeric key.

### 2.8 Persistence

Gson JSON via `AsyncFileWriter` (`common/src/main/java/com/reazip/economycraft/util/AsyncFileWriter.java`).
**Never NBT.** Save on `LifecycleEvent.SERVER_STOPPING` alongside `manager.save()`.

Reserve synchronous writes — as `TollManager.save()` does — only for state that must survive an immediate
crash.

---

## 3. Build and test

### Supported platforms

`build.gradle:30-36` defines the matrix. Java version is `(tgt.javaVersion ?: 25)` (`build.gradle:100`), so
the 1.21.x line pins 21 and everything else defaults to 25.

| Target | Java | Loaders |
|---|---|---|
| 1.21.1 | 21 | Fabric, NeoForge — uses the `legacy121` compat fork |
| 1.21.11 | 21 | Fabric, NeoForge |
| 26.1.2 | 25 | Fabric, NeoForge |
| 26.2 | 25 | Fabric, NeoForge |
| 26.3 | 25 | Fabric, NeoForge — **default**, and the only target with its own test source set |

### Commands

```bash
# default target (26.3)
./gradlew build

# a specific target
./gradlew -Pminecraft_version=1.21.1 build

# one loader
./gradlew -Pfilter_platforms=fabric :common:test :fabric:build

# tests
./gradlew -Pminecraft_version=26.3 :common:test
```

### Test source sets

| Source set | Scope |
|---|---|
| `common/src/test` | all targets |
| `common/src/test26_3` | **26.3 only** |

Because coverage is per-target, **do not state a raw test count in any document.** Any single number is wrong
for four of the five targets. Name the source sets and give the reproduction command instead.

### Source sets

| Source set | Purpose |
|---|---|
| `common/src/main` | shared across all targets |
| `common/src/modern` | 1.21.11 + 26.x |
| `common/src/legacy121` | 1.21.1 only |
| `common/src/obfuscated` | remapped builds |
| `common/src/unobfuscated` | non-remapped builds |

**Any vanilla API that differs across targets goes in a compat fork**, following the existing pattern:

```
common/src/modern/java/.../util/PermissionCompat.java
common/src/modern/java/.../util/ProfileCompat.java
common/src/modern/java/.../util/FailureSoundCompat.java
common/src/legacy121/java/.../util/PermissionCompat.java   (same names, different bodies)
common/src/unobfuscated/java/.../util/CompatMenu.java
```

---

## 4. Where things live

```
api/        public API v1 — com.reazip.economycraft.api.v1 (18 types)
common/     all shared logic
fabric/     Fabric entrypoint + Fabric mixins
neoforge/   NeoForge entrypoint + NeoForge mixins
wiki/       GitHub wiki pages (a SEPARATE git clone — see §8)
```

Module layout is Architectury multi-project: `api/`, `common/`, `fabric/`, `neoforge/`.

### Public API

18 types under `com.reazip.economycraft.api.v1`, reached via `EconomyCraftApi.get(server)`. Provider installed
once by `EconomyCraftApiBootstrap`. 17 are public; `EconomyCraftApiAccess` is package-private and is the
provider `get(server)` resolves, so consumers cannot and should not import it.

`FactionApi` is deliberately read-only — there is no `select()`. Choosing a party is a player-facing action
with a 30-hour lockout, a tag refresh and a save write, and a writable API would be a second unvalidated path to
the same state. `FactionIds` exists so a consumer in another repository writes `FactionIds.MONARCHY` and gets a
compile error on a rename, rather than a silently-never-applying discount from comparing against `"Monarchy"`.

```java
EconomyCraftApi api = EconomyCraftApi.get(server);
api.balances();          // BalanceApi
api.prices();            // PriceApi — read-only
api.leaderboard();       // LeaderboardApi — read-only
api.balanceEvents();     // post-mutation, cannot cancel
api.factions();          // FactionApi
api.formatMoney(long);
api.inflationMultiplier();
api.medianActiveBalance();
```

`api/v1` is extended **additively only**. `inflationMultiplier()` and `medianActiveBalance()` were added for
ShopGuard integration and are the precedent.

### Configuration

`common/src/main/resources/assets/economycraft/config.json` — **164 leaf keys** across:

| Section | Keys | Notes |
|---|---|---|
| top level | 34 | balances, limits, expirations, wealth tax, feature toggles |
| `professions` | 61 | per-profession thresholds and effects |
| `factions` | 38 | per-faction tax rates, icons, colours, multipliers |
| `quests` | 14 | server-funded quests and buyback |
| `gemini_gossip` | 14 | LLM provider, prompts, privacy |
| `motd` | 3 | login message |
| **total** | **164** | |

Shipped data directory: `config/economycraft/{config,prices,webhook}.json` + `data/*.json` + `logs/*.log`.
About 21 data files are written at runtime; `util/EconomyPaths.java:40-51` marks which are importable.

### Land claims are not ours

Claims live in **ShopGuard**, an external Fabric-only mod, not in this repository.

EconomyCraft **does** reference ShopGuard, but only reflectively, so the dependency stays one-way and
optional:

| Item | Location |
|---|---|
| `ClaimBridge` — the seam, package-private | `common/.../integration/ClaimBridge.java` |
| `ReflectiveShopGuardBackend` — reflection over ShopGuard's classes | `common/.../integration/ReflectiveShopGuardBackend.java` |
| Availability gate | `ClaimBridge.java:53` — `Platform.isModLoaded("shopguard")` |
| Only consumer | `common/.../faction/FactionEffects.java:157,169` |

There is **no** `ClaimEconomy.Backend` in this codebase. That interface is ShopGuard's side of the seam; it
is named in `integration/package-info.java:6` as the pattern being mirrored, not as a type EconomyCraft
implements.

When ShopGuard is absent, `ClaimBridge.isAvailable()` returns false and `FactionEffects` skips the claim
rules, so Monarchy's claim cost and claim damage and Anarchism's wilderness speed degrade gracefully.

### Villager dialogue exists

Gossip is built on villager dialogue — `gossip/GossipConfig.java`, `/eco gossip dialogue [prof]`. Villager
*trading* as a feature is separate and minimal.

### Builder reach is a vanilla attribute, not a range check

The `Thành thạo` reach bonus modifies `player.block_interaction_range` (default `4.5`) with one
`AttributeModifier`. It is not a mixin and it needs no client mod, because *both* the client's block picking and
the server's own range check already read that attribute.

This corrects an earlier conclusion that a server-side mod cannot extend reach on a vanilla client — that
looked for the range check in the wrong class.

Two consequences worth knowing before editing it:

- It widens **block interaction** range, not building range. A Master Builder also reaches further to open a
  chest, read a sign or click an item frame. That is inherent to the single shared attribute and is intended.
- It cannot be made conditional on the held item, because an attribute modifier is not per-item and the held
  item at placement time *is* the block just placed — gating on it would kill the reach at the exact moment a
  block is placed.

### Verifying toll changes

The toll feature has its own verification notes. They are contributor-facing, so they live here rather than in
the player wiki page.

`TollUiTest` exercises real menu callbacks and the toll store with mocked server and player boundaries. It
covers visibility, missing targets, cancellation, block and game-mode restrictions, revoked permissions, changed
ownership, owner/admin flows, recipient limits, online notices, reload from disk, and the existing right-click
and pressure plate payment paths.

```sh
./gradlew -Pfilter_platforms=fabric -Pminecraft_version=26.3 :common:test :fabric:build
```

For an in-game smoke test, check the menu as an owner, a non-owner and an admin; test a protected block, an
out-of-reach block, cancel/back navigation, and both toll interaction types (right-click and pressure plate).
These are manual checks and need no change to a running server.

Behaviour worth knowing before editing this area:

- Tolls attach to one block position. A double chest is two blocks, but both halves resolve to one toll and
  count as one active toll against `max_active_tolls_per_player` (default `10`).
- A hopper directly under a toll chest cannot extract items, so automation cannot bypass the fee. Player
  interaction still pays.
- Admin access grants admin transfer and admin remove only. It does **not** grant fee editing or a creation
  override, and the toll commands keep their owner checks.
- The fee also shows on the vanilla action bar while the crosshair is on the toll block within five blocks.

---

## 5. Documentation rules

The repository's user-facing docs must match the shipped code. This is the rule that keeps them matching.

**Source of truth, in order:**

1. `config.json` — every configuration key and default
2. Registered commands, permission nodes (`util/EconomyPermissions.java:12-29`), public API package
3. `build.gradle` / `gradle.properties` — platform matrix, Java version, mod version

**Rules:**

- Never document a key, command, permission or type that does not ship. Never document one that does ship and
  is undocumented. Both directions matter.
- State each default exactly once. A second mention is a cross-reference, never a restatement.
- State a key's **exact** shipped name. Keys are `snake_case`; top-level ones are `camelCase`
  (`startingBalance`, `dailyAmount`). Invented variants such as `ownClaimDamageMultiplier` are defects.
- No phase numbers, task identifiers or internal decision IDs in user-facing docs. Nobody outside the
  original effort can act on them.
- Preserve the GPL-3.0 upstream attribution in `README.md`.

### Wiki language policy

Per-page language follows audience. **This is deliberate, not drift.** Read this before adding a wiki page.

| Audience | Language | Pages |
|---|---|---|
| Players | **Vietnamese** | `Chon-tag`, `Factions`, `Professions`, `Tolls` |
| Integrators | **English** | `Home`, `Getting-Started`, `Balances-and-Payments`, `Prices-and-Leaderboard`, `Balance-Events`, `API-Reference` |

Each page is exactly one language — do not mix, and do not write an English paragraph inside a Vietnamese
page or the reverse.

**Player-facing pages carry no code.** No Gradle commands, no test-class names, no internal identifiers. When
contributor guidance is buried in a player page, it moves *here* rather than being deleted; `wiki/Tolls.md`
carried a `TollUiTest` verification block until it was relocated to §4 above.

Integrator pages may contain Java and are written in English.

The same policy is restated in `wiki/_Sidebar.md` so a reader of the wiki sees it too. It is stated in exactly
two places deliberately: this file for contributors, the sidebar for readers. Do not restate it a third time.

---

## 6. Spec Kit workflow

Spec-driven development lives in `specs/<NNN>-<short-name>/`. Commands are `/speckit.specify`, `.plan`,
`.tasks`, `.implement`, plus `.clarify`, `.checklist`, `.analyze`, `.converge`, `.taskstoissues`.

Project governance lives in `.specify/memory/constitution.md`, which holds the normative principles. **This
file is its operational companion** — it adds the code citations, build commands and architecture map. Where
the two overlap, this file defers to the constitution on principle, and the constitution defers here on
mechanics.

Numbering is sequential (`feature_numbering: sequential` in `.specify/init-options.json`).

---

## 7. Traps

Things that will waste your time if you trust them.

| Trap | Reality |
|---|---|
| A raw test count in any document | Coverage is per-target. Wrong for 4 of 5 targets |
| "This is an aspirational feature, coming soon" in a wiki page | Wiki documents what ships. If it does not ship, it is not in the wiki |
| Assuming ShopGuard is in this repo | It is not. External, Fabric-only, one-way dependency |
| Looking for a `TabStyle` class | It is `TagStyle` (`tag/TagStyle.java`) |
| Looking for `EconomyManager` in `util/` | It is at the package root |
| `online_time.json` not being in `DATA_FILES` | It is deliberately excluded — `EconomyPaths.java:32`. The file exists and is used by `OnlineTimeService`, but `/eco import` must not carry it: importing per-player progression would hand every player a fresh party and profession. `player_activity.json` is last-seen millis for dynamic pricing and is **not** online time; the two must never be merged |
| Assuming tax is computed in one place | It *is*: `tax/TaxPolicy.java`, reached through `TaxScope`, with ~20 call sites. It was once copy-pasted across 18 of them. `tax/package-info.java` states the invariant — no `taxRate` multiplication outside that package — but **nothing enforces it**, so verify with a grep rather than trusting it. Add a tax site by calling `TaxPolicy`, never by inlining `Math.round(base * taxRate)` |

---

## 8. The wiki is a separate repository

`wiki/` is **not** rendered by GitHub from this repository. A GitHub wiki is its own git clone. Changes here
do not appear on the hosted wiki until pushed there:

```bash
git clone https://github.com/TraiNguyenVan/EconomyCraft.wiki.git
# copy pages in, then commit and push
```

Internal wiki links are relative (`Factions`); links from `README.md` to a wiki page must be absolute
(`https://github.com/TraiNguyenVan/EconomyCraft/wiki/...`), because a wiki page is not reachable by a
repository-relative path.

---

*Verified against the repository on 2026-10-07 (`mod_version 1.10.0`, unreleased; `git tag` ends at `1.9.0`).
Where this file and the code disagree, the code wins.*