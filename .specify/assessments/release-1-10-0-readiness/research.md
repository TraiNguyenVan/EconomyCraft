# Idea Research: Release readiness for the next version

- **Slug**: release-1-10-0-readiness
- **Created**: 2026-10-12
- **Evidence confidence (overall)**: **high** for everything observable inside the repository, in the build, and
  in the public store APIs; **low** for publishing identity (token scope) and for maintainer intent, both of
  which are marked `[NEEDS CLARIFICATION]`.

Supporting evidence files (same directory): `evidence/release-mechanics.md`,
`evidence/build-matrix.md`, `evidence/changelog-vs-code.md`, `evidence/docs-vs-code.md`,
`evidence/code-health.md`. Every claim below carries a source; unsourced statements are tagged `ASSUMPTION`.

## Users & Demand

- The consumers of a release are server owners and players of the public Modrinth/CurseForge project: 192
  published versions, the newest being `1.10.0` (10 files, 2026-09-15 and 2026-09-17). [source:
  `api.modrinth.com/v2/project/H4o5bO7g/version`; `curseforge.com/api/v1/mods/1321557/files`] (confidence:
  high, cited)
- Demand *for this tree* is not observable: the repository has no open issues, no open pull requests, no
  discussions (`has_discussions: false`), and no workflow runs at all. The release is therefore
  maintainer-initiated, not user-requested. [source: `gh api repos/TraiNguyenVan/EconomyCraft/issues`,
  `…/pulls`, `…/actions/runs`] (confidence: high, cited)
- What users are missing is large: this tree is a superset of upstream `1.10.0` by 286 tracked files, and
  nothing from it has ever been published (the repository was created 2026-09-28, after both `1.10.0`
  publication dates). [source: `evidence/release-mechanics.md` §1.2; `git ls-files | wc -l` = 422 vs 136 in
  upstream] (confidence: high, cited)
- The tree's own documentation treats the feature set as shipped and user-visible: `README.md` documents
  contracts (`README.md:76-84`) and quests (`README.md:153-160`), and `wiki/` carries 17 pages including
  `wiki/Contracts.md`, `wiki/Server-Quests.md`, `wiki/Tolls.md`, `wiki/Factions.md`,
  `wiki/Professions.md`. [source: `ls wiki/`] (confidence: high, cited)

## Prior Art

- **The same release workflow has already shipped this mod five times.** Upstream `PhilipB06/EconomyCraft`
  carries a byte-identical `.github/workflows/release.yml` (verified with `diff`), and its release cadence was
  1.8.0 (2026-08-19) → 1.9.0 (2026-08-29) → 1.10.0 (2026-09-15 for four targets, 2026-09-17 for 26.3). The
  release path, the store ids, the file-naming scheme and the Java-21-runner + toolchain-provisioning
  arrangement are therefore all proven in practice. [source: `evidence/release-mechanics.md` §2; Modrinth
  version list] (confidence: high, cited)
- **The upstream `1.10.0` tag is the version this tree still declares**, and upstream left
  `mod_version = 1.10.0` in place after publishing it (`/tmp/upstream` clone: `gradle.properties`). The fork
  inherited the number and never bumped it. [source: `gradle.properties:6`; upstream clone] (confidence: high,
  cited)
- **This tree's changelog records a build-then-remove cycle that never reached users**: container locks and
  the public villager-gossip path were added and then removed inside `## Unreleased`
  (`CHANGELOG.md:125-142`, `CHANGELOG.md:42-68`), and upstream `1.10.0` has neither feature (no
  `gemini_gossip` section, no container-lock code). [source: `evidence/release-mechanics.md` §1.2] (confidence:
  high, cited)
- The repository has a prior assessment in the same pipeline
  (`.specify/assessments/agent-switch-generated-files/`), so this assessment follows an established local
  pattern rather than introducing a new one. [source: `ls .specify/assessments/`] (confidence: high, cited)

## Market & Context

- **The cost of doing nothing is visible but bounded.** Nothing of this tree ships, so users stay on
  upstream's `1.10.0` build, which lacks contracts, quests, tolls, factions, professions, the SQLite
  persistence seam and the AI villager dialogue work. [source: `evidence/release-mechanics.md` §1.2]
  (confidence: high, cited)
