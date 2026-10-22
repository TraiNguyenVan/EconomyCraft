package com.reazip.economycraft.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.reazip.economycraft.EconomyConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The HTTP market snapshot's config keys bind by name, and this class has no field-naming
 * policy: an unannotated field binds to its Java name and silently ignores the snake_case key
 * every other key in this file uses.
 *
 * <p>That failure is invisible. {@code http_market_enabled: true} in config.json left the field
 * false, the listener never bound, and the server booted clean with zero errors and no warning.
 * Found only by deploying it and asking why nothing was listening. These tests exist so the
 * second occurrence is a failing build instead of a silent no-op.
 */
class MarketSnapshotConfigKeyTest {

    /** Mirrors EconomyConfig's own Gson: no naming policy, so annotations are the only mapping. */
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    @Test
    void snakeCaseKeysBindToTheFields() {
        EconomyConfig parsed = GSON.fromJson(
                "{"
                        + "\"http_market_enabled\": true,"
                        + "\"http_market_port\": 18081,"
                        + "\"http_market_token\": \"s3cret\""
                        + "}",
                EconomyConfig.class);

        assertTrue(parsed.httpMarketEnabled, "http_market_enabled did not bind");
        assertEquals(18081, parsed.httpMarketPort, "http_market_port did not bind");
        assertEquals("s3cret", parsed.httpMarketToken, "http_market_token did not bind");
    }

    @Test
    void absentKeysFallBackToInertDefaults() {
        EconomyConfig parsed = GSON.fromJson("{}", EconomyConfig.class);

        assertFalse(parsed.httpMarketEnabled, "the snapshot must be off unless config says otherwise");
        assertEquals(0, parsed.httpMarketPort);
        assertEquals("", parsed.httpMarketToken);
    }
}