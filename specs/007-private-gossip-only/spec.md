# Feature Specification: Private-Only Villager Gossip

**Feature Branch**: `007-private-gossip-only`

**Created**: 2026-10-08

**Status**: Draft

**Input**: User description: "in economycraft mod, remove all the public gossip modules, only keep the private message remain"

## Clarifications

### Session 2026-10-08

- Q: Which spec should carry the record of this gossip-removal change? → A: A new feature spec in the EconomyCraft repository; do not rewrite the shipped `002-gemini-villager-gossip` spec that mandated the broadcast.
- Q: Should the shared village-wide rumor pool survive as context for private villager dialogue, or be deleted along with the public gossip path? → A: Delete the entire pool pipeline and drop `grapevineRumors` from the private prompt.
- Q: Should `TransactionAnonymizer` and `RecentSpokenTracker` be deleted with the pipeline? → A: No. Both are load-bearing for the private path; delete only `TransactionAnonymizer.formatDigest` and all of `TransactionDigest`, and keep `resolveArchetype`, the prompt-injection sanitizer, and all of `RecentSpokenTracker`.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Villager speech stays private and still works (Priority: P1)

A player opens a villager's trade interface and receives a personal, in-fiction line from that villager.
No villager ever speaks into server-wide chat, and the line is as context-rich as it was before:
it still uses the villager's personality, the player's relationship memory, current stall offers,
recorded past purchases, and recent-spoken-line avoidance.

**Why this priority**: This is the behavior players actually see. If this survives the removal intact,
the feature is a success. Everything else in this spec is cleanup around it.

**Independent Test**: With two players online on a shared server, have each open a villager's trade
interface and confirm the other player sees no gossip message at all, while the opening player
receives a line referencing their own relationship and current stock.

**Acceptance Scenarios**:

1. **Given** two or more players are online, **When** one player opens a villager's trade interface, **Then** no villager gossip message appears in chat for any other player.
2. **Given** a villager has generated gossip, **When** any player interacts with it, **Then** the line is delivered only to that interacting player.
3. **Given** a returning player with stored memories and recorded past trades, **When** they open a villager's trade interface, **Then** the private line may still reference their relationship, their past purchases, and the villager's current stock.
4. **Given** a villager has spoken recently, **When** it generates a new private line, **Then** it still avoids repeating recently spoken lines.
5. **Given** a player interacts with a villager, **When** the private line is generated, **Then** the player's identity is still transmitted as an anonymous archetype, never as a literal username.

---

### User Story 2 - The codebase carries no orphaned gossip machinery (Priority: P2)

A maintainer changing the villager dialogue pipeline finds no public-gossip code left behind: no
unused pool classes, no unreachable digest path, no configuration keys that do nothing, no commands
that report on a cache nothing publishes, and no test suite asserting removed behavior.

**Why this priority**: The user asked to keep the codebase as clean as possible. Code retained "just in
case" is worse than code removed, because the next reader cannot tell whether it is live.

**Independent Test**: Grep the tree for every removed symbol and configuration key and confirm zero
production references, zero test references, and zero documentation references remain.

**Acceptance Scenarios**:

1. **Given** the removal is complete, **When** a maintainer searches for the removed pool, digest, and category types, **Then** no production code, test, or documentation file references them.
2. **Given** a server owner inspects `config.json` after upgrading, **Then** no removed configuration key is present, and the mod does not warn about or silently ignore a stale key left in an operator's file.
3. **Given** an operator runs the gossip administration command after the removal, **Then** only the subcommands whose behavior still exists are present, and removed subcommands return a clear "unknown subcommand" result rather than a stale success message.
4. **Given** the project is compiled and its test suite runs, **Then** the build succeeds with no reference to removed classes and no test remains whose subject no longer exists.
5. **Given** a maintainer reads the private dialogue prompt builder, **Then** no parameter, field, or prompt section exists for a source of rumors that is no longer produced.

---

### User Story 3 - Administrators keep the memory oversight they have today (Priority: P3)

An authorized administrator can still inspect and clear a villager-player memory after the removal,
because that capability is part of the private path and is not part of the public gossip system.

**Why this priority**: Memory inspection and clearing shipped under feature 006 and are owned by the
private dialogue path. Removing them would be scope creep beyond the public-gossip boundary.

**Independent Test**: As an authorized administrator, inspect and clear a selected villager-player
memory and confirm the cleared details are absent from the next generated line.

**Acceptance Scenarios**:

1. **Given** an authorized administrator, **When** they inspect a villager-player memory, **Then** the stored relationship summary and recent recorded events are shown.
2. **Given** an unauthorized player, **When** they attempt to inspect or clear a memory, **Then** no memory data is disclosed or changed.

