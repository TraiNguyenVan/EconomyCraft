package com.reazip.economycraft.tag;

import com.reazip.economycraft.EconomyConfig;
import com.reazip.economycraft.faction.FactionId;
import com.reazip.economycraft.profession.ProfessionId;
import com.reazip.economycraft.profession.ProfessionLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tag surfaces, with the stores faked.
 *
 * <p>Scope, stated up front because it is narrow on purpose. What is asserted here is everything that decides
 * <em>what text is produced and when it is rebuilt</em>: composition, the two D22 rules, the label rule, and the
 * staleness check that makes the cache self-healing. What is <em>not</em> asserted is anything that needs a live
 * server — pushing {@code UPDATE_DISPLAY_NAME}, creating and moving scoreboard teams — because those paths take a
 * {@code ServerPlayer} and a {@code MinecraftServer}, and a unit test that mocks them would only assert the mock.
 * Those are covered by the fact that a dev server boots with both mixins applied and no exception, and by hand on a
 * real client.
 */
class TagDisplayServiceTest {

    private static final UUID PLAYER = UUID.nameUUIDFromBytes("tag-display-test".getBytes());

    /** A {@link TagDisplayService.TagSource} backed by three mutable fields, which is the whole fake. */
    private static final class FakeSource implements TagDisplayService.TagSource {

        private FactionId faction;
        private boolean chosen = true;
        private ProfessionId profession;
        private ProfessionLevel level = ProfessionLevel.APPRENTICE;

        @Override
        public FactionId factionOf(UUID player) {
            return faction;
        }

        @Override
        public boolean hasChosenFaction(UUID player) {
            return chosen;
        }

        @Override
        public ProfessionId professionOf(UUID player) {
            return profession;
        }

        @Override
        public ProfessionLevel levelOf(UUID player) {
            return level;
        }
    }

    @TempDir
    Path world;

    private FakeSource source;
    private TagDisplayService service;

    /**
     * Loads the real shipped config rather than hand-writing icons and colours, so these assertions are about what
     * players will actually see — {@code [☭]} for Communism, not whatever a test invented. It is the same
     * {@link EconomyConfig#load} path {@code BundledConfigTest} uses, and it matters because a tag's icon and
     * colour are read from those records rather than from anything the display layer owns.
     */
    @BeforeEach
    void setUp() {
        MinecraftServer server = mock(MinecraftServer.class);
        when(server.isDedicatedServer()).thenReturn(false);
        when(server.getWorldPath(LevelResource.ROOT)).thenReturn(world);
        EconomyConfig.load(server);

        source = new FakeSource();
        service = new TagDisplayService(source);
    }

    private static String plain(Component component) {
        return component.getString();
    }

    @Test
    void untaggedPlayerGetsNothingAtAll() {
        assertTrue(service.tagsOf(PLAYER).isEmpty());
        assertEquals("", plain(service.teamPrefixForTest(PLAYER)));
        assertNull(service.tabRowFor(PLAYER, Component.literal("Steve")));
    }

    @Test
    void aPlayerWhoHasNotChosenWearNoPartyTag() {
        // The store answers Anarchism for anyone who was never asked, so drawing `factionOf` alone would label
        // every undecided player as an Anarchist — and, since a party is opt-in, that is a false statement about
        // them rather than a default. The tab list, the nametag and the team key must all agree on this.
        source.faction = FactionId.ANARCHISM;
        source.chosen = false;

        assertTrue(service.tagsOf(PLAYER).isEmpty());
        assertEquals("", plain(service.teamPrefixForTest(PLAYER)));
        assertNull(service.tabRowFor(PLAYER, Component.literal("Steve")));
        assertEquals("ec_no_no", service.teamKeyOf(PLAYER));

        // Choosing is what puts the tag back, and it is the sweep that notices without anyone calling refresh().
        source.chosen = true;
        assertTrue(service.isStale(PLAYER));
        service.revalidate(PLAYER);
        assertEquals(1, service.tagsOf(PLAYER).size());
    }

    @Test
    void aProfessionSurvivesHavingNoParty() {
        // The mirror of the rule above: choosing a job is a real choice and its tag is shown, while the party
        // slot stays empty rather than being filled in from the default.
        source.faction = FactionId.ANARCHISM;
        source.chosen = false;
        source.profession = ProfessionId.BUILDER;

        assertEquals(1, service.tagsOf(PLAYER).size());
        assertEquals(ProfessionId.BUILDER.displayName(), labelOf());
    }

