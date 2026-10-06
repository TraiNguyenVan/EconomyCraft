package com.reazip.economycraft.gossip;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Thread-safe, lock-free tracker for per-player, per-villager gossip cooldowns.
 * Uses a composite (playerUuid, villagerUuid) key and ConcurrentHashMap.
 */
public final class CooldownTracker {

    /**
     * Composite key identifying a specific player interacting with a specific villager.
     */
    public record CooldownKey(UUID playerUuid, UUID villagerUuid) {
        public CooldownKey {
            Objects.requireNonNull(playerUuid, "playerUuid cannot be null");
            Objects.requireNonNull(villagerUuid, "villagerUuid cannot be null");
        }
    }

    private final ConcurrentHashMap<CooldownKey, Long> cooldowns = new ConcurrentHashMap<>();
    private final LongSupplier timeSupplier;

    public CooldownTracker() {
        this(System::currentTimeMillis);
    }

    public CooldownTracker(LongSupplier timeSupplier) {
        this.timeSupplier = Objects.requireNonNull(timeSupplier, "timeSupplier cannot be null");
    }

    /**
     * Checks if a player's interaction with a villager is currently on cooldown.
     * Performs lazy pruning if the cooldown timestamp has passed.
     *
     * @param playerUuid   UUID of the player
     * @param villagerUuid UUID of the villager
     * @return true if still on cooldown, false otherwise
     */
    public boolean isOnCooldown(@Nullable UUID playerUuid, @Nullable UUID villagerUuid) {
        if (playerUuid == null || villagerUuid == null) {
            return false;
        }

        CooldownKey key = new CooldownKey(playerUuid, villagerUuid);
        Long expiresAt = cooldowns.get(key);
        if (expiresAt == null) {
            return false;
        }

        long now = timeSupplier.getAsLong();
        if (now < expiresAt) {
            return true;
        }

        // Expired: prune lazily using atomic conditional removal
        cooldowns.remove(key, expiresAt);
        return false;
    }

    /**
     * Sets a cooldown for a player-villager pair for the specified duration.
     *
     * @param playerUuid     UUID of the player
     * @param villagerUuid   UUID of the villager
     * @param durationMillis duration in milliseconds
     */
    public void setCooldown(@Nullable UUID playerUuid, @Nullable UUID villagerUuid, long durationMillis) {
        if (playerUuid == null || villagerUuid == null || durationMillis <= 0) {
            return;
        }

        long expiresAt = timeSupplier.getAsLong() + durationMillis;
        cooldowns.put(new CooldownKey(playerUuid, villagerUuid), expiresAt);
    }

    /**
     * Actively prunes all expired cooldown entries from the tracker.
     */
    public void pruneExpired() {
        long now = timeSupplier.getAsLong();
        cooldowns.entrySet().removeIf(entry -> entry.getValue() <= now);
    }

    /**
     * Returns the remaining cooldown duration in milliseconds, or 0 if not on cooldown.
     */
    public long getRemainingMillis(@Nullable UUID playerUuid, @Nullable UUID villagerUuid) {
        if (playerUuid == null || villagerUuid == null) {
            return 0L;
        }

        Long expiresAt = cooldowns.get(new CooldownKey(playerUuid, villagerUuid));
        if (expiresAt == null) {
            return 0L;
        }

        long remaining = expiresAt - timeSupplier.getAsLong();
        return Math.max(0L, remaining);
    }

    /**
     * Current number of tracked cooldown entries.
     */
    public int size() {
        return cooldowns.size();
    }

    /**
     * Clears all cooldown entries.
     */
    public void clear() {
        cooldowns.clear();
    }
}
