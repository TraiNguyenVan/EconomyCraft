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
