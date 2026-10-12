# Docs vs code — release 1.10.0 readiness (user-facing documentation audit)

Scope: `README.md`, `wiki/*.md` (18 files), `docs/contributing/*.md`, `AGENTS.md`,
`.specify/memory/constitution.md`. Code is authoritative. Read-only audit; nothing was changed.

Baseline: `mod_version = 1.10.0` (`gradle.properties:6`), unreleased; last tag `1.9.0` (`AGENTS.md:79`).

---

## Summary verdict

**Not ready to ship as-is.** The command surface and the config *key names* are in good shape — no
documented command is missing from the code, and no documented config key is absent from
`config.json`. But three classes of defect remain:

1. **The 1.10.0 headline change is invisible to server admins.** The move to a single SQLite file
   (`economycraft.db`) is documented only in `CHANGELOG.md:29-40` and `AGENTS.md:31-34`. `README.md`
   still tells admins the mod writes ~22 separate JSON files plus `villagers.db` under `data/`
   (`README.md:262-291`) and lists the `/eco import` subset without `economycraft.db`
   (`README.md:295-297`). `wiki/` says nothing about it at all. Anyone following the README after
   upgrading will look for files that no longer exist.
2. **Several README statements are factually contradicted by the code** — the `max_contract_reward`
   clamp range (`README.md:394`), the `shop.json` migration target (`README.md:280`), the
   `/eco import` registration condition (`README.md:209`), and the `gemini_gossip` prompt default
   (bundled `config.json:253` vs `GossipConfig.java:44-58`).
3. **The public wiki cannot be released in sync.** `wiki/` is a hand-copied mirror
   (`docs/contributing/documentation.md:27-32`) and there is no sync workflow
   (`.github/workflows/` contains only `release.yml`). A 1.10.0 release leaves the hosted wiki stale
   unless a human copies pages by hand.

Findings are ranked inside each section, and section 8 collects them into a single ranked table
(D1-D18). The highest-severity items are D1-D3 (persistence docs and wiki sync), D4-D12 (wrong
values/ranges and undocumented shipped surface), and R4 (a constitution governance row that cites a
mechanism the code no longer uses).

---

## 1. Commands diff

### Method

Registered command tree read from `common/src/main/java/com/reazip/economycraft/EconomyCommands.java`
(root at `:172-215`, standalone registration at `:80-148`) plus `SellCommand.java:19-21` and
`WorthCommand.java:27-33`. Documented commands read from `README.md:51`, `README.md:190-211`,
`README.md:235-249`, `wiki/Server-Administration.md:33-53` and the per-feature wiki pages.

### Registered in code (authoritative)

| Path | Evidence |
|---|---|
| `/eco`, `/eco menu`, `/eco admin` | `EconomyCommands.java:172-180` |
| `/eco bal [target]` | `EconomyCommands.java:386-392` |
| `/eco pay <player> <amount>` | `EconomyCommands.java:394-409` |
| `/eco sell` | `EconomyCommands.java:80-87`, `SellCommand.java:19-21` |
| `/eco ah`, `/eco auction` (+`list`, `search`, `<price> [amount] [description]`) | `EconomyCommands.java:191-192`, `:800-845` |
| `/eco offers` (+`ah <id>`, `order <id>`) | `EconomyCommands.java:193`, `:1091-1104` |
| `/eco shop` (+`search`, `<category>`) | `EconomyCommands.java:194`, `:935-952` |
| `/eco orders` (+`request`, `search`) | `EconomyCommands.java:195`, `:986-1030` |
| `/eco contracts` (+`mine`, `new`, `view`, `actions`, `accept`, `submit`, `approve`, `revise`, `cancel`, `dispute`, `admin list [status]`, `admin resolve <id> pay / refund`) | `EconomyCommands.java:1159-1220` |
| `/eco deliveries`, `/eco daily`, `/eco transactions` | `EconomyCommands.java:196-201`, `:1500-1533` |
| `/eco toll` (+`create`, `set`, `transfer`, `info`, `remove`) | `EconomyCommands.java:199`, `:220-229` |
| `/eco tag`, `/eco job`, `/eco party` | `EconomyCommands.java:200-202`, `:1536-1660` |
| `/eco worth` | `EconomyCommands.java:203`, `WorthCommand.java:27-33` |
| `/eco gossip` (bare = status; `status`, `dialogue [profession]`, `reload`, `memory inspect <v> <p>`, `memory clear <v> <p>`) | `EconomyCommands.java:1727-1757` |
| `/eco motd`, `/eco reload` | `EconomyCommands.java:202-203` |
| `/eco addmoney`, `/eco setmoney`, `/eco removemoney`, `/eco removeplayer` | `EconomyCommands.java:73-78`, `:116-148` |
| `/eco import` (integrated server only) | `EconomyCommands.java:211-214` |
| Standalone aliases: `/bal`, `/pay`, `/sell`, `/ah`, `/auction`, `/offers`, `/shop`, `/orders`, `/contracts`, `/deliveries`, `/daily`, `/transactions`, `/toll`, `/tag`, `/job`, `/party`, `/worth` | `EconomyCommands.java:82-112`, `:154-157` |
| Standalone admin aliases: `/addmoney`, `/setmoney`, `/removemoney`, `/removeplayer`, `/gossip` | `EconomyCommands.java:115-148` |

