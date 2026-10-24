import type {SidebarsConfig} from '@docusaurus/plugin-content-docs';

const sidebars: SidebarsConfig = {
  wikiSidebar: [
    'Installation-and-Compatibility',
    {
      type: 'category',
      label: 'Gameplay',
      link: {type: 'doc', id: 'Gameplay'},
      items: [
        'Choosing-a-Party-and-Profession',
        'Factions',
        'Professions',
        'Tolls',
        'Shop-and-Selling',
        'Auctions-Orders-and-Offers',
        'Contracts',
        'Balances-Daily-Rewards-and-Leaderboards',
        'Server-Quests',
        'Villager-Dialogue',
      ],
    },
    'Server-Administration',
    'Server-Configuration',
    'Backups-and-Migration',
    {
      type: 'category',
      label: 'API and integrations',
      items: ['Home', 'Getting-Started', 'Balances-and-Payments', 'Prices-and-Leaderboard', 'Balance-Events', 'API-Reference'],
    },
  ],
};

export default sidebars;
