package com.reazip.economycraft.tag;

import com.reazip.economycraft.EconomyConfig;
import com.reazip.economycraft.config.ProfessionSettings;
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
        private ProfessionId profession;
        private ProfessionLevel level = ProfessionLevel.APPRENTICE;

        @Override
        public FactionId factionOf(UUID player) {
            return faction;
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
    void aPlayerWhoHasNotChosenWearsTheAnarchistTag() {
        // The store answers Anarchism for anyone who was never asked, and the tag follows the party a player
        // is actually subject to - tax, land and speed all read the same id. A tag that disagreed with the
        // rules would be the one surface every player always sees telling them something false.
        source.faction = FactionId.ANARCHISM;
        assertEquals(1, service.tagsOf(PLAYER).size());

        // Switching party is what changes it, and it is the sweep that notices without anyone calling refresh().
        source.faction = FactionId.COMMUNISM;
        assertTrue(service.isStale(PLAYER));
        service.revalidate(PLAYER);
        assertEquals(1, service.tagsOf(PLAYER).size());
    }

    @Test
    void noPartySystemMeansNoTags() {
        // The one genuinely tagless state: no backend, so factionOf is null. Not the same as "undecided".
        source.faction = null;
        assertTrue(service.tagsOf(PLAYER).isEmpty());
        assertEquals("", plain(service.teamPrefixForTest(PLAYER)));
        assertNull(service.tabRowFor(PLAYER, Component.literal("Steve")));
        assertEquals("ec_no_no", service.teamKeyOf(PLAYER));
    }

    @Test
    void aProfessionIsDrawnAlongsideWhicheverPartyThePlayerReadsAs() {
        // Choosing a job is a real choice and its tag is shown, next to the party tag rather than instead of it.
        source.faction = FactionId.ANARCHISM;
        source.profession = ProfessionId.BUILDER;

        assertEquals(2, service.tagsOf(PLAYER).size());
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
    void professionTagNamesTheLevelOnlyWhenItIsAWarning() {
        source.profession = ProfessionId.MINER;

        source.level = ProfessionLevel.APPRENTICE;
        assertEquals(ProfessionId.MINER.displayName(), labelOf());

        // Master is carried by the << >> frame on the tag itself, so spelling it out would only interrupt the
        // name. Rusted keeps its word: half the job's effect is gone and the player has to be able to see that.
        // Reads are served from the cache on purpose, so each level change goes through the same refresh the
        // server tick uses. Without this the test would be asserting that a read is live, which is the opposite of
        // the design (P3-T4 forbids a store lookup per rendered line).
        source.level = ProfessionLevel.MASTER;
        service.revalidate(PLAYER);
        assertEquals(ProfessionId.MINER.displayName(), labelOf());

        source.level = ProfessionLevel.RUSTED;
        service.revalidate(PLAYER);
        assertEquals(ProfessionId.MINER.displayName() + ": " + ProfessionLevel.RUSTED.displayName(), labelOf());
    }

    @Test
    void masterIsFramedRatherThanRelabelled() {
        source.profession = ProfessionId.MINER;

        source.level = ProfessionLevel.APPRENTICE;
        service.revalidate(PLAYER);
        assertEquals("[" + ProfessionId.MINER.displayName() + "] Steve",
                plain(TagStyle.tabRow(service.tagsOf(PLAYER), Component.literal("Steve"))));

        source.level = ProfessionLevel.MASTER;
        service.revalidate(PLAYER);
        assertEquals("\u00ab" + ProfessionId.MINER.displayName() + "\u00bb Steve",
                plain(TagStyle.tabRow(service.tagsOf(PLAYER), Component.literal("Steve"))));
    }

    /**
     * The brackets and the content are separate styled runs, so the colour has to be read off the children
     * rather than off the tag itself. A tag that carried its own style would tint the icon with the brackets,
     * which is the thing this split exists to prevent.
     */
    @Test
    void masterBracketsAreGoldAndTheIconKeepsTheJobColour() {
        source.profession = ProfessionId.MINER;

        source.level = ProfessionLevel.MASTER;
        service.revalidate(PLAYER);
        TagStyle.Tagged master = service.tagsOf(PLAYER).get(0);
        assertEquals("\u00ab" + ProfessionId.MINER.settings().icon + "\u00bb", plain(master.icon()));

        List<Component> parts = master.icon().getSiblings();
        assertEquals(3, parts.size(), "bracket, icon, bracket");
        assertEquals(ProfessionSettings.DEFAULT_MASTERED_COLOR, colourOf(parts.get(0)));
        assertEquals(ProfessionId.MINER.settings().color, colourOf(parts.get(1)), "the icon is not recoloured");
        assertEquals(ProfessionSettings.DEFAULT_MASTERED_COLOR, colourOf(parts.get(2)));
        assertFalse(parts.get(0).getStyle().isBold(), "no bold: it competes with the icon at nametag size");
    }

    @Test
    void aNonMasterTagGetsGreySquareBrackets() {
        source.profession = ProfessionId.MINER;

        source.level = ProfessionLevel.APPRENTICE;
        service.revalidate(PLAYER);
        TagStyle.Tagged apprentice = service.tagsOf(PLAYER).get(0);
        assertEquals("[" + ProfessionId.MINER.settings().icon + "]", plain(apprentice.icon()));

        List<Component> parts = apprentice.icon().getSiblings();
        assertEquals(3, parts.size());
        assertEquals(0x808080, colourOf(parts.get(0)));
        assertEquals(ProfessionId.MINER.settings().color, colourOf(parts.get(1)));
        assertEquals(0x808080, colourOf(parts.get(2)));
    }

    @Test
    void aRustedTagIsNotMasteredAndKeepsItsWord() {
        source.profession = ProfessionId.MINER;
        source.level = ProfessionLevel.RUSTED;
        service.revalidate(PLAYER);

        TagStyle.Tagged rusted = service.tagsOf(PLAYER).get(0);
        assertEquals("[" + ProfessionId.MINER.settings().icon + "]", plain(rusted.icon()),
                "the nametag icon is unchanged: Rusted is a word, not a frame");
        assertEquals(0x808080, colourOf(rusted.icon().getSiblings().get(0)), "and it is not a Master's brackets");
        assertEquals("[" + ProfessionId.MINER.displayName() + ": " + ProfessionLevel.RUSTED.displayName() + "] Steve",
                plain(TagStyle.tabRow(service.tagsOf(PLAYER), Component.literal("Steve"))));
    }

    @Test
    void aPartyTagIsOneRunInItsOwnColourAndIsNeverMastered() {
        source.faction = FactionId.MONARCHY;
        source.profession = null;

        TagStyle.Tagged party = service.tagsOf(PLAYER).get(0);
        assertEquals("[" + FactionId.MONARCHY.settings().icon + "]", plain(party.icon()));
        assertEquals(FactionId.MONARCHY.settings().color, party.icon().getStyle().getColor().getValue(),
                "brackets included: the party tag does not take the job rule");
        assertTrue(party.icon().getSiblings().isEmpty(), "one run, so there are no separately coloured brackets");
    }

    private static int colourOf(Component component) {
        return component.getStyle().getColor().getValue();
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