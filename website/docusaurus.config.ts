import {themes as prismThemes} from 'prism-react-renderer';
import type {Config} from '@docusaurus/types';
import type * as Preset from '@docusaurus/preset-classic';

const config: Config = {
  title: 'EconomyCraft Wiki',
  tagline: 'A server-side economy for Fabric and NeoForge',
  favicon: 'img/favicon.svg',
  url: 'https://trainguyenvan.github.io',
  baseUrl: '/EconomyCraft/',
  organizationName: 'TraiNguyenVan',
  projectName: 'EconomyCraft',
  onBrokenLinks: 'throw',
  markdown: {hooks: {onBrokenMarkdownLinks: 'throw'}},
  i18n: {defaultLocale: 'en', locales: ['en']},
  presets: [
    [
      'classic',
      {
        docs: {
          path: '../wiki',
          routeBasePath: 'docs',
          exclude: ['**/_Sidebar.md'],
          sidebarPath: './sidebars.ts',
          editUrl: 'https://github.com/TraiNguyenVan/EconomyCraft/edit/main/wiki/',
        },
        blog: false,
        theme: {customCss: './src/css/custom.css'},
      } satisfies Preset.Options,
    ],
  ],
  plugins: [
    [
      '@docusaurus/plugin-content-docs',
      {
        id: 'contributors',
        path: '../docs/contributing',
        routeBasePath: 'contributors',
        sidebarPath: './sidebars.contributors.ts',
        editUrl: 'https://github.com/TraiNguyenVan/EconomyCraft/edit/main/docs/contributing/',
      },
    ],
  ],
  themes: [
    [
      '@easyops-cn/docusaurus-search-local',
      {
        hashed: true,
        docsDir: ['../wiki', '../docs/contributing'],
        docsRouteBasePath: ['docs', 'contributors'],
        indexBlog: false,
      },
    ],
  ],
  themeConfig: {
    image: 'img/social-card.svg',
    colorMode: {respectPrefersColorScheme: true},
    navbar: {
      title: 'EconomyCraft',
      logo: {alt: 'EconomyCraft', src: 'img/logo.svg'},
      items: [
        {type: 'docSidebar', sidebarId: 'wikiSidebar', position: 'left', label: 'Wiki'},
        {to: '/contributors/architecture', label: 'Contributing', position: 'left'},
        {href: 'https://github.com/TraiNguyenVan/EconomyCraft', label: 'GitHub', position: 'right'},
      ],
    },
    footer: {
      style: 'dark',
      links: [
        {title: 'Guides', items: [{label: 'Getting started', to: '/docs/Getting-Started'}, {label: 'Server administration', to: '/docs/Server-Administration'}]},
        {title: 'Development', items: [{label: 'Contributor guides', to: '/contributors/architecture'}, {label: 'GitHub', href: 'https://github.com/TraiNguyenVan/EconomyCraft'}]},
      ],
      copyright: `EconomyCraft is licensed under GPL-3.0. Built with Docusaurus.`,
    },
    prism: {theme: prismThemes.github, darkTheme: prismThemes.dracula},
  } satisfies Preset.ThemeConfig,
};

export default config;
