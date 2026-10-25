import {useEffect, useRef, type ReactNode} from 'react';
import Link from '@docusaurus/Link';
import useBaseUrl from '@docusaurus/useBaseUrl';
import Layout from '@theme/Layout';
import Heading from '@theme/Heading';

import styles from './index.module.css';

type FeatureIconName = 'shop' | 'auction' | 'order' | 'quest' | 'balance' | 'profession' | 'faction' | 'api';
type FeatureTone = 'forest' | 'mint' | 'azure' | 'gold' | 'violet' | 'coral' | 'slate' | 'lime';

type Feature = {
  icon: FeatureIconName;
  category: 'PLAY' | 'COMMUNITY' | 'SERVER' | 'DEVELOPER';
  title: string;
  description: string;
  link: string;
  label: string;
  tone: FeatureTone;
};

const features: Feature[] = [
  {icon: 'shop', category: 'PLAY', title: 'A server-run shop', description: 'Set prices, organize categories, and let players buy or sell through a familiar in-game menu.', link: '/docs/Shop-and-Selling', label: 'How shops work', tone: 'forest'},
  {icon: 'auction', category: 'PLAY', title: 'Auctions', description: 'List rare finds, place offers, and let the best deal find its buyer.', link: '/docs/Auctions-Orders-and-Offers', label: 'Explore auctions', tone: 'mint'},
  {icon: 'order', category: 'PLAY', title: 'Wanted orders', description: 'Name the item you need and the reward. Someone in the world may have it.', link: '/docs/Auctions-Orders-and-Offers', label: 'Explore orders', tone: 'azure'},
  {icon: 'quest', category: 'COMMUNITY', title: 'Community quests', description: 'Pool server funds into requests that give everyone a reason to pitch in.', link: '/docs/Server-Quests', label: 'Explore quests', tone: 'gold'},
  {icon: 'balance', category: 'SERVER', title: 'Balances & payments', description: 'Give every player a durable balance, transfers, and a clear path from rewards to spending.', link: '/docs/Balances-and-Payments', label: 'Read about balances', tone: 'violet'},
  {icon: 'profession', category: 'SERVER', title: 'Professions', description: 'Let players choose a role and earn an identity that changes how they play.', link: '/docs/Choosing-a-Party-and-Profession', label: 'Choose a profession', tone: 'coral'},
  {icon: 'faction', category: 'COMMUNITY', title: 'Factions', description: 'Turn party choices into meaningful tax, claim, and movement tradeoffs.', link: '/docs/Factions', label: 'Explore factions', tone: 'slate'},
  {icon: 'api', category: 'DEVELOPER', title: 'Developer API', description: 'Connect balances, prices, leaderboards, and factions to the rest of your server stack.', link: '/docs/API-Reference', label: 'Read the API reference', tone: 'lime'},
];

function FeatureIcon({name}: {name: FeatureIconName}): ReactNode {
  const commonProps = {
    viewBox: '0 0 24 24',
    fill: 'none',
    stroke: 'currentColor',
    strokeLinecap: 'round' as const,
    strokeLinejoin: 'round' as const,
    strokeWidth: 1.7,
    'aria-hidden': true,
  };

  switch (name) {
    case 'shop':
      return <svg {...commonProps}><path d="M4 10h16M6 10v9h12v-9M5 10l1.1-5h11.8l1.1 5M9 14h6M9 19v-5h6v5" /></svg>;
    case 'auction':
      return <svg {...commonProps}><path d="m14 5 5 5M12.5 6.5l5 5M4 20l6.5-6.5M3 21h8M13 4l2-2 5 5-2 2M7 17l-3-3 5-5 3 3" /></svg>;
    case 'order':
      return <svg {...commonProps}><circle cx="10.5" cy="10.5" r="5.5" /><path d="m15 15 4 4M10.5 8v5M8.5 10h4" /></svg>;
    case 'quest':
      return <svg {...commonProps}><path d="M12 3 4.5 6v5c0 4.5 3.1 8.1 7.5 10 4.4-1.9 7.5-5.5 7.5-10V6L12 3Z" /><path d="m8.5 12 2.2 2.2 4.8-5" /></svg>;
    case 'balance':
      return <svg {...commonProps}><path d="M12 4v16M6 20h12M5 7h14M7 7l-3 6h6L7 7ZM17 7l-3 6h6l-3-6Z" /><path d="M9 4h6" /></svg>;
    case 'profession':
      return <svg {...commonProps}><path d="M4 18h16M6 18V9l6-5 6 5v9M9 18v-5h6v5M8 8h8" /></svg>;
    case 'faction':
      return <svg {...commonProps}><circle cx="8" cy="8" r="3" /><circle cx="16" cy="8" r="3" /><path d="M3.5 19c.6-3 2.1-4.5 4.5-4.5S11.9 16 12.5 19M11.5 19c.6-3 2.1-4.5 4.5-4.5s3.9 1.5 4.5 4.5" /></svg>;
    case 'api':
      return <svg {...commonProps}><path d="m8 8-4 4 4 4M16 8l4 4-4 4M14 5l-4 14" /></svg>;
  }
}

