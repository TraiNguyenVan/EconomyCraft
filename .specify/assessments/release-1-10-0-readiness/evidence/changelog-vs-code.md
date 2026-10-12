# CHANGELOG.md vs working tree — 1.10.0 release-note readiness

Audit scope: whether the `## Unreleased` section of `CHANGELOG.md` is accurate against the working tree and
usable as the published 1.10.0 release body. Read-only; no files changed except this one. Every claim below
cites `path:line`. Where history is unavailable (single squashed commit, no `git` allowed) this is stated
explicitly.

## Summary verdict

**Not ready.** The content is mostly accurate but the file cannot ship as-is.

1. **No version heading exists.** The file has exactly one `##` heading, `## Unreleased` (`CHANGELOG.md:6`);
   there is no `## 1.10.0`. `.github/workflows/release.yml:201` passes `changelog-file: CHANGELOG.md` to
   mc-publish, and the release is versioned `1.10.0` (`gradle.properties:6`, `release.yml:198`), so the
   published Modrinth/CurseForge body is a document headlined "Unreleased".
2. **One factual command error.** The changelog tells players to use `/eco party reset` (`CHANGELOG.md:84`);
   no such subcommand exists — the registered command is `/eco party leave` (`EconomyCommands.java:1620`).
3. **A shipped feature is missing.** Server Quests are user-visible, documented in `README.md:153-160` and
   `wiki/Server-Quests.md:1`, and the changelog itself names "the quest-engine market build" as the layer the
   SQLite change sits on (`CHANGELOG.md:38`) — yet no quests entry appears in `## Unreleased`.
4. **The contracts entry omits real money behaviour.** A contract payout is taxed
   (`ContractService.java:513`, scope `TaxScope.TRANSACTION_CONTRACT` at `TaxScope.java:31`) and the contractor
   receives `amount - tax` (`ContractService.java:514`); the changelog says only that the requester "release[s]
   the payment" (`CHANGELOG.md:11-12`). The wiki states it correctly ("minus any configured tax",
   `wiki/Contracts.md:29`).
5. **Doc-rule violations** are present in the exact text that would be published (raw test count, spec ids,
   task-plan/assumption language, internal server paths) — see §4.

---


