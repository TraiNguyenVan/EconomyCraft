# Phase 0 Research: Private-Only Villager Gossip

Feature: `007-private-gossip-only` | Date: 2026-10-08

This document records the resolved unknowns from the plan's Technical Context, plus the full deletion
inventory that implementation depends on.

---

## Unknown 1: Does `ProfessionMapper` / `GossipCategory` survive the pool removal?

**Decision**: DELETE both. `VillagerProfile.profession()` is a plain `String`, not a `GossipCategory`.

**Rationale**: `VillagerMemoryService.java:142` computes `GossipCategory category = ProfessionMapper.fromEntity(villager);`
and line 143-144 uses it in exactly one expression: `pool.getRumors(category)`. With the pool gone,
the local variable becomes unused and the whole `ProfessionMapper` mapping layer has no caller.

The load-bearing question was whether `VillagerProfile.profession()` needs the mapping to populate prompts.
It does not. `VillagerProfile` is
`record VillagerProfile(UUID, String name, String profession, String biome, List<String> traits, String quirk, String backstory, long createdAt, long lastSeen)`
— `profession` is a `String`, defaulted to `"none"` when blank, persisted as TEXT (confirmed by
`VillagerDatabaseTest`). `VillagerDialoguePromptBuilder` reads it via `profile.profession()` in the persona
block (line 68) and in the removed grapevine block (line 97). It never touches `GossipCategory`.

`GossipCategory` exists only to partition `GossipPool`'s `Map<GossipCategory, List<String>>` and to provide
the six suggestion strings for `/eco gossip test`. Both consumers are being deleted.

**Alternatives considered**:
- Keep `GossipCategory` as a profession vocabulary — rejected: nothing would read it, and it would be
  the exact "orphaned-but-alive" code the user asked to eliminate.
- Keep `ProfessionMapper` and repoint it at `String` — rejected: `VillagerProfile.profession` is already
  a `String` sourced elsewhere; a second mapping layer between them would be redundant indirection.

**Consequence**: `VillagerSeeder` also needs checking for `GossipCategory` usage (see Inventory).

---

## Unknown 2: Will an existing `config.json` with removed keys still boot?

**Decision**: YES. No migration needed. FR-016 is satisfied by existing behavior.

**Rationale**: `GossipConfig.Adapter.read` (lines 325-390) is a hand-written `TypeAdapter` using
`JsonReader`. Its `while (in.hasNext())` loop has a terminal `default -> in.skipValue();` (line 367).
Any key not in the switch is skipped without error. So `public_chat`, `public_chat_chance`,
`refresh_interval_minutes`, `system_instruction`, and `pool_size_per_category` in an operator's
existing file are silently ignored and boot proceeds normally.

A second, non-obvious consequence discovered during research:

- **Stale keys are never pruned on load.** `EconomyConfig.mergeNewDefaultsFromBundledDefault()` →
  `addMissingRecursive()` (lines 377-394) only *adds* missing keys. Removed keys survive on disk
  indefinitely, harmlessly.
- **But any later `save()` silently rewrites them out.** `EconomyConfig.save()` (lines 268-283)
  serializes the in-memory instance, and `Adapter.write` emits only declared components. `save()` is
  reached from `EconomyManager.java:778`, `admin/AdminQuestsUi.java:164,170,176,185,197,209,251,280`,
  and `admin/AdminSettingsUi.java:66`. So an unrelated admin-UI action drops the removed keys with no
  log line. This is a hidden mutation worth a CHANGELOG note, but not a boot hazard.

**Shipped default resource**: `common/src/main/resources/assets/economycraft/config.json:227-242`
contains all five removed keys (lines 231, 235, 236, 238, 240). Must be edited: 14 keys → 9.

**Critical test coupling**: `BundledConfigTest` asserts **bidirectionally** between the shipped
`config.json` and `GossipConfig`'s record components, by reflection:
- lines 114-118: every key in the file must match a declared field (leftover keys FAIL with
  "matches no field — typo, or the field was removed")
- lines 120-148: every declared field must have a key with a matching value (new fields missing FAIL)

