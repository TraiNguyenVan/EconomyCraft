# Decision: Releasing the next EconomyCraft version

- **Slug**: release-1-10-0-readiness
- **Decided**: 2026-10-12
- **Verdict**: **go** — for Option A (prepare and ship a 26.3-only release), with Option C's plumbing folded in
- **Artifacts reviewed**: intake.md, research.md, problem.md, concept.md

## Scorecard

| Criterion | Rating | Justification |
|-----------|--------|---------------|
| Problem validity | strong | The primary problem is concrete and observed, not speculative: finished work cannot reach users, and the tree's own declared version number is already published for all five targets on both stores (`evidence/release-mechanics.md` §1.1). |
| Evidence strength | strong | Every load-bearing claim is verified: 10 release legs executed (2 pass, 8 fail — `evidence/build-matrix.md`), store state read from both public APIs, the publish workflow read and diffed against upstream, and three independent audits with `path:line` citations. |
| Value vs. inaction | strong | Doing nothing leaves the whole feature set unobtainable while this repository's README and wiki already document it; the recommended option's cost is documentation and a version number. |
| Feasibility / appetite | strong | Small appetite: no code change is needed for 26.3 to build, and the publish path is proven in practice by upstream's five releases through the same workflow. |
| Strategic fit | adequate | Aligns with the constitution's rule that documentation describes only what ships, and with "make an explicit loader decision". It does *not* restore the five-target claim; that stays false until Option B, and the recommended option must say so in the docs rather than leave the claim standing. |
| Risk posture | adequate | Main risks are identified and bounded: the workflow publishes nothing unless every selected leg builds (`needs: [setup, build]`), so a bad scope fails safe. Two risks stay open — publish-token scope is unverifiable from here, and no automated gate exists until one is added. |

## Verdict & Rationale

**Go**, for the recommended option: prepare the release artifacts and ship **26.3 on Fabric and NeoForge only**,
under a version number that is not already published. Problem validity, evidence strength and value-vs-inaction
are all strong, and the 26.3 release path is verified green end to end on both loaders, so this is a release that
can be made correct and safe with a small appetite. The verdict is deliberately scoped: it is a `go` for a
26.3-only release with prepared artifacts, and it is **not** a `go` for a five-target release. If all five
declared targets are required, the honest verdict for that scope is `needs-clarification` — Option B's size is
unknown, its surface is the newest code in the tree, and the changelog itself records that supporting those APIs
"would mean forking all of them" (`CHANGELOG.md:321-326`). Porting is a separate assessment, not a step in this
release.

Four decisions remain with the maintainer and **must be answered before the publish dispatch** (they do not
change the verdict; they configure it): the version number, the confirmation of 26.3-only scope, the store
destination and token ownership, and who writes the release notes.

## Decisions required before dispatch

- **Version number**: must be strictly greater than `1.10.0` (published) and unused on both stores. The delta
  (new features, removed features, changed tax semantics) supports `1.11.0`; `2.0.0` is the alternative if the
  removed features and changed tax behaviour are treated as breaking. `1.10.0` and `1.10.1` are not available
  in the sense that matters: `1.10.0` exists, and `1.10.1` would imply a patch to a release that never
  contained this code.
- **Scope**: confirm 26.3-only for this dispatch, and that the README/wiki supported-version statements are
  corrected in the same change rather than left contradicting the store.
- **Store destination and tokens**: confirm whether publishing into Modrinth `H4o5bO7g` / CurseForge `1321557`
  (owned by `ReaZip`) is intended, and that the repository's Actions secrets hold a token with write access to
  those projects. `[NEEDS CLARIFICATION]` — the API refuses to disclose secret names or permissions (403).
- **Release notes ownership**: who writes the versioned notes, and whether the runbook lives in
  `docs/contributing/`.


## If go — Handoff to `$speckit-specify`

- **Problem**: finished, user-facing work in this tree cannot reach users because the declared version is
  already published, four of five targets do not compile, and the release artifacts (notes, docs, tag) are not
  release-ready.
- **Chosen approach**: Option A — ship a 26.3-only release with prepared artifacts, folding in Option C's
  plumbing (build gate + runbook).
- **In scope**: version bump; versioned release notes; corrections to the admin-facing documentation and the
  changelog's false "still builds on the other declared targets" claim; a build/test gate for the shipped
  target; a release runbook; a tag on the released commit; a `dry_run`; the publish dispatch for 26.3 on both
  loaders.
- **Out of scope**: porting to 1.21.1 / 1.21.11 / 26.1.2 / 26.2; feature, tuning, tax or persistence changes;
  store-listing copy; the code-audit risks recorded as findings.
