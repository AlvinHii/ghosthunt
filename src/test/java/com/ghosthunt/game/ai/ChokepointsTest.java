package com.ghosthunt.game.ai;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChokepointsTest {

    @Test
    void dumbbell_middleIsArticulation_endsAreDeadEnds() {
        // 0 - 1 - 2：中間那區拿掉就切成兩半
        Chokepoints c = new Chokepoints(3, List.of(new int[]{0, 1}, new int[]{1, 2}));
        assertTrue(c.isArticulation(1));
        assertFalse(c.isArticulation(0));
        assertTrue(c.deadEnd(0));
        assertTrue(c.deadEnd(2));
        assertFalse(c.deadEnd(1));
    }

    @Test
    void cycle_hasNoArticulation() {
        Chokepoints c = new Chokepoints(4, List.of(new int[]{0, 1}, new int[]{1, 2}, new int[]{2, 3}, new int[]{3, 0}));
        for (int i = 0; i < 4; i++) assertFalse(c.isArticulation(i));
    }

    @Test
    void star_centerHasHighestBetweenness() {
        Chokepoints c = new Chokepoints(5, List.of(new int[]{0, 1}, new int[]{0, 2}, new int[]{0, 3}, new int[]{0, 4}));
        for (int i = 1; i < 5; i++) assertTrue(c.betweenness(0) > c.betweenness(i));
        assertEquals(1.0, c.betweenness(0), 1e-9); // 正規化到 0～1
    }

    @Test
    void duplicateEdges_countOnce() {
        Chokepoints c = new Chokepoints(2, List.of(new int[]{0, 1}, new int[]{0, 1}));
        assertEquals(1, c.degree(0));
    }

    // ---- 最小割（第 12 項第三點） ----

    private static boolean hasEdge(List<int[]> cut, int a, int b) {
        for (int[] e : cut) if ((e[0] == a && e[1] == b) || (e[0] == b && e[1] == a)) return true;
        return false;
    }

    @Test
    void minCut_dumbbell_isTheCorridor() {
        // 0 - 1 - 2 - 3 - 4，目標 4、離它 3 區以上的當「其他地方」：切 3-4 一條就夠
        Chokepoints c = new Chokepoints(5, List.of(new int[]{0, 1}, new int[]{1, 2}, new int[]{2, 3}, new int[]{3, 4}));
        List<int[]> cut = c.minCut(4, 3);
        assertEquals(1, cut.size());
        assertTrue(hasEdge(cut, 3, 4));
    }

    @Test
    void minCut_twoRoutes_needsTwo() {
        // 0 分兩條路（0-1-3、0-2-3）到 3，3 後面接目標 4：只守 3-4 一扇就夠；目標換成 3 要守兩扇
        Chokepoints c = new Chokepoints(5, List.of(new int[]{0, 1}, new int[]{0, 2}, new int[]{1, 3}, new int[]{2, 3}, new int[]{3, 4}));
        assertEquals(1, c.minCut(4, 2).size());
        List<int[]> cut = c.minCut(3, 2);
        assertEquals(2, cut.size());
        assertTrue(hasEdge(cut, 1, 3) && hasEdge(cut, 2, 3));
    }

    @Test
    void minCut_noFarRegion_isEmpty() {
        Chokepoints c = new Chokepoints(2, List.of(new int[]{0, 1}));
        assertTrue(c.minCut(1, 3).isEmpty());
    }
}
