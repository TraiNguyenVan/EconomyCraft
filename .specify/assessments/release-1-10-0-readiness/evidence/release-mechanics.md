# Evidence: release mechanics

Collected 2026-10-12 by the assessment lead. Every claim is either a repository citation
(`path:line`), a GitHub/Modrinth/CurseForge API observation, or a local command result. Claims that could
not be verified are marked `[UNVERIFIED]`.

## 1. Version identity

- The tree declares `mod_version = 1.10.0` (`gradle.properties:6`); this is the only version source for the
  build (`build.gradle:16` sets every project's `version`) and for the release workflow, which greps
  `mod_version` out of `gradle.properties` (`.github/workflows/release.yml:52`).
- `AGENTS.md:79` records the state as "`mod_version 1.10.0`, unreleased; last tag `1.9.0`", verified
  2026-10-07.
- This repository has **no tags and no GitHub releases**: `gh api repos/TraiNguyenVan/EconomyCraft/tags`
  returns an empty list, `git tag` is empty, and `git describe --tags --always` returns the bare commit
  `bc9d48c`. The release workflow prints the tag on `HEAD` and would therefore report `(none)`
  (`.github/workflows/release.yml:55-57`).
- The upstream repository `PhilipB06/EconomyCraft` (the fork's parent, per the repository description) **does
  have a `1.10.0` tag**, dated 2026-09-17 on commit `5a8d1ec` ("port to 26.3"), and its `gradle.properties`
  also carries `mod_version = 1.10.0`. Tag list: `1.10.0, 1.9.0, 1.8.0, 1.7.1, 1.7.0, …`.

### 1.1 The version number is already published

The release workflow publishes to Modrinth project `H4o5bO7g` and CurseForge project `1321557`
(`.github/workflows/release.yml:203-204`). Both projects already carry `1.10.0` for **all five Minecraft
targets on both loaders**:

| Observation | Value |
|---|---|
| Modrinth project | `H4o5bO7g`, slug `economycraft`, team owner `ReaZip` |
| Modrinth versions named `1.10.0` | 10 (5 targets × 2 loaders), published 2026-09-15 (1.21.1, 1.21.11, 26.1.2, 26.2) and 2026-09-17 (26.3) |
| CurseForge files named `1.10.0` | 10, with file names identical to this build's output: `economycraft-fabric-1.10.0_26.3.jar`, `economycraft-neoforge-1.10.0_26.3.jar`, … |
| Highest published version | `1.10.0` (the project has 192 versions in total; history runs 1.0.0 → 1.10.0) |

The published file names are exactly what this tree produces: a local build of the release path emitted
`build/fabric/26.3/economycraft-fabric-1.10.0_26.3.jar` and
`build/neoforge/26.3/economycraft-neoforge-1.10.0_26.3.jar`.

**Consequence.** Dispatching the workflow as-is asks both platforms to publish a version number that already
exists on the target project. `[UNVERIFIED]` the exact rejection behaviour (whether Modrinth answers with a
duplicate-version error, and whether mc-publish aborts or overwrites) — it cannot be tested without publish
tokens. What is verified is that the version identifier and every published file name are already taken.

### 1.2 The fork's code has never been published

The repository was created 2026-09-28 (`gh api repos/TraiNguyenVan/EconomyCraft` → `createdAt`), i.e. after
both `1.10.0` publication dates, so the published `1.10.0` artifacts cannot contain any of this tree's work.
The tree is also a large superset of upstream `1.10.0`:

- 422 tracked files here vs 136 in upstream `1.10.0`.
- Zero upstream files were deleted; 49 upstream files were modified; whole new packages exist only here:
  `contracts/`, `db/`, `faction/`, `fiscal/`, `gossip/`, `integration/`, `motd/`, `negotiation/`,
  `profession/`, `quests/`, `tag/`, `tax/`, `time/`, `config/`, plus `TollManager`, `TollHud`, `TollUi`,
  `AdminQuestsUi`, `util/AmpersandFormat`, `util/BookInputUi`, `util/TimeFormat`, `api/v1/FactionApi`,
  `api/v1/FactionIds`, the `common/src/test` and `common/src/test26_3` source sets, both loaders' mixin
  sets, `AGENTS.md` and `docs/`.
- Upstream `1.10.0` has no `gemini_gossip` config section and no container-lock code; both are described in
  this tree's changelog as additions that were later removed (`CHANGELOG.md:133`, `CHANGELOG.md:162`). Those
  "removed" entries therefore describe churn no user has ever seen, because nothing from this tree has ever
  shipped.


## 2. Release workflow

- `.github/workflows/release.yml` is the only workflow in the repository, and it is **byte-identical to the
  upstream copy** (verified with `diff` against `PhilipB06/EconomyCraft@main`). It is `workflow_dispatch`
  only — five target toggles (`release_1_21_1`, `release_1_21_11`, `release_26_1_x`, `release_26_2`,
  `release_26_3`) plus `dry_run` (`.github/workflows/release.yml:3-28`).
- The workflow is active: `gh workflow list` reports "Release to Modrinth & CurseForge … active".
- Publish job settings: `version-type: release` (hard-coded), `changelog-file: CHANGELOG.md`,
  `environment: server`, `dependencies: architectury-api | depends`, `files: artifacts/*.jar`,
  `name: EconomyCraft <version> - <loader> - <mc>` (`.github/workflows/release.yml:198-215`).
- Target→task mapping is correct: the workflow pads the patch segment below 10
  (`.github/workflows/release.yml:135-141`), which matches the real task names — a local run confirmed
  `buildAndCopyFabricJars_26.1.02` (not `…_26.01.02`) exists, and `…_1.21.01`, `…_1.21.11`, `…_26.2`,
  `…_26.3` are the generated names (`build.gradle:46-53`, `build.gradle:190`).
- **The release path does not run the test suite.** The release legs build `:fabric:build` /
  `:neoforge:build` (`.github/workflows/release.yml:151-156`); in a local run of
  `buildAndCopyFabricJars_26.3`, `:fabric:test` reported `NO-SOURCE` and `:common:test` never ran (0 matches
  for `:common:test` in that log). The common test suite only runs when the root `build` task is used
  (a local `./gradlew build` did execute `:common:test`).
- **There is no CI gate on push or pull request.** No workflow runs on `push`/`pull_request`, and
  `gh api repos/TraiNguyenVan/EconomyCraft/actions/runs` returns an empty list — the workflow has never been
  run in this repository.
- CI Java version: every matrix leg installs Java 21 (`.github/workflows/release.yml:118-121`), while the
  26.x targets compile with a Java 25 toolchain (`build.gradle:100-108`; the 26.x entries in
  `build.gradle:33-35` set no `javaVersion`, so the default 25 applies). The build only succeeds because
  `settings.gradle` registers `org.gradle.toolchains.foojay-resolver-convention` and auto-provisions a JDK 25.
  Empirically this works in CI: the identical upstream workflow published the 26.3 artifacts on 2026-09-17.
- Publish credentials: `secrets.MODRINTH_PERSONAL_ACCESS_TOKEN` and
  `secrets.CURSEFORGE_PERSONAL_ACCESS_TOKEN` (`.github/workflows/release.yml:206-207`). Presence and scope
  cannot be checked from here — the API answers 403 for both `/actions/secrets` and `/actions/permissions`.
  The Modrinth project's team owner is `ReaZip` while this repository is owned by `TraiNguyenVan`, so
  whether these secrets hold a token with write access to project `H4o5bO7g` / `1321557` is
  `[NEEDS CLARIFICATION]`.

## 3. Changelog as release body

- `mc-publish`'s `changelog-file` is documented as "a glob pointing to the changelog file" — there is no
  section extraction (Kir-Antipov/mc-publish README, `changelog-file` section). The file's whole content
  becomes the published changelog.
- `CHANGELOG.md` is 326 lines, begins with `# Changelog` and a preamble that names the workflow
  (`CHANGELOG.md:1-4`), and contains exactly one version heading: `## Unreleased` (`CHANGELOG.md:6`). The
  rest of the file is a run of `###` sections describing feature groups (factions, professions, quests,
  price offers, tag surfaces) plus sections titled `### Known gaps` and
  `### Design decisions taken after the spec` (`CHANGELOG.md:177`, `CHANGELOG.md:282`).
- Consequence: a release published today would carry the whole file — preamble, internal "known gaps" and
  design-decision prose included — as the Modrinth/CurseForge changelog, with no version heading.

## 4. Repository surfaces a release touches

| Surface | State | Evidence |
|---|---|---|
| LICENSE | GPL-3.0, matches the loader metadata | `LICENSE:1-3`, `fabric/src/main/resources/fabric.mod.json:9`, `neoforge/src/main/resources/META-INF/neoforge.mods.toml:3` |
| Mod icon | present and referenced by both loaders | `common/src/main/resources/assets/economycraft/logo.png`, `fabric.mod.json:6`, `neoforge.mods.toml:8` |
| Mod metadata | version injected from the Gradle version; no version literal | `fabric.mod.json:4`, `neoforge.mods.toml:6` |
| README download surface | no Modrinth/CurseForge project link, no version statement, no install section | `grep -i 'modrinth\|curseforge\|download' README.md` → only the placeholder-api links at `README.md:807` |
| Release runbook | none — `docs/contributing/` holds `architecture.md`, `documentation.md`, `speckit.md`, `tolls.md` only | `ls docs/contributing/` |
| GitHub wiki | **disabled on this repository** (`has_wiki: false`) while the parent repository has it enabled; there is no wiki-sync workflow | `gh api repos/TraiNguyenVan/EconomyCraft`; `ls .github/workflows/` |
| Mod metadata description | one line, `Server-side economy mod.` | `fabric.mod.json:7`, `neoforge.mods.toml:9-11` |
| `neoforge.mods.toml` comment | still cites a removed planning document: "Mixins (server-side display hooks; see TODO.md P3-T1)" | `neoforge/src/main/resources/META-INF/neoforge.mods.toml:39` |
