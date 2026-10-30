# Server administration

This guide covers the in-game admin menu, server commands, permissions, and optional integrations. For installation requirements, see [Installation and compatibility](Installation-and-Compatibility.md).

## Admin menu

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

When enabled (`dynamic_prices.enabled`), this scales every buy price by:

```
current price = base price × active-player median balance / economy.starting_balance
```

The scale is clamped between `dynamic_prices.min_multiplier` and `dynamic_prices.max_multiplier`, and recalculated at the interval in `dynamic_prices.update_minutes`. Sell prices are never affected. Opt out per category or item from the Shop editor above.

### Daily wealth tax

When enabled (`wealth_tax_enabled`), this applies a daily percentage to each balance's surplus above the floor:

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

The optional rebate (`wealth_tax_rebate_enabled`) pays players below the floor only when the active median falls below `economy.starting_balance x wealth_tax_rebate_trigger_factor`.

Balances at or below the floor are never taxed, and a tax can never push a balance below the floor. These tax and rebate movements do not count as earned or spent money on leaderboards.

### Settings

Most of the grouped `config.json` keys are editable in-game: starting balance, daily reward, sell limit, tax
rate, number separator, the feature toggles, both dynamic-pricing bounds and the active-player window, the
dynamic price update interval, both expiration windows, the per-player order/auction caps, and the whole daily
wealth tax block.

The `factions`, `professions`, `gemini_gossip`, and `motd` sections are **file-only**. Some `quests` settings
are available in the Server Quests screen; the remaining quest settings are file-only. Edit file-only keys in
`config.json` and press **Reload from disk**. The [configuration reference](Server-Configuration.md) lists
every key and shipped default.

The login MOTD uses ordered `motd.blocks`. `motd.delay_ticks` delays the first block; each block's
`next_delay_seconds` delays its successor. `/eco motd` previews all blocks immediately. Reloading cancels an
in-progress login sequence.

### Server Quests

The server-funded bounty board, behind the same node as Settings. It switches the board and the buyback market
on and off, toggles the shop-only pool, sets the order fraction, the listing fraction and the period budget,
sets the period length, and — behind the Reset Tools node — forces a re-draw. Every other `quests` key is
file-only. All of them are in [configuration reference](Server-Configuration.md).

Force Re-draw cancels every open quest order and posts a fresh board immediately. Reserved money returns to the
server bot, and the current period's mint cap stays spent.

### Players

Select any player, online or not, to give, take or set their balance, clear their Leaderboard stats, remove them from the economy, or override their max active orders/auctions.

### Reset Tools


| Tool                        | Does                                                                                        |
|-----------------------------|---------------------------------------------------------------------------------------------|
| **Reset All Balances**      | Sets every player's balance back to `economy.starting_balance`.                            |
| **Reset Daily Reward Data** | Everyone can claim their daily reward again immediately.                                    |
| **Reset Daily Sell Limits** | Everyone's daily sell limit resets to full immediately.                                     |
| **Clear Auctions**          | Cancels every active listing. Items are returned to sellers' deliveries.                    |
| **Clear Orders**            | Cancels every open order. Escrowed money is refunded to requesters.                         |
| **Remove All Items**        | Empties the shop. Removed items can be restored from the shop editor.                       |
| **Reset Shop Prices**       | Restores prices, categories, and removed items to their defaults. Custom items are deleted; a `prices.json` backup is kept beside the file. |
| **Run Wealth Tax Now**      | Applies the daily wealth tax immediately and reports what it took, per day.                |
| **Run Faction Daily Tax**   | Applies the Capitalism and Monarchy daily taxes immediately.                                |
| **Reset Entire Economy**    | Resets balances, rewards, sell limits, leaderboard stats, auctions, orders, and the transaction log. It does not change shop prices or categories. |

The two shop tools are the only reset actions that modify shop contents or prices. None of these tools change permission settings. **Run Wealth Tax Now** and **Run Faction
Daily Tax** are the two tools that move real balances as a side effect; the first does nothing while
`wealth_tax_enabled` is off, and the second does nothing while `factions.enabled` is off or a player has no
party.

## HTTP market snapshot and command endpoint

EconomyCraft can publish the auction and order market as JSON and accept player-scoped commands through one optional HTTP listener. The `http_market_enabled`, `http_market_port`, and `http_market_token` settings are documented in the [configuration reference](Server-Configuration.md). Set a non-empty token before enabling the listener. The listener starts with the server; changes to its settings take effect after a restart.

