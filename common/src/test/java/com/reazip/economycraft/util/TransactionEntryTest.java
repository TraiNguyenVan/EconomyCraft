package com.reazip.economycraft.util;

import com.google.gson.JsonObject;
import com.reazip.economycraft.api.v1.BalanceMutationType;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TransactionEntryTest {

    @Test
    void readsOldRowsWithoutItemMetadata() {
        JsonObject row = row();

        TransactionEntry entry = TransactionEntry.fromJson(row);

        assertEquals(BalanceMutationType.PAYMENT_RECEIVED, entry.type());
        assertNull(entry.item());
        assertEquals(0, entry.quantity());
        assertNull(entry.marketKey());
    }

    @Test
    void readsItemAndQuantityForNewAuctionRows() {
        JsonObject row = row();
        row.addProperty("item", "minecraft:iron_ingot");
        row.addProperty("quantity", 64);
        row.addProperty("market_key", "minecraft:iron_ingot");

        TransactionEntry entry = TransactionEntry.fromJson(row);

        assertEquals("minecraft:iron_ingot", entry.item());
        assertEquals(64, entry.quantity());
        assertEquals("minecraft:iron_ingot", entry.marketKey());
    }

    private static JsonObject row() {
        JsonObject row = new JsonObject();
        row.addProperty("time", "2026-10-31T00:00:00Z");
        row.addProperty("type", BalanceMutationType.PAYMENT_RECEIVED.name());
        row.addProperty("player", UUID.randomUUID().toString());
        row.addProperty("amount", 640L);
        row.addProperty("balance_before", 0L);
        row.addProperty("balance_after", 640L);
        row.addProperty("source", "economycraft:auction_purchase");
        return row;
    }
}
