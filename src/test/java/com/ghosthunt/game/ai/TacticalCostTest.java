package com.ghosthunt.game.ai;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 第三版第 4 項：戰術尋路（對方看得到的格子多算距離）。 */
class TacticalCostTest {

    private static final GridNav.Cost FREE = e -> 0;

    @Test
    void avoidsCellsTheObserverCanSee() {
        // 從左下到右下：直走第 4 列（短，但第 4 列都被看到）或繞第 2 列（長 4 格，看不到）
        GridNav g = TestGrids.grid(
                "#########",
                "#.......#",
                "#.#####.#",
                "#.......#",
                "#########");
        int from = TestGrids.id(g, 1, 3), to = TestGrids.id(g, 7, 3);
        List<Integer> plain = g.path(from, to, FREE);
        for (int v : plain) assertEquals(3, g.z(v)); // 沒有懲罰：走第 3 列
        GridNav.Cost sneaky = TacticalCost.wrap(FREE, v -> g.z(v) == 3 && g.x(v) > 1 && g.x(v) < 7, 3);
        List<Integer> p = g.path(from, to, sneaky);
        assertTrue(p.stream().anyMatch(v -> g.z(v) == 1), "應該繞上面那條");
    }

    @Test
    void impassableStaysImpassable() {
        GridNav.Cost base = e -> Double.POSITIVE_INFINITY;
        GridNav g = TestGrids.grid("...");
        GridNav.Cost c = TacticalCost.wrap(base, v -> true, 3);
        assertTrue(Double.isInfinite(c.extra(g.edges(0).get(0))));
    }
}
