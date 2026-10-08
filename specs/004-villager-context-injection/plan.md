# Implementation Plan: Villager Dialogue Context Injection

**Branch**: `004-villager-context-injection` | **Date**: 2026-10-07 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/004-villager-context-injection/spec.md`

## Summary

Enrich EconomyCraft's individual villager AI dialogue pipeline by extracting and injecting:

1. The villager's active trade inventory (buy/sell offers, stock status).
2. The interacting player's historical trade transactions with that specific villager.

Data is stored persistently in SQLite (`villager_trades` table) and formatted into prompt sections within strict character and token bounds, allowing natural, context-aware villager dialogue without compromising main-thread performance or LLM API latency.

## Technical Context

**Language/Version**: Java 25 (Minecraft Fabric / Architectury 26.3)

**Primary Dependencies**: Fabric API, Architectury API, SQLite JDBC, Gson, SLF4J / Mojang LogUtils

**Storage**: SQLite (`EconomyCraft/villagers.db` via `VillagerDatabase`)

**Testing**: JUnit 5 via containerized Gradle test runner

**Target Platform**: Linux server, Pterodactyl container, Minecraft 26.3 Fabric

**Project Type**: Minecraft Server Mod (Fabric / Common multi-project)

**Performance Goals**:

- Main thread execution time for offer extraction < 0.1ms per interaction.
- Asynchronous database queries for player trade history off the main thread.
- Prompt injection overhead strictly capped under 600 characters (< 150 tokens).

**Constraints**:

- Absolute thread safety: entity inventory reads strictly on the server tick thread; LLM API calls and SQLite queries strictly on background worker threads.
- Non-blocking: zero disruption to vanilla villager trading screen opening.
- Backward compatibility: preserve existing `player_memories` and `villagers` tables.

**Scale/Scope**: Server-wide NPC villagers across all active worlds and players.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- **Principle I (Live-System Truth)**: Plan adheres to verifying live server state through dynamic commands rather than stale assumptions.
- **Principle II (Verification Gate)**: All changes will be validated via unit tests and in-game boot/interaction verification before asserting success.
- **Principle III (Host-Fact Isolation)**: Mod code in `/home/yes/projects/EconomyCraft` will not contain hardcoded host paths, container IDs, or IP addresses.
- **Principle V (Markdown Lint Gate)**: All spec markdown files formatted and verified with `markdownlint-cli2`.
- **Principle VI (Commit Attribution)**: Commit trailer `Co-authored-by: CapCapSever <vhung18.2@gmail.com>` applied to all commits.
- **Principle XII (Never Hot-Swap Mod Jars)**: Deployment procedures will stop the server before replacing the mod jar.

*Status: All Gates Passed.*

## Project Structure

### Documentation (this feature)

```text
specs/004-villager-context-injection/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output
│   └── context-injection-api.md
└── checklists/
    └── requirements.md
```

### Source Code (`/home/yes/projects/EconomyCraft`)

```text
common/src/main/java/com/reazip/economycraft/
├── gossip/
│   ├── GossipApiClient.java                       # Passes trade context into prompt request
│   ├── VillagerGossipListener.java                # Captures main-thread trade snapshot
│   ├── memory/
│   │   ├── TradeOfferSnapshot.java                # In-memory record for villager active trades
│   │   ├── VillagerDialoguePromptBuilder.java     # Formats trade inventory and history into prompt
│   │   └── VillagerMemoryService.java             # Coordinates trade snapshot & history retrieval
│   └── storage/
│       ├── TradeRecord.java                       # Persistent trade record entity
│       └── VillagerDatabase.java                  # SQLite schema + async queries for trades
└── profession/
    └── ProfessionHooks.java                       # Enriched onVillagerTrade logging
```
