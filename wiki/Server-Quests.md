# Server quests

Server quests are requests funded by the server. Open `/quest` or `/eco quest` to browse active bounties. Select a bounty, place the quantity you want to deliver in the item slots, and confirm. Player-created requests remain in `/orders` or `/eco orders`. The board covers any applicable order tax, so the fulfiller receives the full amount shown.

When the board refreshes with active bounties, online players receive one chat notice with a clickable button to open `/quest`.

The board is drawn from eligible items in the server's price catalog and refreshes by period. Each period has a server-funded budget; the board may stop posting once the budget is used. Open quests expire when a period rolls over and do not carry forward.

## Quest stock in the shop

Items collected from completed quests enter finite stock in `/shop` when the stock shop is enabled. The shop shows the remaining quantity and the price per item. If a purchase asks for more than remains, it sells the available quantity and charges only for those items. An item with no quest stock has no shop entry.

Quest and stock-shop prices follow completed player-to-player auction sales. Each sale is normalized by its item quantity, then the board uses the median sale price. Until it has five sales in the 90-day window, it blends that median with the existing shop-based fallback. With fewer than five sales, the blend moves progressively toward the player-market price. The same current price is used when quest stock is purchased from `/shop`.

The transaction log must remain enabled for player-market pricing. If logging is disabled, quest prices use the shop-based fallback. The server retains up to the configured market lookback window; new history starts accumulating after the updated retention setting takes effect.

Server administrators can enable or disable the board and stock shop, and tune the period, budget, eligible prices, and other quest settings.
