# Baseline — pre-change state

**Captured**: 2026-10-07, before any document was modified.
**Purpose**: Prove the end state is better than the measured start state, per `tasks.md` T001.
**Method**: static analysis against the shipped code. No document was edited to produce this.

Reproduce with `quickstart.md` V1-V9. Note two defects in those checkers, recorded in §3.

---

## 1. Measured results

| Check | Metric | Value |
|---|---|---|
| V1 | Shipped configuration leaf keys | **164** |
| V2 | Wrong defaults, checker as written | **1** reported — a false positive, and 5 real ones invisible (§3.1) |
| V2 | Wrong defaults, checker fixed (`scripts/check_config_keys.py`) | **7 rows, 5 distinct keys** |
| V3 | Phantom configuration keys (named in docs, do not ship) | **4** |
| V4 | Broken links, checker as written | **16** reported — 10 are false positives (§3.2) |
| V4 | Broken links, checker fixed (`scripts/check_links.py`) | **6**, all upstream-wiki URLs; **0** missing targets |
| V5 | Paths pointing outside the repository | **0** in `README.md` + `wiki/`; **2** in `TODO.md` |
| V6 | Shipped configuration keys undocumented | **130** by the quickstart count; **63** by the fixed checker (§4) |
| V6 | `## Unreleased` headings in `CHANGELOG.md` | **4** |
| V6 | Constitution placeholder tokens | **6** |
| V6 | `TODO.md` present | **yes** |

Where the two checkers disagree, the fixed one is correct. Both disagreements are explained below.

### Undocumented configuration keys by section

| Section | Undocumented |
|---|---|
| `professions` | 61 |
| `factions` | 38 |
| `gemini_gossip` | 14 |
| `quests` | 14 |
| `motd` | 3 |
| **total** | **130** of 164 |

### Phantom keys (V3)

All four in `README.md`:

| Documented | Actually ships |
|---|---|
| `ownClaimDamageMultiplier` | `own_claim_damage_multiplier` |
| `crop_boost_interval_minutes` | `crop_boost_cooldown_minutes` |
| `lava_regen_duration_seconds` | `lava_regeneration_seconds` |
| `discount_master` | `cost_factor_master` |

### Real broken links (V4, after removing false positives)

| Location | Problem |
|---|---|
| `README.md:468` | upstream wiki URL |
| `wiki/Home.md:30-34` | 5 upstream wiki URLs |

Plus one the checker **cannot** detect by file existence, because the target file does exist:
`README.md:25` links `wiki/Tolls.md` as a repo-relative path. A GitHub wiki page is not reachable that way.

**Real total: 7.**

### Constitution placeholders

`PROJECT_NAME`, `CONSTITUTION_VERSION`, `RATIFICATION_DATE`, `LAST_AMENDED_DATE`, `GOVERNANCE_RULES`,
`GUIDANCE_FILE` — 6 tokens, 0 principles.

### Paths outside the repository (V5)

| Location | Content |
|---|---|
| `TODO.md:4` | `` `/home/capcap/Git/Vibe code plugin.md` `` — called "the single source of truth" |
| `TODO.md:1135` | `cd shopguard && ./gradlew test` — sibling repository |

Both are in `TODO.md` and resolve when that file is deleted in T050.

---

## 2. Targets

| Check | From | To |
|---|---|---|
| Undocumented config keys | 130 | 0 |
| Phantom keys | 4 | 0 |
| Real broken links | 7 | 0 |
| `## Unreleased` headings | 4 | 1 |
| Constitution placeholders | 6 | 0 |
| `TODO.md` present | yes | no |
| Out-of-repo paths | 2 | 0 |

---

## 3. Defects found in the checkers themselves

Both were found while running the baseline. **Neither is a documentation defect** — they are gaps in
`quickstart.md` that `tasks.md` T006 and T007 must close before any correction is verified.

### 3.1 V2 cannot see section-relative configuration keys

