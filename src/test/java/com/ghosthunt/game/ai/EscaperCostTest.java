package com.ghosthunt.game.ai;

import com.ghosthunt.game.ai.GridNav.Kind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EscaperCostTest {

    private static GridNav.Edge edge(Kind k, int ref) {
        return new GridNav.Edge(1, 1, k, ref);
    }

    @Test
    void hole_isFree() {
        GridNav.Cost c = EscaperCost.of(i -> false, i -> false, i -> true, 6, 8);
        assertEquals(0, c.extra(edge(Kind.HOLE, 0)));
        assertEquals(0, c.extra(edge(Kind.WALK, 0)));
        assertEquals(0, c.extra(edge(Kind.STAIR, 0)));
    }

    @Test
    void barricaded_isBlocked() {
        GridNav.Cost c = EscaperCost.of(i -> false, i -> i == 3, i -> true, 6, 8);
        assertTrue(Double.isInfinite(c.extra(edge(Kind.BARRICADE, 3))));
    }

    @Test
    void unbarricaded_isFree() {
        GridNav.Cost c = EscaperCost.of(i -> false, i -> i == 3, i -> true, 6, 8);
        assertEquals(0, c.extra(edge(Kind.BARRICADE, 2)));
    }

    @Test
    void jammedDoor_costsHalfUnjamPerEdge() {
        GridNav.Cost c = EscaperCost.of(i -> i == 1, i -> false, i -> true, 6, 8);
        assertEquals(3, c.extra(edge(Kind.DOOR, 1)));
        assertEquals(0, c.extra(edge(Kind.DOOR, 0)));
    }

    @Test
    void hatch_costsHatchBlocks() {
        GridNav.Cost c = EscaperCost.of(i -> false, i -> false, i -> true, 6, 8);
        assertEquals(8, c.extra(edge(Kind.HATCH, 0)));
    }

    @Test
    void unknownHatch_isBlocked() {
        // 鬼不知道的地洞：推理時不讓粒子走（不然等於洩漏地圖知識）
        GridNav.Cost c = EscaperCost.of(i -> false, i -> false, i -> i == 2, 6, 8);
        assertTrue(Double.isInfinite(c.extra(edge(Kind.HATCH, 0))));
        assertEquals(8, c.extra(edge(Kind.HATCH, 2)));
    }
}
