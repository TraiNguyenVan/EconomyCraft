# EconomyCraft

A server-side economy for Fabric and NeoForge.
Requires Architectury API.

---

## The `/eco` menu

| Buttom            | Description                                                                                                              |
|-------------------|--------------------------------------------------------------------------------------------------------------------------|
| **Shop**          | Buy and sell at fixed prices with unlimited stock. Left click buys, right click sells, shift-click uses the bulk amount. |
| **Auction House** | Buy items other players have listed, or list your own. Offline sellers receive a sale notice when they return.           |
| **Sell Items**    | Drop items in, check the total, confirm. Unpriced items can not be sold.                                                 |
| **Orders**        | Request an item, amount and price. Other players fill it and get paid.                                                   |
| **Daily Reward**  | Claims the daily payout, once per day.                                                                                   |
| **Pay a Player**  | Send money to another player.                                                                                            |
| **Leaderboards**  | Top Balances, Earners, Spenders, Sellers, Buyers and Traders.                                                            |
| **Item Value**    | The buy and sell price of any item.                                                                                      |
| **Deliveries**    | Items or payouts that couldn't be delivered directly (full inventory/completed while offline).                           |
| **Transactions**  | Your recent balance history.                                                                                             |
| **Tolls**         | Manage the block you are looking at within five blocks; its fee appears on the action bar. See [toll management](wiki/Tolls.md). |

Each screen also has a command: `/bal`, `/pay`, `/daily`, `/shop`, `/ah`, `/auction`, `/sell`, `/worth`, `/orders`, `/deliveries`, `/transactions`.

---

## The Admin (`/eco admin`) menu

### Shop editor

Browse categories and click an item to edit it.

- **Category editor**: right-click a category to rename it, change its color, icon or visibility, or exclude it from dynamic pricing. Deleting a category moves its items to `misc` and zeroes their buy prices.
- **Add item**: pick any item in the game, or one from your inventory. Custom names, enchantments and container contents are kept.
- **Buy Price / Sell Price**: the price of one item; `0` disables that direction. If dynamic pricing applies, this also shows the current price and multiplier.
- **Dynamic Pricing**: opt this item out of dynamic pricing even while it's enabled server-wide.
- **Bulk Amount**: how many a shift-click buys or sells.
- **Category**: which shop page the item appears on. `blocks.wood` creates a sub-page.
- **Delete**: removes the entry.

### Dynamic shop pricing

Optional, off by default (`dynamic_prices_enabled`). Scales every buy price by:

```
current price = base price × active-player median balance / starting balance
```

The scale is clamped between `dynamic_price_min_multiplier` and `dynamic_price_max_multiplier`, and recalculated at most once an hour. Sell prices are never affected. Opt out per category or item from the Shop editor above.

### Daily wealth tax

Optional, off by default (`wealth_tax_enabled`). Once per day, every balance above the floor pays a percentage of its surplus:

```
floor = max(wealth_tax_floor, active-player median balance x wealth_tax_median_floor_factor)
tax   = (balance - floor) x rate
```

Because the floor is a fraction of the median rather than a fixed number, it follows the economy: as rich balances pay down and the median drifts lower, the tax base shrinks on its own.

`wealth_tax_median_floor_factor` decides who is reached:

| Factor  | Floor        | Reaches                                    |
|---------|--------------|--------------------------------------------|
| `0.0`   | fixed        | everyone above the floor, including the poorest players |
| `0.5`   | half median  | the upper half                              |
| `1.0`   | the median   | only the top half, and the median can then never fall |

Players unseen for `wealth_tax_inactive_days` are charged `rate x wealth_tax_inactive_multiplier`, so the policy drains idle stock rather than taxing play.

The optional rebate (`wealth_tax_rebate_enabled`, off by default) is the injection side. It only arms when the median falls below `startingBalance x wealth_tax_rebate_trigger_factor` — the aggregate gate matters, because with a healthy median the floor sits above the poorest players, and a per-player test alone would pay out in a healthy economy.

Balances at or below the floor are never taxed, and a tax can never push a balance below the floor. Movement is recorded under `economycraft:wealth_tax` / `economycraft:wealth_rebate` and is deliberately excluded from the Leaderboards' earned/spent totals, since it is neither income nor spending.

### Settings

Every option in `config.json`, editable in-game.

### Players

Select any player, online or not, to give, take or set their balance, clear their Leaderboard stats, remove them from the economy, or override their max active orders/auctions.

### Reset Tools


