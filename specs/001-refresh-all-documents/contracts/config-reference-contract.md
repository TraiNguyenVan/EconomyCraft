# Contract: Configuration Reference

**Feature**: `001-refresh-all-documents` | **Applies to**: `README.md`, `wiki/Tolls.md`
**Authority**: `common/src/main/resources/assets/economycraft/config.json`
**Requirements**: FR-003, FR-004, FR-007, FR-012 | **Criteria**: SC-001

---

## 1. The contract

Any table, list or prose sentence that states a configuration key MUST satisfy all of the following.

### R1 — Key existence
The stated key MUST exist in the shipped configuration file. A key that does not ship MUST be removed, not
corrected into a guess.

### R2 — Exact key path
The key MUST be stated using its exact shipped path and spelling. The configuration file uses `snake_case`
throughout. Three wrong names currently exist in the README:

| Documented | Shipped |
|---|---|
| `ownClaimDamageMultiplier` | `own_claim_damage_multiplier` |
| `crop_boost_interval_minutes` | `crop_boost_cooldown_minutes` |
| `lava_regen_duration_seconds` | `lava_regeneration_seconds` |
| `discount_master` | `cost_factor_master` |

### R3 — Exact default
The stated default MUST equal the value in the shipped file. Seven rate defaults are currently wrong,
including two that change how much tax a player actually pays:

| Key | Documented | Shipped |
|---|---|---|
| `factions.capitalism.daily_tax_rate` | `0.05` | `0.025` |
| `factions.monarchy.daily_tax_rate` | `0.017` | `0.01` |
| `factions.communism.income_tax_tier1_rate` | `0.005` | `0.0025` |
| `factions.communism.income_tax_tier2_rate` | `0.0075` | `0.00375` |
| `factions.communism.income_tax_tier3_rate` | `0.0125` | `0.00625` |

### R4 — Single canonical table
A key's default MUST be stated in exactly one place. Any other mention MUST be a cross-reference, not a
restatement. This is the fix for the README documenting `factions` and `professions` twice — at lines 206-284
and again at 398-428 — with conflicting names and defaults in eight cases.

### R5 — Unit and meaning
Where the shipped value is a rate, multiplier or interval, the document MUST state the unit and the
direction of effect. `0.05` as a tax rate is "5%", and "multiplier 1.25" is "+25%". The README does this well
in places and inconsistently in others.

### R6 — Off-by-default features are labelled
A feature whose shipped default is `false` MUST be labelled as disabled by default. At minimum:
`dynamic_prices_enabled`, `wealth_tax_enabled`, `wealth_tax_rebate_enabled`, `standalone_admin_commands`.

### R7 — No implementation-process language
Descriptions MUST NOT reference phase numbers, task identifiers, or internal decision IDs. The README
currently describes `capitalism.max_rate_change_per_day` as "D14's griefing brake" — `D14` means nothing to a
reader and disappears when the plan document is deleted.

---

## 2. Missing-key inventory — all must be added

Three entire sections ship undocumented.

### `quests` — 11 keys, `config.json:209-226`

| Key | Shipped default |
|---|---|
| `enabled` | `true` |
| `period_days` | `7` |
| `weekly_budget` | `12000` |
| `price_factor` | `0.5` |
| `weekly_count` | `10` |
| `max_concurrent` | `10` |
| `min_quest_unit` | `3` |
| `max_quest_unit` | `100` |
| `sell_fallback_multiplier` | `3.3` |
| `blacklist` | `[]` |
| `require_shop_price` | `true` |
| `bot_name` | `"Server Quests"` |
| `buyback.enabled` | `true` |
| `buyback.price_factor` | `0.8` |

### `gemini_gossip` — 14 keys, `config.json:227-242`

| Key | Shipped default | Note |
|---|---|---|
| `enabled` | `true` | |
| `api_key` | `""` | Must be documented as requiring a value to function |
| `model` | `"gemini-3.8-flash"` | Document as shipped; existence unverified (KnownIssue) |
| `base_url` | `"https://generativelanguage.googleapis.com"` | |
| `refresh_interval_minutes` | `20` | |
| `cooldown_minutes` | `3` | |
| `anonymize_players` | `true` | Privacy-relevant — MUST be documented, not buried |
| `temperature` | `0.85` | |
| `public_chat` | `false` | Off by default |
| `public_chat_chance` | `0.25` | |
| `private_chat_chance` | `0.5` | |
| `pool_size_per_category` | `3` | |
| `system_instruction` | *(long string)* | Document its purpose, not its content |
| `dialogue_system_instruction` | *(long string)* | Same |