### Documented but absent from code

**None found.** In particular the removed public-gossip commands are gone from both sides:
`/eco gossip refresh` and `/eco gossip test` appear nowhere in `README.md` or `wiki/` (grep over
`README.md wiki/`), they are absent from `EconomyCommands.java:1727-1757`, and they are listed as
removed in `CHANGELOG.md:56`.

### In code but undocumented

| Command | Status |
|---|---|
| `/contracts actions <id>` | Registered at `EconomyCommands.java:1170-1173`; documented nowhere in `README.md` or `wiki/`. The README's headless list (`README.md:78`) skips it. **Medium.** |
| `/eco gossip memory inspect <villager-uuid> <player-uuid>` | Registered `EconomyCommands.java:1742-1748`; documented only at `wiki/Server-Administration.md:42`, missing from the README admin-command table (`README.md:190-207`). **Medium.** |
| `/eco gossip memory clear <villager-uuid> <player-uuid>` | Registered `EconomyCommands.java:1749-1756`; documented only at `wiki/Server-Administration.md:43`. **Medium.** |
| `/shop <category>` / `/shop search` | `EconomyCommands.java:939-951`; documented at `wiki/Shop-and-Selling.md:7` but not in `README.md`. **Low.** |

### Documented inaccurately

| Claim | Reality |
|---|---|
| `README.md:78`: "`view`, `accept`, `submit`, `approve`, `revise`, `cancel` and `dispute` each take an id." | `revise <id> <reason>` and `dispute <id> <reason>` both require a reason (`EconomyCommands.java:1192-1205`), and `submit <id> [notes]` takes optional notes (`EconomyCommands.java:1183-1189`). `wiki/Contracts.md:23,35,44` gets all three right. **Low.** |
| `README.md:209`: "`/eco import` is only registered when short commands are on". | The gate is the command selection, not `standalone_commands`: `if (selection != Commands.CommandSelection.DEDICATED)` at `EconomyCommands.java:211-214`, plus `EconomyCraft.canImportSharedFolder()` (`EconomyCraft.java:317-320`). `/eco import` is therefore absent on a dedicated server regardless of `standalone_commands`. **Medium.** |
| `README.md:242-249` permission-to-command mapping | Matches `EconomyPermissions.Nodes.COMMAND_ALL` (`EconomyPermissions.java:28-35`) and the `withCommandPermission(...)` wiring at `EconomyCommands.java:181-203`. No defect. |

---

## 2. Config keys diff

### Method

Leaf keys flattened from `common/src/main/resources/assets/economycraft/config.json` (175 leaves) and
compared against every Markdown table row of the form ``| `key` | `default` | ... |`` in `README.md`,
plus the keys named in `wiki/*.md`. Defaults were compared numerically/string-wise against the bundled
JSON. Field names and clamps were cross-checked against
`common/src/main/java/com/reazip/economycraft/EconomyConfig.java`,
`config/FactionsSection.java`, `config/ProfessionsSection.java`, `config/QuestsSection.java`,
`config/MotdSection.java`, `config/MotdBlock.java` and `gossip/GossipConfig.java`.

### (a) Documented but absent from the code

