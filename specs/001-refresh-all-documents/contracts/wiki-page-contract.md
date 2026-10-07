# Contract: Wiki Page

**Feature**: `001-refresh-all-documents` | **Applies to**: all 11 files under `wiki/`
**Requirements**: FR-002, FR-005, FR-006, FR-008, FR-017 | **Criteria**: SC-002, SC-003, SC-011

---

## 1. The contract

Every page MUST satisfy all of the following.

### R1 — Audience determines language
A page written for players is written in **Vietnamese**. A page written for integrators is written in
**English**. Each page is written in exactly one language. This split is existing project intent (decision
D18), not drift (FR-017, research.md R6).

### R2 — Player pages carry no code
A player-facing page MUST NOT contain code blocks, Gradle commands, test-class names or internal class
identifiers. This is D18's own rule. It is currently violated by one page — see §4.

### R3 — No development-process language
No phase numbers, task identifiers, or plan references. Also no release-process language: `wiki/Tolls.md:26`
tells the reader that "Installing the new jar requires a server restart", which is maintainer phrasing in a
player page.

### R4 — Only claims the code supports
Every stated effect, buff, reward, limit or command MUST be verifiable in the codebase. This catches facts
documented as shipped that are not implemented — see §4.

### R5 — Cross-references resolve
Every link resolves, and internal links are **relative** (`Factions`, not an absolute URL). The
`wiki/`-internal relative style is already correct in `_Sidebar.md` and MUST NOT be converted to absolute
URLs.

---

## 2. Page register

### Section 1 — Gameplay (player audience → Vietnamese)

| Page | Language | Defects to fix |
|---|---|---|
| `Chon-tag.md` | vi ✔ | Omits `/eco party` and `/eco job`, both shipped (`EconomyCommands.java:193-195`). Profession named "Merchants" (plural); code enum is `MERCHANT` |
| `Factions.md` | vi ✔ | **Advertises a buff that does not exist** — see §4. Has `/eco party` but no pointer from the professions page to `/eco job` |
| `Professions.md` | vi ✔ | "Merchants" → "Merchant"; no pointer to `/eco job` |
| `Tolls.md` | **en** ✘ | **Wrong language for its section.** Contains a developer verification section (§4). `max_active_tolls_per_player` named without its default |

### Section 2 — EconomyCraft API v1 (integrator audience → English)

| Page | Language | Defects to fix |
|---|---|---|
| `Home.md` | en ✔ | **All 5 documentation links point at the upstream repo** (`PhilipB06/EconomyCraft/wiki/...`). Convert to relative, matching `_Sidebar.md` |
| `Getting-Started.md` | en ✔ | No defects found |
| `Balances-and-Payments.md` | en ✔ | No defects found |
| `Prices-and-Leaderboard.md` | en ✔ | No defects found |
| `Balance-Events.md` | en ✔ | No defects found |
| `API-Reference.md` | en ✔ | **Omits 4 public API members** — see §3 |
| `_Sidebar.md` | mixed | **Must state the language policy** (R6). Section headings English, item 6 English among Vietnamese items — consistent once `Tolls.md` is corrected |

**Six of eight API pages are already correct.** The API documentation is in better shape than the README —
this feature should not churn it.

---

## 3. Missing public API surface — `API-Reference.md` and `Home.md`

`EconomyCraftApi.java:11-52` exposes eight members. Four are absent from the wiki.

| Member | Line | Status |
|---|---|---|
| `balances()` | 11-52 | ✔ documented |
| `prices()` | 11-52 | ✔ documented |
| `leaderboard()` | 11-52 | ✔ documented |
| `balanceEvents()` | 11-52 | ✔ documented |
| `formatMoney(long)` | 11-52 | ✔ documented |
| **`factions()`** | **23** | ✘ absent — returns `FactionApi` |
| **`inflationMultiplier()`** | **45** | ✘ absent |
| **`medianActiveBalance()`** | **52** | ✘ absent |

**`FactionApi` and `FactionIds` appear in zero of the 11 wiki pages.** `FactionIds` alone has 5 constants
and `FactionApi` has 5 methods. `CHANGELOG.md:205` is the only mention of them anywhere in the documentation.