---

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The system MUST NOT broadcast villager gossip to server-wide chat under any configuration, and MUST remove the configuration options that enabled it (`public_chat`, `public_chat_chance`).
- **FR-002**: The system MUST continue to deliver one private villager line to the interacting player when the private chat chance roll succeeds.
- **FR-003**: The system MUST preserve the private dialogue context that does not depend on the rumor pool: villager personality and backstory, player relationship memory, current stall offers, recorded past purchases, inflation, player archetype, and recently spoken lines.
- **FR-004**: The system MUST continue to anonymize the interacting player into an archetype before transmitting any prompt to the external service.
- **FR-005**: The system MUST continue to sanitize incoming prompt content against instruction-override, role-marker, prompt-leak, and special-token injection patterns.
- **FR-006**: The system MUST retain the per-player, per-villager interaction cooldown.
- **FR-007**: The system MUST retain the external service circuit breaker, so repeated failures pause requests instead of degrading the server.
- **FR-008**: The system MUST remove the rumor pool generation pipeline, including the periodic digest cycle and the single-rumor generation path.
- **FR-009**: The system MUST remove the pool from the private dialogue prompt, including the "Word from your fellow villagers across the realm" prompt section and the parameter that carries it.
- **FR-010**: The system MUST remove configuration keys that exist only to drive the removed pipeline (`refresh_interval_minutes`, `system_instruction`, `pool_size_per_category`).
- **FR-011**: The system MUST remove the administration subcommands whose only function was populating or sampling the rumor pool.
- **FR-012**: The system MUST remove the test suites, fixtures, and stress tests whose subject is a removed class or removed behavior.
- **FR-013**: The system MUST retain the shared-source anonymization and repetition-tracking utilities that the private path still calls, and MUST remove only the members that served the removed pipeline.
- **FR-014**: The system MUST preserve administrator memory inspection and clearing.
- **FR-015**: The system MUST continue to operate silently when the external service is disabled or unavailable, never interrupting trading.
- **FR-016**: The system MUST continue to load its configuration in a way that tolerates stale keys left in an existing `config.json` rather than failing to start.
- **FR-017**: Documentation (README, CHANGELOG, AGENTS.md, wiki pages) MUST be updated so no document describes the removed public gossip feature as present.

### Key Entities

- **Private villager line**: A single in-fiction sentence generated for one player by one villager, delivered only to that player, carrying a sentiment delta used to update their relationship memory.
- **Player-villager relationship**: A memory scoped to one player and one villager, holding a bounded relationship summary and recent interaction facts. Unchanged by this feature.
- **Villager identity**: A villager's persistent name, profession, personality, and current trade offers. Unchanged by this feature.
- **Trade record**: A completed transaction record holding item and quantity when known. Unchanged by this feature.
- **Player archetype**: The anonymous character description substituted for a player's identity in prompts. Retained.
- **Recently spoken lines**: A short-lived server-wide ring of recently delivered lines used to reduce repetition across all villagers. Retained; no longer mixed with generated pool rumors.
- **Anonymization and sanitization utilities**: Shared helpers that map player identities to archetypes and strip injection patterns from prompt content. Retained, minus the transaction-digest formatter.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: In tests with at least two players online, 100% of villager gossip messages are delivered to the interacting player only, and zero appear in other players' chat.
- **SC-002**: 100% of removed classes, methods, configuration keys, commands, and test suites have zero remaining references in production code, tests, and documentation.
- **SC-003**: The project compiles and its full test suite passes after the removal, with zero compile errors and zero test failures attributable to the change.
- **SC-004**: In a review of private dialogue samples generated after the removal, no sample contains a "Word from your fellow villagers" section or any reference to a shared pool rumor.
- **SC-005**: In 100% of generated private lines, the player is identified by archetype and never by literal username, and prompt-injection sanitization remains active.
- **SC-006**: Private dialogue retains its context richness: in a review of generated lines, returning players are still served lines that use stored memories, current offers, and recorded past purchases.
- **SC-007**: A server booting with an existing `config.json` that still contains the removed keys starts successfully and logs no parsing error.
- **SC-008**: Zero removed configuration keys remain in the shipped default configuration file.

## Assumptions

- "Public gossip" means the entire rumor-pool generation pipeline and the broadcast delivery path, not merely the broadcast call. Confirmed by the user.
- Villager speech is and remains exclusively private, delivered as a formatted chat message to the interacting player.
- Shared anonymization and repetition-tracking utilities are retained because the private path depends on them, not because they belong to the gossip pool.
- The removal is a code and documentation change; no server data migration is required, because the rumor pool is in-memory only and is rebuilt at every start.
- Player-villager memory data is persisted and is explicitly out of scope for removal.

## Out of Scope

- Removing or altering villager memory, trade records, or relationship scoring.
- Removing the external AI service integration, circuit breaker, or privacy protections.
- Changing the content, tone, or length rules of private villager dialogue.
- Server deployment, configuration hand-editing on a live server, or jar replacement.