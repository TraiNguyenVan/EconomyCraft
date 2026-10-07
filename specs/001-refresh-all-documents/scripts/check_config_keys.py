#!/usr/bin/env python3
"""Configuration-key documentation checker. Implements checks V2 and V3.

Authority for every default is the shipped config.json. A default is never taken
from another document.

Three defects in the quickstart.md version of this check are fixed here; see
specs/001-refresh-all-documents/baseline.md section 3.

  D1  Section-relative keys. README.md documents faction and profession keys
      without their section prefix -- "capitalism.daily_tax_rate" where the
      shipped path is "factions.capitalism.daily_tax_rate". The old lookup
      missed the key and skipped it silently, hiding 99 of 164 keys. Fixed by
      resolving a documented token against every section prefix.

  D2  Quote handling. The old comparison stripped quotes from the shipped side
      only, so a string documented as "." with quotes reported a false
      mismatch. Both sides are normalised now.

  D3  Token pattern. The old pattern required a dotted lowercase name, so it
      never matched camelCase top-level keys such as "startingBalance" -- 34 of
      164 keys were invisible. Any backticked token is now considered, and kept
      only if it is a real key or a prefix of one.

Exit status is 0 when no error class is populated and 1 otherwise.

Usage:
    python3 check_config_keys.py [--root REPO] [--quiet]
"""

from __future__ import annotations

import argparse
import json
import pathlib
import re
import sys

ASSETS = "common/src/main/resources/assets/economycraft"
CONFIG = f"{ASSETS}/config.json"

# config.json is the authority for defaults. These two are shipped config files
# whose keys documents also reference; recognising them stops webhook_url and
# unit_buy being reported as invented names.
SIBLINGS = ("webhook.json", "prices.json")

# Any backticked token. Deliberately broad; membership in the key set is the
# filter, not the shape of the token (defect D3).
TOKEN = re.compile(r"`([A-Za-z_][A-Za-z0-9_.]*)`")

# A config table row: | `key` | `value` | ... |
ROW = re.compile(r"^\s*\|(.*)\|\s*$", re.M)

# A table cell holding one or more backticked fragments. README.md uses
# "`a` / `b`" for pairs such as `communism.income_tax_tier1_threshold` / `_rate`,
# which the old single-pair regex silently dropped -- that is how the three
# communism tier rates went unreported.
FRAG = re.compile(r"`([^`]+)`")

# Names confirmed not to ship (drift-inventory, config-reference-contract R2).
CONFIRMED_PHANTOM = {
    "ownClaimDamageMultiplier": "own_claim_damage_multiplier",
    "crop_boost_interval_minutes": "crop_boost_cooldown_minutes",
    "lava_regen_duration_seconds": "lava_regeneration_seconds",
    "discount_master": "cost_factor_master",
}

def config_shaped(tok: str) -> bool:
    """True when a token is plausibly a config key name.

    Shipped config keys are lower snake_case. Requiring that shape keeps Java
    identifiers -- `MutationSource`, `addMoney`, `BLOCK_INTERACTION_RANGE` --
    out of the review list, which was otherwise 50 lines of noise. camelCase
    phantoms are still caught, by CONFIRMED_PHANTOM, which is checked first and
    does not rely on this shape test.
    """
    if "." in tok or tok.startswith("_") or tok.endswith("_"):
        return False
    return bool(re.fullmatch(r"[a-z][a-z0-9]*(_[a-z0-9]+)+", tok))


def expand(base: str, frag: str) -> str:
    """Expand the shorthand README.md uses to pair keys in one table cell.

    Two forms occur, both seen in README.md:

      `communism.income_tax_tier1_threshold` / `_rate`
          `_rate` is a leading-underscore shorthand for the sibling key
          sharing the tier prefix -> `communism.income_tax_tier1_rate`.

      `builder.haste_level` / `haste_refresh_seconds`
          A bare sibling name inherits the first key's parent ->
          `builder.haste_refresh_seconds`.

    An unqualified first fragment is returned unchanged; the section heading
    disambiguates it later.
    """
    if not base:
        return frag
    parent = base.rsplit(".", 1)[0] if "." in base else ""
    if frag.startswith("_"):
        stem = base.rsplit("_", 1)[0] if "_" in base.rsplit(".", 1)[-1] else base
        return f"{stem}{frag}"
    if "." not in frag and parent:
        return f"{parent}.{frag}"
    return frag


