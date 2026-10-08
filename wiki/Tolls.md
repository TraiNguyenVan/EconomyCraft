# Toll Management

Look at a block within five blocks, then open `/eco` → **Tolls**. This button requires the
`economycraft.command.toll` permission. Keep looking at the block while using the menu. Confirmation will be rejected if the target, dimension, permissions, or owner changes in the meantime.

- For an unregistered block, the menu shows **Create**. Use the amount editor to set the base toll fee.
- For your own toll, the menu shows **Info**, **Change fee**, **Transfer**, and **Remove**.
- For another player's toll, the menu shows **Info** only. Players who meet the same administrator requirements as the main menu's **Admin** button also see **Admin transfer** and **Admin remove**.

Creating and changing fees use the same block-editing and game-mode checks as the command. Creation observes `max_active_tolls_per_player`; during a transfer, the recipient's limit is checked again at confirmation. A value of `0` means unlimited. The player picker lists online players and accounts already known to EconomyCraft, including offline players with saved accounts. It does not create a UUID from an unverified name, so a typo cannot accidentally create a new player identity. Administrators can transfer a toll to themselves; transferring it to its current owner is rejected.

Transfers and removals both require confirmation. Selecting **Cancel** returns without making a change. Administrative actions notify the previous owner if they are online. Administrator access does not allow fee changes or bypass creation checks, and toll commands still enforce owner checks.

Changes are saved to the existing `tolls.json` store. Payment, tax, cooldown, right-click, and gravity-drop behavior is unchanged. No client mod or data migration is required.

> [!NOTE]
> Land-claim features require **ShopGuard**, which runs on Fabric only. On NeoForge servers, or Fabric servers without ShopGuard, these features are unavailable: the 50% claim-cost discount and increased damage on Monarchy land, and increased speed in Anarchism wilderness.

## Chest behavior

- A toll is attached to one block position. A double chest has two blocks, but EconomyCraft resolves both halves to the same toll. Interacting with either half uses the same fee, and the chest counts as one active toll.
- A hopper directly below a chest with a toll cannot extract items. This prevents automation from bypassing the toll; normal player interaction still requires payment.

## Fee display

The current toll fee also appears in Minecraft's vanilla **action bar** when your crosshair points at the toll block within five blocks. When you look away, the message stops refreshing and fades out.
