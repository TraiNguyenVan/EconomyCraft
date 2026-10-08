# Implementation Plan: Gemini-Powered Villager Gossip in EconomyCraft

**Branch**: `002-gemini-villager-gossip` | **Date**: 2026-10-06 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/002-gemini-villager-gossip/spec.md`

## Summary

Add a Gemini-powered economic rumor engine to `EconomyCraft`. A lightweight background worker digests recent transaction records from `TransactionLogReader`, filters top economic events, anonymizes player identities into flavorful archetypes, and queries Google's Gemini REST API (`gemini-3.8-flash`) with structured JSON schema output to populate an in-memory rotating rumor pool. When players right-click a villager to trade, the villager greets them with a witty, profession-tailored economic rumor delivered as a private chat message ($O(1)$ memory read, 0ms main-thread latency), protected by a per-player cooldown and silent fallback circuit breaker.

## Technical Context

**Language/Version**: Java 21 (Minecraft 1.21.4 / 26.3, Fabric / Architectury Loom)

**Primary Dependencies**: Built-in Java 21 HTTP Client (`java.net.http.HttpClient`), Minecraft bundled Google `Gson`, Fabric API `UseEntityCallback` (zero external jar dependencies)

**Storage**: In-memory `AtomicReference<GossipPool>` for active rumors; disk transaction logs (`TransactionLogWriter`/`TransactionLogReader`); `config.json` under `gemini_gossip`

**Testing**: JUnit 5 test suite in `common/src/test/java` for JSON parsing, prompt building, anonymization, and cooldown mechanics

**Target Platform**: Linux dedicated Minecraft 1.21.4 server running in Docker container

**Project Type**: Server-side Minecraft Mod (`EconomyCraft`, multi-project Architectury Loom repo in `/home/yes/projects/EconomyCraft`)

**Performance Goals**: <1ms tick time overhead on villager interaction; 0 main thread blocking operations; background digest off-thread cycle <2s every 20 minutes

**Constraints**: Zero new external runtime dependencies added to jar; zero global chat spam; silent degradation on API unavailability or circuit breaker trip; strict player anonymization

**Scale/Scope**: ~10–20 rumors per generation cycle; supports unlimited concurrent players; cooldown tracked per player-villager pair

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Check | Status | Notes |
|---|---|---|---|
| **I. Live-System Truth** | Verified live mod state, transaction log structure, and dynamic multiplier | PASS | Reads dynamic inflation and transaction entries directly from live systems |
| **II. Verification Gate** | Mod deploy and build verification follows the host operator's procedure | PASS | Feature validation is detailed in [quickstart.md](quickstart.md); deployment steps belong to the host operator documentation |
| **III. Host-Fact Isolation** | No host IPs, volume UUIDs, or server specifics in mod code | PASS | Config reads generic env vars and JSON settings; all design kept clean of host specifics |
| **IV. Doc Artifact Set** | Mod sources and feature documentation live in the EconomyCraft repository | PASS | Spec and plan stay in `specs/002-gemini-villager-gossip/` |
| **V. Markdown Lint Gate** | All documentation passes `markdownlint-cli2` | PASS | Fenced code blocks tagged; 0 lint issues |
| **VI. Commit Attribution** | Trailed with `Co-authored-by: CapCapSever <vhung18.2@gmail.com>` | PASS | Required for all git commits |
| **VII. Operator Mechanics** | Deployment follows the operator runbook with the server stopped | PASS | Operational details live in the host's deployment runbook |
| **VIII. Secrets Control** | No hardcoded API keys; `.env` and `config.json` isolation | PASS | API key loaded via config/env with fallback |

## Project Structure

### Documentation (this feature)

```text
specs/002-gemini-villager-gossip/
├── plan.md              # This implementation plan
├── research.md          # Architecture and technology decisions
├── data-model.md        # Entities, state machine, and in-memory structures
├── quickstart.md        # Verification and testing guide
├── contracts/           # API and configuration schemas
│   ├── gemini-rest-contract.json
│   └── config-contract.json
├── checklists/
│   └── requirements.md
└── spec.md              # Feature specification
```

### Source Code (`/home/yes/projects/EconomyCraft`)

```text
common/src/main/java/com/reazip/economycraft/
├── gossip/
│   ├── GossipConfig.java                # Schema mapping and validation
│   ├── GossipCategory.java              # Profession enum mapping
│   ├── GossipPool.java                  # Immutable thread-safe rumor pool
│   ├── CooldownTracker.java             # Player-villager interaction cooldowns
│   ├── TransactionAnonymizer.java       # Archetype translation and injection sanitizer
│   ├── GeminiClient.java                # Async Java 21 HttpClient & circuit breaker
│   ├── GossipDigestWorker.java          # Periodic background aggregator & prompt executor
│   └── VillagerGossipListener.java      # Trade-open event hook and player messaging
└── config/
    └── EconomyConfig.java               # Integrated gemini_gossip configuration section
```

## Complexity Tracking

No constitution violations detected. Zero external dependencies introduced.
