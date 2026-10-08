# Tasks: Verified Villager Context

**Input**: Design documents from `specs/005-verified-villager-context/`

**Prerequisites**: `plan.md`, `spec.md`, `research.md`, `data-model.md`, and `contracts/villager-dialogue-context.md`

**Tests**: No test-writing tasks are included because the feature specification does not explicitly request a TDD approach or test additions. The independent acceptance criteria below remain implementation review gates.

## Phase 1: Setup

**Purpose**: Use the existing EconomyCraft project and villager dialogue pipeline; no project initialization or dependency changes are needed.

No setup tasks.

## Phase 2: Foundational

**Purpose**: Preserve existing persistence and context boundaries; this feature requires no schema migration or new shared infrastructure.

No foundational tasks. Existing `villager_trades` and `player_memories` data remain compatible.

---

## Phase 3: User Story 1 - Accurate references to completed trades (Priority: P1) 🎯 MVP

**Goal**: Keep individualized dialogue's item and quantity references tied to completed trade rows and prevent the derived `pricePaid` value from being presented as currency paid.

**Independent Test**: Review generated prompt context for a row written by the existing `costCount * 10L` path: valid item and quantity may appear, but no dollar amount appears. Empty history produces no purchase claim; malformed details are omitted; repeated rows are not combined.

### Implementation for User Story 1

- [X] T001 [US1] Change `TradeRecord.toPromptDescription()` in `/home/yes/projects/EconomyCraft/common/src/main/java/com/reazip/economycraft/gossip/storage/TradeRecord.java` to format valid item details, avoid duplicating captured quantities, and omit `pricePaid` because current and legacy rows have no verified-currency provenance.
- [X] T002 [US1] Update `/home/yes/projects/EconomyCraft/common/src/main/java/com/reazip/economycraft/gossip/memory/VillagerDialoguePromptBuilder.java` to include only supported record details, omit the section when no eligible details exist, suppress unverified `totalSpent` and legacy dollar events, and instruct the model not to infer spending from supplied context.
- [X] T003 [US1] Confirm the existing history lookup in `/home/yes/projects/EconomyCraft/common/src/main/java/com/reazip/economycraft/gossip/memory/VillagerMemoryService.java` remains scoped to the current villager/player pair and does not synthesize trade records from interaction memories; no change was needed.

**Checkpoint**: The individualized prompt contains no unverified monetary amounts and cannot claim an unsupported purchase; existing verified item and quantity details remain usable.

---

## Phase 4: User Story 2 - Existing context stays within its current privacy boundary (Priority: P2)

**Goal**: Deliver the accuracy correction without adding player data categories or changing general profession gossip.

**Independent Test**: Compare the resulting code diff with the existing collection and gossip paths. It adds no player fields, database columns, collection calls, or general-gossip behavior changes; the only changed output is individualized trade-history context.

### Implementation for User Story 2

- [X] T004 [US2] Keep trade capture and persistence fields unchanged in `/home/yes/projects/EconomyCraft/common/src/main/java/com/reazip/economycraft/profession/ProfessionHooks.java` and `/home/yes/projects/EconomyCraft/common/src/main/java/com/reazip/economycraft/gossip/storage/VillagerDatabase.java`; confirm the implementation adds no player data category or schema migration.
- [X] T005 [US2] Keep profession-wide gossip generation and its prompt inputs unchanged in `/home/yes/projects/EconomyCraft/common/src/main/java/com/reazip/economycraft/gossip/VillagerGossipListener.java` and `/home/yes/projects/EconomyCraft/common/src/main/java/com/reazip/economycraft/gossip/GossipApiClient.java`; ensure the transaction display change remains limited to individualized dialogue.

**Checkpoint**: User Story 1 is satisfied while collection, persistence schema, and general profession gossip retain their existing behavior.

---

## Phase 5: Polish & Cross-Cutting Concerns

**Purpose**: Check requirement coverage and hand off runtime verification to the authorized implementation/deployment workflow.

- [X] T006 Review the final diff across the EconomyCraft files named in T001–T005 against the contract and confirm acceptance scenarios are addressed.
- [X] T007 Record the currency-provenance limitation in `specs/005-verified-villager-context/quickstart.md`; positive legacy `pricePaid`, `totalSpent`, and dollar-denominated events are not verified payment evidence.

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No work required; the existing mod project is used.
- **Foundational (Phase 2)**: No work required; the schema and shared infrastructure remain unchanged.
- **User Story 1 (Phase 3)**: Can start immediately; T002 depends on the output contract established by T001. T003 confirms existing history scoping and can be reviewed independently.
- **User Story 2 (Phase 4)**: Can start after the scope of User Story 1 is clear; T004 and T005 preserve separate data-collection and general-gossip boundaries.
- **Polish (Phase 5)**: Depends on the selected story work being complete.

### User Story Dependencies

- **User Story 1 (P1)**: No dependency on another story; MVP.
- **User Story 2 (P2)**: Scope guard for the User Story 1 implementation; does not require new behavior from User Story 1 to be independently reviewed.

### Parallel Opportunities

- T001 and T003 can be reviewed in parallel because they target separate source files; T002 should follow T001 so prompt formatting matches the record projection.
- T004 and T005 can be reviewed in parallel because they cover persistence/capture and general gossip respectively.
- T006 and T007 are polish tasks and may proceed in parallel once T001–T005 are complete.

## Parallel Example: User Story 1

```text
Review existing player-villager history scoping in VillagerMemoryService.java (T003)
Implement safe transaction description formatting in TradeRecord.java (T001)
```

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete T001 to stop presenting the derived stored number as currency.
2. Complete T002 to constrain individualized prompt claims to supported record details.
3. Complete T003 to confirm no-trade and player/villager scoping behavior.
4. Review the independent acceptance criteria for User Story 1 before taking on the privacy-boundary review.

### Incremental Delivery

1. Deliver User Story 1 as the user-visible accuracy correction.
2. Complete User Story 2 scope checks without adding data collection, schema, or general-gossip behavior.
3. Complete the final contract and limitation review in Phase 5.

## Notes

- Every task has a sequential ID, a story label when it belongs to a user story, and concrete source or artifact paths.
- No test-writing task or test command is included; the user did not request tests.
- Do not deploy or restart a live server as part of task execution without separate authorization through the operations workflow.
