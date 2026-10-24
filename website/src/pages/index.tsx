import type {ReactNode} from 'react';
import Link from '@docusaurus/Link';
import useBaseUrl from '@docusaurus/useBaseUrl';
import Layout from '@theme/Layout';
import Heading from '@theme/Heading';

import styles from './index.module.css';

const features = [
  {number: '01', title: 'A server-run shop', description: 'Set prices, organize categories, and let players buy or sell through a familiar in-game menu.', link: '/docs/Shop-and-Selling', label: 'Explore the shop'},
  {number: '02', title: 'Player auctions', description: 'Give players a place to list items, make offers, and find the next deal.', link: '/docs/Auctions-Orders-and-Offers', label: 'Explore auctions'},
  {number: '03', title: 'Wanted orders', description: 'Players post what they need and what they will pay. Other players deliver the goods.', link: '/docs/Auctions-Orders-and-Offers', label: 'Explore orders'},
  {number: '04', title: 'Server-funded quests', description: 'Turn the market into a shared goal with a board of item requests and rewards.', link: '/docs/Server-Quests', label: 'Explore quests'},
];

export default function Home(): ReactNode {
  const heroImage = useBaseUrl('/img/economycraft-hero.png');

  return (
    <Layout title="A server-side economy mod" description="Give your Minecraft server a player economy with shops, auctions, orders, and quests. Works with vanilla clients on Fabric and NeoForge.">
      <main>
        <header className={styles.hero}>
          <div className={`container ${styles.heroLayout}`}>
            <div className={styles.heroCopy}>
              <p className={styles.kicker}><span aria-hidden="true" /> BUILT FOR YOUR SERVER</p>
              <Heading as="h1">Give every trade a place to happen.</Heading>
              <p className={styles.lead}>A complete player economy for Fabric and NeoForge: shops, auctions, orders, and quests. Your players join with the vanilla client they already have.</p>
              <div className={styles.actions}>
                <Link className={`button button--primary button--lg ${styles.primaryCta}`} to="/docs/Installation-and-Compatibility">Installation guide</Link>
                <Link className={styles.textLink} to="/docs/Server-Administration">Server administration <span aria-hidden="true">↗</span></Link>
              </div>
            </div>
            <figure className={styles.screenshot}>
              <img src={heroImage} alt="EconomyCraft's in-game economy menu open over a Minecraft market square" width="1920" height="1054" fetchPriority="high" />
              <figcaption><span className={styles.liveDot} aria-hidden="true" /> EconomyCraft, in game</figcaption>
            </figure>
          </div>
        </header>

        <section className={styles.compatibility} aria-label="Compatibility">
          <div className={`container ${styles.compatibilityInner}`}>
            <div><span>GAME VERSION</span><strong>Minecraft 26.3</strong></div>
            <div><span>LOADERS</span><strong>Fabric <i aria-hidden="true">/</i> NeoForge</strong></div>
            <div><span>JAVA</span><strong>25</strong></div>
            <div><span>PLAYER CLIENT</span><strong>Vanilla</strong></div>
            <p>Architectury API required. <Link href="https://github.com/TraiNguyenVan/shopguard">ShopGuard</Link> is optional for claim-based faction features on Fabric.</p>
          </div>
        </section>

        <section className={`container ${styles.features}`} aria-labelledby="features-heading">
          <div className={styles.sectionHeading}>
            <div>
              <p className={styles.eyebrow}>THE ECONOMY, IN MOTION</p>
              <Heading as="h2" id="features-heading">More than a balance command.</Heading>
            </div>
            <p>Give players useful ways to earn, trade, and work together—right inside your world.</p>
          </div>
          <div className={styles.featureGrid}>
            {features.map((feature) => (
              <article className={styles.featureCard} key={feature.number}>
                <span className={styles.featureNumber}>{feature.number}</span>
                <div>
                  <Heading as="h3">{feature.title}</Heading>
                  <p>{feature.description}</p>
                  <Link to={feature.link}>{feature.label}<span aria-hidden="true"> ↗</span></Link>
                </div>
              </article>
            ))}
          </div>
          <div className={styles.moreLinks}>
            <span>Looking for something else?</span>
            <Link to="/docs/Choosing-a-Party-and-Profession">Player guides</Link>
            <Link to="/docs/API-Reference">Developer API</Link>
            <Link to="/contributors/architecture">Contributing</Link>
            <Link href="https://github.com/TraiNguyenVan/EconomyCraft/releases">Latest release</Link>
          </div>
        </section>
      </main>
    </Layout>
  );
}