**None.** Every config key named in `README.md` and `wiki/` resolves to a real leaf in `config.json`
or a real `@SerializedName` field in `EconomyConfig.java` / its section classes.

This includes the removed public-gossip keys: `public_chat`, `public_chat_chance`,
`refresh_interval_minutes`, `system_instruction` and `pool_size_per_category` appear **only** in
`CHANGELOG.md:49-50` (as removals), never in `README.md` or `wiki/`.

### (b) In the code but undocumented

`README.md:307-308` claims "Every shipped key, with its shipped default", and `README.md:315` claims
the table is "generated from config.json". Eight shipped keys are missing from `README.md`:

| Key | Default | Evidence |
|---|---|---|
| `quests.category_weights.ores` | `30` | `config.json:233-239` |
| `quests.category_weights.food` | `25` | `config.json:233-239` |
| `quests.category_weights.redstone` | `20` | `config.json:233-239` |
| `quests.category_weights.utility` | `15` | `config.json:233-239` |
| `quests.category_weights.blocks.stones` | `10` | `config.json:233-239` |
| `quests.max_per_category` | `2` | `config.json:232` |
| `quests.max_unsold_expiries` | `1` | `config.json:231` |
| `quests.reprice_threshold_percent` | `0.1` | `config.json:230` |

The README `quests` table (`README.md:630-647`) lists 14 of the 22 quest leaves; the aggregate table
(`README.md:461-474`) repeats the same 14. **Medium** — the "every shipped key" promise is false.

`gemini_gossip` is **not** affected: `config.json:245-255` has exactly 9 keys and the README documents
all 9 (`README.md:372-389`, repeated at `:659-676`). The 14-to-9 reduction described in
`CHANGELOG.md:49-50` is reflected in both the bundled config and the docs. No defect.

The `contracts_*` keys are documented in three places — `README.md:322-327`, `README.md:390-394`, and
`wiki/Contracts.md:49-52` — and all seven exist in `config.json:22-29` and `EconomyConfig.java:64-79`.
No defect.

### (c) Documented defaults or ranges that disagree with the code

| Doc claim | Code | Severity |
|---|---|---|
| `README.md:394`: `max_contract_reward` "Clamped to 1-99,999,999." | `clampContractReward` clamps to `1 .. EconomyManager.MAX` where `MAX = 999_999_999_999L` (`EconomyConfig.java:283-288`, `EconomyManager.java:158`). The documented upper bound is four orders of magnitude too low. | Medium |
| `README.md:376` / `README.md:663`: `gemini_gossip.dialogue_system_instruction` default shown as the 5-item "Keep it short and easy to understand" prompt (matching `config.json:253`). | The code fallback used when the key is absent/null is a *different*, 6-item sales-pitch prompt (`GossipConfig.java:44-58`, applied at `GossipConfig.java:234`). A server that deletes the key gets behaviour the README does not describe. | Medium |
| `README.md:376` / `README.md:663`: the default value is embedded as a raw multi-line string inside a Markdown table cell, so the row spans many physical lines. | Cosmetic/rendering defect; the documented text itself matches `config.json:253`. | Low |

Every other documented default was machine-compared against `config.json` and matched, including
`motd.delay_ticks` (0-1200, `MotdSection.java:19-21`), `motd.*.next_delay_seconds` (0-3600,
`MotdBlock.java:11-13`), `gemini_gossip.cooldown_minutes` (1-60, `GossipConfig.java:62-63`),
`gemini_gossip.temperature` (0.0-2.0, `GossipConfig.java:66-67`) and
`gemini_gossip.private_chat_chance` (0.0-1.0, `GossipConfig.java:60-61`).

### (d) Duplicated config tables (drift risk)

`README.md` states the same key/default pairs twice: once in the aggregate table
(`README.md:315-494`) and again in the per-section tables (`#### factions` at `README.md:497`,
`#### professions` at `README.md:531`, `#### quests` at `README.md:624`,
`#### gemini_gossip` at `README.md:649`, `#### motd` at `README.md:680`). The two copies currently
agree, but the missing `quests.*` keys above show the copies are not generated from one source.
**Low (maintenance risk).**

---

## 3. Stale references

### S1 — JSON-file persistence is documented as current (HIGH)

