package com.ghosthunt.game.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class SoundFieldTest {

    private static final GridNav.Cost NONE = e -> 0;

    @Test
    void travelsAroundWall() {
        GridNav g = TestGrids.grid("..#..", ".....");
        SoundField.Heard h = SoundField.hear(g, TestGrids.id(g, 0, 0), TestGrids.id(g, 4, 0), 16, NONE, 6);
        assertNotNull(h);
        assertEquals(6, h.distance(), 1e-9); // 直線 4 格，但要繞過牆
    }

    @Test
    void beyondRange_notHeard() {
        GridNav g = TestGrids.grid("..#..", ".....");
        assertNull(SoundField.hear(g, TestGrids.id(g, 0, 0), TestGrids.id(g, 4, 0), 5, NONE, 6));
    }

    @Test
    void closedDoor_addsDistance() {
        GridNav g = TestGrids.grid("..D..");
        GridNav.Cost door = e -> e.kind() == GridNav.Kind.DOOR ? 2 : 0; // 進出各 2 → 關著的門 +4
        SoundField.Heard h = SoundField.hear(g, TestGrids.id(g, 0, 0), TestGrids.id(g, 4, 0), 16, door, 6);
        assertEquals(8, h.distance(), 1e-9);
    }

    @Test
    void heardPoint_isAlongPathFromListener() {
        // 聲音在走廊另一頭：聽到的位置是「從聽的人往聲音方向 2 格」那格
        GridNav g = TestGrids.grid("..........");
        SoundField.Heard h = SoundField.hear(g, TestGrids.id(g, 0, 0), TestGrids.id(g, 9, 0), 16, NONE, 2);
        assertEquals(TestGrids.id(g, 7, 0), h.pointNode());
    }

    @Test
    void closeSound_pointIsSource() {
        GridNav g = TestGrids.grid("..........");
        SoundField.Heard h = SoundField.hear(g, TestGrids.id(g, 8, 0), TestGrids.id(g, 9, 0), 16, NONE, 6);
        assertEquals(TestGrids.id(g, 8, 0), h.pointNode());
    }

    @Test
    void farSound_pointStopsShortOfSource_alongPath() {
        // 聲音 9 格遠：聽到的位置是「沿路離聲音 3 格」（至少離聽的人 2 格），不是直線上的誤差
        GridNav g = TestGrids.grid("..........");
        SoundField.Heard h = SoundField.hear(g, TestGrids.id(g, 0, 0), TestGrids.id(g, 9, 0), 16, NONE, 2, 3);
        assertEquals(TestGrids.id(g, 3, 0), h.pointNode());
    }

    @Test
    void shortOfSource_neverCloserThanToward() {
        GridNav g = TestGrids.grid("..........");
        SoundField.Heard h = SoundField.hear(g, TestGrids.id(g, 5, 0), TestGrids.id(g, 9, 0), 16, NONE, 2, 3);
        assertEquals(TestGrids.id(g, 7, 0), h.pointNode()); // d=4，4-3=1 < 2 → 用 2
    }
}
