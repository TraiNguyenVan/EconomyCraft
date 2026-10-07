# Implementation Plan: Refresh Every Project Document

**Branch**: `001-refresh-all-documents` | **Date**: 2026-10-07 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/001-refresh-all-documents/spec.md`
**Research**: [research.md](research.md) | **Evidence**: [drift-inventory.md](drift-inventory.md)

## Summary

Bring every document in the EconomyCraft repository into agreement with the code as actually shipped.

The repository's documentation has drifted past the point of being out of date and become actively
misleading. A server owner following the README configures a Capitalism tax rate of 5% and receives 2.5%;
three whole configuration sections (`quests`, `gemini_gossip`, `motd`) are undocumented; fourteen commands
and thirty-plus configuration keys are missing; the API wiki omits the entire faction API; and three
documents send readers to the *upstream* project's wiki instead of this fork's. Meanwhile the plan document
being retired is the only place the project's enforced architectural rules are written down.

**Technical approach**: treat the shipped configuration file, the registered command set, the permission
nodes and the public API package as the sole authority. Extract ground truth statically — no server required
— then correct every document against it. Document nothing that code does not do, and delete nothing that
documents something code does. Retire the completed plan document only after migrating the standing rules it
is the last record of into the project constitution, which is itself currently an empty template.

The work is documentation-only. No runtime behavior, configuration default, API signature or permission node
changes (FR-001), enforced by SC-008.

## Technical Context

**Language/Version**: Not applicable to the deliverable — the output is Markdown. For the *subject* of the
documentation: Java 21 (targets 1.21.1, 1.21.11) and Java 25 (targets 26.1.2, 26.2, 26.3), per
`build.gradle:100` `(tgt.javaVersion ?: 25)`.

**Primary Dependencies**: None for the work itself. For verification only: `python3` (available; used for
JSON extraction and link checking), `git` (for remote and tag inspection). No new dependency is introduced.

**Storage**: N/A — Markdown files in the working tree.

**Testing**: The project's own suite is JUnit via Gradle: `common/src/test` (408 `@Test` methods) and
`common/src/test26_3` (117 methods, 26.3 only). Run with
`./gradlew -Pminecraft_version=26.3 :common:test`. Not modified by this feature; run to confirm SC-008.

**Target Platform**: GitHub repository rendering Markdown; GitHub wiki rendering `wiki/`.

**Project Type**: Documentation set for a Minecraft server-side mod (Architectury multi-project:
`api/`, `common/`, `fabric/`, `neoforge/`).

**Performance Goals**: N/A. Not a runtime artifact.

**Constraints**:
- Documentation-only. No change to runtime behavior, config defaults, API signatures or permission nodes.
- No live server available; all verification is static against the repository.
- Every documented default must equal the shipped default in `config.json`.
- Historical change-log entries are a record of what was true when written and are not retro-fitted.

**Scale/Scope**: 4 top-level documents (`README.md`, `CHANGELOG.md`, `TODO.md` to delete, plus new
constitution content), 11 wiki pages, 11 command definitions, 5 specification-kit templates.

Measured, not estimated: **164** configuration keys ship, of which **130 are undocumented** (professions 61,
factions 38, gemini_gossip 14, quests 14, motd 3). **1 of 14** command permission nodes is absent from the
documented table (`economycraft.command.tag`), all 6 admin nodes are present, **15** registered commands are
missing from the documented lists, **4** public API members and 2 whole API types are missing from the wiki,
and **4** `## Unreleased` headings must collapse to 1. Roughly 250 discrete corrections in total, recorded
with `file:line` evidence in [drift-inventory.md](drift-inventory.md).

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

**Result: NOT EVALUABLE — and this is a finding, not a formality.**

`.specify/memory/constitution.md` is an unmodified blank template: five placeholder principle names
(`[PRINCIPLE_1_NAME]` … `[PRINCIPLE_5_NAME]`), three placeholder sections, and a placeholder governance
block. It states no principle, no constraint and no rule.

A gate cannot pass or fail against an empty rule set. Recording it as "passed" would be theatre; treating
the absence as "no constraints" would silently authorise work that violates eight architectural rules the
code actively enforces.

**Handling** (per research.md R8): derive the project's *de facto* principles from verifiable code evidence,
use those as the working constraint set for this plan, and treat filling the constitution as a deliverable of
this feature (FR-015) rather than an unmet precondition.

### Working gate — de facto principles, each independently verifiable

