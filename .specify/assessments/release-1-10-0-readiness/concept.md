# Concept: Releasing the next EconomyCraft version

- **Slug**: release-1-10-0-readiness
- **Created**: 2026-10-12
- **Recommended option**: Option A — ship a 26.3-only release with prepared artifacts (with Option C's
  plumbing folded in)

## Options

### Option A — Ship 26.3 now, with prepared artifacts

- **Sketch**: Cut one release for the only target that builds. Bump the version to a number neither store has,
  write release notes as a real versioned section (or a dedicated release-notes file) instead of publishing the
  whole changelog, correct the admin-facing statements that describe the old persistence model, state the
  supported-target list as it actually is, tag the released commit, run the publish workflow as a `dry_run`
  first, then publish 26.3 for Fabric and NeoForge. Users get the whole new feature set on the current target;
  the other four targets stay unpublished exactly as they are today.
- **Appetite**: small (days). The work is versioning, notes, documentation corrections, a tag and a dry run;
  no code has to change for 26.3 to build.
- **Trade-offs**: Wins — everything of value reaches users now; the release is honest about its scope; the
  workflow's failure semantics protect against a partial publish. Sacrifices — the README/wiki "supported
  versions" claim must be corrected to 26.3-only (or the other targets marked unreleased), a user-visible
  reduction against upstream's five-target `1.10.0`; owners of 1.21.1/1.21.11/26.1.2/26.2 servers get nothing
  new. Risks — the release ships without an automated gate unless the gate is added in the same change.
- **Rabbit holes**: the documentation corrections can expand (9 ranked defects, and the README duplicates its
  own config table); the "removed features" prose (container locks, public gossip) has to be rewritten as
  "never released" rather than "removed", which invites a full changelog rewrite; deciding the version number
  and the publish destination is a maintainer decision that can stall the cut.

### Option B — Port to all five targets first, then release

- **Sketch**: Restore the four broken targets by moving era-specific vanilla usage out of the shared source set
  into compat forks (`profession/`, `tag/`, `util/BookInputUi`, the profession mixins), make the loader
  decisions the constitution requires, then release all five targets × two loaders as upstream did. Users on
  every declared target get the new feature set at once, and the repository's "five supported targets" claim
  becomes true again.
- **Appetite**: large (months, unbounded as scoped here). The porting surface is the newest and largest part of
  the tree — the whole faction/profession/quest/toll/dialogue feature set — and the changelog itself says
  supporting those APIs "would mean forking all of them" (`CHANGELOG.md:321-326`).
- **Trade-offs**: Wins — no scope reduction, one release for everybody, and the multi-target invariant is
  restored rather than documented away. Sacrifices — nothing ships until the port is done, which extends the
  current state by months; the port touches money-adjacent code (profession effects, tax) where the invariant
  tests are the only safety net, and those tests only run on 26.3 today. Risks — the port's true size is
  unknown until the shared-source surface is enumerated per target; each target's fork is a new divergence to
  maintain forever.
- **Rabbit holes**: 1.21.1's villager/entity package layout (`profession/MerchantEffects.java:7-9`) is a
  different shape of change from 26.2's missing `BonemealSource`; the mixin sets differ per loader and per era;
  and the port invites a "while we're here" refactor of the very code the release notes describe.

### Option C — Prepare the release plumbing only; publish nothing yet

- **Sketch**: Do the parts that are unambiguously good regardless of scope: add a push/PR build gate over the
  targets that must stay green, write the release runbook (`docs/contributing/`), bump the version, tag the
  commit, fix the documentation and changelog defects, and record the multi-target breakage as known. Leave the
  store publish to a later dispatch once the maintainer has settled scope and tokens.
- **Appetite**: small–medium (days to a week, mostly documentation and CI).
- **Trade-offs**: Wins — every artefact a release needs exists and is verified; the next dispatch is a
  mechanical act; the documentation stops lying. Sacrifices — no user-visible outcome; the features stay
  unobtainable, so the primary problem in `problem.md` remains open. Risks — "preparation" can drift into the
  full documentation rewrite; without a publish date the version bump can go stale again, which is precisely
  how `1.10.0` collided.
- **Rabbit holes**: the changelog rewrite; deciding whether the other four targets are "supported", "unreleased"
  or "removed" in the docs, which is the same scope decision Options A and B are blocked on.

## Recommendation

**Option A, with Option C's plumbing folded in.** The problem is that finished work cannot reach users; A is
the only option that closes it inside the current release cycle, and its cost is documentation plus a version
number, not code. Evidence strength is high, and the 26.3 release path is verified green on both loaders
(`evidence/build-matrix.md`), so the risk of shipping is low. C alone leaves the primary problem open. B is a
prerequisite for a five-target release, not a step in this one — if the maintainer requires five targets, the
honest verdict for *that* release is "not now" until B is scoped, because its size is unknown and its surface
is the newest code in the tree. The recommendation is therefore: ship 26.3, state the scope plainly, and treat
the port as its own assessed workstream.

## Out of Scope (for the recommended option)

- Porting to 1.21.1, 1.21.11, 26.1.2 or 26.2 (Option B), including the compat forks it needs.
- Any feature, tuning, tax or persistence change made only to make the release "nicer".
- Store-listing marketing copy and project-page restructuring.
- Deciding the fork's long-term store identity beyond the number used for this release.
- Fixing the deeper risks found in the code audit (the second `EconomyDatabase` connection in `TollManager`,
  placeholder create-on-read, escrow refunds counted as earned) — recorded as findings, not release blockers.

## Assumptions to Validate

- The repository's Actions secrets can publish to the target store projects; if they cannot, Option A degrades
  to Option C plus an artifact attached to a GitHub Release.
- A version number strictly above the published `1.10.0` is acceptable to the project's owner and does not
  collide with a number already reserved upstream.
- Publishing a 26.3-only release does not require changing the store projects' declared game-version list.
- The `dry_run` input really does build without publishing — it skips the `release` job, which is what the
  workflow shows (`.github/workflows/release.yml:182`).

