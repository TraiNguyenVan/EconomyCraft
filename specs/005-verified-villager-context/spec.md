# Feature Specification: Verified Villager Context

**Feature Branch**: unavailable (the pre-specify branch hook could not write to `.git`)

**Created**: 2026-10-08

**Status**: Draft

**Input**: Approved assessment handoff: keep existing individualized villager dialogue within verified player context; do not add new player data or expand data sharing. The recorded trade amount may be derived rather than actual currency paid.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Accurate references to completed trades (Priority: P1)

As a player returning to a villager, I want dialogue about our past trades to reflect what I actually traded and what I actually paid, so that the villager's memory feels credible.

**Why this priority**: The existing trade record can contain a derived amount that is presented as dollars, creating the clearest documented risk in the assessment.

**Independent Test**: Review dialogue for a returning player whose history includes a completed trade with known item details and a verified or unverified amount. Confirm that the item reference matches the trade and that an amount is stated only when its currency value is verified.

**Acceptance Scenarios**:

1. **Given** a returning player has a completed trade with verified item, quantity, and currency paid, **When** the player receives individualized villager dialogue, **Then** any transaction details mentioned match the verified trade.
2. **Given** a returning player's trade record has an estimated, derived, missing, or otherwise unverified currency amount, **When** the villager refers to that trade, **Then** the dialogue does not present that amount as actual money paid.
3. **Given** a player has no completed trade history with a villager, **When** the player receives dialogue, **Then** the villager does not claim that a prior trade occurred.

### User Story 2 - Existing context stays within its current privacy boundary (Priority: P2)

As a player, I want this accuracy improvement to use only the player information already used for individualized villager dialogue, so that improving transaction references does not silently broaden what is tracked or shared about me.

**Why this priority**: The assessment found no evidence that broader activity or preference data is needed, and the privacy boundary for such data is unresolved.

**Independent Test**: Compare the categories of player information used for individualized dialogue before and after the feature; confirm that no new category is collected or supplied as a result of this change.

**Acceptance Scenarios**:

1. **Given** a player interacts with a villager after this feature is introduced, **When** individualized dialogue is prepared, **Then** no new category of player information is collected or supplied because of this feature.
2. **Given** a dialogue flow generates general profession gossip rather than an individual response to a player, **When** this feature is active, **Then** that gossip flow remains outside the feature's scope.

### Edge Cases

- A legacy trade record has an item and quantity but no trustworthy currency amount; dialogue may refer to the verified item details but must not state an amount paid.
- Legacy relationship totals or trade-event memories contain amounts derived from trade inputs; dialogue must not repeat those amounts as currency paid.
- A trade record has a missing or malformed item name or quantity; dialogue must not invent those details.
- The player has interaction memories but no completed trades with this villager; dialogue must not imply a purchase.
- A player has several records for the same item; any reference must not combine or exaggerate quantities or spending across them.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: Individualized villager dialogue MUST NOT present a recorded or aggregated transaction amount as actual currency paid unless that amount is verified as the currency paid for the transaction.
- **FR-002**: When a transaction's currency amount is estimated, derived, missing, or otherwise unverified, dialogue MUST omit the monetary amount rather than describe it as money spent.
- **FR-003**: When dialogue refers to a prior trade, the named item and quantity MUST match the reliable details recorded for that completed trade; if either detail is unavailable or malformed, dialogue MUST omit that detail.
- **FR-004**: Dialogue MUST NOT claim that a player completed a trade with a villager when no completed trade record supports that claim.
- **FR-005**: This feature MUST NOT introduce a new category of player information to be collected or supplied for individualized villager dialogue.
- **FR-006**: This feature MUST apply only to individualized villager dialogue and MUST NOT change general profession gossip behavior.
- **FR-007**: When no verified transaction details are available, individualized dialogue MUST continue without transaction-specific claims.

### Key Entities *(include if data involved)*

- **Player-villager trade record**: A completed exchange associated with a player and a villager, with item details and a recorded amount whose reliability as actual currency may vary.
- **Verified transaction detail**: An item, quantity, or currency amount that can be substantiated as belonging to the completed exchange.
- **Individualized villager dialogue**: A private response to a player-villager interaction that may use existing relationship and trade context.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: In all reviewed dialogue cases that mention a monetary amount, 100% of the amounts match verified currency paid for the referenced completed trade.
- **SC-002**: In all reviewed cases where the recorded amount is estimated, derived, missing, or unverified, zero dialogue responses describe it as actual money paid.
- **SC-003**: In all reviewed dialogue cases that mention a prior trade, 100% of the item and quantity details match the reliable details for that trade; cases without supporting trade records contain no prior-trade claim.
- **SC-004**: No new category of player information is collected or supplied for individualized villager dialogue as part of this feature.
- **SC-005**: A majority of surveyed players who review transaction references describe them as accurate and not intrusive (baseline: unknown; the survey population and sample size must be established before evaluation).

## Assumptions

- The intended scope is individual villager responses to a player, not profession-wide gossip.
- Existing reliable item and quantity details may continue to support dialogue even when the recorded currency amount is not reliable.
- If actual currency paid cannot be established for a transaction, omitting the amount is an acceptable default.
- This feature does not promise to fix relationship-history reload behavior; that concern remains subject to separate runtime confirmation.
- Existing categories of player context and their current privacy treatment remain unchanged; broader data use requires a separate decision.