`README.md:262-291` presents a table of "Data files" that "The mod writes ... under `data/`" and lists
22 entries including `balances.json`, `stats.json`, `tolls.json`, `quests.json`, `parties.json`,
`professions.json`, `contracts.json`, `negotiations.json`, `fiscal.json`, `faction_fiscal.json`,
`cooldowns.json`, `online_time.json`, `stock.json` and `villagers.db`. It then says "They are written
only when something changes, so an untouched file does not appear until first use"
(`README.md:264-265`).

The code does the opposite:

- Every store writes through the single database seam: `Documents.write` returns after
  `db.writeDocument(key, content)` whenever a database is present
  (`common/src/main/java/com/reazip/economycraft/db/Documents.java:45-51`).
- The document keys (legacy file names) survive only as row keys:
  `db/EconomyDatabase.java:49-73` (`DOCUMENT_KEYS`) and `:40` (`DB_FILE_NAME = "economycraft.db"`).
- Call sites confirm the production path, e.g. `EconomyManager.java:555-579` (`balances.json`,
  `daily_sells.json`, `stats.json`), `TollManager.java:254` (`tolls.json`),
  `QuestManager.java:644` (`quests.json`), `ContractManager.java:194` (`contracts.json`),
  `NegotiationStore.java:153` (`negotiations.json`), `FactionStore.java:146` (`parties.json`),
  `ProfessionStore.java:384` (`professions.json`), `CooldownService.java:146` (`cooldowns.json`),
  `AuctionManager.java:270`, `OrderManager.java:246`, `DeliveryManager.java:101`,
  `FiscalPass.java:211`, `FactionFiscalPass.java:376`, `QuestStock.java:101`.
- The database is opened at `EconomyManager.java:167` and `TollManager.java:46` on
  `data/economycraft.db`, and on first boot legacy JSON is imported once and archived under
  `data/_migrated-json/` (`EconomyDatabase.java:43`, `:281-322`).

### S2 — `villagers.db` documented as a separate file (HIGH)