Affected tests: `bundledDefaultMatchesEveryConfigFieldInBothDirections` (67-76),
`shippedDefaultsSurviveTheirOwnClamp` (78-91), `freshInstallWritesTheBundledDefaultUnchanged` (93-103).
The third additionally requires `Adapter.write`'s key order to stay byte-equal to the JSON file order.
**`config.json` and `GossipConfig` must be changed together, in lockstep.**

**Alternatives considered**:
- Write a config migration that strips removed keys on load — rejected: unnecessary complexity for keys
  that are already ignored, and it would add a write on a read path.
- Log a warning on encountering removed keys — considered, deferred. Nice for operators but not required
  by any spec requirement; adds a migration-detection mechanism for cosmetic value.

---

## Unknown 3: Orphaned-but-tested helpers (`diversifyRumors`, `formatTransaction`)

**Decision**: DELETE `diversifyRumors` + `LEADING_GRUNT_PATTERN`. KEEP `formatTransaction`.

**Rationale**:
- `diversifyRumors` (`GossipApiClient:899-929`) and `LEADING_GRUNT_PATTERN` (`:896-897`) have exactly one
  caller each other / `parseGossipJson` (`:881`, `:911`). Both die with `parseGossipJson`. Their tests
  (`GossipApiClientTest:351-376`, `ChallengerStressTest:508-518`) must go too. Not named in the original
  remove list but unreachable — keeping them violates the "no orphaned machinery" story (US2).
- `formatTransaction` (`TransactionAnonymizer:195-296`) has ~15 test cases but **no production caller**
  after `GossipDigestWorker:428` is deleted. It is NOT on the private path. Kept because it is a
  public, tested, non-dead utility in a class the private path still uses for `resolveArchetype` and the
  injection sanitizer; deleting it is scope creep beyond "public gossip". Flagged here so it is not
  mistaken for accidental leftover.

---

## Unknown 4: Does removing `GossipDigestWorker` break any scheduling?

**Decision**: No scheduler registration needs removal; one scheduled executor dies with the file.

**Rationale**: `GossipDigestWorker.start()` is **already a no-op scheduler** — it sets `running = true`,
logs "on-demand dynamic mode", and never calls `executor.schedule(...)`. The only actual scheduling was
two self-retries inside the worker itself (`executor.schedule(this::runDigestCycle, 30, TimeUnit.SECONDS)`
at lines 218 and 227), both in the deleted file. The executor is created at `GossipDigestWorker.java:96`
via `EconomyExecutors.newSingleThreadScheduledExecutor("EconomyCraft-AI-Worker")` and owned by `stop()`
at 157-167. `EconomyCraft.onServerTick` registers no gossip task.

**Notable finding**: `refresh_interval_minutes` was therefore **already inert in the shipped jar** —
nothing has read it since the periodic scheduler was replaced by on-demand mode. This matches the
project's documented history of inert config keys. Worth stating plainly in the CHANGELOG so the removal
is not mistaken for a behavior regression.

---

## Unknown 5: Where does `GossipApiClient` live after the worker is deleted?

**Decision**: Hoist to a `private static volatile GossipApiClient gossipApiClient` field on `EconomyCraft`.

**Rationale**: `reloadGossipService` currently constructs `GossipApiClient` at line 155 *inside* the
`if (config != null && config.enabled())` block, because its only long-lived consumer was the worker
created at 156. With the worker deleted, two **surviving** subcommands need a client:
- `/eco gossip status` → `getGossipWorker().getApiClient().isCircuitOpen()` (line 1486)
- `/eco gossip dialogue [prof]` → `generateIndividualDialogue(...)` (line 1599)

Without hoisting, both break silently at runtime. Assign at line 155; null at the points the worker was
nulled (`:99`, `:150`, and in the disabled branch `:194`). This is the single largest non-mechanical
edit in the feature and the most likely thing to miss.

---

## Full Deletion Inventory

### DELETE — 7 files (~1,680 lines)