| # | Principle | Verification basis |
|---|---|---|
| 1 | Server-side only. No client mod, no custom packets, no registered `MenuType`; UI is vanilla menus | `onInitializeClient()` empty; zero custom payloads; 3 Fabric-only mixins |
| 2 | Public API entry points keep the `requireServerThread()` contract | `EconomyCraftApiImpl` |
| 3 | Money moves only through the mutation engine, asymmetric form; levies are burned, never credited | `EconomyManager.transferMoney` |
| 4 | Every levy or rebate registers its source, or leaderboards and the scoreboard are corrupted | `FISCAL_SOURCES` |
| 5 | Configuration is the only tuning surface; no hard-coded literals in gameplay classes; new numeric keys use clamp-and-warn validation | `EconomyConfig` |
| 6 | Persistence is Gson JSON via `AsyncFileWriter`; NBT is never used | no NBT usage in the codebase |
| 7 | Vanilla APIs that differ across targets go in a compat fork | `util/*Compat.java`, source-set split |
| 8 | Mutation source names follow a fixed charset and namespace | `MutationSource` |

### Gate evaluation

| Principle | Applies to this feature? | Verdict |
|---|---|---|
| 1 | Yes — *directly load-bearing*. It is why `wiki/Tolls.md:36-48` embedding a Gradle command in a player-facing page is a defect, not a style choice | PASS with correction |
| 2 | No — documentation change | PASS |
| 3 | Indirectly — the constitution will state it, and the code must be re-verified against it before writing | PASS, re-verification required |
| 4 | Indirectly — same as principle 3 | PASS, re-verification required |
| 5 | Yes — **the whole feature is a large-scale application of this principle.** Documenting `config.json` faithfully is principle 5's documentation counterpart | PASS — this feature is an implementation of it |
| 6 | Indirectly — the ~21 runtime data files must be documented accurately | PASS, re-verification required |
| 7 | Yes — the source-set split is undocumented and must be documented per R3 | PASS with addition |
| 8 | No — but will be written into the constitution, re-verified from `MutationSource` first | PASS, re-verification required |

**Verdict: PASS**, conditional on the re-verification obligations marked above being met when the
constitution is authored. No violations requiring a Complexity Tracking entry.

**Post-design re-check** (required after Phase 1):

| Principle | Design artifacts | Verdict |
|---|---|---|
| 1 server-side only | `wiki-page-contract.md` R2 makes "no code in player pages" a contract rule; §4.2 relocates `Tolls.md:36-48` rather than deleting it | PASS |
| 2 thread contract | Carried into `constitution-contract.md` §3 as principle 2, re-verified against `EconomyCraftApiImpl` at authoring time | PASS |
| 3 money movement | Carried as constitution principle 3 | PASS, re-verification required |
| 4 fiscal sources | Carried as constitution principle 4, with the leaderboard-corruption consequence stated so the rule is not forgotten | PASS, re-verification required |
| 5 config is the tuning surface | **The entire feature is a documentation-scale application of this principle.** `config-reference-contract.md` R1-R7 make `config.json` the authority for ~164 keys | PASS |
| 6 persistence | `config-reference-contract.md` §4 enumerates all 21 runtime data files; `data-model.md` §3 records it as read-only authority | PASS, re-verification required |
| 7 compat forks | `research.md` R3 adds the undocumented source-set split to the documentation scope | PASS with addition |
| 8 mutation source naming | Carried as constitution principle 8 | PASS, re-verification required |

**Verdict: PASS.** No violations, no Complexity Tracking entry required.

Two obligations are carried forward as hard preconditions on the constitution task, not as advisory notes:

1. `TODO.md` §2 must be re-verified row by row before migration. Four rows are known-stale (online-time
   tracking, villager trading, the tax-site count, the API type count). Copying them forward would promote
   errors into a higher-authority document.
2. `TODO.md` §1's eight ground rules are the constitution's source material and must be re-verified against
   code, not copied. Deletion happens only after migration is confirmed complete (FR-019, SC-012).

**Artifacts produced by this phase:**

| Artifact | Contents |
|---|---|
| [research.md](research.md) | 8 decisions (R1-R8) with rejected alternatives |
| [data-model.md](data-model.md) | 7 entities: Document, DocumentedFact, SourceOfTruth, Correction, PlanDocumentRetirement, KnownIssue, and their invariants |
| [contracts/config-reference-contract.md](contracts/config-reference-contract.md) | 12 rules; full missing-key inventory; explicit "must not document" list |
| [contracts/wiki-page-contract.md](contracts/wiki-page-contract.md) | 7 rules; 11-page register with per-page defects; 4 judgement calls flagged |
| [contracts/constitution-contract.md](contracts/constitution-contract.md) | 5 content requirements; 8 required principles; mandatory re-verification of §2 |
| [quickstart.md](quickstart.md) | 10 runnable checks, V1-V10, with measured baselines |

All validation scripts in `quickstart.md` were executed against the current repository and correctly
reproduce the failing state. One regex bug was found and fixed during authoring: the config-key matcher
initially rejected camelCase top-level keys such as `startingBalance`, reporting zero documented keys when
34 are in fact documented. The corrected matcher reports the true figure of 130 undocumented of 164.

## Project Structure

### Documentation (this feature)

