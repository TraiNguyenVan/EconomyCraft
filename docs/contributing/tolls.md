# Toll contributor verification

Run the focused toll verification command:

```sh
./gradlew -Pfilter_platforms=fabric -Pminecraft_version=26.3 :common:test :fabric:build
```

`TollUiTest` covers menu callbacks and toll storage, including visibility, missing targets, cancellation,
block and game-mode restrictions, revoked permissions, ownership changes, owner/admin flows, recipient
limits, online notices, reloads, right-click payment and pressure-plate payment.

For an in-game smoke test, check the menu as owner, non-owner and admin; test a protected block, an
out-of-reach block, cancel/back navigation, right-click payment and pressure-plate payment.

Behavioral details to preserve:

- Tolls attach to a block position. Both halves of a double chest resolve to one toll and count once toward
  `max_active_tolls_per_player` (default `10`).
- A hopper directly under a toll chest cannot extract items; player interaction still pays.
- Admin access grants transfer and removal only, not fee editing or toll creation override. Toll commands
  retain owner checks.
- The vanilla action bar shows the fee while the crosshair points at a toll block within five blocks.
- Legacy toll JSON without share data loads as an owner-only toll with a 100% share.
- Shared toll payouts calculate one tax and settle all recipient credits in one balance mutation. A
  failed recipient credit must leave the visitor and every recipient unchanged.
- Share percentages are integer values totaling 100%; `max_toll_recipients` is clamped like other numeric
  config keys, and zero means unlimited.