`README.md:291` lists `villagers.db` as a current data file ("Generated villagers, their names and
traits, and their memories..."). In 1.10.0 the villager database *is* `economycraft.db`:
`EconomyCraft.java:137-139` constructs `VillagerDatabase` on
`EconomyPaths.dataDir(server).resolve(EconomyDatabase.DB_FILE_NAME)`, and
`EconomyDatabase.importVillagers` (`:324-366`) copies the three gossip tables out of a legacy
`villagers.db` and archives it. `AGENTS.md:31-34` and `CHANGELOG.md:29-40` state this correctly; the
README and the whole `wiki/` do not.

### S3 — `wiki/Tolls.md:14` cites a `tolls.json` store (MEDIUM)

"Changes are saved to the existing `tolls.json` store." `TollManager.java:254` writes the
`tolls.json` *document key* into `economycraft.db`; no such file exists on disk after first boot.

### S4 — README `shop.json` row describes the wrong migration target (MEDIUM)

`README.md:280`: "`shop.json` | Legacy delivery storage, migrated into `deliveries.json` on first
start." The code treats `shop.json` as read-only legacy input for **auctions**: "`shop.json` is
read-only legacy: it feeds the `auctions.json` document when no auctions file exists, then is
archived" (`EconomyDatabase.java:45-47`, implemented at `:295-298` and `:318-320`).

### S5 — README `/eco import` list omits `economycraft.db` (MEDIUM)

`README.md:295-297` lists the import subset as `balances.json`, `daily.json`, `daily_sells.json`,
`deliveries.json`, `auctions.json`, `shop.json`, `orders.json`, `notifications.json`,
`player_activity.json`, `player_names.json`. The code's `DATA_FILES`
(`EconomyPaths.java:40-56`) contains those ten **plus `economycraft.db`** (`:55`, with the comment at
`:51-54` explaining that it is now carried by `/eco import`). `CHANGELOG.md:35-37` states this
correctly.

### S6 — Names checked and found NOT stale (no defect)

- `AsyncFileWriter` still exists and is still reachable: `util/AsyncFileWriter.java:14-53`, used by
  `Documents.write` on the `db == null` (test/legacy) path (`Documents.java:50`) and flushed on
  server stop (`EconomyCraft.java:105`). Docs do not reference it; see the constitution note in
  section 6.
- `EconomyCraftApiImpl` does not exist and is correctly described as an old draft name
  (`AGENTS.md:20-21`, `constitution.md:33-35`).
- No doc references `/eco gossip refresh`, `/eco gossip test`, a rumor pool, `public_chat*`,
  `refresh_interval_minutes`, `system_instruction` or `pool_size_per_category` except
  `CHANGELOG.md:47-59`, which describes them as removed.
- `docs/contributing/architecture.md:46-48` correctly states "There is no shared rumor pool,
  transaction digest or public chat broadcast option."

---

## 4. Version and release surfaces

### V1 — Supported-version table and Java requirement match the build (no defect)

`README.md:12-18` lists 1.21.1, 1.21.11, 26.1.2, 26.2, 26.3 and `README.md:20` says "The 1.21.x line
needs a Java 21 server; the 26.x line needs Java 25." That matches `build.gradle:30-36`
(`mcTargets`: 1.21.1, 1.21.11, 26.1.2, 26.2, 26.3; `javaVersion: 21` on the two 1.21 entries only) and
the default at `build.gradle:100` (`tgt.javaVersion ?: 25`). `AGENTS.md:52-54` states the same list.
No defect.

### V2 — Fabric/NeoForge dependency statements are incomplete (LOW/MEDIUM)

`README.md:4` says only "Requires Architectury API." That matches the publish dependency in
`.github/workflows/release.yml:205-206` (`architectury-api | depends`) and the Architectury requirement
in `neoforge/src/main/resources/META-INF/neoforge.mods.toml:32-37` and
`fabric/src/main/resources/fabric.mod.json:20-25`. But `fabric.mod.json:22` also declares
`"fabric-api": "*"` as a **required** dependency, and `fabric.mod.json:23-25` requires
`fabricloader >= 0.15.0`. Neither Fabric API nor a minimum loader version is mentioned anywhere in
`README.md`. **Low/Medium** — a Fabric admin could install the jar without Fabric API.

### V3 — No version, badge, download, changelog or store link in any doc (LOW)

Nothing in `README.md`, `wiki/*.md`, `docs/contributing/*.md` or `AGENTS.md` states a mod version,
shows a badge, or links to the Modrinth/CurseForge pages or to `CHANGELOG.md` (grep for
`badge|shields.io|modrinth|curseforge|1\.9\.0|1\.10\.0|CHANGELOG|download|releases` returns only
`README.md:807`, an optional Placeholder API link, and `AGENTS.md:79`). Consequently there is nothing
here that would be *wrong* at 1.10.0, but there is also no release surface at all: a reader cannot
tell which version the README documents. The store ids exist only in CI
(`.github/workflows/release.yml:194-195`: `modrinth-id: H4o5bO7g`, `curseforge-id: 1321557`) and are
not published anywhere user-facing. **Low** (gap, not an error).

### V4 — Dated "verified"/"amended" claims are five days old (LOW, partly unverifiable)

`AGENTS.md:79` asserts "Verified against the repository on 2026-10-07 (`mod_version 1.10.0`,
unreleased; last tag `1.9.0`)." and `.specify/memory/constitution.md:10,141` assert
"Last Amended: 2026-10-07". Today is 2026-10-12. If anything changed in the tree after 2026-10-07 the
"verified" claim is stale by construction; if 1.10.0 has since been tagged, "unreleased" is wrong.
**Assumption / unverifiable here:** git history and tags are out of scope for this audit (no git
commands permitted), so I cannot confirm whether the tree changed after 2026-10-07 or whether the
1.10.0 tag exists. The dates themselves are verifiable and are five days behind the audit date.

### V5 — Release workflow Java vs documented requirement (no defect, for the record)

`.github/workflows/release.yml:131-134` builds every target on `java-version: 21`; the per-target
Java requirement is satisfied by Gradle toolchains (`build.gradle:100-105`, `:173`). This is
consistent with the README's Java statement and is not a doc defect.

---

## 5. Doc-rule violations (constitution + AGENTS.md)

### R1 — Raw test counts: compliant

