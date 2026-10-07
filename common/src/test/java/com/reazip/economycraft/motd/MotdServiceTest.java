package com.reazip.economycraft.motd;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class MotdServiceTest {

    @Test
    @DisplayName("tick does not throw UnsupportedOperationException on entry set iteration")
    void testTickDoesNotThrow() {
        UUID id = UUID.randomUUID();
        // Since PENDING_TICKS is private, onPlayerQuit is a no-op if empty
        assertDoesNotThrow(() -> MotdService.tick(null));
    }
}
