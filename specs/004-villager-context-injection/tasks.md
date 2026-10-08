# Tasks: Villager Dialogue Context Injection

**Feature**: `004-villager-context-injection` | **Date**: 2026-10-07 | **Spec**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md)

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Prepare project branch and data structures for context injection

- [X] T001 [P] Create `TradeOfferSnapshot` immutable record in `/home/yes/projects/EconomyCraft/common/src/main/java/com/reazip/economycraft/gossip/memory/TradeOfferSnapshot.java`
- [X] T002 [P] Create `TradeRecord` entity record with timestamp, items, and cost in `/home/yes/projects/EconomyCraft/common/src/main/java/com/reazip/economycraft/gossip/storage/TradeRecord.java`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core database schema and retrieval foundation in SQLite

**⚠️ CRITICAL**: Must be completed before user stories connect to storage

- [X] T003 Update SQLite schema in `/home/yes/projects/EconomyCraft/common/src/main/java/com/reazip/economycraft/gossip/storage/VillagerDatabase.java` to create `villager_trades` table and index
- [X] T004 Implement `recordTradeTransaction` and `getRecentTrades` methods in `/home/yes/projects/EconomyCraft/common/src/main/java/com/reazip/economycraft/gossip/storage/VillagerDatabase.java`
- [X] T005 [P] Add unit test for `villager_trades` table operations in `/home/yes/projects/EconomyCraft/common/src/test/java/com/reazip/economycraft/gossip/VillagerDatabaseTest.java`

**Checkpoint**: Foundation ready - database schema and core snapshot records are operational.

---

## Phase 3: User Story 1 - Current Trade Offer Context in Dialogue (Priority: P1) 🎯 MVP

**Goal**: Extract active villager trade offers on the main server thread and inject them into prompt generation so villagers reference what they currently sell or buy.

**Independent Test**: Interact with a villager in-game or via prompt tests; verify the generated prompt contains formatted trade offers and the dialogue references their merchandise.

### Implementation for User Story 1

- [X] T006 [US1] Implement trade offer extraction utility in `/home/yes/projects/EconomyCraft/common/src/main/java/com/reazip/economycraft/gossip/memory/TradeOfferSnapshot.java` to parse `MerchantOffers` safely on main thread
- [X] T007 [US1] Update `VillagerGossipListener.handleVillagerInteraction` in `/home/yes/projects/EconomyCraft/common/src/main/java/com/reazip/economycraft/gossip/VillagerGossipListener.java` to extract current offers snapshot from the villager entity
- [X] T008 [US1] Extend `VillagerMemoryService.handleInteraction` in `/home/yes/projects/EconomyCraft/common/src/main/java/com/reazip/economycraft/gossip/memory/VillagerMemoryService.java` to accept trade offers snapshot
- [X] T009 [US1] Update `VillagerDialoguePromptBuilder.buildSystemInstruction` in `/home/yes/projects/EconomyCraft/common/src/main/java/com/reazip/economycraft/gossip/memory/VillagerDialoguePromptBuilder.java` to format and inject active stall offers
- [X] T010 [US1] Propagate trade snapshot parameter through `GossipApiClient.generateIndividualDialogue` in `/home/yes/projects/EconomyCraft/common/src/main/java/com/reazip/economycraft/gossip/GossipApiClient.java`
- [X] T011 [P] [US1] Add unit test for prompt formatting of trade offers in `/home/yes/projects/EconomyCraft/common/src/test/java/com/reazip/economycraft/gossip/VillagerDialoguePromptBuilderTest.java`

**Checkpoint**: User Story 1 complete — active trade offers are extracted, bounded, and delivered into villager LLM prompts.

---

## Phase 4: User Story 2 - Per-Player Detailed Trade History Awareness (Priority: P2)

**Goal**: Record per-player trade transactions into SQLite and inject past purchase context into dialogue prompts for returning customers.

**Independent Test**: Complete a trade, wait for cooldown, talk to the villager, and verify that the dialogue acknowledges the prior purchase.

### Implementation for User Story 2

