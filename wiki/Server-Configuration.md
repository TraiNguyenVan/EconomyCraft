# Server configuration

Configuration files are in `config/economycraft/` on a dedicated server and `saves/<world>/economycraft/` in singleplayer. Edit `config.json`, `webhook.json`, or `prices.json` there. Keys are case-sensitive. Use `/eco reload` after changing configuration files; HTTP listener settings take effect after a server restart.

The tables below describe the shipped settings and defaults. The bundled [default `config.json`](https://github.com/TraiNguyenVan/EconomyCraft/blob/main/common/src/main/resources/assets/economycraft/config.json) is the source for `config.json` values.

Since 1.11.0, core economy settings are grouped under `economy`, `display`, `commands`, `shop`, `selling`,
`dynamic_prices`, `auction`, `orders`, and `transaction_log`. On reload, older flat keys such as
`startingBalance`, `taxRate`, and `dynamic_prices_enabled` are converted into their grouped names. Unknown keys
are kept; if both an old and new key are present with different values, the migration stops safely and reports the
ambiguous setting instead of guessing.

## `config.json`

The keys are case-sensitive. Top-level names use `camelCase` where shown; other keys use `snake_case`.

Keys marked **file-only** are not editable from `/eco admin` → Settings; change them in the file and use
**Reload from disk**.

<!-- generated from config.json; descriptions hand-written, defaults read from the file -->

| Key | Default | Description |
|---|---|---|
| `economy.starting_balance` | `1000` | Money new players start with. |
| `economy.max_balance` | `1000000000000000000` | Highest balance a player can hold. |
| `economy.daily_reward` | `100` | Money given by the daily reward. |
| `economy.tax_rate` | `0.1` | Tax on trades and orders, as a decimal (`0.1` = 10%). |
| `display.balance_separator` | `"."` | Thousands separator, e.g. `","` gives `$1,000`. |
| `display.currency_symbol` | `"$"` | Currency symbol used by formatted amounts. |
| `display.currency_symbol_position` | `"prefix"` | Put the symbol before or after the amount (`prefix` or `suffix`). |
| `display.currency_name` | `""` | Optional currency name used in chat messages. |
| `display.scoreboard_enabled` | `true` | Show the balance sidebar. |
| `commands.standalone` | `true` | Allow `/pay`, `/daily` and other commands without the `/eco` prefix. |
| `commands.standalone_admin` | `false` | Allow admin commands without the `/eco` prefix. |
| `shop.enabled` | `true` | Enable the fixed-price shop. |
| `selling.enabled` | `true` | Enable selling. |
| `selling.daily_limit` | `10000` | Most a player can earn per day from selling. `0` disables the limit. |
| `selling.worth_enabled` | `true` | Enable item value lookups (`/worth`). |
| `dynamic_prices.enabled` | `false` | **Disabled by default.** Scale shop buy prices with the active-player median balance. |
| `dynamic_prices.min_multiplier` | `0.5` | Lowest allowed dynamic price scale. |
| `dynamic_prices.max_multiplier` | `5.0` | Highest allowed dynamic price scale. |
| `dynamic_prices.min_active_days` | `30` | Players must have logged in within this many days to count as active. `0` includes everyone. |
| `dynamic_prices.update_minutes` | `60` | Minimum minutes between dynamic price recalculations. |
| `auction.enabled` | `true` | Enable the auction house. |
| `auction.expiration_hours` | `168` | Hours before an unsold auction expires and its item goes to deliveries. `0` disables expiration. |
| `auction.max_active_per_player` | `0` | Most active auctions a player can have at once. `0` = unlimited. |
| `orders.enabled` | `true` | Enable the orders board. Deliveries still work either way. |
| `orders.expiration_hours` | `168` | Hours before an unfulfilled order expires and its escrow is refunded. `0` disables expiration. |
| `orders.max_active_per_player` | `0` | Most open orders a player can have at once. `0` = unlimited. |
| `transaction_log.enabled` | `true` | Record every balance change to a daily log file. |
| `transaction_log.retention_days` | `7` | How many days of transaction logs to keep. Above `90` logs a console warning on start. |
| `auction_listing_broadcast` | `true` | Send notices when a listing is posted or its price drops. |
| `auction_listing_notify_active_days` | `30` | Send listing notices to offline players seen within this many days. `0` limits notices to online players. |
| `contract_default_duration_hours` | `168` | Default work time offered for a new contract (7 days). |
| `contract_max_duration_hours` | `720` | Longest work time a contract may set (30 days). Raised to match the default if lower. |
| `contract_max_revisions` | `2` | Times the requester can send work back before disputes unlock. `0` disables revisions. |
| `contract_revision_extension_hours` | `72` | Extra work time granted per revision. `0` keeps the original deadline. |
| `contract_review_hours` | `72` | Hours the requester has to review a submission before it auto-approves. `0` disables auto-approval. |
| `contracts_enabled` | `true` | Enable player-to-player contracts. |
| `http_market_enabled` | `false` | Enable the HTTP market and command endpoints. |
| `http_market_port` | `0` | Listener port. `0` selects an available port and logs the selected port when the server starts. |
| `http_market_token` | `""` | Shared request token for both endpoints. Set a non-empty value before enabling the listener. |
| `sulfur_cube_chunk_loading` | `true` | Let a sulfur cube that swallowed a block keep its surroundings loaded as it moves. Natural cubes without a block never load chunks. |
| `sulfur_cube_chunk_radius` | `3` | Chunk radius ticketed around each loaded sulfur cube, clamped to 2–6. Radius 3 keeps a 3×3 square of chunks entity-ticking and loads out to 7×7. Radius 2 is the smallest that still keeps the cube's own chunk entity-ticking, so lower values are raised to 2. |
| `sulfur_cube_chunk_lookahead_ticks` | `25` | How far ahead, in ticks of travel, an upcoming chunk is preloaded. Clamped to 0–100; `0` disables preloading. Only chunks outside the radius above are preloaded, never further than twice the radius. |
| `sulfur_cube_lookahead_min_speed` | `4.0` | Horizontal speed in blocks per second above which preloading runs. |
| `sulfur_cube_chunk_max_loaders` | `0` | Most sulfur cubes that may load chunks at once across the server. `0` = unlimited. Cubes past the cap load nothing until a slot frees up. |
| `sulfur_cube_chunk_idle_seconds` | `120` | How long a loaded cube may stand still before it stops loading its chunk. Nearby players keep the area loaded as usual, and the cube starts loading again as soon as it moves. `0` keeps loading while the cube is alive. |
| `max_active_contracts_per_player` | `0` | Most live contracts a player can be part of at once. `0` = unlimited. Overridable per player. |
| `max_active_tolls_per_player` | `10` | Most tolls a player can have placed at once. `0` = unlimited. |
| `max_contract_reward` | `1000000` | Highest reward a contract may offer. Clamped to 1–99,999,999. |
| `wealth_tax_enabled` | `false` | **Disabled by default.** Apply the daily wealth tax. See [Daily wealth tax](Server-Administration.md#daily-wealth-tax). |
| `wealth_tax_floor` | `1000` | Balances at or below this are never taxed. Also the rebate target. |
| `wealth_tax_inactive_days` | `7` | Days before an unseen player is charged the idle rate. `0` = one rate for everyone. |
| `wealth_tax_inactive_multiplier` | `3.33` | Rate multiplier for players past the inactive window. |
| `wealth_tax_max_catchup_days` | `7` | Most days applied after downtime. `0` = unlimited. |
| `wealth_tax_median_floor_factor` | `0.5` | How far the floor follows the active median. `0` keeps it fixed, `1` makes it equal the median. |
| `wealth_tax_rate` | `0.015` | Daily cut taken from the surplus above the floor, as a decimal. |
| `wealth_tax_rebate_enabled` | `false` | **Disabled by default.** Pay players below the floor when the median has crashed. Creates money. |
| `wealth_tax_rebate_max_rate` | `0.01` | Rebate rate applied to the shortfall below the floor. |
| `wealth_tax_rebate_trigger_factor` | `1.0` | Rebate arms only when the median is below `economy.starting_balance x` this. |
### `factions`

Party tuning. The four parties are `communism`, `capitalism`, `monarchy` and `anarchism`.

| Key | Default | Description |
|---|---|---|
| `factions.anarchism.color` | `11184810` | Anarchism tag colour, as a 24-bit RGB integer. |
| `factions.anarchism.icon` | `"Ⓐ"` | Anarchism tag icon, one glyph. |
| `factions.anarchism.unclaimed_horse_speed_multiplier` | `1.15` | Horse riding speed on unclaimed land. |
| `factions.anarchism.unclaimed_speed_multiplier` | `1.15` | Movement speed multiplier on unclaimed land (`1.15` = +15%). Requires [ShopGuard](https://github.com/TraiNguyenVan/shopguard). |
| `factions.capitalism.color` | `5635925` | Capitalism tag colour, as a 24-bit RGB integer. |
| `factions.capitalism.concentration_elasticity` | `1.0` | The single difficulty dial; above `1` punishes concentration harder. |
| `factions.capitalism.concentration_max_multiplier` | `5.0` | Upper bound on the concentration multiplier. |
| `factions.capitalism.concentration_min_multiplier` | `0.0` | Lower bound on the concentration multiplier. |
| `factions.capitalism.concentration_reference_share` | `0.15` | Party wealth share at which the multiplier is exactly `1.0`. |
| `factions.capitalism.daily_tax_rate` | `0.025` | Base daily rate, before the concentration multiplier (`0.025` = 2.5%). |
| `factions.capitalism.icon` | `"$"` | Capitalism tag icon, one glyph. |
| `factions.capitalism.max_rate_change_per_day` | `0.25` | How fast the daily rate may move, as a fraction per day. Caps griefing the rate up and down. |
| `factions.capitalism.toll_tax_multiplier` | `1.25` | Toll tax amount multiplier (`1.25` = +25%). |
| `factions.capitalism.use_global_inflation` | `true` | Legacy setting retained for existing config files; Capitalism's daily tax no longer uses inflation. |
| `factions.communism.color` | `16733525` | Communism tag colour, as a 24-bit RGB integer. |
| `factions.communism.icon` | `"☭"` | Communism tag icon, one glyph. |
| `factions.communism.income_tax_tier1_rate` | `0.0025` | Tier 1 rate (`0.0025` = 0.25% of the balance). |
| `factions.communism.income_tax_tier1_threshold` | `10000` | Balance above which income tax tier 1 applies. |
| `factions.communism.income_tax_tier2_rate` | `0.00375` | Tier 2 rate (`0.00375` = 0.375%). |
| `factions.communism.income_tax_tier2_threshold` | `15000` | Balance above which income tax tier 2 applies. |
| `factions.communism.income_tax_tier3_rate` | `0.00625` | Tier 3 rate (`0.00625` = 0.625%). The highest matching tier wins. |
| `factions.communism.income_tax_tier3_threshold` | `22000` | Balance above which income tax tier 3 applies. |
| `factions.communism.party_fee` | `10` | Fee charged to party members at each interval. The money is removed from the economy. |
| `factions.communism.toll_tax_exempt_chance` | `0.5` | Chance that a toll payment is exempt from tax. The toll owner is still paid. |
| `factions.daily_tax_max_catchup_days` | `7` | Most offline days the daily faction tax catches up on. `0` = unlimited. |
| `factions.enabled` | `true` | Master switch for the party system. |
| `factions.levy_interval_minutes` | `45` | Accumulated online time between party fees and income tax. |
| `factions.monarchy.claim_cost_multiplier` | `0.5` | Claim-cost multiplier (`0.5` = 50% lower cost). Requires [ShopGuard](https://github.com/TraiNguyenVan/shopguard). |
| `factions.monarchy.color` | `16777045` | Monarchy tag colour, as a 24-bit RGB integer. |
| `factions.monarchy.corruption_multiplier` | `1.0` | Multiplier on the monarchy's daily tax. The amount is removed from the economy. |
| `factions.monarchy.daily_tax_rate` | `0.01` | Base daily rate (`0.01` = 1%). Monarchy taxes well below Capitalism. |
| `factions.monarchy.icon` | `"♔"` | Monarchy tag icon, one glyph. |
| `factions.monarchy.import_tax_chance` | `0.5` | Chance that the import tax applies. |
| `factions.monarchy.import_tax_factor` | `0.5` | Import surcharge when the tax applies (`0.5` = +50%). |
| `factions.monarchy.money_supply_inflation_max` | `3.0` | Legacy setting retained for existing config files; Monarchy's daily tax no longer uses money-supply inflation. |
| `factions.monarchy.money_supply_reference_per_player` | `1000.0` | Legacy setting retained for existing config files; Monarchy's daily tax no longer uses money-supply inflation. |
| `factions.monarchy.own_claim_damage_multiplier` | `1.15` | Damage dealt and damage resistance inside your own claim. Requires [ShopGuard](https://github.com/TraiNguyenVan/shopguard). |
| `factions.selection_lockout_hours` | `30` | How long a party choice holds. Independent of the profession lockout. `0` = off. |

### `professions`

Profession tuning. The five jobs are `builder`, `farmer`, `miner`, `merchant` and `soldier`.

| Key | Default | Description |
|---|---|---|
| `professions.builder.building_blocks` | See default below. | Blocks a Builder counts toward profession progress. |
| `professions.builder.color` | `16755200` | Apprentice Builder tag colour, as a 24-bit RGB integer. |
| `professions.builder.haste_level` | `1` | Haste level applied while breaking a trigger block. |
| `professions.builder.haste_refresh_seconds` | `1` | How often the Builder's Haste effect is refreshed while breaking eligible blocks. |
| `professions.builder.haste_trigger_blocks` | See default below. | Blocks that can trigger the Builder's Haste effect. |
| `professions.builder.icon` | `"⚒"` | Builder tag icon, one glyph. |
| `professions.builder.level_up_count` | `1000` | Building blocks placed to reach Master. |
| `professions.builder.mastered_color` | `16766720` | Master Builder tag colour, as a 24-bit RGB integer. |
| `professions.builder.reach_bonus_apprentice_blocks` | `1.0` | Extra block-interaction reach at Apprentice, added to the vanilla range (4.5 blocks). |
| `professions.builder.reach_bonus_master_blocks` | `2.0` | Extra block-interaction reach at Master, added to the vanilla range (4.5 blocks). |
| `professions.enabled` | `true` | Master switch for the profession system. |
| `professions.farmer.baby_growth_factor_apprentice` | `1.15` | Animal offspring growth multiplier at Apprentice. |
| `professions.farmer.baby_growth_factor_master` | `1.3` | Animal offspring growth multiplier at Master. |
| `professions.farmer.bonus_output_chance_apprentice` | `0.01` | Chance of bonus output when crafting or cooking, at Apprentice. |
| `professions.farmer.bonus_output_chance_master` | `0.05` | Chance of bonus output when crafting or cooking, at Master. |
| `professions.farmer.breeding_cooldown_factor_apprentice` | `0.1` | Animal breeding cooldown multiplier at Apprentice. |
| `professions.farmer.breeding_cooldown_factor_master` | `0.2` | Animal breeding cooldown multiplier at Master. |
| `professions.farmer.color` | `5627221` | Apprentice Farmer tag colour, as a 24-bit RGB integer. |
| `professions.farmer.crop_boost_chance_apprentice` | `0.1` | Chance of triggering the crop-growth effect at Apprentice. |
| `professions.farmer.crop_boost_chance_master` | `0.2` | Chance of triggering the crop-growth effect at Master. |
| `professions.farmer.crop_boost_cooldown_minutes` | `4` | Cooldown for the crop-growth effect. |
| `professions.farmer.crop_boost_radius_blocks` | `24` | Radius scanned for the crop-growth effect. |
| `professions.farmer.icon` | `"☘"` | Farmer tag icon, one glyph. |
| `professions.farmer.level_up_count` | `300` | Crops harvested, animals fed and bred, to reach Master. |
| `professions.farmer.mastered_color` | `16766720` | Master Farmer tag colour, as a 24-bit RGB integer. |
| `professions.merchant.auction_purchase_count` | `5` | Purchases from `/ah` needed to reach Master. A second, separate counter. |
| `professions.merchant.color` | `16777045` | Apprentice Merchant tag colour, as a 24-bit RGB integer. |
| `professions.merchant.cost_factor_apprentice` | `0.05` | Villager trade price multiplier at Apprentice. |
| `professions.merchant.cost_factor_master` | `0.15` | Villager trade price multiplier at Master. |
| `professions.merchant.icon` | `"⚖"` | Merchant tag icon, one glyph. |
| `professions.merchant.mastered_color` | `16766720` | Master Merchant tag colour, as a 24-bit RGB integer. |
| `professions.merchant.max_trades_per_villager` | `20` | Per-villager cap on that count. |
| `professions.merchant.villager_trade_count` | `50` | Villager trades needed to reach Master. Trades made with a stick do not count. |
| `professions.miner.color` | `8952319` | Apprentice Miner tag colour, as a 24-bit RGB integer. |
| `professions.miner.double_drop_chance_apprentice` | `0.05` | Double-drop chance at Apprentice. |
| `professions.miner.double_drop_chance_master` | `0.15` | Double-drop chance at Master. |
| `professions.miner.double_value_ores` | See default below. | Ores that count twice toward Miner progress. |
| `professions.miner.haste_level` | `2` | Haste level while breaking an ore or stone-type block. |
| `professions.miner.haste_refresh_seconds` | `1` | How often the Miner's Haste effect is refreshed while breaking eligible blocks. |
| `professions.miner.haste_trigger_blocks` | See default below. | Blocks that can trigger the Miner's Haste effect. |
| `professions.miner.icon` | `"⛏"` | Miner tag icon, one glyph. |
| `professions.miner.lava_cooldown_minutes` | `5` | Cooldown before that Regeneration can trigger again. |
| `professions.miner.lava_regeneration_level` | `2` | Regeneration level granted on touching lava. |
| `professions.miner.lava_regeneration_seconds` | `4` | How long that Regeneration lasts. |
| `professions.miner.level_up_count` | `270` | Ores mined to reach Master. |
| `professions.miner.mastered_color` | `16766720` | Master Miner tag colour, as a 24-bit RGB integer. |
| `professions.miner.ore_tags` | See default below. | Block tags used to identify ores for Miner progress. |
| `professions.rust_effect_factor` | `0.5` | What "half effect" means, as a multiplier on the job's numbers. |
| `professions.rust_online_minutes` | `45` | Online minutes at half effect after returning to a profession. |
| `professions.selection_lockout_hours` | `30` | How long a profession choice holds. `0` = off. |
| `professions.soldier.adrenaline_cooldown_minutes` | `5` | `Adrenaline` cooldown. It triggers automatically, never manually. |
| `professions.soldier.adrenaline_duration_factor` | `0.5` | The halving itself. |
| `professions.soldier.adrenaline_window_seconds` | `4` | `Adrenaline` halves negative effects only within this of the first debuff. |
| `professions.soldier.color` | `16733525` | Apprentice Soldier tag colour, as a 24-bit RGB integer. |
| `professions.soldier.damage_dealt_factor_apprentice` | `1.05` | Damage dealt multiplier at Apprentice. |
| `professions.soldier.damage_dealt_factor_master` | `1.15` | Damage dealt multiplier at Master. |
| `professions.soldier.damage_taken_factor_apprentice` | `0.95` | Damage taken multiplier at Apprentice. |
| `professions.soldier.damage_taken_factor_master` | `0.85` | Damage taken multiplier at Master. |
| `professions.soldier.icon` | `"⚔"` | Soldier tag icon, one glyph. |
| `professions.soldier.kill_count` | `100` | Kills needed to reach Master. |
| `professions.soldier.mastered_color` | `16766720` | Master Soldier tag colour, as a 24-bit RGB integer. |

#### Default block and item lists

The list-valued profession settings use these shipped values:

```json
{
  "professions": {
    "builder": {
      "building_blocks": [
        "#minecraft:logs",
        "#minecraft:planks",
        "#minecraft:stairs",
        "#minecraft:slabs",
        "#minecraft:walls",
        "#minecraft:fences",
        "#minecraft:fence_gates",
        "#minecraft:terracotta",
        "#minecraft:stone_bricks",
        "minecraft:scaffolding",
        "minecraft:dirt",
        "minecraft:coarse_dirt",
        "minecraft:rooted_dirt",
        "minecraft:mud",
        "minecraft:glass",
        "minecraft:tinted_glass",
        "#c:glass_blocks",
        "#c:glass_panes",
        "minecraft:smooth_stone",
        "minecraft:bricks",
        "minecraft:mud_bricks",
        "minecraft:packed_mud",
        "minecraft:prismarine",
        "minecraft:dark_prismarine",
        "minecraft:prismarine_bricks",
        "minecraft:purpur_block",
        "minecraft:purpur_pillar",
        "minecraft:end_stone_bricks",
        "minecraft:quartz_block",
        "minecraft:smooth_quartz",
        "minecraft:chiseled_quartz_block",
        "minecraft:quartz_pillar",
        "minecraft:quartz_bricks"
      ],
      "haste_trigger_blocks": [
        "minecraft:stone",
        "minecraft:cobblestone",
        "minecraft:dirt"
      ]
    },
    "miner": {
      "double_value_ores": [
        "minecraft:diamond_ore",
        "minecraft:deepslate_diamond_ore",
        "minecraft:gold_ore",
        "minecraft:deepslate_gold_ore",
        "minecraft:nether_gold_ore"
      ],
      "haste_trigger_blocks": [
        "minecraft:stone",
        "minecraft:deepslate",
        "minecraft:tuff",
        "minecraft:netherrack",
        "#minecraft:ores"
      ],
      "ore_tags": [
        "#minecraft:ores"
      ]
    }
  }
}
```

### `quests`

The server-funded quest board selects shop items at regular intervals and posts requests from the Server Quests account. Completing a request pays the listed reward, including any configured order tax. The weekly budget caps the total amount the board can pay; unused budget does not become player money.

| Key | Default | Description |
|---|---|---|
| `quests.blacklist` | `[]` | Price keys never drawable, even inside the unit window. |
| `quests.bot_name` | `"Server Quests"` | Display name the reserved bot account resolves to in order lists and lore. |
| `quests.buyback.enabled` | `true` | Resell accumulated quest stock through ordinary `/ah` listings owned by the bot. |
| `quests.buyback.price_factor` | `0.8` | Fraction of the effective buy unit the buyback charges. Below `1` sells stock back discounted. |
| `quests.category_weights.blocks.stones` | `10` | Relative selection weight for stone block categories in the periodic quest draw. |
| `quests.category_weights.food` | `25` | Relative selection weight for food categories in the periodic quest draw. |
| `quests.category_weights.ores` | `30` | Relative selection weight for ore categories in the periodic quest draw. |
| `quests.category_weights.redstone` | `20` | Relative selection weight for redstone categories in the periodic quest draw. |
| `quests.category_weights.utility` | `15` | Relative selection weight for utility categories in the periodic quest draw. |
| `quests.enabled` | `true` | Master switch for the server-funded quest board. |
| `quests.max_per_category` | `2` | Maximum number of items from one category in a periodic quest draw. |
| `quests.max_unsold_expiries` | `1` | Unsold buyback expiries allowed before an item is removed from the buyback market. |
| `quests.max_concurrent` | `10` | Safety ceiling on simultaneously open quest orders. |
| `quests.max_quest_unit` | `100` | Highest quest-unit price an entry may be drawn at. Kills quests that would pay a fortune for one item. |
| `quests.min_quest_unit` | `3` | Lowest quest-unit price an entry may be drawn at. Kills zero-value junk. |
| `quests.period_days` | `7` | Days in one quest period, counted from the last rollover rather than a calendar boundary. |
| `quests.price_factor` | `0.5` | Fraction of the effective buy unit a quest pays per item. |
| `quests.require_shop_price` | `true` | Only entries with a buy price are drawn. Sell-only catalog entries never reach the board or `/ah`. |
| `quests.reprice_threshold_percent` | `0.1` | Minimum relative price change that triggers a quest order update. |
| `quests.sell_fallback_multiplier` | `3.3` | Multiplier on `unit_sell` for entries with no buy price. |
| `quests.weekly_budget` | `12000` | Maximum amount the Server Quests account can spend per quest period. `0` posts no quests. |
| `quests.weekly_count` | `10` | How many distinct items the periodic draw picks. |

### `gemini_gossip`

Optional AI-generated villager dialogue. When enabled and configured, a villager may send a private line when
you open its trade interface. The dialogue service sends context to a third-party API; review the settings
before enabling it. Player names are anonymized according to the configured option.

| Key | Default | Description |
|---|---|---|
| `gemini_gossip.anonymize_players` | `true` | Replace real player names with archetypes before anything is sent to the provider. |
| `gemini_gossip.api_key` | `""` | API key for the provider. **Empty by default; villagers stay silent until this is set.** |
| `gemini_gossip.base_url` | `"https://generativelanguage.googleapis.com"` | Provider base URL. |
| `gemini_gossip.cooldown_minutes` | `3` | Minimum gap between generations for the same player-villager pair. Clamped to 1-60. |
| `gemini_gossip.dialogue_system_instruction` | See the shipped prompt below. | Instructions used to guide villager dialogue. |
| `gemini_gossip.enabled` | `true` | Master switch for AI-generated villager dialogue. Has no effect without `api_key`. |
| `gemini_gossip.model` | `"gemini-3.8-flash"` | Model identifier. Shipped as `gemini-3.8-flash`. |
| `gemini_gossip.private_chat_chance` | `0.5` | Chance that a villager says something when you open its trade interface. The line is sent **only to you**. Clamped to 0.0-1.0. |
| `gemini_gossip.temperature` | `0.85` | Sampling temperature. Clamped to 0.0-2.0. |


#### Shipped dialogue prompt

```text
Dialogue Instructions:
1. Keep it short and easy to understand: most lines should be under 15 words. Avoid overly complex prose or purple vocabulary.
2. Speak in exactly 1 concise, conversational sentence matching your personality, quirk, and relationship with this player.
3. Address the player or your past memories directly when appropriate.
4. Villagers have quirky mannerisms: occasionally mutter or hum ('Hmm...', 'Huh?', 'Haah...'), but vary how you speak and DO NOT start every line with 'Hrmm...'.
5. Respond strictly with valid JSON with fields:
   {
     "dialogue": "<your concise line>",
     "sentiment_delta": <-2 to 5 integer>
   }
```

### `motd`

The ordered message blocks sent to a player when they join. Formatting codes, player placeholders and clickable
URLs work in each line. Existing `motd.lines` arrays are automatically migrated to one block before defaults are
merged. If both legacy `lines` and `blocks` are present, `blocks` is used and `lines` is discarded.

| Key | Default | Description |
|---|---|---|
| `motd.delay_ticks` | `40` | Ticks after joining before the message appears (`40` = 2 seconds). Clamped to 0-1200. |
| `motd.enabled` | `true` | Send the join message on login. |
| `motd.blocks` | See default below. | Ordered message blocks. Each block has formatted `lines` and a delay before the next block. |
| `motd.release_notes.enabled` | `true` | Append the latest release summary to the login MOTD. |
| `motd.release_notes.after_block` | `-1` | Insert release notes before block 1 (`0`), after block N (`N`), or after the last block (`-1`). Invalid positions fall back to the last block. |
| `motd.release_notes.max_changes` | `3` | Maximum number of newest merged PR titles to show. Clamped to 0-20; `0` keeps the linked version title but hides the titles. |

`motd.delay_ticks` applies before the first item in the sequence. A block's `next_delay_seconds` applies before the
next configured block or an inserted release summary. If release notes follow the final block, that final delay is
used too; with the default block it is 30 seconds. When a summary is inserted between blocks, the following block
continues immediately after the summary. `/eco motd` previews the same ordering and settings immediately, without
delays, even when `motd.enabled` disables automatic join messages. `/eco reload` cancels sequences already in progress.


#### Default message blocks

```json
{
  "motd": {
    "release_notes": {
      "enabled": true,
      "after_block": -1,
      "max_changes": 3
    },
    "blocks": [
      {
        "lines": [
          "&8&m----------------------------------------",
          "&6Hello &e{player}&6!",
          "&7If you want to share any ideas, create an issue at: &bhttps://github.com/TraiNguyenVan/EconomyCraft/issues",
          "&8&m----------------------------------------"
        ],
        "next_delay_seconds": 30
      }
    ]
  }
}
```

## `webhook.json`

| Key                  | Default | Description                                            |
|----------------------|---------|--------------------------------------------------------|
| `webhook_enabled`    | `false` | Post transactions to `webhook_url`.                    |
| `webhook_url`        | `""`    | Discord-compatible incoming webhook URL.               |
| `webhook_min_amount` | `0`     | Skip webhook posts for transactions smaller than this. |

## `prices.json`

One entry per shop item, keyed by item id:

```json
{
  "minecraft:diamond": {
    "category": "ores",
    "stack": 64,
    "unit_buy": 800,
    "unit_sell": 200
  }
}
```

`category` accepts `top.sub` for a sub-page, `stack` is the shift-click bulk amount, and `unit_buy`/`unit_sell` are the price of one item (`0` disables that direction). Items from installed mods are added automatically, using their mod ID as category and `0` for both prices.

The editor also writes a few extra keys:

- `components`: NBT for custom items (name, enchantments, container contents). A `#label` suffix distinguishes duplicates of the same item, e.g. `minecraft:shulker_box#loot_rare`.
- `"removed": true`: marks a deleted default so it isn't restored on the next start. Delete the entry to restore it.
- `"dynamic_price_enabled": false`: opts an item, or a category under `_categories`, out of dynamic pricing.

---