Note: `TODO.md` records that `inflationMultiplier()` and `medianActiveBalance()` were added as a precedent
for extending the API additively. Since the plan document is being deleted, that rationale migrates to the
constitution (research.md R4) rather than into the wiki.

**R6 — API completeness**: every type in the `api/v1` package that is public MUST appear in
`API-Reference.md`. Implementation and provider classes are internal and MUST NOT appear.

---

## 4. Content defects requiring judgement

### 4.1 A buff that does not exist — `Factions.md:13`

`Factions.md:13` lists `Tài trợ` (subsidy) as a shipped Communism buff. The project's own record marks spec
line 11 / `Tài trợ` as **"§9 — read-only, not implemented"**.

Verified in Phase 2: `factions.communism` in `config.json` ships `color`, `icon`, `party_fee`, three
`income_tax_tier*` pairs and `toll_tax_exempt_chance`. Nothing corresponds to `Tài trợ`. The buff that *does*
ship, `toll_tax_exempt_chance = 0.5`, is `Đầu tư công`, which the page already documents correctly.

**DECIDED (owner, Phase 2)**: keep the `Tài trợ` line and mark it **`chưa triển khai`** — not implemented —
rather than deleting it.

This is a deliberate exception to FR-006's "never document a key that does not ship". Recorded so it reads as
a decision, not a slip. The page must present the buff as absent, and must not imply a config key or effect
exists for it.

### 4.2 A developer section inside a player page — `Tolls.md:36-48`

Contains a Gradle invocation and `TollUiTest` internals. Violates R2 and D18's own rule. MUST be removed from
the player page.

**DECIDED (owner, Phase 2)**: move this content to **`AGENTS.md`**, the contributor-facing operational
document. It is genuine verification guidance and must not be deleted; it is simply not player help. The
`Tolls.md` player page keeps the behavioural content above it (block-pair resolution, the hopper restriction)
and loses the build tooling.

### 4.3 Naming inconsistency across three documents

`Professions.md:9,41` and `Chon-tag.md:9` say **"Merchants"** (plural). `ProfessionId.values()` and
`EconomyCommands.java:1239` use **`MERCHANT`** (singular), and `README.md:396` says "Merchant".

**R7**: use the singular form matching the shipped enum. Decide once and apply consistently.

### 4.4 Missing command pointers

| From | Must point to |
|---|---|
| `Chon-tag.md:7` | `/eco party` and `/eco job`, not just `/tag` |
| `Professions.md` | `/eco job` (currently no pointer at all) |
| `Factions.md:3` | ✔ already points to `/eco party <tên_phe>` |

---

## 5. Link contract

| Location | Current | Required |
|---|---|---|
| `README.md:25` | `wiki/Tolls.md` (repo-relative) | `https://github.com/TraiNguyenVan/EconomyCraft/wiki/Tolls` |
| `README.md:468` | `.../PhilipB06/EconomyCraft/wiki` | `https://github.com/TraiNguyenVan/EconomyCraft/wiki` |
| `wiki/Home.md:30-34` | absolute, upstream | relative, matching `_Sidebar.md` |
| `wiki/_Sidebar.md:10-15` | relative ✔ | **unchanged** — do not convert to absolute |

A GitHub wiki page is **not** reachable via a repository-relative `.md` path. That is why `README.md:25` is
broken and `_Sidebar.md`'s relative links only work *from inside the wiki*.

**Canonical base URL**: `https://github.com/TraiNguyenVan/EconomyCraft/wiki` (research.md R5).

**Must be preserved unchanged** — GPL-3.0 attribution obligations:
- `README.md:6` — "enhanced fork of PhilipB06/EconomyCraft"
- `README.md:475` — attribution to "EconomyCraft by PhilipB06 (ReaZip)"

---

## 6. Validation

| Rule | Test |
|---|---|
| R1 language matches audience | `player` pages 100% Vietnamese; `integrator` pages 100% English |
| R2 no code in player pages | no code fence or Gradle invocation in `Chon-tag`, `Factions`, `Professions`, `Tolls` |
| R3 no process language | grep for phase numbers, task IDs, "Phase N" |
| R4 every claim supported | spot-check each stated effect against the codebase |
| R5 all links resolve | link check per `quickstart.md` |
| R6 API complete | every public `api/v1` type appears in `API-Reference.md` |
| R7 naming consistent | one form of the profession name across all documents |