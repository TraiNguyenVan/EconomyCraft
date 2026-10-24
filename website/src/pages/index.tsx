import type {ReactNode} from 'react';
import Link from '@docusaurus/Link';
import useBaseUrl from '@docusaurus/useBaseUrl';
import Layout from '@theme/Layout';
import Heading from '@theme/Heading';

import styles from './index.module.css';

const features = [
  {icon: '▤', title: 'A server-run shop', description: 'Set prices, organize categories, and let players buy or sell through a familiar in-game menu.', link: '/docs/Shop-and-Selling', label: 'How shops work'},
  {icon: '◇', title: 'Auctions', description: 'List rare finds, place offers, and let the best deal find its buyer.', link: '/docs/Auctions-Orders-and-Offers', label: 'Explore auctions'},
  {icon: '⌕', title: 'Wanted orders', description: 'Name the item you need and the reward. Someone in the world may have it.', link: '/docs/Auctions-Orders-and-Offers', label: 'Explore orders'},
  {icon: '✦', title: 'Community quests', description: 'Pool server funds into requests that give everyone a reason to pitch in.', link: '/docs/Server-Quests', label: 'Explore quests'},
];

export default function Home(): ReactNode {
  const heroImage = useBaseUrl('/img/economycraft-hero.png');

  return (
    <Layout title="A player economy for your Minecraft server" description="Bring shops, auctions, wanted orders, and community quests to your Minecraft server with EconomyCraft.">
      <main>
        <section className={styles.hero} aria-labelledby="hero-title">
          <div className={`container ${styles.heroInner}`}>
            <p className={styles.heroMark}><span aria-hidden="true">E</span> EconomyCraft for server owners</p>
            <Heading as="h1" id="hero-title">Make your world<br className={styles.desktopBreak} /> worth trading in.</Heading>
            <p className={styles.heroLead}>Give players a real reason to gather, build, and trade—with a complete economy that lives in Minecraft.</p>
            <div className={styles.actions}>
              <Link className={styles.primaryCta} to="/docs/Installation-and-Compatibility">Install EconomyCraft</Link>
              <Link className={styles.secondaryCta} to="/docs/Server-Administration">Read the server guide</Link>
            </div>

            <figure className={styles.screenshot}>
              <img src={heroImage} alt="EconomyCraft's in-game economy menu open over a Minecraft market square" width="1920" height="1054" fetchPriority="high" />
              <figcaption>Your market, right in the world</figcaption>
            </figure>
          </div>
        </section>

        <section className={styles.compatibility} aria-label="Compatibility">
          <div className={`container ${styles.compatibilityInner}`}>
            <span className={styles.compatibilityLabel}>Ready for your server</span>
            <strong>Minecraft 26.3</strong>
            <span className={styles.separator} aria-hidden="true" />
            <strong>Fabric &amp; NeoForge</strong>
            <span className={styles.separator} aria-hidden="true" />
            <strong>Java 25</strong>
            <span className={styles.separator} aria-hidden="true" />
            <strong>Vanilla clients</strong>
            <span className={styles.requirement}>Needs Architectury API · ShopGuard is optional for faction features on Fabric.</span>
          </div>
        </section>

        <section className={`container ${styles.economy}`} aria-labelledby="economy-title">
          <div className={styles.sectionIntro}>
            <Heading as="h2" id="economy-title">A living economy,<br className={styles.desktopBreak} /> built around your players.</Heading>
            <p>Turn mined resources into useful trades, friendly competition, and projects the whole server can get behind.</p>
          </div>
          <div className={styles.featureGrid}>
            {features.map((feature) => (
              <article className={styles.featureCard} key={feature.title}>
                <span className={styles.featureIcon} aria-hidden="true">{feature.icon}</span>
                <div className={styles.featureCopy}>
                  <Heading as="h3">{feature.title}</Heading>
                  <p>{feature.description}</p>
                  <Link to={feature.link}>{feature.label}</Link>
                </div>
              </article>
            ))}
          </div>
          <nav className={styles.resourceLinks} aria-label="More EconomyCraft resources">
            <span>More to explore</span>
            <Link to="/docs/Choosing-a-Party-and-Profession">Player guides</Link>
            <Link to="/docs/API-Reference">Developer API</Link>
            <Link to="/contributors/architecture">Contributing</Link>
            <Link href="https://github.com/TraiNguyenVan/EconomyCraft/releases">Latest release</Link>
          </nav>
        </section>
      </main>
    </Layout>
  );
}
