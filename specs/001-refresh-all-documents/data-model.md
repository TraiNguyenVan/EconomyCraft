# Phase 1 Data Model: Refresh Every Project Document

**Feature**: `001-refresh-all-documents` | **Date**: 2026-10-07
**Spec**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md) | **Research**: [research.md](research.md)

This feature has no runtime data model — it changes no code and stores no new data. What follows models the
*documentation domain* instead: the things a documentation refresh creates, changes and retires, and the
relationships between them. It is what `/speckit.tasks` will decompose, and what the contracts in
[`contracts/`](contracts/) constrain.

---

## 1. Document

The unit of work.

| Field | Type | Description |
|---|---|---|
| `path` | path | Repository-relative path. Unique. Primary identifier. |
| `class` | enum | `entry-point` \| `change-log` \| `wiki-page` \| `wiki-index` \| `tooling-definition` \| `template` \| `governance` |
| `audience` | enum | `server-owner` \| `player` \| `integrator` \| `contributor` \| `tooling-author` |
| `language` | enum | `en` \| `vi` |
| `lifecycle` | enum | `current` \| `to-create` \| `to-delete` |
| `groundTruthRefs` | set of ref | Source-of-truth references this document must agree with |

### Classes and counts

| Class | Path(s) | Count |
|---|---|---|
| `entry-point` | `README.md` | 1 |
| `change-log` | `CHANGELOG.md` | 1 |
| `wiki-index` | `wiki/_Sidebar.md` | 1 |
| `wiki-page` | `wiki/` (remaining 10) | 10 |
| `governance` | `.specify/memory/constitution.md` | 1 — currently a blank template |
| `tooling-definition` | `.opencode/commands/*.md` | 11 |
| `template` | `.specify/templates/*.md` | 5 |
| *(to-delete)* | `TODO.md` | 1 |

**Total in scope: 31 documents**, of which 1 is created, 1 is deleted, 29 are modified.

### Validation rules

| Rule | Source | Test |
|---|---|---|
| A `wiki-page` has exactly one `language` | FR-017 | language matches audience: `player` → `vi`, `integrator` → `en` |
| A `wiki-page` for a `player` contains no code or build commands | FR-017, D18 | `Tolls.md:36-48` currently violates this |
| Every document's `path` resolves in the repository | FR-009 | `test -f` |
| Every `path` cited inside a document resolves in-repo | FR-009 | no path matches `^/` or names another machine |
| A `governance` document contains no `PLACEHOLDER` token | FR-015 | grep for `[...]` placeholder forms |

---

## 2. DocumentedFact

The atom of correctness. A single claim a document makes about the system.

| Field | Type | Description |
|---|---|---|
| `subject` | string | What the fact is about: a config key, command, permission node, API type, data file, platform, limit, behavior |
| `value` | string | The value the document asserts |
| `authority` | enum | `config-json` \| `command-registry` \| `permission-registry` \| `api-package` \| `build-config` |
| `citedBy` | set of path | Documents asserting this fact |
| `status` | enum | `correct` \| `wrong-value` \| `wrong-name` \| `nonexistent` \| `missing` \| `duplicate-conflict` |

### Relationships

```
Document ──asserts──▶ DocumentedFact
DocumentedFact ──verified-against──▶ SourceOfTruth
DocumentedFact.citedBy (cardinality > 1) ──requires──▶ exactly one agreed value
```

### The cardinality rule that drives FR-007

`README.md` documents the `factions` and `professions` configuration sections **twice** — at lines 206-284
and again at 398-428 — with conflicting key names and conflicting defaults in eight cases. Because a fact may
be cited by many documents but must have exactly one agreed value, the fix is to **collapse to a single
canonical table** and make any second mention a cross-reference rather than a restatement.

### Status distribution in the current repository

Measured against the shipped `config.json` and the code registries — not estimated.

