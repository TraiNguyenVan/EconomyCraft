# API reference

All public v1 types are in:

```java
com.reazip.economycraft.api.v1
```

Implementation and provider classes are internal. Other mods should only import the types listed here.

The package holds 18 types. Seventeen are public and documented below; `EconomyCraftApiAccess` is
package-private and is the provider `EconomyCraftApi.get(server)` resolves, so you cannot and should not
import it.

---

## EconomyCraftApi

```java
public interface EconomyCraftApi {
    static EconomyCraftApi get(MinecraftServer server);

    BalanceApi balances();
    PriceApi prices();
    LeaderboardApi leaderboard();
    BalanceEvents balanceEvents();
    FactionApi factions();
    String formatMoney(long amount);
    double inflationMultiplier();
    double medianActiveBalance();
}
```

---

## FactionApi

Read-only access to a player's party. Added for ShopGuard's claim rules, which live in another repository and
cannot import EconomyCraft's internals.

```java
public interface FactionApi {
    String factionId(UUID playerId);
    String factionDisplayName(UUID playerId);
    boolean hasChosen(UUID playerId);
    String defaultFactionId();
    double claimCostMultiplier(UUID playerId);
}
```

| Method | Returns |
|---|---|
| `factionId` | The player's party as one of the `FactionIds` constants. Never `null`, and never a string outside `FactionIds` — an unknown id falls back to the default, so one corrupt record cannot make a consumer throw. |
| `factionDisplayName` | The party's English display name, e.g. `"Monarchy"`, so a consumer need not keep its own copy of the strings. |
| `hasChosen` | Whether the player has *actually chosen* a party, as opposed to `factionId` reporting the default for them. |
| `defaultFactionId` | The party treated as unselected. |
| `claimCostMultiplier` | The multiplier to apply to this player's whole claim charge, from `factions.monarchy.claim_cost_multiplier`. `1.0` for every other party, and `1.0` for everybody when the party system is disabled. |

**`hasChosen` is not redundant with `factionId`.** `FactionIds.DEFAULT` is `anarchism`, so a consumer keying
off `factionId` alone applies Anarchism's restrictions to every player who has never made a choice — on a live
server with no saved parties, that is every player including operators. Read "no choice" as **unrestricted** and
reserve the restrictions for players who opted into the party that carries them. `hasChosen` also returns
`false` for a record that exists but cannot be read, so a corrupt save fails open.

**There is no `select()`.** Choosing a party is a player-facing action with a 30-hour lockout, a tag refresh and
a save-file write; it belongs to EconomyCraft's own command and UI layers. A writable API would be a second,
unvalidated path to the same state.

`claimCostMultiplier` lives here rather than in the consumer because the faction owns the benefit. A consumer
that hard-coded `0.5` would keep charging full price after an admin retuned the key, and nothing would report
the drift — the discount would simply stop existing.

## FactionIds

The four party ids, as the stable lowercase strings the rest of the ecosystem compares against.

```java
public final class FactionIds {
    public static final String COMMUNISM  = "communism";
    public static final String CAPITALISM = "capitalism";
    public static final String MONARCHY   = "monarchy";
    public static final String ANARCHISM  = "anarchism";
    public static final String DEFAULT    = ANARCHISM;
}
```

They exist because a party name crosses repository boundaries — a save file, a command argument, and
ShopGuard's claim pricing — and a consumer in another repository cannot import EconomyCraft's internal
`FactionId` enum. Passing strings across that boundary is unavoidable; what is avoidable is the typo. Write
`FactionIds.MONARCHY` and you get a compile error when the party is renamed, instead of a discount that
silently never applies because it compared against `"Monarchy"`.

The ids are lowercase and match the `/eco party` subcommand literals exactly, so one string works as a command
argument, a config value and an API comparison.

`DEFAULT` is `anarchism` — the party that charges no tax. It is a separate constant rather than an implicit
string, so a consumer that needs to know the fallback need not re-derive it.

---

## BalanceApi

```java
public interface BalanceApi {
    long getBalance(UUID playerId);
    long getMaximumBalance();

    BalanceMutationResult addMoney(UUID playerId, long amount);
    BalanceMutationResult addMoney(UUID playerId, long amount, MutationSource source);

    BalanceMutationResult removeMoney(UUID playerId, long amount);
    BalanceMutationResult removeMoney(UUID playerId, long amount, MutationSource source);

    BalanceMutationResult setMoney(UUID playerId, long balance);
    BalanceMutationResult setMoney(UUID playerId, long balance, MutationSource source);

    PaymentResult pay(UUID senderId, UUID receiverId, long amount);
    PaymentResult pay(UUID senderId, UUID receiverId, long amount, MutationSource source);
}
```

---

## BalanceMutationResult

```java
public record BalanceMutationResult(
        BalanceMutationStatus status,
        BalanceMutationType type,
        UUID playerId,
        long requestedAmount,
        long previousBalance,
        long newBalance,
        Optional<MutationSource> source
) {
    boolean successful();
    long difference();
}
```

