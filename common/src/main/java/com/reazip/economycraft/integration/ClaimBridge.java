package com.reazip.economycraft.integration;

import dev.architectury.platform.Platform;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * Land-claim bridge to ShopGuard (Phase 10).
 *
 * <p>Isolates all cross-mod coupling so EconomyCraft never requires ShopGuard. On NeoForge (where ShopGuard
 * does not exist) or servers without ShopGuard installed, this bridge degrades gracefully with a single
 * startup warning, and claim-dependent faction effects (Monarchy {@code Phép vua}, Anarchism {@code Thoải mái})
 * become inert.
 */
public final class ClaimBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger("EconomyCraft-ClaimBridge");

    public record ClaimInfo(long id, UUID owner, String dimension) {}

    public interface Backend {
        ClaimInfo claimAt(String dim, int x, int z);
        boolean isOwnClaim(UUID player, String dim, int x, int z);
        boolean isUnclaimed(String dim, int x, int z);
        boolean mayBuild(UUID player, String dim, int x, int z);
    }

    private static volatile Backend cached;
    private static volatile Backend testBackend;
    private static volatile boolean testOverrideActive = false;
    private static boolean warnedMissing;

    private ClaimBridge() {}

    public static void setBackendForTest(Backend backend) {
        testBackend = backend;
        testOverrideActive = true;
    }

    public static void resetBackendForTest() {
        testBackend = null;
        testOverrideActive = false;
    }

    public static Backend backend() {
        if (testOverrideActive) return testBackend;
        Backend current = cached;
        if (current != null) return current;

        try {
            if (!Platform.isModLoaded("shopguard")) {
                if (!warnedMissing) {
                    warnedMissing = true;
                    LOGGER.warn("[EconomyCraft] ShopGuard is not installed — land-claim faction rules "
                            + "(Monarchy Phép vua, Anarchism Thoải mái) are disabled.");
                }
                return null;
            }
        } catch (Throwable t) {
            // Outside of a mod runtime (e.g. plain unit tests)
            return null;
        }

        synchronized (ClaimBridge.class) {
            if (cached == null) {
                try {
                    cached = new ReflectiveShopGuardBackend();
                } catch (Throwable t) {
                    LOGGER.error("[EconomyCraft] Failed to initialize ShopGuard claim backend", t);
                }
            }
            return cached;
        }
    }

    public static boolean isAvailable() {
        return backend() != null;
    }

    public static ClaimInfo claimAt(String dim, int x, int z) {
        Backend b = backend();
        return b == null ? null : b.claimAt(dim, x, z);
    }

    public static boolean isOwnClaim(UUID player, String dim, int x, int z) {
        if (player == null) return false;
        Backend b = backend();
        return b != null && b.isOwnClaim(player, dim, x, z);
    }

    public static boolean isOwnClaim(ServerPlayer player) {
        if (player == null) return false;
        String dim = player.level().dimension().identifier().toString();
        return isOwnClaim(player.getUUID(), dim, player.getBlockX(), player.getBlockZ());
    }

    public static boolean isUnclaimed(String dim, int x, int z) {
        Backend b = backend();
        return b != null && b.isUnclaimed(dim, x, z);
    }

    public static boolean isUnclaimed(ServerPlayer player) {
        if (player == null) return false;
        String dim = player.level().dimension().identifier().toString();
        return isUnclaimed(dim, player.getBlockX(), player.getBlockZ());
    }

    public static boolean mayBuild(UUID player, String dim, int x, int z) {
        if (player == null) return false;
        Backend b = backend();
        return b == null || b.mayBuild(player, dim, x, z);
    }

    public static boolean mayBuild(ServerPlayer player, String dim, int x, int z) {
        return player != null && mayBuild(player.getUUID(), dim, x, z);
    }
}