| Status | Count | Representative |
|---|---|---|
| `wrong-value` | 5 | `capitalism.daily_tax_rate` documented `0.05`, ships `0.025` |
| `wrong-name` | 4 | `ownClaimDamageMultiplier` vs shipped `own_claim_damage_multiplier` |
| `duplicate-conflict` | 8 keys × 2 locations | the two faction/profession tables in README |
| `nonexistent` | ~6 | container-lock symbols; Communism `Tài trợ` buff; `TabStyle#tabRow` |
| `missing` — config keys | **130 of 164** | `professions` 61, `factions` 38, `gemini_gossip` 14, `quests` 14, `motd` 3 |
| `missing` — permission nodes | 1 of 14 | `economycraft.command.tag` (all 6 admin nodes present) |
| `missing` — commands | 15 | `/tag`, `/job`, `/party`, `/toll` + 11 under `/eco` |
| `missing` — public API | 4 members, 2 types | `factions()`, `inflationMultiplier()`, `medianActiveBalance()`, `FactionApi`, `FactionIds` |
| `missing` — changelog shape | 4 headings | four `## Unreleased` sections must become 1 |
| `missing` — constitution | 6 placeholders, 0 principles | blank template |

### Validation rules

| Rule | Test |
|---|---|
| `wrong-value` / `wrong-name` / `missing` → `correct`, with `value` re-read from authority | compare against extracted ground truth |
| `nonexistent` → the fact is removed from every citing document | grep the subject across all documents |
| `duplicate-conflict` → one citing document retains the canonical table; others become cross-references | count citations post-edit; must be 1 for table-type facts |
| Every `status: correct` fact re-verified at the end of the work, after all edits | final extraction run |

---

## 3. SourceOfTruth

The authority a `DocumentedFact` is checked against. Not editable by this feature.

| Ref | File | Authoritative for |
|---|---|---|
| `config-json` | `common/src/main/resources/assets/economycraft/config.json` | every configuration key, default, and nesting |
| `command-registry` | `EconomyCommands.java:70-147,162-215`; `WorthCommand.java:26-34` | command literals and their arguments |
| `permission-registry` | `util/EconomyPermissions.java:12-29` | admin and command permission nodes |
| `api-package` | `api/src/main/java/com/reazip/economycraft/api/v1/` | public API types and members |
| `build-config` | `build.gradle:30-36,100,173`; `gradle.properties` | platform matrix, Java version, mod version |

**Invariant**: `SourceOfTruth` is read-only under FR-001. When a fact is wrong, the document changes; the
authority never does. Two known cases where this bites are recorded rather than fixed — see §6.

---

## 4. Correction

The only kind of change this feature produces.

| Field | Type | Description |
|---|---|---|
| `target` | path | Document edited |
| `subject` | string | Fact corrected |
| `from` | string | Current asserted value |
| `to` | string | Corrected value |
| `class` | enum | `value-fix` \| `name-fix` \| `addition` \| `removal` \| `link-fix` \| `typo` \| `restructure` |
| `evidence` | string | `file:line` citation supporting the correction |

### Validation rules

| Rule | Test |
|---|---|
| Every `Correction` carries evidence | no correction without a `file:line` citation |
| `class: value-fix` / `name-fix` → `to` equals the authority's value | re-read authority |
| `class: removal` → the removed subject appears nowhere in any document | grep across the document set |
| `class: addition` → the added fact was previously absent | grep before edit confirms absence |
| No `Correction` has `class: code-change` | such a correction is out of scope by FR-001 |

---

## 5. PlanDocumentRetirement

The specific lifecycle transition for `TODO.md`. Modelled separately because it is the only irreversible
operation in the feature.

| Field | Type | Description |
|---|---|---|
| `path` | path | `TODO.md` |
| `contentClass` | enum | `standing-rule` \| `architecture-fact` \| `completed-effort` \| `open-decision` |
| `destination` | path \| none | Where the content relocates, per research.md R4 |
| `reverificationRequired` | boolean | Whether content must be re-verified before relocation |

### Migration map

