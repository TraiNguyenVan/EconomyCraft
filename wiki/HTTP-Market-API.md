# HTTP market API

## HTTP market snapshot

`api/v1` is for mods on the server thread. A consumer that is **not** an in-process mod — a
dashboard, a companion plugin, an ops script — reaches the market over HTTP instead. Two routes
share one listener and one secret:

```text
GET  /economycraft/v1/market[?player=<uuid>]
POST /economycraft/v1/command?player=<uuid>
```

`GET` reads the market. `POST` runs one command from the set the snapshot advertises in its
`commands` field; these are the only writes exposed by either route.

Off by default. The listener is the JDK's built-in HTTP server, so it adds no dependency to the
mod, and it binds inside the server process only — reaching it is a deployment decision, not
something the mod configures.

### Authentication

Every request, on either route, must carry the configured shared secret:

| Header | Value |
|---|---|
| `X-EconomyCraft-Token` | exactly `http_market_token` |

| Response | Route | When |
|---|---|---|
| `200` | both | the snapshot (`GET`) or a command that succeeded (`POST`) |
| `400` | `POST` only | the `?player=` scope is missing or not a uuid, the body is not JSON with a `cmd`, or the command refused — see the command route below |
| `403` | both | the header is missing or does not match |
| `404` | both | a path under the route that is not the route |
| `405` | both | any method other than the route's own verb (`GET` for the market, `POST` for the command) |
| `413` | `POST` only | the command body is larger than 64 KiB |
| `500` | both | the server could not answer; the body is `{"error":"snapshot unavailable"}` or `{"ok":false,"error":"command unavailable"}` |

Each route answers one verb and refuses every other, so a `GET` to the command route is a `405`, not
a `404`. On both routes the verb and the path are checked **before** the token, so a `404` or `405`
never reads the secret.

**Every command is scoped to the player named by `?player=`.** It cannot act on behalf of an
arbitrary second player. Market counterparties come from the selected listing or order, not from a
player UUID supplied in the request: `buyListing` pays that listing's seller, and `deliverOrder`
fulfills that order's requester. `GET /economycraft/v1/market` remains read-only by construction.

The token is a shared secret, not a per-player credential: it says a caller is trusted, not which
player it is. The player whose inventory or balance a command uses is named by the `?player=` scope.
The token is never logged and never echoed in a response body or an error message.

### Response

```jsonc
{
  "generatedAt": 1760000000000,       // epoch millis, when the snapshot was taken
  "inflationMultiplier": 1.25,       // the same signal as EconomyCraftApi.inflationMultiplier()
  "taxRate": 0.1,                    // the auction-purchase tax rate, see below

  "commands": ["claimDelivery", "listItem", "buyListing", "cancelListing",
               "placeOrder", "cancelOrder", "deliverOrder"],

  "auctions": [{
    "id": 41,
    "seller": "…uuid…",
    "sellerName": "alice",
    "price": 500,
    "createdAt": 1759990000000,
    "expiresAt": 1760594800000,
    "description": "nice sword",     // absent when the seller wrote none
    "system": false,                  // true for the quest board's own rows
    "stack": { "id": "minecraft:diamond_sword", "count": 1, "components": { … } }
  }],

  "orders": [{
    "id": 7,
    "requester": "…uuid…",
    "requesterName": "bob",
    "price": 300,                     // the total posted for the whole amount
    "amount": 4,
    "escrow": 300,                    // how much of price is held right now
    "unitPrice": 75,                  // price for one item
    "createdAt": 1759990000000,
    "expiresAt": 1760594800000,
    "description": "note",            // absent when the requester wrote none
    "system": false,
    "stack": { "id": "minecraft:wheat", "count": 1 }
  }],

  "players": [{ "uuid": "…uuid…", "name": "alice" }],

  "you": {                            // present only for a scoped request; see below
    "balance": 4242,
    "deliveries": [{ "id": "<delivery-uuid>", "stack": { "id": "minecraft:apple", "count": 3 } }],
    "listings": [41],
    "orders": [7],
    "inventory": [                    // always present; empty when the player holds nothing
      { "slot": 0, "stack": { "id": "minecraft:diamond_hoe", "count": 1 } },
      { "slot": 9, "stack": { "id": "minecraft:amethyst_shard", "count": 7 } }
    ],
    "enderChest": []                  // always present; same shape as inventory
  }
}
```

