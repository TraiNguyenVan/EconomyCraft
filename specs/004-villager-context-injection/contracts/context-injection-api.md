# Contract: Villager Context Injection Internal Interfaces & API

**Feature Branch**: `004-villager-context-injection`
**Date**: 2026-10-07
**Status**: Draft

## 1. VillagerDialoguePromptBuilder Contract

### Enhanced Method Signatures

```java
public static String buildSystemInstruction(
        VillagerProfile profile,
        PlayerMemory memory,
        String playerArchetype,
        @Nullable List<String> grapevineRumors,
        double inflation,
        @Nullable String customInstructions,
        @Nullable List<String> recentSpokenTopics,
        @Nullable List<TradeOfferSnapshot> currentOffers,
        @Nullable List<TradeRecord> customerTradeHistory
);
```

### Injected Prompt Format

```text
Stall Trade Inventory:
- Selling 1x Diamond Pickaxe for 18x Emerald [In stock]
- Selling 1x Enchanted Book (Mending) for 24x Emerald [In stock]
- Buying 24x Iron Ingot for 1x Emerald [OUT OF STOCK]

Customer's Past Purchase History With You:
- Bought 1x Diamond Pickaxe ($180)
- Bought 3x Golden Apple ($60)
Lifetime customer volume: 4 trades ($350 spent).
```

---

## 2. VillagerDatabase Contract

### Additional Asynchronous Methods

```java
/**
 * Asynchronously logs a completed trade between a player and villager.
 */
public CompletableFuture<Void> recordTradeTransaction(
        UUID villagerUuid,
        UUID playerUuid,
        String itemName,
        int count,
        long pricePaid,
        long timestamp
);

/**
 * Asynchronously fetches recent trade history between a player and villager.
 */
public CompletableFuture<List<TradeRecord>> getRecentTrades(
        UUID villagerUuid,
        UUID playerUuid,
        int limit
);
```

---

## 3. GossipApiClient Contract

`generateIndividualDialogue` propagates trade context records down to `buildSystemInstruction`.

```java
public CompletableFuture<Optional<IndividualDialogueResult>> generateIndividualDialogue(
        VillagerProfile profile,
        PlayerMemory memory,
        String playerArchetype,
        @Nullable List<String> grapevineRumors,
        double inflation,
        @Nullable List<String> recentSpokenTopics,
        @Nullable List<TradeOfferSnapshot> currentOffers,
        @Nullable List<TradeRecord> tradeHistory
);
```
