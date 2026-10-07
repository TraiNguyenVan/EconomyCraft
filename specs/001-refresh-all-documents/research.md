# Phase 0 Research: Refresh Every Project Document

**Feature**: `001-refresh-all-documents`
**Date**: 2026-10-07
**Inputs**: [`spec.md`](spec.md), [`drift-inventory.md`](drift-inventory.md)

All `NEEDS CLARIFICATION` markers from the specification have been resolved by the user. This document
records the eight technical decisions that must be settled before design, each with the alternatives that
were considered and why they were rejected.

---

## R1 — Establishing ground truth without a running server

**Question**: The specification makes the shipped code the authority for every documented fact. What is the
cheapest reliable way to read that authority, given that starting a Minecraft server per fact-check is
absurd and the audit was static-only?

**Decision**: Three static sources, in descending order of authority, with no server required.

| Order | Source | Authoritative for | How to read |
|---|---|---|---|
| 1 | `common/src/main/resources/assets/economycraft/config.json` | every configuration key, its default, and its nesting | parse the JSON; walk recursively to produce a flat key path |
| 2 | Registered commands, permission nodes, API types | command and permission inventories, public API surface | read `EconomyCommands.java:70-147,162-215`; `util/EconomyPermissions.java:12-29`; `api/src/main/java/com/reazip/economycraft/api/v1/` |
| 3 | `build.gradle:30-36`, `gradle.properties` | supported platform matrix, Java version, mod version | read the `ext.mcTargets` table |

`config.json` is authoritative for defaults specifically because it is the file that ships and is auto-merged
into a fresh install by `EconomyConfig` — a documented value that disagrees with it will not match what a
new server actually gets.

**Rationale**: Static reading is sufficient for every claim in the drift inventory, and the audit proved it
by resolving ~200 findings without executing anything. It is also the only method that scales to checking
several hundred configuration keys.

**Alternatives considered**:
- *Boot a dedicated server and read `config.json` after first run.* Rejected: adds minutes per iteration and
  the bundled file already is what gets written.
- *Trust the README as a starting point and verify by exception.* Rejected: the README is demonstrably wrong
  in at least nine places; exception-checking would have missed all of them.
- *Ask the author.* Rejected for planning purposes: the audit already produced file-and-line evidence, so
  verification questions are answerable from the repository without blocking.

**Caveat carried forward**: this method proves *what the code does*, never *what it should do*. Where the two
disagree the code wins (FR-001), and genuine design questions go to the user rather than being resolved by
inference. The audit hit exactly one such case — the gossip model identifier — and it was left unresolved
rather than guessed.

---

## R2 — How to state test counts without recreating the same rot

**Question**: The drift inventory found three mutually inconsistent test counts (313 in `CHANGELOG.md:87`,
322 in `TODO.md:1069`, 525 actual). The obvious fix is to write 525. The audit also revealed the count is
**per platform target**, not global. Would writing 525 simply create a new number that rots?

**Decision**: Do not state a raw total anywhere. The two test source sets are version-conditional —
`common/src/test` (408 `@Test` methods) and `common/src/test26_3` (117 methods), the latter added only for the
26.3 target. Any single number is therefore false for four of the five supported targets.

Instead:
- The change log's historical entries keep whatever count they recorded at the time; that is a historical
  record, not a live claim, and rewriting it would falsify history.
- No *new* count is added. Where a reader benefits from knowing test coverage exists, name the source sets
  and the command that reproduces the number, rather than the number itself.

**Rationale**: A number that is wrong on 4 of 5 platforms is worse than no number, because it invites
comparison and cannot be kept correct without a maintenance step nobody will perform. Naming the reproduction
command transfers the freshness burden to the reader at the moment they care.

**Alternatives considered**:
- *Write 525.* Rejected: wrong for every target except 26.3, and stale after the next feature.
- *Write a per-target matrix of counts.* Rejected: five numbers to maintain instead of one, for a fact no
  reader of a README needs.
