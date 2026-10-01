# Changelog

All notable changes to EconomyCraft are documented here. This file is the `changelog-file` consumed by
`.github/workflows/release.yml`, so a release published without updating it ships an empty changelog body.

## Unreleased — Faction & Profession System

Planning is tracked in `TODO.md`. **No gameplay behaviour has landed yet**; the baseline is 192 passing tests
on 26.3, both loaders green. Phases 0–2 are pure infrastructure and ship with zero behaviour change.

### Added
- Single central tax policy (`tax` package), replacing 19 duplicated `Math.round(base * taxRate)` sites.
- Party tags: Communism, Capitalism, Monarchy, Anarchism (default when nothing is chosen).
- Profession tags: Builder, Farmer, Miner, Merchant, Soldier.
- Online-time accumulator (`OnlineTimeService`), wall-clock cooldown service (`CooldownService`), and the
  `FactionStore` / `ProfessionStore` pair, persisted to `online_time.json`, `cooldowns.json`, `parties.json` and
  `professions.json`. Data only — no effect reads them yet.
- `BlockTags`: the config-driven building-block, ore, double-value-ore and Haste-trigger sets, with tags
  resolved lazily so a `/reload` is honoured and a bad entry is dropped with a warning instead of failing.
- `factions` and `professions` config sections, with the spec's defaults and clamping for every key.
- `container_lock` section and `ContainerLockMode`, with the defaults above. The lock itself is not yet
  enforced — that is Phase 10.

### Changed
- Config merge now covers nested sections, so an existing server gains the new keys without losing any
  hand-tuned values (`EconomyConfigMergeTest`).
- Nothing user-visible. The only behavioural changes in this phase are the four new data files existing at all.

### Fixed
- A hand-edited `"factions": null` no longer leaves the new sections null; they are rebuilt from defaults and the
  mistake is named in the log.

### Known gaps
- The 30-hour party and profession lockouts are independent, and each writes nothing until the player actually
  chooses, so "never chose" stays distinguishable from "chose Anarchism" on disk.
- Builder reach (`Thành thạo`) is stored and not applied — see `TODO.md` D11, the one rule still needing a
  designer call.
- `/eco settings` does not expose the new keys yet; they are file-only.

### Design decisions taken after the spec
- **Monarchy's daily tax** (`TODO.md` D19): Capitalism's formula, one change — inflation read off the server's
  total money rather than the player-activity signal — at `1.7%` instead of `5%`. The reference is per player, so
  the tax means the same thing on a 5-player and a 200-player server.
- **Haste is conditional** (D20): Builder I and Miner II apply only while breaking a block that job counts, and
  are removed the first tick they are not. `haste_duration_seconds` is replaced by `haste_refresh_seconds`,
  which is an anti-flicker window rather than a duration. The Builder's triggers union in `building_blocks`, so
  the 33-entry list is not duplicated into a second key.
- **Container locking is opt-in** (D10 closed): the server default is `UNLOCKED`, a player may lock their own
  container to `PRIVATE`, and Communism's `Cộng đồng` buff is what grants `PARTY_ONLY`. The lock itself is still
  Phase 10.

### Notes
- The pre-existing wealth tax (`FiscalPass`, `FiscalPolicy`, `fiscal.json`, `wealth_tax_*`) is deliberately
  untouched by this feature.
- **Supported versions.** The faction and profession system targets **Minecraft 26.3 only**. It still builds
  and runs on the other declared targets (1.21.1, 1.21.11, 26.1.2, 26.2) and on both Fabric and NeoForge, but
  these features are absent there — several vanilla classes this feature hooks (`AgeableMob`,
  `AbstractHorse`, `BreedGoal`, `LavaFluid`, `LivingEntity#hurtServer`,
  `ServerPlayerGameMode#destroyAndAck`, `server.players.NameAndId`) were renamed or reshaped after 1.21.11,
  and supporting them would mean forking all of them. Existing features are unaffected.
