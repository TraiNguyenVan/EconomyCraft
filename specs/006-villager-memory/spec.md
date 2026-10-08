# Feature Specification: Enriched Villager Memories

**Feature Branch**: `006-villager-memory`

**Created**: 2026-10-08

**Status**: Draft

**Input**: User description: "Enrich villager memories with accurate structured details, relevant dialogue context, and admin inspection and clearing."

## Clarifications

### Session 2026-10-08

- Q: When an administrator clears a player’s memory for a villager, should that also remove the matching trade records used to build dialogue context? → A: Clear both the relationship memory and matching trade records for the selected villager-player pair.
- Q: If an administrator clears a player’s memory while that player is interacting with the villager, should the response already being generated be allowed to use the old memory? → A: Invalidate memory immediately; responses generated afterward cannot use it, while a response already sent cannot be recalled.

## User Scenarios & Testing

### User Story 1 - Accurate memories of trades and visits (Priority: P1)

When a player returns to a villager, the villager should recall verified facts about their past interactions without inventing prices, quantities, or events.

**Why this priority**: Trustworthy memory is the foundation for useful personalization. Incorrect recollections make the feature feel broken and can misrepresent transactions.

**Independent Test**: Complete known trades and visits with a villager, return later, and confirm dialogue can reference recorded items and visits while omitting unverified transaction details.

**Acceptance Scenarios**:

1. **Given** a player has completed a recorded trade with a villager, **When** they return, **Then** the villager may recall the traded item and recorded quantity accurately.
2. **Given** the trade record does not verify a currency or amount paid, **When** dialogue is generated, **Then** the villager does not state or imply a price or spending amount.
3. **Given** a player has interacted without trading, **When** they return, **Then** the villager may recall the visit without describing it as a purchase.

### User Story 2 - Personal, relevant conversations (Priority: P2)

When a returning player speaks with a villager, the villager uses a small selection of relevant remembered details alongside current stall offers, so the conversation feels continuous and useful.

**Why this priority**: Once stored facts are reliable, selecting relevant details makes memory perceptible to players without overwhelming dialogue.

**Independent Test**: Give a player several interactions of different kinds, return after a delay, and verify that dialogue uses a relevant remembered detail, respects current stock, and remains concise.

**Acceptance Scenarios**:

1. **Given** a returning player has multiple remembered interactions, **When** the villager responds, **Then** the response uses no more than a small bounded set of relevant memories.
2. **Given** a remembered item is no longer offered at the stall, **When** the villager makes a sales pitch, **Then** the pitch refers to an item currently offered rather than presenting the remembered item as current stock.
3. **Given** a player is new to a villager, **When** they speak, **Then** the villager does not claim a prior relationship or past trade.
4. **Given** a detailed trade record is older than 90 days, **When** dialogue context is assembled, **Then** the expired record is not used as a recent memory.

### User Story 3 - Admin memory oversight (Priority: P3)

An authorized server administrator can inspect and clear the memories associated with a villager or player when correction or privacy cleanup is needed.

**Why this priority**: Oversight supports troubleshooting and gives the operator a bounded way to correct or remove accumulated memory.

**Independent Test**: As an authorized administrator, inspect a selected memory and clear it; verify it no longer affects a subsequent conversation. Verify a non-admin cannot perform either action.

**Acceptance Scenarios**:

1. **Given** an authorized administrator selects a villager and player, **When** they inspect the memory, **Then** they see the stored relationship summary and recent recorded events without exposing unrelated players' details.
2. **Given** an authorized administrator clears a selected memory, **When** that player next interacts with that villager, **Then** the relationship memory and matching trade records are removed, and the villager treats the player as having no stored relationship history.
3. **Given** a non-admin attempts to inspect or clear memory, **When** the request is made, **Then** the action is denied and no memory data is disclosed or changed.

### Edge Cases

