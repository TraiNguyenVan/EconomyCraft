# EconomyCraft

A server-side economy for Fabric and NeoForge.
Requires Architectury API.

> **Note:** This repository is an enhanced fork of [PhilipB06/EconomyCraft](https://github.com/PhilipB06/EconomyCraft), adding dynamic pricing, LLM-powered private villager dialogue, tolls, player order books, and faction economic integrations.

## Supported versions

Pick the jar that matches your server's Minecraft version. Every target ships for both loaders.

| Minecraft | Java | Fabric | NeoForge |
|---|---|---|---|
| 1.21.1 | 21 | yes | yes |
| 1.21.11 | 21 | yes | yes |
| 26.1.2 | 25 | yes | yes |
| 26.2 | 25 | yes | yes |
| 26.3 | 25 | yes | yes |

The 1.21.x line needs a Java 21 server; the 26.x line needs Java 25. This is a server-side mod — no client
mod is needed and the jar works with an unmodified vanilla client.

Land-claim-dependent faction features need [ShopGuard](https://github.com/andrewwwwwwwwwwwwwww/shopguard),
which is Fabric-only. Without it those features are inert and everything else works normally.

---

## The `/eco` menu

| Button          | Description                                                                                                              |
|-----------------|--------------------------------------------------------------------------------------------------------------------------|
| **Shop**        | Buy and sell at fixed prices with unlimited stock. Left click buys, right click sells, shift-click uses the bulk amount.  |
| **Auction House** | Buy items other players have listed, or list your own. Offline sellers receive a sale notice when they return.           |
| **Sell Items**  | Drop items in, check the total, confirm. Unpriced items can not be sold.                                                 |
| **Orders**      | Request an item, amount and price. Other players fill it and get paid.                                                   |
| **Contracts**   | Post work for others, or accept theirs and get paid.                                                                    |
| **Daily Reward** | Claims the daily payout, once per day.                                                                                   |
| **Pay a Player** | Send money to another player.                                                                                            |
| **Leaderboards** | Top Balances, Earners, Spenders, Sellers, Buyers and Traders.                                                            |
| **Item Value**  | The buy and sell price of any item.                                                                                      |
| **Deliveries**  | Items or payouts that couldn't be delivered directly (full inventory/completed while offline).                           |
| **Transactions** | Your recent balance history.                                                                                            |
| **Price offers** | Non-binding offers other players left on your listings and requests, and the ones you made. See [price offers](#price-offers). |
| **Tolls**       | Manage the block you are looking at within five blocks; its fee appears on the action bar. See [toll management](https://github.com/TraiNguyenVan/EconomyCraft/wiki/Tolls). |
| **Tags**        | Choose or change your party and profession. See [Party and profession tags](#party-and-profession-tags).                 |
| **How It Works** | A short in-game primer: claim your daily reward, sell what you mine, then buy what you need.                             |
| **Admin**       | Admin only. The Shop editor, Settings, Players, Server Quests, Reload and Reset Tools. See [The Admin menu](#the-admin-eco-admin-menu). |

Your balance is shown at the top-left of the same menu.

Each screen also has a command: `/bal`, `/pay`, `/daily`, `/shop`, `/ah`, `/auction`, `/sell`, `/worth`, `/orders`, `/contracts`, `/deliveries`, `/transactions`, `/offers`, `/toll`, `/tag`, `/job`, `/party`. Every one of them also works as `/eco <command>`, and the short forms exist only while `standalone_commands` is on.

`/worth` takes an item and an optional amount: `/worth minecraft:diamond 8`.

### Price offers

An offer is a price you suggest instead of the seller's asking price. It is **non-binding**: only the
owner can accept it, and anyone can still buy or fulfil at the posted price first. Offers on
server-funded bounties and quest buy-back listings are refused — those prices are policy and the bot
never reads messages.

- `/eco offers` (or `/offers`) opens the hub: offers waiting on your items first, then your own open
  offers. Incoming rows open the review screen for that listing or request; your own rows offer
  **Withdraw**.
- `/eco offers ah <id>` and `/eco offers order <id>` open one target's offers directly. The
  **Review it now** link in an offer message runs exactly that, so a click lands on the offers it is
  about, and the login prompt for offers that arrived while you were offline points at the hub.
- The owner is told about every new offer, acceptance, decline, reprice and withdrawal.

### Contracts

A contract is paid work between two players: the requester reserves the full reward in escrow up
front, one contractor accepts, submits the work, and the requester approves to release the payment.
Contracts can be public (anyone may accept) or targeted at one player.

- `/contracts` (or `/eco contracts`) opens the board; `/contracts mine` lists what you posted and
  accepted; `/contracts new` starts the guided creation flow. The same steps work headlessly:
  `view`, `accept`, `submit`, `approve`, `revise`, `cancel` and `dispute` each take an id.
- Cancelling an open contract refunds the escrow at once. After acceptance both sides must agree.
  Missed deadlines expire the contract and refund the requester; an unreviewed submission
  auto-approves after `contract_review_hours`.
- Either side can dispute submitted work (or work out of revisions). Disputes freeze the contract;
  an admin resolves with `/contracts admin resolve <id> pay|refund`, or lists disputes with
  `/contracts admin list [status]`. Failed payouts and refunds stay pending and retry automatically.

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

Most of the top-level `config.json` keys, editable in-game: starting balance, daily reward, sell limit, tax
rate, number separator, the feature toggles, both dynamic-pricing bounds and the active-player window, both
expiration windows, the per-player order/auction caps, and the whole daily wealth tax block.

The `factions`, `professions`, `quests`, `gemini_gossip` and `motd` sections are **file-only**. Edit them in
`config.json` and press **Reload from disk**. Every key and its shipped default is in
[`config.json`](#configjson).

The login MOTD uses ordered `motd.blocks`. `motd.delay_ticks` delays the first block after joining; each
block's `next_delay_seconds` delays only its successor, and the sequence ends after the final block. A zero
second wait sends the next block on the following server tick. Waits are clamped to 0-3600 seconds. Existing
`motd.lines` arrays are migrated to a single block on load; if both keys are present, `blocks` wins. `/eco motd`
previews all blocks immediately with separators, without waiting. Reloading cancels any in-progress login sequence.

### Server Quests

The server-funded bounty board, behind the same node as Settings. It switches the board and the buyback market
on and off, toggles the shop-only pool, sets the order fraction, the listing fraction and the period budget,
sets the period length, and — behind the Reset Tools node — forces a re-draw. Every other `quests` key is
file-only. All of them are in [`config.json`](#configjson).

Force Re-draw cancels every open quest order and posts a fresh board immediately. Escrow is refunded, but the
mint cap stays spent.

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
| **Run Faction Daily Tax**   | Applies the Capitalism and Monarchy daily taxes immediately.                                |
| **Reset Entire Economy**    | All of the above, plus wipes the Leaderboards stats and deletes the entire transaction log. |

None of these touch shop prices/categories or permission settings. **Run Wealth Tax Now** and **Run Faction
Daily Tax** are the two tools that move real balances as a side effect; the first does nothing while
`wealth_tax_enabled` is off, and the second does nothing while `factions.enabled` is off or a player has no
party.

### Admin commands

Every screen above also has a command. The player-facing ones are on the main menu; the administrative ones are:

| Command | Does |
|---|---|
| `/eco admin` | Opens the Admin menu. |
| `/eco reload` | Re-reads `config.json` and `prices.json` from disk. |
| `/eco motd` | Immediately previews every configured login MOTD block, separated for clarity. |
| `/eco import` | Moves balances, listings and prices from an older shared folder into this world. |
| `/eco gossip status` | Whether the dialogue provider is reachable and whether its circuit breaker is open. |
| `/eco gossip dialogue [prof]` | Sends one line of villager dialogue, optionally for a profession. |
| `/eco gossip reload` | Re-reads the gossip configuration. |
| `/eco toll` | Toll management: `create`, `set`, `transfer`, `info`, `remove`. |
| `/eco tag <player>` | Shows a player's party and profession, read-only. |
| `/eco job` | Opens your profession selector. |
| `/eco party` | Opens your party selector. |
| `/eco offers` | Opens the price-offer hub. `ah <id>` and `order <id>` open one listing. |
| `/eco addmoney` | Gives a player money. |
| `/eco setmoney` | Sets a player's balance. |
| `/eco removemoney` | Takes money from a player. |
| `/eco removeplayer` | Removes a player from the economy. |

`/eco import` is only registered when short commands are on, and only appears if there is an older shared
folder to migrate from. `/addmoney`, `/setmoney`, `/removemoney`, `/removeplayer` and `/gossip` also work
without the `/eco` prefix, but only while `standalone_admin_commands` is on, which is off by default.

---

## Permissions

Admin and command access is gated by permission nodes. Any admin node not set by a permission plugin falls back to OP; any command node not set falls back to allowed for everyone.

### Admin nodes

| Node                          | Grants                                                                                        |
|-------------------------------|-----------------------------------------------------------------------------------------------|
| `economycraft.admin`          | Everything below                                                                              |
| `economycraft.admin.contracts`  | `/contracts admin list`, `/contracts admin resolve`                                           |
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
| `economycraft.command.contracts`     | `/contracts`        |
| `economycraft.command.deliveries`   | `/deliveries`       |
| `economycraft.command.daily`        | `/daily`            |
| `economycraft.command.transactions` | `/transactions`     |
| `economycraft.command.worth`        | `/worth`            |
| `economycraft.command.toll`         | `/eco toll`, `/toll`, and the Tolls menu |
| `economycraft.command.tag`          | `/eco tag`, `/eco job`, `/eco party`, `/tag`, and the hub Tags button |
| `economycraft.command.offers`       | `/eco offers`, `/offers`      |

A hub button is hidden entirely when the player lacks its node, so denying `economycraft.command.tag` removes
the Tags button rather than greying it out.

---

## Config files

Stored in `config/economycraft/` on a server (or `saves/<world>/economycraft/` per-world in singleplayer):
`config.json`, `webhook.json` and `prices.json` at the top, transaction logs under `logs/`, and player and
world state under `data/`.

### Data files

The mod writes these under `data/`. They are written only when something changes, so an untouched file does not
appear until first use.

| File | Holds |
|---|---|
| `balances.json` | Every player's balance. |
| `daily.json` | When each player last claimed the daily reward. |
| `daily_sells.json` | What each player has earned from selling today. |
| `stats.json` | Leaderboard totals: earned, spent and the per-category counters. |
| `player_names.json` | UUID to name, so offline players still display correctly. |
| `player_activity.json` | Last-seen time per player, for the active-player median behind dynamic pricing. |
| `notifications.json` | Queued messages for players who were offline. |
| `deliveries.json` | Items and payouts that could not be delivered directly. |
| `auctions.json` | Live auction listings. |
| `orders.json` | Live order requests and their escrow. |
| `contracts.json` | Live contracts, their escrow and any pending settlement. |
| `shop.json` | Legacy delivery storage, migrated into `deliveries.json` on first start. |
| `negotiations.json` | Open price offers on listings and requests. |
| `tolls.json` | Placed tolls: position, fee and owner. |
| `quests.json` | The quest board's current period and open quests. |
| `stock.json` | Stock the quest bot has banked, awaiting buyback. |
| `fiscal.json` | Daily wealth tax state: last run and per-player carry. |
| `faction_fiscal.json` | Daily Capitalism and Monarchy tax state. |
| `online_time.json` | Accumulated online minutes, for the levy and rust timers. |
| `cooldowns.json` | Wall-clock cooldowns. |
| `parties.json` | Party choices and their lockouts. |
| `professions.json` | Profession, level and rust state. |
| `villagers.db` | Generated villagers, their names and traits, and their memories of your visits and trades. |

### Import and export

`/eco import` moves a **subset** of these between a singleplayer world and a server: `balances.json`,
`daily.json`, `daily_sells.json`, `deliveries.json`, `auctions.json`, `shop.json`, `orders.json`,
`notifications.json`, `player_activity.json` and `player_names.json` — the economy itself.

The rest are deliberately excluded, because they are per-player progression rather than economy, and importing
them would hand every player a fresh party and profession on migration. That covers `online_time.json`,
`cooldowns.json`, `parties.json` and `professions.json`, along with the quest, toll and fiscal state.
Copy those by hand if you mean to move them; deleting the shared copy also destroys the source server's tag
state.

### `config.json`

Every shipped key, with its shipped default. Keys are `snake_case` except the top level, which is `camelCase`
(`startingBalance`, `dailyAmount`). Every default below is clamped rather than rejected, with the value and the
bound in one warning line: a rate is a 0–1 decimal factor, a colour is 24-bit and an icon is a single glyph. A
mistyped key costs you that value, not the server.

Keys marked **file-only** are not editable from `/eco admin` → Settings; change them in the file and use
**Reload from disk**.

<!-- generated from config.json; descriptions hand-written, defaults read from the file -->

| Key | Default | Description |
|---|---|---|
| `auction_enabled` | `true` | Enable the auction house. |
| `auction_expiration_hours` | `168` | Hours before an unsold auction expires and its item goes to deliveries. `0` disables expiration. |
| `balance_separator` | `"."` | Thousands separator, e.g. `","` gives `$1,000`. |
| `contract_default_duration_hours` | `168` | Default work time offered for a new contract (7 days). |
| `contract_max_duration_hours` | `720` | Longest work time a contract may set (30 days). Raised to match the default if lower. |
| `contract_max_revisions` | `2` | Times the requester can send work back before disputes unlock. `0` disables revisions. |
| `contract_revision_extension_hours` | `72` | Extra work time granted per revision. `0` keeps the original deadline. |
| `contract_review_hours` | `72` | Hours the requester has to review a submission before it auto-approves. `0` disables auto-approval. |
| `contracts_enabled` | `true` | Enable player-to-player contracts. |
| `dailyAmount` | `100` | Money given by the daily reward. |
| `dailySellLimit` | `10000` | Most a player can earn per day from selling. `0` disables the limit. |
| `dynamic_price_max_multiplier` | `5.0` | Highest allowed price scale. |
| `dynamic_price_min_active_days` | `30` | Players must have logged in within this many days to count as active. `0` includes everyone. |
| `dynamic_price_min_multiplier` | `0.5` | Lowest allowed price scale. |
| `dynamic_prices_enabled` | `false` | **Disabled by default.** Scale shop buy prices with the active-player median balance. See [Dynamic shop pricing](#dynamic-shop-pricing). |
| `factions.anarchism.color` | `11184810` | Anarchism tag colour, as a 24-bit RGB integer. |
| `factions.anarchism.icon` | `"Ⓐ"` | Anarchism tag icon, one glyph. |
| `factions.anarchism.unclaimed_horse_speed_multiplier` | `1.15` | Horse riding speed on unclaimed land. Horses ignore movement speed, so this needs its own hook. |
| `factions.anarchism.unclaimed_speed_multiplier` | `1.15` | `Thoải mái` movement speed on unclaimed land (`1.15` = +15%). Needs ShopGuard. |
| `factions.capitalism.color` | `5635925` | Capitalism tag colour, as a 24-bit RGB integer. |
| `factions.capitalism.concentration_elasticity` | `1.0` | The single difficulty dial; above `1` punishes concentration harder. |
| `factions.capitalism.concentration_max_multiplier` | `5.0` | Upper bound on the concentration multiplier. |
| `factions.capitalism.concentration_min_multiplier` | `0.0` | Lower bound on the concentration multiplier. |
| `factions.capitalism.concentration_reference_share` | `0.15` | Party wealth share at which the multiplier is exactly `1.0`. |
| `factions.capitalism.daily_tax_rate` | `0.025` | Base daily rate, before the concentration multiplier (`0.025` = 2.5%). |
| `factions.capitalism.icon` | `"$"` | Capitalism tag icon, one glyph. |
| `factions.capitalism.max_rate_change_per_day` | `0.25` | How fast the daily rate may move, as a fraction per day. Caps griefing the rate up and down. |
| `factions.capitalism.toll_tax_multiplier` | `1.25` | Toll tax amount multiplier (`1.25` = +25%). |
| `factions.capitalism.use_global_inflation` | `true` | Read the server-wide inflation signal instead of a fourth independent measure. |
| `factions.communism.color` | `16733525` | Communism tag colour, as a 24-bit RGB integer. |
| `factions.communism.icon` | `"☭"` | Communism tag icon, one glyph. |
| `factions.communism.income_tax_tier1_rate` | `0.0025` | Tier 1 rate (`0.0025` = 0.25% of the balance). |
| `factions.communism.income_tax_tier1_threshold` | `10000` | Balance above which income tax tier 1 applies. |
| `factions.communism.income_tax_tier2_rate` | `0.00375` | Tier 2 rate (`0.00375` = 0.375%). |
| `factions.communism.income_tax_tier2_threshold` | `15000` | Balance above which income tax tier 2 applies. |
| `factions.communism.income_tax_tier3_rate` | `0.00625` | Tier 3 rate (`0.00625` = 0.625%). The highest matching tier wins. |
| `factions.communism.income_tax_tier3_threshold` | `22000` | Balance above which income tax tier 3 applies. |
| `factions.communism.party_fee` | `10` | `Đảng phí`, charged per interval. Burned — there is no recipient. |
| `factions.communism.toll_tax_exempt_chance` | `0.5` | `Đầu tư công` — chance a toll is paid without tax. The toll owner is still paid. |
| `factions.daily_tax_max_catchup_days` | `7` | Most offline days the daily faction tax catches up on. `0` = unlimited. |
| `factions.enabled` | `true` | Master switch for the party system. |
| `factions.levy_interval_minutes` | `45` | Accumulated online time between party fees and income tax. |
| `factions.monarchy.claim_cost_multiplier` | `0.5` | `Tự trị` — halved claim cost (`0.5` = -50%). Needs ShopGuard. |
| `factions.monarchy.color` | `16777045` | Monarchy tag colour, as a 24-bit RGB integer. |
| `factions.monarchy.corruption_multiplier` | `1.0` | `Cống nạp`, as a multiple of the daily tax. Burned. |
| `factions.monarchy.daily_tax_rate` | `0.01` | Base daily rate (`0.01` = 1%). Monarchy taxes well below Capitalism. |
| `factions.monarchy.icon` | `"♔"` | Monarchy tag icon, one glyph. |
| `factions.monarchy.import_tax_chance` | `0.5` | `Nhập khẩu` — chance the import tax applies. |
| `factions.monarchy.import_tax_factor` | `0.5` | `Nhập khẩu` — surcharge applied when it does (`0.5` = +50%). |
| `factions.monarchy.money_supply_inflation_max` | `3.0` | Ceiling on that factor, so one rich player cannot push the whole server's tax up without limit. |
| `factions.monarchy.money_supply_reference_per_player` | `1000.0` | Money per active player at which Monarchy's inflation factor is exactly `1.0`. |
| `factions.monarchy.own_claim_damage_multiplier` | `1.15` | `Phép vua thua lẹ làng` — damage dealt and resistance inside your own claim. Needs ShopGuard. |
| `factions.selection_lockout_hours` | `30` | How long a party choice holds. Independent of the profession lockout. `0` = off. |
| `gemini_gossip.anonymize_players` | `true` | Replace real player names with archetypes before anything is sent to the provider. |
| `gemini_gossip.api_key` | `""` | API key for the provider. **Empty by default; villagers stay silent until this is set.** |
| `gemini_gossip.base_url` | `"https://generativelanguage.googleapis.com"` | Provider base URL. |
| `gemini_gossip.cooldown_minutes` | `3` | Minimum gap between two generations. Clamped to 1-60. |
| `gemini_gossip.dialogue_system_instruction` | `"Dialogue Instructions:
1. Keep it short and easy to understand: most lines should be under 15 words. Avoid overly complex prose or purple vocabulary.
2. Speak in exactly 1 concise, conversational sentence matching your personality, quirk, and relationship with this player.
3. Address the player or your past memories directly when appropriate.
4. Villagers have quirky mannerisms: occasionally mutter or hum ('Hmm...', 'Huh?', 'Haah...'), but vary how you speak and DO NOT start every line with 'Hrmm...'.
5. Respond strictly with valid JSON with fields:
   {
     "dialogue": "<your concise line>",
     "sentiment_delta": <-2 to 5 integer>
   }"` | Prompt telling the model to write one villager's line. Edit to change the tone. |
| `gemini_gossip.enabled` | `true` | Master switch for LLM villager dialogue. Has no effect without `api_key`. |
| `gemini_gossip.model` | `"gemini-3.8-flash"` | Model identifier. Shipped as `gemini-3.8-flash`. |
| `gemini_gossip.private_chat_chance` | `0.5` | Chance that a villager says something when you open its trade interface. The line is sent **only to you**. Clamped to 0.0-1.0. |
| `gemini_gossip.temperature` | `0.85` | Sampling temperature. Clamped to 0.0-2.0. |
| `max_active_auctions_per_player` | `0` | Most active auctions a player can have at once. `0` = unlimited. Overridable per player. |
| `max_active_contracts_per_player` | `0` | Most live contracts a player can be part of at once. `0` = unlimited. Overridable per player. |
| `max_active_orders_per_player` | `0` | Most open orders a player can have at once. `0` = unlimited. Overridable per player. |
| `max_active_tolls_per_player` | `10` | Most tolls a player can have placed at once. `0` = unlimited. |
| `max_contract_reward` | `1000000` | Highest reward a contract may offer. Clamped to 1–99,999,999. |
| `motd.delay_ticks` | `40` | Ticks after joining before the message appears (`40` = 2 seconds). Clamped to 0-1200. |
| `motd.enabled` | `true` | Send the join message on login. |
| `motd.blocks` | One block | Ordered message blocks. Each has `lines` and `next_delay_seconds` (default `30`, clamped to 0-3600); the final block's delay is unused. Legacy `motd.lines` is migrated to one block. |
| `order_expiration_hours` | `168` | Hours before an unfulfilled order expires and its escrow is refunded. `0` disables expiration. |
| `orders_enabled` | `true` | Enable the orders board. Deliveries still work either way. |
| `professions.builder.building_blocks` | `33 entries` | The building blocks a Builder counts towards level, and the base of the Haste trigger set. Tags and ids. |
| `professions.builder.color` | `16755200` | Apprentice Builder tag colour, as a 24-bit RGB integer. |
| `professions.builder.haste_level` | `1` | `Sửa lỗi` Haste level. Applied only while breaking a trigger block. |
| `professions.builder.haste_refresh_seconds` | `1` | Anti-flicker window between two breaking packets. **Not** how long the effect can linger. |
| `professions.builder.haste_trigger_blocks` | `3 entries` | Blocks that count for `Sửa lỗi`. Unioned with `building_blocks`. |
| `professions.builder.icon` | `"⚒"` | Builder tag icon, one glyph. |
| `professions.builder.level_up_count` | `1000` | Building blocks placed to reach Master. |
| `professions.builder.mastered_color` | `16766720` | Master Builder tag colour, as a 24-bit RGB integer. |
| `professions.builder.reach_bonus_apprentice_blocks` | `1.0` | `Thành thạo` added to vanilla's `player.block_interaction_range` (4.5), at Apprentice. |
| `professions.builder.reach_bonus_master_blocks` | `2.0` | `Thành thạo` added to vanilla's `player.block_interaction_range` (4.5), at Master. |
| `professions.enabled` | `true` | Master switch for the profession system. |
| `professions.farmer.baby_growth_factor_apprentice` | `1.15` | `Chăm sóc` offspring growth multiplier at Apprentice. |
| `professions.farmer.baby_growth_factor_master` | `1.3` | `Chăm sóc` offspring growth multiplier at Master. |
| `professions.farmer.bonus_output_chance_apprentice` | `0.01` | `Khéo léo` chance of bonus output when crafting or cooking, at Apprentice. |
| `professions.farmer.bonus_output_chance_master` | `0.05` | `Khéo léo` chance of bonus output when crafting or cooking, at Master. |
| `professions.farmer.breeding_cooldown_factor_apprentice` | `0.1` | `Chăm sóc` breeding cooldown multiplier at Apprentice. |
| `professions.farmer.breeding_cooldown_factor_master` | `0.2` | `Chăm sóc` breeding cooldown multiplier at Master. |
| `professions.farmer.color` | `5627221` | Apprentice Farmer tag colour, as a 24-bit RGB integer. |
| `professions.farmer.crop_boost_chance_apprentice` | `0.1` | `Tươi tốt` growth pulse chance at Apprentice. |
| `professions.farmer.crop_boost_chance_master` | `0.2` | `Tươi tốt` growth pulse chance at Master. |
| `professions.farmer.crop_boost_cooldown_minutes` | `4` | `Tươi tốt` cooldown. |
| `professions.farmer.crop_boost_radius_blocks` | `24` | `Tươi tốt` scan radius. |
| `professions.farmer.icon` | `"☘"` | Farmer tag icon, one glyph. |
| `professions.farmer.level_up_count` | `300` | Crops harvested, animals fed and bred, to reach Master. |
| `professions.farmer.mastered_color` | `16766720` | Master Farmer tag colour, as a 24-bit RGB integer. |
| `professions.merchant.auction_purchase_count` | `5` | Purchases from `/ah` needed to reach Master. A second, separate counter. |
| `professions.merchant.color` | `16777045` | Apprentice Merchant tag colour, as a 24-bit RGB integer. |
| `professions.merchant.cost_factor_apprentice` | `0.05` | `Lưỡi không xương` villager trade price multiplier at Apprentice. |
| `professions.merchant.cost_factor_master` | `0.15` | `Lưỡi không xương` villager trade price multiplier at Master. |
| `professions.merchant.icon` | `"⚖"` | Merchant tag icon, one glyph. |
| `professions.merchant.mastered_color` | `16766720` | Master Merchant tag colour, as a 24-bit RGB integer. |
| `professions.merchant.max_trades_per_villager` | `20` | Per-villager cap on that count. |
| `professions.merchant.villager_trade_count` | `50` | Villager trades needed to reach Master. Trades made with a stick do not count. |
| `professions.miner.color` | `8952319` | Apprentice Miner tag colour, as a 24-bit RGB integer. |
| `professions.miner.double_drop_chance_apprentice` | `0.05` | `Khéo tay` double-drop chance at Apprentice. |
| `professions.miner.double_drop_chance_master` | `0.15` | `Khéo tay` double-drop chance at Master. |
| `professions.miner.double_value_ores` | `5 entries` | Ores that count as 2 towards level: diamond and gold. |
| `professions.miner.haste_level` | `2` | `Lanh lợi` Haste level. Applied only while breaking an ore or stone-type block. |
| `professions.miner.haste_refresh_seconds` | `1` | Anti-flicker window between two mining packets. **Not** a duration. |
| `professions.miner.haste_trigger_blocks` | `5 entries` | Blocks that count for `Lanh lợi`. |
| `professions.miner.icon` | `"⛏"` | Miner tag icon, one glyph. |
| `professions.miner.lava_cooldown_minutes` | `5` | Cooldown before that Regeneration can trigger again. |
| `professions.miner.lava_regeneration_level` | `2` | `Bảo hộ lao động` Regeneration level granted on touching lava. |
| `professions.miner.lava_regeneration_seconds` | `4` | How long that Regeneration lasts. |
| `professions.miner.level_up_count` | `270` | Ores mined to reach Master. |
| `professions.miner.mastered_color` | `16766720` | Master Miner tag colour, as a 24-bit RGB integer. |
| `professions.miner.ore_tags` | `1 entries` | The ore set, as a tag so modded ores count. |
| `professions.rust_effect_factor` | `0.5` | What "half effect" means, as a multiplier on the job's numbers. |
| `professions.rust_online_minutes` | `45` | `Lụt nghề` — online minutes at half effect after returning. |
| `professions.selection_lockout_hours` | `30` | How long a profession choice holds. `0` = off. |
| `professions.soldier.adrenaline_cooldown_minutes` | `5` | `Adrenaline` cooldown. It triggers automatically, never manually. |
| `professions.soldier.adrenaline_duration_factor` | `0.5` | The halving itself. |
| `professions.soldier.adrenaline_window_seconds` | `4` | `Adrenaline` halves negative effects only within this of the first debuff. |
| `professions.soldier.color` | `16733525` | Apprentice Soldier tag colour, as a 24-bit RGB integer. |
| `professions.soldier.damage_dealt_factor_apprentice` | `1.05` | `Sắt được tôi` damage dealt multiplier at Apprentice. |
| `professions.soldier.damage_dealt_factor_master` | `1.15` | `Sắt được tôi` damage dealt multiplier at Master. |
| `professions.soldier.damage_taken_factor_apprentice` | `0.95` | `Sắt được tôi` damage taken multiplier at Apprentice. |
| `professions.soldier.damage_taken_factor_master` | `0.85` | `Sắt được tôi` damage taken multiplier at Master. |
| `professions.soldier.icon` | `"⚔"` | Soldier tag icon, one glyph. |
| `professions.soldier.kill_count` | `100` | Kills needed to reach Master. |
| `professions.soldier.mastered_color` | `16766720` | Master Soldier tag colour, as a 24-bit RGB integer. |
| `quests.blacklist` | `[]` | Price keys never drawable, even inside the unit window. |
| `quests.bot_name` | `"Server Quests"` | Display name the reserved bot account resolves to in order lists and lore. |
| `quests.buyback.enabled` | `true` | Resell accumulated quest stock through ordinary `/ah` listings owned by the bot. |
| `quests.buyback.price_factor` | `0.8` | Fraction of the effective buy unit the buyback charges. Below `1` sells stock back discounted. |
| `quests.enabled` | `true` | Master switch for the server-funded quest board. |
| `quests.max_concurrent` | `10` | Safety ceiling on simultaneously open quest orders. |
| `quests.max_quest_unit` | `100` | Highest quest-unit price an entry may be drawn at. Kills quests that would pay a fortune for one item. |
| `quests.min_quest_unit` | `3` | Lowest quest-unit price an entry may be drawn at. Kills zero-value junk. |
| `quests.period_days` | `7` | Days in one quest period, counted from the last rollover rather than a calendar boundary. |
| `quests.price_factor` | `0.5` | Fraction of the effective buy unit a quest pays per item. |
| `quests.require_shop_price` | `true` | Only entries with a buy price are drawn. Sell-only catalog entries never reach the board or `/ah`. |
| `quests.sell_fallback_multiplier` | `3.3` | Multiplier on `unit_sell` for entries with no buy price. |
| `quests.weekly_budget` | `12000` | Most the bot may mint per period. Posting stops when the next quest would exceed it; `0` posts nothing. |
| `quests.weekly_count` | `10` | How many distinct items the periodic draw picks. |
| `scoreboard_enabled` | `true` | Show the balance sidebar. |
| `sell_enabled` | `true` | Enable selling. |
| `shop_enabled` | `true` | Enable the fixed-price shop. |
| `standalone_admin_commands` | `false` | **Disabled by default.** Allow `/addmoney`, `/setmoney` etc. without the `/eco` prefix. |
| `standalone_commands` | `true` | Allow `/pay`, `/daily` etc. without the `/eco` prefix. |
| `startingBalance` | `1000` | Money new players start with. |
| `taxRate` | `0.1` | Tax on trades and orders, as a decimal (`0.1` = 10%). |
| `transaction_log_enabled` | `true` | Record every balance change to a daily log file. |
| `transaction_log_retention_days` | `7` | How many days of transaction logs to keep. Above `90` logs a console warning on start. |
| `wealth_tax_enabled` | `false` | **Disabled by default.** Apply the daily wealth tax. See [Daily wealth tax](#daily-wealth-tax). |
| `wealth_tax_floor` | `1000` | Balances at or below this are never taxed. Also the rebate target. |
| `wealth_tax_inactive_days` | `7` | Days before an unseen player is charged the idle rate. `0` = one rate for everyone. |
| `wealth_tax_inactive_multiplier` | `3.33` | Rate multiplier for players past the inactive window. |
| `wealth_tax_max_catchup_days` | `7` | Most days applied after downtime. `0` = unlimited. |
| `wealth_tax_median_floor_factor` | `0.5` | How far the floor follows the active median. `0` keeps it fixed, `1` makes it equal the median. |
| `wealth_tax_rate` | `0.015` | Daily cut taken from the surplus above the floor, as a decimal. |
| `wealth_tax_rebate_enabled` | `false` | **Disabled by default.** Pay players below the floor when the median has crashed. Creates money. |
| `wealth_tax_rebate_max_rate` | `0.01` | Rebate rate applied to the shortfall below the floor. |
| `wealth_tax_rebate_trigger_factor` | `1.0` | Rebate arms only when the median is below `startingBalance x` this. |
| `worth_enabled` | `true` | Enable item value lookups (`/worth`). |

#### `factions`

Party tuning. The four parties are `communism`, `capitalism`, `monarchy` and `anarchism`.

| Key | Default | Description |
|---|---|---|
| `factions.anarchism.color` | `11184810` | Anarchism tag colour, as a 24-bit RGB integer. |
| `factions.anarchism.icon` | `"Ⓐ"` | Anarchism tag icon, one glyph. |
| `factions.anarchism.unclaimed_horse_speed_multiplier` | `1.15` | Horse riding speed on unclaimed land. Horses ignore movement speed, so this needs its own hook. |
| `factions.anarchism.unclaimed_speed_multiplier` | `1.15` | `Thoải mái` movement speed on unclaimed land (`1.15` = +15%). Needs ShopGuard. |
| `factions.capitalism.color` | `5635925` | Capitalism tag colour, as a 24-bit RGB integer. |
| `factions.capitalism.concentration_elasticity` | `1.0` | The single difficulty dial; above `1` punishes concentration harder. |
| `factions.capitalism.concentration_max_multiplier` | `5.0` | Upper bound on the concentration multiplier. |
| `factions.capitalism.concentration_min_multiplier` | `0.0` | Lower bound on the concentration multiplier. |
| `factions.capitalism.concentration_reference_share` | `0.15` | Party wealth share at which the multiplier is exactly `1.0`. |
| `factions.capitalism.daily_tax_rate` | `0.025` | Base daily rate, before the concentration multiplier (`0.025` = 2.5%). |
| `factions.capitalism.icon` | `"$"` | Capitalism tag icon, one glyph. |
| `factions.capitalism.max_rate_change_per_day` | `0.25` | How fast the daily rate may move, as a fraction per day. Caps griefing the rate up and down. |
| `factions.capitalism.toll_tax_multiplier` | `1.25` | Toll tax amount multiplier (`1.25` = +25%). |
| `factions.capitalism.use_global_inflation` | `true` | Read the server-wide inflation signal instead of a fourth independent measure. |
| `factions.communism.color` | `16733525` | Communism tag colour, as a 24-bit RGB integer. |
| `factions.communism.icon` | `"☭"` | Communism tag icon, one glyph. |
| `factions.communism.income_tax_tier1_rate` | `0.0025` | Tier 1 rate (`0.0025` = 0.25% of the balance). |
| `factions.communism.income_tax_tier1_threshold` | `10000` | Balance above which income tax tier 1 applies. |
| `factions.communism.income_tax_tier2_rate` | `0.00375` | Tier 2 rate (`0.00375` = 0.375%). |
| `factions.communism.income_tax_tier2_threshold` | `15000` | Balance above which income tax tier 2 applies. |
| `factions.communism.income_tax_tier3_rate` | `0.00625` | Tier 3 rate (`0.00625` = 0.625%). The highest matching tier wins. |
| `factions.communism.income_tax_tier3_threshold` | `22000` | Balance above which income tax tier 3 applies. |
| `factions.communism.party_fee` | `10` | `Đảng phí`, charged per interval. Burned — there is no recipient. |
| `factions.communism.toll_tax_exempt_chance` | `0.5` | `Đầu tư công` — chance a toll is paid without tax. The toll owner is still paid. |
| `factions.daily_tax_max_catchup_days` | `7` | Most offline days the daily faction tax catches up on. `0` = unlimited. |
| `factions.enabled` | `true` | Master switch for the party system. |
| `factions.levy_interval_minutes` | `45` | Accumulated online time between party fees and income tax. |
| `factions.monarchy.claim_cost_multiplier` | `0.5` | `Tự trị` — halved claim cost (`0.5` = -50%). Needs ShopGuard. |
| `factions.monarchy.color` | `16777045` | Monarchy tag colour, as a 24-bit RGB integer. |
| `factions.monarchy.corruption_multiplier` | `1.0` | `Cống nạp`, as a multiple of the daily tax. Burned. |
| `factions.monarchy.daily_tax_rate` | `0.01` | Base daily rate (`0.01` = 1%). Monarchy taxes well below Capitalism. |
| `factions.monarchy.icon` | `"♔"` | Monarchy tag icon, one glyph. |
| `factions.monarchy.import_tax_chance` | `0.5` | `Nhập khẩu` — chance the import tax applies. |
| `factions.monarchy.import_tax_factor` | `0.5` | `Nhập khẩu` — surcharge applied when it does (`0.5` = +50%). |
| `factions.monarchy.money_supply_inflation_max` | `3.0` | Ceiling on that factor, so one rich player cannot push the whole server's tax up without limit. |
| `factions.monarchy.money_supply_reference_per_player` | `1000.0` | Money per active player at which Monarchy's inflation factor is exactly `1.0`. |
| `factions.monarchy.own_claim_damage_multiplier` | `1.15` | `Phép vua thua lẹ làng` — damage dealt and resistance inside your own claim. Needs ShopGuard. |
| `factions.selection_lockout_hours` | `30` | How long a party choice holds. Independent of the profession lockout. `0` = off. |

#### `professions`

Profession tuning. The five jobs are `builder`, `farmer`, `miner`, `merchant` and `soldier`.

| Key | Default | Description |
|---|---|---|
| `professions.builder.building_blocks` | `33 entries` | The building blocks a Builder counts towards level, and the base of the Haste trigger set. Tags and ids. |
| `professions.builder.color` | `16755200` | Apprentice Builder tag colour, as a 24-bit RGB integer. |
| `professions.builder.haste_level` | `1` | `Sửa lỗi` Haste level. Applied only while breaking a trigger block. |
| `professions.builder.haste_refresh_seconds` | `1` | Anti-flicker window between two breaking packets. **Not** how long the effect can linger. |
| `professions.builder.haste_trigger_blocks` | `3 entries` | Blocks that count for `Sửa lỗi`. Unioned with `building_blocks`. |
| `professions.builder.icon` | `"⚒"` | Builder tag icon, one glyph. |
| `professions.builder.level_up_count` | `1000` | Building blocks placed to reach Master. |
| `professions.builder.mastered_color` | `16766720` | Master Builder tag colour, as a 24-bit RGB integer. |
| `professions.builder.reach_bonus_apprentice_blocks` | `1.0` | `Thành thạo` added to vanilla's `player.block_interaction_range` (4.5), at Apprentice. |
| `professions.builder.reach_bonus_master_blocks` | `2.0` | `Thành thạo` added to vanilla's `player.block_interaction_range` (4.5), at Master. |
| `professions.enabled` | `true` | Master switch for the profession system. |
| `professions.farmer.baby_growth_factor_apprentice` | `1.15` | `Chăm sóc` offspring growth multiplier at Apprentice. |
| `professions.farmer.baby_growth_factor_master` | `1.3` | `Chăm sóc` offspring growth multiplier at Master. |
| `professions.farmer.bonus_output_chance_apprentice` | `0.01` | `Khéo léo` chance of bonus output when crafting or cooking, at Apprentice. |
| `professions.farmer.bonus_output_chance_master` | `0.05` | `Khéo léo` chance of bonus output when crafting or cooking, at Master. |
| `professions.farmer.breeding_cooldown_factor_apprentice` | `0.1` | `Chăm sóc` breeding cooldown multiplier at Apprentice. |
| `professions.farmer.breeding_cooldown_factor_master` | `0.2` | `Chăm sóc` breeding cooldown multiplier at Master. |
| `professions.farmer.color` | `5627221` | Apprentice Farmer tag colour, as a 24-bit RGB integer. |
| `professions.farmer.crop_boost_chance_apprentice` | `0.1` | `Tươi tốt` growth pulse chance at Apprentice. |
| `professions.farmer.crop_boost_chance_master` | `0.2` | `Tươi tốt` growth pulse chance at Master. |
| `professions.farmer.crop_boost_cooldown_minutes` | `4` | `Tươi tốt` cooldown. |
| `professions.farmer.crop_boost_radius_blocks` | `24` | `Tươi tốt` scan radius. |
| `professions.farmer.icon` | `"☘"` | Farmer tag icon, one glyph. |
| `professions.farmer.level_up_count` | `300` | Crops harvested, animals fed and bred, to reach Master. |
| `professions.farmer.mastered_color` | `16766720` | Master Farmer tag colour, as a 24-bit RGB integer. |
| `professions.merchant.auction_purchase_count` | `5` | Purchases from `/ah` needed to reach Master. A second, separate counter. |
| `professions.merchant.color` | `16777045` | Apprentice Merchant tag colour, as a 24-bit RGB integer. |
| `professions.merchant.cost_factor_apprentice` | `0.05` | `Lưỡi không xương` villager trade price multiplier at Apprentice. |
| `professions.merchant.cost_factor_master` | `0.15` | `Lưỡi không xương` villager trade price multiplier at Master. |
| `professions.merchant.icon` | `"⚖"` | Merchant tag icon, one glyph. |
| `professions.merchant.mastered_color` | `16766720` | Master Merchant tag colour, as a 24-bit RGB integer. |
| `professions.merchant.max_trades_per_villager` | `20` | Per-villager cap on that count. |
| `professions.merchant.villager_trade_count` | `50` | Villager trades needed to reach Master. Trades made with a stick do not count. |
| `professions.miner.color` | `8952319` | Apprentice Miner tag colour, as a 24-bit RGB integer. |
| `professions.miner.double_drop_chance_apprentice` | `0.05` | `Khéo tay` double-drop chance at Apprentice. |
| `professions.miner.double_drop_chance_master` | `0.15` | `Khéo tay` double-drop chance at Master. |
| `professions.miner.double_value_ores` | `5 entries` | Ores that count as 2 towards level: diamond and gold. |
| `professions.miner.haste_level` | `2` | `Lanh lợi` Haste level. Applied only while breaking an ore or stone-type block. |
| `professions.miner.haste_refresh_seconds` | `1` | Anti-flicker window between two mining packets. **Not** a duration. |
| `professions.miner.haste_trigger_blocks` | `5 entries` | Blocks that count for `Lanh lợi`. |
| `professions.miner.icon` | `"⛏"` | Miner tag icon, one glyph. |
| `professions.miner.lava_cooldown_minutes` | `5` | Cooldown before that Regeneration can trigger again. |
| `professions.miner.lava_regeneration_level` | `2` | `Bảo hộ lao động` Regeneration level granted on touching lava. |
| `professions.miner.lava_regeneration_seconds` | `4` | How long that Regeneration lasts. |
| `professions.miner.level_up_count` | `270` | Ores mined to reach Master. |
| `professions.miner.mastered_color` | `16766720` | Master Miner tag colour, as a 24-bit RGB integer. |
| `professions.miner.ore_tags` | `1 entries` | The ore set, as a tag so modded ores count. |
| `professions.rust_effect_factor` | `0.5` | What "half effect" means, as a multiplier on the job's numbers. |
| `professions.rust_online_minutes` | `45` | `Lụt nghề` — online minutes at half effect after returning. |
| `professions.selection_lockout_hours` | `30` | How long a profession choice holds. `0` = off. |
| `professions.soldier.adrenaline_cooldown_minutes` | `5` | `Adrenaline` cooldown. It triggers automatically, never manually. |
| `professions.soldier.adrenaline_duration_factor` | `0.5` | The halving itself. |
| `professions.soldier.adrenaline_window_seconds` | `4` | `Adrenaline` halves negative effects only within this of the first debuff. |
| `professions.soldier.color` | `16733525` | Apprentice Soldier tag colour, as a 24-bit RGB integer. |
| `professions.soldier.damage_dealt_factor_apprentice` | `1.05` | `Sắt được tôi` damage dealt multiplier at Apprentice. |
| `professions.soldier.damage_dealt_factor_master` | `1.15` | `Sắt được tôi` damage dealt multiplier at Master. |
| `professions.soldier.damage_taken_factor_apprentice` | `0.95` | `Sắt được tôi` damage taken multiplier at Apprentice. |
| `professions.soldier.damage_taken_factor_master` | `0.85` | `Sắt được tôi` damage taken multiplier at Master. |
| `professions.soldier.icon` | `"⚔"` | Soldier tag icon, one glyph. |
| `professions.soldier.kill_count` | `100` | Kills needed to reach Master. |
| `professions.soldier.mastered_color` | `16766720` | Master Soldier tag colour, as a 24-bit RGB integer. |

**Haste is conditional, not a potion you carry.** `Sửa lỗi` and `Lanh lợi` apply *only* while the player is
breaking a block their job counts — a Builder gets Haste I on stone, cobblestone, dirt and building blocks; a
Miner gets Haste II on stone, deepslate, tuff, netherack and ores — and it is removed the moment they break
something else. A Builder cannot carry Haste to a chest, and a Miner cannot walk around with it. That is why the
key is `haste_refresh_seconds` and not a duration: `1` second is only the anti-flicker window between two
mining packets, not how long the effect can linger. The Builder's trigger set is the union of
`professions.builder.haste_trigger_blocks` and `professions.builder.building_blocks`, so adding a building block
extends the triggers automatically.

**Builder reach is a real attribute, not a config no-op.** `Thành thạo` adds one `AttributeModifier` to the
vanilla `player.block_interaction_range` (default `4.5`), which is what both the client's block picking and the
server's own range check read — so a Master Builder reaches 6.5 blocks with an unmodified vanilla client, and
no mixin is involved. Note that this is *block interaction* range, not building-only: it also widens opening a
container, reading a sign and clicking an item frame. That is intended (it is plain reach), so it is documented
here rather than left to be discovered.

#### `quests`

The server-funded bounty board. Once a period the server draws a batch of priced items and posts them as buy
orders from a reserved bot account. Fills pay through the ordinary order path, including order tax; the bought
items are diverted into a stock ledger rather than a deliveries mailbox. Money enters only through a capped
weekly mint, and whatever budget is left unspent simply never mints.

| Key | Default | Description |
|---|---|---|
| `quests.blacklist` | `[]` | Price keys never drawable, even inside the unit window. |
| `quests.bot_name` | `"Server Quests"` | Display name the reserved bot account resolves to in order lists and lore. |
| `quests.buyback.enabled` | `true` | Resell accumulated quest stock through ordinary `/ah` listings owned by the bot. |
| `quests.buyback.price_factor` | `0.8` | Fraction of the effective buy unit the buyback charges. Below `1` sells stock back discounted. |
| `quests.enabled` | `true` | Master switch for the server-funded quest board. |
| `quests.max_concurrent` | `10` | Safety ceiling on simultaneously open quest orders. |
| `quests.max_quest_unit` | `100` | Highest quest-unit price an entry may be drawn at. Kills quests that would pay a fortune for one item. |
| `quests.min_quest_unit` | `3` | Lowest quest-unit price an entry may be drawn at. Kills zero-value junk. |
| `quests.period_days` | `7` | Days in one quest period, counted from the last rollover rather than a calendar boundary. |
| `quests.price_factor` | `0.5` | Fraction of the effective buy unit a quest pays per item. |
| `quests.require_shop_price` | `true` | Only entries with a buy price are drawn. Sell-only catalog entries never reach the board or `/ah`. |
| `quests.sell_fallback_multiplier` | `3.3` | Multiplier on `unit_sell` for entries with no buy price. |
| `quests.weekly_budget` | `12000` | Most the bot may mint per period. Posting stops when the next quest would exceed it; `0` posts nothing. |
| `quests.weekly_count` | `10` | How many distinct items the periodic draw picks. |

#### `gemini_gossip`

LLM-written villager dialogue. When you open a villager's trade interface it may say one line to **you and
only you** — villager speech is never broadcast to other players. Each line is built from that villager's
own personality, what it currently sells, and its memory of your visits and trades. **This section sends
economy data to a third-party API.** `gemini_gossip.api_key` ships empty, so nothing is sent and nothing is
generated until you set it.

| Key | Default | Description |
|---|---|---|
| `gemini_gossip.anonymize_players` | `true` | Replace real player names with archetypes before anything is sent to the provider. |
| `gemini_gossip.api_key` | `""` | API key for the provider. **Empty by default; villagers stay silent until this is set.** |
| `gemini_gossip.base_url` | `"https://generativelanguage.googleapis.com"` | Provider base URL. |
| `gemini_gossip.cooldown_minutes` | `3` | Minimum gap between two generations. Clamped to 1-60. |
| `gemini_gossip.dialogue_system_instruction` | `"Dialogue Instructions:
1. Keep it short and easy to understand: most lines should be under 15 words. Avoid overly complex prose or purple vocabulary.
2. Speak in exactly 1 concise, conversational sentence matching your personality, quirk, and relationship with this player.
3. Address the player or your past memories directly when appropriate.
4. Villagers have quirky mannerisms: occasionally mutter or hum ('Hmm...', 'Huh?', 'Haah...'), but vary how you speak and DO NOT start every line with 'Hrmm...'.
5. Respond strictly with valid JSON with fields:
   {
     "dialogue": "<your concise line>",
     "sentiment_delta": <-2 to 5 integer>
   }"` | Prompt telling the model to write one villager's line. Edit to change the tone. |
| `gemini_gossip.enabled` | `true` | Master switch for LLM villager dialogue. Has no effect without `api_key`. |
| `gemini_gossip.model` | `"gemini-3.8-flash"` | Model identifier. Shipped as `gemini-3.8-flash`. |
| `gemini_gossip.private_chat_chance` | `0.5` | Chance that a villager says something when you open its trade interface. The line is sent **only to you**. Clamped to 0.0-1.0. |
| `gemini_gossip.temperature` | `0.85` | Sampling temperature. Clamped to 0.0-2.0. |

#### `motd`

The ordered message blocks sent to a player when they join. Formatting codes, player placeholders and clickable
URLs work in each line. Existing `motd.lines` arrays are automatically migrated to one block before defaults are
merged. If both legacy `lines` and `blocks` are present, `blocks` is used and `lines` is discarded.

| Key | Default | Description |
|---|---|---|
| `motd.delay_ticks` | `40` | Ticks after joining before the message appears (`40` = 2 seconds). Clamped to 0-1200. |
| `motd.enabled` | `true` | Send the join message on login. |
| `motd.blocks` | One block | Ordered message blocks, each with formatted `lines` and a `next_delay_seconds` wait before its successor. The wait defaults to 30 seconds and is clamped to 0-3600; the final block's wait is unused. Existing `motd.lines` is migrated to one block. |

`motd.delay_ticks` applies before block one. Each `next_delay_seconds` applies after its block and before the next;
zero advances on the next server tick, and the last block's value is ignored. `/eco motd` immediately previews all
blocks with separators. `/eco reload` cancels sequences already in progress.

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

## Party and profession tags

Each player can hold one **party** (Communism, Capitalism, Monarchy, Anarchism) and one **profession** (Builder,
Farmer, Miner, Merchant, Soldier). Both are chosen with `/tag` (or `/eco party` / `/eco job`) and both are drawn on a vanilla client, with no
client mod:

| Surface | What it shows | Example |
|---|---|---|
| Tab list (hold <kbd>Tab</kbd>) | the full word, in the party's or profession's colour | `[Communism][Builder] Steve` |
| Above the head | the icons only | `[☭][⚒] Steve` |
| Chat | the icons in front of the message | `<Steve> [☭][⚒] hello` |
| `/tag <player>` | the player's tags, read-only | — |

The icon and colour for each are config keys (`factions.<party>.icon` / `.color`, and the same under
`professions.<profession>`), so a server can restyle them without touching the code. A profession shows its level in
the tab list only once it is `Master` or `Rusted` — `Apprentice` is the default and is not worth interrupting a
name for.

### The online-time convention
Certain faction debuffs (Communism party fee and income tax) and profession mechanics (the 45-minute rust recovery) use **accumulated online time**:
- The timer increments **only while the player is logged in and active on the server**.
- The timer **freezes when the player logs off**.
- The timer only resets once it reaches the configured threshold (e.g. 45 minutes), at which point the corresponding action executes and the timer restarts from zero.

### The 30-hour lockout
- Choosing a party or profession initiates an independent **30-hour real-time lockout** for that category.
- The lockout begins on your very first selection.
- During the lockout, you cannot change or leave your selected party or profession (operators bypass this restriction).

### Factions (Parties)

What each party does. Every rate and threshold is a `factions` key — see
[`config.json`](#configjson) for the shipped defaults, which are stated once there and not repeated here.

1. **Communism (`☭`, Red):**
   - *Public Investment:* `Đầu tư công` — a chance that toll tax is waived. The toll owner still receives their fee.
   - *Party Fee:* `Đảng phí`, a flat charge every `factions.levy_interval_minutes` of accumulated online time. Burned from circulation; there is no recipient.
   - *Income Tax:* Assessed on the same interval, immediately after the party fee. Three tiers apply to the whole balance, and the highest threshold you clear wins.
2. **Capitalism (`$`, Gold):**
   - *Competitive Market:* Purchases from your own `/ah` listings are exempt from transaction tax.
   - *Capitalist State:* A daily wealth tax on the base rate, scaled by server inflation and by how concentrated wealth is in the party. The rate cannot move faster than `factions.capitalism.max_rate_change_per_day` allows, in either direction. Toll tax is multiplied up.
3. **Monarchy (`♔`, Purple):**
   - *Autonomy:* `Tự trị` — land claim costs are halved. Needs ShopGuard.
   - *Royal Prerogative:* `Phép vua thua lẹ lààng` — damage dealt and damage resistance inside your own claim. Needs ShopGuard.
   - *Tribute:* `Cống nạp` — an additional daily corruption levy, a multiple of the Monarchy daily tax rate. Burned.
   - *Import Tax:* `Nhập khẩu` — a chance of a surcharge on transaction taxes when purchasing goods.
   - Monarchy's daily rate scales with total money on the server rather than with concentration.
4. **Anarchism (`Ⓐ`, White/Gray - Default):**
   - *Freedom:* Fully exempt from all sales, purchase, toll and daily taxes.
   - *Unbound:* `Thoải mái` — increased movement and horse riding speed on unclaimed wilderness land. Needs ShopGuard.
   - *No Government:* Cannot create claims, receive claim transfers, or be added to claim trust lists (`/claim trust`).

### Professions

What each profession does. Every goal count, radius and rate is a `professions` key — see
[`config.json`](#configjson) for the shipped defaults, which are stated once there and not repeated here.

- Progression: **Apprentice** (default) &rarr; **Master** (on completing that job's goals).
- Switching professions resets prior progress. Returning to a previously mastered profession inflicts **Rusted** status (effects at `professions.rust_effect_factor`) for `professions.rust_online_minutes` online minutes before Master rank restores.
1. **Builder:** Place building blocks. `Thành thạo` adds block interaction range. `Sửa lỗi` grants Haste while breaking a block the job counts, and only then.
2. **Farmer:** Harvest crops, feed and breed animals. `Tươi tốt` pulses nearby crop growth on a cooldown; `Chăm sóc` shortens breeding cooldowns and speeds offspring growth; `Khéo léo` adds bonus output when crafting or cooking.
3. **Miner:** Mine ores, with diamond and gold counting double. `Lanh lợi` grants Haste while breaking an ore or stone-type block; `Khéo tay` adds a double-drop chance; `Bảo hộ lao động` grants Regeneration on touching lava, after a cooldown.
4. **Merchant:** Trade with villagers and buy from `/ah` — two separate counters. `Lưỡi không xương` reduces the **tax** on those transactions. It does not lower the price you pay.
5. **Soldier:** Kill monsters. `Sắt được tôi` adjusts damage taken and damage dealt; Master rank triggers **Adrenaline** on harmful effects, halving negative effect durations within a short window and then going on cooldown.

### Configuration reference

Every `factions` and `professions` key is documented once, with its shipped default, in
[`config.json`](#configjson) above. The full `config.json` reference — top level, `factions`, `professions`,
`quests`, `gemini_gossip` and `motd` — lives in that single section.

### Limitations
- **Nametag & Scoreboard Teams:** Because nametags are rendered client-side, the icon above a player's head is delivered via scoreboard team prefixes (`PlayerTeam#setPlayerPrefix`). If another plugin manages player teams, EconomyCraft's team assignment may conflict.
- **Chat Signature Badge:** Minecraft client Compose `<Name>` slots strictly from account profiles. To show icons in chat, the server prefixes the message content, marking messages as *server-modified* (`ChatTrustLevel.MODIFIED`).
- **Interaction Reach:** The Builder reach bonus utilizes vanilla's syncable `BLOCK_INTERACTION_RANGE` attribute. Consequently, it consistently widens interaction reach for block placing, breaking, and opening containers without requiring any client mods.
- **Platform Parity & ShopGuard:** ShopGuard is a Fabric-only mod. On NeoForge (or Fabric servers without ShopGuard), land-claim-dependent features (Monarchy's halved claim cost and claim damage bonus, Anarchism's wilderness speed and claim restrictions) degrade gracefully and remain inert. All other faction and profession features function identically across both Fabric and NeoForge.


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

See the [Developer API wiki](https://github.com/TraiNguyenVan/EconomyCraft/wiki/API-Reference) for setup, examples and the complete reference.

---

## Upstream & License

EconomyCraft is licensed under GNU General Public License v3.0 (GPL-3.0).
This repository is an enhanced fork of the original [EconomyCraft by PhilipB06 (ReaZip)](https://github.com/PhilipB06/EconomyCraft).
