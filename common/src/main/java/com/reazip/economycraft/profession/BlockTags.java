package com.reazip.economycraft.profession;

import com.mojang.logging.LogUtils;
import com.reazip.economycraft.config.ProfessionsSection;
import com.reazip.economycraft.util.IdentifierCompat;
import com.reazip.economycraft.util.IdentifierCompat.Id;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * The vendor lists: which blocks a Builder counts, which blocks a Miner counts, and which blocks trigger each
 * job's Haste (spec §4, §6).
 *
 * <p>Every one of those sets is a config list of block ids and {@code #tags}, and they are read here, once, in
 * one place. A job asking "is this a building block?" must not grow its own literal list — that is exactly how
 * the Builder's building set and the Miner's Haste set ended up disagreeing in the first draft of the spec.
 *
 * <h2>Why tags are resolved lazily</h2>
 *
 * <p>A {@link TagKey} can be built the moment the config is parsed, but the contents of that tag do not exist
 * until the datapacks finish loading, which is well after {@code EconomyConfig.load()}. So this class stores
 * keys, not tag contents, and asks the block state at match time via {@link BlockState#is(TagKey)}. That also
 * means {@code /reload} is picked up for free — a cached tag snapshot would silently go stale after a
 * datapack change.
 *
 * <p>The only thing resolved eagerly is the block-id half, since a literal id that does not exist in the
 * registry is a typo in the config and there is no excuse for it.
 */
public final class BlockTags {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final BlockSet buildingBlocks;
    private final BlockSet ores;
    private final BlockSet doubleValueOres;
    private final BlockSet builderHasteTriggers;
    private final BlockSet minerHasteTriggers;

    private BlockTags(BlockSet buildingBlocks, BlockSet ores, BlockSet doubleValueOres,
                      BlockSet builderHasteTriggers, BlockSet minerHasteTriggers) {
        this.buildingBlocks = buildingBlocks;
        this.ores = ores;
        this.doubleValueOres = doubleValueOres;
        this.builderHasteTriggers = builderHasteTriggers;
        this.minerHasteTriggers = minerHasteTriggers;
    }

    /** Builds all five sets from the parsed config. Safe to call before the server has finished loading. */
    public static BlockTags fromConfig(ProfessionsSection config) {
        BlockSet buildingBlocks =
                BlockSet.parse("professions.builder.building_blocks", config.builder.buildingBlocks);
        return new BlockTags(
                buildingBlocks,
                BlockSet.parse("professions.miner.ore_tags", config.miner.oreTags),
                BlockSet.parse("professions.miner.double_value_ores", config.miner.doubleValueOres),
                // The spec triggers Builder Haste on "stone, cobblestone, dirt and every building block", and
                // the second half of that is already a list in the config. Unioning here rather than in the file
                // keeps one canonical list: an admin who extends building_blocks extends the Haste trigger set
                // too, and cannot add a building block that quietly does not give Haste.
                BlockSet.parse("professions.builder.haste_trigger_blocks", config.builder.hasteTriggerBlocks)
                        .union(buildingBlocks),
                BlockSet.parse("professions.miner.haste_trigger_blocks", config.miner.hasteTriggerBlocks));
    }

    /** Whether a placed block counts towards Builder progress. */
    public boolean isBuildingBlock(BlockState state) {
        return buildingBlocks.matches(state);
    }

    /** Whether a broken block counts towards Miner progress at all. */
    public boolean isOre(BlockState state) {
        return ores.matches(state);
    }

    /**
     * How much Miner progress a broken block is worth: 2 for the spec's double-value ores (diamond, gold),
     * 1 for any other ore, 0 for everything else.
     */
    public int minerProgressFor(BlockState state) {
        if (doubleValueOres.matches(state)) return 2;
        return ores.matches(state) ? 1 : 0;
    }

    public boolean triggersBuilderHaste(BlockState state) {
        return builderHasteTriggers.matches(state);
    }

    /** How many literal blocks and tags the Builder's Haste trigger set resolved to, for diagnostics. */
    public int builderHasteTriggerCount() {
        return builderHasteTriggers.blocks().size() + builderHasteTriggers.tags().size();
    }

    public boolean triggersMinerHaste(BlockState state) {
        return minerHasteTriggers.matches(state);
    }

    /**
     * One configured list, split into the literal blocks and the tags it names.
     *
     * <p>Unusable entries are dropped rather than fatal: a bad id in {@code building_blocks} should cost one
     * block type, not the server. The cost of dropping something is that the admin has to notice, which is why
     * each drop is logged by name.
     */
    public static final class BlockSet {

        private final String source;
        private final List<Block> blocks;
        private final List<TagKey<Block>> tags;
        /** Tags whose binding was already checked, so a typo is reported once instead of every block broken. */
        private final List<Boolean> tagChecked;

        private BlockSet(String source, List<Block> blocks, List<TagKey<Block>> tags) {
            this.source = source;
            this.blocks = blocks;
            this.tags = tags;
            this.tagChecked = new ArrayList<>(java.util.Collections.nCopies(tags.size(), Boolean.FALSE));
        }

        static BlockSet parse(String source, List<String> entries) {
            List<Block> blocks = new ArrayList<>();
            List<TagKey<Block>> tags = new ArrayList<>();

            if (entries != null) {
                for (String entry : entries) {
                    if (entry == null || entry.isBlank()) continue;

                    if (entry.charAt(0) == '#') {
                        TagKey<Block> tag = tagFor(entry.substring(1).trim());
                        if (tag != null) tags.add(tag);
                    } else {
                        Block block = blockFor(entry);
                        if (block != null) blocks.add(block);
                    }
                }
            }

            LOGGER.info("[EconomyCraft] {} resolved to {} block(s) and {} tag(s).", source, blocks.size(), tags.size());
            return new BlockSet(source, List.copyOf(blocks), List.copyOf(tags));
        }

        private static Block blockFor(String id) {
            IdentifierCompat.Id parsed = IdentifierCompat.tryParse(id);
            if (parsed == null || !IdentifierCompat.registryContainsKey(BuiltInRegistries.BLOCK, parsed)) {
                LOGGER.warn("[EconomyCraft] No such block '{}'; dropping it from this list.", id);
                return null;
            }
            return IdentifierCompat.<Block>registryGetOptional(BuiltInRegistries.BLOCK, parsed).orElse(null);
        }

        private static TagKey<Block> tagFor(String id) {
            IdentifierCompat.Id parsed = IdentifierCompat.tryParse(id);
            if (parsed == null) {
                LOGGER.warn("[EconomyCraft] '{}' is not a valid block tag id; dropping it from this list.", id);
                return null;
            }
            return TagKey.create(Registries.BLOCK, (net.minecraft.resources.Identifier) parsed.handle());
        }

        /**
         * Matches without copying the tag's contents.
         *
         * <p>The tag contents themselves are read live from the block state, so a {@code /reload} is honoured.
         * The {@code tagChecked} bookkeeping exists only to turn "this tag matches nothing, ever" into one
         * warning — a tag can legitimately be empty (a datapack may add to it later), so this is a diagnostic,
         * not a filter.
         */
        public boolean matches(BlockState state) {
            if (state == null) return false;

            for (Block block : blocks) {
                if (state.is(block)) return true;
            }
            for (int i = 0; i < tags.size(); i++) {
                if (state.is(tags.get(i))) return true;
                warnIfTagNeverMatches(i);
            }
            return false;
        }

        private void warnIfTagNeverMatches(int index) {
            if (tagChecked.get(index)) return;
            tagChecked.set(index, Boolean.TRUE);

            Iterable<net.minecraft.core.Holder<Block>> bound = BuiltInRegistries.BLOCK.getTagOrEmpty(tags.get(index));
            if (!bound.iterator().hasNext()) {
                LOGGER.warn("[EconomyCraft] Tag #{} in {} is unknown or empty; nothing will match it. Check the id for a typo.",
                        tags.get(index).location(), source);
            }
        }

        /** The literal blocks, for diagnostics and tests. */
        public List<Block> blocks() {
            return blocks;
        }

        /** The tag keys, for diagnostics and tests. */
        public List<TagKey<Block>> tags() {
            return tags;
        }

        /** The config path these came from, for diagnostics. */
        public String source() {
            return source;
        }

        /**
         * A set matching either input.
         *
         * <p>No flag is needed: a block or tag in both sets is simply matched twice, and the tag warning is
         * still per-entry. Used for the Builder's Haste triggers, which are the configured list plus the
         * building blocks.
         */
        public BlockSet union(BlockSet other) {
            if (other == null) return this;
            List<Block> mergedBlocks = new ArrayList<>(blocks);
            mergedBlocks.addAll(other.blocks);
            List<TagKey<Block>> mergedTags = new ArrayList<>(tags);
            mergedTags.addAll(other.tags);
            return new BlockSet(source + " ∪ " + other.source, List.copyOf(mergedBlocks), List.copyOf(mergedTags));
        }
    }
}