# Feature Specification: Villager Dialogue Context Injection

**Feature Branch**: `004-villager-context-injection`

**Created**: 2026-10-07

**Status**: Draft

**Input**: User description: "focus economycraft mod, we gonna inject more context into the each villager conext, including what is it selling, and perplayer trade history with them"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Current Trade Offer Context in Dialogue (Priority: P1)

As a player interacting with an economic villager, I want the villager to reference the specific goods and items they currently offer for sale or buy in their trade inventory, so that their dialogue feels immersive, contextually aware, and tied directly to their active shop inventory rather than generic profession tropes.

**Why this priority**: Directly grounds the villager's personality in their actual trade offers at that exact moment, providing immediate conversational relevance upon opening the trade window.

**Independent Test**: Can be tested by opening the trading screen of a villager selling specific items (e.g. Diamond Chestplate, Enchanted Books, or Bread) and verifying that the generated dialogue mentions or references their active trade inventory/offers.

**Acceptance Scenarios**:

1. **Given** a villager has active trade offers (e.g., selling emeralds for wheat, or enchanted books for emeralds), **When** a player interacts with the villager, **Then** the dialogue prompt includes a concise summary of the villager's available trades, and the generated response reflects their current merchandise.
2. **Given** a villager has out-of-stock or disabled trades, **When** dialogue is generated, **Then** the villager may acknowledge supply shortages or highlight remaining available items.

---

### User Story 2 - Per-Player Detailed Trade History Awareness (Priority: P2)

As a returning customer, I want the villager to remember our exact trade history together (items previously bought or sold, transaction recency, and overall volume), so that the villager treats me like an established customer who bought specific items from them rather than an anonymous entity.

**Why this priority**: Deepens customer relationship tracking. While sentiment score exists, knowing *what* was bought or exchanged allows the villager to ask about past purchases (e.g., "How is that diamond pickaxe holding up?").

**Independent Test**: Can be tested by conducting a trade for a specific item (e.g., an Enchanted Bow), closing the window, and interacting again after cooldown to observe the villager referencing the past purchase.

**Acceptance Scenarios**:

1. **Given** a player has previously completed trades with a villager for specific items, **When** the player interacts with that same villager again, **Then** the prompt includes the player's past trade transaction history with that villager, and the dialogue can organically refer to those specific past exchanges.
2. **Given** a player has never traded with this villager before, **When** they interact, **Then** the villager recognizes them as a prospective or first-time buyer without past transaction history.

---

### User Story 3 - Context Budget and Prompt Token Optimization (Priority: P3)

As a server administrator, I want injected trade offers and history to be strictly summarized and bounded, so that LLM prompt token limits are respected, API latency stays low, and generation costs remain minimal.

**Why this priority**: Villagers can have dozens of trade recipes and lengthy transaction histories. Unbounded lists will exceed context windows, increase latency, and degrade roleplay consistency.

**Independent Test**: Can be tested with a max-level Master villager with full trade recipes and an extensive player purchase history, verifying prompt size remains strictly capped within configured limits.

**Acceptance Scenarios**:

1. **Given** a villager has many trade recipes (e.g., 10+ trade tiers unlocked), **When** constructing dialogue context, **Then** trades are summarized into a compact, sanitized list of top or notable offers.
2. **Given** a player has completed dozens of transactions over time, **When** building prompt context, **Then** only the most recent N purchases or summarized lifetime purchase categories are included.

---

### Edge Cases

- **Villager with no trades unlocked yet (Novice with 0 XP or uninitialized offers)**: Dialogue prompt falls back gracefully to indicating the villager is setting up shop or has a basic stock.
- **Unemployed or Nitwit villagers**: Do not have trade tables; context injection cleanly omits trade offers and transaction history without errors.
- **Player with corrupted or legacy history data**: If prior transaction items are unrecorded or malformed, the system falls back to aggregate spending figures without crashing.
- **Trades with complex custom item NBT/components**: Names and descriptions are sanitized to plain, clean item display names rather than raw serialization blobs.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST extract the villager's active trade inventory (current buy and sell offers, prices, and stock status) and inject it into the villager prompt generation context.
- **FR-002**: System MUST record the specific item names, quantities, and transaction values for each completed trade per player-villager pair in persistent storage.
- **FR-003**: System MUST retrieve and format the per-player trade history (most recent transactions and notable past purchases) for the interacting player and supply it to the dialogue prompt builder.
- **FR-004**: System MUST sanitize and truncate trade items and historical records to fit within a strict character/token budget (e.g., max top 5-8 active trades, max 5 most recent player purchases).
- **FR-005**: System MUST preserve asynchronous, non-blocking execution so that reading trade offers and querying trade history never stalls the Minecraft main server tick.
- **FR-006**: System MUST gracefully handle villagers without trades or players without trade history without breaking dialogue generation.
- **FR-007**: Prompt formatting MUST guide the AI model to naturally weave inventory and past interactions into conversational dialogue while maintaining the 1-sentence concise response constraint.

### Key Entities

- **VillagerTradeSnapshot**: Represents a compact view of a villager's active offers (e.g. inputs required, output item, price in currency/emeralds, whether trade is currently disabled).
- **TradeTransactionRecord**: Represents an individual completed trade between a specific player and villager, capturing timestamp, item transacted, quantity, and cost.
- **PlayerVillagerMemory**: Extended player memory entity incorporating episodic trade history alongside existing sentiment, total spent, and interaction counters.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100% of generated dialogue requests for trading villagers include active trade inventory context in the prompt payload.
- **SC-002**: For returning trading players, past purchase context is present in the prompt payload within 100% of eligible dialogue requests.
- **SC-003**: Prompt generation payload overhead for trade context remains strictly below 300 additional tokens / 1,200 characters per request.
- **SC-004**: Zero main-thread tick stalling or TPS drops introduced by fetching or formatting trade history.
- **SC-005**: 100% backward compatibility maintained with existing SQLite villager and player memory database tables.

## Assumptions

- The underlying LLM provider supports prompt sizes with this additional ~200-300 token contextual overhead without noticeable latency degradation.
- Item display names can be extracted cleanly using standard Minecraft translation keys/component text formatting.
- Trade history storage will be integrated into the existing SQLite database utilized by `VillagerDatabase`.