## PaymentResult

```java
public record PaymentResult(
        BalanceMutationStatus status,
        UUID senderId,
        UUID receiverId,
        long amount,
        long senderPreviousBalance,
        long senderNewBalance,
        long receiverPreviousBalance,
        long receiverNewBalance,
        Optional<MutationSource> source
) {
    boolean successful();
    long senderDifference();
    long receiverDifference();
}
```

## BalanceMutationStatus

```java
public enum BalanceMutationStatus {
    SUCCESS,
    NO_CHANGE,
    INVALID_AMOUNT,
    INSUFFICIENT_FUNDS,
    SAME_PLAYER,
    MAX_BALANCE_EXCEEDED
}
```

## BalanceMutationType

```java
public enum BalanceMutationType {
    ADD,
    REMOVE,
    SET,
    PAYMENT_SENT,
    PAYMENT_RECEIVED
}
```

## MutationSource

```java
public record MutationSource(String namespace, String reason) {
    static MutationSource of(String namespacedReason);
    String asString();
}
```

---

## PriceApi

```java
public interface PriceApi {
    Optional<ItemPrice> resolve(ItemStack stack);
    List<String> categories();
    List<ItemPrice> entries(String category);
}
```

## ItemPrice

```java
public final class ItemPrice {
    public ItemPrice(
            String key,
            String itemId,
            String category,
            int bulkAmount,
            OptionalLong unitBuyPrice,
            OptionalLong unitSellPrice,
            boolean customItem,
            ItemStack prototype
    );

    public String key();
    public String itemId();
    public String category();
    public int bulkAmount();
    public OptionalLong unitBuyPrice();
    public OptionalLong unitSellPrice();
    public boolean hasBuyPrice();
    public boolean hasSellPrice();
    public boolean customItem();
    public ItemStack prototype();
}
```

The constructor copies `prototype`. The `prototype()` accessor returns another copy.

---

## LeaderboardApi

```java
public interface LeaderboardApi {
    List<LeaderboardEntry> getLeaderboardEntries(int limit);
    Optional<LeaderboardEntry> getLeaderboardEntry(int rank);
}
```

## LeaderboardEntry

```java
public record LeaderboardEntry(UUID playerId, long balance) {}
```

---

## BalanceEvents

```java
public interface BalanceEvents {
    ListenerRegistration register(BalanceChangeListener listener);
}
```

## BalanceChangeListener

```java
@FunctionalInterface
public interface BalanceChangeListener {
    void onBalanceChanged(BalanceChangeEvent event);
}
```

## BalanceChangeEvent

```java
public record BalanceChangeEvent(
        UUID playerId,
        long previousBalance,
        long newBalance,
        BalanceMutationType type,
        Optional<UUID> counterpartyId,
        Optional<MutationSource> source,
        Optional<String> detail
) {
    long difference();
}
```

`detail` is a short, human-readable description of what caused the change (e.g. `"12x Iron Ingot"`) when EconomyCraft's own shop, auction or order features triggered it. It's empty for payments, admin commands, rewards, and for changes made through `addMoney`/`removeMoney`/`setMoney`/`pay` without a detail argument.

## ListenerRegistration

```java
public interface ListenerRegistration extends AutoCloseable {
    void unregister();
    void close();
}
```

---

## HTTP market snapshot

`api/v1` is for mods on the server thread. A consumer that is **not** an in-process mod — a
dashboard, a companion plugin, an ops script — reads the market over HTTP instead:

```text
GET /economycraft/v1/market[?player=<uuid>]
```

Off by default. It is served from the JDK's built-in HTTP server, so it adds no dependency to the
mod, and it binds inside the server process only — reaching it is a deployment decision, not
something the mod configures.

### Authentication

Every request must carry the configured shared secret:

| Header | Value |
|---|---|
| `X-EconomyCraft-Token` | exactly `http_market_token` |

| Response | When |
|---|---|
| `200` | the snapshot, as JSON |
| `403` | the header is missing or does not match |
| `404` | a path under the route that is not the route |
| `405` | any method other than `GET` |

**There is no write route, and no route that can become one.** One context is registered; it answers
`GET` and nothing else. The token is never logged and never echoed in a response body or an error
message.

### Response

