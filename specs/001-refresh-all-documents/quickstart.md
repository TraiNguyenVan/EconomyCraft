# Quickstart: Verifying the Documentation Refresh

**Feature**: `001-refresh-all-documents` | **Date**: 2026-10-07
**Plan**: [plan.md](plan.md) | **Contracts**: [contracts/](contracts/)

Runnable checks that prove the documentation now matches the shipped code. Every check is static — no
Minecraft server is started. Run them from the repository root.

> **Run V6 last.** Configuration keys can change while editing. V6 is the authoritative pass and must run
> after all documentation edits are complete.

---

## Prerequisites

| Requirement | Verify with |
|---|---|
| `python3` | `python3 --version` |
| `git` | `git --version` |
| Repository root | `git rev-parse --show-toplevel` |
| No uncommitted source changes | `git status --short` |

Nothing is installed by this feature. No dependency is added.

---

## V1 — Extract configuration ground truth

The authority for every documented default.

```bash
python3 - <<'PY'
import json
d = json.load(open('common/src/main/resources/assets/economycraft/config.json'))
out = []
def walk(o, p=''):
    if isinstance(o, dict):
        for k, v in o.items():
            walk(v, f'{p}.{k}' if p else k)
    else:
        out.append((p, o))
walk(d)
for k, v in sorted(out):
    print(f'{k} = {json.dumps(v)}')
PY
```

**Expected**: **164 leaf keys** across six groups — top-level scalars plus `factions`, `professions`,
`quests`, `gemini_gossip`, `motd`.

**This output is the reference** for correcting
[config-reference-contract.md](contracts/config-reference-contract.md) §2. Spot-check that it includes:

| Key | Expected default |
|---|---|
| `factions.capitalism.daily_tax_rate` | `0.025` |
| `factions.monarchy.daily_tax_rate` | `0.01` |
| `factions.communism.income_tax_tier3_rate` | `0.00625` |
| `max_active_tolls_per_player` | `10` |
| `quests.weekly_budget` | `12000` |
| `gemini_gossip.anonymize_players` | `true` |
| `motd.enabled` | `true` |

If any differs, `config.json` changed — re-read before correcting any document.

---

## V2 — Verify documented keys exist and match defaults

