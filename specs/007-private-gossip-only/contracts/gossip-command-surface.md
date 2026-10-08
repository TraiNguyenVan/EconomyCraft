# Contract: `/eco gossip` Command Surface

Feature: `007-private-gossip-only` | Date: 2026-10-08

The mod exposes no gossip API to downstream consumers — `api/` has zero gossip references, so there is no
binary-compatibility contract to preserve. The user-facing contract is the command tree.

Permission: `economycraft.admin` (any admin). Registered at `EconomyCommands.java:141-145` (standalone
`/gossip`) and `:198` (`/eco gossip`).

---

## Retained subcommands

### `/eco gossip` — status summary

Equivalent to `/eco gossip status`.

Reports: enabled state, model, base URL, cooldown, and circuit-breaker state.

**Removed from output**: the refresh interval line and the entire `Active Rumors: N rumor(s) across M
category(ies)` / `Categories:` block. Those were pool-shaped and had no meaning after removal.

### `/eco gossip status`

Same as above. Reads the hoisted `EconomyCraft.gossipApiClient` for circuit-breaker state instead of
`getGossipWorker().getApiClient()`.

### `/eco gossip dialogue [profession]`

Generates one private dialogue line for an ad-hoc villager/player pairing, outside a real interaction.

- `profession` argument is **optional**; blank falls back to `"farmer"`.
- Argument suggestions change from `GossipCategory.values()` (deleted enum) to a literal string list.

### `/eco gossip reload`

Reloads the gossip service from disk without restarting the server. Behavior unchanged.

### `/eco gossip memory inspect <villager-uuid> <player-uuid>`

Shows the stored relationship summary and recent recorded events for one pair. **Unchanged** — owned by
feature 006, which is out of scope for this removal.

### `/eco gossip memory clear <villager-uuid> <player-uuid>`

Removes the relationship memory and matching trade records for the pair, invalidating cached copies
immediately. **Unchanged.**

---

## Removed subcommands

| Subcommand | Handler | Reason |
|---|---|---|
| `/eco gossip refresh` | `refreshGossip` (1516-1537) | Only forced a pool digest cycle. Nothing publishes the pool |
| `/eco gossip test [category]` | `testGossip` (1539-1564) | Only sampled a rumor from the pool by category |

Behavior after removal: Brigadier's default `Unknown subcommand` response. This is the required
behavior per FR-011 and AC3 — a stale success message would be worse than an explicit error.

---

## Client-observable behavior

| Scenario | Expected |
|---|---|
| Player A opens a villager trade UI | Only A receives a villager line |
| Player B is online elsewhere | Receives nothing |
| Any player, any villager, any config | Zero messages in global chat |
| Cooldown is active for A + villager | No line generated for A |
| AI service disabled | Trade UI opens normally, no line, no error |

The trade UI must never be blocked, delayed, or closed by gossip logic. `InteractionResult.PASS` is
returned unconditionally.

---

## Configuration contract

Shipped default `config.json` ships exactly 9 keys in the `gemini_gossip` section. See
[../data-model.md](../data-model.md#removed-configuration-surface) for the retained/removed tables.

**Backward compatibility guarantee**: an operator's pre-existing `config.json` containing any of the 5
removed keys continues to boot normally. `GossipConfig.Adapter.read` terminates its key loop with
`default -> in.skipValue()`, so unrecognized keys are skipped without error. There is no migration step
and no operator action required.

The removed keys are pruned from disk opportunistically: `EconomyConfig.save()` re-serializes only declared
components, so any later save (including one triggered by an unrelated admin-UI action) drops them
silently. This is a benign hidden mutation, documented in the CHANGELOG.