- *Delete all counts from history.* Rejected: `CHANGELOG.md` is a historical record, and `.github/workflows/
  release.yml:201` consumes it directly. Rewriting past entries misrepresents what was true when written.

---

## R3 — The supported platform matrix is undocumented and must be added

**Question**: What does the project actually support? No document states this, yet `README.md:27` tells a
reader to "use the normal EconomyCraft jar that matches the Minecraft version and loader of your mod"
without ever saying which versions exist.

**Decision**: Document the matrix from `build.gradle:30-36`, which is the authority:

| Target | Java | Loader | Notes |
|---|---|---|---|
| 1.21.1 | 21 | Fabric + NeoForge | `legacy121` compat fork; NeoForge `21.1.250` |
| 1.21.11 | 21 | Fabric + NeoForge | no legacy fork |
| 26.1.2 | 25 | Fabric + NeoForge | |
| 26.2 | 25 | Fabric + NeoForge | |
| 26.3 | 25 | Fabric + NeoForge | **default** (`gradle.properties`), the only target with its own test source set |

**Java version rule**: `build.gradle:100` reads `(tgt.javaVersion ?: 25)` — the 1.21.x line pins 21
explicitly, everything else defaults to 25.

Also documented, because contributors hit it immediately: `common/src/` is split into `main`, plus
`modern`/`legacy121` and `obfuscated`/`unobfuscated` compat forks, plus two test source sets
(`test`, `test26_3`). The rule that any vanilla API differing across versions belongs in a compat fork
following the existing `util/*Compat.java` pattern is currently recorded **only** in the plan document being
deleted — see R4.

**Rationale**: This is the single highest-value addition to the README. Without it a prospective server owner
cannot know whether the mod supports their version at all, and a contributor cannot know where to put code
that must compile against five targets.

**Alternatives considered**:
- *Leave version support implicit.* Rejected: it is already causing harm — `README.md:27` gives an
  instruction that cannot be followed without information the repository does not provide.
- *Put the matrix in the wiki only.* Rejected: the README is the entry point and is where the instruction it
  needs to support appears.

---

## R4 — Where the deleted plan document's content must go

**Question**: `TODO.md` is to be deleted (FR-018). It contains material of three different kinds. Where does
each go? This is the highest-risk part of the feature, because `TODO.md:24-43` (§1 "Ground rules") and
`TODO.md:47-70` (§2 "Architecture baseline") are the **only** records of several constraints the code
actively enforces.

**Decision**: Classify by whether the content is a standing rule, an architecture fact, or a completed
effort's record. Each class has one destination.

| Content | Class | Destination | Verification required |
|---|---|---|---|
| §1 "Ground rules" (8 rules) | **Standing rule** — applies to every future change | **Migrated to `AGENTS.md` §2**; constitution holds the normative principle | Done — verified against code |
| §2 "Architecture baseline" (18 rows) | **Architecture fact** — true of the codebase now | **Migrated to `AGENTS.md` §3-4**; constitution §2 references it | Done — 6 stale rows corrected |
| Phases 0-11 task records, `P<n>-T<n>` IDs | Completed effort | Nothing — already in `CHANGELOG.md` | None |
| §4 open decisions D1-D20 | Mixed | Split: standing rules → constitution; feature-scoped → the document for that feature | Re-verify |
| §8 traceability matrix | Completed effort | Nothing | None |

**§2 must not be copied verbatim.** It is stale in at least three rows, verified against the audit:

| §2 claim | Reality |
|---|---|
| "Online-time tracking — **Does not exist**" | False. `online_time.json` ships (`drift-inventory.md` §1i) and `cooldowns.json` is written by it. |
| "Villager trading — **Not in EconomyCraft at all.** Zero `Merchant`/`Villager` references." | False. The gossip subsystem is built on villager dialogue; `gossip/GossipConfig.java` and `/eco gossip dialogue [prof]` exist. |
| "**18 independent sites**" for tax | Corrected to 19 within the same file at `TODO.md:319`; §2 was never updated. |