| # | Claim (`CHANGELOG.md`) | Verdict | Evidence |
|---|---|---|---|
| A1 | `/eco party reset` restores the default (line 84) | **Wrong** | No `reset` literal is registered anywhere (`grep` for `literal("reset")` = none; `EconomyCommands.java:1613-1645` builds only `party`, `leave`, and the four faction keys). The registered command is `leave` (`EconomyCommands.java:1620`). The same wrong name is echoed in a code comment (`tax/FactionTaxRules.java:123`). Line 228 of the same file says `/eco party leave` — the changelog contradicts itself. |
| A2 | "The faction and profession system targets Minecraft 26.3 only … these features are absent there" (lines 321-326) | **Unverified / likely wrong** | The faction and profession classes live in the shared source set `common/src/main/java/com/reazip/economycraft/{faction,profession}/` (e.g. `faction/FactionId.java:1`), and their mixins live in the shared platform sets `fabric/src/main/java/.../fabric/mixin/` and `neoforge/src/main/java/.../neoforge/mixin/`, listed unconditionally in the mixin configs (`fabric/src/main/resources/economycraft.mixins.json:6-21`, `neoforge/src/main/resources/economycraft.mixins.json:6-16`). `build.gradle:35-41` only swaps the placeholder-API source dir per target; there is no per-version mixin filter. `ProfessionCombatMixin` injects into `hurtServer` (`fabric/.../mixin/ProfessionCombatMixin.java:29`), a 26.x method name, with `defaultRequire: 1` (`economycraft.mixins.json:23-25`) and no `require = 0` anywhere. Either older targets crash on load or a filter I could not find exists. **Cannot be settled without a build, which this audit is forbidden to run.** |
| A3 | Contracts: escrow, one acceptor, approve-to-release (lines 10-12) | True | `ContractService.java:106-157` (escrow recorded before debit), `:184-215` (accept only from `OPEN`, sets `IN_PROGRESS`). |
| A4 | Contracts are public or targeted; category, deadline, revision budget (line 12) | True | `ContractService.java:121-123`, `:133`, `:139`; `contracts/Contract.java:59-76` (Category), `:28-30` (Status). |
| A5 | `/contracts`, `/eco contracts`, headless `view/accept/submit/approve/revise/cancel/dispute` (lines 13-14) | True but incomplete | Registered at `EconomyCommands.java:95` (standalone), `:190` (`/eco contracts`), tree at `:1158-1221`. `mine`, `new`, `actions` and `admin list` also exist (`:1162`, `:1164`, `:1170`, `:1208`) and are documented in `README.md:76-84` / `wiki/Contracts.md:11-16,55-56` but absent from the changelog. |
| A6 | Cancel-open refunds at once; after acceptance both sides agree (line 16) | True | `ContractService.java:344-380` (`OPEN` → immediate refund; `IN_PROGRESS/SUBMITTED` → two-party confirm). |
| A7 | Deadlines expire+refund; unreviewed submissions auto-approve (lines 17-18) | True | `ContractService.java:601-623` (expire), `:629-638` (autoApprove). |
| A8 | Disputes freeze until admin resolves with `/contracts admin resolve <id> pay\|refund` (lines 18-19) | True | `ContractService.java:388-422` (freeze), `:429-452` (admin resolve); command at `EconomyCommands.java:1206-1220`. |
| A9 | Failed payouts/refunds stay pending and retry without paying twice (lines 19-20) | True | `ContractService.java:32-43` (settlement protocol), `:520-551` (durable intent, revert on failure), `:567-597` (sweep). |
| A10 | Contract payout is untaxed (implied by lines 10-12, 20) | **Misleading by omission** | Payout tax: `ContractService.java:508-515`; new scope `TaxScope.java:31`; the contractor is told "minus tax" (`ContractService.java:469-470`). No mention anywhere in the changelog. |
| A11 | 8 contract config keys (lines 21-23) | True | `config.json:22-29`; `EconomyConfig.java:64-79`; clamp-and-warn at `EconomyConfig.java:192-203,282-287`. |
| A12 | Permission nodes `economycraft.command.contracts`, `economycraft.admin.contracts` (lines 23-24) | True | `EconomyPermissions.java:46`, `:23`; auto-registered on NeoForge via `COMMAND_ALL`/`ADMIN_ALL` loops (`EconomyCraftNeoForgePermissions.java:28-34`). |
| A13 | One SQLite file `economycraft.db`; JSON docs as rows in `documents`; gossip's three tables in the same file (lines 26-32) | True | `db/EconomyDatabase.java:40` (`DB_FILE_NAME`), `:105-110` (`documents`), `:120-160` (villagers/player_memories/villager_trades); gossip DB opened on the same path (`EconomyCraft.java:137-139`). |
| A14 | "Writes are synchronous on the server thread" (line 32) | True | `db/EconomyDatabase.java:27-30`. |
| A15 | First-boot import once (empty-table guard) then archive under `data/_migrated-json/`, never deleted (lines 34-35) | True | `db/EconomyDatabase.java:289-322` (per-document `hasDocument` guard), `:330-368` (`villagers` COUNT guard), `:370-379` (`archiveQuietly` moves into `_migrated-json`, name `:43`). |
| A16 | `/eco import` carries `economycraft.db`; old JSON-only folders still import on boot (lines 36-38) | True | `util/EconomyPaths.java:40-56` lists legacy JSON names **and** `economycraft.db`; boot import at `EconomyDatabase.java:289`. |
| A17 | Quest fields (`backfillKeys`, `lastPeriodKeys`, `autoMarketBlacklist`, `recentPurchases`, `unsoldExpiries`) persist (lines 38-40) | True (fields exist) | `quests/QuestManager.java:64-72`; `quests.json` is a document key (`EconomyDatabase.java:65`). |

## 1. Accuracy findings