**Symptom**: V2 reports only 1 wrong default, and that one is a false positive. The 5 genuinely wrong
defaults in `drift-inventory.md` are invisible.

**Cause**: two separate problems.

**(a) The key is documented without its section prefix.** `README.md` documents faction keys under a section
heading, so the documented token is `capitalism.daily_tax_rate`, while the shipped path is
`factions.capitalism.daily_tax_rate`. The checker looks the documented token up in the flat key set, does not
find it, and silently skips it.

Affected: every key in the `factions` and `professions` sections — 99 of the 164 keys.

Observed, at four sites:

| Location | Documented | Ships |
|---|---|---|
| `README.md:225`, `README.md:408` | `capitalism.daily_tax_rate` = `0.05` | `0.025` |
| `README.md:232`, `README.md:410` | `monarchy.daily_tax_rate` = `0.017` | `0.01` |

This also explains why the two duplicate faction tables agree with each other while both disagree with the
code: the checker never compared either against anything.

**(b) String comparison does not strip quotes uniformly.** `README.md` documents `balance_separator` as
`` `"."` `` — literally, with quotes, which is correct for a string value. The checker strips quotes from the
shipped side but not the documented side, so it reports a false mismatch.

**Fix required in T006**: resolve a documented token against the flat key set by also trying each section
prefix; and normalise both sides before comparing.

### 3.2 V4 treats correct wiki links as broken

**Symptom**: V4 reports 16 broken links. Ten of them are `_Sidebar.md`'s links to `Chon-tag`, `Factions`,
`Professions`, `Tolls`, `Home`, `Getting-Started`, `Balances-and-Payments`, `Prices-and-Leaderboard`,
`Balance-Events`, `API-Reference`.

**Cause**: the checker resolves a relative link as `base / link` and tests for existence. The target files
are `wiki/Chon-tag.md` and so on — they exist, but with a `.md` extension the link omits.

This is **correct as written** for a GitHub wiki, where page links carry no extension. The checker is wrong,
not the links. Ten false positives out of sixteen would send an implementer to "fix" ten links that are
already right.

**Fix required in T007**: when a relative link does not resolve, retry with `.md` appended before reporting a
failure.

---

## 4. Checker disagreements, resolved in Phase 2

Both fixed checkers were run against the unmodified documents. Where they disagree with §1, the fixed
checker is right.

### 4.1 Undocumented keys: 130 or 63?

**63.** The quickstart count of 130 was inflated because its dotted-name regex never matched a
section-relative token such as `capitalism.daily_tax_rate`, so those keys were counted as undocumented even
when the page named them. The fixed checker resolves them.

The 63 split by cause:

| Kind | Count | Meaning |
|---|---|---|
| Described in prose, no exact key name | 26 | `color`, `icon`, `enabled`, `selection_lockout_hours` are discussed without their full path. `README.md:242,280,355` |
| Absent from every document | 37 | genuinely missing |
| **total** | **63** | |

The 37 absent keys, by section: `gemini_gossip` 13, `quests` 12, `professions` 9, `motd` 2, `factions` 1.

The 26 are a naming defect rather than an omission — contract rule R2 requires the exact shipped key, so
they still need fixing, but they are not "undocumented" in the sense of being unknown to the reader.

### 4.2 Wrong defaults: 5 or 7?

**5 distinct keys, across 7 table rows.** `factions.capitalism.daily_tax_rate` and
`factions.monarchy.daily_tax_rate` each appear in both duplicated faction tables
(`README.md:225`/`408` and `:232`/`410`), so fixing the duplication in T012 removes the duplicate rows too.

The fixed checker also surfaced two name defects the value check alone would not have:

| Documented | Ships | Rows |
|---|---|---|
| `communism.income_tax_tier1_rate` | `factions.communism.income_tax_tier1_rate` | `README.md:221` |
| `communism.income_tax_tier2_rate` | `factions.communism.income_tax_tier2_rate` | `README.md:222` |
| `communism.income_tax_tier3_rate` | `factions.communism.income_tax_tier3_rate` | `README.md:223` |

