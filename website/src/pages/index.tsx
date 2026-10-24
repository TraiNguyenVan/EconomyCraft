import type {ReactNode} from 'react';
import Link from '@docusaurus/Link';
import Layout from '@theme/Layout';
import Heading from '@theme/Heading';

import styles from './index.module.css';

const sections = [
  {title: 'For players', description: 'Choose a faction and profession, then explore trading, quests, tolls, and contracts.', link: '/docs/Choosing-a-Party-and-Profession', label: 'Browse gameplay guides'},
  {title: 'For server owners', description: 'Install the right jar, configure the server, and review available commands and permissions.', link: '/docs/Server-Administration', label: 'Read server documentation'},
  {title: 'For mod developers', description: 'Use the public API to read balances and prices, make payments, and listen for balance events.', link: '/docs/API-Reference', label: 'Read the API reference'},
  {title: 'For contributors', description: 'Learn how the project is structured and how to contribute changes.', link: '/contributors/architecture', label: 'Read contributor guides'},
];

export default function Home(): ReactNode {
  return (
    <Layout title="A server-side economy mod" description="Player, server owner, and integrator guides for EconomyCraft.">
      <main>
        <header className={styles.hero}>
          <div className={`container ${styles.heroLayout}`}>
            <div>
              <p className={styles.kicker}>A server economy for Minecraft</p>
              <Heading as="h1">Make room for a different kind of trade.</Heading>
              <p className={styles.lead}>Factions, professions, shops, and an API for Fabric and NeoForge servers. Players join with the vanilla client they already use.</p>
              <div className={styles.actions}>
              <Link className={`button button--primary button--lg ${styles.primaryCta}`} to="/docs/Installation-and-Compatibility">Installation guide</Link>
                <Link className={styles.textLink} to="/docs/Server-Administration">Server owner guide</Link>
              </div>
            </div>
            <aside className={styles.ledger} aria-label="EconomyCraft compatibility">
              <p className={styles.ledgerTitle}>At a glance</p>
              <dl>
                <div><dt>Game</dt><dd>Minecraft 26.3</dd></div>
                <div><dt>Loaders</dt><dd>Fabric and NeoForge</dd></div>
                <div><dt>Java</dt><dd>25</dd></div>
                <div><dt>Client</dt><dd>Vanilla compatible</dd></div>
              </dl>
              <p className={styles.ledgerNote}>Architectury API required. <a href="https://github.com/TraiNguyenVan/shopguard">ShopGuard</a> is optional for claim-based faction features on Fabric.</p>
            </aside>
          </div>
        </header>
        <section className={`container ${styles.section}`} aria-labelledby="guide-heading">
          <div className={styles.sectionHeading}>
            <Heading as="h2" id="guide-heading">Find your way in</Heading>
            <p>Choose a guide for what you want to do.</p>
          </div>
          <div className={styles.guideList}>
            {sections.map((section) => (
              <article className={styles.guide} key={section.title}>
                <Heading as="h3">{section.title}</Heading>
                <p>{section.description}</p>
                <Link to={section.link}>{section.label}</Link>
              </article>
            ))}
          </div>
          <p className={styles.release}>Download the <Link href="https://github.com/TraiNguyenVan/EconomyCraft/releases">latest release</Link> from GitHub.</p>
        </section>
      </main>
    </Layout>
  );
}
