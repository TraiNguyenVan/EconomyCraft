package com.reazip.economycraft.gossip;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * Thread-safe sliding ring buffer of recently spoken village gossip lines and dialogue topics.
 * Used for negative prompting to prevent AI villagers from fixating on the same topic repeatedly.
 */
public final class RecentSpokenTracker {
    public static final int DEFAULT_CAPACITY = 8;

    private final int capacity;
    private final Deque<String> recentEntries;

    public RecentSpokenTracker() {
        this(DEFAULT_CAPACITY);
    }

    public RecentSpokenTracker(int capacity) {
        this.capacity = Math.max(2, capacity);
        this.recentEntries = new ArrayDeque<>(this.capacity);
    }

    /**
     * Records a recently spoken rumor, dialogue, or topic summary into the rolling buffer.
     */
    public synchronized void recordSpoken(@Nullable String message) {
        if (message == null || message.isBlank()) {
            return;
        }

        String cleaned = message.trim();
        // Remove brackets/prefixes if any
        if (cleaned.startsWith("[") && cleaned.indexOf(']') > 0 && cleaned.indexOf(']') < cleaned.length() - 1) {
            cleaned = cleaned.substring(cleaned.indexOf(']') + 1).trim();
        }

        // Avoid adding exact duplicate consecutively
        if (!recentEntries.isEmpty() && recentEntries.peekLast().equalsIgnoreCase(cleaned)) {
            return;
        }

        if (recentEntries.size() >= capacity) {
            recentEntries.removeFirst();
        }
        recentEntries.addLast(cleaned);
    }

    /**
     * Returns an unmodifiable snapshot list of recently spoken topics in chronological order.
     */
    public synchronized List<String> getRecentSpoken() {
        if (recentEntries.isEmpty()) {
            return List.of();
        }
        return Collections.unmodifiableList(new ArrayList<>(recentEntries));
    }

    /**
     * Clears all recorded entries.
     */
    public synchronized void clear() {
        recentEntries.clear();
    }

    /**
     * Returns current count of stored recent entries.
     */
    public synchronized int size() {
        return recentEntries.size();
    }
}
