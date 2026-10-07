# Documentation Drift Inventory — EconomyCraft

**Produced**: 2026-10-07, as an input to `../spec.md` (Refresh Every Project Document)
**Method**: read-only static audit. Every finding cites `file:line`. Claims marked **uncertain** were not
verified by execution. No runtime behavior was changed.

Repo: `/home/yes/projects/EconomyCraft`. `mod_version 1.10.0`, `minecraft_version 26.3`
(`gradle.properties:6,11`).

---

## 1. Ground truth (what actually ships)

### 1a. Registered commands

`common/src/main/java/com/reazip/economycraft/EconomyCommands.java:70-147` (`register`), `:162-215` (`buildRoot`).

`/eco` subcommands actually registered (`EconomyCommands.java:172-212`):

| Literal | Line |
|---|---|
| `eco` (bare, opens hub) | 172 |
| `menu` | 173 |
| `admin` | 176 |
| `bal` | 179 |
| `pay` | 180 |
| `sell` | 182 |
| `ah` | 183 |
| `auction` | 184 |
| `offers` (+ `offers ah <id>`, `offers order <id>`) | 185, 1089-1096 |
| `shop` | 186 |
| `orders` (+ `request`, `search`, `list`) | 187, 801/833/987/1007 |
| `deliveries` | 188 |
| `daily` | 189 |
| `transactions` | 190 |
| `toll` (+ `create`/`set`/`transfer`/`info`/`remove`) | 191, 217-228 |
| `tag` (+ `tag <player>`) | 192, 1190-1219 |
| `job` (+ `job leave`, `job <profession>`) | 193, 1222-1254 |
| `party` (+ `party leave`, `party <faction>`) | 194-195, 1268-1314 |
| `worth` (+ `worth <item> [amount]`) | 196, `WorthCommand.java:26-34` |
| `gossip` (+ `status`/`refresh`/`test`/`dialogue`/`reload`) | 198, 1369-1398 |
| `motd` | 199 |
| `reload` | 200 |
| `addmoney`, `setmoney`, `removemoney`, `removeplayer` | 203-206 |
| `import` (only when `selection != DEDICATED`) | 208-212 |

Standalone (non-`/eco`) registrations (`:81-146`): `bal`, `pay`, `sell`, `ah`, `auction`, `offers`, `shop`,
`orders`, `deliveries`, `daily`, `transactions`, `toll`, `tag`, `job`, `party`, `worth`; standalone admin
`addmoney`/`setmoney`/`removemoney`/`removeplayer` and `gossip` (gated on `standalone_admin_commands`).

### 1b. `/eco` hub buttons

`HubUi.java:189-288`: Shop (190), Auction House (196), Sell Items (202), Orders (208), Daily Reward (215),
Pay a Player (226), Leaderboards (231), Item Value (236), Transactions (242), **Tags** (247), Deliveries
(252), Price offers (261), Tolls (272), **How It Works** (277), Admin (285), plus a balance/sell-limit
info item (178-187).

### 1c. Admin menu and reset tools

`admin/AdminUi.java:62-98`: Shop (63), Settings (70), Players (76), Reload from disk (81),
**Server Quests** (87), Reset Tools (93), Main menu (98).

`admin/AdminResetUi.java:73-105`: Reset All Balances, Reset Daily Reward Data, Reset Daily Sell Limits,
Clear Auctions, Clear Orders, Reset Entire Economy, **Run Wealth Tax Now** (96),
**Run Faction Daily Tax** (101).

### 1d. Permission nodes

`util/EconomyPermissions.java:12-15` (6 admin nodes) and `:23-29` (14 command nodes):
`economycraft.command.{menu,balance,pay,shop,auction,sell,orders,deliveries,daily,transactions,worth,toll,tag,offers}`.

### 1e. Public API surface `com.reazip.economycraft.api.v1`

18 source files under `api/`. `EconomyCraftApi.java:11-52` exposes `balances()`, `prices()`, `leaderboard()`,
`balanceEvents()`, **`factions()`** (23), `formatMoney(long)`, **`inflationMultiplier()`** (45),
**`medianActiveBalance()`** (52). Plus `FactionApi` (5 methods) and `FactionIds` (5 constants).

