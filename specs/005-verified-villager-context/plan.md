# Implementation Plan: Verified Villager Context

**Branch**: `005-verified-villager-context` | **Date**: 2026-10-08 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/005-verified-villager-context/spec.md`

## Summary

Keep individualized villager dialogue grounded in completed trade records. Preserve the existing SQLite records and player-context categories, but treat the current `pricePaid` value as unverified: `ProfessionHooks.onVillagerTrade` currently supplies `offer.getCostA().getCount() * 10L`, while `TradeRecord.toPromptDescription()` presents positive values as dollars. The prompt projection will omit unverified monetary amounts and include only validated item/quantity details. General profession gossip remains unchanged.

## Technical Context

**Language/Version**: Java 25

**Primary Dependencies**: Fabric API, Architectury API, SQLite JDBC, Gson, SLF4J / Mojang LogUtils

**Storage**: Existing SQLite `villager_trades` table in `VillagerDatabase`; no new player data category or table is planned.

**Testing**: Existing EconomyCraft Gradle/JUnit 5 workflow; validation tasks should cover the prompt projection and completed-trade cases. Do not run tests as part of this planning/task-generation request.

**Target Platform**: Minecraft 26.3 Fabric server

**Project Type**: Java multi-project Minecraft server mod; source repository `/home/yes/projects/EconomyCraft`

**Performance Goals**: No new synchronous database or LLM work; retain the existing asynchronous history lookup and bounded prompt history.

**Constraints**: Scope is individualized villager dialogue only. Do not broaden player data collection, alter general gossip, or describe the derived legacy amount as currency paid. Preserve backward compatibility with existing `villager_trades` and `player_memories` records.

**Scale/Scope**: Prompt projection and validation in the existing villager dialogue pipeline; no deployment or live-server changes in this feature-planning stage.

## Constitution Check

- **I–II Live truth and verification**: No live-state claim is needed for this code change. Build, tests, deployed artifact, and in-game verification are separate implementation/deploy gates.
- **III Host-fact isolation**: Mod changes belong in the EconomyCraft source repository; do not add host paths, IDs, IPs, credentials, or deployment facts to mod source or README files.
- **IV Documentation-only artifact set**: This planning artifact stays in the spec tree. Any source implementation is confined to the separately maintained EconomyCraft repository.
- **V Markdown lint**: User requested that the Markdown formatting check be skipped; it will not be run in this workflow.
- **VI Commit attribution**: Any agent-created commit must end with the configured co-author trailer.
- **VIII Secrets**: Do not read or reproduce provider credentials.
- **IX Evidence-first judgment**: The source currently passes `costCount * 10L` into trade recording and formats positive `pricePaid` values with a dollar sign. Treat that amount as unverified unless implementation evidence establishes otherwise.
- **X Scope discipline**: Do not change general profession gossip, add player context categories, deploy, or restart a server as part of this feature.

**Gate status**: Pass with the scope and verification constraints above.

## Project Structure

### Documentation (this feature)

```text
specs/005-verified-villager-context/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   └── villager-dialogue-context.md
└── tasks.md
```

### Source Code (`/home/yes/projects/EconomyCraft`)

```text
common/src/main/java/com/reazip/economycraft/
├── gossip/memory/VillagerDialoguePromptBuilder.java
├── gossip/memory/VillagerMemoryService.java
├── gossip/storage/TradeRecord.java
└── profession/ProfessionHooks.java
```

**Structure Decision**: Limit implementation to the existing individualized dialogue projection and its trade-record interpretation. Keep database schema and collection categories unchanged unless implementation evidence shows that verified currency provenance already exists and is necessary to satisfy the spec.

## Complexity Tracking

No constitution violations are planned.