- [X] T012 [US2] Update `ProfessionHooks.onVillagerTrade` in `/home/yes/projects/EconomyCraft/common/src/main/java/com/reazip/economycraft/profession/ProfessionHooks.java` to log structured items and prices to `VillagerMemoryService`
- [X] T013 [US2] Extend `VillagerMemoryService.recordTrade` in `/home/yes/projects/EconomyCraft/common/src/main/java/com/reazip/economycraft/gossip/memory/VillagerMemoryService.java` to persist to `VillagerDatabase.recordTradeTransaction`
- [X] T014 [US2] Update `VillagerMemoryService.handleInteraction` in `/home/yes/projects/EconomyCraft/common/src/main/java/com/reazip/economycraft/gossip/memory/VillagerMemoryService.java` to asynchronously retrieve recent customer trades
- [X] T015 [US2] Extend `VillagerDialoguePromptBuilder.buildSystemInstruction` in `/home/yes/projects/EconomyCraft/common/src/main/java/com/reazip/economycraft/gossip/memory/VillagerDialoguePromptBuilder.java` to inject customer trade history
- [X] T016 [P] [US2] Add unit test for prompt formatting of customer trade history in `/home/yes/projects/EconomyCraft/common/src/test/java/com/reazip/economycraft/gossip/VillagerDialoguePromptBuilderTest.java`

**Checkpoint**: User Story 2 complete — villagers remember and reference specific past trades with individual players.

---

## Phase 5: User Story 3 - Context Budget and Prompt Token Optimization (Priority: P3)

**Goal**: Enforce strict truncation, formatting, and character limits on trade context so prompts remain concise, inexpensive, and fast.

**Independent Test**: Run prompt builder with 20+ trade offers and 50+ past transactions; verify prompt string length remains within the 600 character (<150 token) ceiling.

### Implementation for User Story 3

- [X] T017 [US3] Add token budget clamping and offer ranking (top 5-6 offers, highlighting in-stock and out-of-stock items) in `/home/yes/projects/EconomyCraft/common/src/main/java/com/reazip/economycraft/gossip/memory/VillagerDialoguePromptBuilder.java`
- [X] T018 [US3] Refine system prompt guidelines in `/home/yes/projects/EconomyCraft/common/src/main/java/com/reazip/economycraft/gossip/memory/VillagerDialoguePromptBuilder.java` to instruct the LLM to mention inventory naturally without listing it like a store menu
- [X] T019 [P] [US3] Add stress/boundary test verifying strict character capping under large inventories in `/home/yes/projects/EconomyCraft/common/src/test/java/com/reazip/economycraft/gossip/VillagerDialoguePromptBuilderTest.java`

**Checkpoint**: User Story 3 complete — prompt payloads strictly bounded against latency and token budget bloat.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Build validation, markdown linting, and deploy preflight checks

- [X] T020 Run containerized test suite (`./gradlew test --no-daemon`) via yolk container
- [X] T021 [P] Verify documentation Markdown conforms to `.markdownlint.jsonc` via `npx --yes markdownlint-cli2 "**/*.md"`
- [X] T022 Update authoritative documentation in `/home/yes/pterodactyl/economy/gossip-and-motd.md` reflecting new context injection capabilities

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — can start immediately.
- **Foundational (Phase 2)**: Depends on Phase 1 completion — blocks User Story 2.
- **User Story 1 (Phase 3)**: Depends on Phase 1 models. Can proceed independently of Phase 2 database tasks.
- **User Story 2 (Phase 4)**: Depends on Phase 2 database methods and Phase 3 prompt builder extensions.
- **User Story 3 (Phase 5)**: Depends on Phase 3 and Phase 4 prompt builder integration.
- **Polish (Phase 6)**: Runs after all user stories complete.

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1 (T001, T002) and Phase 3 (T006 - T011).
2. Validate active trade offers in prompts.
3. Deploy/test MVP.

### Full Incremental Delivery

1. Foundation: SQLite `villager_trades` table (Phase 2).
2. MVP: Active trade offers context (Phase 3).
3. History: Per-player past trades context (Phase 4).
4. Optimization: Strict token capping & prompt refinement (Phase 5).
5. Verification: Full test suite & documentation update (Phase 6).
