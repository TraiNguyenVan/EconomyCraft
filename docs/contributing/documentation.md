# Documentation contributor guide

## Source of truth

Use these sources in order:

1. `common/src/main/resources/assets/economycraft/config.json` for configuration names and defaults.
2. Registered commands, `util/EconomyPermissions.java`, and the public API package for commands, permissions
   and API types.
3. `build.gradle` and `gradle.properties` for supported platforms, Java versions and mod version.

Document every shipped command, permission, config key and public type accurately; do not include anything
that does not ship. State each default once. Keys are case-sensitive and must match their shipped spelling.
Do not include phase numbers, task identifiers or internal decision IDs in user-facing docs. Preserve the
GPL-3.0 upstream attribution in `README.md`.

## Wiki language and audience

Wiki page language is English for players, server owners and integrators. Do not mix languages within a page.
Player-facing pages contain no code, Gradle commands, test class names or internal identifiers. The
server-owner page may name shipped commands, config keys and permission nodes, but contains no source code
or internal implementation details. Integrator pages may contain Java.

The same policy is summarized in `wiki/_Sidebar.md` for wiki readers. Keep the policy stated in these two
places only.

## Publishing the GitHub wiki

The `wiki/` directory in this repository is not the hosted GitHub wiki; that wiki is a separate Git clone.
Copy changed pages to the wiki clone, commit and push them there. Include `_Sidebar.md` whenever navigation
changes, then check the hosted page because GitHub may serve a stale cached render. Wiki-to-wiki links are
relative; links from `README.md` to wiki pages must use absolute GitHub wiki URLs.