`stack` is the same shape the mod already persists, because it is the same `ItemStack.CODEC`
encoding. `components` is present only when the item carries components.

**`commands` is the capability signal, and it is always present.** It names the commands this server
accepts on `POST /economycraft/v1/command`, in the shape `{"cmd": "<name>", ...}`. Gate a UI on it
rather than on a route probing for a field: an array that is present is a promise that the command is
served, and it never varies within one jar. This build advertises, in order:

```jsonc
["claimDelivery", "listItem", "buyListing", "cancelListing", "placeOrder", "cancelOrder", "deliverOrder"]
```

A server whose snapshot has no `commands` field at all is an older jar. Treat the order commands as
unavailable; derive earlier command support from the snapshot version and available `you` fields.

**`inventory` and `enderChest` are sparse, and always present.** Each is an array of
`{"slot": <int>, "stack": <encoded stack>}` in container order, where `slot` is the container index
the item actually occupies. Only **non-empty** slots are emitted, so a row's position in the array is
not its slot — read `slot` off the row instead of counting. Both arrays are present in every `you`
block, including one with nothing to report, where they are empty rather than `null` or absent. When
the player is online this is their live inventory; when they are offline it is read from the server's
own player data. The two are never mixed, so a field is either current or reported empty, never
stale.

**A `slot` from `inventory` is only addressable when it is below 36**, and that is the one place the
two states do not agree:

| State | `inventory` slots | Ender chest slots |
|---|---|---|
| offline | `0`–`35` only. The server stores armour and the offhand outside the `Inventory` list, so no offline row can name them at all. | `0`–`26` |
| online | `0`–`40`: `0`–`35` main, `36`–`39` armour, `40` offhand — the live container read sees all 41. | `0`–`26` |

So an **online** snapshot can show a row at `slot: 38`, and every slot-addressed command refuses
`slot >= 36`. The index means the same thing in both states exactly for `0`–`35`, which is also the
range the mod's own item picker walks; armour and offhand have no offline counterpart to address, so a
command that accepted them would escrow a chestplate over HTTP and mean something else entirely to an
offline player.

**`system` is a tag, not a filter.** Server-owned rows carry `"system": true`, so a consumer that
cannot filter still sees the whole market instead of a silently truncated one. Current quest-board
requests appear in `orders`; legacy bot-owned buyback listings are moved into finite `/shop` stock on
the next board sweep. Filter on the flag to hide server-owned rows.

**`taxRate` is the configured base rate**, so an effective auction cost is `price * (1 + taxRate)`
without the consumer hard-coding a number that an admin can retune. It does **not** include a
player's own discount or exemption, which are deliberately not published (see the exclusions below).

### Scoping to one player

Add `?player=<uuid>` and, **if that uuid is a player this snapshot already lists in `players`**, the
response gains a `you` block with that player's balance, their waiting deliveries, the ids of their
own listings and orders, and their inventory and ender chest.

The ids in `you` are drawn from the `auctions` and `orders` arrays in the very same response, so an
id you act on always names a row you can see. An unscoped request has no `you` block at all — not an
empty one.

A uuid that is not a known player, or the quest board's own uuid, yields no `you` block, and the
private read is never performed for it.

The same `?player=` scope, parsed the same way, selects the player the command route acts for. Every
delivery row has a stable `id`, retained when the server saves the delivery ledger. The command uses
that ID instead of a list position, so a retry cannot silently select the next delivery after an
earlier request finishes late. `you.inventory[].stack` is exactly the stack a `listItem` names, so
the snapshot and command route agree on which item is which.

### Command route

```text
POST /economycraft/v1/command?player=<uuid>
X-EconomyCraft-Token: <http_market_token>

{ "cmd": "claimDelivery", "deliveryId": "<delivery-uuid>" }
{ "cmd": "listItem", "slot": 12, "count": 8, "price": 250,
  "item": { "id": "minecraft:diamond", "count": 8 }, "description": "spare" }
{ "cmd": "buyListing", "id": 293 }
{ "cmd": "cancelListing", "id": 293 }
{ "cmd": "placeOrder", "slot": 12, "amount": 16, "price": 1200,
  "item": { "id": "minecraft:diamond", "count": 8 }, "description": "for crafting" }
{ "cmd": "cancelOrder", "id": 7 }
{ "cmd": "deliverOrder", "id": 7, "amount": 4 }
```

