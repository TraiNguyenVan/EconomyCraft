# EconomyCraft Constitution

Governance for the EconomyCraft repository. These principles are the ones the codebase **already enforces**;
each cites the code that enforces it, so a reader can check it rather than take it on trust.

This document holds normative principles. `AGENTS.md` holds the operational mechanics — build commands, code
citations, the architecture map and the traps. Where the two overlap, this file states *what must be true* and
`AGENTS.md` states *how it is done today*.

**Version**: 1.0.0 | **Last Amended**: 2026-10-07 | **Ratified**: none — this is the first written version; it
was derived from code and from the retired `TODO.md`, not from a signing event.

---

## Core Principles

### I. Server-side only

No client mod, no custom packets, no registered `MenuType`. The entire UI is vanilla server-side
`AbstractContainerMenu` over `MenuType.GENERIC_9xN`, and it must work with an unmodified vanilla client.

Server→client display uses only vanilla channels: chat, the action bar, particles, menus, and one scoreboard
objective for balances.

*Enforced by*: `fabric/src/main/java/com/reazip/economycraft/fabric/client/EconomyCraftFabricClient.java` —
`onInitializeClient()` is empty. Zero custom payload registrations exist anywhere in the tree.

### II. The public API keeps the thread contract

Anything reachable from the public `api/v1` surface calls `requireServerThread()`. This is not advisory: an
off-thread call throws rather than corrupting state.

*Enforced by*: `common/src/main/java/com/reazip/economycraft/api/v1/EconomyCraftApiBootstrap.java:49` — the
implementation behind `EconomyCraftApi.get(server)`. (There is no `EconomyCraftApiImpl`; that name appears in
older drafts only.)

### III. Money moves only through the mutation engine

Balances change through the **asymmetric** transfer form, never as a debit followed by a separate credit.
Levies are **burned, not credited** — never `addMoney` to a tax sink.

*Enforced by*: `common/src/main/java/com/reazip/economycraft/EconomyManager.java:502` —
`transferMoney(from, to, debitAmount, creditAmount, source, detail)`.

**Why it matters**: a debit-then-credit pair can half-complete. The single engine is what keeps `/transactions`,
the webhook and the daily log correct, so no player-facing balance may change by any other route.

### IV. Register every fiscal source

A new levy or rebate **must** be added to `FISCAL_SOURCES`. An unregistered source silently inflates the
Leaderboards' `earned`/`spent` totals and the scoreboard, because those are computed by asking whether a
movement was fiscal rather than income or spending.

*Enforced by*: `common/src/main/java/com/reazip/economycraft/EconomyManager.java:81` — `FISCAL_SOURCES`. Note
the file is at the **package root**, not in `util/`.

### V. Configuration is the only tuning surface

Rates, thresholds, weights, durations and colours go into `EconomyConfig`. No gameplay class contains a
hard-coded tunable literal. A new numeric key uses clamp-and-warn validation: clamp it, log the value and the
bound in one warning line, and keep the clamped value.

*Enforced by*: `common/src/main/java/com/reazip/economycraft/EconomyConfig.java`, which auto-merges new keys
from the bundled default and clamps rather than rejecting.

### VI. Persistence is one SQLite file, never NBT

State persists as Gson payloads in `documents` rows of `data/economycraft.db` through
`db/EconomyDatabase`, written synchronously on the server thread; villager gossip memory keeps
relational tables in the same file. Legacy JSON files and `villagers.db` are imported exactly once,
then archived — the database is the only writer afterwards. Everything is still flushed on
`LifecycleEvent.SERVER_STOPPING` alongside `manager.save()`.

*Enforced by*: `common/src/main/java/com/reazip/economycraft/db/EconomyDatabase.java` and
`common/src/main/java/com/reazip/economycraft/EconomyCraft.java`. No NBT is used for mod state.

### VII. Cross-version APIs go in a compat fork

Any vanilla API that differs across platform targets goes in a compat fork with the same name in each branch,
following the existing `util/*Compat.java` pattern (`PermissionCompat`, `ProfileCompat`,
`FailureSoundCompat`, `CompatMenu`).

*Enforced by*: the source-set split — `main`, plus `modern`/`legacy121` and `unobfuscated`/`obfuscated`. A
second target means a new fork, not an `if` on a Minecraft version inside shared code.

### VIII. Mutation source naming is constrained

`MutationSource` takes a lowercase namespace matching `[a-z0-9._-]+` and a reason matching `[a-z0-9/._-]+`,
with exactly one `:`. Use the `economycraft:` namespace — except ShopGuard integration money, which stays
`shopguard:`.

*Enforced by*: `api/src/main/java/com/reazip/economycraft/api/v1/MutationSource.java`, which validates both
patterns and throws otherwise.

---

## Architecture baseline

The full baseline lives in `AGENTS.md` §3-4, and is deliberately **not** restated here. It is verified against
the code rather than copied, and it will drift as the code changes; a stale copy in a higher-authority document
is worse than a pointer. Read `AGENTS.md` §3-4 for the platform matrix, the source sets, the test commands and
the architecture map.

Two facts a newcomer most needs, stated only because they are counter-intuitive:

- **Land claims are not ours.** They live in ShopGuard, an external Fabric-only mod. EconomyCraft references it
  reflectively, so the dependency stays one-way and optional. See `AGENTS.md` §4.
- **The `/ah` buyer pays the tax.** There is no seller-side listing fee.

## Constraints

- **Public API v1 is extended additively only.** Adding a method is allowed; changing or removing one is not.
  `inflationMultiplier()` and `medianActiveBalance()` are the precedent — both were added for an external
  consumer without touching existing signatures.
- **Mixins are loader-specific and the asymmetry is deliberate.** Fabric and NeoForge mixin sets are not
  mirrors of each other. Adding a mixin to one loader requires an explicit decision about the other; see
  `AGENTS.md` §2.2 for the current sets.
- **Never state a raw test count in a document.** Coverage is per-target, so any single number is wrong for four
  of the five supported targets. Name the source sets and give the reproduction command.
- **Documentation describes what ships.** A feature that is not in the code is not in the wiki or the README,
  and no user-facing document may contain phase numbers, task identifiers or internal decision IDs.

## Governance

This constitution supersedes other written guidance where they conflict. Enforcement is by mechanisms that
already exist, not by review process:

| Principle | How it is actually enforced |
|---|---|
| I, II | Code that throws or is absent — no custom payloads registered; `requireServerThread()` on every public path |
| III, IV | `EconomyManager.transferMoney` is the only balance route; `FISCAL_SOURCES` is an explicit allow-list |
| V | Clamp-and-warn validation on every key, logged at startup |
| VI | `AsyncFileWriter` plus the `SERVER_STOPPING` save |
| VII | The build's source-set split — a version-conditional in shared code has nowhere valid to live |
| VIII | Constructor validation in `MutationSource` |

Amendments require a version bump, a recorded date, and re-verification of every changed principle against the
code. A principle that cannot be verified is an aspiration, and an aspiration here is worse than no principle
at all, because it will be cited as though it were enforced. So: verify, or drop it.

**Version**: 1.0.0 | **Last Amended**: 2026-10-07 | **Ratified**: none
