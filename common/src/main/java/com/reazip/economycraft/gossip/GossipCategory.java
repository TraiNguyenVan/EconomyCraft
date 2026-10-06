package com.reazip.economycraft.gossip;

import java.util.Locale;

public enum GossipCategory {
    FARMER("farmer"),
    BLACKSMITH("blacksmith"),
    CLERIC("cleric"),
    LIBRARIAN("librarian"),
    NITWIT("nitwit"),
    GENERAL("general");

    private final String jsonKey;

    GossipCategory(String jsonKey) {
        this.jsonKey = jsonKey;
    }

    public String jsonKey() {
        return jsonKey;
    }

    public static GossipCategory fromJsonKey(String key) {
        if (key == null) {
            return GENERAL;
        }
        String normalized = key.trim().toLowerCase(Locale.ROOT);
        for (GossipCategory category : values()) {
            if (category.jsonKey.equals(normalized)) {
                return category;
            }
        }
        return GENERAL;
    }
}
