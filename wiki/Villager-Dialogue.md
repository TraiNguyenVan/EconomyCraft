# Villager dialogue

EconomyCraft can generate private dialogue when you interact with a villager or open its trade interface. A line is sent only to the player interacting with that villager; it is not broadcast to chat.

Dialogue requires the server administrator to enable the feature and configure a provider API key. Without a working provider configuration, villagers do not generate dialogue. Administrators can also configure a cooldown and the chance that dialogue is generated during a trade interaction.

The feature can anonymize player names before sending context to the provider. The server administrator controls this privacy setting and the dialogue prompt. Ask the server owner how the feature is configured on that server.

## Persistent villager identity and memory

Each villager gets a persistent profile keyed by its UUID. A new profile is seeded from the villager's profession and biome and includes a name, two or three personality traits, a quirk, and a short backstory. The profile remains associated with that villager after it is loaded again.

Dialogue is contextual rather than generic. The prompt can include the villager's current offers, recent spoken topics, the player's relationship sentiment and interaction count, recent memories, and up to five recent purchases from that stall. The provider is instructed to mention an item that is actually in the current stock and not invent a past trade.

Memory is stored per villager and player. Administrators can inspect or clear a pair with `/eco gossip memory inspect <villager-uuid> <player-uuid>` and `/eco gossip memory clear <villager-uuid> <player-uuid>`.

The cooldown is tracked separately for each player-villager pair. If the provider fails three requests in a row, EconomyCraft opens a 30-minute circuit breaker and pauses further requests during that period.
