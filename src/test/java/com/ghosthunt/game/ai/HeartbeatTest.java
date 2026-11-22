package com.ghosthunt.game.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 心跳：距離 → 每分鐘幾下、音量、音調（越近越快、越大聲）。 */
class HeartbeatTest {

    private final Heartbeat h = new Heartbeat(32, 55, 150, 0.3, 1.0, 1.15);

    @Test
    void outOfRange_silent() {
        assertEquals(-1, h.intervalTicks(33));
        assertEquals(-1, h.intervalTicks(Double.POSITIVE_INFINITY));
    }

    @Test
    void closer_isFasterLouderHigher() {
        assertTrue(h.bpm(4) > h.bpm(16));
        assertTrue(h.bpm(16) > h.bpm(30));
        assertTrue(h.volume(4) > h.volume(30));
        assertTrue(h.pitch(4) > h.pitch(30));
    }

    @Test
    void endpoints() {
        assertEquals(150, h.bpm(0), 1e-9);
        assertEquals(55, h.bpm(32), 1e-9);
        assertEquals(8, h.intervalTicks(0)); // 150 下／分 = 0.4 秒一下 = 8 tick
        assertEquals(1.0, h.volume(0), 1e-9);
        assertEquals(0.3, h.volume(32), 1e-9);
        assertEquals(1.0, h.pitch(32), 1e-9);
    }

    @Test
    void smooth_noJumps() {
        // 每靠近 1 格，間隔最多少 1 tick（不會突然跳很多）
        int prev = h.intervalTicks(32);
        for (int d = 31; d >= 0; d--) {
            int cur = h.intervalTicks(d);
            assertTrue(cur <= prev && prev - cur <= 2, "d=" + d + " " + prev + "→" + cur);
            prev = cur;
        }
    }
}
