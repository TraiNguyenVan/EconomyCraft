# Data Model: Enriched Villager Memories

**Feature**: `006-villager-memory`

## Existing persisted entities

### Villager profile

Villager persona keyed by `uuid`. It provides identity/personality context and is not cleared by a player-memory clear.

### Player-villager relationship (`player_memories`)

| Field | Existing role | Feature constraints |
|---|---|---|
| `villager_uuid` | Villager half of composite key | Must match selected villager exactly |
| `player_uuid` | Player half of composite key | Must match selected player exactly |
| `sentiment` | Relationship sentiment | Existing clamp range [-100, 100]; not treated as verified event evidence |
| `interaction_count` | Aggregate interaction count | Nonnegative; aggregate only, not a claim about a specific event |
| `total_spent` | Legacy spend aggregate | Retained for compatibility; never presented as verified currency or dialogue evidence |
| `last_interaction` | Timestamp of most recent interaction | Drives whole-row relationship expiration at 90 days |
| `recent_events` | JSON array of up to 10 legacy event strings | Bounded; malformed JSON becomes empty; do not infer transactions or monetary facts from legacy strings |

Pair identity is `(villager_uuid, player_uuid)`. Clearing removes exactly that row and evicts the matching cache entry.

### Completed trade (`villager_trades`)

| Field | Existing role | Feature constraints |
|---|---|---|
| `id` | Row identity | Deletion is scoped by both pair UUIDs; retention removes rows older than cutoff |
| `villager_uuid`, `player_uuid` | Relationship scope | Both must match the selected pair |
| `item_name` | Recorded item description | Include only if nonblank and free of malformed/control text |
| `item_count` | Recorded quantity | Include only when positive and valid |
| `price_paid` | Legacy numeric field | No currency provenance; omit from dialogue and inspection display as monetary fact |
| `timestamp` | Trade event time | Exclude and purge rows older than the 90-day cutoff |

A clear removes all matching trade rows for this villager-player pair, regardless of age.

## Dialogue projection

Dialogue context is a bounded projection, not a new persistent entity:

- Load the persisted relationship before use when the pair is absent from cache.
- Include at most the existing bounded number of recent trade records (currently five), after the 90-day cutoff and validation.
- Select a bounded number of useful relationship details; malformed or unsupported details are omitted.
- Current stall offers remain a separate live snapshot. Historical items do not imply current stock.
- If no eligible memory or trade remains, represent the player as new to this villager.

## Cache and asynchronous clear fence

The service cache is keyed by the same UUID pair. Maintain a monotonically increasing in-process generation per pair (or an equivalent token). An interaction captures its pair generation when its memory context is chosen. A clear increments the generation and evicts cached memory. When an asynchronous dialogue request completes, it must compare generations before persisting updated memory or delivering the result. A mismatch means discard the stale result; no deleted data is recreated by that completion.

The fence only governs in-process asynchronous work. Persisted deletion is the durable source of truth after restart.

## Retention state rule

Use one 90-day cutoff based on epoch milliseconds. Trade rows older than the cutoff are ineligible at read time and are eligible for asynchronous purge. A relationship row whose `last_interaction` is older than the cutoff is ineligible as a whole and is purged. Equality with the cutoff is not expired; only timestamps strictly earlier than the cutoff are excluded. This avoids inventing timestamps for individual legacy event strings.
