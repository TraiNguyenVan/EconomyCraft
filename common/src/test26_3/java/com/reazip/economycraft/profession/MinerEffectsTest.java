package com.reazip.economycraft.profession;

import com.reazip.economycraft.config.ProfessionsSection;
import com.reazip.economycraft.EconomyConfig;
import com.reazip.economycraft.EconomyCraft;
import com.reazip.economycraft.EconomyManager;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.UUID;

import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for the Miner profession's arithmetic, mechanics, and rust scaling (Phase 6, spec lines 51-55).
 */
class MinerEffectsTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(VanillaRegistries.createWorldLookup())
                .forEach(components -> components.apply());
    }

    private static ProfessionsSection.MinerSettings defaults() {
        return new ProfessionsSection.MinerSettings();
    }

    // --- Khéo tay (Double Ore Drop) ---

    @Test
    void doubleDropChanceMatchesConfigDefaults() {
        ProfessionsSection.MinerSettings settings = defaults();
        assertEquals(0.05D, MinerEffects.effectiveDoubleDropChance(settings, ProfessionLevel.APPRENTICE, 1.0D), 1e-9D);
        assertEquals(0.15D, MinerEffects.effectiveDoubleDropChance(settings, ProfessionLevel.MASTER, 1.0D), 1e-9D);
    }

    @Test
    void doubleDropChanceCustomSettingsOverrideDefaults() {
        ProfessionsSection.MinerSettings settings = defaults();
        settings.doubleDropChanceApprentice = 0.12D;
        settings.doubleDropChanceMaster = 0.35D;

        assertEquals(0.12D, MinerEffects.effectiveDoubleDropChance(settings, ProfessionLevel.APPRENTICE, 1.0D), 1e-9D);
        assertEquals(0.35D, MinerEffects.effectiveDoubleDropChance(settings, ProfessionLevel.MASTER, 1.0D), 1e-9D);
    }

    @Test
    void doubleDropChanceScalesWithRust() {
        ProfessionsSection.MinerSettings settings = defaults();
        assertEquals(0.025D, MinerEffects.effectiveDoubleDropChance(settings, ProfessionLevel.APPRENTICE, 0.5D), 1e-9D);
        assertEquals(0.075D, MinerEffects.effectiveDoubleDropChance(settings, ProfessionLevel.MASTER, 0.5D), 1e-9D);
    }

    @Test
    void doubleDropChanceReturnsZeroForNullOrRustedLevel() {
        ProfessionsSection.MinerSettings settings = defaults();
        assertEquals(0.0D, MinerEffects.effectiveDoubleDropChance(settings, null, 1.0D), 1e-9D);
        assertEquals(0.0D, MinerEffects.effectiveDoubleDropChance(null, ProfessionLevel.MASTER, 1.0D), 1e-9D);
        assertEquals(0.0D, MinerEffects.effectiveDoubleDropChance(settings, ProfessionLevel.RUSTED, 1.0D), 1e-9D);
    }

    @Test
    void doublesOriginalStacksAndPreservesComponents() {
        ItemStack silkDrop = new ItemStack(Items.IRON_ORE);
        ItemStack fortuneDrop = new ItemStack(Items.RAW_IRON, 4);
        fortuneDrop.set(DataComponents.CUSTOM_NAME, Component.literal("Named ore"));
        List<ItemStack> original = List.of(silkDrop, fortuneDrop);

        List<ItemStack> doubled = MinerEffects.copyDrops(original);
        assertEquals(4, doubled.size());
        assertSame(silkDrop, doubled.get(0));
        assertNotSame(silkDrop, doubled.get(2));
        assertEquals(Items.IRON_ORE, doubled.get(2).getItem());
        assertEquals(4, doubled.get(3).getCount());
        assertEquals(fortuneDrop.get(DataComponents.CUSTOM_NAME), doubled.get(3).get(DataComponents.CUSTOM_NAME));
        doubled.get(3).setCount(1);
        assertEquals(4, fortuneDrop.getCount());
        assertTrue(MinerEffects.copyDrops(List.of()).isEmpty());
    }

    @Test
    void bonusRequiresMinerOreAndSuccessfulChance() {
        MinecraftServer server = mock(MinecraftServer.class);
        ServerLevel level = mock(ServerLevel.class);
        ServerPlayer player = mock(ServerPlayer.class);
        RandomSource random = mock(RandomSource.class);
        EconomyManager economy = mock(EconomyManager.class);
        ProfessionStore professions = mock(ProfessionStore.class);
        UUID id = UUID.randomUUID();
        EconomyConfig config = new EconomyConfig();
        config.professions.miner.doubleDropChanceMaster = 1.0D;
        when(player.level()).thenReturn(level);
        when(level.getServer()).thenReturn(server);
        when(player.getUUID()).thenReturn(id);
        when(player.getRandom()).thenReturn(random);
        when(economy.getProfessions()).thenReturn(professions);
        when(economy.getBlockTags()).thenReturn(BlockTags.fromConfig(config.professions));
        when(professions.professionOf(id)).thenReturn(ProfessionId.MINER);
        when(professions.baseLevelOf(id)).thenReturn(ProfessionLevel.MASTER);
        when(professions.levelOf(id)).thenReturn(ProfessionLevel.MASTER);
        List<ItemStack> drops = List.of(new ItemStack(Items.RAW_IRON, 4));
        try (MockedStatic<EconomyCraft> craft = mockStatic(EconomyCraft.class);
             MockedStatic<EconomyConfig> configs = mockStatic(EconomyConfig.class)) {
            craft.when(() -> EconomyCraft.getManager(server)).thenReturn(economy);
            configs.when(EconomyConfig::get).thenReturn(config);
            assertEquals(8, MinerEffects.doubleOreDrops(player, Blocks.IRON_ORE.defaultBlockState(), drops)
                    .stream().mapToInt(ItemStack::getCount).sum());
            assertSame(drops, MinerEffects.doubleOreDrops(player, Blocks.STONE.defaultBlockState(), drops));
            assertTrue(MinerEffects.doubleOreDrops(player, Blocks.IRON_ORE.defaultBlockState(), List.of()).isEmpty());
            config.professions.miner.doubleDropChanceMaster = 0.05D;
            when(random.nextDouble()).thenReturn(0.9D);
            assertSame(drops, MinerEffects.doubleOreDrops(player, Blocks.IRON_ORE.defaultBlockState(), drops));
            config.professions.miner.doubleDropChanceMaster = 1.0D;
            when(professions.professionOf(id)).thenReturn(ProfessionId.FARMER);
            assertSame(drops, MinerEffects.doubleOreDrops(player, Blocks.IRON_ORE.defaultBlockState(), drops));
            assertSame(drops, MinerEffects.doubleOreDrops(null, Blocks.IRON_ORE.defaultBlockState(), drops));
        }
    }

    // --- Bảo hộ lao động (Lava Regeneration) ---

    @Test
    void lavaRegenTicksMatchesConfigDefaults() {
        ProfessionsSection.MinerSettings settings = defaults();
        // 4 seconds at 20 ticks/sec = 80 ticks
        assertEquals(80, MinerEffects.effectiveLavaRegenTicks(settings, 1.0D));
    }

    @Test
    void lavaRegenTicksCustomSettingsOverrideDefaults() {
        ProfessionsSection.MinerSettings settings = defaults();
        settings.lavaRegenerationSeconds = 8;
        assertEquals(160, MinerEffects.effectiveLavaRegenTicks(settings, 1.0D));
    }

    @Test
    void lavaRegenTicksScalesWithRust() {
        ProfessionsSection.MinerSettings settings = defaults();
        // 80 ticks * 0.5 = 40 ticks
        assertEquals(40, MinerEffects.effectiveLavaRegenTicks(settings, 0.5D));
    }

    @Test
    void lavaRegenTicksReturnsZeroForNull() {
        assertEquals(0, MinerEffects.effectiveLavaRegenTicks(null, 1.0D));
    }

    // --- Ore and Double-Value Ore Progression ---

    @Test
    void minerProgressWorthMatchesSpecValues() {
        ProfessionsSection section = new ProfessionsSection();
        BlockTags tags = BlockTags.fromConfig(section);

        // Diamonds and gold are worth 2 progress
        assertEquals(2, tags.minerProgressFor(Blocks.DIAMOND_ORE.defaultBlockState()));
        assertEquals(2, tags.minerProgressFor(Blocks.DEEPSLATE_DIAMOND_ORE.defaultBlockState()));
        assertEquals(2, tags.minerProgressFor(Blocks.GOLD_ORE.defaultBlockState()));
        assertEquals(2, tags.minerProgressFor(Blocks.NETHER_GOLD_ORE.defaultBlockState()));

        // Non-ores give 0
        assertEquals(0, tags.minerProgressFor(Blocks.STONE.defaultBlockState()));
        assertEquals(0, tags.minerProgressFor(Blocks.DIRT.defaultBlockState()));
        assertEquals(0, tags.minerProgressFor(Blocks.OAK_LOG.defaultBlockState()));
    }

    // --- Haste II Triggers ---

    @Test
    void minerHasteTriggersMatchSpecRequirements() {
        ProfessionsSection section = new ProfessionsSection();
        BlockTags tags = BlockTags.fromConfig(section);

        // Spec line 53: stone, deepslate, tuff, netherrack
        assertTrue(tags.triggersMinerHaste(Blocks.STONE.defaultBlockState()));
        assertTrue(tags.triggersMinerHaste(Blocks.DEEPSLATE.defaultBlockState()));
        assertTrue(tags.triggersMinerHaste(Blocks.TUFF.defaultBlockState()));
        assertTrue(tags.triggersMinerHaste(Blocks.NETHERRACK.defaultBlockState()));

        // Non-trigger blocks
        assertFalse(tags.triggersMinerHaste(Blocks.DIRT.defaultBlockState()));
        assertFalse(tags.triggersMinerHaste(Blocks.GLASS.defaultBlockState()));
        assertFalse(tags.triggersMinerHaste(Blocks.OAK_PLANKS.defaultBlockState()));
    }
}
