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
}
