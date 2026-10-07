# Contract: Project Constitution

**Feature**: `001-refresh-all-documents` | **Applies to**: `.specify/memory/constitution.md`
**Requirements**: FR-015, FR-016, FR-019 | **Criteria**: SC-010, SC-012

---

## 1. Why this is a contract and not a writing task

The constitution is currently an unmodified blank template: five placeholder principle names, three
placeholder sections, a placeholder governance block, a placeholder version line. It states nothing.

Under Q1's "both" answer it entered scope. But filling it is the highest-risk item in the feature for one
specific reason: **the material for it is the plan document being deleted.**

`TODO.md:24-43` (§1 "Ground rules") and `TODO.md:47-70` (§2 "Architecture baseline") are the only records of
constraints the codebase actively enforces. Deleting `TODO.md` without migrating them destroys that knowledge.
Migrating them *without re-verifying them* promotes stale claims into a document with higher authority than
the one they currently sit in.

This contract therefore exists to make both failure modes impossible.

---

## 2. Content requirements

### C1 — No placeholder text survives
The constitution MUST contain no placeholder token in the form `[UPPER_SNAKE_CASE]` or `[bracketed example]`.
A reader must not be able to mistake unfilled scaffolding for policy.

### C2 — Every principle is verifiable against code
Each principle MUST be checkable by reading the repository. A principle that cannot be verified is an
aspiration, and an aspiration in a constitution is worse than a blank constitution because it will be cited
as though it were enforced.

### C3 — No principle may be invented
Every principle MUST correspond to something the project already practises or its code already enforces.
FR-015 forbids this explicitly. This rules out plausible-sounding additions the project does not follow.

### C4 — Version metadata is real
The template's version line carries `[CONSTITUTION_VERSION]`, `[RATIFICATION_DATE]` and `[LAST_AMENDED_DATE]`.
The first version MUST be recorded as `1.0.0` with the date it is written. Do not claim a ratification date
that never occurred.

---

## 3. Required principles

Eight principles, recovered from `TODO.md:24-43` §1 and independently verifiable. Each MUST be re-verified
against the code before being written.

| # | Principle | Verification basis | Source |
|---|---|---|---|
| 1 | **Server-side only.** No client mod, no custom packets, no registered `MenuType`. UI is vanilla server-side containers | `onInitializeClient()` is empty; zero custom payloads; 3 Fabric-only mixins | §1 rule 1 |
| 2 | **Thread contract.** Public API entry points call `requireServerThread()` | `EconomyCraftApiImpl` | §1 rule 2 |
| 3 | **Money movement.** Asymmetric transfer only; levies are burned, never credited. Never `addMoney` to a tax sink | `EconomyManager.transferMoney` | §1 rule 3 |
| 4 | **Fiscal sources must register.** A new levy or rebate source MUST be added to `FISCAL_SOURCES` or it corrupts leaderboard `earned`/`spent` and the scoreboard | `EconomyManager.java:63` | §1 rule 5 |
| 5 | **Configuration is the only tuning surface.** No hard-coded literals in gameplay classes; new numeric keys use clamp-and-warn validation | `EconomyConfig` | §1 rule 7 |
| 6 | **Persistence.** Gson JSON via `AsyncFileWriter`; NBT is never used; save on server stop | no NBT usage anywhere | §1 rule 8 |
| 7 | **Compat forks.** Any vanilla API differing across platform targets goes in a compat fork following `util/*Compat.java` | source-set split | §2 |
| 8 | **Mutation source naming.** Lowercase, namespace `[a-z0-9._-]+`, reason `[a-z0-9/._-]+`, exactly one `:`; `economycraft:` namespace except ShopGuard's `shopguard:` | `MutationSource` | §1 rule 4 |

Two further constraints from §1 are **not** principles but belong in a constraints section:

- Balance changes reach players only through the mutation engine, so `/transactions`, the webhook and the
  daily log keep working.
- Reserve synchronous writes for state that must survive an immediate crash.

### Note on principle 4 — a rule with teeth

This one exists because of a specific past failure mode: an unregistered levy source silently inflates
leaderboards and the scoreboard. It is stated in the constitution with that consequence attached, because a
rule without its consequence is the kind of rule that gets forgotten.

---

## 4. Architecture baseline — mandatory re-verification

`TODO.md:47-70` §2 contains 18 rows describing the codebase. It is **not** current. It MUST be re-verified
row by row before any of it is written into the constitution.

### Known-stale rows — must be corrected, not copied

Six rows, all verified against the code on 2026-10-07. `AGENTS.md` now carries the corrected versions of
rows 1-6; see `AGENTS.md` §2, §3 and §4.