### `motd` — `config.json:243-252`

| Key | Shipped default |
|---|---|
| `enabled` | `true` |
| `delay_ticks` | `40` |
| `lines` | *(array of formatted strings)* |

The `lines` default references `https://github.com/TraiNguyenVan/EconomyCraft/issues` — **correct**, matching
`git remote -v` `origin`. Document as-is. Do not "correct" it to the upstream URL.

### Single top-level key missing

| Key | Shipped default | Where referenced |
|---|---|---|
| `max_active_tolls_per_player` | `10` | `config.json:21`; used by `TollUi.java:122,173` and `EconomyCommands.java:248,301`. Named in `wiki/Tolls.md:13` **without its default** |

---

## 3. Command and permission contract

### R8 — Command inventory
Every registered command MUST be documented. Currently missing: `/tag`, `/job`, `/party`, `/toll` from the
`README.md:27` list; and `/eco admin`, `/eco reload`, `/eco motd`, `/eco import`, `/eco gossip`, `/eco toll`,
`/eco tag`, `/eco job`, `/eco party`, `/eco offers`, plus the standalone admin family, from `README.md:120`.

Documented commands with argument arity that must be stated:
- `/worth <item> [amount]` (`WorthCommand.java:29-33`) — README:27 gives no arguments
- `/eco gossip` subcommands: `status`, `refresh`, `test`, `dialogue [prof]`, `reload`
- `/eco offers ah <id>` and `/eco offers order <id>` (already documented at README:39-41)

### R9 — Permission nodes
Every node in `util/EconomyPermissions.java:12-29` MUST appear in the command-node table. Currently 13 of 14
are documented; `economycraft.command.tag` is missing, and it gates `/eco tag`, `/eco job`, `/eco party`, the
hub Tags button and `/tag`.

### R10 — Document the two contradictory claims, or fix one
The README asserts both:
- `README.md:97` — "Every option in `config.json`, editable in-game"
- `README.md:306` — factions/professions keys "are not yet editable from `/eco settings`"

The second is correct (`admin/AdminSettingsUi.java:71-111` lists no faction or profession key). Only one may
stand.

---

## 4. Runtime data files

**R11**: The ~21 files the mod writes at runtime MUST be documented where data storage is described.
`README.md:163-166` names four and describes them as "Phase 2 additions" — which is development-process language
(FR-011) and incomplete.

Full set: `balances.json`, `daily.json`, `daily_sells.json`, `stats.json`, `player_names.json`,
`notifications.json`, `player_activity.json`, `deliveries.json`, `auctions.json`, `orders.json`,
`online_time.json`, `cooldowns.json`, `parties.json`, `professions.json`, `tolls.json`, `negotiations.json`,
`quests.json`, `stock.json`, `fiscal.json`, `faction_fiscal.json`, `villagers.db`.

`util/EconomyPaths.java:40-51` distinguishes the importable subset from the rest — preserve that distinction,
as the README already does correctly for the four it names.

---

## 5. Platform matrix

**R12**: The supported platform matrix MUST be stated. No document currently does, while `README.md:27`
instructs the reader to pick "the jar that matches your Minecraft version" without ever listing them.

| Target | Java | Loaders |
|---|---|---|
| 1.21.1 | 21 | Fabric, NeoForge (`legacy121` compat fork) |
| 1.21.11 | 21 | Fabric, NeoForge |
| 26.1.2 | 25 | Fabric, NeoForge |
| 26.2 | 25 | Fabric, NeoForge |
| 26.3 | 25 | Fabric, NeoForge — **default**, only target with its own test source set |

Authority: `build.gradle:30-36`; Java rule at `build.gradle:100` `(tgt.javaVersion ?: 25)`.

---

## 6. What must NOT be documented

| Item | Reason |
|---|---|
| A corrected config default written into `config.json` | FR-001. The code is the authority; if it is wrong, that is a separate change |
| A test count | research.md R2 — the count is per-target, so any single number is wrong for 4 of 5 targets. Name the source sets and the reproduction command instead |
| `gemini-3.8-flash` asserted to be valid | Unverified. Document the shipped value; do not vouch for it |
| Claims about ShopGuard's current behaviour | Not present in this repository. Document the dependency only |