`constitution.md:118-119` forbids raw test counts. No raw test count appears in `README.md`,
`wiki/*.md`, `docs/contributing/*.md` or `AGENTS.md`; the only matches for "test count"/"coverage"
are the rules themselves (`wiki/_Sidebar.md:28`, `AGENTS.md:47-48`, `constitution.md:118-119`).
Compliant.

### R2 — Phase numbers / task ids / decision ids in user-facing docs: compliant (one contributing-doc hit)

A regex sweep for `phase N`, `R#`, `FR-#`, `US#`, `T##`, `task N`, `decision N`, `specs/NNN` over
`README.md`, `wiki/*.md`, `docs/contributing/*.md`, `AGENTS.md` and `constitution.md` returns **no
matches**. The internal identifiers do still appear in **source comments** outside the audited doc
set — `faction/package-info.java:33` ("TODO.md §7 Phases 9-10"), `profession/package-info.java:22`,
`tag/package-info.java:7,33`, `time/package-info.java:5` ("TODO.md §5 R10"),
`EconomyConfig.java:106-115` ("Phase 2") and
`neoforge/src/main/resources/META-INF/neoforge.mods.toml:39` ("TODO.md P3-T1"). These are code, not
user-facing docs, so they are not violations of the letter of the rule — but `TODO.md` was retired
(`constitution.md:11`), so every pointer to it is a dangling reference for contributors.

### R3 — "Documentation describes what ships": one gap on a shipped feature

The **contracts** feature is shipped and *is* documented: `README.md:36` (hub button), `:70-84`
(feature), `:224`, `:242` (permissions), `:322-327`, `:390-394` (config); `wiki/Contracts.md`;
`wiki/_Sidebar.md:9`. The gaps are `actions` (section 1) and the two `gossip memory` subcommands
missing from the README admin table.

The **SQLite persistence change** is shipped but is documented **only** where a server admin would
not normally look: `CHANGELOG.md:29-40` and `AGENTS.md:31-34` (a contributor file). Neither
`README.md` nor `wiki/Server-Administration.md` mentions `economycraft.db`, SQLite, the one-time
import or the `data/_migrated-json/` archive. This is the clearest "shipped but undocumented"
feature for a server-admin audience.

### R4 — `constitution.md` governance table cites a mechanism that no longer enforces the principle

`constitution.md:133` lists the enforcement of Principle VI ("Persistence is one SQLite file") as
"`AsyncFileWriter` plus the `SERVER_STOPPING` save". `AsyncFileWriter` no longer participates in the
production write path: `Documents.write` only calls it when `db == null` (`Documents.java:44-51`),
and the only production call is the shutdown flush (`EconomyCraft.java:105`). The prose body of
Principle VI (`constitution.md:66-75`) is accurate and cites `db/EconomyDatabase.java` and
`EconomyCraft.java` correctly; the governance row contradicts it. **Medium** — the constitution's own
closing rule ("a principle that cannot be verified is an aspiration") makes an inaccurate
enforcement citation self-defeating.

### R5 — `constitution.md` line-number citations have drifted

| Citation | Actual |
|---|---|
| `constitution.md:42` — `EconomyManager.java:502` is `transferMoney(from, to, debitAmount, creditAmount, source, detail)` | The method is at `EconomyManager.java:539` (6-arg overload) and `:544` (7-arg); line 502 is elsewhere. |
| `constitution.md:54` — `EconomyManager.java:81` is `FISCAL_SOURCES` | `FISCAL_SOURCES` is declared at `EconomyManager.java:82`. |
| `constitution.md:33` — `api/v1/EconomyCraftApiBootstrap.java:49` is the implementation behind `EconomyCraftApi.get(server)` | The file is `common/src/main/java/com/reazip/economycraft/api/v1/EconomyCraftApiBootstrap.java` (correct path); line 49 is inside the balance-events lambda, not the class entry point. **Low.** |

### R6 — `docs/contributing/architecture.md` mixin list is incomplete