The `?player=` scope is **required** here and names the account every command acts for; a missing or
unparseable scope is a `400` before the body is read. The body is a JSON object whose `cmd` names the
command and whose remaining fields are its arguments. `cmd` must be one of the names in the
snapshot's `commands` array; anything else is `unknown command: <cmd>`. Bodies larger than 64 KiB are
rejected with `413`.

| `cmd` | Arguments | Effect |
|---|---|---|
| `claimDelivery` | `deliveryId` (UUID, required) | Moves the delivery with that ID in **that player's own** `you.deliveries` into that player's own inventory, then removes it from the delivery ledger. |
| `listItem` | `slot` (integer, required), `count` (integer, required), `price` (integer, required), `item` (encoded stack, required), `description` (string, optional) | Takes `count` items out of `slot` of **that player's own** `you.inventory`, and posts them as a new listing for `price` with `description` as its text. The reply's `id` is the new listing. |
| `buyListing` | `id` (integer, required) | Buys the listing `id` for **that player**. The caller pays the ask plus the auction-purchase tax; the seller receives the ask and is told who bought it. The item is delivered to the caller's inventory or, if that will not take it, to their deliveries mailbox. |
| `cancelListing` | `id` (integer, required) | Removes the caller's own listing `id`. The escrowed stack returns to the caller's inventory or, if that will not take it, to their deliveries mailbox. |
| `placeOrder` | `slot`, `amount`, `price` (integers, required), `item` (encoded stack, required), `description` (string, optional) | Posts a buy order for the caller. The item is a prototype from the caller's inventory; it is not taken. `price` is escrowed when the order is created. |
| `cancelOrder` | `id` (integer, required) | Cancels the caller's own order and refunds its remaining escrow. |
| `deliverOrder` | `id` (integer, required), `amount` (integer, optional; `0` or omitted means as many as held) | Delivers matching items from the caller's inventory to someone else's order. The caller must be online. |

#### `claimDelivery`

Use the `id` from the delivery row in a recent snapshot. If the request times out after the claim has
run, retrying that ID cannot claim a different delivery; the server returns `no delivery with id`
once that ID has been removed.

The command is all-or-nothing. The item is placed in the inventory **before** it is dropped from the
ledger, and a placement that does not fully fit is rolled back, so a refusal leaves both the ledger
and the inventory exactly as they were. A refused claim is therefore safe to retry, and a successful
one cannot hand the same item out twice.

#### `placeOrder`

The server verifies that `item` still matches the named inventory slot, then escrows `price` and
creates the order. The item remains in the caller's inventory as a prototype. `amount` is the total
number requested and is limited to at most 36 stacks.

#### `cancelOrder`

Only the caller's own order can be cancelled. The remaining escrow is refunded; a failed refund leaves
the order in place.

#### `deliverOrder`

Only an online caller can deliver an order. The server takes matching items from the caller's
inventory and pays the fulfiller from the order's escrow. On success, `total` is the gross payment
drawn from escrow and `paid` is the net payout received by the caller. A zero or omitted `amount`
means deliver as many as the caller holds, subject to the order's remaining amount.

#### `listItem`

`slot` must be `0`–`35` — see the slot table above; armour and the offhand are refused. `item` is the
encoded stack exactly as `you.inventory[].stack` gave it: send the whole object back, not just its id.

The mod re-reads the slot from whichever inventory is authoritative **at execution time** and refuses
if its item, components, or count no longer match the encoded stack. This makes a request from a stale
page refuse instead of listing a different stack or applying a delayed retry twice. `count` may still
be smaller than the held stack, and is refused when it exceeds what the slot holds. `price` follows
the same bounds `/ah sell` enforces (`1` upward).

`description` runs through the same sanitiser the in-game command uses: line breaks are stripped, it
is trimmed, blank becomes no description, and it is capped at 100 characters.

