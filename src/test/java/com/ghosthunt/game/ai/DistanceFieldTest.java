package com.ghosthunt.game.ai;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DistanceFieldTest {

    @Test
    void aroundWall_matchesAStar() {
        GridNav g = TestGrids.grid(
                "......",
                ".####.",
                "......");
        int a = TestGrids.id(g, 0, 1), b = TestGrids.id(g, 5, 1);
        DistanceField f = DistanceField.compute(g, e -> 0, 1.0, a);
        List<Integer> p = g.path(a, b, e -> 0);
        assertEquals(p.size() - 1, f.seconds(b), 1e-9); // 每條邊長度 1、速度 1 格/秒
    }

    @Test
    void multiSource_takesMinimum() {
        GridNav g = TestGrids.grid("..........");
        DistanceField f = DistanceField.compute(g, e -> 0, 1.0, TestGrids.id(g, 0, 0), TestGrids.id(g, 9, 0));
        assertEquals(2, f.seconds(TestGrids.id(g, 7, 0)), 1e-9);
        assertEquals(3, f.seconds(TestGrids.id(g, 3, 0)), 1e-9);
        assertEquals(0, f.seconds(TestGrids.id(g, 9, 0)), 1e-9);
    }

    @Test
    void specialEdge_addsExtraOverSpeed() {
        GridNav g = TestGrids.grid("..B..");
        DistanceField f = DistanceField.compute(g, e -> e.kind() == GridNav.Kind.BARRICADE ? 3 : 0, 2.0, TestGrids.id(g, 0, 0));
        assertEquals((4 + 3 + 3) / 2.0, f.seconds(TestGrids.id(g, 4, 0)), 1e-9); // 進出兩條邊各 +3
    }

    @Test
    void infiniteEdge_isNotCrossed() {
        GridNav g = TestGrids.grid("..B..");
        DistanceField f = DistanceField.compute(g, e -> e.kind() == GridNav.Kind.BARRICADE ? Double.POSITIVE_INFINITY : 0, 1, TestGrids.id(g, 0, 0));
        assertTrue(Double.isInfinite(f.seconds(TestGrids.id(g, 4, 0))));
        assertEquals(1, f.seconds(TestGrids.id(g, 1, 0)), 1e-9);
    }

    @Test
    void downhill_stepsTowardSource() {
        GridNav g = TestGrids.grid(
                "......",
                ".####.",
                "......");
        int src = TestGrids.id(g, 0, 1);
        DistanceField f = DistanceField.compute(g, e -> 0, 1.0, src);
        int v = TestGrids.id(g, 5, 1);
        int steps = 0;
        while (v != src) {
            int next = f.downhill(v);
            assertTrue(next >= 0);
            assertTrue(f.seconds(next) < f.seconds(v));
            v = next;
            assertTrue(++steps < 50);
        }
        assertEquals(-1, f.downhill(src)); // 起點已經是最小
    }

    @Test
    void invalidNode_isInfinite() {
        GridNav g = TestGrids.grid("...");
        DistanceField f = DistanceField.compute(g, e -> 0, 1.0, TestGrids.id(g, 0, 0));
        assertTrue(Double.isInfinite(f.seconds(-1)));
        assertEquals(-1, f.downhill(-1));
    }
}
