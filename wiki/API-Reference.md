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
dashboard, a companion plugin, an ops script — reaches the market over HTTP instead. Two routes
share one listener and one secret:

```text
GET  /economycraft/v1/market[?player=<uuid>]
POST /economycraft/v1/command?player=<uuid>
```

`GET` reads the market. `POST` runs one command, `claimDelivery` — the only thing on either route
that can change anything.

Off by default. The listener is the JDK's built-in HTTP server, so it adds no dependency to the
mod, and it binds inside the server process only — reaching it is a deployment decision, not
something the mod configures.

### Authentication

Every request, on either route, must carry the configured shared secret:

| Header | Value |
|---|---|
| `X-EconomyCraft-Token` | exactly `http_market_token` |

| Response | Route | When |
|---|---|---|
| `200` | both | the snapshot (`GET`) or a command that succeeded (`POST`) |
| `400` | `POST` only | the `?player=` scope is missing or not a uuid, the body is not JSON with a `cmd`, or the command refused — see the command route below |
| `403` | both | the header is missing or does not match |
| `404` | both | a path under the route that is not the route |
| `405` | both | any method other than the route's own verb (`GET` for the market, `POST` for the command) |
| `500` | both | the server could not answer; the body is `{"error":"snapshot unavailable"}` or `{"ok":false,"error":"command unavailable"}` |

Each route answers one verb and refuses every other, so a `GET` to the command route is a `405`, not
a `404`. On both routes the verb and the path are checked **before** the token, so a `404` or `405`
never reads the secret.

**The write route is one command, and nothing more.** `POST /economycraft/v1/command` exists to
claim a delivery a player already owns: it moves a waiting item out of that player's delivery ledger
and into that **same player's** inventory. It cannot buy, sell, list, bid, cancel, transfer or
create anything, it has no argument that names a second player, and it cannot touch a balance. `GET
/economycraft/v1/market` itself is still read-only by construction — the snapshot it returns has no
field that reaches a mutation.

The token is a shared secret, not a per-player credential: it says a caller is trusted, not which
player it is. The player is named entirely by the `?player=` scope, and every effect stays inside
that one uuid — so one request can never move anything between players. The token is never logged
and never echoed in a response body or an error message.

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
    "orders": [7],
    "inventory": [                    // always present; empty when the player holds nothing
      { "slot": 0, "stack": { "id": "minecraft:diamond_hoe", "count": 1 } },
      { "slot": 9, "stack": { "id": "minecraft:amethyst_shard", "count": 7 } }
    ],
    "enderChest": []                  // always present; same shape as inventory
  }
}
```

`stack` is the same shape the mod already persists, because it is the same `ItemStack.CODEC`
encoding. `components` is present only when the item carries components.

**`inventory` and `enderChest` are sparse, and always present.** Each is an array of
`{"slot": <int>, "stack": <encoded stack>}` in container order, where `slot` is the container index
the item actually occupies (`0`–`35` for the player's inventory and `0`–`26` for the ender chest;
armour and the offhand are outside both lists). Only **non-empty** slots are emitted, so a row's
position in the array is not its slot — read `slot` off the row instead of counting. Both arrays are
present in every `you` block, including one with nothing to report, where they are empty rather than
`null` or absent. When the player is online this is their live inventory; when they are offline it is
read from the server's own player data. The two are never mixed, so a field is either current or
reported empty, never stale.

**`system` is a tag, not a filter.** The quest board's rows are in `auctions` and `orders` with
`"system": true` rather than being removed, so a consumer that cannot filter still sees the whole
board instead of a silently truncated market. Filter on the flag to hide them.

**`taxRate` is the configured base rate**, so an effective auction cost is `price * (1 + taxRate)`
without the consumer hard-coding a number that an admin can retune. It does **not** include a
player's own discount or exemption, which are deliberately not published (see the exclusions below).

### Scoping to one player

Add `?player=<uuid>` and, **if that uuid is a player this snapshot already lists in `players`**, the
response gains a `you` block with that player's balance, their waiting deliveries, the ids of their
own listings and orders, and their inventory and ender chest.

The ids in `you` are drawn from the `auctions` and `orders` arrays in the very same response, so an
id you act on always names a row you can see. An unscoped request has no `you` block at all — not an
empty one.

A uuid that is not a known player, or the quest board's own uuid, yields no `you` block, and the
private read is never performed for it.

The same `?player=` scope, parsed the same way, selects the player the command route acts for.
`you.deliveries` is exactly the array a `claimDelivery` index counts through, so the two routes agree
on which delivery is which.

### Command route

```text
POST /economycraft/v1/command?player=<uuid>
X-EconomyCraft-Token: <http_market_token>