Ordering, so that no item can be lost or duplicated: the slot is validated first, the listing is
created only after the item has left the inventory, and if creating the listing fails the stack is
placed in the caller's **deliveries mailbox** instead — a delivery the caller can claim, never a lost
stack. A double-submit is harmless here for the same reason: the second request finds the stack gone
or changed and refuses.

The caller is charged nothing for a listing, so a successful `listItem` reports `total: 0`.

#### `buyListing`

The listing is claimed before anything is paid, and put back if the payment fails, so a refusal costs
nothing. A caller cannot buy their own listing.

`total` in the reply is what the caller was actually charged, tax included. The tax is the buyer's,
as it is in the game, and the seller receives the whole ask. A Monarchy import surcharge or a
per-player Merchant discount means the client cannot compute `total` in advance from `taxRate` alone
— show the ask on a confirmation step and the reply's `total` on the receipt.

The sale is the mod's own auction purchase, the one the in-game menu runs, so the seller's sale
notification and delivery fallback behave identically over HTTP. A legacy quest-board listing may
still be buyable before migration; those old server listings are tax-free, as they are in the game.

#### `cancelListing`

Only the caller's own listing can be cancelled: an id that belongs to somebody else is refused, with
the same reason the in-game menu uses. No money moves either way, and `total` and `paid` are `0`.

#### Replies

Every command returns the same shape, with `COMMAND_GSON`'s nulls serialised, so every field is
present on every answer:

```jsonc
{ "ok": true,  "error": null,  "id": 1031, "total": 0, "paid": 0, "stored": false }
{ "ok": false, "error": "not enough balance", "id": null, "total": 0, "paid": 0, "stored": false }
```

- `error` — the refusal, or `null`
- `id` — the created or acted-on listing id on success; `null` on a refusal
- `total` — what the caller was charged (`buyListing`) or the gross amount paid from order escrow
  (`deliverOrder`); `0` for the other commands
- `paid` — net amount the caller received from `deliverOrder`; `0` for the other commands
- `stored` — `true` when an item went to the deliveries mailbox instead of the caller's inventory.
  This can be `true` on a refusal: a `listItem` whose listing could not be created returns
  `ok:false` **and** `stored:true`, and the caller's item is waiting in their deliveries.

`ok` is the only thing to branch on. A `200` means `ok:true` and a `400` means `ok:false`, and the
reason is for diagnosis — the set of reasons can grow without the contract changing.