| Tool                        | Does                                                                                        |
|-----------------------------|---------------------------------------------------------------------------------------------|
| **Reset All Balances**      | Sets every player's balance back to `startingBalance`.                                      |
| **Reset Daily Reward Data** | Everyone can claim their daily reward again immediately.                                    |
| **Reset Daily Sell Limits** | Everyone's daily sell limit resets to full immediately.                                     |
| **Clear Auctions**          | Cancels every active listing. Items are returned to sellers' deliveries.                    |
| **Clear Orders**            | Cancels every open order. Escrowed money is refunded to requesters.                         |
| **Run Wealth Tax Now**      | Applies the daily wealth tax immediately and reports what it took, per day.                |
| **Reset Entire Economy**    | All of the above, plus wipes the Leaderboards stats and deletes the entire transaction log. |

None of these touch shop prices/categories or permission settings. **Run Wealth Tax Now** is the one tool that moves real balances as a side effect; it does nothing while `wealth_tax_enabled` is off.

### Admin commands

`/eco addmoney`, `/eco setmoney`, `/eco removemoney`, `/eco removeplayer`.

---

## Permissions

Admin and command access is gated by permission nodes. Any admin node not set by a permission plugin falls back to OP; any command node not set falls back to allowed for everyone.

### Admin nodes

| Node                          | Grants                                                                                        |
|-------------------------------|-----------------------------------------------------------------------------------------------|
| `economycraft.admin`          | Everything below                                                                              |
| `economycraft.admin.players`  | `/eco addmoney`, `/eco setmoney`, `/eco removemoney`, `/eco removeplayer`, the Players screen |
| `economycraft.admin.settings` | The Settings screen                                                                           |
| `economycraft.admin.shop`     | The Shop editor (from the Admin menu or the in-shop edit button)                              |
| `economycraft.admin.reload`   | The "Reload from disk" button                                                                 |
| `economycraft.admin.reset`    | The Reset Tools screen                                                                        |

### Command nodes

| Node                                | Grants              |
|-------------------------------------|---------------------|
| `economycraft.command.menu`         | `/eco`, `/eco menu` |
| `economycraft.command.balance`      | `/bal`              |
| `economycraft.command.pay`          | `/pay`              |
| `economycraft.command.shop`         | `/shop`             |
| `economycraft.command.auction`      | `/ah`, `/auction`   |
| `economycraft.command.sell`         | `/sell`             |
| `economycraft.command.orders`       | `/orders`           |
| `economycraft.command.deliveries`   | `/deliveries`       |
| `economycraft.command.daily`        | `/daily`            |
| `economycraft.command.transactions` | `/transactions`     |
| `economycraft.command.worth`        | `/worth`            |
| `economycraft.command.toll`         | `/eco toll`, `/toll`, and the Tolls menu |

---

## Config files

Stored in `config/economycraft/` on a server (or `saves/<world>/economycraft/` per-world in singleplayer): `config.json`, `webhook.json` and `prices.json` at the top, player data under `data/`.

Phase 2 added four data files: `online_time.json` (accumulated online time), `cooldowns.json` (wall-clock
cooldowns), `parties.json` (party choices and their lockouts) and `professions.json` (profession, progress and
rust state). They are written only when something changes, and are deliberately **not** part of the
singleplayer-to-server folder import: an import moves balances and prices, not who is in which party.

### `config.json`

