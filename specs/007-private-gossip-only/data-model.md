# Phase 1 Data Model: Private-Only Villager Gossip

Feature: `007-private-gossip-only` | Date: 2026-10-08

This feature is a **removal**. It introduces no new entities and changes no persisted schema. What
follows is the authoritative retained/removed classification that implementation and review depend on.

---

## Persistence impact

**None.** `VillagerDatabase` (SQLite) schema, tables, and row formats are untouched. No migration is
required. `VillagerProfile.profession` is stored as TEXT and is unaffected by removing the
`GossipCategory` enum, because nothing ever wrote an enum constant into that column — it was always a
lowercase profession string from the vanilla villager.

The rumor pool is in-memory only (`EconomyCraft.GOSSIP_POOL`, an `AtomicReference` rebuilt every start),
so deleting it has no on-disk footprint at all.

---

## Retained entities

### Villager identity — `VillagerProfile` (record, persisted)

| Field | Type | Notes |
|---|---|---|
| `uuid` | `UUID` | Non-null, constructor-guarded |
| `name` | `String` | Defaults `"Villager"` when blank |
| `profession` | `String` | **Plain string**, defaults `"none"`. NOT a `GossipCategory` |
| `biome` | `String` | Defaults `"plains"` when blank |
| `traits` | `List<String>` | Immutable; defaults `["stoic"]` when empty |
| `quirk` | `String` | Persona quirk, defaults when blank |
| `backstory` | `String` | Persona backstory, defaults when blank |
| `createdAt` | `long` | Epoch millis |
| `lastSeen` | `long` | Epoch millis |

**Unchanged.** `profession` remains the source read by `VillagerDialoguePromptBuilder` for the persona
line. Removing `GossipCategory` does not affect it.

### Player-villager relationship — `PlayerMemory` (persisted, cached)

Carries a bounded relationship summary (sentiment, interaction count) and recent interaction events.
Drives the "Your relationship with them: …" prompt block. **Unchanged.**

### Trade record — `TradeRecord` (persisted)

Completed transaction with item and quantity when known; no verified payment amount. Drives the
"Historical purchase" prompt block. **Unchanged.**

### Private villager line — `IndividualDialogueResult`

A single generated sentence plus a sentiment delta (`-2..5`). The delta updates `PlayerMemory`. Delivered
via `ServerPlayer.sendSystemMessage` only. **Unchanged.**

### Player archetype — derived, not stored

`TransactionAnonymizer.resolveArchetype(playerUuid, faction, rank, playerName)` maps a player to one of
fifteen flavor descriptions across five factions × three wealth tiers, plus non-player cases. Every
generated private line depends on it. **Retained in full.**

### Recently spoken lines — `RecentSpokenTracker` (in-memory ring, capacity 8)

Server-wide short-lived record of delivered lines, read before generation (to avoid repetition) and
written after (`:187-189`). Because broadcasts are gone, every entry is now a private line — the ring
tracks only real dialogue. **Retained in full, unchanged.**

### Prompt sanitization — `TransactionAnonymizer` regex catalog

Eleven pre-compiled patterns stripping: Minecraft section/color codes, ampersand codes, control chars,
zero-width chars, line breaks, collapsed spaces, instruction-override phrases, role markers, code fences,
special tokens, and prompt-leak phrases. Protects the private path. **Retained in full.**

---

## Removed entities

| Entity | Where it lived | Why it goes |
|---|---|---|
| **Gossip pool** | `GossipPool` record, `EconomyCraft.GOSSIP_POOL` | Server-wide categorized rumor cache. Fed the broadcast and, as `grapevineRumors`, the private prompt. Both consumers removed |
| **Rumor category** | `GossipCategory` enum, 6 constants | Existed only to partition the pool and to supply `/eco gossip test` suggestions |
| **Profession→category map** | `ProfessionMapper`, 9 static methods | Sole caller was `pool.getRumors(category)` |
| **Transaction digest** | `TransactionDigest` record | Aggregated economic-event summary; sole consumer was the pool's prompt |
| **Digest worker** | `GossipDigestWorker` | Owned the digest cycle, single-rumor generation, and the AI executor |
| **Interaction event** | `TransactionDigest.toPromptContext()` | Digest-shaped prompt fragment |