| `error` | When |
|---|---|
| `missing or invalid ?player= scope` | the `?player=` parameter is absent or not a uuid |
| `body must be JSON with a cmd field` | the body is not a JSON object carrying a `cmd` |
| `unknown command: <cmd>` | `cmd` names no command this route serves |
| `claimDelivery requires a deliveryId UUID` | `claimDelivery` was called without a string `deliveryId` |
| `deliveryId must be a UUID` | `deliveryId` is not a UUID |
| `no delivery with id <uuid>` | that delivery is no longer in the player's ledger |
| `inventory full` | the whole stack would not fit, so nothing was claimed |
| `the auction house is disabled` | `auction.enabled` is off, so `listItem`, `buyListing` and `cancelListing` are refused |
| `orders are disabled` | `orders.enabled` is off, so the order commands are refused |
| `listItem requires an integer slot` / `slot must be an integer` | `listItem` was called with no `slot`, or one that is not a whole number |
| `slot must be between 0 and 35` | the slot is outside the addressable range, e.g. armour online |
| `listItem requires an integer count` / `count must be an integer` | `listItem` was called with no `count`, or one that is not a whole number |
| `count must be at least 1` | the count is zero or negative |
| `you only have <n> in that slot` | the count exceeds what the slot held at execution time |
| `listItem requires an integer price` / `price must be an integer` | `listItem` was called with no `price`, or one that is not a whole number |
| `price must be at least 1` / `price must be at most <max>` | the price is outside the bounds `/ah sell` enforces |
| `listItem requires an encoded item` | `item` is missing or is not the encoded stack object |
| `description must be text` | `description` was sent as something other than a string |
| `the slot no longer holds that item` | the slot's item, components, or count no longer match the encoded `item` — a stale page or repeated request |
| `you have reached your limit of <n> active listing(s)` | the caller is at their per-player listing limit |
| `that item could not be taken out of your inventory` | an offline player's player data could not be rewritten, so nothing was listed |
| `could not list that item` | the listing could not be created; the item is in the caller's deliveries, and `stored` is `true` |
| `buyListing requires an integer id` / `id must be an integer` | `buyListing` was called with no `id`, or one that is not a whole number |
| `cancelListing requires an integer id` | as above, for `cancelListing` |
| `id must be at least 1` | the id is zero or negative |
| `you cannot buy your own listing` | `buyListing` named a listing the caller is selling |
| `not enough balance` | the caller cannot afford the ask plus tax |
| `seller cannot receive this payment` | the seller is at the balance ceiling, so the sale was rolled back |
| `listing no longer available` | the id names no listing, or it is gone, or — for `buyListing` — it went while the request was in flight |
| `you don't own this listing` | `cancelListing` named somebody else's listing |
| `placeOrder requires an encoded item` / `placeOrder requires an integer slot` / `placeOrder requires an integer amount` / `placeOrder requires an integer price` | a required `placeOrder` argument is missing or has the wrong JSON type |
| `amount exceeds 36 stacks` / `price must be at most <max>` | `placeOrder` exceeds its bounds |
| `you can't afford to reserve <amount>` | the caller cannot escrow the order's price |
| `you don't own this order` / `order no longer available` | `cancelOrder` named another player's order or a missing order |
| `deliverOrder requires an integer id` / `id must be an integer` / `amount must be an integer` | a `deliverOrder` argument is missing or malformed |
| `you must be online to deliver an order` | `deliverOrder` was called for an offline player |
| `you cannot fulfill your own request` / `invalid amount` / `this request must be fulfilled all at once.` | `deliverOrder` conflicts with the order or requested amount |
| `you don't have enough of that item` / `requester can't pay` / `your balance is too high to receive this payout` | `deliverOrder` cannot complete its item or payment transfer |
| `command failed` | an unexpected fault while running the command; the exception itself is written to the server log, never to the reply |
| `command unavailable` | the server could not answer (returned with `500`) |

Those are the reasons the route returns today. Branch on `ok`, not on the string — the reason is for
diagnosis, and the set of reasons can grow without the contract changing.

**What it is not.** The route has no argument that names a second player and cannot act on another
player's behalf. It only exposes the listed market operations; it has no admin reset, arbitrary
balance adjustment, direct gift, bid, offer, or negotiation command.

### What it deliberately does not expose

The market route is a market view. It is **not** a second route into the economy's private state, and
it never exposes, in any form:

| Never exposed | Why not |
|---|---|
| another player's balance | `you` is the only place a balance appears, and it is the requested one |
| another player's deliveries | `deliveries` only ever reads the requested uuid |
| another player's inventory or ender chest | `inventory` and `enderChest` are read for the requested uuid only, and no request can name a second player |
| tolls, toll owners, toll prices | not part of a market view |
| wealth tax settings or results | not part of a market view |
| faction / party data | would leak who is in which party |
| gossip or villager data | private to the server's own AI feature |
| admin reset capability | no command can reset, erase or grant anything. `listItem` and `cancelListing` move stacks the caller already owned; `buyListing` spends the caller's own balance, and both of those go through the same implementations the in-game menus use, so neither can do more than the game already could |

### Configuration

One listener serves both routes, and one set of keys controls it.

| Key | Default | Meaning |
|---|---|---|
| `http_market_enabled` | `false` | Master switch for the listener. |
| `http_market_port` | `0` | Bind port. `0` binds an ephemeral port, which is logged at server start. |
| `http_market_token` | `""` | The shared secret for `X-EconomyCraft-Token`, on both routes. **Empty means the listener refuses to start** — with no secret the `?player=` scoping could not be told apart from no scoping and the command route would accept an unauthenticated caller, so neither route is served. |

These keys take effect on the next server start; `/eco reload` re-reads them but does not rebind an
already-running listener.

### Threading

The listener runs on its own worker threads, so every snapshot read **and** every command is
marshalled onto the server thread with `server.execute(...)` and awaited there — the same contract as
`api/v1`, which throws if called off the server thread. Nothing is read from, or written to, the live
stores off-thread, so a command runs exactly where every other mutation in the mod runs. A request
that the server thread does not answer within a few seconds is returned as unavailable rather than
left hanging.

---