```bash
python3 - <<'PY'
import json, re, pathlib

cfg = json.load(open('common/src/main/resources/assets/economycraft/config.json'))
flat = {}
def walk(o, p=''):
    if isinstance(o, dict):
        for k, v in o.items():
            walk(v, f'{p}.{k}' if p else k)
    else:
        flat[p] = o
walk(cfg)

bad_name, bad_val = [], []
for doc in ['README.md'] + [str(p) for p in pathlib.Path('wiki').glob('*.md')]:
    text = pathlib.Path(doc).read_text(encoding='utf-8')
    for m in re.finditer(r'`([a-z_]+(?:\.[a-z_0-9]+)+)`\s*\|\s*`([^`]+)`', text):
        key, val = m.group(1), m.group(2)
        if key in flat:
            if json.dumps(flat[key]).strip('"') != val:
                bad_val.append((doc, key, val, flat[key]))
        elif any(k.startswith(key + '.') for k in flat):
            bad_name.append((doc, key))

print('--- wrong default ---')
for d, k, doc_v, real in bad_val:
    print(f'{d}: {k} documented {doc_v} but ships {real}')
print('--- value where a prefix was expected (check by hand) ---')
for d, k in bad_name:
    print(f'{d}: {k}')
print(f'\n{bad_name.__len__()} value errors, {len(bad_name)} prefixes')
PY
```

**Expected after the fix**: zero value errors.

**Known-bad before the fix** — each should disappear:

| Documented | Ships |
|---|---|
| `0.05` for `capitalism.daily_tax_rate` | `0.025` |
| `0.017` for `monarchy.daily_tax_rate` | `0.01` |
| `0.0125` for `income_tax_tier3_rate` | `0.00625` |

---

## V3 — Verify no documentation invents configuration keys

Catches FR-006's second direction — a documented key that does not ship.

```bash
python3 - <<'PY'
import json, re, pathlib

cfg = json.load(open('common/src/main/resources/assets/economycraft/config.json'))
flat = set()
def walk(o, p=''):
    if isinstance(o, dict):
        for k, v in o.items():
            walk(v, f'{p}.{k}' if p else k)
    else:
        flat.add(p)
walk(cfg)

# Known-wrong names that are not configuration keys at all
phantom = ['ownClaimDamageMultiplier', 'crop_boost_interval_minutes',
           'lava_regen_duration_seconds', 'discount_master']

for doc in ['README.md'] + [str(p) for p in pathlib.Path('wiki').glob('*.md')]:
    text = pathlib.Path(doc).read_text(encoding='utf-8')
    for name in phantom:
        if name in text:
            print(f'{doc}: phantom key {name}')

print('\nExpected: no output.')
PY
```

**Known-bad before the fix**: four phantom names, per
[config-reference-contract.md](contracts/config-reference-contract.md) R2.

---

## V4 — Check links

```bash
python3 - <<'PY'
import re, pathlib, urllib.parse

WIKI = 'https://github.com/TraiNguyenVan/EconomyCraft/wiki'
UPSTREAM = 'github.com/PhilipB06/EconomyCraft'

docs = ['README.md'] + [str(p) for p in pathlib.Path('wiki').glob('*.md')]

broken = []
for doc in docs:
    base = pathlib.Path(doc).parent
    for text, link in re.findall(r'\[([^\]]*)\]\(([^)]+)\)', pathlib.Path(doc).read_text(encoding='utf-8')):
        if link.startswith(('http://', 'https://')):
            if UPSTREAM in link and '/wiki' in link:
                broken.append((doc, link, 'points at upstream wiki'))
        elif link.startswith('#'):
            continue
        elif not (base / link).exists():
            broken.append((doc, link, 'target file missing'))

for d, l, why in broken:
    print(f'{d}: [{l}] — {why}')
print(f'\nCanonical wiki base: {WIKI}')
print(f'{len(broken)} problems')
PY
```

**Expected after the fix**: zero problems.

**Known-bad before the fix**:

| Location | Problem |
|---|---|
| `README.md:25` | `wiki/Tolls.md` — a wiki page is not reachable by a repo-relative path |
| `README.md:468` | upstream wiki URL |
| `wiki/Home.md:30-34` | five upstream wiki URLs |

**Must NOT change**: `wiki/_Sidebar.md` relative links, `README.md:6` and `:475` attribution.

---

## V5 — Verify no path points outside the repository

FR-009. The plan document cites `/home/capcap/Git/...`, a path on another machine.

```bash
grep -rn '/home/\|capcap\|\.\./\.\./' README.md wiki/*.md .specify/memory/constitution.md \
  2>/dev/null | grep -v '^\.specify/memory/constitution.md:.*TODO.md'
```

**Expected after the fix and the `TODO.md` deletion**: zero output.

---

## V6 — Full consistency sweep (authoritative, run last)

```bash
python3 - <<'PY'
import json, re, pathlib, subprocess

cfg = json.load(open('common/src/main/resources/assets/economycraft/config.json'))
flat = {}
def walk(o, p=''):
    if isinstance(o, dict):
        for k, v in o.items():
            walk(v, f'{p}.{k}' if p else k)
    else:
        flat[p] = o
walk(cfg)

checks = []

# SC-001: every shipped config key documented somewhere.
# Match any backticked token, then keep it only if it is a real config key or a
# prefix of one. Matching dotted names only would silently miss camelCase
# top-level keys such as `startingBalance`.
documented = set()
for doc in ['README.md'] + [str(p) for p in pathlib.Path('wiki').glob('*.md')]:
    text = pathlib.Path(doc).read_text(encoding='utf-8')
    for tok in re.findall(r'`([A-Za-z_][A-Za-z0-9_.]*)`', text):
        if tok in flat or any(k.startswith(tok + '.') for k in flat):
            documented.add(tok)
missing = sorted(k for k in flat if k not in documented)
checks.append((f'{len(missing)} shipped config keys undocumented', missing))

# SC-006: exactly one Unreleased heading in the change log
ch = pathlib.Path('CHANGELOG.md').read_text(encoding='utf-8')
n = len(re.findall(r'^## Unreleased', ch, re.M))
checks.append((f'CHANGELOG has {n} "## Unreleased" headings', [] if n == 1 else [f'expected 1']))

# SC-003: no completed-work markers in a file that should be gone
checks.append(('TODO.md still present',
               [] if not pathlib.Path('TODO.md').exists() else ['delete after migration']))

# SC-010: constitution has real content
con = pathlib.Path('.specify/memory/constitution.md').read_text(encoding='utf-8')
placeholders = re.findall(r'\[([A-Z_]{4,})\]', con)
checks.append((f'constitution placeholders: {len(placeholders)}',
               [] if not placeholders else sorted(set(placeholders))[:5]))

for name, problems in checks:
    status = 'OK  ' if not problems else 'FAIL'
    print(f'[{status}] {name}')
    for p in problems[:8]:
        print(f'         - {p}')
    if len(problems) > 8:
        print(f'         ... and {len(problems) - 8} more')
PY
```

**Expected**: all four checks report `OK`.

Baseline to beat — measured, not estimated:

| Check | Current state | Target |
|---|---|---|
| Shipped config keys undocumented | **130 of 164** | 0 |
| `## Unreleased` headings in `CHANGELOG.md` | **4** | 1 |
| `TODO.md` present | **yes** | no, after migration |
| Constitution placeholder tokens | **6** | 0 |

Where the 130 undocumented keys sit: `professions` 61, `factions` 38, `gemini_gossip` 14, `quests` 14,
`motd` 3.

If the constitution check reports placeholders, consult
[constitution-contract.md](contracts/constitution-contract.md) C1.

---

## V7 — Confirm no runtime behavior changed (SC-008)

The decisive check. Nothing in this feature may alter behavior.

```bash
git diff --stat -- common api fabric neoforge build.gradle gradle.properties
```

**Expected**: no output.

Then run the existing suite to confirm it still passes:

```bash
./gradlew -Pminecraft_version=26.3 :common:test
```

**Expected**: the same tests pass as before the change.

Note: two test source sets exist — `common/src/test` and `common/src/test26_3`, the latter for the 26.3
target only. A raw count is not a pass criterion, because the count differs per target (research.md R2).

> **Do not** "fix" a failing test by editing the test. A failure here means the documentation work changed
> behavior, which violates FR-001. Investigate and revert the behavior change.

---

## V8 — Repository identity spot-check

Guards against "correcting" the MOTD default or the GPL attribution, both of which are currently right.

```bash
echo "origin:   $(git remote get-url origin)"
echo "upstream: $(git remote get-url upstream)"
grep -n 'TraiNguyenVan\|PhilipB06' README.md common/src/main/resources/assets/economycraft/config.json
```

**Expected**:

| Item | Correct value | Why |
|---|---|---|
| `origin` | `TraiNguyenVan/EconomyCraft` | This fork |
| `upstream` | `PhilipB06/EconomyCraft` | The original |
| `config.json` MOTD issue URL | `TraiNguyenVan/...` | Matches `origin`. **Correct — leave it** |
| `README.md:6` fork note | `PhilipB06/EconomyCraft` | GPL-3.0 attribution. **Leave it** |
| `README.md:475` attribution | `PhilipB06 (ReaZip)` | **Leave it** |
| Canonical wiki URL | `TraiNguyenVan/EconomyCraft/wiki` | Where documentation links must point |

An earlier pass of the audit wrongly flagged the MOTD as a third repository identity. It is correct.

---

## V9 — Language policy spot-check (SC-011)

```bash
for f in wiki/Chon-tag.md wiki/Factions.md wiki/Professions.md; do
  printf '%s: ' "$f"
  grep -qP '[\x{0300}-\x{036f}\x{1EA0}-\x{1EF9}\x{20AB}]' "$f" && echo 'Vietnamese OK' || echo 'NO VIETNAMESE - check'
done
for f in wiki/Tolls.md wiki/Home.md wiki/API-Reference.md; do
  printf '%s: ' "$f"
  grep -qP '[\x{0300}-\x{036f}\x{1EA0}-\x{1EF9}\x{20AB}]' "$f" && echo 'VIETNAMESE FOUND - should be English' || echo 'English OK'
done
```

**Expected**: player pages (`Chon-tag`, `Factions`, `Professions`) report Vietnamese; integrator pages
(`Tolls`, `Home`, `API-Reference`) report English.

`wiki/Tolls.md` currently reports **English** and is therefore a defect — it is a player page in the
Gameplay section (see [wiki-page-contract.md](contracts/wiki-page-contract.md) §2).

---

## V10 — Publish the wiki (manual, outside the repository)

A GitHub wiki is a **separate git clone**, not generated from `wiki/`. These edits do not appear on the
hosted wiki until pushed there.

```bash
# In the wiki clone, not this repository
git clone https://github.com/TraiNguyenVan/EconomyCraft.wiki.git
cd EconomyCraft.wiki
# copy the corrected pages in, then:
git add -A && git commit -m "docs: sync wiki with repository" && git push
```

**Expected**: the hosted wiki serves the corrected pages.

This is a manual step and is **not** performed by this feature.

---

## Summary

| Check | Verifies | Criterion |
|---|---|---|
| V1 | Ground-truth extraction | — |
| V2 | Defaults match the shipped file | SC-001 |
| V3 | No invented keys | SC-001, FR-006 |
| V4 | Links resolve and point at this fork | SC-003 |
| V5 | No paths outside the repository | SC-004 |
| V6 | Full consistency sweep — **run last** | SC-001, SC-006, SC-010 |
| V7 | No runtime behavior changed | SC-008 |
| V8 | Repository identity preserved | GPL notice |
| V9 | Language policy holds per page | SC-011 |
| V10 | Wiki published (manual) | SC-003 |

**Definition of done**: V1-V9 pass, V10 performed manually by the maintainer.

See [contracts/](contracts/) for what each check is validating against.
Full finding list with `file:line` citations in [drift-inventory.md](drift-inventory.md).