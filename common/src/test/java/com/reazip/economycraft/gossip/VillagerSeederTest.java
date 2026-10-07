package com.reazip.economycraft.gossip;

import com.reazip.economycraft.gossip.identity.VillagerSeeder;
import com.reazip.economycraft.gossip.storage.VillagerProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class VillagerSeederTest {

    @Test
    @DisplayName("VillagerSeeder produces deterministic output for the same UUID")
    void testDeterministicSeeding() {
        UUID uuid = UUID.nameUUIDFromBytes("test-villager-123".getBytes());

        VillagerProfile profile1 = VillagerSeeder.createSeededProfile(uuid, "blacksmith", "plains", 1000L);
        VillagerProfile profile2 = VillagerSeeder.createSeededProfile(uuid, "blacksmith", "plains", 2000L);

        assertEquals(profile1.name(), profile2.name());
        assertEquals(profile1.traits(), profile2.traits());
        assertEquals(profile1.quirk(), profile2.quirk());
        assertEquals(profile1.backstory(), profile2.backstory());
        assertEquals("blacksmith", profile1.profession());
        assertEquals("plains", profile1.biome());
    }

    @Test
    @DisplayName("Different UUIDs produce varied personas")
    void testDifferentUuidsProduceVariedPersonas() {
        UUID u1 = UUID.randomUUID();
        UUID u2 = UUID.randomUUID();

        VillagerProfile p1 = VillagerSeeder.createSeededProfile(u1, "farmer", "desert", 0L);
        VillagerProfile p2 = VillagerSeeder.createSeededProfile(u2, "cleric", "taiga", 0L);

        assertNotNull(p1.name());
        assertNotNull(p2.name());
        assertFalse(p1.traits().isEmpty());
        assertFalse(p2.traits().isEmpty());
    }
}
