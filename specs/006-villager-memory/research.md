# Research: Enriched Villager Memories

**Feature**: `006-villager-memory`  
**Date**: 2026-10-08

## Evidence from current implementation

- `VillagerDatabase` uses SQLite and a single-thread scheduled executor for asynchronous database operations. It already defines `villagers`, `player_memories`, and `villager_trades`; both memory and trade data are keyed by villager and player UUID.
- `VillagerMemoryService.handleInteraction` currently gets memory from `memoryCache` and creates a default on a cache miss. It does not fetch persisted `PlayerMemory` through the existing `getPlayerMemory` method before building the individualized prompt. This can lose continuity after restart/cache eviction and is a central implementation gap.
- `handleInteraction` fetches up to five recent trades asynchronously. `GossipApiClient` and `VillagerDialoguePromptBuilder` receive relationship memory, trades, current offers, and recent spoken lines as separate context inputs.
- `PlayerMemory` bounds recent event strings at 10 and clamps sentiment to [-100, 100]. Its legacy `totalSpent` is not a verified ledger. `TradeRecord.pricePaid` explicitly lacks currency provenance and its prompt description omits the amount.
- The current trade-history query orders by timestamp and limits rows but does not exclude or delete rows older than 90 days. The relationship row has a single `last_interaction` timestamp; individual legacy event strings have no timestamps.
- The existing `/eco gossip` command is registered behind an admin gate and uses the existing `EconomyPermissions` conventions. This is the natural place for inspect/clear subcommands.
- Dialogue generation is asynchronous. Delivery is scheduled back onto the server executor after provider completion. A clear must therefore invalidate cached state and guard the completion callback; deleting database rows alone cannot revoke context already submitted to the provider.

## Decisions

### Reuse existing persistence and pair identity

Keep `player_memories` and `villager_trades`; add pair-scoped asynchronous read, inspection, delete, and retention operations to the existing repository. Use UUID pair identity for all data access. Avoid schema migration unless code-level implementation proves existing columns cannot express required behavior.

**Rationale**: Existing tables already contain the needed pair keys and are initialized with indexes. A new store duplicates identity and increases migration and consistency risk.

**Alternatives considered**: Introduce a separate memory-event table or a new structured event schema. Deferred because current requirements can be met with the existing bounded relationship row plus structured trade rows; retain as a future option if event-level dates or richer interaction types are later needed.

### Load persisted relationship before dialogue

On a memory-cache miss, fetch the pair-scoped row asynchronously; only create a default memory if no row exists. Use the result to build the prompt, then refresh the cache. Preserve the current non-blocking server-thread behavior.

**Rationale**: The stored relationship cannot personalize after restart unless it is actually loaded into the interaction path.

**Alternatives considered**: Keep an in-memory-only relationship after startup. Rejected because it contradicts persisted memory and loses established continuity.

### Retention

Use the specified 90-day window for detailed trade records. Filter expired rows from dialogue regardless of cleanup success, and delete expired rows asynchronously during database initialization/startup or another infrequent maintenance point. Because legacy interaction events do not have per-event timestamps, treat the relationship row as a unit: if `last_interaction` is older than the same 90-day window, remove it from dialogue and clean it up. Do not use `total_spent` as dialogue evidence.

**Rationale**: This gives old relationship summaries a clear lifetime without inventing event dates. Filtering at query time protects dialogue even if cleanup fails.

**Alternatives considered**: Retain relationship summaries indefinitely. Rejected because old sentiment/event strings are time-sensitive and the spec requires bounded retention. Add per-event timestamps/schema migration now. Deferred because it is not required for the bounded whole-row policy.

### Clear both stores and fence asynchronous responses

Perform the pair-scoped relationship and trade deletion in one database task/transaction; evict the pair from the service cache and advance a pair-scoped generation/version. Each interaction captures the version it used and, before saving memory or delivering its response, checks that the version is unchanged. If a clear occurred, discard that stale result and do not recreate the deleted row. A response already sent cannot be recalled.

**Rationale**: The accepted clarification requires immediate effect for subsequent responses, even if a prior provider request is still running. The version check prevents stale asynchronous completion from repopulating cleared state.

**Alternatives considered**: Delete rows only and let existing futures finish. Rejected because cached and in-flight context can survive the database deletion. Cancel provider requests. Not required and may not be supported once sent; discarding late results is deterministic.

### Admin inspection and clearing use existing command permissions

Add inspect and clear commands below `/eco gossip`, guarded by the same admin-only gate as the existing gossip administration commands. Return only the selected pair's fields and bounded recent details; validate UUID/name inputs and do not expose adjacent player data.

**Rationale**: This reuses the established command and permission surface without adding client UI or a new permission system.

**Alternatives considered**: Add a new graphical admin UI. Rejected for this scope because it expands UI work and is unnecessary to inspect or clear one selected relationship.

### Keep dialogue evidence conservative

Use only validated item names and positive recorded quantities from trade rows. Do not expose `price_paid`, `total_spent`, or legacy event strings as verified monetary evidence. Continue to treat live offers as current availability and past trades as historical facts.

**Rationale**: Existing storage has no verified currency provenance, and legacy memory text may contain estimates.

**Alternatives considered**: Display stored amounts with caveats. Rejected because generated dialogue may present caveated values as facts.

## Open implementation checks

- Check current serialization and DB error behavior before deciding whether cleanup methods should return result counts or completion status; failures must be logged and must not break trade/dialogue flows.
- Confirm time source/test seams used by adjacent gossip code before implementing the 90-day boundary. Expiration should be deterministic at exactly 90 days.