- **Success metrics**: `dry_run` green for both 26.3 legs; version unused on both stores; release notes
  versioned and free of internal identifiers; no admin-facing doc contradicting the shipped persistence model;
  a tag on the released commit; a runbook in `docs/contributing/`.
- **Carried-forward open questions**: version number; scope confirmation; store destination and token
  ownership; notes ownership; whether the Fabric-only `&x` mixin set is an accepted NeoForge gap.


## Release work list (ordered)

Each item names what proves it done. `[blocker]` = the dispatch cannot succeed without it.

1. **[blocker] Choose and apply the version.** Set `mod_version` in `gradle.properties:6` to the chosen
   number, and fix the now-false statements that cite the old state: `AGENTS.md:79` ("last tag `1.9.0`") and
   the changelog's "Supported versions" note (`CHANGELOG.md:321-326`). *Proof*: `grep -rn '1\.10\.0'
   gradle.properties AGENTS.md` returns the new number, and the built artifact names change accordingly.
2. **[blocker] Decide the scope and record it.** 26.3-only is the verified-green scope; state it in the
   README's supported-versions surface and in the changelog. *Proof*: the documented target list matches the
   release workflow's selected inputs.
3. **[blocker] Confirm publish credentials and destination.** Confirm the repository holds
   `MODRINTH_PERSONAL_ACCESS_TOKEN` / `CURSEFORGE_PERSONAL_ACCESS_TOKEN` with write access to the intended
   projects. *Proof*: a `dry_run` does **not** prove this — it skips the `release` job — so it must be
   confirmed out of band before the real dispatch.
4. **[blocker] Write the release notes.** Add a versioned section (e.g. `## <version>`) to `CHANGELOG.md`, or
   point `changelog-file` at a dedicated release-notes file; remove internal spec identifiers
   (`CHANGELOG.md:272-273`), internal "known gaps" and design-decision prose (`CHANGELOG.md:177-181`,
   `CHANGELOG.md:282-289`), rewrite the never-shipped feature churn as scope, fix `/eco party reset` →
   `/eco party leave` (`CHANGELOG.md:84`), and add the missing shipped entries (Server Quests, contract payout
   taxation, the contract subcommands `mine`/`new`/`actions`/`admin list`). *Proof*: the published notes carry
   exactly one version heading and no internal planning text.
5. **Fix the admin-facing documentation.** Replace the per-file JSON persistence description and the separate
   `villagers.db` (`README.md:262-291`) with the single `economycraft.db` model, add `economycraft.db` to the
   `/eco import` list (`README.md:295-297`), and correct the four contradicted statements (clamp range,
   `shop.json` target, `/eco import` registration condition, gossip prompt default) plus the undocumented
   `quests.*` keys. *Proof*: `evidence/docs-vs-code.md` D1-D9 each have a corresponding doc change.
6. **Publish the wiki.** Enable or locate the hosted wiki and copy the 11 pages that exist only here, per
   `docs/contributing/documentation.md:27-32`; if no hosted wiki is intended, remove the stale publishing
   instructions. *Proof*: the hosted wiki shows `Contracts.md` and `Server-Quests.md`.
7. **Add a build/test gate.** A `push`/`pull_request` workflow running the documented commands for the shipped
   target(s), so a release is not the first verification. *Proof*: a green run on the release commit.
8. **Close the release path's blind spot.** Make the release legs run the test suite (the nested builds
   currently skip `:common:test`). *Proof*: a release leg's log contains `:common:test`.
9. **Fix the public-API thread contract.** `FactionApiImpl.defaultFactionId()`
   (`api/v1/EconomyCraftApiBootstrap.java:228-230`) must call `requireServerThread()` like its siblings.
   *Proof*: an off-thread call throws, as `api/v1/FactionApi.java:18` promises.
10. **Tag the release commit and dry-run.** Push the tag, then dispatch the workflow with `dry_run: true` and
    only `release_26_3` selected. *Proof*: the setup job's summary shows the version and the tag, and both
    build legs are green.
11. **Publish.** Re-dispatch with `dry_run: false`. *Proof*: two new files on each store under the new version,
    with matching file names, carrying the versioned notes from item 4.
12. **Write the runbook.** `docs/contributing/release.md` covering bump → notes → docs → tag → dry run →
    publish, including the target-selection semantics and the fact that any failing leg publishes nothing.
    *Proof*: the file exists and matches the workflow's inputs.
13. **Record the known gaps for the next cycle.** The multi-target breakage (Option B), the second
    `EconomyDatabase` connection in `TollManager`, placeholder create-on-read, escrow refunds counted as
    earned, and the Fabric-only `&x` mixins with no recorded loader decision.
