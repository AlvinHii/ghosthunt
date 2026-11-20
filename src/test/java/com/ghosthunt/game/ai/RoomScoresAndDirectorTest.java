package com.ghosthunt.game.ai;

import com.ghosthunt.game.ai.RoomScores.RoomInfo;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoomScoresAndDirectorTest {

    private static RoomScores threeRooms() {
        return new RoomScores(List.of(new RoomInfo(0, 0, 65, 0, 0), new RoomInfo(1, 10, 65, 0, 0), new RoomInfo(2, 20, 65, 0, 0)), 1.0, 300);
    }

    private static int[] counts(RoomScores rs, long now, int exclude, java.util.function.ToDoubleFunction<RoomInfo> mul) {
        int[] c = new int[3];
        Random rnd = new Random(7);
        for (int i = 0; i < 3000; i++) c[rs.choose(rnd, now, exclude, mul, r -> 4)]++;
        return c;
    }

    @Test
    void longUnvisitedRoom_isPreferred() {
        RoomScores rs = threeRooms();
        rs.visited(0, 0);
        rs.visited(1, 0);
        rs.visited(2, 0);
        rs.visited(1, 20 * 50); // 房間 1 在 50 秒時去過，0、2 已經 100 秒沒去
        int[] c = counts(rs, 20 * 100, -1, r -> 1);
        assertTrue(c[0] > c[1] && c[2] > c[1]);
    }

    @Test
    void excludedRoom_neverChosen() {
        int[] c = counts(threeRooms(), 20 * 100, 1, r -> 1);
        assertEquals(0, c[1]);
    }

    @Test
    void noise_raisesScore() {
        RoomScores rs = threeRooms();
        for (int i = 0; i < 3; i++) rs.visited(i, 20 * 100);
        rs.noise(2, 60);
        int[] c = counts(rs, 20 * 101, -1, r -> 1);
        assertTrue(c[2] > c[0] + c[1]);
    }

    @Test
    void progressMultiplier_shiftsChoice() {
        // 電源沒開：電力室（房間 0）×3
        int[] c = counts(threeRooms(), 20 * 100, -1, r -> r.id() == 0 ? 3 : 1);
        assertTrue(c[0] > c[1] && c[0] > c[2]);
        // 倍率 0 的房間不會被選
        assertEquals(0, counts(threeRooms(), 20 * 100, -1, r -> r.id() == 0 ? 0 : 1)[0]);
    }

    @Test
    void director_sensesOnlyAfterQuietPeriod() {
        SenseDirector d = new SenseDirector(45 * 20, 45 * 20, 60, 30 * 20);
        d.contact(0);
        assertFalse(d.shouldSense(20 * 30)); // 30 秒沒接觸：還不感應
        assertTrue(d.shouldSense(20 * 46));
        d.sensed(20 * 46);
        assertFalse(d.shouldSense(20 * 60)); // 剛感應過，至少隔 45 秒
        assertTrue(d.shouldSense(20 * 92));
    }

    @Test
    void director_contactResetsQuietTimer() {
        SenseDirector d = new SenseDirector(45 * 20, 45 * 20, 60, 30 * 20);
        d.contact(0);
        d.contact(20 * 40);
        assertFalse(d.shouldSense(20 * 50));
        assertTrue(d.shouldSense(20 * 86));
    }

    @Test
    void director_highPressureOrCapture_calms() {
        SenseDirector d = new SenseDirector(45 * 20, 45 * 20, 60, 30 * 20);
        long t = 0;
        for (int i = 0; i < 61; i++) d.pressure(t += 20, true, 20); // 連續緊追 61 秒
        assertTrue(d.calm(t));
        assertFalse(d.shouldSense(t + 20 * 20)); // 冷靜期內不感應（30 秒）……
        assertNotEquals(d.calm(t + 20 * 31), true); // ……30 秒後解除
        SenseDirector d2 = new SenseDirector(45 * 20, 45 * 20, 60, 30 * 20);
        d2.captured(20 * 100);
        assertTrue(d2.calm(20 * 110));
    }
}
