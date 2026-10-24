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

## User-document language and audience

Documentation language is English for players, server owners and integrators. Do not mix languages within a page.
Player-facing pages contain no code, Gradle commands, test class names or internal identifiers. The
server-owner page may name shipped commands, config keys and permission nodes, but contains no source code
or internal implementation details. Integrator pages may contain Java.

The same policy is summarized in `wiki/_Sidebar.md` for documentation readers. Keep the policy stated in
these two places only.

## Publishing the documentation site

The `wiki/` directory is the canonical source for player, server-owner, and integrator guides. The Docusaurus
site reads these files directly; contributor guides come from `docs/contributing/`. Do not maintain copies of
these pages in the website directory.

`website/sidebars.ts` controls the site navigation. `wiki/_Sidebar.md` is retained for legacy wiki navigation
and is excluded from the Docusaurus docs. Links between Markdown guides should remain relative. Links from
`README.md` should use absolute URLs to the published Docusaurus pages under `/EconomyCraft/docs/`.

Pull requests build the site with `.github/workflows/docs-check.yml`. Changes to documentation on `main`
deploy through `.github/workflows/docs-deploy.yml`. Configure repository Pages to use **GitHub Actions** as
the source before the first deployment.
