# EconomyCraft API v1

The API is included in the normal EconomyCraft Fabric and NeoForge jars. Server owners do not install a separate API mod.

Public classes use this package:

```text
com.reazip.economycraft.api.v1
```

---

## What the API covers

- Read, add, remove or set a UUID's balance.
- Pay money from one UUID to another as one atomic operation.
- Use EconomyCraft's official money formatting.
- Resolve configured buy and sell prices for an item.
- Read price categories and their entries.
- Read leaderboard entries by UUID and balance.
- Read which party a player belongs to, and the claim-cost multiplier that party carries.
- Read the economy's inflation multiplier and the active-player median behind it.
- Attach an optional namespaced source to a mutation.
- Listen for successful balance changes.

The API works with UUIDs, including offline players.

---

## Documentation

- [Getting started](Getting-Started)
- [Balances and payments](Balances-and-Payments)
- [Prices and leaderboard](Prices-and-Leaderboard)
- [Balance events](Balance-Events)
- [Complete API reference](API-Reference)
