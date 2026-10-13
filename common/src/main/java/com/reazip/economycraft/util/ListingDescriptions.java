package com.reazip.economycraft.util;

import org.jetbrains.annotations.Nullable;

/** Single home for the auction/order listing description rule. */
public final class ListingDescriptions {
    private ListingDescriptions() {}

    /** Strips line breaks, trims, maps blank to null and caps at 100 chars. */
    public static @Nullable String sanitize(@Nullable String description) {
        if (description == null) return null;
        String desc = description.replace("\r", "").replace("\n", "").trim();
        if (desc.isEmpty()) return null;
        return desc.length() > 100 ? desc.substring(0, 100) : desc;
    }
}
