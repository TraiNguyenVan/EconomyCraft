# Ground Truth: Command Inventory

**Extracted**: 2026-10-07 from `common/src/main/java/com/reazip/economycraft/EconomyCommands.java`.
Line numbers are the call sites inside the `eco` root builder, which begins at line 170 with
`literal("eco")` and returns at line 214.

**This file is the authority for every documented command** (`contracts/config-reference-contract.md` R8).

## `/eco` subcommands - 26 registered

`/eco` on its own also opens the hub (`root.executes`, line 172).

| Command | Call-site line | Notes |
|---|---|---|
| `/eco menu` | 173 |  |
| `/eco admin` | 176 | requires an admin node |
| `/eco bal` | 177 | gated by `Nodes.COMMAND_BALANCE` |
| `/eco pay` | 180 |  |
| `/eco sell` | 181 |  |
| `/eco ah` | 183 |  |
| `/eco auction` | 184 |  |
| `/eco offers` | 185 | `offers ah <id>`, `offers order <id>` (lines 1089-1096) |
| `/eco shop` | 186 |  |
| `/eco orders` | 187 | `request`, `search`, `list` |
| `/eco deliveries` | 188 |  |
| `/eco daily` | 189 |  |
| `/eco transactions` | 190 |  |
| `/eco toll` | 191 | `create`, `set`, `transfer`, `info`, `remove` (lines 217-228) |
| `/eco tag` | 192 | `tag <player>` (line 1190) |
| `/eco job` | 193 | gated by `Nodes.COMMAND_TAG` (line 193) |
| `/eco party` | 194 | gated by `Nodes.COMMAND_TAG` (line 195), and by `factions.enabled` |
| `/eco worth` | 196 | `worth <item> [amount]` - `WorthCommand.java:29-33` |
| `/eco gossip` | 198 | `status`, `refresh`, `test`, `dialogue [prof]`, `reload` (line 1369) |
| `/eco motd` | 199 |  |
| `/eco reload` | 200 | requires an admin node |
| `/eco addmoney` | 203 |  |
| `/eco setmoney` | 204 |  |
| `/eco removemoney` | 205 |  |
| `/eco removeplayer` | 206 |  |
| `/eco import` | 209 | registered only when `selection != Commands.CommandSelection.DEDICATED` (line 208) |

## Standalone commands (no `/eco` prefix)

Registered at `EconomyCommands.java:81-146`; gated by `standalone_commands` (default `true`).

`bal`, `pay`, `sell`, `ah`, `auction`, `offers`, `shop`, `orders`, `deliveries`, `daily`, `transactions`, `toll`, `tag`, `job`, `party`, `worth`

Admin standalines - `addmoney`, `setmoney`, `removemoney`, `removeplayer`, `gossip` - are gated by
`standalone_admin_commands` (default `false`).

## Absent from `README.md`

| Command | Should be added to |
|---|---|
| `/tag`, `/job`, `/party`, `/toll` | the screen-command list at `README.md:27` |
| `/eco admin` | the admin-command list at `README.md:120` |
| `/eco reload` | the admin-command list at `README.md:120` |
| `/eco motd` | the admin-command list at `README.md:120` |
| `/eco import` | the admin-command list at `README.md:120` |
| `/eco gossip` | the admin-command list at `README.md:120` |
| `/eco toll` | the admin-command list at `README.md:120` |
| `/eco tag` | the admin-command list at `README.md:120` |
| `/eco job` | the admin-command list at `README.md:120` |
| `/eco party` | the admin-command list at `README.md:120` |
| `/eco offers` | the admin-command list at `README.md:120` |

`README.md:27` also lists `/worth` with no arguments; the shipped form takes `worth <item> [amount]`.
