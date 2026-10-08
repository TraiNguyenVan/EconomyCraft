# Feature Specification: Gemini-Powered Villager Gossip in EconomyCraft

**Feature Branch**: `002-gemini-villager-gossip`

**Created**: 2026-10-06

**Status**: Draft

**Input**: User description: "Gemini-powered villager economic gossip for EconomyCraft based on `/home/yes/.gemini/antigravity-cli/brain/2d1b89e9-def0-4b52-9db8-258ada729d39/gemini-villager-gossip-spec.md`"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Contextual Villager Economic Gossip on Trade Open (Priority: P1)

When a player interacts with a village merchant to trade, the villager greets them with a humorous, satirical rumor or story reflecting recent server-wide economic events (such as major auction sales, inflation shifts, or party tax levies). The rumor is delivered privately to the interacting player, ensuring zero chat clutter for others.

**Why this priority**: Core user journey that delivers immediate value and personality to Minecraft trading while keeping interactions completely non-intrusive.

**Independent Test**: Can be tested on any server instance with trading villagers: open a trade interface with an active economic history, verify the player receives a private rumor line, and verify other players receive no chat spam.

**Acceptance Scenarios**:

1. **Given** economic activity has occurred and a pool of rumors is available, **When** a player right-clicks a villager to open the trade interface, **Then** the player receives a private in-game message from the villager containing a humorous economic rumor.
2. **Given** a villager has just delivered a rumor to a player, **When** that same player closes and re-opens the trade interface within the interaction cooldown window (default: 3 minutes), **Then** the villager opens trading normally without repeating or sending a new rumor.
3. **Given** a player is trading with a villager and receiving rumors (with default `public_chat = false`), **When** other players are online elsewhere in the world, **Then** no broadcast messages or rumor notifications appear in global chat.
4. **Given** public chat is enabled in configuration (`public_chat = true`), **When** a player opens trade with a villager, **Then** the villager's rumor is broadcast to the public server chat.

---

### User Story 2 - Profession-Themed Gossip Personalities (Priority: P2)

Different villager professions exhibit distinct commentary styles and focus areas matching their trade expertise. Farmers gossip about harvests, bread prices, and inflation; Blacksmiths and Armorers gossip about diamonds, netherite, and weapon auctions; Clerics and Librarians comment on party politics and taxes; and Nitwits invent absurd economic conspiracy theories.

**Why this priority**: Deepens roleplay immersion and rewards players for exploring different villagers across the world.

**Independent Test**: Trade with villagers of different professions (Farmer, Weaponsmith, Cleric, Nitwit) and verify that the delivered rumors reflect each profession's designated thematic focus.

**Acceptance Scenarios**:

1. **Given** a pool of categorized rumors exists, **When** a player trades with a Farmer villager, **Then** the rumor relates to agriculture, food commodities, or general cost of living.
2. **Given** a pool of categorized rumors exists, **When** a player trades with a Blacksmith, Weaponsmith, or Toolsmith, **Then** the rumor relates to weapons, armor, raw minerals, or high-value equipment transactions.
3. **Given** a pool of categorized rumors exists, **When** a player trades with an unemployed villager or Nitwit, **Then** the villager shares an exaggerated or nonsensical rumor.

---

### User Story 3 - Automatic Transaction Digestion & Privacy Anonymization (Priority: P3)

The system periodically processes recent server economic records and synthesizes them into fresh batches of rumors using the configured AI language model. Player identities are automatically anonymized into thematic archetypes (e.g., "a wealthy tycoon", "a shadowy rebel", "a royal noble") to generate engaging folklore without exposing private player actions or causing interpersonal friction.

**Why this priority**: Ensures continuous novelty and dynamic content while preserving player privacy and preventing toxicity.

**Independent Test**: Generate a series of transactions with distinct player names and factions, run the digestion cycle, and verify that all generated rumors reference archetypes rather than real player usernames.

**Acceptance Scenarios**:

1. **Given** recent transaction logs containing player usernames, **When** the periodic background digest generates rumors, **Then** all generated rumors reference contextual archetypes instead of literal player usernames.
2. **Given** the background digestion cycle is configured with an interval, **When** the interval elapses, **Then** the rumor pool is refreshed with newly generated rumors reflecting the latest economic records.

---

### User Story 4 - Resilient Silent Fallback (Priority: P4)

If the AI language model service is disabled, unconfigured, network-constrained, or experiencing service errors, the server gracefully degrades. Villagers simply conduct trades normally in silence without throwing errors, disconnecting players, or generating chat warnings.

