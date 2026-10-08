# Factions

EconomyCraft offers four factions, each with distinct benefits and drawbacks. Choose a faction with `/tag` or `/eco party <faction_name>`.

> [!NOTE]
> **Online-time rule:** Time accumulates only while a player is online on the server and pauses while they are offline. A timer activates and resets each time the player accumulates the specified number of online minutes.

---

## 1. Communism
- **Icon:** `☭` | **Color:** Red
- **Benefits:**
  - **Subsidy:** This has no effect in the current version.
  - **Public Investment:** 50% chance to pay no tax when using toll stations. The toll owner still receives the full toll fee.
- **Drawbacks:**
  - **Party Dues:** Every 45 accumulated online minutes, $10 is deducted as party dues and removed from circulation.
  - **Personal Income Tax:** Every 45 online minutes, immediately after party dues are collected, an anti-hoarding tax is charged based on the remaining balance. Only **one** tier applies: the highest tier reached by your balance.
    - Balance of $10,000 or more: 0.25% of the current balance.
    - Balance of $15,000 or more: 0.375% of the current balance.
    - Balance of $22,000 or more: 0.625% of the current balance.

> [!NOTE]
> These tax rates are the mod's defaults. Server administrators can change them in `config.json`.

---

## 2. Capitalism
- **Icon:** `$` | **Color:** Yellow
- **Benefits:**
  - **Competitive Market:** When you list an item on the `/ah` auction house, its buyer is exempt from the transaction tax, making your listing more competitive.
- **Drawbacks:**
  - **Capitalist State:** Daily wealth tax has a base rate of 2.5%, multiplied by the inflation factor and the faction's share of wealth. The rate cannot increase or decrease by more than 25% per day. Toll transaction tax is increased by 25%.

---

## 3. Monarchy
- **Icon:** `♔` | **Color:** Purple
- **Benefits:**
  - **Autonomy:** The cost of creating and expanding protected land claims through ShopGuard is reduced by half (-50%).
  - **The Crown's Protection:** Deal 15% more damage and gain 15% damage resistance (take 15% less damage) while standing in your own claimed land.
- **Drawbacks:**
  - **Tribute:** An additional tribute tax is charged and destroyed, equal to Monarchy's daily tax. Monarchy's daily tax is much lower than Capitalism's and is adjusted based on the total money in circulation on the server.
  - **Imports:** Each purchase from the `/ah` auction house or fulfillment of an order has a 50% chance of incurring an additional import tax equal to 50% of that transaction's tax.

---

## 4. Anarchism
- **Icon:** `Ⓐ` | **Color:** White / Gray (default when no faction is chosen)
- **Benefits:**
  - **Freedom:** You pay no taxes of any kind (transaction, daily, or toll taxes are all zero; the original purchase and toll fees still apply).
  - **Ease:** Gain 15% movement speed on foot and 15% movement speed on horseback while in wilderness that has not been claimed.
- **Drawbacks:**
  - **Anarchy:** You cannot own or claim new land, receive a land transfer from another player, or be added to another player's trust list with `/claim trust`.
