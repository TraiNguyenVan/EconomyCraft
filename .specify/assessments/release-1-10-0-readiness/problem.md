# Problem Definition: Releasing the next EconomyCraft version

- **Slug**: release-1-10-0-readiness
- **Created**: 2026-10-12
- **Inputs used**: intake.md, research.md

## Problem Statement

This tree holds a large body of finished, user-facing work that has never shipped, yet it cannot be published
as it stands: the version it declares (`1.10.0`) is already published on both store projects for all five
targets, four of the five declared Minecraft targets no longer compile, the changelog that becomes the release
body is not release-shaped, and the admin documentation describes a persistence model the code no longer uses.
The maintainer therefore has no path that is both correct and safe without first deciding scope and version
and preparing the release artifacts.

## Affected Users & Stakeholders

- **Users**: server owners running Fabric or NeoForge — the tree's README and wiki already document contracts,
  quests, tolls, factions and professions, but no published build contains them, so the documentation describes
  software they cannot obtain. [source: research.md §Users & Demand; `evidence/release-mechanics.md` §1.2]
- **Users**: players on those servers — the faction, profession, quest and dialogue surfaces are absent from
  every published build. [source: research.md §Users & Demand]
- **Stakeholders**: this repository's owner — decides the version number, the target scope, and whether the
  release is published at all. [source: research.md §Gaps & Open Questions]
- **Stakeholders**: the upstream project owner (`ReaZip`; Modrinth team `x3yA59pd`, CurseForge `1321557`) — the
  public project page is the blast radius if a divergent tree is published under an existing version number.
  [source: research.md §Market & Context; `evidence/release-mechanics.md` §1.1]
- **Stakeholders**: documentation readers — the 11 wiki pages that exist only here have no publication path
  from this repository (`has_wiki: false`, no sync workflow). [source: `evidence/docs-vs-code.md` §6]
- **Stakeholders**: future maintainers — no release runbook exists, and the only version evidence
  (`AGENTS.md:79`) is already wrong. [source: `evidence/release-mechanics.md` §2, §4]

## Goals

- Fix the release identity: a version number that does not already exist on either store, reflected in
  `gradle.properties` and in the repository's own statements.
- Fix the release scope: the set of Minecraft targets that actually ships, consistent with what compiles.
- Make the release artifacts release-ready: versioned release notes, admin documentation that matches the
  shipped persistence model, and a tag on the released commit.
- Make the release verifiable before it is public: a build/test gate over the shipped targets and a dry run of
  the publish workflow.
- Make the release repeatable: a written runbook covering bump → notes → tag → dry run → publish.

## Non-Goals

- Porting the tree to 1.21.1, 1.21.11, 26.1.2 or 26.2. That is a separate workstream; this assessment only
  decides whether the release waits for it or ships without it.
- Redesigning the mod's features, money engine, tax routing or persistence layer.
- Changing gameplay tuning, config defaults, or store-listing marketing copy.
- Deciding the fork's long-term relationship with the upstream project beyond this release.

## Success Metrics

- A dispatched `dry_run` run completes green for every selected target×loader leg (baseline: 2 of 10 legs
  green — 26.3 on both loaders).
- The published version number does not already exist on either store (baseline: the declared number exists on
  both, for all five targets and both loaders).
- Release notes exist as a versioned section or file with no internal spec identifiers and no internal
  "known gaps" prose (baseline: none; the entire 326-line changelog would publish under the heading
  "Unreleased").
- No admin-facing document contradicts the shipped persistence model (baseline: 9 ranked documentation defects,
  `evidence/docs-vs-code.md` D1-D9).
- The released commit carries a tag (baseline: zero tags in this repository).
- A release runbook exists under `docs/contributing/` (baseline: none).

## Cost of Inaction

Nothing from this tree ships. Users stay on upstream's `1.10.0` build, which lacks contracts, quests, tolls,
factions, professions, the single-file SQLite persistence seam and the AI villager dialogue work — while this
tree's README and wiki already document them, so anyone who finds the repository reads documentation for
software they cannot obtain. The multi-target breakage also grows silently: each new era-specific API call in
shared code widens the gap to the four other declared targets, and because the default target hides it, the
gap is only discoverable by building the other four. [source: research.md §Evidence Against the Idea;
`evidence/build-matrix.md`]

## Open Questions

- [NEEDS CLARIFICATION: which version number should the release carry?]
- [NEEDS CLARIFICATION: which Minecraft targets ship, and does the release wait for a port?]
- [NEEDS CLARIFICATION: do the repository's Actions secrets hold tokens with write access to the store
  projects, and is publishing into them intended?]
- [NEEDS CLARIFICATION: who owns the release notes and the runbook?]
- [NEEDS CLARIFICATION: is the Fabric-only `&x` mixin set an accepted NeoForge gap?]
