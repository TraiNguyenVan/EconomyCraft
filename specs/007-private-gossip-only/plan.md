# Implementation Plan: Private-Only Villager Gossip

**Branch**: `007-private-gossip-only` | **Date**: 2026-10-08 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/007-private-gossip-only/spec.md`

## Summary

Remove the entire public villager gossip subsystem from EconomyCraft, leaving only the private
per-player villager dialogue path. This deletes the rumor-pool generation pipeline (pool, categories,
profession mapping, periodic digest, single-rumor generation), the server-wide broadcast path, the
configuration keys that only drove them, and the two administration subcommands whose only function was
populating or sampling the pool. It also decouples the private dialogue prompt from the pool.

Technical approach: pure deletion plus signature narrowing, plus one non-mechanical fix — hoisting
`GossipApiClient` to a static field on `EconomyCraft` so the two surviving `/eco gossip` subcommands
(`status`, `dialogue`) keep a client after the worker that owned it is deleted.

## Technical Context

**Language/Version**: Java 21 (Architectury multi-module Minecraft mod; `common` + `fabric` + `neoforge`)

**Primary Dependencies**: Architectury events (`InteractionEvent`), Gson (config + DB persistence), Java `HttpClient`, JUnit 5

**Storage**: SQLite via `gossip.storage.VillagerDatabase` (player-villager memories, trade records, villager profiles). Untouched by this feature. The rumor pool is in-memory only.

**Testing**: JUnit 5 via Gradle (`common/src/test/java/com/reazip/economycraft/gossip/`)

**Target Platform**: Minecraft dedicated server (Fabric + NeoForge), server-side only per Constitution Principle I

**Project Type**: Minecraft server-side mod

**Performance Goals**: Preserve zero-latency trade-open interaction. Private dialogue generation stays asynchronous and must never block the main thread.

**Constraints**: Must work with an unmodified vanilla client; no new custom packets; no mixins added. Existing `config.json` files containing removed keys must still boot.

**Scale/Scope**: ~1,680 lines deleted across 7 files; 19 files edited; test count drops from ~135 to ~90.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Evidence |
|---|---|---|
| **I. Server-side only** | PASS | No client code touched. No packets added. `fabric`/`neoforge` client entry points unchanged. |
| **II. Public API keeps the thread contract** | PASS | `api/` module has zero gossip references (verified by grep). No API surface removed. No new async boundaries introduced; one async call removed. |
| **III. Tax must be burned, not credited** | N/A | No tax/fiscal code touched. |
| **IV. Fiscal sources must be registered** | N/A | No fiscal source touched. |

**Gate result: PASS.** No violations, no Complexity Tracking entry required.

Post-design re-check: unchanged. Deletion only reduces surface; it cannot introduce a violation.

## Project Structure

### Documentation (this feature)

```text
specs/007-private-gossip-only/
├── spec.md              # clarified specification
├── plan.md              # this file
├── research.md          # Phase 0 — deletion inventory + resolved unknowns
├── data-model.md        # Phase 1 — retained vs removed entities
├── quickstart.md        # Phase 1 — validation guide
└── tasks.md             # Phase 2 (/speckit.tasks — NOT created by /speckit.plan)
```

### Source Code (repository root)

```text
common/src/main/java/com/reazip/economycraft/
├── EconomyCraft.java              # EDIT — hoist GossipApiClient, drop pool/worker
├── EconomyCommands.java           # EDIT — drop refresh/test subcommands, retarget 2 handlers
├── gossip/
│   ├── GossipApiClient.java       # EDIT — delete ~470 lines of pool/single-rumor code
│   ├── GossipConfig.java          # EDIT — drop 5 record components + clamps + ctors
│   ├── VillagerGossipListener.java# EDIT — delete broadcast block + pool wiring
│   ├── CooldownTracker.java       # EDIT — drop public-broadcast throttle
│   ├── TransactionAnonymizer.java # EDIT — drop formatDigest only
│   ├── RecentSpokenTracker.java   # KEEP unchanged
│   ├── memory/                    # KEEP, minus grapevine params
│   ├── storage/                   # KEEP unchanged
│   └── identity/                  # KEEP unchanged
├── gossip/GossipPool.java         # DELETE
├── gossip/GossipCategory.java     # DELETE
├── gossip/ProfessionMapper.java   # DELETE
├── gossip/GossipDigestWorker.java # DELETE
└── gossip/TransactionDigest.java  # DELETE

common/src/main/resources/assets/economycraft/config.json   # EDIT — 14 keys -> 9
common/src/test/java/com/reazip/economycraft/gossip/        # 2 DELETE, 8 EDIT, 3 KEEP
README.md / AGENTS.md / CHANGELOG.md                        # EDIT
fabric/ neoforge/ api/                                     # UNTOUCHED
wiki/                                                       # UNTOUCHED (zero references)
specs/001..006                                              # UNTOUCHED (historical record)
```

**Structure Decision**: Existing multi-module Architectury layout retained. No new directories. All
work is in-place deletion and narrowing within `common/`.

## Complexity Tracking

> No constitution violations. Entry not required.