These three were invisible to the quickstart check because they are written as a paired cell,
`` `communism.income_tax_tier1_threshold` / `_rate` ``, which a single key/value regex does not match.

### 4.3 Two remaining false positives avoided

Both were caught by inspection and are recorded so they are not "fixed" later:

- `professions.miner.ore_tags` — documented `["#minecraft:ores"]`, ships the same list. Comparing as text
  reported a mismatch because `str(list)` renders single quotes. Values are now compared as JSON.
- `webhook_url`, `unit_buy` and 8 similar names — real keys, but they live in `webhook.json` and
  `prices.json`, not `config.json`. The checker now reads those two as secondary authorities, since
  `README.md:311-313` documents `webhook.json` keys under the same heading.

One token remains flagged for review rather than asserted wrong: `haste_trigger_blocks` at
`README.md:296`, mentioned in prose with no section prefix. It ships under both `professions.builder` and
`professions.miner`, so the reference is imprecise but not false.

### 4.4 Both checkers were tested against injected defects

A checker that only reproduces today's known errors proves nothing. Each was run against a deliberately
broken copy of `README.md` (restored afterwards, `git diff` clean):

| Injected | Detected |
|---|---|
| `startingBalance` documented as `2000`, ships `1000` | yes — and this is the camelCase top-level key the original regex could never match |
| link to a non-existent `wiki/NoSuchPage.md` | yes, as `TARGET` |
| upstream wiki URL replaced with the correct one | correctly no longer reported |

---

## 5. Owner decisions recorded in Phase 2

Both judgement calls in `contracts/wiki-page-contract.md` §4 were resolved. See that file for the decisions
and the reasoning; §4.1's `Tài trợ` handling is a deliberate, recorded exception to FR-006.

---

## 6. `AGENTS.md` re-verification (T010)

Every claim in `AGENTS.md` §1-§8 was re-checked against the code. Six were wrong in the file as first
written and are now corrected:

| Claim | Was | Correct |
|---|---|---|
| Mixin asymmetry | Fabric-only, one direction | Two-directional: 3 toll mixins on Fabric, `ProfessionBreakMixin` on NeoForge. 9 of 12/10 are shared |
| API thread contract | `EconomyCraftApiImpl` | no such class; it is `EconomyCraftApiBootstrap`, and it lives in `common/`, not `api/` |
| ShopGuard | "EconomyCraft references ShopGuard nowhere" | it does, reflectively, via `ClaimBridge` + `ReflectiveShopGuardBackend`; there is no `ClaimEconomy.Backend` here |
| `online_time.json` | "it does [exist]" | it exists but is deliberately excluded from `DATA_FILES`; the trap was about the exclusion |
| Client class path | `fabric/.../client/` | the package is `...economycraft.fabric.client` |
| Fabric/NeoForge mixin count difference | implied 12-10 = 2 | it is 4, because 9 are shared |

Confirmed correct and unchanged: 164 config keys with the 34/61/38/14/14/3 section split, 18 `api/v1` types,
6 admin and 14 command permission nodes, the Java 21/25 matrix, `TagStyle` not `TabStyle`,
`EconomyManager` at the package root, `FISCAL_SOURCES` at line 81, GPL attribution at `README.md:6` and `:472`.

---

## 4. Consequence for later phases

- **T012-T029 (US1) cannot be verified until T006 is fixed.** V2 currently cannot detect a wrong default in
  any faction or profession key, which is where five of the known errors live. Correcting them without a
  working checker means no confirmation they are right.
- **T030's acceptance criterion** — "0 undocumented keys, 0 value errors" — is meaningless until V2 resolves
  section-relative keys. As written it would pass today while four wrong defaults remained.
- **T042's link check** would report ten false failures until T007 appends `.md`.