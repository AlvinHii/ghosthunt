package com.ghosthunt.game.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PathFollowTest {

    @Test
    void straight_isOne() {
        assertEquals(1.0, PathFollow.lookahead(10, false), 1e-9);
    }

    @Test
    void corner_looksFurther() {
        assertEquals(1.75, PathFollow.lookahead(45, false), 1e-9);
    }

    @Test
    void door_staysOne() {
        assertEquals(1.0, PathFollow.lookahead(90, true), 1e-9);
    }

    @Test
    void arrival_slowsNearEnd() {
        assertEquals(0.5, PathFollow.arrivalScale(1.0, true), 1e-9);
        assertEquals(0.35, PathFollow.arrivalScale(0.2, true), 1e-9);
        assertEquals(1.0, PathFollow.arrivalScale(3.0, true), 1e-9);
    }

    @Test
    void chasing_noSlow() {
        assertEquals(1.0, PathFollow.arrivalScale(0.5, true, true), 1e-9);
        assertEquals(0.5, PathFollow.arrivalScale(1.0, true, false), 1e-9);
    }

    @Test
    void segmentClear_usesActualPositionAndBodyWidth() {
        // 只有 (2,0) 那格不能走（例如木桶）。從實際位置 (0.5, 1.1) 往西→東走：
        // 身體往北偏 0.35 會碰到 z=0 那排的 (2,0) → 不通；從 (0.5, 1.6) 走就通
        java.util.function.BiPredicate<Integer, Integer> ok = (x, z) -> !(x == 2 && z == 0) && z >= 0 && z <= 2 && x >= 0 && x <= 5;
        org.junit.jupiter.api.Assertions.assertFalse(PathFollow.segmentClear(0.5, 1.1, 4.5, 1.1, 0.35, ok));
        org.junit.jupiter.api.Assertions.assertTrue(PathFollow.segmentClear(0.5, 1.6, 4.5, 1.6, 0.35, ok));
    }

    @Test
    void notLast_noSlow() {
        assertEquals(1.0, PathFollow.arrivalScale(0.5, false), 1e-9);
    }
}
