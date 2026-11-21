package com.ghosthunt.game.ai;

import com.ghosthunt.game.ai.GridNav.Kind;

import java.util.ArrayList;
import java.util.List;

/** 測試用：字元圖 → GridNav（一層，y 固定 65；節點座標 = (欄, 65, 列)）。 */
final class TestGrids {

    static final int Y = 65;

    private TestGrids() {}

    /**
     * . 可走、# 牆、B 障礙物（BARRICADE 邊）、H 蹲伏小洞（HOLE 邊）、D 門（DOOR 邊，ref 0）、
     * T 地洞出入口（跟旁邊照常相連，另外兩個 T 之間用 HATCH 邊相連，長度 4）。
     */
    static GridNav grid(String... rows) {
        GridNav g = new GridNav();
        List<Integer> hatches = new ArrayList<>();
        for (int z = 0; z < rows.length; z++)
            for (int x = 0; x < rows[z].length(); x++) {
                char ch = rows[z].charAt(x);
                if (ch == '#') continue;
                int id = g.add(x, Y, z);
                if (ch == 'T') hatches.add(id);
            }
        for (int z = 0; z < rows.length; z++)
            for (int x = 0; x < rows[z].length(); x++) {
                char ch = rows[z].charAt(x);
                if (ch == '#') continue;
                int a = g.id(x, Y, z);
                for (int[] d : new int[][]{{1, 0}, {0, 1}}) {
                    int bx = x + d[0], bz = z + d[1];
                    if (bz >= rows.length || bx >= rows[bz].length() || rows[bz].charAt(bx) == '#') continue;
                    char other = rows[bz].charAt(bx);
                    Kind k = ch == 'B' || other == 'B' ? Kind.BARRICADE
                            : ch == 'H' || other == 'H' ? Kind.HOLE
                            : ch == 'D' || other == 'D' ? Kind.DOOR : Kind.WALK;
                    g.link(a, g.id(bx, Y, bz), 1, k, 0);
                }
            }
        if (hatches.size() == 2) g.link(hatches.get(0), hatches.get(1), 4, Kind.HATCH, 0);
        return g;
    }

    static int id(GridNav g, int x, int z) {
        return g.id(x, Y, z);
    }

    /** 可見性表用：# 不透明、D 是門 0、其他（含圖外）透明。節點照 grid() 建的。 */
    static VisibilityTable.Grid visGrid(GridNav g, String... rows) {
        return new VisibilityTable.Grid() {
            public int size() { return g.size(); }
            public int x(int id) { return g.x(id); }
            public int y(int id) { return g.y(id); }
            public int z(int id) { return g.z(id); }
            public boolean opaque(int x, int y, int z) { return ch(x, z) == '#'; }
            public int doorAt(int x, int y, int z) { return ch(x, z) == 'D' ? 0 : -1; }

            private char ch(int x, int z) {
                return z < 0 || z >= rows.length || x < 0 || x >= rows[z].length() ? ' ' : rows[z].charAt(x);
            }
        };
    }
}
