# Data Model: Verified Villager Context

**Feature**: `005-verified-villager-context`
**Date**: 2026-10-08

## Persisted trade record

The feature continues to read the existing `villager_trades` record; it does not require a schema migration.

| Field | Existing meaning | Prompt eligibility |
| --- | --- | --- |
| `villager_uuid` | Villager associated with the completed exchange | Use to scope history to this villager |
| `player_uuid` | Player associated with the completed exchange | Use to scope history to this player |
| `item_name` | Recorded result item description | Include only when nonblank and well-formed |
| `item_count` | Recorded result quantity | Include only when a valid positive quantity |
| `price_paid` | Stored numeric amount; current writer derives it from first cost count × 10 | Treat as unverified and omit from dialogue |
| `timestamp` | Time of the completed exchange | Preserve existing ordering/recency behavior |

## Existing relationship memory

The existing `PlayerMemory.totalSpent` aggregate and legacy trade event strings can contain values derived from the same unverified trade input. They remain persisted for compatibility but are not eligible for individualized prompt context as monetary amounts. Legacy trade event strings containing a dollar amount are omitted from the prompt; new trade events record the item detail without a currency claim.

## Prompt projection

The prompt projection is derived from a completed `TradeRecord`, not new persistent player state. It may contain a validated item name and quantity. It must not contain `price_paid` unless a future, separately evidenced source can prove that the value is actual currency paid; no such provenance exists in the current record path.

Missing or invalid item fields are omitted. Records remain separate so repeated purchases are not combined into unsupported quantity or spending totals. An empty eligible history produces no prior-purchase claim.
