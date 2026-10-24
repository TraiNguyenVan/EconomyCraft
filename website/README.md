# EconomyCraft documentation site

This Docusaurus app publishes the existing `wiki/` guides and `docs/contributing/` references. Edit those source files directly; the site does not keep copies of their content.

## Develop locally

```sh
npm ci
npm start
```

The production build, including broken-link checks and local search indexing, runs with:

```sh
npm run build
```

Wiki pages are served under `/docs/`; contributor guides are under `/contributors/`.

## Publish

`.github/workflows/docs-deploy.yml` builds the site when documentation changes land on `main` and deploys it with GitHub Pages. In repository settings, choose **Settings → Pages → Build and deployment → GitHub Actions** as the source.
