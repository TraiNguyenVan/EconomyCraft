# Evidence: build and test matrix

Collected 2026-10-12 by the assessment lead. This sandbox has no JDK installed by default; JDK 21 (Temurin
21.0.12.1+1) and JDK 25 (Temurin 25.0.4.1+1) were provisioned into `/tmp/jdks` and Gradle 9.7.1 was fetched
by the wrapper (`gradle/wrapper/gradle-wrapper.properties` pins 9.7.1). Network access to Maven Central,
`maven.fabricmc.net`, `maven.architectury.dev`, `maven.neoforged.net` and Mojang's metadata hosts was
verified reachable. Raw logs: `/tmp/verify-logs/` (summary in `summary.txt`).

## How the matrix was run

Two independent paths, because they answer different questions:

1. **The documented verification commands** from `AGENTS.md` §"Build and verification":
   `./gradlew build`, `./gradlew -Pminecraft_version=1.21.1 build`,
   `./gradlew -Pfilter_platforms=fabric :common:test :fabric:build`,
   `./gradlew -Pminecraft_version=26.3 :common:test`.
2. **The release path**, i.e. exactly what `.github/workflows/release.yml` invokes per target:
   `./gradlew buildAndCopyFabricJars_<v> -Pfilter_platforms=fabric` and
   `./gradlew buildAndCopyNeoForgeJars_<v> -Pfilter_platforms=neoforge`, with the workflow's own task-name
   derivation (`26.3`, `26.2`, `26.1.02`, `1.21.11`, `1.21.01`).

## Result summary

| Target | Loader | Release-path task | Result |
|---|---|---|---|
| 26.3 | Fabric | `buildAndCopyFabricJars_26.3` | **PASS** — `build/fabric/26.3/economycraft-fabric-1.10.0_26.3.jar` (15.4 MB, 652 entries) |
| 26.3 | NeoForge | `buildAndCopyNeoForgeJars_26.3` | **PASS** — `build/neoforge/26.3/economycraft-neoforge-1.10.0_26.3.jar` (1.1 MB) |
| 26.2 | Fabric | `buildAndCopyFabricJars_26.2` | **FAIL** — `:common:compileJava`, 6 errors |
| 26.2 | NeoForge | `buildAndCopyNeoForgeJars_26.2` | **FAIL** — `:common:compileJava`, 6 errors |
| 26.1.2 | Fabric | `buildAndCopyFabricJars_26.1.02` | **FAIL** — `:common:compileJava` (BonemealSource, Prediction, `Optional<T>` vs `ChatFormatting`) |
| 26.1.2 | NeoForge | `buildAndCopyNeoForgeJars_26.1.02` | **FAIL** — `:common:compileJava`, same three symbols |
| 1.21.11 | Fabric | `buildAndCopyFabricJars_1.21.11` | **FAIL** — `:common:compileJava`, 6 errors |
| 1.21.11 | NeoForge | `buildAndCopyNeoForgeJars_1.21.11` | **FAIL** — `:common:compileJava`, 6 errors |
| 1.21.1 | Fabric | `buildAndCopyFabricJars_1.21.01` | **FAIL** — `:common:compileJava`, 6 errors |
| 1.21.1 | NeoForge | `buildAndCopyNeoForgeJars_1.21.01` | **FAIL** — `:common:compileJava` (BonemealSource, villager package layout) |

Root-level commands: `./gradlew build` (default target 26.3, both platforms) **PASS**, and it did execute
`:common:test`; `./gradlew -Pfilter_platforms=fabric :common:test` (26.3) **PASS**.

## The failure: 26.3-only vanilla APIs in the shared source set

Every failing target fails for the same reason, in `common/src/main/java` — the shared source set, not a
compat fork:

| File | Symbol | Era |
|---|---|---|
| `common/src/main/java/com/reazip/economycraft/profession/FarmerEffects.java:19,80,111` | `net.minecraft.world.level.block.BonemealSource` | 26.3 only |
| `common/src/main/java/com/reazip/economycraft/util/BookInputUi.java:22,192` | `net.minecraft.util.Prediction` | 26.3 only |
| `common/src/main/java/com/reazip/economycraft/tag/TagDisplayService.java:364` | `Optional<T>` used where `ChatFormatting` is required | 1.21.11 signature difference |
| `common/src/main/java/com/reazip/economycraft/profession/MerchantEffects.java:7-9`, `ProfessionHooks.java:11` | `net.minecraft.world.entity.npc.villager` | absent on 1.21.1 (package layout differs) |
| `common/src/main/java/com/reazip/economycraft/profession/BuilderEffects.java:8,104` | 26.x symbol | absent on 1.21.1 |

All of these files are additions of this tree — none exists in upstream `1.10.0`, whose `1.10.0` artifacts
were published successfully for all five targets. So the multi-target breakage is introduced here, and it
contradicts `AGENTS.md` ("Compatibility: vanilla API differences belong in matching compat forks, not version
checks in shared logic") and constitution principle VII.

The 26.3 build compiles the same sources against the 26.3 jar and passes, which is what makes this look like
a working tree: the default target hides the breakage.

## Observed build-order artifact (26.3 → obfuscated target without a clean)

`./gradlew -Pminecraft_version=1.21.1 -Pfilter_platforms=fabric :common:test` run **in a tree whose previous
build was 26.3** fails during configuration, before compilation, with:

```
A problem occurred configuring project ':fabric'.
> Failed to setup Minecraft, java.lang.IllegalArgumentException: Cannot remap access widener
  from namespace 'official'. Expected: 'intermediary'
```

The release path does not hit this because its generated tasks clean first
(`build.gradle:186`, `build.gradle:262`: `:fabric:clean` / `:neoforge:clean` are passed to the nested
wrapper), and the same 1.21.1 fabric leg got past configuration to the compile errors above. Recorded as an
order-dependent developer-experience artifact, not as a release blocker.