- A trade record is missing an item description, has an invalid quantity, or contains malformed text.
- A player has many old interactions, but only a bounded and relevant subset should influence dialogue.
- A villager is removed or replaced, or a player identity is no longer available.
- Memory storage is unavailable or contains malformed legacy entries; trade and conversation flows should continue without fabricated memories.
- An administrator clears memory while the same player is interacting with the villager; the clear invalidates memory immediately, so any response generated afterward cannot use the cleared memory.
- A remembered item is no longer present in current trade offers.

## Requirements

### Functional Requirements

- **FR-001**: The system MUST distinguish completed trades from visits and other interactions in stored memories.
- **FR-002**: The system MUST retain transaction details only when they are supported by recorded trade facts; it MUST NOT present unverified currency or payment amounts as facts.
- **FR-003**: The system MUST associate each remembered interaction with the relevant villager and player, and MUST keep one player's memory isolated from another's.
- **FR-004**: The system MUST keep a bounded history of recent events for each villager-player relationship.
- **FR-005**: The system MUST maintain a relationship summary that changes only in response to defined interaction outcomes and remains within a documented range.
- **FR-006**: The system MUST select a bounded set of relevant memories for returning-player dialogue.
- **FR-007**: The system MUST treat current stall offers as the source of truth for present availability and MUST distinguish them from past purchases.
- **FR-008**: The system MUST avoid asserting a past relationship or trade when no corresponding memory exists.
- **FR-009**: The system MUST allow authorized administrators to inspect a selected villager-player memory and clear it.
- **FR-010**: The system MUST restrict memory inspection and clearing to authorized administrators and MUST avoid revealing unrelated players' memory data.
- **FR-011**: Clearing a memory MUST remove both the relationship memory and matching trade records for the selected villager-player pair, invalidate cached copies immediately, and prevent responses generated afterward from using those details.
- **FR-012**: The system MUST continue normal villager trading and dialogue behavior when memory data is missing, malformed, or unavailable.
- **FR-013**: The system MUST apply a defined retention policy to detailed trade history and other time-sensitive memories so stale records do not accumulate indefinitely or mislead later dialogue.

### Key Entities

- **Villager identity**: A specific villager and their persistent name, profession, personality, and current trade offers.
- **Player-villager relationship**: A memory scoped to one player and one villager, containing a bounded relationship summary and recent interaction facts.
- **Interaction event**: A verified visit, completed trade, or other supported action, with only the details established by that action.
- **Trade record**: A completed transaction record containing the item and quantity when known, and payment information only when its meaning is verified.
- **Spoken-line history**: A short-lived, server-wide set of recently spoken lines used to reduce repetition rather than represent a persistent relationship memory.

## Success Criteria

### Measurable Outcomes

- **SC-001**: In a review of 100 dialogue responses based on known interaction records, 100% of stated past items and quantities match the records, and none assert an unverified payment amount.
- **SC-002**: At least 90% of returning-player dialogue samples that have relevant stored facts use at least one appropriate remembered detail without confusing it with current stock.
- **SC-003**: In all tested first-time interactions, dialogue does not claim a previous visit, trade, or established relationship.
- **SC-004**: Authorized administrators can inspect and clear a selected relationship memory in no more than three actions, and cleared details are absent from the next generated response.
- **SC-005**: Unauthorized inspection and clearing attempts disclose or change zero memory records.
- **SC-006**: When memory storage is unavailable or malformed, players can still complete villager trades and receive dialogue without a memory-related failure.

## Assumptions

- Villager memories are intended to personalize in-game dialogue and are not an authoritative accounting ledger.
- Memory is scoped to a villager-player pair; server-wide spoken-line repetition tracking remains separate and short-lived.
- Administrators are the only users who need memory inspection and clearing controls in the first release.
- Detailed trade records are retained for 90 days by default, after which they are removed from dialogue context and cleaned up; the retention duration can be adjusted during planning if the existing data model requires a different policy.
- Existing memories may contain legacy event text that is less reliable than detailed trade records; legacy text must not be treated as verified transaction evidence.
- This feature does not change villager trade prices, player balances, or the economy rules.
