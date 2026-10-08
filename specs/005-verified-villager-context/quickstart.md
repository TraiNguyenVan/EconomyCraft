# Quickstart: Verified Villager Context

This guide describes implementation validation scenarios. It does not authorize deployment or live-server changes.

## Scenarios

1. **Legacy derived amount**: provide a completed trade row with a valid item and quantity and a positive `price_paid` created by the existing `costCount * 10` path. Confirm the prompt contains the item and quantity but not a dollar amount.
2. **No trade history**: provide an empty history for a player-villager pair. Confirm the prompt contains no claim that the player purchased from that villager.
3. **Malformed item data**: provide records with blank item name, nonpositive quantity, or both. Confirm invalid details are omitted and not invented.
4. **Repeated rows**: provide multiple records for the same item. Confirm each reference remains supported by an individual row and no combined quantity or spend is asserted.
5. **Scope boundary**: exercise the general profession gossip prompt path. Confirm this feature does not alter its behavior or add individualized player history there.
6. **Legacy relationship aggregate**: provide memory with a positive `totalSpent` and a legacy trade event such as `Bought Emerald for $240`, alongside a valid trade row. Confirm the prompt includes neither the aggregate nor the legacy amount, while retaining eligible item/quantity context.

## Verification boundary

No current code path establishes verified currency provenance for villager trades. A positive `price_paid`, `totalSpent`, or legacy dollar-denominated trade event is therefore not proof of money paid. The persisted fields remain for compatibility; individualized dialogue omits those values. Build validation is separate from tests and deployment; do not deploy or restart the live server as part of this feature without separate authorization.
