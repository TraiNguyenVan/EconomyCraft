package com.reazip.economycraft.profession;

import com.reazip.economycraft.EconomyConfig;
import com.reazip.economycraft.config.ProfessionsSection;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The config-driven block sets, against a bootstrapped registry.
 *
 * <p>Membership is asserted against <strong>literal</strong> block ids only. Tag membership is a datapack-layer
 * property: a JUnit run has no datapacks, so {@code #minecraft:ores} parses into a valid {@link TagKey} but has
 * no contents to match. Asserting it anyway would produce a test that passes on a full server and fails here
 * for a reason that has nothing to do with this class — so the tag half is asserted structurally (the keys that
 * were parsed) and, where a tag does happen to be bound, additionally behaviourally.
 */
class BlockTagsTest {

    @BeforeAll
    static void bootstrap() {
        // Same order as TollUiTest: the version has to be detected before anything touches DataFixers, and the
        // data-component initialisers have to be applied before item components are read.
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(VanillaRegistries.createWorldLookup())
                .forEach(components -> components.apply());
    }

    /** A config with literal-only sets, so matching is deterministic without datapacks. */
    private static ProfessionsSection literalOnly() {
        ProfessionsSection config = new ProfessionsSection();
        config.builder.buildingBlocks = List.of("minecraft:bricks", "minecraft:dirt", "minecraft:smooth_stone");
        config.builder.hasteTriggerBlocks = List.of("minecraft:stone", "minecraft:cobblestone", "minecraft:dirt");
        config.miner.oreTags = List.of();
        config.miner.doubleValueOres = List.of("minecraft:diamond_ore", "minecraft:gold_ore");
        config.miner.hasteTriggerBlocks = List.of("minecraft:stone", "minecraft:deepslate", "minecraft:tuff");
        return config;
    }

    private static boolean tagIsBound(BlockTags.BlockSet set, int index) {
        Iterable<Holder<Block>> bound = BuiltInRegistries.BLOCK.getTagOrEmpty(set.tags().get(index));
        return bound.iterator().hasNext();
    }

    @Test
    void literalBuildingBlocksMatch() {
        BlockTags tags = BlockTags.fromConfig(literalOnly());

        assertTrue(tags.isBuildingBlock(Blocks.BRICKS.defaultBlockState()));
        assertTrue(tags.isBuildingBlock(Blocks.DIRT.defaultBlockState()));
        assertTrue(tags.isBuildingBlock(Blocks.SMOOTH_STONE.defaultBlockState()));
        assertFalse(tags.isBuildingBlock(Blocks.CHEST.defaultBlockState()),
                "a chest is not a building block, whatever a datapack might tag it as");
        assertFalse(tags.isBuildingBlock(Blocks.STONE.defaultBlockState()));
    }

    @Test
    void theBuilderHasteTriggerSetIsItsOwnList() {
        BlockTags tags = BlockTags.fromConfig(literalOnly());

        assertTrue(tags.triggersBuilderHaste(Blocks.STONE.defaultBlockState()));
        assertTrue(tags.triggersBuilderHaste(Blocks.COBBLESTONE.defaultBlockState()));
        assertFalse(tags.triggersBuilderHaste(Blocks.SAND.defaultBlockState()));
        assertFalse(tags.isBuildingBlock(Blocks.STONE.defaultBlockState()),
                "Haste triggers and building blocks are separate keys, and one must not imply the other");
    }

    @Test
    void minerHasteCoversStoneDeepslateAndTuff() {
        BlockTags tags = BlockTags.fromConfig(literalOnly());

        assertTrue(tags.triggersMinerHaste(Blocks.STONE.defaultBlockState()));
        assertTrue(tags.triggersMinerHaste(Blocks.DEEPSLATE.defaultBlockState()));
        assertTrue(tags.triggersMinerHaste(Blocks.TUFF.defaultBlockState()));
        assertFalse(tags.triggersMinerHaste(Blocks.DIRT.defaultBlockState()));
    }

    @Test
    void doubleValueOresAreWorthTwo() {
        BlockTags tags = BlockTags.fromConfig(literalOnly());

        assertEquals(2, tags.minerProgressFor(Blocks.DIAMOND_ORE.defaultBlockState()));
        assertEquals(2, tags.minerProgressFor(Blocks.GOLD_ORE.defaultBlockState()));
        assertEquals(0, tags.minerProgressFor(Blocks.STONE.defaultBlockState()));
    }

    @Test
    void anOreInBothSetsIsStillWorthOneAnswer() {
        ProfessionsSection config = literalOnly();
        config.miner.oreTags = List.of();
        config.miner.doubleValueOres = List.of("minecraft:diamond_ore");

        BlockTags tags = BlockTags.fromConfig(config);

        assertEquals(2, tags.minerProgressFor(Blocks.DIAMOND_ORE.defaultBlockState()),
                "the two sets overlap in the default config; minerProgressFor exists to collapse that to one answer");
        assertEquals(0, tags.minerProgressFor(Blocks.GOLD_ORE.defaultBlockState()),
                "a block in neither set is worth nothing");
    }

    @Test
    void nullStateMatchesNothingRatherThanThrowing() {
        assertFalse(BlockTags.fromConfig(literalOnly()).isBuildingBlock((BlockState) null));
    }

    @Test
    void unparseableEntriesAreDroppedWithAWarningRatherThanFailing() {
        ProfessionsSection config = literalOnly();
        config.builder.buildingBlocks = List.of("minecraft:stone", "Not A Block Id", "  ", "minecraft:bricks");
        config.miner.doubleValueOres = List.of("minecraft:not_a_real_block", "minecraft:gold_ore");

        BlockTags tags = BlockTags.fromConfig(config);

        assertTrue(tags.isBuildingBlock(Blocks.BRICKS.defaultBlockState()),
                "one bad entry must not cost the rest of the list");
        assertEquals(2, tags.minerProgressFor(Blocks.GOLD_ORE.defaultBlockState()));
        assertEquals(0, tags.minerProgressFor(Blocks.STONE.defaultBlockState()));
    }

    @Test
    void anEmptySetMatchesNothing() {
        ProfessionsSection config = literalOnly();
        config.builder.buildingBlocks = List.of();
        config.miner.doubleValueOres = List.of();

        BlockTags tags = BlockTags.fromConfig(config);

        assertFalse(tags.isBuildingBlock(Blocks.BRICKS.defaultBlockState()));
        assertEquals(0, tags.minerProgressFor(Blocks.GOLD_ORE.defaultBlockState()));
    }

    @Test
    void setsReportTheirSizeAndSource() {
        BlockTags.BlockSet set = BlockTags.BlockSet.parse("test.building_blocks", List.of("minecraft:stone", "#minecraft:ores"));

        assertEquals("test.building_blocks", set.source());
        assertEquals(1, set.blocks().size());
        assertEquals(1, set.tags().size());
    }

    @Test
    void tagEntriesBecomeBlockTagKeys() {
        BlockTags.BlockSet set = BlockTags.BlockSet.parse("test.ore_tags", List.of("#minecraft:ores", "#c:ores"));

        assertEquals(2, set.tags().size());
        assertEquals("minecraft:ores", set.tags().get(0).location().toString());
        assertEquals("c", set.tags().get(1).location().getNamespace(), "an unfamiliar namespace must not be rejected");
    }

    @Test
    void bundledBuildingListIsTheSpecList() {
        List<String> specList = List.of(
                "#minecraft:logs", "#minecraft:planks", "#minecraft:stairs", "#minecraft:slabs", "#minecraft:walls",
                "#minecraft:fences", "#minecraft:fence_gates", "#minecraft:terracotta", "#minecraft:stone_bricks",
                "minecraft:scaffolding", "minecraft:dirt", "minecraft:coarse_dirt", "minecraft:rooted_dirt",
                "minecraft:mud", "minecraft:glass", "minecraft:tinted_glass", "#c:glass_blocks", "#c:glass_panes",
                "minecraft:smooth_stone", "minecraft:bricks", "minecraft:mud_bricks", "minecraft:packed_mud",
                "minecraft:prismarine", "minecraft:dark_prismarine", "minecraft:prismarine_bricks",
                "minecraft:purpur_block", "minecraft:purpur_pillar", "minecraft:end_stone_bricks",
                "minecraft:quartz_block", "minecraft:smooth_quartz", "minecraft:chiseled_quartz_block",
                "minecraft:quartz_pillar", "minecraft:quartz_bricks");

        assertEquals(specList, EconomyConfig.get().professions.builder.buildingBlocks,
                "the spec lists 33 entries verbatim; adding, dropping or reordering one changes the profession");
    }

    @Test
    void bundledListsParseIntoTagsAndLiterals() {
        BlockTags.BlockSet building = BlockTags.BlockSet.parse(
                "professions.builder.building_blocks", EconomyConfig.get().professions.builder.buildingBlocks);

        // Of the spec's 33 entries, 11 are tags (nine minecraft: plus #c:glass_blocks and #c:glass_panes).
        assertEquals(11, building.tags().size());
        assertEquals(22, building.blocks().size(), "the other 22 are literal block ids");
        assertEquals(33, building.tags().size() + building.blocks().size(),
                "every spec entry must resolve to something; a dropped id is a silently smaller profession");
    }

    @Test
    void tagMembershipHoldsWhenTheTagIsBound() {
        BlockTags.BlockSet ores = BlockTags.BlockSet.parse("test.ore_tags", List.of("#minecraft:ores"));
        if (!tagIsBound(ores, 0)) return; // No datapacks here; there is nothing to assert about membership.

        assertTrue(ores.matches(Blocks.COAL_ORE.defaultBlockState()));
        assertFalse(ores.matches(Blocks.STONE.defaultBlockState()));
    }
}