- **The cost of publishing carelessly is asymmetric.** The store projects are shared with upstream: project
  `H4o5bO7g` is owned by Modrinth team `x3yA59pd` whose only member is `ReaZip`, while this repository is
  owned by `TraiNguyenVan`. Publishing a 286-file-divergent tree into that project's version list, under a
  version number that project already has, is a public-facing action whose blast radius is the upstream
  project page. [source: `gh api repos/…`; `api.modrinth.com/v2/team/x3yA59pd/members`] (confidence: high,
  cited)
- **The public wiki cannot follow this tree.** `has_wiki` is `false` on this repository (true upstream), the
  wiki is a separate clone that contributors copy pages into by hand
  (`docs/contributing/documentation.md:27-32`), and there is no sync workflow. Eleven wiki pages exist only in
  this tree (`Contracts.md`, `Server-Quests.md`, `Tolls.md`, `Factions.md`, `Professions.md`,
  `Choosing-a-Party-and-Profession.md`, `Auctions-Orders-and-Offers.md`,
  `Balances-Daily-Rewards-and-Leaderboards.md`, `Server-Administration.md`, `Shop-and-Selling.md`,
  `Villager-Dialogue.md`). [source: `evidence/docs-vs-code.md` §6; `diff` against upstream wiki] (confidence:
  high, cited)

## Data & Constraints

### D1. The declared version number is already taken

| Fact | Value | Source |
|---|---|---|
| Version in the tree | `mod_version = 1.10.0` | `gradle.properties:6` |
| Tags in this repository | none (`git tag` empty, `git describe` → bare commit) | local git; `gh api …/tags` |
| Upstream tag | `1.10.0`, 2026-09-17, commit `5a8d1ec` | upstream clone |
| Modrinth files at `1.10.0` | 10 (5 targets × 2 loaders), 2026-09-15 / 2026-09-17 | `api.modrinth.com/v2/project/H4o5bO7g/version` |
| CurseForge files at `1.10.0` | 10, file names identical to this build's output | `curseforge.com/api/v1/mods/1321557/files` |
| This build's output names | `economycraft-fabric-1.10.0_26.3.jar`, `economycraft-neoforge-1.10.0_26.3.jar` | local build, `build/fabric/26.3/`, `build/neoforge/26.3/` |

Confidence: high, cited. The exact rejection behaviour of the stores for a duplicate version number is
`[UNVERIFIED]` — it cannot be exercised without publish tokens. [source: `evidence/release-mechanics.md` §1.1]

### D2. Only one of the five declared targets compiles

The release path was executed per target and per loader (10 legs). Results: 26.3 Fabric and NeoForge **pass**;
26.2 Fabric and NeoForge **fail**; 1.21.11 Fabric and NeoForge **fail**; 1.21.1 Fabric and NeoForge **fail**.
All failures are `:common:compileJava` errors in the **shared** source set, for symbols that exist in only one
era: `net.minecraft.world.level.block.BonemealSource` (`profession/FarmerEffects.java:19,80,111`),
`net.minecraft.util.Prediction` (`util/BookInputUi.java:22,192`), `Optional<T>` vs `ChatFormatting`
(`tag/TagDisplayService.java:364`), and the 1.21.1 villager package layout
(`profession/MerchantEffects.java:7-9`, `profession/ProfessionHooks.java:11`). Every one of those files is an
addition of this tree — upstream `1.10.0` published all five targets successfully from its own sources.
[source: `evidence/build-matrix.md`] (confidence: high, cited)

This matches the tree's own admission, buried in the changelog: "The faction and profession system targets
Minecraft 26.3 only" (`CHANGELOG.md:321-326`), and it contradicts `AGENTS.md` ("Supported targets are
1.21.1 … 26.2 and 26.3") as well as the workflow's five release toggles. [source:
`evidence/changelog-vs-code.md` A2] (confidence: high, cited)

### D3. The release path runs no tests, and nothing runs on push or PR

