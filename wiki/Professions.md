# Professions

EconomyCraft professions let players develop skills through normal survival activities and earn corresponding perks.

Choose a profession with `/job` or `/eco job`, or open `/eco` and select **Tags**. See [Choosing a Party and Profession](Choosing-a-Party-and-Profession) for details about the 30-hour lockout.

## Progression and rust

- **Starting level:** A newly chosen profession starts at **Apprentice**. Meet its requirements to advance to **Master**.
- **Changing professions:** Progress in the previous profession resets to zero whenever you switch professions.
- **Rust:** If you reached Master in a profession, switched to another profession, and later returned, you enter the **Rust** state for **45 minutes of online time**.
  - While Rust is active, profession buffs are only **50% effective**.
  - After 45 minutes of accumulated online time, you automatically regain **Master** level.

---

## 1. Builder
- **Requirement for Master:** Place 1,000 building blocks, such as wood, stone, glass, walls, fences, dirt, or quartz.
- **Skills:**
  - **Proficiency:** Increases vanilla block-interaction reach by +1 block at Apprentice and +2 blocks at Master, over the default 4.5 blocks. This helps with placing blocks and interacting with chests, signs, and item frames; the bonus is not limited to building.
  - **Fixer:** At Master, gain **Haste I** while mining stone, cobblestone, dirt, and building blocks.

---

## 2. Farmer
- **Requirement for Master:** Complete 300 farming actions, such as planting crops, harvesting mature crops, feeding animals, or breeding animals.
- **Skills:**
  - **Lush Growth:** Every 4 minutes, the system scans a 24-block radius around you. Each crop has a 10% chance at Apprentice or 20% chance at Master to receive a natural growth boost, similar to bone meal.
  - **Care:** Reduces the time before livestock can breed again by 10% at Apprentice or 20% at Master. Offspring grow 15% faster at Apprentice or 30% faster at Master.
  - **Craftiness:** When crafting or cooking food, there is a 1% chance at Apprentice or 5% chance at Master to receive 2 extra items of the same type.

---

## 3. Miner
- **Requirement for Master:** Mine 270 ores. Diamond Ore and Gold Ore count twice: one ore gives 2 points.
- **Skills:**
  - **Agility:** Gain **Haste II** while mining stone, deepslate, tuff, netherrack, and all ores.
  - **Handy:** When mining ores, there is a 5% chance at Apprentice or 15% chance at Master to receive twice the ore drops.
  - **Safety Gear:** At Master, accidentally touching lava grants **Regeneration II** for 4 seconds. Cooldown: 5 minutes.

---

## 4. Merchant
- **Requirement for Master:** Complete both objectives:
  - Trade with villagers 50 times. Stick trades do not count, and each villager can contribute at most 20 trades.
  - Complete 5 purchases on the `/ah` auction house.
- **Skills:**
  - **Silver Tongue:** Reduces the cost of trades with employed villagers and reduces transaction tax on taxable player payments, tolls, auction purchases, and order fulfillment. Its configured rate is 5% at Apprentice or 15% at Master; Rust halves the profession effect.

---

## 5. Soldier
- **Requirement for Master:** Kill 100 hostile mobs.
- **Skills:**
  - **Tempered Steel:** At Apprentice, take 5% less damage and deal 5% more damage. At Master, both effects increase to **±15%**.
  - **Adrenaline:** At Master, receiving a harmful effect automatically activates this skill. It halves the duration of all current harmful effects and any additional harmful effects received during the next 4 seconds. After those 4 seconds, the skill enters a 5-minute cooldown.
