# Toll management

Look at a block within five blocks, then open `/eco` → **Tolls**. The button requires
`economycraft.command.toll`. Keep looking at that block while using the menu;
confirmations reject a changed target, dimension, permission, or owner.

- An unregistered block offers **Create**, using the money editor to set the net fee.
- Your toll offers **Info**, **Change fee**, **Transfer**, and **Remove**.
- Another player's toll offers **Info**. Players who pass the same admin check as
  the hub's Admin button also see **Admin transfer** and **Admin remove**.

Creation and fee changes use the command's block modification and game-mode checks.
Creation respects `max_active_tolls_per_player`; transfers check the recipient's
limit again at confirmation. A limit of zero is unlimited. The player picker lists
online players and accounts already known to EconomyCraft, including offline
players with saved accounts. It does not invent a UUID from an unverified name, so
a typo cannot create a new player identity. Admins may transfer to
themselves; transferring to the current owner is rejected.

Transfers and removals require confirmation. Cancel returns without changing the
toll. Admin actions notify the former owner if online. Admin access does not grant
fee editing or creation overrides, and toll commands retain their owner checks.

Changes save through the existing `tolls.json` store. The payment, tax, cooldown,
right-click, and pressure plate handling are unchanged. No client mod or data
migration is needed. Installing the new jar requires a server restart.

## Container behavior

- Tolls are attached to a single block position. A double chest consists of two
  blocks, but EconomyCraft resolves both halves to one toll. Interacting with
  either half uses the same toll fee and counts as one active toll.
- A hopper directly under a toll chest cannot extract items. This prevents the
  hopper automation bypass; normal player interaction still charges the toll.

## Verification

With the Java toolchain available:

```sh
./gradlew -Pfilter_platforms=fabric -Pminecraft_version=26.3 :common:test :fabric:build
```

`TollUiTest` exercises real menu callbacks and the toll store with mocked server and
player boundaries. It covers visibility, missing targets, cancellation, block and
game-mode restrictions, revoked permissions, changed ownership, owner/admin flows,
recipient limits, online notices, reload from disk, and the existing right-click
and pressure plate payment paths.

For an eventual in-game smoke test, check the menu with an owner, a non-owner, and
an admin; test a protected block, an out-of-reach block, cancel/back navigation,
and both toll interaction types. These client checks are separate from the
automated tests and do not require changing a running server during development.

The current toll fee also appears in the vanilla action bar while the crosshair is
on the toll block within five blocks; when the player looks away, the message stops
refreshing and fades naturally.
