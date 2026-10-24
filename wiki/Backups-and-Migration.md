# Backups and migration

EconomyCraft keeps its configuration, prices, transaction logs, and saved economy under one folder:

- Dedicated server: `config/economycraft/`
- Singleplayer world: `saves/<world>/economycraft/`

## Back up or restore a world

1. Stop the server or exit the world.
2. Copy the entire `economycraft` folder to the backup location. This includes the database and the settings needed to use it.
3. To restore, stop the server or exit the world and replace the folder with the backup copy before starting again.

Keeping a full folder copy preserves prices, settings, transaction logs, and the economy together.

## Import an older shared folder into a singleplayer world

Use `/eco import` from the singleplayer world when EconomyCraft detects an older shared `config/economycraft/` folder. This command is available in singleplayer; it is not registered on a dedicated server.

The import copies supported settings and economy data into the world folder. If that world already has EconomyCraft data, the import first creates a timestamped backup folder beside the world's `economycraft` folder. After a successful import, the old shared folder is renamed to `config/economycraft_imported/`. If the import fails, the old folder stays in place so you can retry.

Imports from older data formats do not carry party and profession selections or online-time progress. Other supported settings and saved economy data are copied into the world folder.
