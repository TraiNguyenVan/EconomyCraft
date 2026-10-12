# Idea Intake: Release readiness for the next version

- **Slug**: release-1-10-0-readiness
- **Created**: 2026-10-12
- **Source**: pasted text (chat request) + codebase pointer (`/workspace`, the EconomyCraft repository)
- **Type**: exploration

## Idea (as captured)

> gather information about whether we are ready for a new version relase + works to do to release it seemlessly

## Restated

Establish whether the current `main` checkout can be published as the next EconomyCraft version, and
enumerate the work required to make that release go out cleanly on every supported target and platform.

## Origin & Context

- **Raised by**: repository maintainer (chat request in this session).
- **Trigger**: [NEEDS CLARIFICATION: no external trigger recorded — no open issue, no release deadline, and
  the GitHub repository has no open issues to reference]. The observable context is that
  `gradle.properties` carries `mod_version = 1.10.0` as unreleased work, `AGENTS.md` records that state as
  verified on 2026-10-07, and the only release path is a manually dispatched workflow
  (`.github/workflows/release.yml`) that publishes to Modrinth and CurseForge.

## First-Glance Unknowns

- [NEEDS CLARIFICATION: what version number the next release should carry? `1.10.0` is already published on
  Modrinth and CurseForge for all five Minecraft targets on both loaders, so the number in
  `gradle.properties` cannot be published as-is; the target number is a maintainer decision.]
- [NEEDS CLARIFICATION: which Minecraft targets should ship in this release? All five are buildable targets,
  but there is no statement of intent for this particular release.]
- [NEEDS CLARIFICATION: is publishing this repository's build into the Modrinth project `H4o5bO7g` and
  CurseForge project `1321557` intended, and does this repository hold publish tokens for those projects?]
- [NEEDS CLARIFICATION: what is the intended release-notes body? `mc-publish` publishes the whole
  `CHANGELOG.md` file, which currently carries no version heading.]
- [NEEDS CLARIFICATION: who owns the release run — is there an existing release checklist or runbook
  outside this repository?]