| File | Lines | Reason |
|---|---|---|
| `gossip/GossipPool.java` | 115 | Pool record, keyed by category |
| `gossip/GossipCategory.java` | 35 | Exists only to index the pool |
| `gossip/ProfessionMapper.java` | 105 | Maps profession → `GossipCategory`; no caller remains |
| `gossip/GossipDigestWorker.java` | 431 | Digest cycle, single-rumor generation, scheduler |
| `gossip/TransactionDigest.java` | 51 | Digest record, sole consumer is the worker |
| `test/gossip/ProfessionMapperTest.java` | 110 | Entirely maps → `GossipCategory` |
| `test/gossip/GossipDigestWorkerTest.java` | 238 | Entirely worker + digest + pool |

### EDIT — production (10 files)

- **`GossipApiClient.java`** — delete ~470 lines: `generateRumors` x2 (116-179), `generateSingleRumor` x2
  (292-359), `buildOpenAiSingleRumorRequestBody` x2 (361-428), `buildGeminiSingleRumorRequestBody` x2
  (430-515), `parseOpenAiSingleRumorResponse` (517-531), `parseGeminiSingleRumorResponse` (533-547),
  `parseSingleRumorJson` (549-560), `buildGeminiRequestBody` (692-765), `parseGeminiResponse` (767-796),
  `buildOpenAiRequestBody` (800-834), `parseOpenAiResponse` (836-860), `parseGossipJson` (864-894),
  `diversifyRumors` (899-929), `LEADING_GRUNT_PATTERN` (896-897), and the 3 banner comments.
  KEEP: all `*IndividualDialogue*` (562-688), `extractJsonObject` (931-946), `stripMarkdownFences` (948+),
  circuit-breaker state. Narrow `generateIndividualDialogue` overloads to drop `grapevineRumors`.
- **`GossipConfig.java`** — drop 5 record components (24, 28, 29, 31, 33), their DEFAULTs/MIN/MAX (42, 46,
  47, 51, 52-57, 72-73, 78-79), clamps (96-97, 102-103, 106-110, 116-117), all 4 legacy convenience ctors
  (120-176), and matching `Adapter.write`/`read` lines. KEEP `default -> in.skipValue()` (367).
- **`VillagerGossipListener.java`** — delete broadcast block (190-214), `poolSupplier` (34), `workerSupplier`
  (41), `setWorkerSupplier` (47-49), `getPoolSupplier` (245-247), `formatRumor` (223-233). Narrow `init`
  to drop the `AtomicReference<GossipPool>` param. Update class Javadoc.
- **`VillagerMemoryService.java`** — delete `poolSupplier` field (34), 6-arg ctor (46-55), `Supplier<GossipPool>`
  params (50, 61, 69), grapevine block (141-144). Narrow 7-arg ctor. KEEP `resolveArchetype` (149),
  `getRecentSpoken` (152), `recordSpoken` (187-189).
- **`VillagerDialoguePromptBuilder.java`** — delete `grapevineRumors` param from all 4 overloads and the
  prompt block (96-101).
- **`CooldownTracker.java`** — delete `lastPublicBroadcastMillis` (29), `tryAcquirePublicBroadcast` (117-136),
  `isPublicBroadcastOnCooldown` (138-147), `AtomicLong` import (8). Edit `clear()` (149-155) and Javadoc (12-13).
- **`TransactionAnonymizer.java`** — delete `formatDigest` (298-314) ONLY. KEEP all regex patterns,
  archetype catalogs, `sanitize`, `resolveArchetype`, `formatTransaction`.
- **`EconomyCraft.java`** — see Unknown 5. Also delete `GOSSIP_POOL` (44-45), `gossipWorker` (50),
  `getGossipPool()` (123-125), `getGossipWorker()` (131-133), `AtomicReference` import (33), worker
  stop-blocks (102-110, 144-152, 156-164). Drop first arg from `init` call (75).
  KEEP `getGossipCooldownTracker()` (127-129, now orphaned but public API).
- **`EconomyCommands.java`** — delete `import GossipCategory` (56), `refreshGossip` (1516-1537),
  `testGossip` (1539-1564), builder lines 1374-1384 (refresh + test subcommands). Edit
  `showGossipStatus` (1476-1514) and `testIndividualDialogue` (1566-1616) to use the hoisted client.
  Replace `GossipCategory.values()` suggestions (1379, 1389) with literal strings.
  KEEP: `status`, `dialogue`, `reload`, `memory inspect`, `memory clear`, `inspectGossipMemory`,
  `clearGossipMemory`, `parseUuid`, `showMotd`, `reloadAll`, `reloadGossip`.
