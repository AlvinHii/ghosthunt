package com.ghosthunt.game.ai;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisibilityTableTest {

    private static final java.util.function.IntPredicate OPEN = i -> false, CLOSED = i -> true;

    private static VisibilityTable table(double range, String... rows) {
        return VisibilityTable.build(TestGrids.visGrid(TestGrids.grid(rows), rows), range);
    }

    @Test
    void openRoom_seesAcross() {
        String[] rows = {".....", ".....", ".....", ".....", "....."};
        GridNav g = TestGrids.grid(rows);
        VisibilityTable t = VisibilityTable.build(TestGrids.visGrid(g, rows), 30);
        assertTrue(t.visible(TestGrids.id(g, 0, 0), TestGrids.id(g, 4, 4), OPEN));
        assertTrue(t.visible(TestGrids.id(g, 0, 4), TestGrids.id(g, 4, 1), OPEN));
    }

    @Test
    void wall_blocks() {
        String[] rows = {"..#..", "....."};
        GridNav g = TestGrids.grid(rows);
        VisibilityTable t = VisibilityTable.build(TestGrids.visGrid(g, rows), 30);
        assertFalse(t.visible(TestGrids.id(g, 0, 0), TestGrids.id(g, 4, 0), OPEN));
        assertTrue(t.visible(TestGrids.id(g, 0, 1), TestGrids.id(g, 4, 1), OPEN));
    }

    @Test
    void cornerPinch_blocks() {
        String[] rows = {".#", "#."};
        GridNav g = TestGrids.grid(rows);
        VisibilityTable t = VisibilityTable.build(TestGrids.visGrid(g, rows), 30);
        assertFalse(t.visible(TestGrids.id(g, 0, 0), TestGrids.id(g, 1, 1), OPEN));
    }

    @Test
    void cornerOneSide_sees() {
        String[] rows = {".#", ".."};
        GridNav g = TestGrids.grid(rows);
        VisibilityTable t = VisibilityTable.build(TestGrids.visGrid(g, rows), 30);
        assertTrue(t.visible(TestGrids.id(g, 0, 0), TestGrids.id(g, 1, 1), OPEN));
    }

    @Test
    void closedDoor_blocks_openDoor_sees() {
        String[] rows = {"..D.."};
        GridNav g = TestGrids.grid(rows);
        VisibilityTable t = VisibilityTable.build(TestGrids.visGrid(g, rows), 30);
        int a = TestGrids.id(g, 0, 0), b = TestGrids.id(g, 4, 0);
        assertTrue(t.visible(a, b, OPEN));
        assertFalse(t.visible(a, b, CLOSED));
        // 門本身那一格看得到（站在門口）
        assertTrue(t.visible(a, TestGrids.id(g, 2, 0), CLOSED));
    }

    @Test
    void beyondRange_notKnown() {
        String row = ".".repeat(40);
        GridNav g = TestGrids.grid(row);
        VisibilityTable t = VisibilityTable.build(TestGrids.visGrid(g, row), 30);
        assertFalse(t.known(TestGrids.id(g, 0, 0), TestGrids.id(g, 35, 0)));
        assertFalse(t.visible(TestGrids.id(g, 0, 0), TestGrids.id(g, 35, 0), OPEN));
        assertTrue(t.known(TestGrids.id(g, 0, 0), TestGrids.id(g, 20, 0)));
        assertTrue(t.visible(TestGrids.id(g, 0, 0), TestGrids.id(g, 20, 0), OPEN));
    }

    @Test
    void differentFloors_notInTable() {
        GridNav g = new GridNav();
        int a = g.add(0, 65, 0), b = g.add(1, 70, 0);
        VisibilityTable.Grid grid = new VisibilityTable.Grid() {
            public int size() { return g.size(); }
            public int x(int id) { return g.x(id); }
            public int y(int id) { return g.y(id); }
            public int z(int id) { return g.z(id); }
            public boolean opaque(int x, int y, int z) { return false; }
            public int doorAt(int x, int y, int z) { return -1; }
        };
        VisibilityTable t = VisibilityTable.build(grid, 30);
        assertFalse(t.known(a, b));
        assertFalse(t.visible(a, b, OPEN));
    }

    @Test
    void symmetric() {
        String[] rows = {
                "..........",
                "..#....#..",
                "..#.D..#..",
                "....#.....",
                ".#.....##.",
                ".........."};
        GridNav g = TestGrids.grid(rows);
        VisibilityTable t = VisibilityTable.build(TestGrids.visGrid(g, rows), 30);
        Random r = new Random(3);
        for (int i = 0; i < 300; i++) {
            int a = r.nextInt(g.size()), b = r.nextInt(g.size());
            assertEquals(t.visible(a, b, OPEN), t.visible(b, a, OPEN), a + "↔" + b);
            assertEquals(t.visible(a, b, CLOSED), t.visible(b, a, CLOSED), a + "↔" + b);
        }
    }

    @Test
    void sameNode_isVisible() {
        String[] rows = {"..."};
        GridNav g = TestGrids.grid(rows);
        VisibilityTable t = VisibilityTable.build(TestGrids.visGrid(g, rows), 30);
        assertTrue(t.visible(TestGrids.id(g, 1, 0), TestGrids.id(g, 1, 0), OPEN));
    }
}