`:common:test` passes for the default 26.3 target under both documented commands, and the root
`./gradlew build` executes `:common:test`. But the release legs build `:fabric:build` / `:neoforge:build`,
where `:fabric:test` reports `NO-SOURCE` and `:common:test` never runs (verified: zero `:common:test` matches in
a release-leg log). There is no `push`/`pull_request` workflow, and `actions/runs` is empty. [source:
`evidence/build-matrix.md`; `evidence/release-mechanics.md` §2] (confidence: high, cited)

### D4. Workflow failure semantics protect against a partial publish

The `release` job declares `needs: [setup, build]`, and the `build` matrix uses `fail-fast: false`
(`.github/workflows/release.yml:104-113`, `:141`). Therefore: if *any* selected target×loader leg fails, the
publish job never starts and nothing is published; if only 26.3 is selected, both of its legs build and only
its two publish legs run. [source: `.github/workflows/release.yml`; local leg results] (confidence: high,
cited — a reading of the workflow, corroborated by the leg results)


### D5. The changelog is the release body, and it is not release-shaped

`mc-publish`'s `changelog-file` is a glob to a file; the whole file becomes the published changelog
(Kir-Antipov/mc-publish README, `changelog-file`). `CHANGELOG.md` is 326 lines with exactly one `##` heading,
`## Unreleased` (`CHANGELOG.md:6`), plus internal sections (`### Known gaps` `CHANGELOG.md:177`,
`### Design decisions taken after the spec` `CHANGELOG.md:282`). Its content is largely accurate (17 of 20
audited claims verified true, 1 wrong, 2 incomplete) but it omits shipped features (Server Quests, contract
payout taxation, four contract subcommands) and names a command that does not exist (`/eco party reset`,
`CHANGELOG.md:84`). [source: `evidence/changelog-vs-code.md`] (confidence: high, cited)

### D6. Admin-facing documentation contradicts the shipped persistence model

`README.md:262-291` still describes ~22 per-feature JSON files and a separate `villagers.db`; the tree writes
one `data/economycraft.db`, with legacy JSON imported once and archived. The wiki says nothing about it. Four
further README statements are contradicted by code (clamp range, `shop.json` migration target, `/eco import`
registration condition, gossip prompt default). [source: `evidence/docs-vs-code.md` D1-D9] (confidence: high,
cited)

### D7. Repository invariants are mostly respected; one public-API contract is broken

Every trade-tax call site routes through `tax/TaxPolicy` + `TaxScope` (with a test that scans for stray
`taxRate` mentions), every fiscal levy/rebate is registered in `FISCAL_SOURCES`, no mod state uses NBT, and
mutation-source naming holds. One **violation**: `FactionApiImpl.defaultFactionId()` returns without calling
`requireServerThread()` (`api/v1/EconomyCraftApiBootstrap.java:228-230`), against constitution principle II and
`api/v1/FactionApi.java:18`. Notable risks: a second `EconomyDatabase` connection in `TollManager` that escapes
`EconomyManager`'s save transaction; placeholder display helpers that create balances on read
(`util/PlaceholderValues.java:12-22`); escrow-refund sources counted as "earned" in leaderboards
(`EconomyManager.java:606-621`); three Fabric-only `&x` mixins with no recorded NeoForge decision. [source:
`evidence/code-health.md`] (confidence: high, cited)

### D8. Release-side surfaces that exist and are consistent

LICENSE (GPL-3.0) matches both loader metadata; the mod icon is present and referenced; the mod version is
injected from Gradle in both `fabric.mod.json:4` and `neoforge.mods.toml:6`; the workflow's task-name
derivation matches the generated Gradle task names (`26.1.02`, `1.21.01`, `26.2`, `26.3`); and the CI Java-21
runner plus `foojay-resolver-convention` provisioning is proven by the upstream publications. [source:
`evidence/release-mechanics.md` §2, §4] (confidence: high, cited)



## Evidence Against the Idea

- **A five-target release cannot happen today.** Four of the five declared targets do not compile
  (`evidence/build-matrix.md`), so "release the next version" is not one decision: it is either a port effort
  or a scope reduction to 26.3. (confidence: high, cited)
