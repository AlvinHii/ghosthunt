package com.ghosthunt.game.ai;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InterceptorTest {

    @Test
    void movingEast_eastCandidateMostLikely() {
        double[][] cands = {{10, 0, 0}, {-10, 0, 0}, {0, 10, 0}};
        double[] p = Interceptor.targetProbabilities(0, 0, 1, 0, cands, 0.3);
        assertTrue(p[0] > 0.5, "東邊 " + p[0]);
        assertTrue(p[0] > p[1] && p[0] > p[2]);
        assertEquals(1.0, p[0] + p[1] + p[2], 1e-9);
    }

    @Test
    void approaching_raisesProbability() {
        // 兩個方向差不多，但過去 1 秒離第二個變近很多
        double[][] cands = {{10, 1, 0}, {10, -1, 2.0}};
        double[] p = Interceptor.targetProbabilities(0, 0, 1, 0, cands, 0.3);
        assertTrue(p[1] > p[0]);
    }

    @Test
    void loop_ghostCutsAcrossToWhereItArrivesFirst() {
        // 回字形：逃生者在上排往西跑、繞到左邊往下；鬼在左下角 → 最先「鬼早到 0.5 秒以上」的格子是左上角 (0,0)
        GridNav g = TestGrids.grid(
                ".........",
                ".#######.",
                ".........");
        DistanceField ghost = DistanceField.compute(g, e -> 0, 1, TestGrids.id(g, 2, 2));
        DistanceField esc = DistanceField.compute(g, e -> 0, 1, TestGrids.id(g, 6, 0));
        List<Integer> path = List.of(TestGrids.id(g, 6, 0), TestGrids.id(g, 5, 0), TestGrids.id(g, 4, 0), TestGrids.id(g, 3, 0),
                TestGrids.id(g, 2, 0), TestGrids.id(g, 1, 0), TestGrids.id(g, 0, 0), TestGrids.id(g, 0, 1), TestGrids.id(g, 0, 2));
        assertEquals(TestGrids.id(g, 0, 0), Interceptor.interceptNode(path, ghost, esc, 0.5));
    }

    @Test
    void noPointWhereGhostIsEarlier_returnsMinusOne() {
        GridNav g = TestGrids.grid("..........");
        DistanceField ghost = DistanceField.compute(g, e -> 0, 1, TestGrids.id(g, 0, 0));
        DistanceField esc = DistanceField.compute(g, e -> 0, 1, TestGrids.id(g, 3, 0));
        List<Integer> path = List.of(TestGrids.id(g, 3, 0), TestGrids.id(g, 4, 0), TestGrids.id(g, 5, 0), TestGrids.id(g, 6, 0));
        assertEquals(-1, Interceptor.interceptNode(path, ghost, esc, 0.5));
    }
}