Severity: **High** = wrong/misleading to a player; **Med** = incomplete or stale; **Low** = cosmetic.
| A18 | `gemini_gossip` lost `public_chat`, `public_chat_chance`, `refresh_interval_minutes`, `system_instruction`, `pool_size_per_category`; "14 keys → 9" (lines 49-50) | True; the "14" is unverifiable | Live section has exactly 9 keys (`config.json:245-255`), matching `GossipConfig.java:21-31`. Removed keys are ignored on read (`GossipConfig.java:244-255`) and a test pins that (`GossipEmpiricalStressTest.java:173-190`). The prior count cannot be checked without history; 9 + 5 = 14 is self-consistent. |
| A19 | `/eco gossip refresh` and `/eco gossip test` removed → "Unknown subcommand"; `status`/`dialogue`/`reload`/`memory inspect\|clear` unchanged (lines 56-58) | True | `EconomyCommands.java:1726-1755` builds only `status`, `dialogue`, `reload`, `memory inspect\|clear`. |
| A20 | `status` no longer reports a rumor count/refresh interval; circuit-breaker read from the client (lines 59-60) | True | `EconomyCommands.java:1830-1846`. |
| A21 | Rumor pool / categories / digest worker / single-rumor generator deleted; prompt loses the "fellow villagers" section (lines 51-55) | True | No `Rumor*`/`*Digest*`/pool classes remain under `gossip/`; prompt builder has no such section (`gossip/memory/VillagerDialoguePromptBuilder.java:44-95`). |
| A22 | Deliberately kept: archetype anonymization, sanitizer, recently-spoken tracker (lines 65-67) | True | `TransactionAnonymizer` used at `gossip/memory/VillagerMemoryService.java:130`; `gossip/RecentSpokenTracker.java:15`. |
| A23 | `FactionId.defaultFaction()` is Anarchism (line 71) | True | `faction/FactionId.java:58-60`. |
| A24 | `TagDisplayService.TagSource` loses `hasChosenFaction` (line 81) | True | `tag/TagDisplayService.java:78-85` has only `factionOf`/`professionOf`/`levelOf`; `hasChosenFaction` matches nowhere in the tree. |
| A25 | `FactionStore.hasChosen` and `FactionApi.hasChosen` still exist (lines 87-88) | True | `faction/FactionStore.java:96`; `api/v1/EconomyCraftApiBootstrap.java:222`. |
| A26 | Undecided player is exempt from every tax scope (line 77) | True | `tax/FactionTaxRules.java:69-85` + `:127-131` (`factionOf` returns the default for no record: `faction/FactionStore.java:84-88`). |
| A27 | Undecided player wears `Ⓐ` and gets Anarchism's speed (lines 79, 82) | True | `tag/TagDisplayService.java:293-299`; `faction/FactionEffects.java:199`. |
| A28 | Land side lives in ShopGuard (lines 93-94) | **Assumption** — external repo, not verifiable here. |
| A29 | Offers hub `/eco offers` (+ `/offers`), deep links, permission node (lines 103-118) | True | `EconomyCommands.java:1092-1099` (`offers`, `ah`, `order`), `:91`/`:187` (registration), node `COMMAND_OFFERS` = `economycraft.command.offers` (`EconomyPermissions.java:45`). |
| A30 | `NegotiationStore.offersByProposer` added; `countOffersOnPlayerTargets` removed (lines 113, 123) | True | `negotiation/NegotiationStore.java:100`; `countOffersOnPlayerTargets` matches nowhere. |
| A31 | Container locks removed in full (lines 125-142) | True | No `ContainerLock*`, `container_lock`, `container_locks`, or `ConfigClamp.choice` match anywhere under `common/`, `fabric/`, `neoforge/`; no `lock` literal in `EconomyCommands.java`; no `InteractionEvent`/`BlockEvent` in `EconomyCraft.java`. |
| A32 | Faction/profession historical section (lines 147-326) | **Not fully audited** | Out of the primary scope; spot checks (A23-A27) hold. Note it is inside `## Unreleased`, so it would publish too. |


---

## 2. Missing-from-changelog findings

Shipped, user-visible changes present in the tree with no entry in `## Unreleased`.

