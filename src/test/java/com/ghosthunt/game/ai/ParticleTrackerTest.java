package com.ghosthunt.game.ai;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParticleTrackerTest {

    private static final ParticleTracker.Config CFG = new ParticleTracker.Config(200, 5.6, 0.6, 0.0, 3, 10, 800);
    private static final GridNav.Cost FREE = e -> 0;

    private static ParticleTracker tracker(GridNav g, GridNav.Cost cost, long seed) {
        return new ParticleTracker(g, cost, CFG, new Random(seed));
    }

    private static List<Integer> all(GridNav g) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < g.size(); i++) out.add(i);
        return out;
    }

    @Test
    void seedInvalidNode_staysEmpty() {
        GridNav g = TestGrids.grid(".....");
        ParticleTracker t = tracker(g, FREE, 1);
        t.seed(-1, 0);
        assertEquals(0, t.alive());
        assertEquals(-1, t.bestTarget(i -> true, i -> true));
    }

    @Test
    void removeVisible_leavesNoParticleInSeenCells() {
        String row = ".........";
        GridNav g = TestGrids.grid(row, row, row, row, row, row, row, row, row);
        ParticleTracker t = tracker(g, FREE, 1);
        t.seed(TestGrids.id(g, 4, 4), 0);
        t.step(0.5, DistanceField.compute(g, FREE, 5.3, TestGrids.id(g, 0, 0)), null);
        t.removeVisible(i -> g.x(i) < 4);
        for (int p : t.positions()) assertTrue(g.x(p) >= 4);
        assertTrue(t.alive() > 0);
    }

    @Test
    void deadEnd_particlesStayInside() {
        GridNav g = TestGrids.grid(
                "##########......",
                "#...............",
                "##########......");
        ParticleTracker t = tracker(g, FREE, 2);
        t.seed(TestGrids.id(g, 5, 1), 0);
        DistanceField ghost = DistanceField.compute(g, FREE, 5.3, TestGrids.id(g, 12, 1));
        for (int i = 0; i < 10; i++) t.step(0.5, ghost, null);
        long inside = java.util.Arrays.stream(t.positions()).filter(p -> g.x(p) <= 9).count();
        assertTrue(inside >= 0.9 * t.alive(), "走廊裡 " + inside + "/" + t.alive());
    }

    @Test
    void sound_pullsParticles() {
        String row = ".....................";
        String[] rows = new String[21];
        java.util.Arrays.fill(rows, row);
        GridNav g = TestGrids.grid(rows);
        ParticleTracker t = tracker(g, FREE, 3);
        t.seedUniform(all(g), 0);
        double before = near(g, t, 10, 10, 6);
        for (int i = 0; i < 3; i++) t.weightNear(10.5, TestGrids.Y, 10.5, 2);
        double after = near(g, t, 10, 10, 6);
        assertTrue(after >= before * 1.5, "附近的比例 " + before + " → " + after);
        assertEquals(200, t.alive());
    }

    private static double near(GridNav g, ParticleTracker t, int x, int z, double r) {
        int[] ps = t.positions();
        return java.util.Arrays.stream(ps).filter(p -> Math.hypot(g.x(p) - x, g.z(p) - z) <= r).count() / (double) ps.length;
    }

    @Test
    void particlesDoNotCrossBlockedEdge() {
        GridNav g = TestGrids.grid("...B...");
        GridNav.Cost blocked = e -> e.kind() == GridNav.Kind.BARRICADE ? Double.POSITIVE_INFINITY : 0;
        ParticleTracker t = tracker(g, blocked, 4);
        t.seed(TestGrids.id(g, 0, 0), 0);
        DistanceField ghost = DistanceField.compute(g, FREE, 5.3, TestGrids.id(g, 0, 0));
        for (int i = 0; i < 20; i++) t.step(0.5, ghost, null);
        for (int p : t.positions()) assertTrue(g.x(p) < 3);
    }

    @Test
    void bestTarget_onlyReturnsReachable() {
        GridNav g = TestGrids.grid(".....");
        ParticleTracker t = tracker(g, FREE, 5);
        int hidden = TestGrids.id(g, 4, 0);
        t.seed(hidden, 0);
        assertEquals(TestGrids.id(g, 3, 0), t.bestTarget(i -> true, i -> i != hidden));
    }

    @Test
    void bestTarget_skipsSeenCells() {
        GridNav g = TestGrids.grid(".........");
        ParticleTracker t = tracker(g, FREE, 5);
        t.seed(TestGrids.id(g, 6, 0), 0);
        int best = t.bestTarget(i -> g.x(i) < 5, i -> true); // x ≥ 5 都看過了：粒子旁邊 2 格內沒看過的格子
        assertEquals(TestGrids.id(g, 4, 0), best);
    }

    @Test
    void expires_afterMaxAge_orTooFew() {
        GridNav g = TestGrids.grid(".....");
        ParticleTracker t = tracker(g, FREE, 6);
        t.seed(TestGrids.id(g, 2, 0), 0);
        assertFalse(t.expired(100));
        assertTrue(t.expired(801));
        t.removeVisible(i -> true);
        assertTrue(t.expired(100));
    }

    @Test
    void shareWithin_countsParticles() {
        GridNav g = TestGrids.grid(".....");
        ParticleTracker t = tracker(g, FREE, 7);
        t.seed(TestGrids.id(g, 2, 0), 0);
        assertEquals(1.0, t.shareWithin(List.of(TestGrids.id(g, 2, 0))), 1e-9);
        assertEquals(0.0, t.shareWithin(List.of(TestGrids.id(g, 0, 0))), 1e-9);
    }

    @Test
    void sameSeed_sameResult() {
        String row = "..........";
        GridNav g = TestGrids.grid(row, row, row, row);
        DistanceField ghost = DistanceField.compute(g, FREE, 5.3, TestGrids.id(g, 0, 0));
        ParticleTracker a = tracker(g, FREE, 9), b = tracker(g, FREE, 9);
        a.seed(TestGrids.id(g, 5, 2), 0);
        b.seed(TestGrids.id(g, 5, 2), 0);
        for (int i = 0; i < 5; i++) {
            a.step(0.5, ghost, null);
            b.step(0.5, ghost, null);
        }
        assertArrayEquals(a.positions(), b.positions());
    }
}
