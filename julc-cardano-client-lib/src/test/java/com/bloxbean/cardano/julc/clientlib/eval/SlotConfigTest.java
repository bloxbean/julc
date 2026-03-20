package com.bloxbean.cardano.julc.clientlib.eval;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SlotConfigTest {

    @Test
    void slotToPosixMs_basicConversion() {
        var config = new SlotConfig(0, 1000, 1000);
        assertEquals(11000, config.slotToPosixMs(10));
    }

    @Test
    void slotToPosixMs_mainnetSlot() {
        var config = SlotConfig.mainnet();
        // slot 4492800 → exactly zeroSlotPosixMs
        assertEquals(1596059091000L, config.slotToPosixMs(4492800));
        // slot 4492801 → +1000ms
        assertEquals(1596059092000L, config.slotToPosixMs(4492801));
    }

    @Test
    void slotToPosixMs_preprodSlot() {
        var config = SlotConfig.preprod();
        // slot 86400 → exactly zeroSlotPosixMs
        long expected = 1654041600000L + 86400 * 1000L;
        assertEquals(expected, config.slotToPosixMs(86400));
    }

    @Test
    void mainnet_correctValues() {
        var config = SlotConfig.mainnet();
        assertEquals(4492800, config.zeroSlot());
        assertEquals(1596059091000L, config.zeroSlotPosixMs());
        assertEquals(1000, config.slotLengthMs());
    }

    @Test
    void preprod_correctValues() {
        var config = SlotConfig.preprod();
        assertEquals(86400, config.zeroSlot());
        assertEquals(1000, config.slotLengthMs());
    }
}
