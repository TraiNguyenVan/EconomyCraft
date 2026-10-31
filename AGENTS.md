# AGENTS.md

Operational guidance for agents and contributors. The code is authoritative: if a claim here contradicts
the implementation, correct the guidance.

## Project map

- `api/`: public API v1 (`com.reazip.economycraft.api.v1`).
- `common/`: shared logic and Minecraft compatibility source sets.
- `fabric/`, `neoforge/`: loader entrypoints and loader-specific mixins.
- `wiki/`: canonical player, server-owner, and integrator documentation read directly by Docusaurus; see [contributor docs](docs/contributing/documentation.md).
- `.specify/memory/constitution.md`: normative project principles. This file provides operational mechanics.

EconomyCraft is a server-side Architectury mod for Fabric and NeoForge. It must work with an unmodified
vanilla client: no client mod, custom packets, or registered `MenuType`. UI uses vanilla
`AbstractContainerMenu` and `MenuType.GENERIC_9xN`.

## Rules for changes

- **Contributions:** follow the [contribution workflow](docs/contributing/workflow.md) for commit and pull request rules.

- **API thread safety:** every public `api/v1` path must call `requireServerThread()`. The implementation is
  `common/.../api/v1/EconomyCraftApiBootstrap.java`, not an `EconomyCraftApiImpl`.
- **Balance mutations:** route all player-facing balance changes through the asymmetric
  `EconomyManager.transferMoney(from, to, debitAmount, creditAmount, source, detail)`. Levies are burned;
  never credit a tax sink or split a transfer into separate debit and credit operations.
- **Fiscal sources:** add every new levy or rebate to `EconomyManager.FISCAL_SOURCES` or it will affect
  leaderboard and scoreboard totals incorrectly.
- **Mutation source names:** use lowercase `economycraft:` sources, with ShopGuard integration money as the
  exception (`shopguard:`). `MutationSource` validates the namespace, reason, and single colon.
- **Tuning:** put rates, thresholds, weights, durations and colours in `EconomyConfig`; new numeric keys use
  clamp-and-warn validation. Defaults come from `common/src/main/resources/assets/economycraft/config.json`.
- **Persistence:** one SQLite file (`data/economycraft.db` via `db/EconomyDatabase`) — every legacy
  JSON document lives as a Gson-payload row in `documents`, villager gossip keeps relational tables in the
  same file. Writes are synchronous on the server thread; legacy files import once, then archive. Never use
  NBT for mod state.
- **Compatibility:** vanilla API differences belong in matching compat forks, not version checks in shared
  logic. Source sets include `main`, `modern` and `unobfuscated`.
- **Mixins:** Fabric and NeoForge sets intentionally differ. Fabric has toll pressure plate/base plate/hopper
  mixins; NeoForge has `ProfessionBreakMixin`. Make an explicit loader decision for every new hook.
- **ShopGuard:** claims belong to the external Fabric-only ShopGuard mod. EconomyCraft's reflective,
  optional bridge is in `common/.../integration/`; do not move claim logic into EconomyCraft. The Communism
  `Tài trợ` buff is intentionally unimplemented and read-only.
- **Faction/profession invariants:** a player with neither faction nor profession remains exempt and pays the
  pre-update tax. Lockouts use wall-clock time, faction taxes use online time, and effect cooldowns use
  wall-clock time.
- **Existing data:** load existing balance, stats, auction, order and toll files unchanged. Do not add
  `EconomyPaths.DATA_FILES` or `SETTINGS_FILES` entries without considering `/eco import` behavior.
- **Docs:** document only shipped commands, permissions, config keys and API types. Never state raw test
  counts; coverage varies by target. See [documentation guidance](docs/contributing/documentation.md).

## Build and verification

Minecraft 26.3 (Java 25) is the only supported target, for both Fabric and NeoForge. `mcTargets` in
`build.gradle` holds exactly one entry; `-Pminecraft_version=<other>` fails fast rather than compiling
against an unsupported API. Source sets are `main` plus the `modern` and `unobfuscated` compat forks;
`test26_3` adds the 26.3-only tests.

```sh
./gradlew build                          # common tests + both loaders
./gradlew :common:test                   # common-module tests
./gradlew -Pfilter_platforms=fabric :fabric:build
./gradlew -Pfilter_platforms=neoforge :neoforge:build
```

Common tests live in `common/src/test`; 26.3-only tests live in `common/src/test26_3`. CI (`.github/workflows/ci.yml`)
is the build authority: deployable jars come from Actions runs (PRs) or GitHub Releases (`v*` tags). Invoke Gradle
locally only for the exceptional rollback case — expired artifact and no release; the commands above describe what
CI runs.

## High-risk checks

- Route every tax calculation through `tax/TaxPolicy` and `TaxScope`, including displayed quotes and charged
  amounts. The invariant is not mechanically enforced; search for inline tax multiplication.
- A failed debit/refund is not always a no-op: the refund can fail with `MAX_BALANCE_EXCEEDED`. Do not update
  levy or rebate bookkeeping as if a failed debit succeeded.
- When checking a vanilla hook, follow the call chain one level deeper and verify the actual signature and
  checks. The 26.3 deobfuscated sources may be empty, requiring bytecode inspection.
- Display helpers must not create balances. Never conjure an account from an unverified name.
- The `/ah` buyer pays the tax; there is no seller-side listing fee.

For domain-specific verification notes, see [toll contributor guidance](docs/contributing/tolls.md). For
architecture traps and invariants, see [the contributor reference](docs/contributing/architecture.md).