| Source | Content class | Destination | Re-verify |
|---|---|---|---|
| §1 "Ground rules", 8 rules | `standing-rule` | **`AGENTS.md` §2 — done.** Constitution carries the normative principle; `AGENTS.md` carries the citation and mechanics | **Done** — verified against code |
| §2 "Architecture baseline", 18 rows | `architecture-fact` | **`AGENTS.md` §3-4 — done.** Constitution §2 will reference it | **Done** — 6 stale rows corrected |
| §4 decisions D1-D20 | mixed | Standing rules → constitution; feature-scoped → that feature's document | Yes |
| Phases 0-11, `P<n>-T<n>` | `completed-effort` | Nothing — already in `CHANGELOG.md` | No |
| §8 traceability matrix | `completed-effort` | Nothing | No |
| §5 blockers, §7 status | `completed-effort` | Nothing | No |

### Validation rules

| Rule | Test |
|---|---|
| No `standing-rule` content is deleted without a destination | every §1 rule appears in the constitution |
| Every migrated `architecture-fact` is re-verified against current code | §2's 18 rows re-checked; 3 known-stale rows corrected |
| No `completed-effort` content is relocated | grep the destination for phase/task identifiers; expect zero |
| The file is deleted only after all of the above | ordering constraint: migrate, verify, then delete |

### The three known-stale rows that must be corrected, not copied

| §2 claim | Reality |
|---|---|
| "Online-time tracking — **Does not exist**" | False. `online_time.json` and `cooldowns.json` ship |
| "Villager trading — **Not in EconomyCraft at all.** Zero `Merchant`/`Villager` references." | False. The gossip subsystem is built on villager dialogue |
| "**18 independent sites**" for tax | Self-corrected to 19 at `TODO.md:319`; §2 never updated |

---

## 6. KnownIssue — reported, not fixed

Defects that documentation cannot correct because the fix requires a code change, which FR-001 forbids.
Modelled as entities precisely so they are recorded rather than lost or silently worked around.

| Field | Type | Description |
|---|---|---|
| `subject` | string | The defect |
| `owner` | enum | `code` — outside this feature's scope |
| `evidence` | string | `file:line` |
| `disposition` | enum | `reported-only` |

### Register

| Subject | Evidence | Status |
|---|---|---|
| Gossip model identifier `gemini-3.8-flash` could not be confirmed to exist | `config.json:229` | `reported-only`. Document the value as shipped; correctness is a code question |
| ShopGuard is an external Fabric-only dependency whose current state cannot be checked here | absent from this repository | `reported-only`. Document the dependency as stated; make no claim about its state |

### Withdrawn

| Formerly reported | Why withdrawn |
|---|---|
| "MOTD points at a third repository identity" | **Wrong.** `git remote -v` shows `origin` = `TraiNguyenVan/EconomyCraft`, matching `config.json:249` exactly. The MOTD is correct; the README's *upstream wiki link* is the real defect. See research.md R5 |

---

## 7. Entity relationships

```text
Document ──asserts──▶ DocumentedFact ──verified-against──▶ SourceOfTruth
    │                      │
    │                      └── citedBy: many-to-one, exactly one agreed value (FR-007)
    │
    └── classified by: class, audience, language (FR-017)

Correction ──applied-to──▶ Document ──fixes──▶ DocumentedFact

PlanDocumentRetirement ──relocates──▶ content ──to──▶ Document
PlanDocumentRetirement ──requires──▶ re-verification before relocation

KnownIssue ──not-resolved-by──▶ this feature (reported to the code owner)
```

### Invariants

1. No document asserts a fact absent from a `SourceOfTruth` (FR-006, second direction).
2. Every fact asserted by at least one document exists in a `SourceOfTruth` (FR-006, first direction).
3. Every `DocumentedFact` cited by more than one document has exactly one value (FR-007).
4. Every `Correction` carries evidence (SC-005).
5. `SourceOfTruth` is never modified (FR-001, SC-008).
6. `PlanDocumentRetirement` completes migration before deletion (FR-019, SC-012).
7. A `KnownIssue` is reported, never fixed here (FR-001).