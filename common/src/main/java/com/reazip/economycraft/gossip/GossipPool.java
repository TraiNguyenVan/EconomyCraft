package com.reazip.economycraft.gossip;

import net.minecraft.util.RandomSource;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public record GossipPool(
        Map<GossipCategory, List<String>> rumorsByCategory,
        Instant generatedAt
) {
    public GossipPool {
        Map<GossipCategory, List<String>> copy = new EnumMap<>(GossipCategory.class);
        if (rumorsByCategory != null) {
            for (Map.Entry<GossipCategory, List<String>> entry : rumorsByCategory.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null && !entry.getValue().isEmpty()) {
                    copy.put(entry.getKey(), List.copyOf(entry.getValue()));
                }
            }
        }
        rumorsByCategory = Collections.unmodifiableMap(copy);
        generatedAt = (generatedAt != null) ? generatedAt : Instant.EPOCH;
    }

    public static GossipPool empty() {
        return new GossipPool(Map.of(), Instant.EPOCH);
    }

    public boolean isEmpty() {
        return rumorsByCategory.isEmpty();
    }

    public int totalRumors() {
        int count = 0;
        for (List<String> list : rumorsByCategory.values()) {
            count += list.size();
        }
        return count;
    }

    public List<String> getRumors(GossipCategory category) {
        return rumorsByCategory.getOrDefault(category, List.of());
    }

    /**
     * Primary Minecraft game thread access point using Minecraft's RandomSource.
     */
    public @Nullable String getRandomRumor(GossipCategory category, RandomSource random) {
        List<String> specific = rumorsByCategory.getOrDefault(category, List.of());
        if (!specific.isEmpty()) {
            return specific.get(random.nextInt(specific.size()));
        }
        List<String> general = rumorsByCategory.getOrDefault(GossipCategory.GENERAL, List.of());
        if (!general.isEmpty()) {
            return general.get(random.nextInt(general.size()));
        }
        return null;
    }

    /**
     * Overload using standard Java Random (convenient for unit tests).
     */
    public @Nullable String getRandomRumor(GossipCategory category, Random random) {
        List<String> specific = rumorsByCategory.getOrDefault(category, List.of());
        if (!specific.isEmpty()) {
            return specific.get(random.nextInt(specific.size()));
        }
        List<String> general = rumorsByCategory.getOrDefault(GossipCategory.GENERAL, List.of());
        if (!general.isEmpty()) {
            return general.get(random.nextInt(general.size()));
        }
        return null;
    }

    /**
     * Thread-safe convenience overload using ThreadLocalRandom.
     */
    public @Nullable String getRandomRumor(GossipCategory category) {
        List<String> specific = rumorsByCategory.getOrDefault(category, List.of());
        if (!specific.isEmpty()) {
            return specific.get(ThreadLocalRandom.current().nextInt(specific.size()));
        }
        List<String> general = rumorsByCategory.getOrDefault(GossipCategory.GENERAL, List.of());
        if (!general.isEmpty()) {
            return general.get(ThreadLocalRandom.current().nextInt(general.size()));
        }
        return null;
    }
}
