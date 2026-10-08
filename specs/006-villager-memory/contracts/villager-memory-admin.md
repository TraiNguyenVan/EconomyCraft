# Contract: Villager Memory Administration

**Feature**: `006-villager-memory`

## Surface

Add pair-scoped subcommands under the existing `/eco gossip` administrative command tree. Preserve its admin gate and re-check authorization when executing the action. Console sources may use UUIDs; in-game suggestions may offer online player names where resolution is unambiguous. Villagers are selected by UUID so inspection and deletion cannot affect a similarly named villager.

Suggested command grammar:

```text
/eco gossip memory inspect <villager-uuid> <player-uuid>
/eco gossip memory clear <villager-uuid> <player-uuid>
```

No command may inspect or clear by player alone, villager alone, or wildcard.

## Inspect behavior

- Return only the requested villager-player pair.
- Show whether a relationship row exists, its bounded summary fields, last interaction, and a bounded list of validated recent trade details.
- Do not render `price_paid` or `total_spent` as currency, and do not display unfiltered raw SQL/JSON or unrelated records.
- Missing relationship or trades returns a clear “no stored memory” result.
- Database errors return a failure message without leaking data; normal interaction and trading remain operational.

## Clear behavior

- Delete the relationship row and all trade rows matching both UUIDs as one repository operation/transaction.
- Evict the matching service-cache entry and invalidate asynchronous contexts for that pair.
- Report success only after the database operation completes successfully; distinguish a successful no-op (no rows existed) from a storage failure.
- Subsequent generated responses must not use pre-clear context. If an already-started request completes after the clear, discard it before delivery and before saving updated memory.
- Must not remove the villager profile, other players’ rows, current offers, or spoken-line repetition history.

## Authorization and input safety

- Require the existing gossip admin permission gate for both operations.
- Resolve and validate both UUIDs before database access.
- Use prepared statements and pair-scoped predicates for all reads/deletes.
- Denied commands return no stored data and perform no mutation.
