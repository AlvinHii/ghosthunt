package com.ghosthunt.game.ai;

import com.ghosthunt.game.ai.GridNav.Kind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GridNavTest {

    /** 用字元圖建一層：. 可走、# 牆、B 障礙物（要拆）、H 蹲伏小洞。y 固定。 */
    private static GridNav grid(String... rows) {
        GridNav g = new GridNav();
        for (int z = 0; z < rows.length; z++)
            for (int x = 0; x < rows[z].length(); x++)
                if (rows[z].charAt(x) != '#') g.add(x, 65, z);
        for (int z = 0; z < rows.length; z++)
            for (int x = 0; x < rows[z].length(); x++) {
                char ch = rows[z].charAt(x);
                if (ch == '#') continue;
                int a = g.id(x, 65, z);
                for (int[] d : new int[][]{{1, 0}, {0, 1}}) {
                    int bx = x + d[0], bz = z + d[1];
                    if (bz >= rows.length || bx >= rows[bz].length() || rows[bz].charAt(bx) == '#') continue;
                    char other = rows[bz].charAt(bx);
                    Kind k = ch == 'B' || other == 'B' ? Kind.BARRICADE : ch == 'H' || other == 'H' ? Kind.HOLE : Kind.WALK;
                    g.link(a, g.id(bx, 65, bz), 1, k, 0);
                }
            }
        return g;
    }

    private static final GridNav.Cost FREE = e -> 0;

    @Test
    void goesAroundWall() {
        GridNav g = grid(
                ".....",
                ".###.",
                ".....");
        List<Integer> p = g.path(g.id(0, 65, 1), g.id(4, 65, 1), FREE);
        assertEquals(7, p.size()); // 繞上面或下面：6 步 7 個點
        assertEquals(g.id(4, 65, 1), p.get(p.size() - 1));
    }

    @Test
    void breaksBarricade_whenDetourIsLonger() {
        GridNav g = grid(
                "..........",
                ".########.",
                "....B.....",
                ".########.",
                "..........");
        // 直接拆：8 步＋拆的成本；繞上面：14 步。拆障礙物 3.5 秒 × 每秒 5 格 = 17.5 格 → 繞路比較快
        GridNav.Cost breakCost = e -> e.kind() == Kind.BARRICADE ? 17.5 / 2 : 0; // 穿過那一格會經過兩條邊，每條算一半
        List<Integer> around = g.path(g.id(0, 65, 2), g.id(8, 65, 2), breakCost);
        assertFalse(around.contains(g.id(4, 65, 2)));
        // 拆的成本只有 3 格 → 直接拆（8＋3 ＜ 14）
        GridNav.Cost cheap = e -> e.kind() == Kind.BARRICADE ? 3.0 / 2 : 0;
        assertTrue(g.path(g.id(0, 65, 2), g.id(8, 65, 2), cheap).contains(g.id(4, 65, 2)));
    }

    @Test
    void blockedEdge_isNeverUsed() {
        GridNav g = grid(
                "...H...",
                "###.###");
        GridNav.Cost noHoles = e -> e.kind() == Kind.HOLE ? Double.POSITIVE_INFINITY : 0;
        assertTrue(g.path(g.id(0, 65, 0), g.id(6, 65, 0), noHoles).isEmpty());
        assertFalse(g.path(g.id(0, 65, 0), g.id(6, 65, 0), FREE).isEmpty());
    }

    @Test
    void stairsConnectFloors() {
        GridNav g = new GridNav();
        int a = g.add(0, 65, 0), b = g.add(1, 65, 0), s1 = g.add(2, 66, 0), s2 = g.add(3, 67, 0), top = g.add(4, 70, 0), up = g.add(5, 70, 0);
        g.link(a, b, 1, Kind.WALK, 0);
        g.link(b, s1, 1.2, Kind.STAIR, 0);
        g.link(s1, s2, 1.2, Kind.STAIR, 0);
        g.link(s2, top, 3, Kind.STAIR, 0);
        g.link(top, up, 1, Kind.WALK, 0);
        assertEquals(List.of(a, b, s1, s2, top, up), g.path(a, up, FREE));
        assertEquals(List.of(up, top, s2, s1, b, a), g.path(up, a, FREE));
    }

    @Test
    void nearestNode_withinRange() {
        GridNav g = grid("...", "...");
        assertEquals(g.id(2, 65, 1), g.nearest(2.4, 65, 1.3, 2));
        assertEquals(-1, g.nearest(50, 65, 50, 2));
    }

    @Test
    void simplify_keepsKindChanges() {
        GridNav g = grid(".....B....");
        List<Integer> raw = g.path(g.id(0, 65, 0), g.id(9, 65, 0), FREE);
        // 直線上都看得到：只留起點、障礙物前後、終點
        List<Integer> s = g.simplify(raw, (x, y) -> true);
        assertEquals(g.id(0, 65, 0), s.get(0));
        assertTrue(s.contains(g.id(4, 65, 0)));
        assertTrue(s.contains(g.id(5, 65, 0)));
        assertEquals(g.id(9, 65, 0), s.get(s.size() - 1));
        assertTrue(s.size() <= 5);
    }
}