The market route is `GET /economycraft/v1/market`, which reads and never writes. The command route is `POST /economycraft/v1/command`. Its actions, authentication requirements, and response formats are in the [HTTP market API guide](HTTP-Market-API.md#command-route). The request names the player whose inventory or balance is used; counterparties come from the selected listing or order, not a caller-supplied player UUID. The selected port is printed in the server log when the listener starts. The token is never printed.

## Commands

| Command | Purpose |
|---|---|
| `/eco admin` | Open the administration menu. |
| `/eco reload` | Reload EconomyCraft configuration and prices. |
| `/eco import` | Import supported data from an older shared folder when one is available. |
| `/eco motd` | Preview the configured login message. |
| `/eco gossip status` | Show villager dialogue provider status. |
| `/eco gossip dialogue [profession]` | Generate a private dialogue preview. |
| `/eco gossip reload` | Reload villager dialogue configuration. |
| `/eco gossip memory inspect <villager-uuid> <player-uuid>` | Inspect stored memory for a villager and player. |
| `/eco gossip memory clear <villager-uuid> <player-uuid>` | Clear stored memory for a villager and player. |
| `/eco addmoney <targets> <amount>` | Give money to player accounts. |
| `/eco setmoney <targets> <amount>` | Set player balances. |
| `/eco removemoney <targets> [amount]` | Remove money from player accounts. |
| `/eco removeplayer <targets>` | Remove player accounts from the economy. |
| `/eco toll create <fee>` | Create a toll on the targeted block. |
| `/eco toll set <fee>` | Change your toll's fee. |
| `/eco toll transfer <player>` | Transfer your toll to a known player account. |
| `/eco toll info` | Show the toll on the targeted block. |
| `/eco toll remove` | Remove your toll from the targeted block. |
| `/eco tag <player>` | View a player's tags (admin permission required). |

### Login MOTD

Configure login messages in the `motd` section of `config/economycraft/config.json`. The [configuration reference](Server-Configuration.md) describes the keys. Use `/eco reload` after editing the file; this also cancels an in-progress message sequence. `/eco motd` previews all configured message blocks immediately.

### Server-list release notes

The server list keeps the text configured in `server.properties` and appends the latest published EconomyCraft release. It shows the release version, up to two recent merged pull-request titles, and a link to the full GitHub release notes. The notes are fetched once when the server starts on Fabric or NeoForge; a saved copy is used if GitHub is unavailable, and the configured MOTD is left alone when there is no saved copy yet. Restart the server to pick up a newer release.

## Permissions and optional integrations

| Permission node | Access |
|---|---|
| `economycraft.admin` | All admin permissions. |
| `economycraft.admin.contracts` | List and resolve contract disputes. |
| `economycraft.admin.players` | Player management and balance commands. |
| `economycraft.admin.settings` | Settings and server quest controls. |
| `economycraft.admin.shop` | Shop editor. |
| `economycraft.admin.reload` | Reload from disk. |
| `economycraft.admin.reset` | Reset tools. |
| `economycraft.command.menu` | `/eco` and `/eco menu`. |
| `economycraft.command.contracts` | Contract commands. |
| `economycraft.command.balance` | Balance command. |
| `economycraft.command.pay` | Payment command. |
| `economycraft.command.shop` | Shop command and menu button. |
| `economycraft.command.auction` | Auction house commands. |
| `economycraft.command.sell` | Sell menu. |
| `economycraft.command.orders` | Orders board. |
| `economycraft.command.deliveries` | Deliveries menu. |
| `economycraft.command.daily` | Daily reward. |
| `economycraft.command.transactions` | Transaction history. |
| `economycraft.command.worth` | Item value lookup. |
| `economycraft.command.toll` | Toll commands and menu. |
| `economycraft.command.tag` | Tag, party, and profession menus. |
| `economycraft.command.offers` | Price offers. |

Permission plugins may override default access behavior. Without a permission plugin, admin nodes require operator access and command nodes are allowed for everyone. Land-claim faction effects require the optional, Fabric-only [ShopGuard](https://github.com/TraiNguyenVan/shopguard) mod; they are unavailable on NeoForge and on Fabric servers without ShopGuard.

## Transaction logs and webhooks

When transaction logging is enabled, balance changes are recorded in daily log files under the EconomyCraft configuration folder. The retention setting and webhook options are listed in the [configuration reference](Server-Configuration.md). A Discord-compatible webhook can receive transaction notices and can be limited by a minimum transaction amount.

## Placeholder integrations

EconomyCraft can expose player balance, daily sell limit, and leaderboard values through Text Placeholder API on Fabric or Placeholder API NeoForge on NeoForge. These integrations are optional; install the matching loader-specific placeholder mod to use them.

| Placeholder | Description |
| --- | --- |
| `%economycraft:balance%` | Raw player balance. |
| `%economycraft:balance_formatted%` | Formatted player balance. |
| `%economycraft:balance_short%` | Abbreviated player balance. |
| `%economycraft:daily_sell_remaining%` | Amount the player can still earn from selling today; `∞` if unlimited. |
| `%economycraft:top_name <rank>%` | Name of the player at that leaderboard rank (`1` is the highest balance). |
| `%economycraft:top_balance <rank>%` | Raw balance at that rank. |
| `%economycraft:top_balance_formatted <rank>%` | Formatted balance at that rank. |
| `%economycraft:top_balance_short <rank>%` | Abbreviated balance at that rank. |

Leaderboard ranks are one-based. Ranks beyond the number of players resolve as invalid.
