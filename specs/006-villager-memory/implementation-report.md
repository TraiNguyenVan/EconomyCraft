# Implementation Report: Enriched Villager Memories

**Feature**: `006-villager-memory`  
**Date**: 2026-10-08

## Delivered

- Cache misses load eligible persisted relationship memory asynchronously. Database read failures produce an empty context and do not overwrite stored relationship state when dialogue or trade capture completes.
- Trade dialogue context is pair-scoped, limited to five recent eligible records, and includes only safe item descriptions with positive quantities. Payment fields and arbitrary legacy event strings are excluded from dialogue facts. Current offers remain separate from historical purchases.
- Relationship sentiment, interaction count, and event history are bounded. Only the application-generated `Visited stall` marker is treated as a safe visit event.
- A 90-day retention cutoff filters reads and asynchronous startup cleanup deletes expired trade rows and expired relationship rows. Records exactly at the cutoff remain eligible.
- Pair-scoped inspection and transactional clearing are available as `/eco gossip memory inspect <villager-uuid> <player-uuid>` and `/eco gossip memory clear <villager-uuid> <player-uuid>`. Authorization is checked at command execution and again before async results are returned.
- Per-pair generation fencing, locking, and ordered database operations prevent stale dialogue/trade completions from restoring cleared memory or delivering a late response.

## Validation

- `./gradlew -Pminecraft_version=26.3 :common:compileJava` — passed in the configured Java 25 container.
- `./gradlew -Pminecraft_version=26.3 :common:test` — passed.
- Added focused tests for pair-scoped deletion, retention boundaries, invalid trade projections, historical price omission, legacy trade-event exclusion, and no-history prompt instructions.
- Reviewed the quickstart scenarios against the implementation and the available common tests. Storage isolation and retention, prompt evidence filtering, bounded trade history, and current-offer separation have direct coverage. Admin command permission behavior, cache reload through a live interaction, storage-failure behavior, and the in-flight clear race are implemented but do not have dedicated integration tests in this change; validate those through server-side integration or in-game testing before deployment.
- No deployment or live-server restart was performed.

## Remaining validation

The remaining gaps are integration-level coverage for command authorization, actual async dialogue/cache behavior, injected database failures, and the clear-versus-in-flight response race. The source flow was reviewed, but those scenarios were not exercised end to end.
