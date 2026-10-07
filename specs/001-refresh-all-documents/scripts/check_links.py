#!/usr/bin/env python3
"""Link checker for the documentation set. Implements check V4.

Two classes of problem are reported separately because they need different
fixes:

  WIKI-UPSTREAM  An absolute URL pointing at PhilipB06/EconomyCraft/wiki.
                 The fix is to change the URL to this fork's wiki.

  TARGET         A relative link whose target does not exist. The fix is to
                 create, rename, or re-point the target.

Defect fixed from quickstart.md V4: wiki-internal relative links carry no `.md`
extension, because that is how a GitHub wiki addresses pages. The old checker
tested `base / link` for existence and so reported all ten correct
`_Sidebar.md` links as broken. A relative target is now retried with `.md`
before being called broken.

Usage:
    python3 check_links.py [--root REPO]
"""

from __future__ import annotations

import argparse
import pathlib
import re
import sys

CANONICAL_WIKI = "https://github.com/TraiNguyenVan/EconomyCraft/wiki"
UPSTREAM = "github.com/PhilipB06/EconomyCraft"

LINK = re.compile(r"\[([^\]]*)\]\(([^)\s]+)\)")

# Must not be touched: upstream attribution and GPL notice.
PROTECTED_FILES = ("README.md",)


def docs(root):
    return ["README.md"] + sorted(
        relative(p, root) for p in (root / "wiki").glob("*.md")
    )


def line_of(text, pos):
    return text.count("\n", 0, pos) + 1


def fix_wiki_url(link):
    """Rewrite an upstream wiki URL onto this fork's wiki.

    Only the page path is grafted: CANONICAL_WIKI already carries its scheme,
    so re-adding one produced a doubled `https://`.
    """
    # Everything after the upstream wiki base is the page path, and is kept
    # verbatim. Discarding the scheme and host along with it avoids both a
    # doubled `https://` and a mangled page path.
    tail = link.split(f"{UPSTREAM}/wiki", 1)[1]
    return CANONICAL_WIKI + tail


def relative(path, root):
    """Repo-relative display path. An absolute path is unreadable in a report."""
    try:
        return str(path.relative_to(root))
    except ValueError:
        return str(path)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=".")
    args = ap.parse_args()
    root = pathlib.Path(args.root).resolve()

    upstream_links = []
    missing = []
    checked = 0

    for doc in docs(root):
        path = root / doc
        if not path.exists():
            continue
        text = path.read_text(encoding="utf-8")
        base = path.parent

        for m in LINK.finditer(text):
            label, link = m.group(1), m.group(2)
            line = line_of(text, m.start())

            if link.startswith(("http://", "https://")):
                checked += 1
                if UPSTREAM in link and "/wiki" in link:
                    upstream_links.append(
                        (doc, line, label, link, fix_wiki_url(link))
                    )
                continue

            if link.startswith("#") or link.startswith("mailto:"):
                continue

            checked += 1
            target = (base / link.split("#", 1)[0]).resolve()
            # A wiki page link omits .md; retry with it before failing.
            if not target.exists() and target.with_suffix(".md").exists():
                continue
            if not target.exists():
                missing.append((doc, line, label, link))

    out = sys.stdout
    for doc, line, label, link, fixed in upstream_links:
        out.write(f"WIKI-UPSTREAM  {doc}:{line}  [{label}]({link})\n")
        out.write(f"               fix: {fixed}\n")
    for doc, line, label, link in missing:
        out.write(f"TARGET         {doc}:{line}  [{label}]({link}) has no target\n")

    out.write(f"\ncanonical wiki  {CANONICAL_WIKI}\n")
    out.write(f"links checked   {checked}\n")
    out.write(f"upstream wiki   {len(upstream_links)}\n")
    out.write(f"missing targets {len(missing)}\n")

    return 1 if (upstream_links or missing) else 0


if __name__ == "__main__":
    sys.exit(main())