- **`resources/assets/economycraft/config.json`** — remove 5 keys from lines 231, 235, 236, 238, 240.

### EDIT — tests (8 files, ~45 tests removed)

- **`GossipApiClientTest.java`** — delete 10 tests (`:87-173` pool bodies, `:325-349` parseGossipJson,
  `:378-408` single-rumor). Keep 6 (circuit breaker, 503, blank key, SAFETY, malformed, 429,
  stripMarkdownFences) — retype `generateRumors` calls to `generateIndividualDialogue` with dialogue-shaped
  stub responses. Delete `:351-376` `diversifyRumors`.
- **`ChallengerStressTest.java`** — keep part 1 (`:95-295`, 15 sanitization tests). Delete part 2
  (`:297-507`). Keep `:508-518`. Update `createClient` (`:74-77`) to the new `GossipConfig` signature.
- **`GossipEmpiricalStressTest.java`** — keep+edit `ConfigTests` (`:27-209`); delete `PoolTests` (`:214-412`)
  and `CategoryTests` (`:414-451`).
- **`ResilienceTest.java`** — keep only `:191-208` `passesChance`. Delete the other 6 tests.
  Reassess whether the mock `HttpServer` fixture (`:30-59`) is still worth it.
- **`CooldownTrackerTest.java`** — delete `:174-199` `testGlobalPublicBroadcastThrottle`. Keep 5.
- **`TransactionAnonymizerTest.java`** — delete `:280-292` `testFormatDigest`. Keep ~28.
- **`VillagerDialoguePromptBuilderTest.java`** — edit `:16-69`: drop grapevine local (`:44-47`) and arg
  (`:53`), delete the assertion on `:65`. Keep the rest.
- **`GossipConfigTest.java`** — delete `:110-127`, `:164-181`, and `:290-310`; split `:183-197` to keep
  `private_chat_chance`. Edit `:19-39`, `:50-108`, `:249-288`. Keep 6.

### KEEP unchanged — tests (3 files)

`RecentSpokenTrackerTest.java`, `VillagerSeederTest.java`, `VillagerDatabaseTest.java`

### EDIT — documentation (3 files)

- **`README.md`** — 8 edit sites. ⚠ The entire `gemini_gossip` key table is **duplicated verbatim** at
  `:342-364` and `:629-651`; editing one and missing the other is the likeliest doc failure. Rows to
  delete: `:358` `pool_size_per_category`, `:360` `public_chat`, `:361` `public_chat_chance`,
  `:362` `refresh_interval_minutes`, `:363` `system_instruction`, plus the duplicates at `:645-650`.
  Also delete command rows `:174` (refresh) and `:175` (test); edit `:173` (status), `:176` (dialogue),
  `:6`, `:267`, `:277`. ⚠ `:346-355` and `:633-642` are multi-line table cells.
- **`AGENTS.md`** — lines 18, 243 (config count 14 → 9), 283.
- **`CHANGELOG.md`** — one ADD entry, zero edits (no existing gossip references). Must not reintroduce
  host facts (AGENTS.md rule 9: no jar names, hashes, or server paths).

### UNTOUCHED

- `fabric/`, `neoforge/` — zero gossip references. The two villager mixins are the *profession discount*
  path (`MerchantEffects`), unrelated. `ProfessionVillagerPricesMixin:28` mentions "gossip transfer" in a
  comment explaining the inject point; cosmetic only.
- `api/` — zero gossip references; no downstream consumer breaks.
- `wiki/` — zero references across all 11 pages.
- `specs/001..006` — historical record. `002` mandated the broadcast and is deliberately NOT rewritten.
- `.archify/` — untracked build output; stale after this change, regenerate or ignore.

## Three Highest-Risk Items

1. **`EconomyCraft` must hoist `GossipApiClient`** or two surviving subcommands fail silently (Unknown 5).
2. **`BundledConfigTest` is bidirectional and reflection-driven** — `config.json` and `GossipConfig` must
   change together, including key order (Unknown 2).
3. **`README.md` duplicates its key table** — two sites, must be edited in one pass (Documentation).