def heading_section(text: str, pos: int) -> str:
    """The config section named by the nearest heading above `pos`.

    README.md documents `enabled` under a `### Factions` heading; that heading
    is what makes the token resolvable. Without it, `enabled` matches four
    sections at once and the checker reports a false ambiguity.
    """
    head = text[:pos]
    best = None
    for m in re.finditer(r"^#{1,6}\s+`?([A-Za-z_][A-Za-z0-9_]*)`?\s*$", head, re.M):
        best = m.group(1)
    return best or ""


def flatten(node, prefix="", flat=None):
    """Every leaf path of the config, dotted. The shipped key set."""
    if flat is None:
        flat = {}
    if isinstance(node, dict):
        for key, value in node.items():
            flatten(value, f"{prefix}.{key}" if prefix else key, flat)
    else:
        flat[prefix] = node
    return flat


def coerce(value):
    """Reduce a value to a comparable (kind, payload) pair.

    Strings are unquoted and JSON-decoded before anything else, so a value
    documented as `["#minecraft:ores"]` and a shipped list compare equal
    rather than differing by quote style (defect D2).
    """
    if isinstance(value, str):
        text = value.strip()
        if len(text) >= 2 and text[0] == text[-1] and text[0] in "\"'":
            text = text[1:-1]
        # A documented array or object arrives as text; decode it so it can be
        # compared against the shipped structure.
        if text[:1] in ("[", "{"):
            try:
                value = json.loads(text)
            except ValueError:
                value = text
        else:
            value = text

    if isinstance(value, bool):
        return ("bool", value)
    if isinstance(value, (int, float)):
        return ("num", float(value))
    if isinstance(value, (list, dict)):
        return ("other", json.dumps(value, sort_keys=True))
    if value is None:
        return ("other", "null")

    text = str(value).strip()
    if text.lower() in ("true", "false"):
        return ("bool", text.lower() == "true")
    try:
        return ("num", float(text))
    except ValueError:
        pass
    return ("str", text)


def same(a, b) -> bool:
    """Compare a documented default against the shipped value.

    Both sides are compared as JSON first, so a list documents as
    `["#minecraft:ores"]` and ships as the list itself still match. Comparing
    them as text reported a false mismatch on `professions.miner.ore_tags`,
    because str(list) renders single quotes.
    """
    ka, kb = coerce(a), coerce(b)

    if ka[0] == "num" and kb[0] == "num":
        return abs(ka[1] - kb[1]) < 1e-12
    if ka[0] == kb[0]:
        return ka[1] == kb[1]
    if ka[0] == "other" or kb[0] == "other":
        return False
    return str(ka[1]).strip() == str(kb[1]).strip()


def resolve(token, flat, sections, hint=""):
    """Map a documented token onto a shipped key path (defect D1).

    Returns (path, status) where status is one of:
      exact        -- token is itself a shipped key
      prefixed     -- token was resolved through a section prefix
      ambiguous    -- more than one section resolves it; needs a human
      unknown      -- not a key and not a prefix of one
    """
    if token in flat:
        return token, "exact"
    if hint and f"{hint}.{token}" in flat:
        return f"{hint}.{token}", "prefixed"
    hits = [f"{s}.{token}" for s in sections if f"{s}.{token}" in flat]
    if len(hits) == 1:
        return hits[0], "prefixed"
    if len(hits) > 1:
        return hits[0], "ambiguous"
    return token, "unknown"


