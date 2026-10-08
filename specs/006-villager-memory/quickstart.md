# Quickstart: Enriched Villager Memories

This is an implementation validation guide for the EconomyCraft source repository. It does not authorize deployment or a live-server restart.

## Prerequisites

- Work in the EconomyCraft source repository and use its configured Java toolchain/Gradle wrapper.
- Use an isolated test database; never point validation at the live server database.
- Configure mock/fake provider responses for dialogue-pipeline tests; do not use live provider credentials.
- See [data-model.md](data-model.md) and [contracts/villager-memory-admin.md](contracts/villager-memory-admin.md).

## Validation scenarios

1. **Persisted relationship reload**: write a relationship row, clear service cache/recreate the service, interact, and verify prompt input contains the persisted relationship rather than default/new-player context.
2. **Verified trade context**: seed a valid item and positive quantity plus arbitrary legacy `price_paid`; verify item/quantity can be represented while price and spend are absent.
3. **Malformed context**: seed blank/control-containing item text, nonpositive quantities, and malformed legacy event JSON; verify those details are omitted and normal dialogue still completes.
4. **Current offer boundary**: remember a historical item absent from current offers; verify the prompt distinguishes historical trade from present availability.
5. **Bounded relevance**: seed more events/trades than allowed for prompt inclusion; verify only the bounded selection is provided and no aggregate transaction claim is invented.
6. **Retention boundary**: test trade and relationship timestamps just before, exactly at, and just after the 90-day cutoff; older rows are omitted and eligible for cleanup, boundary/newer rows remain.
7. **Admin inspection isolation**: inspect one pair and verify the result includes no other player’s records or monetary interpretation of legacy fields.
8. **Admin clearing isolation**: clear one pair; verify its relationship and trade rows are deleted, another player’s rows and the villager profile remain, and the pair is treated as new afterward.
9. **Unauthorized access**: attempt inspect and clear as a non-admin; verify no memory is disclosed and no data changes.
10. **In-flight clear race**: start dialogue using a pair generation, clear the pair before provider completion, then complete the response; verify the late response is not delivered and its completion does not recreate the deleted memory.
11. **Storage failure**: force the memory DB read/cleanup/clear path to fail; verify no fabricated memories are used and trading/dialogue error behavior remains bounded.

## Test commands

Run focused common-module JUnit tests first, then the repository-prescribed build/test matrix for affected supported targets after implementation. The exact Gradle task/target flags should follow the source repository’s current build instructions; this planning workflow does not execute tests.

## Operational boundary

Build, deployment, boot-log verification, and in-game confirmation are separate operations. Do not deploy or restart a live server without a separate operator instruction.
