# Evidence: code health and invariant compliance

Collected 2026-10-12 against the working tree at `mod_version 1.10.0` (unreleased; `gradle.properties:6`),
read-only. Scope: `AGENTS.md` "High-risk checks" (`AGENTS.md:65-74`) and the principles in
`.specify/memory/constitution.md`. Every claim below cites `path:line`. Where a check could not be closed by
reading the tree, it is marked **[assumption]**.

## Summary verdict

The money engine, tax routing, mutation-source naming and the SQLite persistence seam are in good shape:
every trade-tax call site routes through `tax/TaxPolicy` + `TaxScope`, every fiscal levy/rebate is registered
in `FISCAL_SOURCES`, and no mod state is written as NBT. One **VIOLATION** of the constitution's public-API
thread contract was found (`FactionApiImpl.defaultFactionId()`). The remaining findings are **RISK** (plausible
defects) and **NIT** (hygiene). Two are worth a maintainer decision before 1.10.0: the second
`EconomyDatabase` connection in `TollManager`, and the Fabric-only `&x`-format mixins with no recorded
NeoForge decision.

## Violations

**V1 — `defaultFactionId()` is a public `api/v1` path that never calls `requireServerThread()`.**
`common/src/main/java/com/reazip/economycraft/api/v1/EconomyCraftApiBootstrap.java:228-230` returns
`FactionId.defaultFaction().key()` directly. Every sibling in the same class goes through `store()`
(`EconomyCraftApiBootstrap.java:200-205`, which throws off-thread) or `manager()` (`:125-131`). The interface
doc states the rule explicitly: "Like the rest of the API, every method must be called from the server thread."
(`api/src/main/java/com/reazip/economycraft/api/v1/FactionApi.java:18`); constitution principle II
(`.specify/memory/constitution.md:28-35`) and `AGENTS.md:20-21` say "every public `api/v1` path must call
`requireServerThread()`".
*Impact:* an off-thread consumer (the ShopGuard bridge is the named consumer of this interface) gets a value
instead of the contract's `IllegalStateException`; blast radius is small today because the method returns a
constant, but the "every path throws off-thread" guarantee is broken at a published interface.

## Risks

**R1 — A second `EconomyDatabase` connection opens the same SQLite file.**
`common/src/main/java/com/reazip/economycraft/TollManager.java:46-53` builds its own
`new EconomyDatabase(dataDir.resolve(EconomyDatabase.DB_FILE_NAME))` and calls `initialize()` +
`importLegacy(dataDir)` again, separately from `EconomyManager`'s instance
(`EconomyManager.java:167`). Constitution VI (`.specify/memory/constitution.md:66-74`) and `AGENTS.md:31-34`
describe **one** database via `db/EconomyDatabase`, and `EconomyManager.save()`'s single-transaction protocol
(`EconomyManager.java:570-589`) only covers documents written through *its* connection. `EconomyDatabase` is
`synchronized` per instance (`db/EconomyDatabase.java:27-35`, `:255`), so two instances do not share a lock.
*Impact:* the toll document is written on a different connection than the balance/escrow documents, so the
"contract state and the money movement commit atomically" property does not extend to tolls; a crash between
the two writes can leave them out of step. Both callers are on the server thread, so there is no live write
contention — this is a durability/atomicity gap, not a data race.