### 1f. Config sections

`common/src/main/resources/assets/economycraft/config.json`: top-level scalars `:2-35`; `factions` `:36-83`;
`professions` `:84-208`; **`quests` `:209-226`**; **`gemini_gossip` `:227-242`**; **`motd` `:243-252`**.
`EconomyConfig.java:116-129` confirms all four nested sections parse. `max_active_tolls_per_player = 10`
(`config.json:21`, `EconomyConfig.java:61-62`).

### 1g. Placeholders

`fabric/.../EconomyCraftFabricPlaceholders.java:14-56` registers exactly 8: `balance`,
`balance_formatted`, `balance_short`, `daily_sell_remaining`, `top_name`, `top_balance`,
`top_balance_formatted`, `top_balance_short`. Mirrored in `neoforge/.../EconomyCraftNeoForgeTab.java:22-63`.

### 1h. Leaderboard categories

`LeaderboardCategory.java:4-9`: Top Balances, Top Earners, Top Spenders, Top Sellers, Top Buyers, Top Traders.

### 1i. Runtime data files

`util/EconomyPaths.java:40-51` (importable set) plus writers: `balances.json`, `daily.json`,
`daily_sells.json`, `stats.json`, `player_names.json`, `notifications.json`, `player_activity.json`,
`deliveries.json`, `auctions.json`, `orders.json`, `online_time.json`, `cooldowns.json`, `parties.json`,
`professions.json`, `tolls.json`, `negotiations.json`, `quests.json`, `stock.json`, `fiscal.json`,
`faction_fiscal.json`, `villagers.db` (`EconomyCraft.java:167`) — ~21 files.

---

## 2. Documented features with no code support

| Doc claim | Evidence |
|---|---|
| README:208-211 "Phase 2 shipped them as data only … nothing consumes it yet" | False. `faction/FactionEffects.java`, `faction/FactionLevyService.java`, `faction/FactionFiscalPass.java`, `tax/FactionTaxRules.java`, `profession/*Effects.java` all read these keys. |
| README:286-288 orphan paragraph "`PRIVATE`, and holding the Communism buff is what admits the party (`PARTY_ONLY`) … Not enforced yet — that is Phase 10." | Orphan fragment (starts mid-sentence at 286); the container-lock subsystem was **removed** (`CHANGELOG.md:62-82`). Self-contradictory. |
| README:306 "These keys are not yet editable from `/eco settings`" | Accurate (`admin/AdminSettingsUi.java:71-111` lists no faction/profession key) but **contradicts README:97** "Every option in `config.json`, editable in-game". |
| README:468 "See the [Developer API wiki](https://github.com/PhilipB06/EconomyCraft/wiki)" | Points at the **upstream** repo, not this fork's `wiki/`. |
| CHANGELOG:86-87 "The baseline is now 313 passing tests" | Actual `@Test` count: 408 in `common/src/test` + 117 in `common/src/test26_3` = **525**. Entry predates the gossip/quest suites (26 test classes exist, e.g. `gossip/ChallengerStressTest.java`, `quests/QuestLogicTest.java`). |
| `wiki/Factions.md:13` lists Communism buff **`Tài trợ`** (subsidy) as shipped | `TODO.md:1085` and `:1115` mark spec line 11 / `Tài trợ` as "**§9 — read-only, not implemented**". **Documented as existing; does not exist.** |
| TODO.md:3 "Status: Complete (Phases 0–11 all complete)" | True, but the file is a completed implementation plan, not a backlog (`TODO.md:1063-1070` marks Phase 11 docs DONE). |

---

## 3. Shipped features with no documentation