**Why this priority**: Reliability is paramount; server stability and core gameplay must never be impaired by external service availability.

**Independent Test**: Simulate an unavailable or invalid AI service key, open trades with villagers, and verify that trades function identically to vanilla Minecraft with zero console errors or user-facing disruptions.

**Acceptance Scenarios**:

1. **Given** the AI language model integration is unconfigured or disabled in settings, **When** a player opens a trade window, **Then** the trading window opens normally with no gossip message and no error notification.
2. **Given** the AI service encounters repeated network failures or rate limits, **When** the threshold for failure is reached, **Then** the system enters a quiet cool-off period without interrupting server ticks or trading interactions.

---

### Edge Cases

- **Zero Recent Transactions**: When the server has just started or no transactions have occurred in the lookback window, the background worker generates general economic commentary based on server inflation metrics.
- **Rapid Window Cycling**: When a player rapidly re-opens a trade interface to cycle trades, the per-player/per-villager cooldown prevents message re-triggering.
- **Unemployed / Custom Villagers**: When interacting with an entity whose profession is unrecognized or general, the system smoothly falls back to universal/general gossip lines.
- **External Service Latency / Outages**: External AI generation runs entirely on a background thread; trade interactions on the main game thread strictly read from an in-memory cache and never block on network I/O.

---

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST provide an automated background worker that periodically digests recent server transaction history and requests structured rumors from the AI model service.
- **FR-002**: System MUST support configurable model selection with default model set to `gemini-3.8-flash`.
- **FR-003**: System MUST support API key configuration via server configuration file with fallback to the `GEMINI_API_KEY` environment variable, transmitting the key via the secure `x-goog-api-key` HTTP header rather than URL query parameters.
- **FR-004**: System MUST anonymize all player identifiers in transaction prompts into thematic character archetypes prior to transmitting data to the external service.
- **FR-005**: System MUST categorize generated rumors into profession groups (`farmer`, `blacksmith`, `cleric`, `librarian`, `nitwit`, `general`).
- **FR-006**: System MUST deliver rumors privately to the interacting player by default, but MUST provide a configuration option (`public_chat: false`) that, when enabled, broadcasts the villager's speech publicly to server chat.
- **FR-007**: System MUST enforce a configurable cooldown (default: 3 minutes) per player-villager pair before delivering another rumor.
- **FR-008**: System MUST implement a circuit breaker that pauses external requests for 30 minutes after 3 consecutive service errors.
- **FR-009**: System MUST operate silently when the AI service is disabled or unavailable, allowing normal trading without player-visible errors.
- **FR-010**: System MUST cache generated rumors in memory so trade-open interactions have zero network latency ($O(1)$ read).
- **FR-011**: System MUST configure Gemini safety settings to `BLOCK_ONLY_HIGH` across all standard harm categories to prevent false-positive censorship of medieval economic terminology.
- **FR-012**: System MUST configure `thinkingBudget: 0` for `gemini-3.8-flash` requests to achieve near-instant generation (<1s) and minimize token consumption.

### Key Entities

- **Transaction Digest**: Aggregated summary of notable economic events (auctions, shop purchases, orders, tax collections) over a lookback window.
- **Rumor Item**: A single-sentence dialogue line carrying satirical economic commentary, tagged with an applicable villager profession.
- **Gossip Pool**: An in-memory, thread-safe collection of available rumors partitioned by villager profession.
- **Interaction Cooldown Record**: Mapping between a player and a specific villager tracking timestamp of last delivered dialogue.

---

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Trade interface opening experiences zero measurable latency impact (<1 millisecond added to trade opening on the main game thread).
- **SC-002**: 100% of delivered rumors are sent strictly to the interacting player's chat view, resulting in zero unintended global chat messages.
- **SC-003**: 0% of generated rumors contain literal player usernames when player anonymization is enabled.
- **SC-004**: System recovers automatically from service interruptions without requiring a server restart once external connectivity is restored.
- **SC-005**: When external service connectivity fails, 100% of villager trades complete normally without game interruptions or player disconnection.

---

## Assumptions

- Villager speech is delivered as private, formatted chat messages directly to the interacting player.
- The default generation cycle interval of 20 minutes provides sufficient variety while maintaining low token usage.
- All rumors are generated in humorous, satirical English with villager mannerisms.
- The feature is integrated into the server's primary economy mod (`EconomyCraft`) where transaction logs and event hooks are co-located.
