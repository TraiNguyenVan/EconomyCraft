# Research: Verified Villager Context

**Feature**: `005-verified-villager-context`
**Date**: 2026-10-08

## Current transaction path

- `ProfessionHooks.onVillagerTrade` observes a completed villager trade, formats the trade result as an item description, and calls `recordTrade` with `offer.getCostA().getCount() * 10L` as `amountSpent`.
- `VillagerMemoryService.recordTrade` updates the existing `PlayerMemory` and writes a structured transaction through `VillagerDatabase.recordTradeTransaction`.
- `TradeRecord` contains item name, item count, `pricePaid`, and timestamp. `toPromptDescription()` appends `($<pricePaid>)` whenever the value is positive.
- `VillagerDialoguePromptBuilder` adds up to five fetched transaction descriptions to the individualized prompt under the customer's past purchases section.
- The prompt builder's general profession gossip path is separate and is outside this feature's scope.

## Decisions

### Do not present the current `pricePaid` as money

The current recording expression is a derived estimate based on the first cost stack count, not evidence of currency actually paid. Existing database rows have no provenance field showing that their amount is verified. Therefore, treat current and legacy values as unverified for dialogue and omit them from the prompt.

### Preserve reliable item and quantity context

Keep item name and quantity available when they are present and valid. Omit malformed or missing fields rather than fabricating a description. Do not merge separate trade rows into an aggregate claim.

### Keep persistence and privacy scope stable

No new player information category, database table, or transaction field is required to satisfy the safe behavior. Existing records remain readable; this feature changes how individualized dialogue presents them.

### Fail closed when history is absent

No completed records means no prior-trade section or claim. Interaction memories alone do not establish a completed purchase.

## Alternatives considered

- **Continue showing the derived amount with softer wording**: rejected because the model may still treat it as currency paid, contrary to FR-001 and FR-002.
- **Add a currency-provenance schema migration immediately**: deferred because no current recording path establishes actual currency paid. A schema field without a verified source would create false confidence and unnecessary data/model changes.
- **Remove trade history entirely from dialogue**: rejected because verified item and quantity details can still provide useful, accurate context.
