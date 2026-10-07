# Ground Truth: Configuration Inventory

**Extracted**: 2026-10-07 from `common/src/main/resources/assets/economycraft/config.json`

**Total leaf keys**: **164**

**This file is the authority for every configuration default** (`contracts/config-reference-contract.md` R1-R3).
Do not state a default in any document from memory or from another document. Read it here.

> Keys are `snake_case` except top-level ones, which are `camelCase` (`startingBalance`, `dailyAmount`).
> `README.md` documents `factions`/`professions` keys *without* the section prefix under a heading;
> the full path below is what ships and what must be documented.


## `Top level` — 34 keys

| Key | Default |
|---|---|
| `auction_enabled` | `true` |
| `auction_expiration_hours` | `168` |
| `balance_separator` | `"."` |
| `dailyAmount` | `100` |
| `dailySellLimit` | `10000` |
| `dynamic_price_max_multiplier` | `5.0` |
| `dynamic_price_min_active_days` | `30` |
| `dynamic_price_min_multiplier` | `0.5` |
| `dynamic_prices_enabled` | `false` |
| `max_active_auctions_per_player` | `0` |
| `max_active_orders_per_player` | `0` |
| `max_active_tolls_per_player` | `10` |
| `order_expiration_hours` | `168` |
| `orders_enabled` | `true` |
| `scoreboard_enabled` | `true` |
| `sell_enabled` | `true` |
| `shop_enabled` | `true` |
| `standalone_admin_commands` | `false` |
| `standalone_commands` | `true` |
| `startingBalance` | `1000` |
| `taxRate` | `0.1` |
| `transaction_log_enabled` | `true` |
| `transaction_log_retention_days` | `7` |
| `wealth_tax_enabled` | `false` |
| `wealth_tax_floor` | `1000` |
| `wealth_tax_inactive_days` | `7` |
| `wealth_tax_inactive_multiplier` | `3.33` |
| `wealth_tax_max_catchup_days` | `7` |
| `wealth_tax_median_floor_factor` | `0.5` |
| `wealth_tax_rate` | `0.015` |
| `wealth_tax_rebate_enabled` | `false` |
| `wealth_tax_rebate_max_rate` | `0.01` |
| `wealth_tax_rebate_trigger_factor` | `1.0` |
| `worth_enabled` | `true` |

## `factions` — 38 keys

| Key | Default |
|---|---|
| `factions.anarchism.color` | `11184810` |
| `factions.anarchism.icon` | `"\u24b6"` |
| `factions.anarchism.unclaimed_horse_speed_multiplier` | `1.15` |
| `factions.anarchism.unclaimed_speed_multiplier` | `1.15` |
| `factions.capitalism.color` | `5635925` |
| `factions.capitalism.concentration_elasticity` | `1.0` |
| `factions.capitalism.concentration_max_multiplier` | `5.0` |
| `factions.capitalism.concentration_min_multiplier` | `0.0` |
| `factions.capitalism.concentration_reference_share` | `0.15` |
| `factions.capitalism.daily_tax_rate` | `0.025` |
| `factions.capitalism.icon` | `"$"` |
| `factions.capitalism.max_rate_change_per_day` | `0.25` |
| `factions.capitalism.toll_tax_multiplier` | `1.25` |
| `factions.capitalism.use_global_inflation` | `true` |
| `factions.communism.color` | `16733525` |
| `factions.communism.icon` | `"\u262d"` |
| `factions.communism.income_tax_tier1_rate` | `0.0025` |
| `factions.communism.income_tax_tier1_threshold` | `10000` |
| `factions.communism.income_tax_tier2_rate` | `0.00375` |
| `factions.communism.income_tax_tier2_threshold` | `15000` |
| `factions.communism.income_tax_tier3_rate` | `0.00625` |
| `factions.communism.income_tax_tier3_threshold` | `22000` |
| `factions.communism.party_fee` | `10` |
| `factions.communism.toll_tax_exempt_chance` | `0.5` |
| `factions.daily_tax_max_catchup_days` | `7` |
| `factions.enabled` | `true` |
| `factions.levy_interval_minutes` | `45` |
| `factions.monarchy.claim_cost_multiplier` | `0.5` |
| `factions.monarchy.color` | `16777045` |
| `factions.monarchy.corruption_multiplier` | `1.0` |
| `factions.monarchy.daily_tax_rate` | `0.01` |
| `factions.monarchy.icon` | `"\u2654"` |
| `factions.monarchy.import_tax_chance` | `0.5` |
| `factions.monarchy.import_tax_factor` | `0.5` |
| `factions.monarchy.money_supply_inflation_max` | `3.0` |
| `factions.monarchy.money_supply_reference_per_player` | `1000.0` |
| `factions.monarchy.own_claim_damage_multiplier` | `1.15` |
| `factions.selection_lockout_hours` | `30` |

## `professions` — 61 keys

