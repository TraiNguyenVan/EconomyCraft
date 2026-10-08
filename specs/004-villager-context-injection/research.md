# Research: Villager Dialogue Context Injection

**Feature Branch**: `004-villager-context-injection`
**Date**: 2026-10-07
**Status**: Completed

## 1. Trade Offers Extraction & Summarization

### Context on Trade Offers

When a player interacts with a Minecraft `Villager` entity, the villager maintains an active list of trade offers (`MerchantOffers` containing `MerchantOffer` items). In Minecraft Fabric/Common:

- `villager.getOffers()` returns `MerchantOffers`.
- Each `MerchantOffer` has:
  - `getCostA()`: `ItemStack` (primary cost)
  - `getCostB()`: `ItemStack` (optional secondary cost)
  - `getResult()`: `ItemStack` (result item)
  - `isOutOfStock()`: `boolean` (checks if `uses >= maxUses`)
  - `getUses()`, `getMaxUses()`

### Decision: In-Memory Snapshot on Interaction

- When `VillagerMemoryService.handleInteraction(ServerPlayer player, Villager villager)` is invoked on the main server thread, extract a lightweight record list `List<TradeOfferSnapshot>` directly from `villager.getOffers()`.
- Each summary records:
  - Input A name + count
  - Optional Input B name + count
  - Output name + count
  - Price or trade status (`out_of_stock`, or remaining uses)
- **Token Budget Limit**: Cap to top 5-6 active offers (preferring available in-stock offers, plus out-of-stock highlights). Summarize cleanly into prompt lines:
  - e.g., `Selling 1x Diamond Sword for 15x Emerald (In stock)`
  - e.g., `Buying 32x Rotten Flesh for 1x Emerald (Out of stock)`
- **Rationale**: Extracting `ItemStack` display names on the main thread during interaction is microsecond-fast, safe against concurrency issues (accessing Minecraft entity inventories off-thread can cause race conditions), and passes an immutable snapshot to the asynchronous LLM client.
- **Alternatives Considered**: Extracting off-thread (rejected: `MerchantOffers` / `ItemStack` mutation or client synchronization is not thread-safe).

---

## 2. Per-Player Trade History Persistence & Storage

### Context on Player History

Currently:

1. `ProfessionHooks.onVillagerTrade` invokes `memoryService.recordTrade(villager.getUUID(), player.getUUID(), costCount * 10L, desc)`.
2. `PlayerMemory` stores `recentEvents` as a list of strings: `"Bought 1x Diamond Sword for $150"`.
3. However, `recentEvents` also intermingles `"Visited stall"` and general interactions, capped at 10 items, and lacks structured query capability for trade-specific summaries (lifetime top purchased items, total trade counts per item).

### Decision: Dedicated `villager_trades` Table in SQLite + Enhanced `PlayerMemory`

- Maintain backward compatibility with `player_memories`.
- Introduce a dedicated table in `VillagerDatabase`:

```sql
CREATE TABLE IF NOT EXISTS villager_trades (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    villager_uuid TEXT NOT NULL,
    player_uuid TEXT NOT NULL,
    item_name TEXT NOT NULL,
    item_count INTEGER NOT NULL,
    price_paid INTEGER NOT NULL,
    timestamp INTEGER NOT NULL,
    FOREIGN KEY (villager_uuid) REFERENCES villagers(uuid) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_trades_villager_player ON villager_trades(villager_uuid, player_uuid);
```

- Additionally, cache recent trades in `PlayerMemory` or fetch the last 5 trades asynchronously when preparing the dialogue context.
- **Prompt Format**:
  - `Customer's past purchase history at your stall:`
    - `- Bought 1x Enchanted Bow ($500) - 2 hours ago`
    - `- Bought 64x Arrow ($64) - yesterday`
    - `Customer lifetime trade volume: 14 purchases totaling $1,250.`
- **Rationale**: Clean relational tracking allows future expansions (bounties, trade analytics) and keeps prompts structured and distinct from generic `"Visited stall"` sentiment bumps.

---

## 3. Prompt Injection Design & Negative Constraint Tuning

### Context on Prompt Tuning

`VillagerDialoguePromptBuilder.buildSystemInstruction` formats the system instruction for Gemini / OpenAI.
If prompt context grows too long:

- LLM latency spikes.
- The model might enumerate all trades like a shop menu rather than roleplaying naturally.

### Decision on Prompt Architecture

- Add dedicated sections into `VillagerDialoguePromptBuilder`:
  1. `Your Current Stall Inventory / Offers:`
  2. `This Customer's Trade History With You:`
- Add explicit behavior instructions to the system prompt:
  - *"Use your stall inventory and customer trade history as natural conversational context (e.g. comment on their past purchases, pitch an in-stock specialty, or grumble about out-of-stock items). DO NOT recite your entire inventory like a menu; stay concise and in character."*
- Strictly bound string length of injected contexts to < 600 characters (~150 tokens).
- **Alternatives Considered**: Asking the LLM to output JSON with recommended trades (rejected: breaks the 1-sentence quick conversational model and adds output parsing failure points).
