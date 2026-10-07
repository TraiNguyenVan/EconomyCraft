# Implementation Notes

Running record of decisions and status taken during `/speckit.implement`.

---

## TODO.md recovery path (T005)

`TODO.md` is tracked and committed, so deleting it is fully reversible.

| Check | Result |
|---|---|
| Tracked in git | yes |
| Lines in `HEAD` | 1140 |
| Lines on disk | 1140 |
| Uncommitted changes (`git diff HEAD`) | **0** |
| Last commit touching it | `c9b682c` — *fix(faction): key party benefits on hasChosen, not the default party* |

### Recovery command

```bash
git show HEAD:TODO.md > TODO.md
```

Any commit will do; `c9b682c` is the most recent one that touched the file.

### Unsaved-buffer risk: CLEARED

Earlier in planning, a Vim swap file `.TODO.md.swp` existed for `TODO.md`, which raised the risk that
unsaved buffer content would be lost on deletion.

Re-checked at the start of Phase 1 implementation:

- `.TODO.md.swp` **no longer exists** — the editor session has closed.
- `git diff HEAD -- TODO.md` reports **0 changes** — the working tree matches the committed version exactly.
- `TODO.md` mtime is `2026-10-03 14:27`, unchanged since before this session.

**Conclusion**: nothing exists outside a commit. Deleting `TODO.md` in T050 carries no data-loss risk, and
the gate that T050 was written to enforce is satisfied.

---

## Checker defects found in Phase 1 (feeds T006 / T007)

Running the `quickstart.md` baseline exposed two defects in the checkers themselves. Both are recorded in
[`baseline.md`](baseline.md) §3 with fixes specified.

| Checker | Defect | Fix in |
|---|---|---|
| V2 | Cannot resolve section-relative configuration keys, so it never checks any of the 99 `factions`/`professions` keys. Five wrong defaults are invisible to it | T006 |
| V2 | String comparison does not normalise quotes, producing a false positive on `balance_separator` | T006 |
| V4 | Treats 10 correct `_Sidebar.md` wiki links as broken, because it does not retry with `.md` appended | T007 |

**Why this matters**: T030's acceptance criterion — "0 undocumented keys, 0 value errors" — is currently
meaningless. As written it would report success while four wrong defaults remained in `README.md`.
US1 corrections cannot be verified until T006 and T007 are done.

---

## Independent reproduction of the drift inventory

Phase 1 re-derived the command inventory from source rather than trusting `drift-inventory.md`.

| Quantity | Audit predicted | Phase 1 extracted |
|---|---|---|
| `/eco` subcommands | 26, plus bare `/eco` | 26, plus bare `/eco` |
| Permission nodes | 6 admin, 14 command | 6 admin, 14 command |
| `api/v1` types | 18 | 18 |
| Missing permission node | `economycraft.command.tag` | `economycraft.command.tag` |
| Missing `EconomyCraftApi` members | `factions`, `inflationMultiplier`, `medianActiveBalance` | same three |

The extractions agree, so the inventory is confirmed against the code.

### Confirmed with new evidence

`EconomyCommands.java:192`, `:193` and `:195` all pass `Nodes.COMMAND_TAG` — for `buildTag`, `buildJob` and
`buildParty` respectively. This is direct proof that the single undocumented permission node gates three
commands and the hub Tags button, which the inventory had asserted from the node list alone.

`FactionApi` exposes 5 methods (`factionId`, `factionDisplayName`, `hasChosen`, `defaultFactionId`,
`claimCostMultiplier`); `FactionIds` has 5 constants (`COMMUNISM`, `CAPITALISM`, `MONARCHY`, `ANARCHISM`,
`DEFAULT`). Neither type appears in any wiki page.

---

## Method notes

Two earlier extraction attempts produced wrong results and were discarded rather than used:

1. Grepping for `.literal("eco")` found nothing — the code uses a static import, `literal("eco")`.
2. Matching every `root.then(literal(...))` swept up sub-subcommands from three *different* root builders
   (toll, gossip, and the toll sub-tree), inflating the count to 14 with names like `create` and `test`.
   Fixed by scoping to the `eco` root block, lines 170-214.
3. Resolving each `build*()` factory to its literal returned `list`, `request` and `create` for
   `buildAuction`, `buildOrders` and `buildToll`, because those three take their root name as a *parameter*
   and the first literal in the body belongs to a sub-subcommand. Fixed by using the call-site names.

Recorded because the failure mode is silent: each attempt produced a plausible-looking file with the wrong
contents. Only cross-checking the count against the audit caught it.
## T050 — TODO.md deleted

Deleted 2026-10-07 after the owner's explicit confirmation. The file was fully committed with no unsaved
working-tree changes and no `.swp` files present at deletion time.

**Recovery command** — the entire file is in git history, in full:

```sh
git show c9b682c:TODO.md            # print it
git show c9b682c:TODO.md > TODO.md  # restore it
```

`c9b682c` is the last commit that touched `TODO.md`
("fix(faction): key party benefits on hasChosen, not the default party", 2026-10-03).

**What was migrated before deletion**, verified in T048/T049:

