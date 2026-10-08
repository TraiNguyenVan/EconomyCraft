# Auctions, orders, and offers

Players can list items for sale in the auction house or post an order requesting items. Open the menus with `/ah` or `/auction`, and `/orders`; each also appears in the `/eco` menu.

## Auction house

- Browse or search with `/ah search <query>`.
- To list an item, hold it and use `/ah <price> [amount] [description]`. You can also use `/ah list <price> [amount] [description]`.
- Listings expire according to the server's settings. Unsold items are sent to your deliveries.
- Buyers pay the listed price plus any applicable tax. The listing screen shows tax information when it can be determined; some faction charges depend on the buyer.
- Items and proceeds that cannot be delivered immediately are available from `/deliveries`.

`/auction` has the same behavior as `/ah`. Server settings can disable auctions and set a per-player listing limit.

## Player orders

Browse or search requests with `/orders` or `/orders search <query>`. To request an item, use:

```text
/orders request <item> <amount> <price> [description]
```

The total price is reserved when the request is created. Other players can fulfill the request for the listed items and receive the offered payment after any applicable tax. A request can expire; when it does, its remaining reserved money is returned to its owner. Items that cannot be delivered immediately go to `/deliveries`.

## Price offers

An offer is a non-binding price suggestion on another player's auction listing or order. It does not reserve money or items, and the owner can accept or decline it. The listing or request may still be bought or fulfilled at its posted terms first. Offers on server quest orders and quest buyback listings are not accepted.

Open `/offers` or `/eco offers` to review offers on your listings and requests, and manage offers you have made. The owner receives notices about new offers and their outcomes. A notice may include a link that opens the relevant offer screen directly.

## Deliveries

Use `/deliveries` or `/eco deliveries` to claim items or payouts that could not be delivered directly, including while you were offline or when your inventory was full. Deliveries remain available independently of whether the orders board is enabled.