Copying §2 forward without re-verification would import those errors into a document with higher authority
than the one they currently sit in — and `TODO.md` is about to be deleted, so the error would have nowhere
left to be corrected.

**Rationale**: FR-019 requires that the count of design decisions lost with the deletion is zero (SC-012).
This table is how that is achieved, and the mandatory re-verification of §2 is what keeps the deletion from
promoting stale claims into standing policy.

**Alternatives considered**:
- *Archive `TODO.md` under `docs/archive/`.* Rejected — the user chose deletion in Q3. Recorded here because
  it was the option that carried zero information-loss risk, and the trade-off was accepted knowingly.
- *Relocate §1 and §2 wholesale.* Rejected: §2 contains the three proven-stale rows above.
- *Migrate only §1, drop §2.* Rejected: §2's load-bearing rows (source-set split, no-NBT rule, no custom
  networking, compat-fork rule) are the ones a newcomer most needs and they exist nowhere else.

---

## R5 — Canonical URLs for every documentation link

**Question**: Several documents link to a wiki that is not this fork's. What is the correct target?

**Decision**: Canonical base URL is `https://github.com/TraiNguyenVan/EconomyCraft/wiki`, established from
`git remote -v` (`origin` = `TraiNguyenVan/EconomyCraft`, `upstream` = `PhilipB06/EconomyCraft`).

Link strategy per context:
- **README → wiki page**: absolute URL to this fork's wiki. `README.md:25`'s repo-relative `wiki/Tolls.md`
  cannot work, because a GitHub wiki page is not reachable by a repository-relative path.
- **Within `wiki/`**: relative links, as `_Sidebar.md` already does correctly. Do **not** convert these to
  absolute URLs — they are correct and absolute would be a regression.
- **`wiki/Home.md:30-34`**: convert from upstream absolute URLs to relative, matching `_Sidebar.md`.
- **Upstream attribution**: `README.md:6` and `:475` are correct and MUST be preserved. The fork relationship is
  real and the licence requires the notice.

**Correction to the drift inventory**: an earlier pass flagged the shipped MOTD default
(`config.json:249`, pointing at `TraiNguyenVan/EconomyCraft/issues`) as a third, inconsistent repository
identity. It is **correct**. Only the upstream *wiki* links are defects. The MOTD requires no change.

**Rationale**: Getting this wrong in either direction is costly — linking to the fork's own nonexistent wiki
leads readers to a 404, while removing the upstream attribution would break GPL-3.0 notice obligations.

---

## R6 — Stating the wiki's language policy where it will be read

**Question**: FR-017 makes the Vietnamese-player / English-integrator split a policy. Where must that policy
be written down for it to have effect?

**Decision**: State it in **two** places, because the two audiences that need it read different files.
- `wiki/_Sidebar.md`, next to the two existing sections — this is where a reader sees the split enacted.
- `CONTRIBUTING`-adjacent guidance in the repository, or failing that an explicit note in the README's
  documentation pointer — because a *contributor* choosing a page's language reads the README and the
  constitution, not the wiki sidebar.

Concretely, the policy is: a page written for players is written in Vietnamese; a page written for
integrators is written in English; each page is exactly one language; player-facing pages carry no code.
The last clause is not invented for this feature — it is an existing recorded decision (originally
`TODO.md:119`, decision D18) that `wiki/Tolls.md:36-48` currently violates by embedding a Gradle command
and test-class internals in a player page.

**Rationale**: A policy documented only where its subject matter appears is a policy contributors will not
find before writing a page in the wrong language.

**Alternatives considered**:
- *Constitution only.* Rejected: the constitution governs contributors, but the concrete per-page mapping is
  most legible in the sidebar itself.
- *Sidebar only.* Rejected: contributors never read the sidebar before choosing a page's language.

---

## R7 — Handling the `## Unreleased` duplication in the change log

**Question**: `CHANGELOG.md` has four separate `## Unreleased` headings. Why does this matter beyond tidiness,
and what is the target shape?