export default function Home(): ReactNode {
  const heroImage = useBaseUrl('/img/economycraft-hero.png');
  const economyRef = useRef<HTMLElement>(null);

  useEffect(() => {
    const section = economyRef.current;
    if (!section || typeof window === 'undefined') return;

    const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    if (reduceMotion) return;

    let cancelled = false;
    let revertAnimation = () => {};

    const animateSection = async () => {
      const [{gsap}, {ScrollTrigger}] = await Promise.all([
        import('gsap'),
        import('gsap/ScrollTrigger'),
      ]);

      if (cancelled) return;

      gsap.registerPlugin(ScrollTrigger);

      const intro = section.querySelector<HTMLElement>(`.${styles.sectionIntro}`);
      const grid = section.querySelector<HTMLElement>(`.${styles.featureGrid}`);
      const cards = section.querySelectorAll<HTMLElement>(`.${styles.featureCard}`);
      if (!intro || !grid || cards.length === 0) return;

      const context = gsap.context(() => {
        gsap.fromTo(intro, {opacity: 0, y: 28}, {
          opacity: 1,
          y: 0,
          duration: 0.7,
          ease: 'power3.out',
          scrollTrigger: {trigger: intro, start: 'top 84%', once: true},
        });

        gsap.fromTo(cards, {opacity: 0, y: 54, scale: 0.9, rotationX: 8, rotationZ: -1.5}, {
          opacity: 1,
          y: 0,
          scale: 1,
          rotationX: 0,
          rotationZ: 0,
          duration: 0.8,
          stagger: 0.08,
          ease: 'back.out(1.45)',
          transformPerspective: 900,
          scrollTrigger: {trigger: grid, start: 'top 88%', once: true},
        });

        gsap.to(cards, {
          yPercent: -3,
          ease: 'none',
          scrollTrigger: {trigger: grid, start: 'top bottom', end: 'bottom top', scrub: 1.2},
        });
      }, section);

      revertAnimation = () => context.revert();
    };

    void animateSection();

    return () => {
      cancelled = true;
      revertAnimation();
    };
  }, []);

  return (
    <Layout title="A player economy for your Minecraft server" description="Bring shops, auctions, wanted orders, and community quests to your Minecraft server with EconomyCraft.">
      <main>
        <section className={styles.hero} aria-labelledby="hero-title">
          <div className={`container ${styles.heroInner}`}>
            <div className={styles.heroCopy}>
              <p className={styles.heroMark}><span aria-hidden="true">E</span> EconomyCraft for server owners</p>
              <Heading as="h1" id="hero-title">Make your world<br className={styles.desktopBreak} /> <span className={styles.heroAccent}>worth trading in.</span></Heading>
              <p className={styles.heroLead}>Give players a real reason to gather, build, and trade—with a complete economy that lives in Minecraft.</p>
              <div className={styles.actions}>
                <Link className={styles.primaryCta} to="/docs/Installation-and-Compatibility">Install EconomyCraft</Link>
                <Link className={styles.secondaryCta} to="/docs/Server-Administration">Read the server guide</Link>
              </div>
              <p className={styles.heroNote}><span>No client mod required</span><span>Built for vanilla clients</span></p>
            </div>

            <figure className={styles.screenshot}>
              <div className={styles.screenshotChrome} aria-hidden="true">
                <span className={styles.windowDots}><i /><i /><i /></span>
                <span className={styles.screenshotLabel}>ECONOMYCRAFT / MARKET MENU</span>
                <span className={styles.screenshotTag}>IN-GAME PREVIEW</span>
              </div>
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

        <section ref={economyRef} className={`container ${styles.economy}`} aria-labelledby="economy-title">
          <div className={styles.sectionIntro}>
            <Heading as="h2" id="economy-title">A living economy,<br className={styles.desktopBreak} /> built around your players.</Heading>
            <p>Turn mined resources into useful trades, friendly competition, and projects the whole server can get behind.</p>
          </div>
          <div className={styles.featureGrid} aria-label="EconomyCraft capabilities">
            {features.map((feature) => (
              <Link className={styles.featureCard} data-tone={feature.tone} key={feature.title} to={feature.link} aria-label={`${feature.title}: ${feature.label}`}>
                <span className={styles.featureIcon}><FeatureIcon name={feature.icon} /></span>
                <div className={styles.featureCopy}>
                  <span className={styles.featureCategory}>{feature.category}</span>
                  <Heading as="h3">{feature.title}</Heading>
                  <p>{feature.description}</p>
                  <span className={styles.featureLink}>{feature.label}<svg className={styles.featureArrow} viewBox="0 0 18 18" aria-hidden="true"><path d="M3 9h11M9.5 4.5 14 9l-4.5 4.5" /></svg></span>
                </div>
              </Link>
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
