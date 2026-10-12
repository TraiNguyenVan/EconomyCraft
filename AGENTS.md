# AGENTS.md

Operational guidance for agents and contributors. The code is authoritative: if a claim here contradicts
the implementation, correct the guidance.

## Project map

- `api/`: public API v1 (`com.reazip.economycraft.api.v1`).
- `common/`: shared logic and Minecraft compatibility source sets.
- `fabric/`, `neoforge/`: loader entrypoints and loader-specific mixins.
- `wiki/`: content for the separate GitHub wiki repository; see [contributor docs](docs/contributing/documentation.md).
- `.specify/memory/constitution.md`: normative project principles. This file provides operational mechanics.

EconomyCraft is a server-side Architectury mod for Fabric and NeoForge. It must work with an unmodified
vanilla client: no client mod, custom packets, or registered `MenuType`. UI uses vanilla
`AbstractContainerMenu` and `MenuType.GENERIC_9xN`.

## Rules for changes

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
  logic. Minecraft 26.3 is the only supported target, so the version forks are the `unobfuscated` and
  `modern` source sets beside `main`; loader differences stay in the `fabric/` and `neoforge/` modules.
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

Minecraft 26.3 (Java 25) is the only supported target, for both Fabric and NeoForge. There is no version
flag to pass: `minecraft_version` is fixed in `gradle.properties` and `build.gradle`. 26.x is
unobfuscated, so the build never remaps against Mojang mappings.

```sh
./gradlew build                                   # both loaders
./gradlew -Pfilter_platforms=fabric build         # Fabric only
./gradlew -Pfilter_platforms=neoforge build       # NeoForge only
./gradlew :common:test                            # common-module tests
```

The release path builds and copies a single loader's jar into `build/<platform>/26.3/`:

```sh
./gradlew -Pfilter_platforms=fabric buildAndCopyFabricJars
./gradlew -Pfilter_platforms=neoforge buildAndCopyNeoForgeJars
```

Common tests live in `common/src/test`; the 26.3 integration tests that bootstrap the item component
registries live in `common/src/test26_3`. For the release procedure, see
[release guidance](docs/contributing/release.md).

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

*Verified against the repository on 2026-10-12 (`mod_version 1.10.0`, unreleased; last tag `1.9.0`).*