**Decision**: Collapse to a single `## Unreleased` heading whose content is organised by subsection, and
preserve every entry. Target shape follows the file's own stated purpose at `CHANGELOG.md:3-5`: the release
workflow sets `changelog-file: CHANGELOG.md` (`.github/workflows/release.yml:201`), so this file *is* the
release notes body.

This is a **functional** concern, not cosmetic. With four identically named sections, a tool or a reader
that takes "the Unreleased section" takes only the first and silently drops three entries' worth of release
notes.

Context established: `git tag` ends at `1.9.0` while `mod_version = 1.10.0`, so all four sections are
accumulating toward the same unreleased version.

**Rationale**: Stating the consequence is what justifies the effort. It converts "tidy up the changelog" into
"stop losing release notes", which is the kind of argument that survives a busy maintainer.

**Alternatives considered**:
- *Leave the sections and only fix prose.* Rejected: the duplicate heading is the actual defect.
- *Rename the sections to distinguish them.* Rejected: invent structure the release tooling does not
  understand, for no gain over subsections.

---

## R8 — Constitution Check cannot be evaluated as-is

**Question**: The plan template gates Phase 0 on a Constitution Check. `.specify/memory/constitution.md`
is an unmodified blank template — five placeholder principle names, three placeholder sections, and a
placeholder governance block. What is the correct handling?

**Decision**: Do not fabricate a passing gate. Record the gate as **not evaluable**, and derive the project's
*de facto* principles from code and from the ground rules that are about to be deleted, so that the rest of
the plan has something concrete to check against. Then treat filling the constitution (FR-015) as a
first-class deliverable of this feature rather than an assumed precondition.

**De facto principles recoverable from the codebase**, each independently verifiable:

| # | Principle | Verifiable by |
|---|---|---|
| 1 | Server-side only; no client mod, no custom packets; UI is vanilla menus | `onInitializeClient()` empty; zero custom payloads; 3 Fabric-only mixins |
| 2 | Public API entry points keep the `requireServerThread()` contract | `EconomyCraftApiImpl` |
| 3 | Money moves only through the mutation engine, asymmetric form; levies are burned, never credited | `EconomyManager.transferMoney` |
| 4 | Every levy or rebate must register its source, or leaderboards and the scoreboard are corrupted | `FISCAL_SOURCES` |
| 5 | Config is the only tuning surface; no hard-coded literals in gameplay classes; new numeric keys use clamp-and-warn validation | `EconomyConfig` |
| 6 | Persistence is Gson JSON via `AsyncFileWriter`; never NBT | no NBT usage anywhere |
| 7 | Vanilla APIs that differ across targets go in a compat fork | `util/*Compat.java`, source-set split |
| 8 | Mutation source names follow a fixed charset and namespace | `MutationSource` |

**Rationale**: Eight of these come verbatim from `TODO.md:24-43`, the file this feature deletes. Principle 1
is also load-bearing for the documentation itself — it is why `wiki/Tolls.md:36-48` is a defect.

**Alternatives considered**:
- *Treat the blank constitution as "no constraints", so everything passes.* Rejected: that silently
  authorises work that violates principles 1-8, and it would produce a plan that looks gated but is not.
- *Stop and ask the user to fill the constitution first.* Rejected: filling it is FR-015, a deliverable of
  this very feature. Blocking on it would make the feature impossible to plan.

---

## Open items carried into Phase 1

| Item | Status | Handling |
|---|---|---|
| Gossip model identifier `gemini-3.8-flash` could not be confirmed to exist | Unresolved by design | Document the value as shipped. Whether it is correct is a code question outside FR-001. Recorded, not guessed. |
| ShopGuard is an external Fabric-only mod referenced by the docs | Depends on an external repository not present here | Document the dependency as stated; make no claim about ShopGuard's current state. |
| Whether Spec Kit regenerates `.specify/templates/*` | Unknown | Flagged in the spec's assumptions. Affects durability of that work, not its correctness. |