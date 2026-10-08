# Implementation Plan: Enriched Villager Memories

**Branch**: `006-villager-memory` | **Date**: 2026-10-08 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/006-villager-memory/spec.md`

## Summary

Make villager dialogue use persisted, pair-scoped player memory and validated recent trade facts, with a 90-day retention window and bounded prompt selection. Add admin-only inspection and clearing under the existing gossip command tree. Clearing removes the relationship row and matching trade rows, evicts cached memory, and fences in-flight responses so no response delivered after the clear can use stale context.

## Technical Context

**Language/Version**: Java 25 for the current 26.3 target; the source is a multi-target Java project with target-specific toolchains.

**Primary Dependencies**: Existing Minecraft server APIs, Architectury common module, SQLite JDBC, Gson, JUnit 5, existing permission backend and gossip API client. No new external dependency planned.

**Storage**: Existing SQLite `player_memories` and `villager_trades` tables in `VillagerDatabase`; retain schema unless implementation inspection finds a correctness need. Execute mutations through the existing single-thread DB executor.

**Testing**: Existing common-module JUnit 5 test suite. Add focused storage, service/cache invalidation, prompt selection, permission and async-race coverage. Planning only; do not run tests here.

**Target Platform**: Server-side EconomyCraft common code; current deployment target is Minecraft 26.3 Fabric, while common code is built for supported Fabric and NeoForge targets.

**Project Type**: Server-side Minecraft mod, Architectury multi-project Java build.

**Performance Goals**: No blocking database or provider calls on the server thread. Keep prompt history bounded; perform retention cleanup asynchronously and avoid per-tick work.

**Constraints**: Preserve existing database compatibility; never treat `price_paid` or `total_spent` as verified currency; isolate all data by villager-player pair; use existing admin permission conventions; do not change trading/economy behavior, general gossip, provider configuration, or deploy/restart a live server as part of implementation planning.

**Scale/Scope**: Existing SQLite profile, relationship and trade history; only the villager dialogue pipeline, its storage/service layer, and admin command surface.

## Constitution Check

- **I–II Live-system truth and verification**: This plan makes no new live-state claim and authorizes no deployment. Implementation and live verification remain separate gates.
- **III Host-fact isolation**: Mod implementation belongs in the EconomyCraft source repository. Keep host paths, IDs, IPs, credentials, and deployment details out of its source/docs.
- **IV Documentation-only artifact set**: This planning output is in the local Spec Kit tree; no mod source edits are made in this step.
- **V Markdown lint**: The constitution specifies a Markdown lint gate for Markdown edits. It is not run during this planning workflow; run it before considering documentation edits complete.
- **VI Commit attribution**: Any agent-created commit must end with the configured co-author trailer. No commit is made by this workflow.
- **VIII Secrets and exposure control**: Do not read or reproduce provider credentials.
- **IX Evidence-first judgment**: Existing source confirms the SQLite tables, asynchronous executor, in-memory caches, `/eco gossip` admin command root, and permission checks. Preserve the distinction between stored trade amounts and verified currency.
- **X Scope discipline**: Keep changes within villager memory/dialogue and its admin oversight. Do not change trade prices, balances, unrelated economy features, or live deployment.

**Gate status**: Pass. No design choice violates a constitutional rule; deploy and in-game verification are outside this plan.

## Project Structure

### Documentation (this feature)

```text
specs/006-villager-memory/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   └── villager-memory-admin.md
└── tasks.md                 # Created by $speckit-tasks, not this command
```

### Source Code (`/home/yes/projects/EconomyCraft`)

```text
common/src/main/java/com/reazip/economycraft/
├── EconomyCommands.java
├── gossip/memory/
│   ├── VillagerMemoryService.java
│   └── VillagerDialoguePromptBuilder.java
├── gossip/storage/
│   ├── VillagerDatabase.java
│   ├── PlayerMemory.java
│   └── TradeRecord.java
└── util/EconomyPermissions.java

common/src/test/java/com/reazip/economycraft/gossip/
├── memory/                  # Add service and prompt behavior coverage as needed
└── storage/                 # Extend VillagerDatabase tests for scoped deletion/retention
```

**Structure Decision**: Implement in the existing common module and reuse the existing SQLite tables, memory service, prompt builder, command tree, and permission system. No new module, external service, database, or client UI is required.

## Complexity Tracking

No constitution violations are planned.