- **The declared version number is unusable.** Publishing `1.10.0` would target a version the store projects
  already have, with identical file names. Whatever the stores answer, the number must change, which also
  invalidates `AGENTS.md:79`'s "last tag `1.9.0`" statement. (confidence: high, cited)
- **The release notes are not writable from the changelog as-is.** No version heading; internal design and
  "known gaps" prose would publish; churn users never saw (container locks, public gossip) is written as if it
  were a removal; at least one shipped feature is missing. (confidence: high, cited)
- **An upgrade would contradict the documentation.** The persistence model changed; admins following
  `README.md:262-291` will look for files that no longer exist, and this repository's wiki cannot receive the
  11 new pages. (confidence: high, cited)
- **Nothing verifies the release but the release itself.** No CI on push/PR and no tests on the release path
  mean the first end-to-end verification of a release is the dispatch that publishes it. (confidence: high,
  cited)
- **Governance risk.** The workflow publishes into store projects owned by `ReaZip` from a repository owned by
  `TraiNguyenVan`; this fork has no tags and no release history of its own, so publishing there is a public act
  on another maintainer's project page. Whether that is intended is `[NEEDS CLARIFICATION]`. (confidence:
  medium, cited)

## Gaps & Open Questions

- [NEEDS CLARIFICATION: which version number should the release carry? Any number strictly above the published
  `1.10.0` is publishable; the size of the delta (new features, removed features, changed tax semantics) makes
  `1.11.0` or `2.0.0` the candidates, and the choice is a maintainer call.]
- [NEEDS CLARIFICATION: which Minecraft targets ship? The tree's own changelog says faction/profession are
  26.3-only, so a 26.3-only scope is coherent; porting to the other four is a separate workstream.]
- [NEEDS CLARIFICATION: do the repository's Actions secrets hold tokens with write access to Modrinth
  `H4o5bO7g` / CurseForge `1321557`? The API refuses to answer (403 on `/actions/secrets` and
  `/actions/permissions`).]
- [NEEDS CLARIFICATION: should this repository publish into the upstream store projects at all, or to its own
  project / a fork-specific version channel?]
- [NEEDS CLARIFICATION: who writes and owns the release notes, and is a release runbook wanted in
  `docs/contributing/`?]
- [NEEDS CLARIFICATION: is the Fabric-only `&x` mixin set (signs/anvil/books) an accepted NeoForge gap, or
  should it be ported?] (from `evidence/code-health.md` R5)


## Sources

- `intake.md`, `evidence/release-mechanics.md`, `evidence/build-matrix.md`, `evidence/changelog-vs-code.md`,
  `evidence/docs-vs-code.md`, `evidence/code-health.md` (host: local repository; policy: not applicable)
- Repository files cited inline (`gradle.properties`, `build.gradle`, `AGENTS.md`,
  `.specify/memory/constitution.md`, `CHANGELOG.md`, `README.md`, `wiki/`, `docs/contributing/`,
  `.github/workflows/release.yml`, `common/src/**`, `fabric/src/**`, `neoforge/src/**`)
- GitHub REST API via `gh`: `repos/TraiNguyenVan/EconomyCraft` (repository metadata, tags, releases, pulls,
  issues, workflow list, action runs) and `repos/PhilipB06/EconomyCraft` (parent repository, tags, workflow
  file) (host: api.github.com, policy: allowlisted)
- Modrinth API: `project/H4o5bO7g`, `project/H4o5bO7g/version`, `team/x3yA59pd/members` (host:
  api.modrinth.com, policy: allowlisted)
- CurseForge public files API: `www.curseforge.com/api/v1/mods/1321557/files` (host: www.curseforge.com,
  policy: allowlisted)
- mc-publish documentation: `raw.githubusercontent.com/Kir-Antipov/mc-publish/master/README.md` (host:
  raw.githubusercontent.com, policy: allowlisted)
- Local toolchain: Temurin JDK 21.0.12.1+1 and 25.0.4.1+1, Gradle 9.7.1 wrapper (host: local sandbox; policy:
  not applicable)