| Key | Default |
|---|---|
| `professions.builder.building_blocks` | `["#minecraft:logs", "#minecraft:planks", "#minecraft:stairs", "#minecraft:slabs", "#minecraft:walls", "#minecraft:fences", "#minecraft:fence_gates", "#minecraft:terracotta", "#minecraft:stone_bricks", "minecraft:scaffolding", "minecraft:dirt", "minecraft:coarse_dirt", "minecraft:rooted_dirt", "minecraft:mud", "minecraft:glass", "minecraft:tinted_glass", "#c:glass_blocks", "#c:glass_panes", "minecraft:smooth_stone", "minecraft:bricks", "minecraft:mud_bricks", "minecraft:packed_mud", "minecraft:prismarine", "minecraft:dark_prismarine", "minecraft:prismarine_bricks", "minecraft:purpur_block", "minecraft:purpur_pillar", "minecraft:end_stone_bricks", "minecraft:quartz_block", "minecraft:smooth_quartz", "minecraft:chiseled_quartz_block", "minecraft:quartz_pillar", "minecraft:quartz_bricks"]` |
| `professions.builder.color` | `16755200` |
| `professions.builder.haste_level` | `1` |
| `professions.builder.haste_refresh_seconds` | `1` |
| `professions.builder.haste_trigger_blocks` | `["minecraft:stone", "minecraft:cobblestone", "minecraft:dirt"]` |
| `professions.builder.icon` | `"\u2692"` |
| `professions.builder.level_up_count` | `1000` |
| `professions.builder.mastered_color` | `16766720` |
| `professions.builder.reach_bonus_apprentice_blocks` | `1.0` |
| `professions.builder.reach_bonus_master_blocks` | `2.0` |
| `professions.enabled` | `true` |
| `professions.farmer.baby_growth_factor_apprentice` | `1.15` |
| `professions.farmer.baby_growth_factor_master` | `1.3` |
| `professions.farmer.bonus_output_chance_apprentice` | `0.01` |
| `professions.farmer.bonus_output_chance_master` | `0.05` |
| `professions.farmer.breeding_cooldown_factor_apprentice` | `0.1` |
| `professions.farmer.breeding_cooldown_factor_master` | `0.2` |
| `professions.farmer.color` | `5627221` |
| `professions.farmer.crop_boost_chance_apprentice` | `0.1` |
| `professions.farmer.crop_boost_chance_master` | `0.2` |
| `professions.farmer.crop_boost_cooldown_minutes` | `4` |
| `professions.farmer.crop_boost_radius_blocks` | `24` |
| `professions.farmer.icon` | `"\u2618"` |
| `professions.farmer.level_up_count` | `300` |
| `professions.farmer.mastered_color` | `16766720` |
| `professions.merchant.auction_purchase_count` | `5` |
| `professions.merchant.color` | `16777045` |
| `professions.merchant.cost_factor_apprentice` | `0.05` |
| `professions.merchant.cost_factor_master` | `0.15` |
| `professions.merchant.icon` | `"\u2696"` |
| `professions.merchant.mastered_color` | `16766720` |
| `professions.merchant.max_trades_per_villager` | `20` |
| `professions.merchant.villager_trade_count` | `50` |
| `professions.miner.color` | `8952319` |
| `professions.miner.double_drop_chance_apprentice` | `0.05` |
| `professions.miner.double_drop_chance_master` | `0.15` |
| `professions.miner.double_value_ores` | `["minecraft:diamond_ore", "minecraft:deepslate_diamond_ore", "minecraft:gold_ore", "minecraft:deepslate_gold_ore", "minecraft:nether_gold_ore"]` |
| `professions.miner.haste_level` | `2` |
| `professions.miner.haste_refresh_seconds` | `1` |
| `professions.miner.haste_trigger_blocks` | `["minecraft:stone", "minecraft:deepslate", "minecraft:tuff", "minecraft:netherrack", "#minecraft:ores"]` |
| `professions.miner.icon` | `"\u26cf"` |
| `professions.miner.lava_cooldown_minutes` | `5` |
| `professions.miner.lava_regeneration_level` | `2` |
| `professions.miner.lava_regeneration_seconds` | `4` |
| `professions.miner.level_up_count` | `270` |
| `professions.miner.mastered_color` | `16766720` |
| `professions.miner.ore_tags` | `["#minecraft:ores"]` |
| `professions.rust_effect_factor` | `0.5` |
| `professions.rust_online_minutes` | `45` |
| `professions.selection_lockout_hours` | `30` |
| `professions.soldier.adrenaline_cooldown_minutes` | `5` |
| `professions.soldier.adrenaline_duration_factor` | `0.5` |
| `professions.soldier.adrenaline_window_seconds` | `4` |
| `professions.soldier.color` | `16733525` |
| `professions.soldier.damage_dealt_factor_apprentice` | `1.05` |
| `professions.soldier.damage_dealt_factor_master` | `1.15` |
| `professions.soldier.damage_taken_factor_apprentice` | `0.95` |
| `professions.soldier.damage_taken_factor_master` | `0.85` |
| `professions.soldier.icon` | `"\u2694"` |
| `professions.soldier.kill_count` | `100` |
| `professions.soldier.mastered_color` | `16766720` |

