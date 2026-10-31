# EconomyCraft

[![CI][ci-badge]][ci-workflow]
[![License: GPL-3.0][license-badge]](LICENSE)

**[EconomyCraft homepage][homepage]**

EconomyCraft is a server-side economy mod for Minecraft 26.3 on Fabric and NeoForge.
It adds balances, player trading, markets, factions, professions, quests, tolls,
and an API for other server-side mods while keeping the client unmodified.

## Supported versions

| Minecraft | Java | Loaders |
| --- | --- | --- |
| 26.3 | 25 | Fabric, NeoForge |

Minecraft 26.3 is the only supported target. Install the jar that matches your server
loader; do not install both loader jars on the same server.

## Installation

1. Download the matching asset from the [latest GitHub release][release]:
   - Fabric: `economycraft-fabric-<version>_26.3.jar`
   - NeoForge: `economycraft-neoforge-<version>_26.3.jar`
2. Place the jar in the server's `mods/` directory.
3. Run the server with Java 25 and the matching Fabric or NeoForge installation.

Fabric servers also need Fabric API. Players do not install EconomyCraft or any
other client mod. The API is bundled in the normal EconomyCraft jars; there is no
separate runtime API mod.

### Optional integrations

- [ShopGuard][shopguard] is an optional Fabric-only dependency for
  land-claim-dependent faction effects. Claims remain owned by ShopGuard.
- Placeholder API is an optional integration for server placeholders.

## Features

- Balances, payments, daily rewards, leaderboards, configurable shop and selling,
  and dynamic prices.
- Auctions, player orders, offers, and escrow-backed contracts.
- Factions, professions, server quests, tolls, and private villager dialogue.
- Public API v1 and an optional HTTP market API for integrations.

See the [gameplay guides][gameplay] for player-facing behavior and the
[server administration guide][administration] for commands and permissions.

## Documentation

- [Installation and compatibility][installation]
- [Gameplay guides][gameplay]
- [Server administration][administration]
- [Configuration reference][configuration]
- [Backups and migration][backups]
- [API overview][api-overview]
- [API getting started][api-getting-started]
- [API reference][api-reference]
- [HTTP market API][http-api]
- [Latest release][release]

Configuration is stored under `config/economycraft/` on a dedicated server. The
configuration guide documents the shipped keys and defaults. The backups guide covers
the SQLite database and legacy import behavior.

## Repository layout

| Path | Purpose |
| --- | --- |
| `api/` | Public API v1 types and entry point. |
| `common/` | Shared economy logic, persistence, commands, and tests. |
| `fabric/` | Fabric entrypoints and Fabric-specific hooks. |
| `neoforge/` | NeoForge entrypoints and NeoForge-specific hooks. |
| `wiki/` | Canonical player, server-owner, and integrator documentation. |
| `docs/contributing/` | Contributor and architecture guidance. |
| `website/` | Docusaurus documentation site. |

Shared behavior lives in `common/`; loader differences stay in the matching
Fabric or NeoForge module. The mod has no custom client packets or custom menu
type, so its UI works with an unmodified vanilla client.

## Development

The project uses Gradle and Java 25. CI is the build authority for routine checks.
Invoke Gradle locally only for an exceptional rollback when the CI artifact has
expired and no release exists. For that exception, use these commands:

```sh
./gradlew :common:test
./gradlew build
./gradlew -Pfilter_platforms=fabric :fabric:build
./gradlew -Pfilter_platforms=neoforge :neoforge:build
```

Pull requests run common tests and both loader jar builds in GitHub Actions. Deployable
jars come from CI artifacts or GitHub Releases. GitHub Releases use automatically
generated notes from merged pull requests; clear, user-facing PR titles become the
release summary.

For contributor rules, read the [architecture guide](docs/contributing/architecture.md),
[documentation guidance](docs/contributing/documentation.md), and
[toll contributor notes](docs/contributing/tolls.md).

## License and upstream

EconomyCraft is licensed under the [GNU General Public License v3.0](LICENSE).
This repository is an enhanced fork of [PhilipB06/EconomyCraft][upstream].

[ci-badge]:
  https://github.com/TraiNguyenVan/EconomyCraft/actions/workflows/ci.yml/badge.svg
[ci-workflow]:
  https://github.com/TraiNguyenVan/EconomyCraft/actions/workflows/ci.yml
[license-badge]:
  https://img.shields.io/badge/License-GPL--3.0-blue.svg
[homepage]: https://trainguyenvan.github.io/EconomyCraft/
[release]: https://github.com/TraiNguyenVan/EconomyCraft/releases/latest
[shopguard]: https://github.com/TraiNguyenVan/shopguard
[gameplay]: https://trainguyenvan.github.io/EconomyCraft/docs/Gameplay
[administration]:
  https://trainguyenvan.github.io/EconomyCraft/docs/Server-Administration
[installation]:
  https://trainguyenvan.github.io/EconomyCraft/docs/Installation-and-Compatibility
[configuration]:
  https://trainguyenvan.github.io/EconomyCraft/docs/Server-Configuration
[backups]: https://trainguyenvan.github.io/EconomyCraft/docs/Backups-and-Migration
[api-overview]: https://trainguyenvan.github.io/EconomyCraft/docs/Home
[api-getting-started]:
  https://trainguyenvan.github.io/EconomyCraft/docs/Getting-Started
[api-reference]: https://trainguyenvan.github.io/EconomyCraft/docs/API-Reference
[http-api]: https://trainguyenvan.github.io/EconomyCraft/docs/HTTP-Market-API
[upstream]: https://github.com/PhilipB06/EconomyCraft