| Feature | Code evidence | Doc status |
|---|---|---|
| **`quests` config section** (11 keys) | `config.json:209-226`, `EconomyConfig.java:116-117`, `config/QuestsSection.java`, `quests/QuestManager.java`, `QuestLogic.java`, `QuestStock.java`, `QuestBuyback.java` | Absent from README. Only tangential: README:33. |
| **`Server Quests` admin screen** | `admin/AdminUi.java:87`, `admin/AdminQuestsUi.java` | Absent from README admin section. |
| **`gemini_gossip` config section** (14 keys incl. `api_key`, `model: gemini-3.8-flash`, `base_url`) | `config.json:227-242`, `EconomyConfig.java:122-123`, `gossip/GossipConfig.java:21-78` | Only README:6 one-clause mention. No key table, no command doc. |
| **`/eco gossip` command family** | `EconomyCommands.java:1369-1398`, registered `:141-145`, `:198` | Undocumented. `grep gossip` in CHANGELOG/TODO returns no match. |
| **`motd` config section** + `/eco motd` | `config.json:243-252`, `EconomyConfig.java:128-129`, `motd/MotdService.java`, `MotdFormatter.java`, `EconomyCommands.java:199` | Undocumented. |
| **`max_active_tolls_per_player`** (default 10) | `config.json:21`, `EconomyConfig.java:61-62`, `TollUi.java:122,173`, `EconomyCommands.java:248,301` | Named in `wiki/Tolls.md:13` **without its default**; absent from README's `config.json` table (README:170-204). |
| **Listing / request notes** (colour-coded free text) | `auction/AuctionUi.java:113-115,851-863`, `orders/OrdersUi.java:110-113,781-841` | Undocumented. |
| **`Tags` button** in `/eco` hub | `HubUi.java:246-249` | README hub table (README:12-25) omits it. |
| **`How It Works` help button** | `HubUi.java:277-280` | Undocumented. |
| **`Run Faction Daily Tax` reset tool** | `admin/AdminResetUi.java:101` | README Reset Tools table (README:106-114) lists 7 of 8 buttons. |
| **`economycraft.command.tag`** permission node | `EconomyPermissions.java:28,42` | README command-node table (README:141-155) lists 13 of 14. It gates `/eco tag`, `/eco job`, `/eco party`, the Tags button and `/tag`. |
| **`inflationMultiplier()` / `medianActiveBalance()`** | `EconomyCraftApi.java:45,52` | Absent from `wiki/API-Reference.md:15-25` and `wiki/Home.md:13-24`. |
| **`FactionApi` / `FactionIds`** | `api/.../FactionApi.java` (5 methods), `FactionIds.java` (5 constants), wired `EconomyCraftApi.java:23` | Absent from **all 11 wiki pages**. Only mention anywhere is `CHANGELOG:205`. |
| **`/eco import`** | `EconomyCommands.java:208-212,315-340` | README:161-166 mentions the folders and import exclusion but never the command. |
| **`/eco reload`** | `EconomyCommands.java:200-201` | Only indirectly as the admin "Reload from disk" button (README:136). |
| **~17 of ~21 runtime data files** | see §1i | README:163-166 names only four, and describes them as the Phase 2 additions. |
| **`/worth <item> [amount]`** | `WorthCommand.java:29-33` | README:27 lists `/worth` with no arguments. |
| **`/tag`, `/job`, `/party`, `/toll`** | `EconomyCommands.java:191-194` | README:27 command list omits all four. |

---

## 4. Defects inside the documents

### 4.1 Broken / wrong internal links

| Location | Problem |
|---|---|
| `README.md:25` | `[toll management](wiki/Tolls.md)` — a wiki page is not reachable via a repo-relative `.md` path; must be `https://github.com/TraiNguyenVan/EconomyCraft/wiki/Tolls`. |
| `README.md:468` | Links `https://github.com/PhilipB06/EconomyCraft/wiki` — the **upstream** repo. Must be `https://github.com/TraiNguyenVan/EconomyCraft/wiki`. |
| `wiki/Home.md:30-34` | All five "Documentation" links point at `github.com/PhilipB06/EconomyCraft/wiki/...` — upstream. `wiki/_Sidebar.md:10-15` uses correct relative links for the same targets, so `Home.md` contradicts the sidebar. |
| `README.md:12` | Table header `Buttom` (typo for "Button"); column padded to 18 chars while the divider row is 2 chars longer, so the table renders misaligned. |

### 4.1a Canonical repository identity (resolved)

`git remote -v` settles this unambiguously:

| Remote | URL | Role |
|---|---|---|
| `origin` | `https://github.com/TraiNguyenVan/EconomyCraft.git` | **this fork** |
| `upstream` | `https://github.com/PhilipB06/EconomyCraft.git` | the original by PhilipB06 (ReaZip) |

Therefore there are exactly **two** identities, and the following are **correct** and must be preserved as-is:

- `README.md:6` — "enhanced fork of PhilipB06/EconomyCraft" ✔
- `README.md:475` — attribution to "EconomyCraft by PhilipB06 (ReaZip)" ✔
- `config.json:249` — MOTD default pointing at `TraiNguyenVan/EconomyCraft/issues` ✔

The **only** repository-identity bug is the two places that send readers to the *upstream wiki*
(`README.md:468`, `wiki/Home.md:30-34`) when the content they want lives in this fork's `wiki/`.

**Correction to an earlier report**: an earlier pass of this inventory flagged `config.json:249` as a
"third repository identity" inconsistent with the README. That was wrong. The MOTD default matches
`origin` exactly and is correct as shipped. The README's upstream wiki link is the defect, not the MOTD.
No configuration change is needed, and none should be made.

Canonical wiki base URL for all documentation links: `https://github.com/TraiNguyenVan/EconomyCraft/wiki`

Also established: `git tag` ends at `1.9.0` while `mod_version = 1.10.0`, confirming `1.10.0` is
unreleased and that the four `## Unreleased` sections in `CHANGELOG.md` are all accumulating toward it.
`.github/workflows/release.yml:201` sets `changelog-file: CHANGELOG.md`, so the shape of that file
directly determines what a published release's notes say.

### 4.2 Stale paths outside this repository

| Location | Quote |
|---|---|
| `TODO.md:4` | `**Spec:** `/home/capcap/Git/Vibe code plugin.md` (67 lines, Vietnamese) — the single source of truth for *what*.`` — absolute path on another machine, called "the single source of truth". |
| `TODO.md:1135` | `` `cd shopguard && ./gradlew test` green (35 tests + whatever P10 added). `` — sibling repo; its count (35) contradicts `TODO.md:1056` ("42 tests in `shopguard` pass"). |
| `CHANGELOG.md:79` | `data/container_locks.json` … "A copy of the live file … was kept on the server under `config/economycraft/data/.removed-20261003/`" — a live-server path outside the repo. |
| `config.json:249` (shipped default) | MOTD points at `https://github.com/TraiNguyenVan/EconomyCraft/issues` | **CORRECT.** Confirmed by `git remote -v`: `origin = https://github.com/TraiNguyenVan/EconomyCraft.git`, `upstream = https://github.com/PhilipB06/EconomyCraft.git`. See §4.1a — this was initially misreported as a third repository identity and is not a defect. |

### 4.3 Duplicated content inside `TODO.md`

`TODO.md:289-450` and `TODO.md:527-688` are **byte-identical** (verified by `diff`, exit 0) — ~162
duplicated lines covering Phase 0, Phase 1 and Phase 2. The two Phase 3 blocks differ (`453-525` vs
`691-737`) and **contradict each other** on P3-T1: line 467 says "**One** mixin is needed (tab list
only), not two" while line 697 still says "Two mixins, still no client mod"; line 729 names
`TabStyle#tabRow` where the class is `TagStyle` (`tag/TagStyle.java`).

`### Phase N` headings appear twice for phases 0-3 (`TODO.md:158,289,307,349,453,527,545,587,691`).

### 4.4 Stale counts

