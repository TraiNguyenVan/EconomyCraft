package com.reazip.economycraft.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ClaimBridgeTest {

    @AfterEach
    void tearDown() {
        ClaimBridge.resetBackendForTest();
    }

    @Test
    void whenBackendIsAbsentAllOperationsDegradeSafely() {
        ClaimBridge.setBackendForTest(null);

        // When ShopGuard is absent, the land-claim bridge finds no backend
        assertFalse(ClaimBridge.isAvailable());
        assertNull(ClaimBridge.claimAt("minecraft:overworld", 100, 200));

        UUID player = UUID.randomUUID();
        // Claim-dependent features are inert:
        assertFalse(ClaimBridge.isOwnClaim(player, "minecraft:overworld", 100, 200));
        assertFalse(ClaimBridge.isUnclaimed("minecraft:overworld", 100, 200));
        // Without protection, building is permitted everywhere:
        assertTrue(ClaimBridge.mayBuild(player, "minecraft:overworld", 100, 200));
    }

    @Test
    void whenBackendIsPresentOperationsDelegateCorrectly() {
        UUID owner = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        String dim = "minecraft:overworld";

        ClaimBridge.Backend backend = new ClaimBridge.Backend() {
            @Override
            public ClaimBridge.ClaimInfo claimAt(String d, int x, int z) {
                if (dim.equals(d) && x >= 0 && x <= 100 && z >= 0 && z <= 100) {
                    return new ClaimBridge.ClaimInfo(1L, owner, dim);
                }
                return null;
            }

            @Override
            public boolean isOwnClaim(UUID player, String d, int x, int z) {
                ClaimBridge.ClaimInfo c = claimAt(d, x, z);
                return c != null && player.equals(c.owner());
            }

            @Override
            public boolean isUnclaimed(String d, int x, int z) {
                return claimAt(d, x, z) == null;
            }

            @Override
            public boolean mayBuild(UUID player, String d, int x, int z) {
                ClaimBridge.ClaimInfo c = claimAt(d, x, z);
                return c == null || player.equals(c.owner());
            }
        };

        ClaimBridge.setBackendForTest(backend);

        assertTrue(ClaimBridge.isAvailable());

        // Inside claim (50, 50)
        ClaimBridge.ClaimInfo info = ClaimBridge.claimAt(dim, 50, 50);
        assertNotNull(info);
        assertEquals(1L, info.id());
        assertEquals(owner, info.owner());
        assertEquals(dim, info.dimension());

        assertTrue(ClaimBridge.isOwnClaim(owner, dim, 50, 50));
        assertFalse(ClaimBridge.isOwnClaim(other, dim, 50, 50));
        assertFalse(ClaimBridge.isUnclaimed(dim, 50, 50));
        assertTrue(ClaimBridge.mayBuild(owner, dim, 50, 50));
        assertFalse(ClaimBridge.mayBuild(other, dim, 50, 50));

        // Outside claim (500, 500)
        assertNull(ClaimBridge.claimAt(dim, 500, 500));
        assertFalse(ClaimBridge.isOwnClaim(owner, dim, 500, 500));
        assertTrue(ClaimBridge.isUnclaimed(dim, 500, 500));
        assertTrue(ClaimBridge.mayBuild(owner, dim, 500, 500));
        assertTrue(ClaimBridge.mayBuild(other, dim, 500, 500));
    }
}
