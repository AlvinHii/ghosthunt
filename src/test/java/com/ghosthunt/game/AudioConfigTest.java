package com.ghosthunt.game;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AudioConfigTest {

    private static AudioConfig sample() {
        return AudioConfig.parse(List.of(
                new String[]{"loop", "30"},
                new String[]{"global", "hydro.global 0.35"},
                new String[]{"zone", "hydro.turbine 0.8 1F 2,27 13,36 47,16 37,10"},
                new String[]{"zone", "hydro.turbine 0.45 2F 6,18 18,25"},
                new String[]{"zone", "hydro.dam 0.8 1F 13,2 44,8"},
                new String[]{"tunnel", "hydro.tunnel 0.9"},
                new String[]{"random", "hydro.creak 100 45 0.5"},
                new String[]{"power-off", "minecraft:block.beacon.deactivate"}));
    }

    @Test
    void parsesAllKeys() {
        AudioConfig c = sample();
        assertTrue(c.problems.isEmpty(), c.problems.toString());
        assertEquals(30, c.loopSeconds);
        assertEquals("ghosthunt:hydro.global", c.global.sound());
        assertEquals(0.35f, c.global.volume(), 1e-6);
        assertEquals(3, c.zones.size());
        assertEquals(2, c.zones.get(0).rects().size());
        assertEquals("minecraft:block.beacon.deactivate", c.powerOff.sound()); // 有命名空間就不加 ghosthunt:
        assertEquals(1f, c.powerOff.volume());
        // 最短、最長寫反了也照小到大
        assertEquals(45, c.randoms.get(0).minSec());
        assertEquals(100, c.randoms.get(0).maxSec());
    }

    @Test
    void zoneLookup() {
        AudioConfig c = sample();
        assertEquals("ghosthunt:hydro.turbine", c.zoneAt(0, 5, 30, false).sound());
        assertEquals("ghosthunt:hydro.turbine", c.zoneAt(0, 40, 12, false).sound()); // 角寫反了（47,16 37,10）也算
        assertEquals(0.45f, c.zoneAt(1, 10, 20, false).volume(), 1e-6);              // 2F 那一筆
        assertNull(c.zoneAt(1, 5, 30, false));                                      // 1F 的區域，2F 不算
        assertEquals("ghosthunt:hydro.dam", c.zoneAt(0, 13, 2, false).sound());     // 邊界含
        assertNull(c.zoneAt(0, 30, 40, false));
        assertEquals("ghosthunt:hydro.tunnel", c.zoneAt(0, 5, 30, true).sound());   // 地下優先
    }

    @Test
    void badLinesAreReportedNotThrown() {
        AudioConfig c = AudioConfig.parse(List.of(
                new String[]{"zone", "hydro.x 0.5 1F"},
                new String[]{"random", "hydro.y abc"},
                new String[]{"whatever", "1"}));
        assertEquals(3, c.problems.size());
        assertTrue(c.isEmpty());
    }
}
