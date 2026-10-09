package com.reazip.economycraft.config;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class MotdSectionTest {
    @Test
    void defaultsToOneBlockWithThirtySecondSuccessorDelay() {
        MotdSection section = new MotdSection();
        section.clamp();

        assertEquals(1, section.blocks.size());
        assertNotNull(section.blocks.get(0).lines);
        assertEquals(30, section.blocks.get(0).nextDelaySeconds);
    }

    @Test
    void nullBlocksRestoreDefaultButEmptyBlocksDisableMessages() {
        MotdSection section = new MotdSection();
        section.blocks = null;
        section.clamp();
        assertEquals(1, section.blocks.size());

        section.blocks = new ArrayList<>();
        section.clamp();
        assertEquals(List.of(), section.blocks);
    }

    @Test
    void delayBoundsIncludeZeroAndMaximumAndClampOutsideValues() {
        MotdSection section = new MotdSection();
        section.blocks.get(0).nextDelaySeconds = -1;
        MotdBlock maximum = new MotdBlock(List.of("maximum"));
        maximum.nextDelaySeconds = 3600;
        MotdBlock tooLong = new MotdBlock(List.of("clamp"));
        tooLong.nextDelaySeconds = 4000;
        section.blocks.add(maximum);
        section.blocks.add(tooLong);

        section.clamp();

        assertEquals(0, section.blocks.get(0).nextDelaySeconds);
        assertEquals(3600, section.blocks.get(1).nextDelaySeconds);
        assertEquals(3600, section.blocks.get(2).nextDelaySeconds);
    }
}
