# Server administration

EconomyCraft's main menu is `/eco`; administrators can open its **Admin** section with `/eco admin`. The available controls depend on permission configuration.

## Admin menu

- **Shop editor:** add and edit catalog entries, prices, categories, visibility, and bulk amounts.
- **Settings:** change the settings exposed by the in-game editor, including feature switches, shop and order limits, and the wealth-tax block.
- **Players:** manage player balances and leaderboard stats, remove an account, and set per-player auction or order limits.
- **Server Quests:** control the quest board and buyback options exposed by the editor.
- **Reload:** reload configuration and price data from disk.
- **Reset Tools:** reset daily data, clear listings or orders, run configured tax passes, or reset the economy. Review each confirmation carefully; some actions change balances or erase history.

Reset Tools include:

| Tool | Effect |
|---|---|
| Reset All Balances | Set account balances back to the configured starting balance. |
| Reset Daily Reward Data | Let everyone claim the daily reward again. |
| Reset Daily Sell Limits | Reset everyone's daily sell total. |
| Clear Auctions | Cancel active listings and return listed items through deliveries. |
| Clear Orders | Cancel open requests and refund reserved balances. |
| Run Wealth Tax Now | Run the configured daily wealth tax immediately. |
| Run Faction Daily Tax | Run Capitalism and Monarchy daily taxes immediately. |
| Reset Entire Economy | Run the listed resets, clear leaderboard stats, and delete the transaction log. |

The tax tools move player balances. **Reset Entire Economy** also deletes transaction history; use the confirmation screen to check the action before accepting it.

Some configuration sections are file-only. Edit `config/economycraft/config.json` and use **Reload from disk** to apply them. The [bundled `config.json`](https://github.com/TraiNguyenVan/EconomyCraft/blob/main/common/src/main/resources/assets/economycraft/config.json) lists the exact setting names and shipped defaults; refer to that file when tuning values.

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

`/eco import` is available only when the server has importable shared-folder data. The player commands and `/eco` subcommands are listed on the relevant gameplay pages. Standalone admin command aliases are off by default and can be enabled in configuration.

## Permissions and optional integrations

| Permission node | Access |
|---|---|
| `economycraft.admin` | All admin permissions. |
| `economycraft.admin.players` | Player management and balance commands. |
| `economycraft.admin.settings` | Settings and server quest controls. |
| `economycraft.admin.shop` | Shop editor. |
| `economycraft.admin.reload` | Reload from disk. |
| `economycraft.admin.reset` | Reset tools. |
| `economycraft.command.menu` | `/eco` and `/eco menu`. |
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

Permission plugins may override default access behavior. Land-claim faction effects require ShopGuard on Fabric; they are unavailable on NeoForge and when ShopGuard is not installed.
