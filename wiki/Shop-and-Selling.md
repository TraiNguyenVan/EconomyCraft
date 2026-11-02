# Shop and selling

The shop lets you buy finite stock supplied by completed quests and direct player sales, and sell items at server-set prices. Open it with `/shop` or `/eco shop`, or choose **Shop** from `/eco`.

## Buying and selling in the shop

- Browse categories, use `/shop search <query>` to find an item, or open a category with `/shop <category>`.
- Left-click an item to buy and right-click to sell. Shift-click uses the item's configured bulk amount.
- A buy offer appears only while the server has stock for that item. Its entry shows the exact remaining quantity and the current price per item.
- A category with stock remains visible under its normal name even when that category is disabled. Its page still shows only items currently in stock; unrelated catalog items stay hidden.
- Shift-click buys up to the configured bulk amount. If less stock remains, the shop sells that amount and charges only for what it delivers.
- Stock prices follow recent completed player-to-player auction sales. If there is not enough sales history, the price blends with the shop-based fallback.
- Direct `/sell` stock is purchased before quest stock when both are available. That purchase removes coins from the economy; quest-stock purchases keep their existing quest-bot transfer.
- The current price cannot fall below the highest qualifying direct-sale payout plus the configured stock-price-floor margin. This prevents a sale and repurchase loop when auction history is manipulated.
- Shop purchases use the displayed price. No transaction tax is added to a fixed-price shop purchase.

The server can disable the shop, selling, quest-stock offers, or direct-sale stock separately. Dynamic pricing changes the shop-based fallback; it does not change prices derived from player auction sales. Server administrators control these settings.

## Selling from your inventory

Open `/sell` or `/eco sell` to place items in the sell menu. Review the offered total and confirm the sale. Items without a configured sell price cannot be sold. Items matching open player orders are matched first. Any remaining standard, pristine items sold to the server add to the shop's direct-sale stock; the confirmation preview and result tell you how many. Damaged items, items with enchantment premiums, and custom-priced items remain sellable but do not add stock. The server may limit how much each player can earn from selling per day.

### `/sell` details

- **Add everything sellable** pulls every currently sellable item from your inventory into the menu. Nothing is sold until you confirm; closing the menu returns the deposited items.
- For standard configured items, the sell value includes configured enchantment values. Damaged items are scaled by their remaining durability. Non-empty containers and bundles are not accepted as ordinary item sales.
- The **Shulker boxes** button cycles through three modes: **Don't accept filled shulkers**, **Sell only contents**, and **Sell everything incl. box**. A filled shulker must contain sellable contents to be accepted. In contents-only mode the box remains; in the final mode both its contents and the box can be sold.

## Checking an item's value

Hold an item and run `/worth`, or use `/worth <item> [amount]` to check a specific item and amount. This reports the configured buy and sell values; it does not guarantee that a particular player can complete a sale or purchase at that moment.

## Price changes

The server's shop editor controls item prices, categories, and bulk amounts. If dynamic pricing is enabled, the buy price shown in the shop may differ from the base price configured by an administrator.