`docs/contributing/architecture.md:12-15` says "Nine are shared; Fabric-only entries are
`TollPressurePlateMixin`, `TollBasePressurePlateMixin` and `TollHopperMixin`; `ProfessionBreakMixin`
is NeoForge-only." "Nine shared" is correct (intersection of
`fabric/src/main/resources/economycraft.mixins.json:5-21` and
`neoforge/src/main/resources/economycraft.mixins.json:5-16`), but three further Fabric-only mixins
are omitted: `SignBlockEntityMixin`, `AnvilMenuMixin` and `BookEditMixin`
(`fabric/.../economycraft.mixins.json:19-21`, absent from the NeoForge list). `AGENTS.md:37-38`
repeats the same incomplete list. **Medium** for a contributor reference that promises the current
sets.

---

## 6. Wiki mechanics

### How `wiki/` reaches the public wiki

By hand, and only by hand. `docs/contributing/documentation.md:27-32` is the entire documented
process: "The `wiki/` directory in this repository is not the hosted GitHub wiki; that wiki is a
separate Git clone. Copy changed pages to the wiki clone, commit and push them there. Include
`_Sidebar.md` whenever navigation changes...". `AGENTS.md:11` describes `wiki/` as "content for the
separate GitHub wiki repository".

There is **no automation**:

- `.github/workflows/` contains exactly one file, `release.yml` (a Modrinth/CurseForge publish
  workflow). Grepping every `*.yml`, `*.yaml`, `*.sh`, `*.gradle`, `*.json` and `*.md` in the repo for
  "wiki" returns only `AGENTS.md`, `README.md`, `.specify/memory/constitution.md`,
  `docs/contributing/documentation.md`, `wiki/_Sidebar.md` and a spec-kit extension cache — no script,
  no workflow, no Gradle task.
- The release workflow publishes only jars (`release.yml:191-208`); it never touches the wiki.

### Would a release leave the public wiki stale?

**Yes, unless a human performs the manual copy.** Evidence:

- The mirror is manual (`docs/contributing/documentation.md:29-32`), so nothing in a 1.10.0 release
  updates the hosted wiki.
- `wiki/` currently has **no page** for the 1.10.0 contracts feature beyond `wiki/Contracts.md`, and
  that page is already linked from `wiki/_Sidebar.md:9`, so the sidebar would be consistent only if
  the manual copy happens.
- `wiki/` has **no** coverage of the SQLite persistence change at all (grep for
  `economycraft.db|SQLite|sqlite|database` over `wiki/*.md` returns nothing), so the hosted
  Server Administration page will keep describing a JSON-file world after 1.10.0 ships.
- `wiki/Tolls.md:14` still points at a `tolls.json` store, which is wrong at 1.10.0 (section 3, S3).
- The `wiki/Home.md` landing page is the API overview (`wiki/Home.md:1` = "# EconomyCraft API v1") and
  never introduces the mod to players or server owners; the only navigation to gameplay pages is
  `wiki/_Sidebar.md:1-16`. A release that copies pages but leaves `Home.md` as-is keeps a landing page
  that omits contracts, tolls, factions and the persistence change.

### `has_wiki` and the missing sync workflow

**Unverifiable from this sandbox (assumption).** The brief states the GitHub repository reports
`has_wiki=false`. I cannot confirm that here: no network access and no git/`gh` commands are
permitted for this audit. What *is* verifiable is that there is no wiki-sync workflow in
`.github/workflows/` (only `release.yml`) and no script anywhere in the tree, which is the mechanism
that would matter if `has_wiki` were true. If `has_wiki` is indeed false, the manual copy process in
`docs/contributing/documentation.md:27-32` is also unusable as written (there is no hosted wiki to
push to), which would make that guide stale.

---

## 7. Open questions

1. **Is `data/economycraft.db` intended to be documented as a data file, or are the legacy document
   keys still meant to be listed?** `README.md:267-291` lists 22 rows by legacy name;
   `EconomyPaths.DATA_FILES` (`:40-56`) lists 11, one of which is `economycraft.db`. Which is the
   intended admin-facing model is not stated anywhere.
2. **Is the `gemini_gossip.dialogue_system_instruction` fallback supposed to match the bundled
   default?** `GossipConfig.java:44-58` and `config.json:253` disagree (section 2c). If the code
   default is the intended one, the README and the bundled config are both wrong; if the bundled
   value is intended, the code fallback is.
3. **Should `README.md` keep the aggregate config table and the per-section tables?** They duplicate
   the same key/default pairs and have already diverged from "every shipped key" (section 2d).
