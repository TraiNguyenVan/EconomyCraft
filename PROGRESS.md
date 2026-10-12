# EconomyCraft — project progress snapshot

Working document, not user documentation. Generated on request on 2026-10-10 against `main`
(`bc9d48c`, "Merge pull request #10 from TraiNguyenVan/issue-9-contract-book"), the only commit this
clone and its remote carry. Evidence: `CHANGELOG.md` "Unreleased", the merged PR/issue history, and the
notes under `.specify/`.

**Not re-verified here:** the build and the test suites. This sandbox has no JDK and no Gradle cache, so
the targets below were read from the tree, not compiled.

## 1. What the project is

- Server-side Architectury mod for Fabric and NeoForge; a fork of
  [PhilipB06/EconomyCraft](https://github.com/PhilipB06/EconomyCraft) that adds dynamic pricing, private
  AI villager dialogue, tolls, order books and faction economics.
- Five Minecraft targets — 1.21.1, 1.21.11, 26.1.2, 26.2, 26.3 (26.3 is the default; 1.21.x needs Java 21,
  26.x needs Java 25) — each for both loaders, from source sets `main`, `modern`, `legacy121`,
  `obfuscated`, `unobfuscated`, plus `test26_3`.
- 273 Java sources: 206 under `src/main`, 51 under the test source sets.
- Docs: an 843-line README, 17 wiki pages in `wiki/`, four contributor guides in `docs/contributing/`,
  and `.specify/memory/constitution.md` v1.0.0 (amended 2026-10-07).
- The only workflow in `.github/workflows/` is `release.yml`, a manual `workflow_dispatch` that publishes
  per-target builds to Modrinth and CurseForge.

## 2. Where the version line stands

- `mod_version = 1.10.0`, **unreleased**. `AGENTS.md` records the last shipped tag as `1.9.0`; the remote
  carries no tag refs and the repository has no published GitHub releases, so the release workflow is the
  only publication path.
- Nothing is in flight in the tracker: six PRs merged, all issues closed, no open PRs or issues.
- **No CI gate.** No workflow builds or tests a push or a pull request; a broken target is only found by
  running Gradle by hand. This is the largest process gap in the repository.

## 3. Merged since 2026-10-03

| PR | Merged | Title |
|---|---|---|
| #1 | 10-03 | Tag |
| #2 | 10-08 | 008 specify integration switch |
| #4 | 10-09 | Add multi-block login MOTD sequences |
| #7 | 10-10 | Bedrock-style `&x` colour codes across chat, signs, anvil and books |
| #8 | 10-10 | Player-to-player contracts with escrow, review and disputes |
| #10 | 10-10 | Display contract detail inside a book and quill |

PRs #1 and #2 are the broadest (100 files each, at the API file-list cap); the rest are focused.

## 4. Unreleased work already on `main`

`CHANGELOG.md` carries a complete 1.10.0 body, so the release is documentation-ready. Themes, newest first:

1. **Player-to-player contracts** — full escrow lifecycle (post, accept, submit, approve, revise, cancel,
   dispute), public or targeted, deadlines, revision budget, auto-approve after the review window, admin
   resolution, and pending settlements that retry without double-paying. Vanilla chest UI plus headless
   subcommands; new `contracts_*` config keys and two permission nodes.
2. **Single-file SQLite persistence** — everything under `data/` now lives in `economycraft.db`; each legacy
   JSON document is one Gson-payload row in `documents`, villager gossip keeps its relational tables there.
   Legacy files import once (empty-table guard) and are archived, never deleted.
3. **Public villager gossip removed** — dialogue is private to the player who opened the trade UI, under
   every configuration. The rumor pool, its categories, digest worker and the commands that fed them are
   deleted; `gemini_gossip` goes from 14 keys to 9 and stale keys are skipped then pruned.
4. **An undecided player is an Anarchist** — all four party rules key on the effective faction id, so a
   player who never ran `/eco party` is tax-exempt, wears `Ⓐ` and gets the speed bonus instead of reading
   as a bug. `hasChosen` survives only where "the choice" itself is the subject.
5. **Price offers hub** — `/eco offers` (alias `/offers`) with incoming/outgoing rows, deep links
   `/eco offers ah <id>` and `/eco offers order <id>`, and withdraw notifications; the row rules live in
   the Minecraft-free, unit-tested `OffersHubModel`.
6. **Container locks removed in full** — the `Cộng đồng` chest lock, its config section, store, UI, commands
   and the interaction/break handlers that enforced it. ShopGuard claims and tolls are untouched.
7. **Faction & Profession system** — the four parties and five professions with tags on tab list, nametag
   and chat; one central `tax` package replacing 19 duplicated tax multiplications; online-time and
   wall-clock services; faction levies and rebates registered in `FISCAL_SOURCES`; land-claim effects
   (Monarchy damage bonus, Anarchism wilderness speed) through the reflective ShopGuard bridge. **26.3
   only** — it still builds and runs on the other four targets, but these features are absent there.

## 5. Open items

### Recorded and still open

- **`.specify/bugs/inflation-dialogue-dead-param`** (verdict `valid`, severity low, 2026-10-08) —
  re-checked at HEAD: still unfixed. The inflation multiplier is read and passed through three signatures
  and never rendered into the prompt, a test asserts the prompt does *not* contain "inflation"
  (`VillagerDialoguePromptBuilderTest:61`), and `VillagerTradeModifier` is still declaration-only
  (`identity/VillagerTradeModifier.java:13,27`). Two `NEEDS CLARIFICATION` questions block the shape of the fix.
- **`.specify/assessments/agent-switch-generated-files`** (verdict `needs-clarification`, 2026-10-08) — five
  blocking questions; revisit at the research stage before any workflow change.
- **CHANGELOG "Known gaps"** — `/eco settings` does not expose the faction and profession keys (file-only);
  the daily faction pass and its levies are not unit-tested end to end (only the pure rules are); and which
  flows count as an "import" for Monarchy's `Nhập khẩu` is still undecided.

### Guidance that no longer matches the code

`AGENTS.md` says the code is authoritative and the constitution says "verify, or drop it", so these are worth
a small corrective pass:

- Constitution §Governance cites `AsyncFileWriter` as the enforcement of Principle VI, but Principle VI and
  `AGENTS.md` both describe synchronous writes through `db/EconomyDatabase`. `AsyncFileWriter` still exists
  and is flushed on shutdown, but it is no longer the durability layer the row claims.
- Four `package-info.java` files cite a retired `TODO.md` (`tag/`, `faction/`, `profession/`, `time/`) that is
  not in the tree; the constitution calls it retired, the javadoc still points at it.
- `docs/contributing/speckit.md` says spec-driven work lives in `specs/<NNN>-<short-name>/`, but no `specs/`
  directory exists in the tree — the only surviving spec artefacts are the `.specify/` bug and assessment notes.

## 6. Suggested next steps

1. Close out the inflation / `VillagerTradeModifier` bug — it is bounded, already diagnosed, and one of its
   two halves is a decision to make rather than code to write.
2. Fix the three guidance drifts above; each is a one-line correction and two of them are in normative files.
3. Add a push/PR workflow that runs `./gradlew build` (or at least `:common:test` per target) — five targets
   times two loaders are currently ungated.
4. Work the recorded gaps: expose the faction/profession keys in `/eco settings`, add the end-to-end daily
   pass test, and decide the "import" flow list.
5. Cut 1.10.0 — the changelog body is already written and the last merge was the contract-book follow-up.