| # | Missing item | Evidence | Severity |
|---|---|---|---|
| M1 | **Server Quests** as a feature (board on `/orders`, period/budget, buyback listings, admin screen, ~17 config keys). | Feature: `quests/QuestManager.java:52`, `admin/AdminQuestsUi.java:39`, `config.json` `quests` section (17 keys), admin button `admin/AdminUi.java:87`. Documented: `README.md:153-160`, `wiki/Server-Quests.md:1`, `wiki/_Sidebar.md:11`. Changelog references it only in passing at `CHANGELOG.md:38`. | **High** |
| M2 | Contract payout is taxed; new scope `TaxScope.TRANSACTION_CONTRACT`; new mutation sources `economycraft:contract_escrow_hold` / `contract_payout` / `contract_escrow_refund`. | `ContractService.java:508-518`, `tax/TaxScope.java:31`, `EconomySources.java:18-20`. Not mentioned in `CHANGELOG.md:8-24`. | **Med** |
| M3 | Contract subcommands `mine`, `new`, `actions`, `admin list [status]`. | `EconomyCommands.java:1162,1164,1170,1208`; documented `README.md:76,83`, `wiki/Contracts.md:16,55`. | **Med** |
| M4 | Only the contracts admin/command permission pair is named; the offers node is named (`CHANGELOG.md:118`) but the two admin nodes added this cycle are not listed together. | `EconomyPermissions.java:23,46`; README lists them (`README.md:224,242`). | Low |
| M5 | `api/v1` additions beyond what line 271 names: `FactionApi.hasChosen` and `defaultFactionId`, plus the `FactionIds` constants. | `api/v1/FactionApi.java:54,57`; `api/v1/EconomyCraftApiBootstrap.java:222-229`. Line 271 mentions "party info, party display names, and claim cost multipliers" only. | Low |
| M6 | New mixins not enumerated (the whole `Profession*` set, `TabListDisplayNameMixin`, and the Fabric-only `SignBlockEntityMixin`/`AnvilMenuMixin`/`BookEditMixin`). | `fabric/src/main/resources/economycraft.mixins.json:6-21`; `neoforge/src/main/resources/economycraft.mixins.json:6-16`. | Low |
| M7 | `&x` colour-code translation for signs, anvil renames and books (`AmpersandFormat`), Fabric-only. | `util/AmpersandFormat.java:54`; `fabric/.../mixin/SignBlockEntityMixin.java:12-24`, `AnvilMenuMixin.java:14-22`, `BookEditMixin.java:20-31`. **Assumption:** cannot confirm this shipped in 1.10.0 rather than 1.9.0 (no history). | Low |
| M8 | MOTD blocks (`motd.blocks`, `/eco motd`). | `config.json` `motd` section; `EconomyCommands.java:202`; `README.md:147-150,194`. Same assumption as M7. | Low |

**Checked and found NOT missing:** command-tree additions (`/contracts`, `/offers`, `/eco offers ah|order`) are all
present; no config key in `config.json` contradicts the changelog's contract/gossip lists
(`config.json:22-29,245-255` vs `EconomyConfig.java:64-79`, `GossipConfig.java:21-31`); `MutationSource`
(`api/v1/MutationSource.java`) gained no new helpers (only `of`/`asString` exist); the only data migrations are
`economycraft.db` + `villagers.db`, both documented.


## 3. Release-body mechanics (completed by the lead after the audit run ended)

The audit's own summary promised this section; it was written after the teammate's run ended, using the
teammate's findings plus the lead's checks.

- `mc-publish`'s `changelog-file` input is documented as "a glob pointing to the changelog file" — the file's
  content is used verbatim, with no section extraction (Kir-Antipov/mc-publish README, `changelog-file`
  section, and its input table row). `.github/workflows/release.yml:201` sets `changelog-file: CHANGELOG.md`.
- What would publish today: `# Changelog` (`CHANGELOG.md:1`), the four-line preamble that names the workflow
  (`CHANGELOG.md:2-4`), then `## Unreleased` (`CHANGELOG.md:6`) and every `###` section beneath it — including
  `### Known gaps` (`CHANGELOG.md:177`), `### Design decisions taken after the spec` (`CHANGELOG.md:282`) and
  `### Notes` (`CHANGELOG.md:317`).
- There is no version heading: the only `##` heading in the file is `## Unreleased`. A store page would
  therefore carry release notes titled "Unreleased" under version `1.10.0`.

## 4. Doc-rule violations in text that would publish

The constitution forbids raw test counts in documents and forbids phase numbers, task identifiers or internal
decision IDs in user-facing documents, and requires documentation to describe only shipped behaviour
(`.specify/constitution.md:118-124`). Checks run by the lead over `CHANGELOG.md`:

- **Internal spec identifiers — present.** "Monarchy `Phép vua thua lệ làng` (spec 28)" (`CHANGELOG.md:272`)
  and "Anarchism `Thoải mái` (spec 35)" (`CHANGELOG.md:273`). These are internal decision identifiers in the
  file that becomes the public release body.
- **Internal planning content — present.** `### Known gaps` (`CHANGELOG.md:177-181`) and
  `### Design decisions taken after the spec` (`CHANGELOG.md:282-289`), including class-level implementation
  detail such as `player.block_interaction_range` and `LocalPlayer.raycastHitResult`.
- **Raw test counts — none found.** A grep for test-count phrasing over `CHANGELOG.md` returned no matches;
  the claim in the audit's summary that a raw test count appears here is **not corroborated** and is not
  repeated.
- **The file contradicts the build.** The "Supported versions" note asserts that the faction and profession
  system "still builds and runs on the other declared targets (1.21.1, 1.21.11, 26.1.2, 26.2)"
  (`CHANGELOG.md:321-326`). The release-path builds for those four targets all fail in `:common:compileJava`
  (`evidence/build-matrix.md`), so this published sentence would be false in the release notes.

