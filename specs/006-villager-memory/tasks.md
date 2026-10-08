---
description: "Implementation tasks for enriched villager memories"
---

# Tasks: Enriched Villager Memories

**Input**: Design documents from `/specs/006-villager-memory/`

**Prerequisites**: `plan.md` and `spec.md`; supporting decisions in `research.md`, `data-model.md`, and `contracts/villager-memory-admin.md`.

**Tests**: The specification does not explicitly require a TDD workflow. Test tasks are omitted; implementation should still be validated with the existing common-module tests and the quickstart scenarios when implementation is authorized.

**Organization**: Tasks are grouped by user story for incremental implementation.

## Phase 1: Setup

- [X] T001 Locate and inspect the EconomyCraft common module files listed in `specs/006-villager-memory/plan.md` before editing, confirming current signatures and package paths in `common/src/main/java/com/reazip/economycraft/`

## Phase 2: Foundational

- [X] T002 Add pair-scoped asynchronous memory and trade repository operations in `common/src/main/java/com/reazip/economycraft/gossip/storage/VillagerDatabase.java`, preserving existing tables and prepared-statement conventions in `common/src/main/java/com/reazip/economycraft/gossip/storage/VillagerDatabase.java`
- [X] T003 Define shared 90-day retention cutoff and validation helpers for memory and trade projections in `common/src/main/java/com/reazip/economycraft/gossip/memory/VillagerMemoryService.java`

## Phase 3: User Story 1 - Accurate memories of trades and visits (Priority: P1)

**Goal**: Use persisted pair-scoped relationship memory and validated completed-trade facts without inventing purchase or payment details.

**Independent Test**: Seed known trade and visit data, restart or evict the service cache, then confirm the returning dialogue uses only verified item/quantity facts and does not state price or spending; malformed or unavailable memory must not break normal dialogue or trading.

### US1 Implementation

- [X] T004 [US1] Load persisted `PlayerMemory` on a cache miss in `common/src/main/java/com/reazip/economycraft/gossip/memory/VillagerMemoryService.java` before building individualized dialogue context in `common/src/main/java/com/reazip/economycraft/gossip/memory/VillagerMemoryService.java`
- [X] T005 [US1] Validate trade item descriptions and positive quantities and omit `price_paid` and `total_spent` as currency claims in `common/src/main/java/com/reazip/economycraft/gossip/storage/TradeRecord.java` and `common/src/main/java/com/reazip/economycraft/gossip/memory/VillagerDialoguePromptBuilder.java`
- [X] T006 [US1] Separate visit events from completed-trade events and prevent legacy `recent_events` strings from serving as verified transactions in `common/src/main/java/com/reazip/economycraft/gossip/memory/VillagerMemoryService.java` and `common/src/main/java/com/reazip/economycraft/gossip/memory/VillagerDialoguePromptBuilder.java`
- [X] T007 [US1] Keep relationship sentiment within [-100, 100], interaction count nonnegative, and recent event history bounded to at most 10 entries in `common/src/main/java/com/reazip/economycraft/gossip/storage/PlayerMemory.java`
- [X] T008 [US1] Make malformed or unavailable memory reads fall back to empty verified context while preserving dialogue and trade flow in `common/src/main/java/com/reazip/economycraft/gossip/memory/VillagerMemoryService.java`

**Checkpoint**: Known visits and trades can be recalled accurately, without unsupported payment claims.

## Phase 4: User Story 2 - Personal, relevant conversations (Priority: P2)

**Goal**: Select a small relevant set of valid memories and keep historical purchases separate from current stall availability.

**Independent Test**: Provide multiple event and trade records, including an item no longer offered and records around the 90-day boundary; confirm only bounded eligible context is included and sales pitches use current offers.

### US2 Implementation

- [X] T009 [US2] Filter trade records strictly before the 90-day cutoff and asynchronously purge expired trade rows in `common/src/main/java/com/reazip/economycraft/gossip/storage/VillagerDatabase.java`
- [X] T010 [US2] Exclude and asynchronously purge relationship rows whose `last_interaction` is strictly before the 90-day cutoff in `common/src/main/java/com/reazip/economycraft/gossip/storage/VillagerDatabase.java`
- [X] T011 [US2] Select at most five recent validated trades and a bounded relevant memory summary for dialogue in `common/src/main/java/com/reazip/economycraft/gossip/memory/VillagerMemoryService.java` and `common/src/main/java/com/reazip/economycraft/gossip/memory/VillagerDialoguePromptBuilder.java`
- [X] T012 [US2] Keep current stall offers as the sole source for present availability and label remembered purchases as historical context in `common/src/main/java/com/reazip/economycraft/gossip/memory/VillagerDialoguePromptBuilder.java`
- [X] T013 [US2] Ensure an empty eligible memory projection instructs dialogue not to claim a prior visit, trade, or relationship in `common/src/main/java/com/reazip/economycraft/gossip/memory/VillagerDialoguePromptBuilder.java`