{ "cmd": "claimDelivery", "index": 0 }
```

The `?player=` scope is **required** here and names the account every command acts for; a missing or
unparseable scope is a `400` before the body is read. The body is a JSON object whose `cmd` names the
command and whose remaining fields are its arguments.

| `cmd` | Arguments | Effect |
|---|---|---|
| `claimDelivery` | `index` (integer, required) | Moves the delivery at `index` in **that player's own** `you.deliveries` into that player's own inventory, then removes it from the delivery ledger. |

`index` is `0`-based and counts the same array the snapshot publishes as `you.deliveries` for the same
scope, so an index is only meaningful next to a snapshot taken shortly before it. An `index` that is
missing, not a whole number, or outside the array is refused.

The command is all-or-nothing. The item is placed in the inventory **before** it is dropped from the
ledger, and a placement that does not fully fit is rolled back, so a refusal leaves both the ledger
and the inventory exactly as they were. A refused claim is therefore safe to retry, and a successful
one cannot hand the same item out twice.

Every command returns the same shape:

```jsonc
{ "ok": true,  "error": null }        // success: `error` is always present, and null here
{ "ok": false, "error": "inventory full" }
```

| `error` | When |
|---|---|
| `missing or invalid ?player= scope` | the `?player=` parameter is absent or not a uuid |
| `body must be JSON with a cmd field` | the body is not a JSON object carrying a `cmd` |
| `unknown command: <cmd>` | `cmd` names no command this route serves |
| `claimDelivery requires an integer index` | `claimDelivery` was called with no `index` |
| `index must be an integer` | `index` is present but is not a whole number |
| `no delivery at index <n>` | `index` is outside that player's delivery list |
| `inventory full` | the whole stack would not fit, so nothing was claimed |
| `command failed` | an unexpected fault while running the command; the exception itself is written to the server log, never to the reply |
| `command unavailable` | the server could not answer (returned with `500`) |

Those are the reasons the route returns today. Branch on `ok`, not on the string — the reason is for
diagnosis, and the set of reasons can grow without the contract changing.

**What it is not.** The route moves a delivery and does nothing else. It cannot buy, sell, list, bid,
cancel, transfer or create a listing, an order or a delivery; it cannot change a balance; and it has
no argument that names a second player, so a request can only ever put a player's own waiting item
into that same player's own inventory. There is no command to act on another player's behalf.

### What it deliberately does not expose

The market route is a market view. It is **not** a second route into the economy's private state, and
it never exposes, in any form:

| Never exposed | Why not |
|---|---|
| another player's balance | `you` is the only place a balance appears, and it is the requested one |
| another player's deliveries | `deliveries` only ever reads the requested uuid |
| another player's inventory or ender chest | `inventory` and `enderChest` are read for the requested uuid only, and no request can name a second player |
| tolls, toll owners, toll prices | not part of a market view |
| wealth tax settings or results | not part of a market view |
| faction / party data | would leak who is in which party |
| gossip or villager data | private to the server's own AI feature |
| admin reset capability | the one command moves a delivery a player already owns into that player's own inventory; it cannot reset, erase, or grant anything, and it cannot reach a balance |

### Configuration

One listener serves both routes, and one set of keys controls it.

| Key | Default | Meaning |
|---|---|---|
| `http_market_enabled` | `false` | Master switch for the listener. |
| `http_market_port` | `0` | Bind port. `0` binds an ephemeral port, which is logged at server start. |
| `http_market_token` | `""` | The shared secret for `X-EconomyCraft-Token`, on both routes. **Empty means the listener refuses to start** — with no secret the `?player=` scoping could not be told apart from no scoping and the command route would accept an unauthenticated caller, so neither route is served. |

These keys take effect on the next server start; `/eco reload` re-reads them but does not rebind an
already-running listener.

### Threading

The listener runs on its own worker threads, so every snapshot read **and** every command is
marshalled onto the server thread with `server.execute(...)` and awaited there — the same contract as
`api/v1`, which throws if called off the server thread. Nothing is read from, or written to, the live
stores off-thread, so a command runs exactly where every other mutation in the mod runs. A request
that the server thread does not answer within a few seconds is returned as unavailable rather than
left hanging.

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