| Key                              | Default | Description                                                                                                     |
|----------------------------------|---------|-----------------------------------------------------------------------------------------------------------------|
| `startingBalance`                | `1000`  | Money new players start with.                                                                                   |
| `dailyAmount`                    | `100`   | Money given by the daily reward.                                                                                |
| `dailySellLimit`                 | `10000` | Most a player can earn per day from selling. `0` disables the limit.                                            |
| `taxRate`                        | `0.1`   | Tax on trades and orders, as a decimal (`0.1` = 10%).                                                           |
| `standalone_commands`            | `true`  | Allow `/pay`, `/daily` etc. without the `/eco` prefix.                                                          |
| `standalone_admin_commands`      | `false` | Allow `/addmoney`, `/setmoney` etc. without the `/eco` prefix.                                                  |
| `scoreboard_enabled`             | `true`  | Show the balance sidebar.                                                                                       |
| `shop_enabled`                   | `true`  | Enable the fixed-price shop.                                                                                    |
| `auction_enabled`                | `true`  | Enable the auction house.                                                                                       |
| `orders_enabled`                 | `true`  | Enable the orders board. Deliveries still work either way.                                                      |
| `sell_enabled`                   | `true`  | Enable selling.                                                                                                 |
| `worth_enabled`                  | `true`  | Enable item value lookups (`/worth`).                                                                           |
| `balance_separator`              | `"."`   | Thousands separator, e.g. `","` gives `$1,000`.                                                                 |
| `transaction_log_enabled`        | `true`  | Record every balance change to a daily log file.                                                                |
| `transaction_log_retention_days` | `7`     | How many days of transaction logs to keep.                                                                      |
| `order_expiration_hours`         | `168`   | Hours before an unfulfilled order expires and its escrow is refunded. `0` disables expiration.                  |
| `auction_expiration_hours`       | `168`   | Hours before an unsold auction expires and its item goes to deliveries. `0` disables expiration.                |
| `max_active_orders_per_player`   | `0`     | Most open orders a player can have at once. `0` = unlimited. Overridable per player.                            |
| `max_active_auctions_per_player` | `0`     | Most active auctions a player can have at once. `0` = unlimited. Overridable per player.                        |
| `dynamic_prices_enabled`         | `false` | Scale shop buy prices with the active-player median balance. See [Dynamic shop pricing](#dynamic-shop-pricing). |
| `dynamic_price_min_multiplier`   | `0.5`   | Lowest allowed price scale.                                                                                     |
| `dynamic_price_max_multiplier`   | `5.0`   | Highest allowed price scale.                                                                                    |
| `dynamic_price_min_active_days`  | `30`    | Players must have logged in within this many days to count as active. `0` includes everyone.                    |
| `wealth_tax_enabled`             | `false` | Apply the daily wealth tax. See [Daily wealth tax](#daily-wealth-tax).                                          |
| `wealth_tax_rate`                | `0.015` | Daily cut taken from the surplus above the floor, as a decimal.                                                  |
| `wealth_tax_floor`               | `1000`  | Balances at or below this are never taxed. Also the rebate target.                                               |
| `wealth_tax_median_floor_factor` | `0.5`   | How far the floor follows the active median. `0` keeps it fixed, `1` makes it equal the median.                  |
| `wealth_tax_inactive_days`       | `7`     | Days before an unseen player is charged the idle rate. `0` = one rate for everyone.                              |
| `wealth_tax_inactive_multiplier` | `3.33`  | Rate multiplier for players past the inactive window.                                                            |
| `wealth_tax_max_catchup_days`    | `7`     | Most days applied after downtime. `0` = unlimited.                                                               |
| `wealth_tax_rebate_enabled`      | `false` | Pay players below the floor when the median has crashed. Creates money.                                         |
| `wealth_tax_rebate_max_rate`     | `0.01`  | Rebate rate applied to the shortfall below the floor.                                                            |
| `wealth_tax_rebate_trigger_factor`| `1.0`   | Rebate arms only when the median is below `startingBalance x` this.                                             |

#### `factions` and `professions`

These two sections hold the party and profession tuning for the faction & profession system. **Phase 2 shipped
them as data only** — every key is read, clamped and saved, and nothing consumes it yet; the effects arrive in
later phases (`TODO.md` §7). They are listed here so the numbers are reviewable before anything acts on them,
and so the defaults are visible without opening `config.json`.

`factions`:

| Key                                | Default    | Description                                                                       |
|------------------------------------|------------|-----------------------------------------------------------------------------------|
| `enabled`                          | `true`     | Master switch for the party system.                                               |
| `selection_lockout_hours`          | `30`       | How long a party choice holds. Independent of the profession lockout. `0` = off.  |
| `levy_interval_minutes`            | `45`       | The spec's "45 minutes online" cadence, in online minutes.                       |
| `communism.party_fee`              | `10`       | `Đảng phí`, charged per interval. Burned — there is no recipient.                |
| `communism.income_tax_tier1_threshold` / `_rate` | `10000` / `0.005` | Anti-speculation income tax, tier 1.                                 |
| `communism.income_tax_tier2_threshold` / `_rate` | `15000` / `0.0075`| Anti-speculation income tax, tier 2.                                 |
| `communism.income_tax_tier3_threshold` / `_rate` | `22000` / `0.0125`| Anti-speculation income tax, tier 3 (highest match wins).             |
| `communism.toll_tax_exempt_chance` | `0.5`      | `Đầu tư công` — chance a toll is paid without tax. The toll owner is still paid. |
| `communism.container_lock_mode`    | `PARTY_ONLY` | `Cộng đồng`. One of `PARTY_ONLY`, `PRIVATE`, `UNLOCKED`. See the note below.  |
| `capitalism.daily_tax_rate`        | `0.05`     | Base daily rate, before the concentration multiplier.                            |
| `capitalism.use_global_inflation`  | `true`     | D14: read the existing inflation signal instead of a fourth independent measure. |
| `capitalism.concentration_reference_share` | `0.15` | Party wealth share at which the multiplier is exactly `1.0`.                   |
| `capitalism.concentration_elasticity` | `1.0`    | The single difficulty dial; above `1` punishes concentration harder.             |
| `capitalism.concentration_min_multiplier` / `_max_multiplier` | `0.0` / `5.0` | Bounds on that multiplier.                             |
| `capitalism.max_rate_change_per_day` | `0.25`    | D14's griefing brake on how fast the rate may move.                              |
| `capitalism.toll_tax_multiplier`   | `1.25`     | Toll tax amount multiplier.                                                      |
| `monarchy.daily_tax_rate`          | `0.05`     | **Assumption** — the spec never states Monarchy's own rate; this matches Capitalism. |
| `monarchy.corruption_multiplier`   | `1.0`      | `Cống nạp`, as a multiple of the daily tax. Burned.                             |
| `monarchy.claim_cost_multiplier`   | `0.5`      | `Tự trị` — halved claim cost.                                                    |
| `monarchy.own_claim_damage_multiplier` | `1.15` | `Phép vua thua lẹ làng`.                                                         |
| `monarchy.import_tax_chance` / `import_tax_factor` | `0.5` / `0.5` | `Nhập khẩu`.                                     |
| `anarchism.unclaimed_speed_multiplier` | `1.15`  | `Thoải mái` on unclaimed land.                                                    |
| `anarchism.unclaimed_horse_speed_multiplier` | `1.15` | Needs its own hook; horses ignore movement speed.                      |

Each faction also has `color` (24-bit RGB integer) and `icon` (one glyph). Colours are integers rather than
vanilla formatting names because the icon set needs shades the sixteen vanilla names do not include.

`professions`:

| Key                                        | Default  | Description                                                            |
|--------------------------------------------|----------|------------------------------------------------------------------------|
| `enabled`                                  | `true`   | Master switch for the profession system.                                |
| `selection_lockout_hours`                  | `30`     | The profession's own lockout. Changing party never disturbs it.        |
| `rust_online_minutes`                      | `45`     | `Lụt nghề` — online minutes at half effect after returning.              |
| `rust_effect_factor`                       | `0.5`    | What "half effect" means, as a multiplier on the job's numbers.         |
| `builder.level_up_count`                   | `1000`   | Building blocks placed.                                                 |
| `builder.reach_bonus_apprentice_blocks` / `_master_blocks` | `1.0` / `2.0` | `Thành thạo`. Kept as the spec's rule; see the note below.   |
| `builder.haste_level` / `haste_duration_seconds` | `1` / `30` | `Sửa lỗi`. Amplifier is from the spec; the duration is an assumption. |
| `builder.building_blocks`                  | spec list | 33 entries, verbatim. Tags (`#minecraft:logs`) and ids.                  |
| `farmer.level_up_count`                    | `300`    | Crops harvested, animals fed, breeding.                                 |
| `farmer.crop_boost_radius_blocks`          | `24`     | `Tươi tốt` scan radius.                                                  |
| `farmer.crop_boost_cooldown_minutes`       | `4`      | `Tươi tốt` cooldown.                                                     |
| `farmer.breeding_cooldown_factor_apprentice` / `_master` | `0.1` / `0.2` | `Chăm sóc` cooldown reduction.                 |
| `farmer.baby_growth_factor_apprentice` / `_master` | `1.15` / `1.3` | `Chăm sóc` offspring growth.                 |
| `farmer.bonus_output_chance_apprentice` / `_master` | `0.01` / `0.05` | `Khéo léo`.                       |
| `miner.level_up_count`                     | `270`    | Ores mined.                                                             |
| `miner.ore_tags`                           | `["#minecraft:ores"]` | The ore set, as a tag so modded ores count.                    |
| `miner.double_value_ores`                  | 5 ids     | Each counts as 2: diamond and gold ores.                                |
| `miner.haste_level` / `haste_duration_seconds` | `2` / `30` | `Lanh lợi` — Haste II, from the spec. Duration is an assumption. |
| `miner.double_drop_chance_apprentice` / `_master` | `0.05` / `0.15` | `Khéo tay`.                                     |
| `miner.lava_regeneration_level` / `_seconds` / `lava_cooldown_minutes` | `2` / `4` / `5` | `Bảo hộ lao động`, at Master.         |
| `merchant.villager_trade_count`            | `50`     | Villager trades. Trades made with a stick do not count.                 |
| `merchant.max_trades_per_villager`         | `20`     | Per-villager cap on that count.                                          |
| `merchant.auction_purchase_count`          | `5`      | Purchases from `/ah`; a second, separate counter.                        |
| `merchant.cost_factor_apprentice` / `_master` | `0.05` / `0.15` | `Lưỡi không xương`.                        |
| `soldier.kill_count`                       | `100`    | Kills.                                                                   |
| `soldier.damage_taken_factor_apprentice` / `_master` | `0.95` / `0.85` | `Sắt được tôi`.               |
| `soldier.damage_dealt_factor_apprentice` / `_master` | `1.05` / `1.15` | `Sắt được tôi`.               |
| `soldier.adrenaline_window_seconds`        | `4`      | Effects are only halved within this of the first debuff.                 |
| `soldier.adrenaline_cooldown_minutes`      | `5`      | `Andrenaline` cooldown; it triggers automatically, never manually.        |
| `soldier.adrenaline_duration_factor`       | `0.5`    | The halving itself.                                                      |

Every job also has `color` and `icon`, read the same way as a faction's.

Every number above is clamped rather than rejected, with the value and the bound in one warning line: a rate is
a 0–1 decimal factor, a colour is 24-bit, an icon is a single glyph, a container lock mode is one of the three
defined values. A mistyped key costs you that value, not the server.

**Two open items.** `container_lock_mode` defaults to `PARTY_ONLY`, the buff's stated intent, but the spec only
ever lists the other two options — if the intent was literally "lock for yourself", the default should be
`PRIVATE`. And `builder.reach_bonus_*_blocks` is the spec's `Thành thạo` rule, which Phase 3 flags for a
designer decision: a reach increase requires an interaction-distance hook that vanilla does not expose, so it is
stored and not yet applied.

These keys are not yet editable from `/eco settings`; they are file-only for now.

### `webhook.json`

| Key                  | Default | Description                                            |
|----------------------|---------|--------------------------------------------------------|
| `webhook_enabled`    | `false` | Post transactions to `webhook_url`.                    |
| `webhook_url`        | `""`    | Discord-compatible incoming webhook URL.               |
| `webhook_min_amount` | `0`     | Skip webhook posts for transactions smaller than this. |

### `prices.json`

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

## Placeholders

Exposes economy data to other mods via [Text Placeholder API](https://modrinth.com/mod/placeholder-api) (Fabric) or [Placeholder API NeoForge](https://modrinth.com/mod/placeholder-api-neoforge) (NeoForge). Both are optional, the mod works without them, but the matching jar must be in `mods/` for placeholders to resolve.

| Placeholder                                   | Description                                                              |
|-----------------------------------------------|--------------------------------------------------------------------------|
| `%economycraft:balance%`                      | Raw balance, e.g. `1000`.                                                |
| `%economycraft:balance_formatted%`            | Formatted balance, e.g. `$1.000`.                                        |
| `%economycraft:balance_short%`                | Abbreviated balance, e.g. `$1.2k`.                                       |
| `%economycraft:daily_sell_remaining%`         | How much the player can still earn from selling today. `∞` if unlimited. |
| `%economycraft:top_name <rank>%`              | Name of the player at that rank (`1` = richest).                         |
| `%economycraft:top_balance <rank>%`           | Raw balance at that rank.                                                |
| `%economycraft:top_balance_formatted <rank>%` | Formatted balance at that rank.                                          |
| `%economycraft:top_balance_short <rank>%`     | Abbreviated balance at that rank.                                        |

Ranks beyond the number of players resolve as invalid.

---

## Transaction logs and webhook

Every balance change is logged to `logs/transactions-YYYY-MM-DD.log` inside the config folder, and kept for `transaction_log_retention_days` days (default `7`). Setting it above 90 logs a console warning on start.

Enable `webhook_enabled` in `webhook.json` to also POST each transaction to a Discord-compatible webhook; use `webhook_min_amount` to only notify on larger transactions.

---

## Developer API

The normal EconomyCraft jar includes API v1 for other server-side mods, no separate runtime API mod to install. Covers balances and payments, money formatting, read-only item prices, leaderboard data and balance-change events. Public classes are under `com.reazip.economycraft.api.v1`.

See the [Developer API wiki](https://github.com/PhilipB06/EconomyCraft/wiki) for setup, examples and the complete reference.

---