| # | §2 claim | Reality | Correct form |
|---|---|---|---|
| 1 | "Online-time tracking — **Does not exist**" | `online_time.json` and `cooldowns.json` ship | Online-time tracking exists and persists |
| 2 | "Villager trading — **Not in EconomyCraft at all.** Zero `Merchant`/`Villager` references." | Gossip is built on villager dialogue; `gossip/GossipConfig.java` and `/eco gossip dialogue [prof]` exist | Villager dialogue exists via the gossip subsystem |
| 3 | "**18 independent sites**" computing tax | Self-corrected to 19 at `TODO.md:319`; §2 never updated | Verify the current count, then state it |
| 4 | "`api/v1`, **16 types**" | 18 source files; `FactionApi`/`FactionIds` added since | 18 types |
| 5 | "Mixins: **3, Fabric only. NeoForge has none** — an existing parity gap" | **Fabric 12, NeoForge 10.** Both loaders have mixins; the asymmetry is in the three toll mixins | 12 Fabric / 10 NeoForge |
| 6 | "`FISCAL_SOURCES` (`EconomyManager.java:63`)" | `EconomyManager.java:81`, and the file is at the **package root**, not `util/` | `common/src/main/java/com/reazip/economycraft/EconomyManager.java:81` |

Rows 5 and 6 were found only while verifying claims for `AGENTS.md` — after the contract was first written.
That is the re-verification obligation working as intended, and it is why §2 cannot be copied wholesale.

### The remaining rows, and why they matter

These are the facts a new contributor most needs and which exist **nowhere else**:

| Row | Content | Why it is load-bearing |
|---|---|---|
| Build | Architectury multi-project; 5 platform targets; Java 21 for 1.21.x, 25 for 26.x | Nobody can build without this |
| Source sets | `main` + `modern`/`legacy121` + `unobfuscated`/`obfuscated`; `test26_3` for 26.3 | Where does new code go? |
| Public API | `api/v1` accessed via `EconomyCraftApi.get(server)`; v1 extended additively | Precedent for API changes |
| Persistence | Gson JSON only, never NBT | Principle 6's basis |
| Networking | None. Zero custom payloads. Server→client is vanilla chat, action bar, particles, menus | Principle 1's basis |
| Land claims | Not in EconomyCraft; live in ShopGuard, which depends on EconomyCraft one-way | Prevents a whole class of wrong assumption |
| Mixins | 3, Fabric only | Known parity gap |
| Tax incidence | The `/ah` buyer pays the tax; there is no seller-side listing fee | Commonly assumed the other way |

### C5 — Re-verification obligation
Every row marked for migration MUST be re-checked against the current code. Rows that cannot be verified
MUST be omitted rather than copied. A row carried forward unverified is a defect introduced by this feature.

---

## 5. What must NOT be migrated

| Content | Reason |
|---|---|
| Phases 0-11, `P<n>-T<n>` task records | Completed effort. Already in `CHANGELOG.md`. A constitution is not a progress log |
| §8 traceability matrix | Completed effort |
| §5 blockers, §7 status | Completed effort |
| `D14`-style decision IDs referenced as if live | Internal process language (FR-011). `README.md:230` currently leaks one of these into a user-facing table |
| Test counts | Per-target, so any single number is wrong for 4 of 5 targets (research.md R2) |
| Any statement about ShopGuard's current behaviour | Not present in this repository; unverifiable here |

---

## 6. Governance section

The template's governance block asks how the constitution is enforced. The honest answer for this project,
given what it already does:

- FR-001 and SC-008 are the enforcement mechanism for this feature: the documentation refresh changes no
  runtime behavior, confirmed by the existing test suite passing unchanged.
- Principle 4 is enforced by a specific code location (`FISCAL_SOURCES`) — cite it.
- Principle 2 is enforced by `requireServerThread()` on every public API path.

Governance rules MUST be written in terms of mechanisms that exist, not review processes that are aspirational.

---

## 7. Validation

| Rule | Test |
|---|---|
| C1 no placeholders | grep for `\[UPPER_SNAKE_CASE]` in the constitution — expect zero |
| C2 verifiable principles | each principle cites a file, class or observable behavior |
| C3 nothing invented | every principle traceable to `TODO.md` §1 or to verifiable code |
| C4 version real | version `1.0.0`, date recorded, no fabricated ratification |
| C5 §2 re-verified | all 4 known-stale rows corrected; unverifiable rows omitted |
| §1 fully migrated | all 8 rules present in the constitution |
| Nothing from §5 present | grep for `P<n>-T<n>`, `Phase N`, `D14` — expect zero |