package com.reazip.economycraft.gossip;

import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("ProfessionMapper Unit Tests")
class ProfessionMapperTest {

    @ParameterizedTest
    @CsvSource({
            "farmer, FARMER",
            "fisherman, FARMER",
            "shepherd, FARMER",
            "fletcher, FARMER",
            "minecraft:farmer, FARMER",
            "minecraft:fisherman, FARMER",
            "minecraft:shepherd, FARMER",
            "minecraft:fletcher, FARMER",

            "armorer, BLACKSMITH",
            "weaponsmith, BLACKSMITH",
            "toolsmith, BLACKSMITH",
            "minecraft:armorer, BLACKSMITH",
            "minecraft:weaponsmith, BLACKSMITH",
            "minecraft:toolsmith, BLACKSMITH",

            "cleric, CLERIC",
            "minecraft:cleric, CLERIC",

            "librarian, LIBRARIAN",
            "cartographer, LIBRARIAN",
            "minecraft:librarian, LIBRARIAN",
            "minecraft:cartographer, LIBRARIAN",

            "nitwit, NITWIT",
            "none, NITWIT",
            "unemployed, NITWIT",
            "minecraft:nitwit, NITWIT",
            "minecraft:none, NITWIT",

            "butcher, GENERAL",
            "leatherworker, GENERAL",
            "mason, GENERAL",
            "minecraft:butcher, GENERAL",
            "minecraft:leatherworker, GENERAL",
            "minecraft:mason, GENERAL"
    })
    @DisplayName("T014: All vanilla professions map to the expected GossipCategory")
    void testVanillaProfessionMapping(String input, GossipCategory expected) {
        assertEquals(expected, ProfessionMapper.fromProfession(input));
    }

    @Test
    @DisplayName("T014: ResourceKey mapping using VillagerProfession constants")
    void testResourceKeyMapping() {
        assertEquals(GossipCategory.FARMER, ProfessionMapper.fromProfession(VillagerProfession.FARMER));
        assertEquals(GossipCategory.FARMER, ProfessionMapper.fromProfession(VillagerProfession.FISHERMAN));
        assertEquals(GossipCategory.FARMER, ProfessionMapper.fromProfession(VillagerProfession.SHEPHERD));
        assertEquals(GossipCategory.FARMER, ProfessionMapper.fromProfession(VillagerProfession.FLETCHER));

        assertEquals(GossipCategory.BLACKSMITH, ProfessionMapper.fromProfession(VillagerProfession.ARMORER));
        assertEquals(GossipCategory.BLACKSMITH, ProfessionMapper.fromProfession(VillagerProfession.WEAPONSMITH));
        assertEquals(GossipCategory.BLACKSMITH, ProfessionMapper.fromProfession(VillagerProfession.TOOLSMITH));

        assertEquals(GossipCategory.CLERIC, ProfessionMapper.fromProfession(VillagerProfession.CLERIC));

        assertEquals(GossipCategory.LIBRARIAN, ProfessionMapper.fromProfession(VillagerProfession.LIBRARIAN));
        assertEquals(GossipCategory.LIBRARIAN, ProfessionMapper.fromProfession(VillagerProfession.CARTOGRAPHER));

        assertEquals(GossipCategory.NITWIT, ProfessionMapper.fromProfession(VillagerProfession.NITWIT));
        assertEquals(GossipCategory.NITWIT, ProfessionMapper.fromProfession(VillagerProfession.NONE));

        assertEquals(GossipCategory.GENERAL, ProfessionMapper.fromProfession(VillagerProfession.BUTCHER));
        assertEquals(GossipCategory.GENERAL, ProfessionMapper.fromProfession(VillagerProfession.LEATHERWORKER));
        assertEquals(GossipCategory.GENERAL, ProfessionMapper.fromProfession(VillagerProfession.MASON));
    }

    @Test
    @DisplayName("T014: Case-insensitivity and formatting tolerance")
    void testCaseInsensitivity() {
        assertEquals(GossipCategory.FARMER, ProfessionMapper.fromProfession("FaRmEr"));
        assertEquals(GossipCategory.BLACKSMITH, ProfessionMapper.fromProfession("WEAPONSMITH"));
        assertEquals(GossipCategory.CLERIC, ProfessionMapper.fromProfession("ClErIc"));
        assertEquals(GossipCategory.LIBRARIAN, ProfessionMapper.fromProfession("Librarian"));
        assertEquals(GossipCategory.NITWIT, ProfessionMapper.fromProfession("NITWIT"));
    }

    @Test
    @DisplayName("T014: Unknown and modded professions fall back to GENERAL")
    void testUnknownProfessionFallback() {
        assertEquals(GossipCategory.GENERAL, ProfessionMapper.fromProfession("alchemist"));
        assertEquals(GossipCategory.GENERAL, ProfessionMapper.fromProfession("modded:beekeeper"));
        assertEquals(GossipCategory.GENERAL, ProfessionMapper.fromProfession("unknown"));
    }

    @Test
    @DisplayName("T014: Null and empty handling returns GENERAL")
    void testNullAndEmptyHandling() {
        assertEquals(GossipCategory.GENERAL, ProfessionMapper.fromProfession((String) null));
        assertEquals(GossipCategory.GENERAL, ProfessionMapper.fromProfession(""));
        assertEquals(GossipCategory.GENERAL, ProfessionMapper.fromProfession("   "));
        assertEquals(GossipCategory.GENERAL, ProfessionMapper.fromProfession((Identifier) null));
    }
}
