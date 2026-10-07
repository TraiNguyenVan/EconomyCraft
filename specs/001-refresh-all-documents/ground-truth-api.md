# Ground Truth: Permission Nodes and Public API

**Extracted**: 2026-10-07 from `common/src/main/java/com/reazip/economycraft/util/EconomyPermissions.java`
(nodes at lines 12-15 and 23-29) and `api/src/main/java/com/reazip/economycraft/api/v1/`.

**This file is the authority for every documented permission node and public API type**
(`contracts/wiki-page-contract.md` R6, `config-reference-contract.md` R9).

## Admin permission nodes - 6

| Node | Grants |
|---|---|
| `economycraft.admin` | everything below |
| `economycraft.admin.players` | `/eco addmoney`, `setmoney`, `removemoney`, `removeplayer`, the Players screen |
| `economycraft.admin.settings` | the Settings screen |
| `economycraft.admin.shop` | the Shop editor |
| `economycraft.admin.reload` | the "Reload from disk" button |
| `economycraft.admin.reset` | the Reset Tools screen |

All six are documented in `README.md:141-155`.

## Command permission nodes - 14

| Node | Grants |
|---|---|
| `economycraft.command.menu` | `/eco`, `/eco menu` |
| `economycraft.command.balance` | `/bal` |
| `economycraft.command.pay` | `/pay` |
| `economycraft.command.shop` | `/shop` |
| `economycraft.command.auction` | `/ah`, `/auction` |
| `economycraft.command.sell` | `/sell` |
| `economycraft.command.orders` | `/orders` |
| `economycraft.command.deliveries` | `/deliveries` |
| `economycraft.command.daily` | `/daily` |
| `economycraft.command.transactions` | `/transactions` |
| `economycraft.command.worth` | `/worth` |
| `economycraft.command.toll` | `/eco toll`, `/toll`, the Tolls menu |
| `economycraft.command.tag` | **`/eco tag`, `/eco job`, `/eco party`, the hub Tags button, `/tag`** |
| `economycraft.command.offers` | `/eco offers`, `/offers` |

`README.md:141-155` documents 13 of these 14. The missing one is **`economycraft.command.tag`**.
It gates `/eco tag`, `/eco job`, `/eco party`, the hub Tags button and `/tag` - confirmed by the
call sites at `EconomyCommands.java:192` (`buildTag`), `:193` (`buildJob`) and `:195` (`buildParty`),
all three passing `Nodes.COMMAND_TAG`.

## Public API - 18 types in `com.reazip.economycraft.api.v1`

```text
BalanceApi.java
BalanceChangeEvent.java
BalanceChangeListener.java
BalanceEvents.java
BalanceMutationResult.java
BalanceMutationStatus.java
BalanceMutationType.java
EconomyCraftApi.java
EconomyCraftApiAccess.java
FactionApi.java
FactionIds.java
ItemPrice.java
LeaderboardApi.java
LeaderboardEntry.java
ListenerRegistration.java
MutationSource.java
PaymentResult.java
PriceApi.java
```

`EconomyCraftApiAccess` is package-private and is not part of the public surface.


## `EconomyCraftApi` members

| Return type | Method | In the wiki? |
|---|---|---|
| `static EconomyCraftApi` | `get()` | ? |
| `BalanceApi` | `balances()` | yes |
| `PriceApi` | `prices()` | yes |
| `LeaderboardApi` | `leaderboard()` | yes |
| `BalanceEvents` | `balanceEvents()` | yes |
| `FactionApi` | `factions()` | **NO** |
| `String` | `formatMoney()` | yes |
| `double` | `inflationMultiplier()` | **NO** |
| `double` | `medianActiveBalance()` | **NO** |

`wiki/API-Reference.md` lists 5 of 8 members. Missing: `factions()` (line 23),
`inflationMultiplier()` (line 45), `medianActiveBalance()` (line 52).

## `FactionApi` - appears in ZERO of the 11 wiki pages

| Return type | Method |
|---|---|
| `String` | `factionId()` |
| `String` | `factionDisplayName()` |
| `boolean` | `hasChosen()` |
| `String` | `defaultFactionId()` |
| `double` | `claimCostMultiplier()` |

## `FactionIds` constants - appears in ZERO of the 11 wiki pages

- `COMMUNISM`
- `CAPITALISM`
- `MONARCHY`
- `ANARCHISM`
- `DEFAULT`

The only mention of either type anywhere in the documentation set is `CHANGELOG.md:205`.
