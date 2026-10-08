# Bug Assessment: Inflation threaded into villager dialogue but never rendered; `VillagerTradeModifier.NEUTRAL` is a dead hook

- **Slug**: inflation-dialogue-dead-param
- **Created**: 2026-10-08
- **Source**: pasted text
- **Verdict**: valid
- **Severity**: low

## Report (verbatim)

> inflation is threaded into the dialogue call but
>      never rendered into the prompt text, and VillagerTradeModifier.NEUTRAL is a 1.0x passthrough with no call site

## Symptom

The inflation multiplier is read, passed through three method signatures, and then never written into the
prompt — so the model never sees it. And `VillagerTradeModifier.NEUTRAL` is referenced nowhere, so villager
prices are not actually influenced by villager identity or sentiment.

## Reproduction

1. `VillagerMemoryService.java:151` reads the multiplier; line 171 passes it on.
2. `GossipApiClient.java:236` forwards it into the prompt builder.
3. `VillagerDialoguePromptBuilder.java:50-158` declares `inflation` (line 55) and never uses it in the body.
4. `VillagerDialoguePromptBuilderTest.java:66` asserts `assertFalse(prompt.contains("inflation"))` — the test codifies the omission.
5. `grep -rn VillagerTradeModifier common/src/main/java` → only its own declaration. No callers.

## Suspected Code Paths

- `common/.../gossip/memory/VillagerDialoguePromptBuilder.java:50-158` — the defect; four overloads pass the value through unused.
- `common/.../gossip/GossipApiClient.java:189-236` — three overloads forwarding it.
- `common/.../gossip/memory/VillagerMemoryService.java:151,171` — correct producer, feeds a hole.
- `common/.../gossip/identity/VillagerTradeModifier.java:1-28` — dead; javadoc claims a spec ratified it, but no spec mentions it.
- `common/.../gossip/TransactionDigest.java:13-50` — same omission: `currentInflation` never rendered either.

## Root Cause Hypothesis

Two independent leftovers. (1) `inflation` was in the signature from the original `002` design and never given a
prompt line; the `assertFalse` test then locked the gap in place. (2) `VillagerTradeModifier` is an extension
point sketched before any consumer existed. Confidence: **high**.

## Proposed Remediation

**Preferred**:

1. Render the inflation figure in `buildSystemInstruction` — one line near the customer/relationship block,
   e.g. a price-climate sentence built with a coarse band (`stable` / `rising` / `runaway`) rather than a raw
   float. Keep instruction 5 intact so the model still never quotes a per-item price. Update the test at line 66.
2. Resolve the dead hook: either wire `NEUTRAL` into the price path (behaviour unchanged at 1.0x) and fix the
   false javadoc, or delete the file. Leaving it as-is is not an option.

**Alternative**: remove the parameter instead — but the `002` spec wants inflation-aware gossip, so that is a
product decision, not a cleanup.

**Files likely to change**:
- `common/.../gossip/memory/VillagerDialoguePromptBuilder.java`
- `common/.../gossip/identity/VillagerTradeModifier.java`
- `common/src/test/.../gossip/VillagerDialoguePromptBuilderTest.java`

**Tests to add or update**:
- Assert the band wording for two values (1.2 → stable, 7.5 → runaway); replaces the current `assertFalse`.
- Assert the "never state prices" instruction survives.
- If the modifier stays: assert `NEUTRAL` returns 1.0 across the sentiment range.

## Risks & Considerations

- One prompt line is negligible against the existing caps, but do not let it become a numeric dump.
- Villager speech changes immediately and may repeat price jokes; `recentSpokenTopics` exists for that.
- Must state "server-wide ratio, not a price" or the model may quote it as an item price.
- `VillagerTradeModifier` is in `common`, not `api/v1`, so deleting it is not an API break. Removing the
  `inflation` parameter *would* be a signature break on a public class.
- Deploy with the server stopped; a hot-swapped jar causes `ZipException: invalid LOC header`.

## Open Questions

- [NEEDS CLARIFICATION: raw multiplier or qualitative band?]
- [NEEDS CLARIFICATION: is `VillagerTradeModifier` ever meant to move prices, or should it be deleted?]
- [NEEDS CLARIFICATION: is the same omission in `TransactionDigest` in scope?]
- [NEEDS CLARIFICATION: `VillagerMemoryService.java:149` passes a hard-coded `0` balance to
  `resolveArchetype`, so every player resolves to the low archetype tier — intended, or a third instance?]