**R2 — Placeholder display helpers create balances on read.**
`common/src/main/java/com/reazip/economycraft/util/PlaceholderValues.java:12-22` calls
`economy.getBalance(playerId, true)` for the `balance`, `balance_formatted` and `balance_short` placeholders.
Those are display paths (tab list / scoreboard / placeholder-api output), reached from
`fabric/src/*/java/com/reazip/economycraft/fabric/EconomyCraftFabricPlaceholders.java:17,23,29` and the
NeoForge equivalents. The house rule is the opposite and is written down next door:
`common/src/main/java/com/reazip/economycraft/util/MenuUiSupport.java:240-242` ("This is a display helper.
Rendering a picker/menu must never create a new account.") reads with `getBalance(playerId, false)` and falls
back to the configured starting balance. Today every caller passes an online player's UUID, so no account is
conjured from an unverified name — but the helper itself is create-on-read.
*Impact:* `AGENTS.md:73` ("Display helpers must not create balances") is satisfied only by the accident that no
current placeholder takes an arbitrary UUID; a future offline-player placeholder would mint the starting
balance.

**R3 — `api/v1` `getBalance` conjures a starting-balance account for any UUID.**
`common/src/main/java/com/reazip/economycraft/api/v1/EconomyCraftApiBootstrap.java:133-135` forwards to
`getBalance(playerId, true)`; `api/src/main/java/com/reazip/economycraft/api/v1/BalanceApi.java:6` has no
javadoc constraining it. This is the same create-on-read shape the engine deliberately uses for a first
`addMoney` (`BalanceMutationEngine.java:41-50`, `:168-177`), so it is by design — but it is the one public
surface where an external mod can turn an arbitrary (unverified) UUID into a funded account.
*Impact:* an external consumer (e.g. the ShopGuard bridge) can mint a starting balance for a name it cannot
verify; the "never conjure an account from an unverified name" guard in `AGENTS.md:73` is not enforced at this
boundary.


**R4 — Escrow-refund sources are in neither `TRADE_SOURCES` nor `FISCAL_SOURCES`, so refunds count as "earned".**
`EconomyManager.recordStats` (`common/src/main/java/com/reazip/economycraft/EconomyManager.java:606-621`) skips
fiscal sources and marks trade sources, then credits any positive delta to `earned`. The refund sources
`ORDER_ESCROW_REFUND` (`EconomySources.java:17`) and `CONTRACT_ESCROW_REFUND` (`:20`) are used as real
mutations — `orders/OrderFulfillment.java:322`, `contracts/ContractService.java:518` — but appear in neither
set (`EconomyManager.java:73-90`). `QUEST_BUYBACK` (`EconomySources.java:23`, used at
`auction/AuctionTrade.java:76`) is likewise absent, so a buyback purchase is not counted as `bought`.
*Impact:* `LeaderboardCategory.EARNED` ("Who has earned the most money, from any source",
`LeaderboardCategory.java:5`) inflates by every cancelled/expired order and contract refund, and the
`BOUGHT`/`TRADED` categories miss bot buybacks. Not a documented-invariant breach (refunds are not levies), but
the totals the constitution's principle IV exists to protect are affected.

**R5 — Loader-decision gap: three Fabric-only mixins have no NeoForge counterpart and no recorded decision.**
`fabric/src/main/resources/economycraft.mixins.json:9-15` registers `SignBlockEntityMixin`, `AnvilMenuMixin`
and `BookEditMixin`; `neoforge/src/main/resources/economycraft.mixins.json` has no equivalent. These three are
the whole `&x` Bedrock-format-code feature (`fabric/.../mixin/SignBlockEntityMixin.java:3,47-49`,
`AnvilMenuMixin.java:36-37`, `BookEditMixin.java:55-65`). `AGENTS.md:37-38` ("Make an explicit loader decision
for every new hook") and constitution §Constraints (`.specify/memory/constitution.md:115-117`) require the
asymmetry to be deliberate and recorded; no javadoc, comment or doc records a decision for these three.
*Impact:* the `&x` feature silently does not exist on NeoForge, and the deliberate-vs-accidental status of that
gap is unrecorded. Note also that `docs/contributing/architecture.md:12-15` enumerates only
`TollPressurePlateMixin`/`TollBasePressurePlateMixin`/`TollHopperMixin` as Fabric-only, so the doc's mixin
inventory is already stale.

**R6 — Shared `economycraft.mixins.json` hard-codes `"compatibilityLevel": "JAVA_25"` for Java-21 targets.**
`fabric/src/main/resources/economycraft.mixins.json:5` and `neoforge/src/main/resources/economycraft.mixins.json:5`
set `JAVA_25`; the file is a single `src/main/resources` file with no per-target substitution
(`grep` for `compatibilityLevel` finds no build-script handling in `build.gradle`, `fabric/build.gradle`,
`neoforge/build.gradle`). `AGENTS.md:52-54` lists 1.21.1 and 1.21.11 as Java 21 targets.
*Impact:* **[assumption]** — a class-file/compatibility-level mismatch on the Java-21 targets is plausible; I
could not run a build to confirm (a build sweep was already in flight), so this is flagged rather than asserted.

**R7 — Version-fragile mixin targets.**
`fabric/.../mixin/SignBlockEntityMixin.java:31-38` pins an `@ModifyArg` to the exact internal target
`SignBlockEntity.updateText(Ljava/util/function/UnaryOperator;Lnet/minecraft/world/level/block/entity/SignTextSlot;)Z`,
and `BookEditMixin.java:38-40` injects into `ServerGamePacketListenerImpl.signBook(FilteredText, List<FilteredText>, int)`
(1.21.x `FilteredText`/`Filterable` shapes). `neoforge/.../mixin/ProfessionBreakMixin.java:26-42` shadows
`ServerPlayerGameMode.player` and injects `destroyBlock(BlockPos)` at HEAD/RETURN, relying on the 26.3 signature
(the javadoc at `:24-26` says so). These are single-version signature pins in a shared mixin set.
*Impact:* a vanilla rename or signature change on any supported target turns these into hard mixin-apply
failures; the 26.3-only-API compile breakage already recorded in `evidence/build-matrix.md` shows the tree is
currently sensitive in exactly this way.


## Nits

**N1 — Duplicate dependency declaration.** `common/build.gradle:39-40` declares
`implementation 'org.xerial:sqlite-jdbc:3.49.1.0'` twice, identically. *Impact:* none at runtime; hygiene only.

**N2 — Unused imports in build scripts.** `fabric/build.gradle:1` and `neoforge/build.gradle:1` both
`import org.gradle.api.GradleException` and never reference it (the root `build.gradle` uses it, at `:41,57,197,207,273,283`).
*Impact:* dead imports; no behaviour change.

**N3 — `IMPORT_TAX` is registered but never used as a mutation source.**
`EconomySources.IMPORT_TAX` (`EconomySources.java:30`) is listed in `FISCAL_SOURCES`
(`EconomyManager.java:87`) but no `addMoney`/`removeMoney`/`transferMoney` call in the tree passes it (only the
registration mentions it). The Monarchy import surcharge is computed inside the tax quote
(`tax/FactionTaxRules.java:95-102`) and burned as part of the scope's tax, attributed to the trade source.
*Impact:* a dead allow-list entry; harmless for totals (nothing is mis-attributed), but it advertises a source
that does not exist.

**N4 — `dailySellLimit` is a numeric key with no clamp-and-warn.**
`EconomyConfig.dailySellLimit` (`EconomyConfig.java:30`) has a bundled default (`config.json:4`) but is absent
from the load-time clamp list (`EconomyConfig.java:177-212`) and is read raw at `EconomyManager.java:1166,1181`.
Constitution V (`.specify/memory/constitution.md:57-64`) requires clamp-and-warn on numeric keys.
*Impact:* a negative value silently behaves as "no limit" (`limit <= 0` at `EconomyManager.java:1167`) with no
warning. `startingBalance` is in the same load-time gap but is clamped at point of use
(`BalanceMutationEngine.java:175-177`, `EconomyManager.java:1097`), so it is lower priority.

**N5 — Admin `set` ignores the mutation result.** `EconomyCommands.java:655,667,703,734` and
`admin/AdminPlayersUi.java:228` call `setMoney(...)` and discard the `BalanceMutationResult`; a rejected set
(`BalanceMutationEngine.java:83-86`, `MAX_BALANCE_EXCEEDED`) still reports success to the admin. *Impact:*
misleading feedback only; no money moves.

**N6 — `getLeaderboardEntry(rank < 1)` returns before the thread check.**
`EconomyCraftApiBootstrap.java:332-336` returns `Optional.empty()` for `rank < 1` without calling
`requireServerThread()`; `rank >= 1` delegates to `getLeaderboardEntries` which does check
(`:318-321`). *Impact:* a narrow hole in the same contract as V1; benign because nothing is read.

**N7 — Swallowed exceptions (empty catch) on best-effort and reflective paths.** Representative sites:
`EconomyCommands.java:1869` and `gossip/GossipApiClient.java:49` catch `Throwable` and discard;
`EconomyManager.java:416,438` catch `RuntimeException` and discard; the `util/*Compat.java` and
`profession/*Effects.java` files contain many `catch (... ignored) {}` blocks (e.g.
`util/ProfileComponentCompat.java:79,108,132,163`, `profession/SoldierEffects.java:48,80,104,172`,
`profession/FarmerEffects.java:50,176,211`). All are deliberately named `ignored` and sit on reflective or
best-effort paths, but none logs, so a silent failure there is invisible. *Impact:* diagnosability only.

**N8 — `EconomyCraftApiImpl` exists as a class, contradicting a normative statement.**
`constitution.md:34` ("There is no `EconomyCraftApiImpl`; that name appears in older drafts only") and
`AGENTS.md:21` ("not an `EconomyCraftApiImpl`") are literally false: the class is declared at
`EconomyCraftApiBootstrap.java:31` (package-private, same file). The intent — the *file* is
`EconomyCraftApiBootstrap.java` — is correct, but the wording will mislead a reader grepping for the name.
*Impact:* documentation accuracy only.

**N9 — Dangling `TODO.md` references.** `tag/package-info.java:7,33`, `profession/package-info.java:22`,
`faction/package-info.java:33`, `time/package-info.java:5` and `faction/FactionRulesTest.java:392` cite a
`TODO.md` that no longer exists in the tree. *Impact:* the "decisions live in TODO.md §4/§5/§7" breadcrumbs
lead nowhere.


## Test-suite coverage map

Named areas only (no raw count; coverage is per-target — constitution §Constraints,
`.specify/memory/constitution.md:118-119`). Every test source file inspected contains at least one assertion,
so no assertion-free test file was found.

`common/src/test` (default/`common:test`) covers:

- **auction** — listing model (`auction/AuctionListingTest.java`).
- **config** — bundled-default ↔ `EconomyConfig` bidirectional match and clamp-survival
  (`config/BundledConfigTest.java:67-103`), config merge behaviour (`config/EconomyConfigMergeTest.java`), MOTD
  section (`config/MotdSectionTest.java`), quests clamp (`config/QuestsSectionClampTest.java`), tag colour/icon
  clamp (`config/TagConfigClampTest.java`).
- **contracts** — book, model and service settlement (`contracts/ContractBookTest.java`,
  `ContractModelTest.java`, `ContractServiceTest.java`).
- **db** — SQLite document seam and one-time legacy import (`db/EconomyDatabaseTest.java`, including
  `legacyImportMovesFilesIntoDocuments`).
- **faction** — effects, fiscal policy math, levy-burn behaviour, faction rules, store, rate breakdown
  (`faction/FactionEffectsTest.java`, `FactionFiscalPolicyTest.java`, `FactionLevyBurnTest.java`,
  `FactionRulesTest.java`, `FactionStoreTest.java`, `RateBreakdownTest.java`).
- **fiscal** — daily wealth-tax/rebate policy math (`fiscal/FiscalPolicyTest.java`).
- **gossip** — the largest cluster: API client, config, cooldown/recent-spoken trackers, dialogue prompt
  builder, seeder, transaction anonymizer, villager DB, plus stress/resilience suites
  (`gossip/GossipApiClientTest.java`, `GossipConfigTest.java`, `CooldownTrackerTest.java`,
  `RecentSpokenTrackerTest.java`, `VillagerDialoguePromptBuilderTest.java`, `VillagerSeederTest.java`,
  `TransactionAnonymizerTest.java`, `VillagerDatabaseTest.java`, `ChallengerStressTest.java`,
  `GossipEmpiricalStressTest.java`, `ResilienceTest.java`).
- **integration** — reflective ShopGuard claim bridge (`integration/ClaimBridgeTest.java`).
- **motd** — formatter and service (`motd/MotdFormatterTest.java`, `motd/MotdServiceTest.java`).
- **negotiation** — events, store, offers-hub model (`negotiation/NegotiationEventsTest.java`,
  `NegotiationStoreTest.java`, `OffersHubModelTest.java`).
- **orders** — request model (`orders/OrderRequestTest.java`).
- **profession** — store (`profession/ProfessionStoreTest.java`).
- **quests** — quest math (`quests/QuestLogicTest.java`).
- **tax** — the tax resolver, including the source-scanning assertion that no `taxRate` mention survives outside
  `tax/`, `EconomyConfig` and `AdminSettingsUi` (`tax/TaxPolicyTest.java:174-188`) — this is the mechanical
  guard behind check 1.
- **time** — cooldown and online-time services (`time/CooldownServiceTest.java`, `time/OnlineTimeServiceTest.java`).
- **util** — `&x` ampersand parsing (`util/AmpersandFormatTest.java`), time formatting
  (`util/TimeFormatTest.java`).

`common/src/test26_3` (26.3-only, `-Pminecraft_version=26.3`) covers:

- **toll UI** — menu callbacks, storage, visibility, restrictions, ownership/admin flows, notices, payment
  (`TollUiTest.java`; this is the suite `docs/contributing/tolls.md:9-11` names).
- **profession effects** — `BlockTags`, Builder, Farmer, Miner, Merchant, Soldier and haste
  (`profession/BlockTagsTest.java`, `BuilderEffectsTest.java`, `FarmerEffectsTest.java`, `MinerEffectsTest.java`,
  `MerchantEffectsTest.java`, `SoldierEffectsTest.java`, `ProfessionHasteTest.java`).
- **tag display** — `tag/TagDisplayServiceTest.java`.

## Open questions

1. Is the Fabric-only `&x` sign/anvil/book mixin set (R5) an accepted NeoForge gap, or should the hooks be
   ported or explicitly documented as Fabric-only? No decision is recorded anywhere in the tree.
2. Is `IMPORT_TAX` (N3) reserved for a future separate attribution of the Monarchy import surcharge, or should
   it be removed from `FISCAL_SOURCES`?
3. Should escrow refunds count toward `EARNED` (R4), or should the refund sources be treated as non-income?
4. Is `compatibilityLevel: JAVA_25` (R6) intended for the Java-21 targets? **[assumption]** unverified without a
   build run.
5. Should `TollManager` share `EconomyManager`'s `EconomyDatabase` instance (R1) so toll writes join the same
   transaction boundary?

