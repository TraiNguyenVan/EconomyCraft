# Toll Management

Look at a block within five blocks, then open `/eco` → **Tolls**. This button requires the
`economycraft.command.toll` permission. Keep looking at the block while using the menu. Confirmation will be rejected if the target, dimension, permissions, or owner changes in the meantime.

- For an unregistered block, the menu shows **Create**. Use the amount editor to set the base toll fee.
- For your own toll, the menu shows **Info**, **Change fee**, **Transfer**, and **Remove**.
- For your own toll, **Manage shares** adds, edits, removes, or promotes revenue recipients. Each
  recipient has an explicit percentage and the total must be 100%; the editor shows the remaining
  percentage while a share is being changed.
- For another player's toll, the menu shows **Info** only. Players who meet the same administrator requirements as the main menu's **Admin** button also see **Admin transfer** and **Admin remove**.

Creating and changing fees use the same block-editing and game-mode checks as the command. Creation observes `max_active_tolls_per_player`; during a transfer, the recipient's limit is checked again at confirmation. A value of `0` means unlimited. The player picker lists online players and accounts already known to EconomyCraft, including offline players with saved accounts. It does not create a UUID from an unverified name, so a typo cannot accidentally create a new player identity. Administrators can transfer a toll to themselves; transferring it to its current owner is rejected.

Transfers and removals both require confirmation. Selecting **Cancel** returns without making a change. Administrative actions notify the previous owner if they are online. Administrator access does not allow fee changes or bypass creation checks, and toll commands still enforce owner checks.

Changes are saved to the existing `tolls.json` store. Payment, tax, cooldown, right-click, and gravity-drop behavior is unchanged. No client mod or data migration is required.

## Shared revenue

Tolls start with the owner receiving 100% of the net fee. The owner can add known player accounts as
recipients and assign integer percentages that total 100%. The configured `max_toll_recipients` limit
controls how many recipients a toll may have; `0` means unlimited. All recipients can use the toll
without paying.

The visitor pays one toll tax. The untaxed fee is credited atomically to every recipient, with any
whole-unit rounding remainder going to the primary owner. If a recipient cannot receive their share,
the whole payment is rejected. Existing toll records load as single-owner 100% tolls until changed.

The command equivalents are `/eco toll share add <player> <percent>`, `/eco toll share set <player> <percent>`,
`/eco toll share remove <player>`, and `/eco toll share promote <player>`. The primary owner and existing
toll administrators can manage shares. Promoting a recipient makes them primary and redistributes the
former primary's percentage across the remaining recipients. Full ownership transfer preserves the
recipient agreement and merges the former owner's share into the new owner when needed.

> [!NOTE]
> Land-claim features require [**ShopGuard**](https://github.com/TraiNguyenVan/shopguard), which runs on Fabric only. On NeoForge servers, or Fabric servers without ShopGuard, these features are unavailable: the 50% claim-cost discount and increased damage on Monarchy land, and increased speed in Anarchism wilderness.

## Chest behavior

- A toll is attached to one block position. A double chest has two blocks, but EconomyCraft resolves both halves to the same toll. Interacting with either half uses the same fee, and the chest counts as one active toll.
- A hopper directly below a chest with a toll cannot extract items. This prevents automation from bypassing the toll; normal player interaction still requires payment.

## Fee display

The current toll fee also appears in Minecraft's vanilla **action bar** when your crosshair points at the toll block within five blocks. When you look away, the message stops refreshing and fades out.