---

## Removed configuration surface

The `gemini_gossip` section shrinks from **14 keys to 9**.

| Removed key | Former purpose |
|---|---|
| `public_chat` | Master switch for server-wide broadcast |
| `public_chat_chance` | Probability of broadcasting instead of staying private |
| `refresh_interval_minutes` | Periodic digest interval — **already inert**; the scheduler was replaced by on-demand mode |
| `system_instruction` | Prompt for generating the shared rumor pool |
| `pool_size_per_category` | Rumors cached per profession category |

| Retained key | Purpose |
|---|---|
| `enabled` | Master switch for the AI subsystem |
| `api_key` | Provider credential (also falls back to `GEMINI_API_KEY`) |
| `model` | Model identifier |
| `base_url` | Provider base URL (OpenAI-compatible supported) |
| `cooldown_minutes` | Per-player, per-villager interaction cooldown |
| `private_chat_chance` | Probability of generating a private line |
| `anonymize_players` | Anonymize identifiers before transmission |
| `temperature` | Sampling temperature |
| `dialogue_system_instruction` | Prompt overlay for private dialogue |

---

## Removed runtime state

| State | Location | Note |
|---|---|---|
| `AtomicReference<GossipPool> GOSSIP_POOL` | `EconomyCraft:44-45` | In-memory, rebuilt each start |
| `volatile GossipDigestWorker gossipWorker` | `EconomyCraft:50` | Nulled on stop/reload/disabled |
| `lastPublicBroadcastMillis` | `CooldownTracker:29` | Global broadcast throttle |
| `poolSupplier` | `VillagerGossipListener:34`, `VillagerMemoryService:34` | Both feed the removed pool |
| `workerSupplier` | `VillagerGossipListener:41` | Owned broadcast rumor generation |

**Added**: `volatile GossipApiClient gossipApiClient` on `EconomyCraft`. The client must outlive the
deleted worker so `/eco gossip status` and `/eco gossip dialogue` retain an AI client. Assigned inside the
enabled branch of `reloadGossipService`; nulled on stop, on reload teardown, and in the disabled branch.

---

## Behavior transitions

### Broadcast → private (the only delivery change)

**Before** (`VillagerGossipListener:183-214`), on villager interact:

```
private chance roll passes  → memoryService.handleInteraction(...)   // private line
AND public_chat enabled
AND public_chat_chance passes
AND global broadcast throttle permits → worker.generateDynamicRumor(category)
                                        → broadcastSystemMessage(...) // EVERYONE
```

**After**:

```
private chance roll passes → memoryService.handleInteraction(...)      // private line, unchanged
```

The cooldown is still recorded once per interact (`setCooldown` at `:181`) regardless of which branches
follow, so private-dialogue pacing is unchanged.

### Command surface

| Before | After |
|---|---|
| `/eco gossip` (status) | kept, retargeted to hoisted client |
| `/eco gossip status` | kept, pool lines removed |
| `/eco gossip refresh` | **removed** |
| `/eco gossip test [category]` | **removed** |
| `/eco gossip dialogue [prof]` | kept, grapevine arg dropped |
| `/eco gossip reload` | kept |
| `/eco gossip memory inspect <v> <p>` | kept |
| `/eco gossip memory clear <v> <p>` | kept |

Removed subcommands fall through Brigadier's default "Unknown subcommand", satisfying FR-011 / AC3
without a custom error handler.

### Prompt context

`buildSystemInstruction` loses the `grapevineRumors` parameter and the
`"Word from your fellow <profession>s across the realm:"` block. Every other block is unchanged:
persona, relationship, recent memories, recently-spoken lines, current stall offers, historical purchases,
and the no-eligible-facts guard.

---

## Validation rules (from requirements)

- No literal player username may appear in any transmitted prompt → archetype substitution stays mandatory.
- A returned line must never assert a past trade that no trade record supports.
- A pitch must reference currently offered stock, not a remembered purchase.
- A first-time player must not be described as having prior relationship.
- Clearing a memory must invalidate it immediately for subsequent generations.
- Trading must never fail because the AI service or memory store is unavailable.