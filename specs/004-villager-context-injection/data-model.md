# Data Model: Villager Dialogue Context Injection

**Feature Branch**: `004-villager-context-injection`
**Date**: 2026-10-07
**Status**: Draft

## 1. Data Structures & Entities

### 1.1 `TradeOfferSnapshot` (In-Memory Record)

Represents a concise, immutable snapshot of an active villager trade offer extracted on the main game thread.

```java
public record TradeOfferSnapshot(
        String inputA,
        int countA,
        @Nullable String inputB,
        int countB,
        String output,
        int countOutput,
        boolean outOfStock,
        int remainingUses
) {
    public String toPromptString() {
        // e.g. "Selling 1x Diamond Sword for 15x Emerald [In stock: 12 left]"
        // e.g. "Buying 32x Rotten Flesh for 1x Emerald [OUT OF STOCK]"
    }
}
```

### 1.2 `TradeRecord` (Persistent Entity)

Represents a completed trade transaction between a player and a specific villager.

- **Storage**: SQLite table `villager_trades`
- **Fields**:
  - `id`: `INTEGER PRIMARY KEY AUTOINCREMENT`
  - `villagerUuid`: `TEXT NOT NULL`
  - `playerUuid`: `TEXT NOT NULL`
  - `itemName`: `TEXT NOT NULL`
  - `itemCount`: `INTEGER NOT NULL`
  - `pricePaid`: `INTEGER NOT NULL` (in in-game dollars or approximate emerald value)
  - `timestamp`: `INTEGER NOT NULL` (epoch ms)

### 1.3 `PlayerTradeHistorySummary` (In-Memory / Prompt Context Record)

Aggregates a player's relationship with a specific villager for prompt consumption.

```java
public record PlayerTradeHistorySummary(
        int totalCompletedTrades,
        long totalMoneySpent,
        List<String> recentPurchases // e.g. ["1x Diamond Pickaxe ($120)", "64x Bread ($32)"]
) {
    public String toPromptString() {
        // Formatted for prompt injection
    }
}
```

---

## 2. Schema Evolution (SQLite in `VillagerDatabase`)

### 2.1 Table: `villager_trades`

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
CREATE INDEX IF NOT EXISTS idx_trades_timestamp ON villager_trades(timestamp DESC);
```

### 2.2 Table: `player_memories` (Compatibility)

Existing `player_memories` table schema remains intact. The existing `recent_events` field continues to receive interaction event strings for backward compatibility.