| Claim | Location | Actual |
|---|---|---|
| "313 passing tests" | `CHANGELOG.md:87` | **525** `@Test` methods (408 + 117) |
| "322 EconomyCraft tests and 42 ShopGuard tests" | `TODO.md:1069` | 525 |
| "45 new Phase 9 tests … 313 tests in `:common` overall" | `TODO.md:1029-1031` | 525 |
| "74 tests (was 61): `FiscalPolicyTest` 40, `TaxPolicyTest` 13, `TollUiTest` 21" | `TODO.md:344` | `TollUiTest.java` has **20** `@Test` methods |
| "74 tests" / "192 tests green" / "all 270 tests pass" | `TODO.md:344,361,855` | mutually inconsistent progression; all stale |
| "Only 2 classes: `fiscal/FiscalPolicyTest` … and `test26_3/TollUiTest`" | `TODO.md:72` | **48** test classes exist |
| "`api/v1`, 16 types" | `TODO.md:59` | 18 source files; `FactionApi`/`FactionIds` added since |
| "18 independent sites" for tax | `TODO.md:61` | corrected to 19 at `TODO.md:319`; §2 never updated |

### 4.5 References to removed symbols

| Location | Reference | Status |
|---|---|---|
| `TODO.md:106` (D10), `:1004-1025` (P9-T14), `:1084` (§8 row 10) | `ContainerLockMode`, `ContainerLockStore`, `ContainerLockPolicy`, `ContainerLockUi`, `ContainerLockSection`, `data/container_locks.json`, `/eco lock` | **Removed** per `CHANGELOG.md:62-82`. TODO keeps them as DONE. `README.md:286-288` keeps the orphaned half. |
| `README.md:286-288` | "`PRIVATE` … `PARTY_ONLY`" | Removed feature; paragraph has no preceding context. |
| `README.md:208-211` | "Phase 2 shipped them as data only" | Phases 3-10 shipped; stale. |
| `README.md:211` | "the effects arrive in later phases (`TODO.md` §7)" | §7 is entirely DONE (`TODO.md:1063-1070`); points at a plan with nothing outstanding. |
| `TODO.md:107` (D11), `:783` (P4-T5) | "the same range check P9-T14's container lock composes with" | P9-T14 removed. |
| `TODO.md:729` | `TabStyle#tabRow` | Class is `TagStyle`; no `TabStyle` exists. |
| `README.md:340` | Two consecutive `---` rules (338, 340) with nothing between | Formatting defect. |
| `README.md:230` | describes `capitalism.max_rate_change_per_day` as "D14's griefing brake" | Internal decision-ID leakage into a user-facing table. |

### 4.6 Contradictory values within `README.md`

`README.md` documents `factions`/`professions` **twice** — §"`factions` and `professions`" (206-284) and
§"Configuration reference" (398-428) — with different key names and defaults:

| Key | §206-284 | §398-428 | Actual (`config.json`) |
|---|---|---|---|
| `factions.capitalism.daily_tax_rate` | `0.05` (225, 408) | `0.05` (408) | **`0.025`** (`:56`) |
| `factions.monarchy.daily_tax_rate` | `0.017` (232) | `0.017` (410) | **`0.01`** (`:68`) |
| `factions.communism.income_tax_tier1_rate` | `0.005` (221) | — | **`0.0025`** (`:46`) |
| `factions.communism.income_tax_tier2_rate` | `0.0075` (222) | — | **`0.00375`** (`:48`) |
| `factions.communism.income_tax_tier3_rate` | `0.0125` (223) | — | **`0.00625`** (`:50`) |
| `factions.monarchy.own_claim_damage_multiplier` | — | camelCase `ownClaimDamageMultiplier` (412) | **`own_claim_damage_multiplier`** (`:73`) |
| `professions.farmer.crop_boost_cooldown_minutes` | `crop_boost_cooldown_minutes` (259) | `crop_boost_interval_minutes` (424) | **`crop_boost_cooldown_minutes`** (`:145`) |
| `professions.miner.lava_regeneration_*` | 3 keys (268) | `lava_regen_duration_seconds` (425) | **`lava_regeneration_level` / `lava_regeneration_seconds` / `lava_cooldown_minutes`** (`:181-183`) |
| `professions.merchant.cost_factor_master` | `cost_factor_apprentice`/`_master` (272) | `discount_master` (426) | **`cost_factor_apprentice`/`cost_factor_master`** (`:192-193`) |
| `professions.soldier.damage_taken_factor_master` | `0.85` (274) | `0.85` (427) | `0.85` (`:201`) ✔ |

Verified-correct claims worth preserving: `miner.double_value_ores` "5 ids" (README:265 vs `:163-169`);
`builder.building_blocks` "33 entries" (README:256 vs config).

