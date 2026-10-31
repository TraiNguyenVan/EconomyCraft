# Contribution workflow

This guide defines the shared pull request and commit workflow. See the
[architecture](architecture.md), [documentation](documentation.md) and [toll](tolls.md) guides for
topic-specific details.

## Pull requests

- Use a title in the form `<type>: <concise summary>`, for example `fix: prevent duplicate toll charges` or
  `docs: clarify server configuration`. Use a lowercase type that describes the change; established types
  include `feat`, `fix`, `docs`, `test`, `ci` and `release`.
- Fill out the [pull request template](https://github.com/TraiNguyenVan/EconomyCraft/blob/main/.github/pull_request_template.md), including its summary,
  impact, compatibility, documentation and verification sections. When creating a PR with `gh pr create`,
  include the same information in the PR body.
- Keep the PR focused on one coherent change so its title and review describe the same work.

## Commits

- Stage only files for the intended change, then inspect the staged diff before committing.
- Keep each commit focused on one logical, reviewable change. Use the same `<type>: <concise summary>`
  subject format as PR titles, such as `fix: prevent duplicate toll charges`.
- Add a commit body when the reason or relevant context is not clear from the subject.