```jsonc
{
  "generatedAt": 1760000000000,       // epoch millis, when the snapshot was taken
  "inflationMultiplier": 1.25,       // the same signal as EconomyCraftApi.inflationMultiplier()
  "taxRate": 0.1,                    // the auction-purchase tax rate, see below

  "auctions": [{
    "id": 41,
    "seller": "…uuid…",
    "sellerName": "alice",
    "price": 500,
    "createdAt": 1759990000000,
    "expiresAt": 1760594800000,
    "description": "nice sword",     // absent when the seller wrote none
    "system": false,                  // true for the quest board's own rows
    "stack": { "id": "minecraft:diamond_sword", "count": 1, "components": { … } }
  }],

  "orders": [{
    "id": 7,
    "requester": "…uuid…",
    "requesterName": "bob",
    "price": 300,                     // the total posted for the whole amount
    "amount": 4,
    "escrow": 300,                    // how much of price is held right now
    "unitPrice": 75,                  // price for one item
    "createdAt": 1759990000000,
    "expiresAt": 1760594800000,
    "description": "note",            // absent when the requester wrote none
    "system": false,
    "stack": { "id": "minecraft:wheat", "count": 1 }
  }],

  "players": [{ "uuid": "…uuid…", "name": "alice" }],

  "you": {                            // present only for a scoped request; see below
    "balance": 4242,
    "deliveries": [{ "stack": { "id": "minecraft:apple", "count": 3 } }],
    "listings": [41],
    "orders": [7]
  }
}
```

`stack` is the same shape the mod already persists, because it is the same `ItemStack.CODEC`
encoding. `components` is present only when the item carries components.

**`system` is a tag, not a filter.** The quest board's rows are in `auctions` and `orders` with
`"system": true` rather than being removed, so a consumer that cannot filter still sees the whole
board instead of a silently truncated market. Filter on the flag to hide them.

**`taxRate` is the configured base rate**, so an effective auction cost is `price * (1 + taxRate)`
without the consumer hard-coding a number that an admin can retune. It does **not** include a
player's own discount or exemption, which are deliberately not published (see the exclusions below).

### Scoping to one player

Add `?player=<uuid>` and, **if that uuid is a player this snapshot already lists in `players`**, the
response gains a `you` block with that player's balance, their waiting deliveries, and the ids of
their own listings and orders.

The ids in `you` are drawn from the `auctions` and `orders` arrays in the very same response, so an
id you act on always names a row you can see. An unscoped request has no `you` block at all — not an
empty one.

A uuid that is not a known player, or the quest board's own uuid, yields no `you` block, and the
private read is never performed for it.

### What it deliberately does not expose

This endpoint is a market view. It is **not** a second route into the economy's private state, and it
never exposes, in any form:

| Never exposed | Why not |
|---|---|
| another player's balance | `you` is the only place a balance appears, and it is the requested one |
| another player's deliveries | `deliveries` only ever reads the requested uuid |
| tolls, toll owners, toll prices | not part of a market view |
| wealth tax settings or results | not part of a market view |
| faction / party data | would leak who is in which party |
| gossip or villager data | private to the server's own AI feature |
| admin reset capability | there is no mutation path of any kind |

### Configuration

| Key | Default | Meaning |
|---|---|---|
| `http_market_enabled` | `false` | Master switch for the listener. |
| `http_market_port` | `0` | Bind port. `0` binds an ephemeral port, which is logged at server start. |
| `http_market_token` | `""` | The shared secret for `X-EconomyCraft-Token`. **Empty means the route refuses to start** — with no secret the `?player=` scoping could not be told apart from no scoping, so the market would be published unscoped. |

These keys take effect on the next server start; `/eco reload` re-reads them but does not rebind an
already-running listener.

### Threading

The listener runs on its own worker threads, so every snapshot read is marshalled onto the server
thread with `server.execute(...)` and awaited there — the same contract as `api/v1`, which throws if
called off the server thread. Nothing is read from the live stores off-thread.

---

## Inflation signal

```java
double inflationMultiplier();
double medianActiveBalance();
```

`inflationMultiplier()` is the median balance of the players counted as active, divided by `startingBalance`,
clamped to the `dynamic_price_min_multiplier`..`dynamic_price_max_multiplier` range. `1.0` means the median
sits exactly at the starting balance; `7.36` means it sits at 7.36x. `medianActiveBalance()` is the numerator in
the same units, and is `0` when no player is active — in which case the multiplier is `1.0`.

**This is a slowly moving market rate, not a transaction price.** It is recomputed at most once an hour and only
from players seen inside the activity window, so with a small player base it can jump sharply when one profile
ages out of that window. If you need a stable figure, compress it into your own range and anchor it to a
reference value rather than using it raw — and never let it decide a price at a finer grain than the hourly
refresh.

It is independent of `dynamic_prices_enabled`: that flag gates whether item buy prices are scaled, not whether
this signal is maintained. It is also the signal the daily fiscal pass reads, so your pricing and the mod's
taxation can never disagree about who is active.

---

## General contract

- All calls are server-thread-only. `get(server)` and every method reachable from it throw if called off the
  server thread.
- UUID arguments support offline players.
- Calling mods handle their own permissions.
- Normal absent values use `Optional`, `OptionalLong` or an empty immutable list instead of `null`.
- Failed mutations do not change or implicitly save balances.
- Returned price and leaderboard data is immutable.
- Balance events are successful after-events and are not cancellable.