4. **Should `/eco import` be documented as integrated-server-only, or is the dedicated-server
   exclusion a bug?** `EconomyCommands.java:211-214` silently omits the command on dedicated servers;
   `README.md:195,209` implies it is available whenever short commands are on.
5. **Is there a public store listing / release-notes link that should be in `README.md`?** The store
   ids exist only in CI (`release.yml:194-195`); no doc links to `CHANGELOG.md` or to releases.
6. **Are the 2026-10-07 "verified"/"amended" dates in `AGENTS.md:79` and
   `constitution.md:10,141` still accurate at the 1.10.0 cut?** Unverifiable without git (section 4,
   V4).
7. **Does the public wiki exist at all?** If `has_wiki=false` as the brief states, the whole manual
   copy procedure in `docs/contributing/documentation.md:27-32` needs revisiting, not just re-running.

---

## 8. Ranked discrepancy list (for the release decision)

| # | Severity | Finding | Anchor |
|---|---|---|---|
| D1 | High | README/wiki still describe per-file JSON persistence under `data/`; the SQLite change is absent from every admin-facing doc | `README.md:262-291`, `Documents.java:45-51`, `EconomyDatabase.java:40,49-73`, `EconomyPaths.java:40-56` |
| D2 | High | `villagers.db` documented as a live file; it is consolidated into `economycraft.db` | `README.md:291`, `EconomyCraft.java:137-139`, `EconomyDatabase.java:324-366` |
| D3 | High | No wiki sync mechanism; 1.10.0 leaves the hosted wiki stale (and `Home.md` is API-only) | `docs/contributing/documentation.md:27-32`, `.github/workflows/release.yml`, `wiki/Home.md:1` |
| D4 | Medium | `max_contract_reward` clamp range documented as 1-99,999,999; code clamps to 1-999,999,999,999 | `README.md:394`, `EconomyConfig.java:283-288`, `EconomyManager.java:158` |
| D5 | Medium | README `/eco import` subset omits `economycraft.db` | `README.md:295-297`, `EconomyPaths.java:51-55` |
| D6 | Medium | `shop.json` documented as migrating into `deliveries.json`; code feeds `auctions.json` | `README.md:280`, `EconomyDatabase.java:45-47,295-298` |
| D7 | Medium | README `/eco import` registration condition wrong (selection gate, not `standalone_commands`) | `README.md:209`, `EconomyCommands.java:211-214` |
| D8 | Medium | 8 shipped `quests.*` config keys undocumented despite "every shipped key" claim | `config.json:230-239`, `README.md:307-308,630-647` |
| D9 | Medium | `gemini_gossip.dialogue_system_instruction` code fallback differs from bundled default and README | `GossipConfig.java:44-58,234`, `config.json:253`, `README.md:376,663` |
| D10 | Medium | Constitution governance row VI cites `AsyncFileWriter` as the enforcement of the SQLite principle | `constitution.md:133`, `Documents.java:44-51` |
| D11 | Medium | Fabric-only mixin list in the contributor reference omits 3 mixins | `docs/contributing/architecture.md:12-15`, `AGENTS.md:37-38`, fabric/neoforge mixins JSON |
| D12 | Medium | "`/contracts actions` undocumented; `/eco gossip memory inspect` and `memory clear` missing from README" | `EconomyCommands.java:1170-1173,1742-1756`, `wiki/Server-Administration.md:42-43` |
| D13 | Low | `wiki/Tolls.md:14` cites a `tolls.json` store | `wiki/Tolls.md:14`, `TollManager.java:254` |
| D14 | Low | README omits required Fabric API dependency and minimum Fabric loader | `README.md:4`, `fabric/src/main/resources/fabric.mod.json:20-25` |
| D15 | Low | Constitution line-number citations drifted (`:502`, `:81`, `:49`) | `constitution.md:33,42,54` vs `EconomyManager.java:539,544,82` |
| D16 | Low | README contract headless syntax imprecise (reasons/notes) | `README.md:78`, `EconomyCommands.java:1183-1205` |
| D17 | Low | README duplicates the whole config table; copies already diverge | `README.md:315-494` vs `:497-690` |
| D18 | Low | No version/badge/download/changelog surface anywhere in user docs | grep over `README.md wiki/ docs/ AGENTS.md` |
