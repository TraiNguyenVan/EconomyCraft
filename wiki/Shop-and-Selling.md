# Shop and selling

The shop lets you buy and sell items at prices set by the server. Open it with `/shop` or `/eco shop`, or choose **Shop** from `/eco`.

## Buying and selling in the shop

- Browse categories, use `/shop search <query>` to find an item, or open a category with `/shop <category>`.
- Left-click an item to buy and right-click to sell. Shift-click uses the item's configured bulk amount.
- An item may be buy-only, sell-only, or unavailable in one direction. The shop shows the available action and price.
- Shop purchases use the displayed price. No transaction tax is added to a fixed-price shop purchase.

The server can disable the shop or selling separately. Dynamic pricing, when enabled, changes buy prices based on the economy; it does not change sell prices. Server administrators control these settings.

## Selling from your inventory

Open `/sell` or `/eco sell` to place items in the sell menu. Review the offered total and confirm the sale. Items without a configured sell price cannot be sold. Items matching open player orders are matched first. The server may limit how much each player can earn from selling per day.

## Checking an item's value

Hold an item and run `/worth`, or use `/worth <item> [amount]` to check a specific item and amount. This reports the configured buy and sell values; it does not guarantee that a particular player can complete a sale or purchase at that moment.

## Price changes

The server's shop editor controls item prices, categories, and bulk amounts. If dynamic pricing is enabled, the buy price shown in the shop may differ from the base price configured by an administrator.