### 4.7 Commands documented vs shipped

- README:27 lists `/bal`, `/pay`, `/daily`, `/shop`, `/ah`, `/auction`, `/sell`, `/worth`, `/orders`,
  `/deliveries`, `/transactions`, `/offers`. Missing though shipped: `/tag`, `/job`, `/party`, `/toll`.
- README:120 "Admin commands: `/eco addmoney`, `/eco setmoney`, `/eco removemoney`, `/eco removeplayer`."
  Missing: `/eco admin`, `/eco reload`, `/eco motd`, `/eco import`, `/eco gossip`, `/eco toll`, `/eco tag`,
  `/eco job`, `/eco party`, `/eco offers`, and the standalone `/addmoney` family.
- README:141-155 command-node table omits `economycraft.command.tag` (13 of 14 documented).

### 4.8 Wiki pages: language and content

| File | Lines | Language |
|---|---|---|
| `wiki/_Sidebar.md` | 15 | **Mixed** — section headings in English (1, 8); items 3-5 Vietnamese, item 6 English (`Tolls management`), items 10-15 English |
| `wiki/Chon-tag.md` | 24 | **Vietnamese** (100%) |
| `wiki/Factions.md` | 50 | **Vietnamese** (100%) — keeps English game terms inline (Communism, `/ah`, Toll, `PARTY_ONLY`, `/claim trust`) |
| `wiki/Professions.md` | 54 | **Vietnamese** (100%) — same pattern |
| `wiki/Tolls.md` | 57 | **English** (100%) |
| `wiki/Home.md` | 34 | **English** (100%) |
| `wiki/Getting-Started.md` | 85 | **English** (100%) |
| `wiki/Balances-and-Payments.md` | 120 | **English** (100%) |
| `wiki/Prices-and-Leaderboard.md` | 92 | **English** (100%) |
| `wiki/Balance-Events.md` | 91 | **English** (100%) |
| `wiki/API-Reference.md` | 238 | **English** (100%) |

Net: 3 Vietnamese player pages, 7 English integrator pages, 1 mixed sidebar.

**This split appears to be deliberate** — `TODO.md:119` (decision D18) specifies no code in player-facing
pages. Remaining friction: `Tolls.md` is player-facing English sitting in the Vietnamese Gameplay section;
the sidebar does not label either audience's language.

Other wiki defects:

| Location | Problem |
|---|---|
| `wiki/Tolls.md:13` | names `max_active_tolls_per_player` without its default (actual `10`). |
| `wiki/Tolls.md:36-48` | **developer** verification section (Gradle command, `TollUiTest` internals) inside a player-facing page — violates D18's own "no code" rule (`TODO.md:119`). |
| `wiki/Tolls.md:26` | "Installing the new jar requires a server restart" — internal dev phrasing in a player page. |
| `wiki/Professions.md:9,41`, `wiki/Chon-tag.md:9` | profession named **`Merchants`** (plural); the code enum is `MERCHANT` (`ProfessionId.values()`, `EconomyCommands.java:1239`) and README:396 says "Merchant". Inconsistent across three docs. |
| `wiki/Factions.md:3` | references `/eco party <tên_phe>` correctly, but there is no equivalent pointer from `wiki/Professions.md` to `/eco job`. |
| `wiki/Chon-tag.md:7` | says open the menu via `/tag` (or `/eco`) but omits `/eco party` and `/eco job`, both shipped (`EconomyCommands.java:193-195`). |

---

## 5. Uncertain / not verified

- `TODO.md:144` "`TollUiTest` (26.3, 488 lines)" — 20 `@Test` methods counted; the 488-line claim was not
  verified by execution.
- Whether the `shopguard` sibling repo still exists (`TODO.md:1056,1069,1135`) — it is not in this repo and
  no path is given.
- `config.json` `gemini_gossip.model = "gemini-3.8-flash"` — a model identifier that could not be confirmed
  to exist. Documentation can record the value; whether the value is correct is a code question, not a doc one.
- No test suite was executed and no server was started. Every finding above is static.