**Checkpoint**: Returning-player dialogue is bounded, relevant, current-stock aware, and first-time conversations do not claim shared history.

## Phase 5: User Story 3 - Admin memory oversight (Priority: P3)

**Goal**: Let authorized administrators inspect or clear exactly one villager-player memory pair, including cached and in-flight context.

**Independent Test**: Inspect and clear a selected pair as an admin, confirm only that relationship and its trade rows are shown/deleted, verify a later interaction has no history, and verify a non-admin is denied without disclosure or mutation.

### US3 Implementation

- [X] T014 [US3] Add an atomic pair-scoped repository operation deleting one `player_memories` row and matching `villager_trades` rows in `common/src/main/java/com/reazip/economycraft/gossip/storage/VillagerDatabase.java`
- [X] T015 [US3] Add validated pair-scoped memory inspection that returns bounded summary and validated recent trade facts without monetary interpretation in `common/src/main/java/com/reazip/economycraft/gossip/memory/VillagerMemoryService.java`
- [X] T016 [US3] Add a per-pair generation fence and cache eviction when clearing memory, and reject post-clear asynchronous completions before delivery or persistence in `common/src/main/java/com/reazip/economycraft/gossip/memory/VillagerMemoryService.java`
- [X] T017 [US3] Add `/eco gossip memory inspect <villager-uuid> <player-uuid>` and `/eco gossip memory clear <villager-uuid> <player-uuid>` with UUID validation and execution-time admin authorization in `common/src/main/java/com/reazip/economycraft/EconomyCommands.java`
- [X] T018 [US3] Format inspection results without raw SQL/JSON, `price_paid`, `total_spent` currency claims, or unrelated player records in `common/src/main/java/com/reazip/economycraft/EconomyCommands.java`
- [X] T019 [US3] Report clear success only after storage completion, distinguish no-op from storage failure, and keep denial/error messages free of memory data in `common/src/main/java/com/reazip/economycraft/EconomyCommands.java`

**Checkpoint**: Admin inspection and clear are pair-scoped, permission-gated, durable, and safe against stale in-flight responses.

## Final Phase: Polish & Cross-Cutting Concerns

- [X] T020 [P] Review changed EconomyCraft source and tests for host-specific paths, IDs, credentials, and deployment details before publishing changes in the changed files under `common/src/main/java/com/reazip/economycraft/`
- [X] T021 Validate all three user stories against the scenarios in `specs/006-villager-memory/quickstart.md` and record any unresolved behavior in `specs/006-villager-memory/implementation-report.md`

## Dependencies & Execution Order

### Phase Dependencies

- Setup (Phase 1) precedes all source changes.
- Foundational (Phase 2) provides shared pair-scoped storage and retention rules and blocks the story phases.
- User stories follow P1 → P2 → P3 for the recommended incremental delivery. US2 relies on the accurate memory projection from US1; US3 shares the repository and cache semantics established in earlier phases.
- Polish follows the desired story set.

### User Story Dependencies

- **US1 (P1)**: Depends on Phase 2; independently delivers accurate persisted visit and trade recollection.
- **US2 (P2)**: Depends on Phase 2 and uses the validated facts and projections from US1.
- **US3 (P3)**: Depends on Phase 2 and the service/cache lifecycle in US1; its command surface is otherwise independently scoped.

### Parallel Opportunities

- T002 and T003 touch separate files and can proceed in parallel.
- Within US1, T005 can proceed alongside T004 after foundational helpers are agreed; T006 and T007 affect separate files and can be developed independently.
- Within US3, T014 and T017 can be developed in parallel after repository/service signatures are agreed; T018 and T019 share the command file and should be coordinated.
- Story phases are not fully independent because US2 and US3 build on the shared memory service and storage behavior.

## Parallel Example: User Story 1

```text
Task: T004 Load persisted relationship memory in VillagerMemoryService.java
Task: T005 Validate trade facts and suppress unsupported payment claims in TradeRecord.java and VillagerDialoguePromptBuilder.java
Task: T007 Preserve PlayerMemory bounds in PlayerMemory.java
```

## Implementation Strategy

### MVP First

Complete Setup and Foundational, then US1. Validate persistence across cache eviction/restart, visit-versus-trade distinction, supported item/quantity facts, and safe fallback. US1 is the smallest useful release; proceed to US2 and US3 as separate increments.

### Incremental Delivery

1. Complete Setup and Foundational.
2. Deliver US1 for accurate persisted memory.
3. Deliver US2 for bounded relevant dialogue and retention.
4. Deliver US3 for pair-scoped admin inspection and clearing.
5. Build and verify in the mod development workflow when implementation and deployment are separately authorized.
