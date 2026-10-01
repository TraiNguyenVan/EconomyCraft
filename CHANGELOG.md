# Changelog

All notable changes to EconomyCraft are documented here. This file is the `changelog-file` consumed by
`.github/workflows/release.yml`, so a release published without updating it ships an empty changelog body.

## Unreleased — Faction & Profession System

Planning is tracked in `TODO.md`. No gameplay code has landed yet; the baseline is 61 passing tests
(`FiscalPolicyTest` 40, `TollUiTest` 21) on 26.3, both loaders green.

### Added (planned)
- Single central tax policy (`tax` package), replacing 18 duplicated `Math.round(base * taxRate)` sites.
- Party tags: Communism, Capitalism, Monarchy, Anarchism (default).
- Profession tags: Builder, Farmer, Miner, Merchant, Soldier.
- Online-time accumulator, wall-clock cooldown service, party/profession stores.
- Container lock (`Cộng đồng`) for all `Container` blocks.
- Inflation-responsive Capitalism daily rate (faction wealth concentration × server inflation).
- Optional ShopGuard integration for the four claim-dependent faction effects.

### Changed (planned)
- Nothing yet. Phases 0–2 are pure infrastructure and must ship with zero behaviour change.

### Fixed (planned)
- Nothing yet.

### Notes
- The pre-existing wealth tax (`FiscalPass`, `FiscalPolicy`, `fiscal.json`, `wealth_tax_*`) is deliberately
  untouched by this feature.
- **Supported versions.** The faction and profession system targets **Minecraft 26.3 only**. It still builds
  and runs on the other declared targets (1.21.1, 1.21.11, 26.1.2, 26.2) and on both Fabric and NeoForge, but
  these features are absent there — several vanilla classes this feature hooks (`AgeableMob`,
  `AbstractHorse`, `BreedGoal`, `LavaFluid`, `LivingEntity#hurtServer`,
  `ServerPlayerGameMode#destroyAndAck`, `server.players.NameAndId`) were renamed or reshaped after 1.21.11,
  and supporting them would mean forking all of them. Existing features are unaffected.