def docs(root):
    return ["README.md"] + sorted(
        str(p) for p in (root / "wiki").glob("*.md")
    )


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=".")
    ap.add_argument("--quiet", action="store_true")
    args = ap.parse_args()
    root = pathlib.Path(args.root).resolve()

    cfg = json.loads((root / CONFIG).read_text(encoding="utf-8"))
    flat = flatten(cfg)
    sections = sorted({k.split(".", 1)[0] for k in flat if "." in k})

    # Keys documented in README.md but living in a sibling config file. They
    # are real; they are simply not config.json defaults and are never value-
    # checked against it.
    sibling_keys = set()
    for name in SIBLINGS:
        path = root / ASSETS / name
        if not path.exists():
            continue
        keys = set(flatten(json.loads(path.read_text(encoding="utf-8"))))
        sibling_keys |= keys
        sibling_keys |= {k.rsplit(".", 1)[-1] for k in keys}

    # A documented key whose default disagrees with the shipped one. The key
    # name may also be section-relative; both are reported together.
    wrong_names = []
    ambiguous = []         # >1 section prefix resolves the token
    phantoms = []          # documented name does not ship at all
    candidates = []        # config-shaped token, does not ship -- review only
    covered = set()        # shipped keys confirmed documented

    for doc in docs(root):
        path = root / doc
        if not path.exists():
            continue
        text = path.read_text(encoding="utf-8")

        for m in ROW.finditer(text):
            cells = [c for c in m.group(1).split("|")]
            if len(cells) < 2:
                continue
            keys = FRAG.findall(cells[0])
            values = FRAG.findall(cells[1])
            if not keys:
                continue
            hint = heading_section(text, m.start())

            # Pair key fragments with value fragments. README.md writes
            # "`a` / `_b`" against "`1` / `2`", so the counts line up.
            base = None
            for i, frag in enumerate(keys):
                name = expand(base, frag) if base else frag
                if not name.startswith("_"):
                    base = name
                documented = values[i] if i < len(values) else None
                if documented is None:
                    continue
                key, status = resolve(name, flat, sections, hint)
                if status == "exact" or status == "prefixed":
                    covered.add(key)
                    if not same(documented, flat[key]):
                        wrong_names.append(
                            (doc, name, key, documented, json.dumps(flat[key]))
                        )
                elif status == "ambiguous":
                    ambiguous.append((doc, name))

        for m in TOKEN.finditer(text):
            token = m.group(1)
            hint = heading_section(text, m.start())
            key, status = resolve(token, flat, sections, hint)
            if status in ("exact", "prefixed"):
                covered.add(key)
            elif token in CONFIRMED_PHANTOM:
                phantoms.append(
                    (doc, token, CONFIRMED_PHANTOM[token])
                )
            elif status == "unknown" and token in sibling_keys:
                continue
            elif status == "unknown" and config_shaped(token):
                candidates.append((doc, token))

    # Bare fragments of a paired cell -- `import_tax_factor` inside
    # "`monarchy.import_tax_chance` / `import_tax_factor`" -- were already
    # resolved through their sibling by the row loop. Suppress the standalone
    # pass re-reporting them as invented.
    paired = {k.rsplit(".", 1)[-1] for k in covered}
    candidates = [c for c in candidates if c[1] not in paired]

    undocumented = sorted(set(flat) - covered)

    out = sys.stdout
    if not args.quiet:
        for doc, token, key, documented, real in wrong_names:
            out.write(
                f"WRONG  {doc}: {token} ships as {key} with default "
                f"{real}, documented {documented}\n"
            )
        for doc, token in sorted(set(ambiguous)):
            out.write(
                f"AMBIG  {doc}: {token} resolves under more than one "
                f"section; add the section prefix\n"
            )
        for doc, token, real in sorted(set(phantoms)):
            out.write(
                f"PHANTOM {doc}: {token} does not ship; real key is {real}\n"
            )
        for doc, token in sorted(set(candidates)):
            out.write(
                f"REVIEW {doc}: {token} is lower snake_case but does not "
                f"ship -- confirm it is not a config key\n"
            )

        out.write(f"\nshipped keys        {len(flat)}\n")
        out.write(f"documented keys     {len(covered)}\n")
        out.write(f"undocumented keys   {len(undocumented)}\n")
        for key in undocumented:
            out.write(f"  - {key}\n")
        out.write(f"wrong defaults      {len(wrong_names)}\n")
        out.write(f"ambiguous names     {len(set(ambiguous))}\n")
        out.write(f"phantom keys        {len(set(phantoms))}\n")
        out.write(f"review candidates   {len(set(candidates))}\n")

    errors = (
        len(wrong_names)
        + len(set(ambiguous))
        + len(set(phantoms))
        + len(undocumented)
    )
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())