## `quests` — 14 keys

| Key | Default |
|---|---|
| `quests.blacklist` | `[]` |
| `quests.bot_name` | `"Server Quests"` |
| `quests.buyback.enabled` | `true` |
| `quests.buyback.price_factor` | `0.8` |
| `quests.enabled` | `true` |
| `quests.max_concurrent` | `10` |
| `quests.max_quest_unit` | `100` |
| `quests.min_quest_unit` | `3` |
| `quests.period_days` | `7` |
| `quests.price_factor` | `0.5` |
| `quests.require_shop_price` | `true` |
| `quests.sell_fallback_multiplier` | `3.3` |
| `quests.weekly_budget` | `12000` |
| `quests.weekly_count` | `10` |

## `gemini_gossip` — 14 keys

| Key | Default |
|---|---|
| `gemini_gossip.anonymize_players` | `true` |
| `gemini_gossip.api_key` | `""` |
| `gemini_gossip.base_url` | `"https://generativelanguage.googleapis.com"` |
| `gemini_gossip.cooldown_minutes` | `3` |
| `gemini_gossip.dialogue_system_instruction` | `"Dialogue Instructions:\n1. Keep it short and easy to understand: most lines should be under 15 words. Avoid overly complex prose or purple vocabulary.\n2. Speak in exactly 1 concise, conversational sentence matching your personality, quirk, and relationship with this player.\n3. Address the player or your past memories directly when appropriate.\n4. Villagers have quirky mannerisms: occasionally mutter or hum ('Hmm...', 'Huh?', 'Haah...'), but vary how you speak and DO NOT start every line with 'Hrmm...'.\n5. Respond strictly with valid JSON with fields:\n   {\n     \"dialogue\": \"<your concise line>\",\n     \"sentiment_delta\": <-2 to 5 integer>\n   }"` |
| `gemini_gossip.enabled` | `true` |
| `gemini_gossip.model` | `"gemini-3.8-flash"` |
| `gemini_gossip.pool_size_per_category` | `3` |
| `gemini_gossip.private_chat_chance` | `0.5` |
| `gemini_gossip.public_chat` | `false` |
| `gemini_gossip.public_chat_chance` | `0.25` |
| `gemini_gossip.refresh_interval_minutes` | `20` |
| `gemini_gossip.system_instruction` | `"You are a witty, satirical economic gossip for Minecraft villagers on an economy server. Based on the provided transaction summary, write 2-3 short, exaggerated gossip lines (1 sentence each) for each villager profession. Villagers have quirky mannerisms: occasionally mutter, sigh, or hum (e.g. 'Hmm...', 'Hrmm...', 'Huh?', 'Haah...'), but vary how lines begin and do NOT start every line with 'Hrmm...' \u2014 many lines should begin directly. Always refer to money in dollars ('$'). Never mention real player usernames; use the given archetypes."` |
| `gemini_gossip.temperature` | `0.85` |

## `motd` — 3 keys

| Key | Default |
|---|---|
| `motd.delay_ticks` | `40` |
| `motd.enabled` | `true` |
| `motd.lines` | `["&8&m----------------------------------------", "&6Hello &e{player}&6!", "&7If you want to share any ideas, create an issue at: &bhttps://github.com/TraiNguyenVan/EconomyCraft/issues", "&8&m----------------------------------------"]` |

---

## Known-wrong values currently in `README.md`

| Key | README says | Ships | README lines |
|---|---|---|---|
| `factions.capitalism.daily_tax_rate` | `0.05` | `0.025` | 225, 408 |
| `factions.monarchy.daily_tax_rate` | `0.017` | `0.01` | 232, 410 |
| `factions.communism.income_tax_tier1_rate` | `0.005` | `0.0025` | 221 |
| `factions.communism.income_tax_tier2_rate` | `0.0075` | `0.00375` | 222 |
| `factions.communism.income_tax_tier3_rate` | `0.0125` | `0.00625` | 223 |

## Phantom keys: named in `README.md`, do not ship

| Documented | Ships |
|---|---|
| `ownClaimDamageMultiplier` | `factions.monarchy.own_claim_damage_multiplier` |
| `crop_boost_interval_minutes` | `professions.farmer.crop_boost_cooldown_minutes` |
| `lava_regen_duration_seconds` | `professions.miner.lava_regeneration_seconds` |
| `discount_master` | `professions.merchant.cost_factor_master` |

## Not documented anywhere

All `quests` (14), `gemini_gossip` (14) and `motd` (3) keys, plus 99 further keys in
`professions` and `factions`. See `baseline.md` for the measured count.