```text
specs/001-refresh-all-documents/
├── plan.md              # This file (/speckit.plan command output)
├── spec.md              # Feature specification (input, unchanged)
├── research.md          # Phase 0 output — 8 decisions R1-R8
├── drift-inventory.md   # Evidence base — ~250 findings with file:line citations
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/
│   ├── config-reference-contract.md    # Phase 1 — the config-key table contract
│   ├── wiki-page-contract.md           # Phase 1 — per-page structure, language, audience
│   └── constitution-contract.md        # Phase 1 — constitution content requirements
└── checklists/
    └── requirements.md   # Specification quality checklist, 16/16 passing
```

### Source Code (repository root)

This feature creates no source code. It modifies documents in place.

```text
# Modified in place
README.md                  # Entry point: config tables, commands, permissions, platform matrix, links
CHANGELOG.md               # Collapse four "## Unreleased" headings into one section with subsections
wiki/
├── _Sidebar.md            # Language policy statement; already-correct relative links retained
├── Home.md                # Upstream wiki URLs -> relative links matching _Sidebar.md
├── API-Reference.md       # Add FactionApi, FactionIds, inflationMultiplier, medianActiveBalance
├── Factions.md            # Remove the non-existent buff; add /eco job pointer
├── Professions.md         # "Merchants" -> "Merchant"; add /eco job
├── Chon-tag.md            # Add /eco party and /eco job
└── Tolls.md               # Move developer verification section out of a player page; add default value

# Deleted (after migration per FR-019)
TODO.md                   # 1140 lines; completed plan; migrated content lands in constitution first

# Written from scratch (FR-015)
.specify/memory/constitution.md   # Currently a blank template; 8 principles + constraints + governance
AGENTS.md                          # Replaces TODO.md; carries its ground rules + architecture baseline

# Reviewed, corrected only where wrong (FR-016)
.opencode/commands/*.md           # 11 command definitions
.specify/templates/*.md           # 5 templates — see durability caveat in research.md
```

**Structure Decision**: Documentation-only, modified in place. No source tree is created or restructured,
because the deliverable is prose whose correctness is measured against existing code, not new code. The one
file written from scratch is the constitution, because it is the only in-scope document that does not exist
in usable form.

## Phase 0 — Research *(complete)*

Eight decisions recorded in [research.md](research.md):

| ID | Decision |
|---|---|
| R1 | Ground truth via three static sources, no server required |
| R2 | Do not state raw test counts; the count is per-target and any single number is wrong for 4 of 5 targets |
| R3 | Document the five-target platform matrix and the two-source-set test layout — currently absent from every document |
| R4 | Three-way destination table for the deleted plan's content; `AGENTS.md` created as the migration target and §2 re-verified rather than copied — **6 stale rows found and corrected** |
| R5 | Canonical wiki base URL from `git remote -v`; relative links inside `wiki/`, absolute from README; preserve upstream attribution |
| R6 | Language policy stated in both the wiki sidebar and a contributor-facing location |
| R7 | Collapse four `## Unreleased` headings — functionally load-bearing, since release notes consume this file |
| R8 | Constitution gate not evaluable; derive 8 de facto principles as the working constraint set |

## Phase 1 — Design *(this phase)*

| Artifact | Purpose |
|---|---|
| [data-model.md](data-model.md) | Models the documentation domain: documents, documented facts, corrections, and the retirement of a plan document |
| [contracts/config-reference-contract.md](contracts/config-reference-contract.md) | Rules any configuration-key table must satisfy, plus the full missing-key inventory |
| [contracts/wiki-page-contract.md](contracts/wiki-page-contract.md) | Per-page audience, language, and content rules for all 11 wiki pages |
| [contracts/constitution-contract.md](contracts/constitution-contract.md) | What the constitution must contain, the re-verification obligation, and the migration map from the deleted plan |
| [quickstart.md](quickstart.md) | Runnable verification: extract ground truth, check keys, check links, check commands, confirm no behavior change |

## Complexity Tracking

Not required — the Constitution Check produced no violations.

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|

Empty. All eight working-gate principles pass.

## Risks

| Risk | Impact | Mitigation |
|---|---|---|
| Spec Kit regenerates `.specify/templates/*` | Work there may not survive a toolkit upgrade | Flagged in spec assumptions and research R-table. Confirm before spending effort; constitution work is unaffected |
| Copying the retired plan's §2 architecture baseline forward | Would import six known-stale rows into a higher-authority document | research.md R4 mandates re-verification. **Done**: 6 rows corrected during the `AGENTS.md` migration — including mixins (12 Fabric/10 NeoForge, not "3 Fabric only") and `FISCAL_SOURCES` (line 81 at the package root, not line 63 in `util/`) |
| Configuration keys change during the work | Documented defaults go stale again mid-flight | Re-run the extraction in `quickstart.md` as the final step, after all edits |
| Scope creep into code fixes | Violates FR-001; SC-008 detects it | FR-001 and SC-008 are absolute; known code-side issues are recorded, never fixed |
| The wiki is not rebuilt from these files by this feature | GitHub wikis are separate clones | Out of scope; state the push step in `quickstart.md` as an explicit manual action |