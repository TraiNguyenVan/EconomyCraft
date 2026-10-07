---

description: "Task list for refreshing every project document to match shipped code"
---

# Tasks: Refresh Every Project Document

**Input**: Design documents from `/specs/001-refresh-all-documents/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md, drift-inventory.md

**Tests**: The specification did not request a TDD approach, so no test-authoring tasks are generated.
The acceptance mechanism for this feature is `quickstart.md` — its V1-V10 checks are the gate, and every
phase ends with the relevant checks.

**Organization**: Tasks are grouped by user story so each can be completed and validated independently.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to
- Exact file paths in every description

## Path Conventions

Documentation-only feature. No source file is created or modified.

- `README.md`, `CHANGELOG.md`, `AGENTS.md` — repository root
- `wiki/*.md` — 11 wiki pages
- `.specify/memory/constitution.md` — governance
- `specs/001-refresh-all-documents/` — design docs and ground truth

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Capture ground truth before editing anything. Every later correction is checked against
these, so nothing in this phase may modify a document.

- [X] T001 Record the pre-change baseline: run every check in `specs/001-refresh-all-documents/quickstart.md` (V1-V9) and save the output to `specs/001-refresh-all-documents/baseline.md`. Expected failing state: 130 undocumented config keys, 4 `## Unreleased` headings, `TODO.md` present, 6 constitution placeholders, 4 phantom keys, 4 upstream-wiki link problems
- [X] T002 [P] Extract the config inventory to `specs/001-refresh-all-documents/ground-truth-config.md` — 164 leaf keys with exact paths and defaults, from `common/src/main/resources/assets/economycraft/config.json`, grouped by section (top-level 34, professions 61, factions 38, quests 14, gemini_gossip 14, motd 3)
- [X] T003 [P] Extract the command inventory to `specs/001-refresh-all-documents/ground-truth-commands.md` from `common/src/main/java/com/reazip/economycraft/EconomyCommands.java:70-147,162-215` and `common/src/main/java/com/reazip/economycraft/WorthCommand.java:26-34`, including argument arity for `/worth <item> [amount]`, `/eco gossip` subcommands and `/eco offers ah|order <id>`
- [X] T004 [P] Extract the permission and API inventory to `specs/001-refresh-all-documents/ground-truth-api.md` — 6 admin nodes and 14 command nodes from `common/src/main/java/com/reazip/economycraft/util/EconomyPermissions.java:12-29`, and the 18 public types under `api/src/main/java/com/reazip/economycraft/api/v1/`
- [X] T005 Confirm `TODO.md` is recoverable before any deletion: run `git show HEAD:TODO.md | wc -l` and expect `1140`. Record the recovery command `git show HEAD:TODO.md > TODO.md` in `specs/001-refresh-all-documents/notes.md`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Build the checking tools and clear the judgement calls. Nothing downstream can be verified
without these.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

- [X] T006 [P] Create `specs/001-refresh-all-documents/scripts/check_config_keys.py` implementing checks V2 and V3 from `quickstart.md`. It MUST match any backticked token and keep it only if it is a real config key or a prefix of one — matching dotted names only silently misses camelCase top-level keys such as `startingBalance`, a bug already hit once during planning
- [X] T007 [P] Create `specs/001-refresh-all-documents/scripts/check_links.py` implementing check V4. It MUST flag absolute upstream-wiki URLs separately from relative links, because the two need different fixes
- [X] T008 [P] Add `.*.swp` and `*.swp` to `.gitignore` — the Vim swap file `.TODO.md.swp` is currently untracked and visible
- [X] T009 Ask the user to resolve the two judgement calls recorded in `specs/001-refresh-all-documents/contracts/wiki-page-contract.md` §4: (a) whether the Communism `Tài trợ` buff in `wiki/Factions.md:13` is removed or merely unimplemented, and (b) where the developer verification block in `wiki/Tolls.md:36-48` should go. Do not guess either
- [X] T010 Re-verify the 8 ground rules and the architecture baseline one final time against current code, per `contracts/constitution-contract.md` C5. `AGENTS.md` §2-4 already carries corrected versions of the 6 known-stale `TODO.md` rows; confirm each is still true before relying on it
- [X] T011 Commit `AGENTS.md` so the `TODO.md` migration is durable before deletion. It is currently untracked, which defeats the purpose of it being the replacement

**Checkpoint**: Ground truth captured, checkers built, judgement calls resolved. Ready to edit documents.

---

## Phase 3: User Story 1 - A server owner sets up the mod from the docs alone (Priority: P1) 🎯 MVP

**Goal**: `README.md` states the real platform matrix, every configuration key with its shipped default,
every registered command and permission node, and links that resolve.

**Independent Test**: Configure a clean server using only `README.md` and confirm each documented key,
command and node matches the running server.

### Implementation for User Story 1

All tasks target `README.md`. They are sequential within the file to avoid merge conflicts — do **not**
mark these `[P]`.

- [X] T012 [US1] Collapse the two duplicated configuration reference sections in `README.md:206-284` and `README.md:398-428` into one canonical table per contract rule R4, turning the second into a cross-reference
- [X] T013 [US1] Correct the 5 wrong defaults in `README.md`: `factions.capitalism.daily_tax_rate` to `0.025`, `factions.monarchy.daily_tax_rate` to `0.01`, and the three `factions.communism.income_tax_tier{1,2,3}_rate` values to `0.0025` / `0.00375` / `0.00625`
- [X] T014 [US1] Correct the 4 wrong key names in `README.md` per contract rule R2: `ownClaimDamageMultiplier` → `own_claim_damage_multiplier`, `crop_boost_interval_minutes` → `crop_boost_cooldown_minutes`, `lava_regen_duration_seconds` → `lava_regeneration_seconds`, `discount_master` → `cost_factor_master`
- [X] T015 [US1] Add the 130 undocumented configuration keys to `README.md` as tables grouped by section, using exact shipped paths and defaults from `ground-truth-config.md`. Include `max_active_tolls_per_player` = `10`
- [X] T016 [US1] Add the supported platform matrix to `README.md` per `research.md` R3: five targets (1.21.1, 1.21.11, 26.1.2, 26.2, 26.3), Java 21 for the 1.21.x line and 25 for 26.x, from `build.gradle:30-36` and `build.gradle:100`
- [X] T017 [US1] Complete the command list at `README.md:27` with `/tag`, `/job`, `/party`, `/toll`, and expand the admin command list at `README.md:120` with `/eco admin`, `/eco reload`, `/eco motd`, `/eco import`, `/eco gossip`, `/eco toll`, `/eco tag`, `/eco job`, `/eco party`, `/eco offers` and the standalone admin family
- [X] T018 [US1] Add the missing `economycraft.command.tag` node to the permission table at `README.md:141-155`, and state that it gates `/eco tag`, `/eco job`, `/eco party`, the hub Tags button and `/tag`
- [X] T019 [US1] Resolve the contradiction between `README.md:97` ("Every option in `config.json`, editable in-game") and `README.md:306` (factions/professions keys "not yet editable from `/eco settings`"). `common/src/main/java/com/reazip/economycraft/admin/AdminSettingsUi.java:71-111` confirms the second is correct, so only one may stand
- [X] T020 [US1] Replace the stale "Phase 2 shipped them as data only" text at `README.md:208-211`, which contradicts the shipped faction effects
- [X] T021 [US1] Delete the orphan paragraph at `README.md:286-288` describing the removed container-lock feature (`PRIVATE`, `PARTY_ONLY`)
- [X] T022 [US1] Fix the two links: `README.md:25` `wiki/Tolls.md` → `https://github.com/TraiNguyenVan/EconomyCraft/wiki/Tolls`, and `README.md:468` upstream wiki → this fork's wiki
- [X] T023 [US1] Preserve `README.md:6` and `README.md:475` upstream attribution unchanged — GPL-3.0 obligation
- [X] T024 [US1] Fix the `Buttom` typo at `README.md:12` and re-pad the table so the divider row matches the column width
- [X] T025 [US1] Remove the internal decision-ID leak at `README.md:230` describing `capitalism.max_rate_change_per_day` as "D14's griefing brake", per contract rule R7
- [X] T026 [US1] Add the `/eco` hub buttons missing from the table at `README.md:12-25`: Tags and How It Works, from `common/src/main/java/com/reazip/economycraft/HubUi.java:246-249,277-280`
- [X] T027 [US1] Add the missing admin reset tool `Run Faction Daily Tax` to the table at `README.md:106-114`, which currently lists 7 of 8 buttons, from `common/src/main/java/com/reazip/economycraft/admin/AdminResetUi.java:101`
- [X] T028 [US1] Add the missing Server Quests admin screen to the admin section, from `common/src/main/java/com/reazip/economycraft/admin/AdminUi.java:87`
- [X] T029 [US1] Replace the four named data files at `README.md:163-166` with the full set of ~21 runtime data files, preserving the importable distinction from `common/src/main/java/com/reazip/economycraft/util/EconomyPaths.java:40-51`, and remove the "Phase 2 added" phrasing per FR-011
- [X] T030 [US1] Validate: run `scripts/check_config_keys.py` and confirm 0 undocumented keys and 0 value errors, and confirm 0 phantom keys

**Checkpoint**: A server owner can install, configure and permission the mod from `README.md` alone.
Verifies SC-001, SC-009.

---

## Phase 4: User Story 2 - A mod developer integrates against the API wiki (Priority: P2)

**Goal**: Every wiki page resolves from the index, matches the shipped public API, and is written in the
language its audience calls for.

**Independent Test**: Write a small integration against the shipped jar using only `wiki/` as reference and
compile it without reading the source tree.

### Implementation for User Story 2

^- [X] T031 [P] [US2] Add the 4 missing members to `wiki/API-Reference.md`: `factions()`, `inflationMultiplier()`, `medianActiveBalance()`, plus a `FactionApi` section, per `api/src/main/java/com/reazip/economycraft/api/v1/EconomyCraftApi.java:23,45,52`
^- [X] T032 [P] [US2] Add `FactionApi` (5 methods) and `FactionIds` (5 constants) to `wiki/API-Reference.md`, per contract rule R6. These appear in zero of the 11 wiki pages today
^- [X] T033 [P] [US2] Convert the 5 upstream absolute URLs at `wiki/Home.md:30-34` to relative links matching `wiki/_Sidebar.md:10-15`
^- [X] T034 [US2] Resolve the `wiki/Factions.md:13` `Tài trợ` buff per the answer to T009 — remove it if unimplemented, and do not keep it in the wiki if it is merely planned
^- [X] T035 [US2] Fix the profession name to singular `Merchant` in `wiki/Professions.md:9,41` and `wiki/Chon-tag.md:9`, matching `ProfessionId.values()`, per contract rule R7
^- [X] T036 [US2] Add the missing `/eco job` pointer to `wiki/Professions.md` and the missing `/eco party` and `/eco job` to `wiki/Chon-tag.md:7`
^- [X] T037 [US2] Move the developer verification block out of `wiki/Tolls.md:36-48` to its destination decided in T009, and add the `max_active_tolls_per_player` default of `10` to `wiki/Tolls.md:13`. Do not delete the content — it is real contributor guidance
^- [X] T038 [US2] Remove the maintainer phrasing at `wiki/Tolls.md:26` ("Installing the new jar requires a server restart") per contract rule R3
^- [X] T039 [US2] Translate `wiki/Tolls.md` to Vietnamese, or record the decision to leave it English and correct the sidebar accordingly — it is a player page in the Vietnamese Gameplay section, violating FR-017. Use the T009 outcome if covered there, otherwise raise it
^- [X] T040 [US2] State the wiki language policy in `wiki/_Sidebar.md`: player pages Vietnamese, integrator pages English, one language per page, no code in player pages, per `research.md` R6
^- [X] T041 [US2] Add the same language policy in a contributor-facing location per `research.md` R6, so a contributor choosing a page's language finds it without reading the wiki sidebar
^- [X] T042 [US2] Validate: run `scripts/check_links.py` and confirm 0 problems; confirm every `api/v1` public type appears in `wiki/API-Reference.md`

**Checkpoint**: US1 and US2 both independently correct. Verifies SC-002, SC-003, SC-011.

---

## Phase 5: User Story 3 - A contributor knows the rules the project works by (Priority: P3)

**Goal**: The constitution states the principles the code enforces, the change log has one release
section, and the completed plan document is retired without losing its durable content.

**Independent Test**: Ask a new contributor to name three rules their change must follow and explain one
past design decision, using only the project's documents.

### Implementation for User Story 3

- [ ] T043 [P] [US3] Write `.specify/memory/constitution.md` with the 8 principles from `contracts/constitution-contract.md` §3, each citing the code that enforces it. Replace all 6 placeholder tokens. Record version `1.0.0` and today's date, with no fabricated ratification date
- [ ] T044 [P] [US3] Add the constraints and governance sections to `.specify/memory/constitution.md` per `contracts/constitution-contract.md` §6, written in terms of mechanisms that exist (`requireServerThread()`, `FISCAL_SOURCES`) rather than aspirational review processes
- [ ] T045 [P] [US3] Add the architecture baseline to `.specify/memory/constitution.md` §2, referencing `AGENTS.md` §3-4 rather than restating it, per `research.md` R4. Do not copy `TODO.md` §2 — 6 of its rows are known-stale
- [ ] T046 [US3] Collapse the four `## Unreleased` headings in `CHANGELOG.md` into one section with subsections, preserving every entry, per `research.md` R7. This is functionally load-bearing: `.github/workflows/release.yml:201` consumes this file as the release notes body
- [ ] T047 [US3] Leave historical test-count claims in `CHANGELOG.md` alone — they are a record of what was true when written. Add no new count, per `research.md` R2
- [ ] T048 [US3] Verify every `TODO.md` §1 ground rule appears in `.specify/memory/constitution.md` or `AGENTS.md` before deleting, per FR-019
- [ ] T049 [US3] Verify no `TODO.md` content that is the last record of a design decision is lost: grep `.specify/memory/constitution.md` and `AGENTS.md` for phase numbers, `P<n>-T<n>` task IDs and `D<n>` decision IDs, and confirm the source-set rule, the no-NBT rule, the no-custom-networking rule and the ShopGuard dependency statement are all present
- [ ] T050 [US3] **Gate**: confirm with the user that the open Vim buffer for `TODO.md` is saved or deliberately discarded. Unsaved buffer content is in no commit and will be lost. Then `git rm TODO.md` and record the recovery command in `specs/001-refresh-all-documents/notes.md`
- [ ] T051 [US3] Confirm `AGENTS.md` §6 still describes its relationship to the constitution correctly after both files exist

**Checkpoint**: All three stories independently functional. Verifies SC-006, SC-007, SC-010, SC-012.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [ ] T052 [P] Run `quickstart.md` V5 and confirm 0 paths pointing outside the repository
- [ ] T053 [P] Run `quickstart.md` V8 and confirm `origin` is `TraiNguyenVan/EconomyCraft`, the MOTD URL in `common/src/main/resources/assets/economycraft/config.json` is untouched, and the GPL attribution in `README.md` is intact
- [ ] T054 [P] Run `quickstart.md` V9 and confirm player pages are Vietnamese and integrator pages English
- [ ] T055 **Run `quickstart.md` V7 last**: `git diff --stat` over `common api fabric neoforge build.gradle gradle.properties` MUST be empty, then `./gradlew -Pminecraft_version=26.3 :common:test` must pass unchanged. A non-empty diff or a failing test means documentation work altered behavior, violating FR-001 — revert the behavior change, never edit the test
- [ ] T056 Run the authoritative sweep `quickstart.md` V6 and confirm all checks report OK: 0 undocumented keys, 1 `## Unreleased` heading, `TODO.md` absent, 0 constitution placeholders
- [ ] T057 Re-run the config extraction and confirm no default drifted during the work, per the `quickstart.md` V6 warning
- [ ] T058 Document the manual wiki publish step from `quickstart.md` V10 for the maintainer — `wiki/` is a separate git clone and these edits do not reach the hosted wiki on their own
- [ ] T059 Record the two KnownIssues from `data-model.md` §6 as separate code-side decisions: the unverified `gemini-3.8-flash` model identifier, and ShopGuard's unverifiable external state. Neither may be fixed in this feature

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies. Captures ground truth; modifies nothing
- **Foundational (Phase 2)**: Depends on Phase 1. **BLOCKS all user stories** — corrections cannot be verified without the extracted truth and the checkers
- **User Stories (Phase 3-5)**: All depend on Phase 2. US1 and US2 touch different files and can run in parallel; US3 is independent of both
- **Polish (Phase 6)**: Depends on all desired stories. V7 must run last

### User Story Dependencies

- **User Story 1 (P1)**: No dependencies on other stories. `README.md` only
- **User Story 2 (P2)**: No hard dependency on US1. Both write files referenced by each other — T022 and T033 must agree on the canonical wiki URL, so treat the URL as fixed by Phase 1
- **User Story 3 (P3)**: Depends on T010 (re-verification) and T011 (AGENTS.md committed), because it deletes `TODO.md` on the guarantee that its content already lives elsewhere

### Within Each User Story

- Ground truth before prose — never edit a document from memory
- Sequential within a single file to avoid conflicts; `[P]` only across different files
- Complete a story's own validation before starting the next

### Parallel Opportunities

- T002-T004 (ground truth extraction) — different sources, fully parallel
- T006-T008 (checkers and gitignore) — different files, parallel
- T031-T042 (wiki pages) — different files, parallel within US2
- T043-T045 (constitution) — same file, sequential
- T052-T054 (independent polish checks) — parallel
- US1 and US2 can be staffed in parallel by different people

---

## Parallel Example: User Story 2

```bash
# All target different files - launch together:
Task: "T031 Add 4 missing members to wiki/API-Reference.md"
Task: "T033 Convert 5 upstream URLs at wiki/Home.md to relative"
Task: "T035 Fix profession name in wiki/Professions.md and wiki/Chon-tag.md"
Task: "T037 Relocate developer verification block in wiki/Tolls.md"
Task: "T040 State wiki language policy in wiki/_Sidebar.md"
```

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Phase 1: Setup — capture ground truth
2. Phase 2: Foundational — build checkers, resolve judgement calls
3. Phase 3: User Story 1 — `README.md`
4. **STOP and VALIDATE**: run T030. Configure a server from the README alone
5. Ship it

The README alone delivers most of the value: it holds every wrong default, every phantom key name, the
missing command and permission entries, and both broken links.

### Incremental Delivery

1. Setup + Foundational → ground truth locked, checkers ready
2. US1 → README correct → **MVP**, largest single win
3. US2 → wiki correct → integrators unblocked
4. US3 → governance correct, plan document retired
5. Polish → V7 confirms nothing else moved

### Parallel Team Strategy

1. One person completes Phase 1 and 2 (short — it is extraction, not authoring)
2. Then in parallel:
   - Developer A: US1 (`README.md`)
   - Developer B: US2 (`wiki/*.md`)
   - Developer C: US3 (constitution, change log, deletion)
3. US3's deletion task must wait on A and C's migration completing

---

## Notes

- [P] = different files, no dependencies
- [Story] label maps task to a user story for traceability
- No test-authoring tasks: the specification did not request TDD. Validation is `quickstart.md`
- **Never edit a source file.** FR-001 forbids it and V7 detects it. KnownIssues are reported, not fixed
- Every task touching a document must cite its evidence in `drift-inventory.md` or `ground-truth-*.md`
- Do not state a raw test count in any document — coverage is per-target (R2)
- Do not state a config default from memory — re-extract if `config.json` changed (V6 warning)
- Commit after each task or logical group
- Stop at any checkpoint to validate the story independently