| Content | New home |
|---|---|
| 8 ground rules (§1) | constitution principles I-VIII, each citing enforcing code |
| Architecture baseline (§2) | `AGENTS.md` §3-4, re-verified rather than copied |
| Builder reach decision | `AGENTS.md` §4 "Builder reach is a vanilla attribute" |
| Toll verification block | `AGENTS.md` §4 "Verifying toll changes" |
| Faction API rationale | `AGENTS.md` §4 + `wiki/API-Reference.md` |
| Container-lock design reasoning | `CHANGELOG.md`, marked removed with its reasoning kept |

Six rows of the old baseline were **wrong** and were corrected rather than copied: mixin counts, the
`EconomyCraftApiImpl` name, `FISCAL_SOURCES`' location, the `api/v1` type count, online-time tracking, and
villager trading. A seventh claim — "there is no central tax policy" — was also wrong and was corrected in
Phase 5 when `tax/TaxPolicy.java` was found with ~20 call sites.

## T059 — KnownIssues recorded as code-side decisions

Two defects cannot be fixed by documentation. FR-001 forbids the code change, so they are recorded here and in
`data-model.md` §6 as `reported-only`, with the owner marked `code`.

### 1. The gossip model identifier is unverified

`gemini_gossip.model` ships `gemini-3.8-flash` (`config.json:229`, `GossipConfig.DEFAULT_MODEL`). That
identifier could not be confirmed to exist at any provider. It is documented **as shipped**, with no claim that
it is valid.

Resolving this is a code-side decision: either the identifier is corrected in `config.json` and
`GossipConfig.DEFAULT_MODEL`, or the provider is changed. It is deliberately *not* corrected here, because a
documentation fix would change shipped runtime behaviour and would be unverifiable — if the model does not
exist, the failure is at generation time, and guessing a replacement from a search result would be a worse
outcome than a documented unknown.

### 2. ShopGuard's state cannot be checked from this repository

ShopGuard is an external, Fabric-only mod. Its current API, versions and behaviour are not observable here, so
the documentation states the dependency and the graceful-degradation behaviour, and makes **no claim** about
ShopGuard's own state or current releases.

Everything asserted about the seam is asserted only about *this* side of it: the reflection in
`ClaimBridge` / `ReflectiveShopGuardBackend`, the `Platform.isModLoaded("shopguard")` gate at
`ClaimBridge.java:53`, and the single consumer at `FactionEffects.java:157,169`. Those are checkable here, and
they were checked.

Neither KnownIssue may be fixed as part of this feature.

## T055 — V7 gate passed

Both halves of the FR-001 gate, verified on 2026-10-07:

**`git diff` over the source trees is empty.** Across all six phases this feature touched documentation,
`.gitignore` and spec-kit tooling only. Zero `.java` files, zero config JSON, zero build files:

```
git diff --name-only 93950c4^ HEAD | grep -E '\.java$|\.json$|build\.gradle|gradle\.properties'
# -> no output
```

**`:common:test` passes on 26.3**: 49 test classes, 556 tests, 0 failures, 0 errors, 0 skipped. Counts are
recorded here rather than in any user-facing document, because coverage is per-target and a single number is
wrong for four of the five targets.

### Two environment obstacles, and why they are not project defects

Neither was caused by the repository. Both are recorded so the next person does not repeat the diagnosis.

**1. No usable JDK on this machine.** It is Alpine Linux, so the C library is **musl**. Every glibc JDK —
including a freshly downloaded Temurin — fails identically with `Unable to load jimage library`, which reads
like a corrupt download but is not one. `ldd` on the library shows the real cause: `libjvm.so` resolves through
`/lib/ld-musl-x86_64.so.1`. Adoptium ships no musl build, and `apk add` needs root, so the working JDK was
assembled by extracting Alpine's own musl packages (`openjdk25-jre`, `openjdk25-jre-headless`,
`openjdk25-jdk`) into a local directory without installing them. `java` lives in `-jre-headless`, not `-jre`.

**2. SSL and temp space.** The extracted JDK ships a dangling `lib/security/cacerts` symlink, so Gradle fails
with `trustAnchors parameter must be non-empty`; a JKS keystore must be built from `/etc/ssl/certs`. Separately,
Gradle failed with `Permission denied` writing its temp files until `GRADLE_USER_HOME` was pointed off the
sticky-bit `/tmp` on this host.

```bash
export JAVA_HOME=/home/yes/gwtmp/alpine-jdk/usr/lib/jvm/java-25-openjdk
export GRADLE_USER_HOME=/home/yes/gwtmp/gh
export GRADLE_OPTS="-Djavax.net.ssl.trustStore=$JAVA_HOME/lib/security/cacerts \
  -Djavax.net.ssl.trustStorePassword=changeit -Djavax.net.ssl.trustStoreType=PKCS12"
./gradlew -Pminecraft_version=26.3 :common:test
```

### A trap worth recording: `BUILD SUCCESSFUL` is not a test pass

The first run reported `BUILD SUCCESSFUL` with `:common:test UP-TO-DATE`. That is Gradle confirming cached
outputs — **no test executed**. Accepting it would have meant reporting a result never obtained. The real run
used `cleanTest`, and the result was confirmed from the XML in `common/build/test-results/test/` rather than
from the exit code:

```bash
./gradlew -Pminecraft_version=26.3 :common:cleanTest :common:test
grep -l '<failure\|<error' common/build/test-results/test/*.xml   # expect: no output
```

`BUILD SUCCESSFUL` plus `UP-TO-DATE` means nothing was verified. Always check that the test task actually ran.
