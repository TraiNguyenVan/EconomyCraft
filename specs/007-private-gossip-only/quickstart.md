# Quickstart Validation Guide: Private-Only Villager Gossip

Feature: `007-private-gossip-only` | Date: 2026-10-08

How to verify this change is correct. Covers compile/test gates and live behavioral checks.

For the code inventory and rationale see [research.md](research.md). For the command surface see
[contracts/gossip-command-surface.md](contracts/gossip-command-surface.md).

---

## Prerequisites

- JDK 21 on `PATH`, or use the containerized build described in
  [operations/build-mods.md](../../../operations/build-mods.md) (the host has no JDK by design).
- Network access for Gradle dependency resolution on first build.

---

## Gate 1 — Compile

```bash
cd /home/yes/projects/EconomyCraft
./gradlew :common:compileJava :fabric:compileJava :neoforge:compileJava
```

**Expected**: BUILD SUCCESSFUL.

**This gate alone proves a lot.** The deleted classes are referenced from `EconomyCraft.java` and
`EconomyCommands.java`; a missed reference fails compilation rather than shipping.

---

## Gate 2 — Test suite

```bash
./gradlew :common:test
```

**Expected**: BUILD SUCCESSFUL, all tests pass. Test count drops from ~135 to ~90.

### Gate 2a — Reflection-coupled config test (highest risk)

`BundledConfigTest` asserts **bidirectionally** by reflection between the shipped `config.json` and
`GossipConfig`'s record components. It fails loudly on either drift direction:

| Failure message | Meaning |
|---|---|
| `matches no field — typo, or the field was removed` | A key is still in `config.json` but no longer declared |
| field-missing assertion | A declared field has no key in `config.json` |

`freshInstallWritesTheBundledDefaultUnchanged` additionally requires `Adapter.write`'s key emission order
to match the JSON file order. If this test fails on ordering, reorder `out.name(...)` calls in
`GossipConfig.Adapter.write` to match `config.json`.

### Gate 2b — Verify removals took

```bash
cd /home/yes/projects/EconomyCraft
for s in GossipPool GossipCategory ProfessionMapper GossipDigestWorker TransactionDigest \
         generateRumors generateSingleRumor parseGossipJson diversifyRumors \
         tryAcquirePublicBroadcast isPublicBroadcastOnCooldown formatDigest \
         refreshIntervalMinutes poolSizePerCategory publicChatChance; do
  printf '%-32s %s\n' "$s" "$(grep -rn "$s" common/src fabric/src neoforge/src api/src 2>/dev/null | wc -l)"
done
```

**Expected**: `0` for every symbol.

Then confirm nothing in the private path was collaterally removed:

```bash
grep -rn "resolveArchetype\|MAX_DETAIL_LENGTH\|INSTRUCTION_OVERRIDE_PATTERN" common/src/main/java | head
grep -rn "RecentSpokenTracker" common/src/main/java/com/reazip/economycraft/gossip/memory/
```

**Expected**: hits in both. These are the load-bearing survivors (see research.md Unknown 1).

---

## Gate 3 — Documentation consistency

`README.md` carries the `gemini_gossip` key table **twice**, at approximately lines 342-364 and 629-651.

```bash
grep -n "public_chat\|pool_size_per_category\|refresh_interval_minutes\|system_instruction" \
  README.md AGENTS.md common/src/main/resources/assets/economycraft/config.json
```

**Expected**: zero hits in all three files.

```bash
grep -n "gossip refresh\|gossip test" README.md
```

**Expected**: zero hits.

```bash
grep -c '"' common/src/main/resources/assets/economycraft/config.json
grep -n "gemini_gossip" -A 16 common/src/main/resources/assets/economycraft/config.json
```

**Expected**: exactly 9 keys in the `gemini_gossip` section.

---

## Gate 4 — Live behavior

Requires a deployed server. Follow the deployment procedure in
[operations/mod-dev-loop.md](../../../operations/mod-dev-loop.md) — **stop the server before replacing the
jar**, since writing a jar into a running server causes `ZipException: invalid LOC header` on any
not-yet-loaded class.

### 4a — Boot with a stale config (SC-007)

Keep the pre-removal `config.json` containing all five removed keys, deploy, start.

**Expected**: server reaches `Done (...)` with no config parse error. Gossip section reports correctly.
Trigger any admin-UI save (e.g. open and close the quests admin UI) and confirm the removed keys
disappear from the file — this proves FR-016 tolerance and the documented opportunistic prune.

### 4b — Privacy (SC-001)

Two players online.

1. Player A opens a villager trade UI.
2. **Expected**: A receives one private villager line. **B receives nothing.**
3. Repeat with B. A must receive nothing.

### 4c — Context richness (SC-006)

1. A trades with a villager (completing a real trade).
2. Wait past the cooldown.
3. A opens the same villager's trade UI again.

**Expected**: the line may reference the recorded purchase and current stock. It must **not** contain a
"Word from your fellow …" section, and must not state a price or spending amount that no record supports.

### 4d — Repetition avoidance (SC-004)

Have several villagers speak in sequence on a busy server.

**Expected**: lines vary; the recently-spoken mechanism still suppresses repeats.

### 4e — Anonymity and sanitization (SC-005)

Inspect the outbound prompt (or the mock-server capture in tests).

**Expected**: the player appears as an archetype ("a wealthy tycoon"), never as a literal username.

### 4f — Commands

```text
/eco gossip              → status, no pool line
/eco gossip status       → same
/eco gossip dialogue     → generates one line (blank profession defaults to farmer)
/eco gossip reload       → reloads service
/eco gossip memory inspect <villager-uuid> <player-uuid>   → works
/eco gossip memory clear   <villager-uuid> <player-uuid>   → works
/eco gossip refresh      → "Unknown subcommand"
/eco gossip test farmer  → "Unknown subcommand"
```

### 4g — Degradation (SC-003 / FR-015)

Set an invalid `api_key`, then interact with a villager.

**Expected**: no villager line, no error spam, trade UI opens normally. After 3 consecutive failures the
circuit breaker opens and `/eco gossip status` reports it.

---

## Definition of done

| Check | Gate |
|---|---|
| Compiles | Gate 1 |
| Full suite passes | Gate 2 |
| Zero removed symbols remain in any source set | Gate 2b |
| Private path survivors intact | Gate 2b |
| Docs and default config carry no removed key | Gate 3 |
| No gossip reaches other players | Gate 4b |
| Private dialogue retains memory/offer/trade context | Gate 4c |
| Memory inspect/clear still work | Gate 4f |
| Trading unaffected when AI is broken | Gate 4g |