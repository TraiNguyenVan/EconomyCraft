# Release runbook

EconomyCraft ships one jar per loader for Minecraft 26.3. Releases run from
`.github/workflows/release.yml` (`workflow_dispatch`) and publish to Modrinth project `H4o5bO7g` and
CurseForge project `1321557`.

## Before dispatching

1. **Bump `mod_version` in `gradle.properties`.** It is the only version source: `build.gradle` sets every
   project's `version` from it, and the workflow reads it back out of the same file.
2. **Check the number is free.** The Modrinth/CurseForge project history already contains `1.10.0` for the
   older Minecraft targets (published from the upstream repository before this fork existed), so a release
   of this tree must use a new number rather than reuse `1.10.0`.
3. **Update `CHANGELOG.md`.** The whole file is the `changelog-file` that `mc-publish` uploads, so keep it
   free of internal notes, known-gap lists and design-decision prose. Unreleased work stays under
   `## Unreleased`.
4. **Commit and push** the release commit. The workflow reads the tag on `HEAD` for its version summary.

## What the workflow does

| Job | Purpose |
|---|---|
| `test` | `./gradlew :common:test` on Java 25. Every later job `needs` it, so a release cannot skip tests. |
| `setup` | Reports the `gradle.properties` version and the tag on `HEAD`. |
| `build` | Builds both loaders (`buildAndCopyFabricJars`, `buildAndCopyNeoForgeJars`) and uploads the jars. |
| `release` | Publishes each jar unless `dry_run` is set. |

Dispatch once with `dry_run: true` and confirm both build legs are green before a real release.

Publishing uses the `MODRINTH_PERSONAL_ACCESS_TOKEN` and `CURSEFORGE_PERSONAL_ACCESS_TOKEN` repository
secrets; the workflow does not create or manage them.

## Local equivalent

```sh
./gradlew :common:test
./gradlew -Pfilter_platforms=fabric buildAndCopyFabricJars
./gradlew -Pfilter_platforms=neoforge buildAndCopyNeoForgeJars
```

The jars land in `build/fabric/26.3/` and `build/neoforge/26.3/`.
