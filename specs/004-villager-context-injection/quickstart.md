# Quickstart & Verification: Villager Context Injection

**Feature Branch**: `004-villager-context-injection`
**Date**: 2026-10-07
**Status**: Draft

## 1. Prerequisites

- Built the EconomyCraft Fabric jar using the repository's supported build environment.
- A Fabric test server running a supported Minecraft version.
- AI dialogue enabled in `config.json` (`gemini_gossip.enabled: true` with a valid API key).

---

## 2. Automated Test Verification

Run unit tests in the EconomyCraft repository using its configured Java/Gradle environment:

```bash
./gradlew test --no-daemon
```

Verify specific tests:

- `VillagerDialoguePromptBuilderTest`: Checks that `TradeOfferSnapshot` and `TradeRecord` are correctly formatted into prompt strings and adhere to token budget limits.
- `VillagerDatabaseTest`: Validates `villager_trades` table creation, asynchronous insertion, and query ordering.

---

## 3. In-Game End-to-End Verification

1. **Trade Offer Reference**:
   - Locate an armorer or weaponsmith villager with active trades.
   - Interact with the villager (right-click).
   - Observe the villager's private chat greeting.
   - Verify that the villager references their active merchandise (e.g., armor pieces, weapons, or trade goods).

2. **Per-Player Trade History Awareness**:
   - Complete a trade with the villager for a specific item (e.g., iron armor or diamond weapon).
   - Wait for the interaction cooldown to elapse.
   - Right-click the villager again.
   - Verify that the generated dialogue mentions or comments upon the previous purchase.