    @Test
    void bothTagsRenderPartyFirstThenProfession() {
        source.faction = FactionId.COMMUNISM;
        source.profession = ProfessionId.BUILDER;

        String row = plain(TagStyle.tabRow(service.tagsOf(PLAYER), Component.literal("Steve")));
        assertEquals("[" + FactionId.COMMUNISM.displayName() + "][" + ProfessionId.BUILDER.displayName() + "] Steve",
                row);
    }

    @Test
    void professionTagCarriesItsLevelOnlyWhenItIsWorthSaying() {
        source.profession = ProfessionId.MINER;

        source.level = ProfessionLevel.APPRENTICE;
        assertEquals(ProfessionId.MINER.displayName(), labelOf());

        // Master and Rusted are the two states that change what a player can do, so the tab row interrupts the name
        // for them. Apprentice is the default and saying so on every row would be noise.
        // Reads are served from the cache on purpose, so each level change goes through the same refresh the
        // server tick uses. Without this the test would be asserting that a read is live, which is the opposite of
        // the design (P3-T4 forbids a store lookup per rendered line).
        source.level = ProfessionLevel.MASTER;
        service.revalidate(PLAYER);
        assertEquals(ProfessionId.MINER.displayName() + ": " + ProfessionLevel.MASTER.displayName(), labelOf());

        source.level = ProfessionLevel.RUSTED;
        service.revalidate(PLAYER);
        assertEquals(ProfessionId.MINER.displayName() + ": " + ProfessionLevel.RUSTED.displayName(), labelOf());
    }

    @Test
    void changingAFactionMakesTheCacheStale() {
        source.faction = FactionId.COMMUNISM;
        assertTrue(service.isStale(PLAYER), "an uncached player is stale by definition");

        // Reading the tags populates the cache, which is what makes the next assertion meaningful.
        service.tagsOf(PLAYER);
        assertFalse(service.isStale(PLAYER), "nothing changed, so nothing to re-push");

        source.faction = FactionId.MONARCHY;
        assertTrue(service.isStale(PLAYER), "a party change must be noticed without anyone calling refresh()");

        source.faction = FactionId.COMMUNISM;
        assertFalse(service.isStale(PLAYER), "changing back is still a change the sweep has to see");
    }

    @Test
    void aLevelChangeAlsoMakesTheCacheStale() {
        source.profession = ProfessionId.FARMER;
        source.level = ProfessionLevel.APPRENTICE;
        service.tagsOf(PLAYER);
        assertFalse(service.isStale(PLAYER));

        // This is the case the sweep exists for: Phase 5's level-up and Phase 9's rust transition both change the
        // tag, and neither should have to remember to invalidate anything.
        source.level = ProfessionLevel.MASTER;
        assertTrue(service.isStale(PLAYER));
    }

    @Test
    void tagsAreIndependentOfTheOrderTheStoreReportsThem() {
        source.profession = ProfessionId.MERCHANT;
        assertEquals(1, service.tagsOf(PLAYER).size());
        source.faction = FactionId.ANARCHISM;
        service.revalidate(PLAYER);
        assertEquals(2, service.tagsOf(PLAYER).size());
        // A profession without a party is a legitimate state: the default party is a read-time fallback, so a
        // player who has chosen a job but not a party really does exist.
        assertEquals(ProfessionId.MERCHANT.displayName(), labelOf());
    }

    @Test
    void teamPrefixIsEmptyWithoutTagsAndIconOnlyWith() {
        assertEquals("", plain(service.teamPrefixForTest(PLAYER)));

        source.faction = FactionId.MONARCHY;
        service.revalidate(PLAYER);
        assertEquals("[♔] ", plain(service.teamPrefixForTest(PLAYER)));

        source.profession = ProfessionId.SOLDIER;
        service.revalidate(PLAYER);
        assertEquals("[♔][⚔] ", plain(service.teamPrefixForTest(PLAYER)));
    }

    private String labelOf() {
        List<TagStyle.Tagged> tags = new ArrayList<>(service.tagsOf(PLAYER));
        return tags.get(tags.size() - 1).label();
    }
}