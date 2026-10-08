# Contract: Individualized Villager Trade Context

## Scope

This contract applies to the individual player response built by `VillagerDialoguePromptBuilder`. It does not govern profession-wide gossip.

## Input

- Existing player-villager memory and completed trade records fetched for the current player and villager.
- Existing trade rows may contain a derived or otherwise unverified `price_paid` value.
- Existing relationship memory may contain an aggregate `total_spent` and legacy trade-event strings derived from the same unverified values.

## Output requirements

1. Include a past-trade reference only when a completed record supports it.
2. Include an item name or quantity only when that field is valid in the record.
3. Do not expose an estimated, derived, missing, or unverified amount as money paid.
   This includes aggregate relationship totals and legacy event strings, not only the trade row's `price_paid` field.
4. Do not combine multiple rows into unsupported item counts or spending totals.
5. When no eligible details exist, omit transaction-specific context and continue the existing individualized dialogue flow.
6. Do not add player data categories or change general profession gossip.

## Compatibility

Existing SQLite rows remain readable without migration. The prompt projection must handle legacy rows conservatively because those rows do not identify whether `price_paid` represents verified currency.
