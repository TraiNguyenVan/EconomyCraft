package com.reazip.economycraft.gossip.storage;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Immutable record representing an individual villager's memory of and relationship with a specific player.
 */
public record PlayerMemory(
        UUID villagerUuid,
        UUID playerUuid,
        int sentiment,             // Range: -100 (Hostile) to +100 (Devoted)
        int interactionCount,
        long totalSpent,
        long lastInteraction,
        List<String> recentEvents   // Most recent 5-10 notable interaction events
) {
    public static final int MIN_SENTIMENT = -100;
    public static final int MAX_SENTIMENT = 100;
    public static final int MAX_RECENT_EVENTS = 10;

    private static final Gson GSON = new Gson();
    private static final Type LIST_STRING_TYPE = new TypeToken<List<String>>() {}.getType();

    public PlayerMemory {
        if (villagerUuid == null) throw new IllegalArgumentException("villagerUuid cannot be null");
        if (playerUuid == null) throw new IllegalArgumentException("playerUuid cannot be null");
        sentiment = Math.clamp(sentiment, MIN_SENTIMENT, MAX_SENTIMENT);
        interactionCount = Math.max(0, interactionCount);
        totalSpent = Math.max(0L, totalSpent);
        if (recentEvents == null || recentEvents.isEmpty()) {
            recentEvents = List.of();
        } else {
            List<String> bounded = new ArrayList<>();
            for (String event : recentEvents) {
                if (event == null || event.isBlank() || event.length() > 240 || event.chars().anyMatch(Character::isISOControl)) continue;
                bounded.add(event.trim());
                if (bounded.size() > MAX_RECENT_EVENTS) bounded.remove(0);
            }
            recentEvents = Collections.unmodifiableList(bounded);
        }
    }

    public static PlayerMemory createDefault(UUID villagerUuid, UUID playerUuid, long currentTime) {
        return new PlayerMemory(villagerUuid, playerUuid, 0, 0, 0L, currentTime, List.of());
    }

    public String sentimentDescription() {
        if (sentiment <= -60) return "Hostile and deeply distrustful";
        if (sentiment <= -20) return "Wary and cold";
        if (sentiment <= 20) return "Neutral and purely businesslike";
        if (sentiment <= 60) return "Warm and friendly regular customer";
        return "Devoted and trustworthy favorite customer";
    }

    public PlayerMemory withInteraction(int sentimentDelta, @Nullable String eventSummary, long timestamp) {
        int newSentiment = Math.clamp(sentiment + sentimentDelta, MIN_SENTIMENT, MAX_SENTIMENT);
        int newCount = interactionCount + 1;
        List<String> newEvents = new ArrayList<>(recentEvents);
        if (eventSummary != null && !eventSummary.isBlank()) {
            newEvents.add(eventSummary.trim());
            while (newEvents.size() > MAX_RECENT_EVENTS) {
                newEvents.remove(0);
            }
        }
        return new PlayerMemory(villagerUuid, playerUuid, newSentiment, newCount, totalSpent, timestamp, newEvents);
    }

    public PlayerMemory withTrade(long amountSpent, @Nullable String itemDescription, long timestamp) {
        long newTotalSpent = totalSpent + Math.max(0L, amountSpent);
        int newCount = interactionCount + 1;
        // Successful trades increase sentiment slightly (+2 to +5)
        int sentimentBonus = amountSpent > 500 ? 5 : 2;
        int newSentiment = Math.clamp(sentiment + sentimentBonus, MIN_SENTIMENT, MAX_SENTIMENT);
        List<String> newEvents = new ArrayList<>(recentEvents);
        String event = "Completed a trade";
        newEvents.add(event);
        while (newEvents.size() > MAX_RECENT_EVENTS) {
            newEvents.remove(0);
        }
        return new PlayerMemory(villagerUuid, playerUuid, newSentiment, newCount, newTotalSpent, timestamp, newEvents);
    }

    public String eventsJson() {
        return GSON.toJson(recentEvents);
    }

    public static List<String> parseEventsJson(@Nullable String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<String> list = GSON.fromJson(json, LIST_STRING_TYPE);
            return list != null ? list : List.of();
        } catch (Exception e) {
            return List.of();
        }
    }

    public PlayerMemory withValidatedEvents(List<String> validatedEvents) {
        return new PlayerMemory(villagerUuid, playerUuid, sentiment, interactionCount, totalSpent,
                lastInteraction, validatedEvents);
    }
}
