package com.ghosthunt.sim;

import com.ghosthunt.game.ai.GridNav;
import com.ghosthunt.game.ai.GridNav.Kind;
import com.ghosthunt.game.ai.VisibilityTable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;

/**
 * 模擬器用的地圖：讀 pve-maps/*.txt 的 [1F]（這一版只模擬 1F），建成 GridNav。
 * 可走：. , ; 標記（S E）、數字（地洞，兩個同數字用 HATCH 邊連）、門 D W O（當作開著）。
 * h：蹲伏小洞（HOLE 邊，只有逃生者能走）。H：暗格入口（HOLE 邊），後面那格是暗格（也是 HOLE 邊）。
 * 牆、樹籬、家具、櫃子 C、終端機 Q、大門 G、鐵欄杆門 I、監獄 J、鬼房 K、樓梯箭頭不可走。
 * 不透明：牆、樹籬、家具（全部當成 2 格高）、C、Q、G。
 */
final class SimMap {

    private final String[] rows;
    private final int w, h;
    private final GridNav nav = new GridNav();
    private final List<Integer> hideouts = new ArrayList<>();
    private boolean[] main = new boolean[0];
    private int spawn = -1, ghostStart = -1;
    private VisibilityTable vis;

    private SimMap(String[] rows) {
        this.rows = rows;
        this.h = rows.length;
        int mw = 0;
        for (String r : rows) mw = Math.max(mw, r.length());
        this.w = mw;
    }

    static SimMap load(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        List<String> grid = new ArrayList<>();
        boolean in = false;
        for (String l : lines) {
            String st = l.strip();
            if (st.startsWith("[") && st.endsWith("]")) {
                if (in) break;
                in = st.equalsIgnoreCase("[1F]");
                continue;
            }
            if (in) grid.add(l.replace("\r", ""));
        }
        while (!grid.isEmpty() && grid.get(grid.size() - 1).isBlank()) grid.remove(grid.size() - 1);
        SimMap m = new SimMap(grid.toArray(new String[0]));
        m.build();
        return m;
    }

    char at(int x, int z) {
        return z < 0 || z >= h || x < 0 || x >= rows[z].length() ? ' ' : rows[z].charAt(x);
    }

    private static boolean furniture(char c) {
        return Character.isLowerCase(c) && c != 'h' && c != 'v';
    }

    private static boolean walkable(char c) {
        return c == '.' || c == ',' || c == ';' || c == 'S' || c == 'E' || c == 'D' || c == 'W' || c == 'O'
                || c == 'h' || c == 'H' || Character.isDigit(c);
    }

    boolean opaque(int x, int z) {
        char c = at(x, z);
        return c == '#' || c == '%' || c == 'C' || c == 'Q' || c == 'G' || furniture(c);
    }

    private void build() {
        Map<Character, List<Integer>> hatch = new HashMap<>();
        for (int z = 0; z < h; z++)
            for (int x = 0; x < w; x++) {
                char c = at(x, z);
                if (!walkable(c)) continue;
                int id = nav.add(x, 65, z);
                if (c == 'S' && spawn < 0) spawn = id;
                if (Character.isDigit(c)) hatch.computeIfAbsent(c, k -> new ArrayList<>()).add(id);
            }
        for (int z = 0; z < h; z++)
            for (int x = 0; x < w; x++) {
                int a = nav.id(x, 65, z);
                if (a < 0) continue;
                for (int[] d : new int[][]{{1, 0}, {0, 1}}) {
                    int b = nav.id(x + d[0], 65, z + d[1]);
                    if (b < 0) continue;
                    char ca = at(x, z), cb = at(x + d[0], z + d[1]);
                    Kind k = ca == 'h' || cb == 'h' || ca == 'H' || cb == 'H' ? Kind.HOLE
                            : ca == 'D' || cb == 'D' || ca == 'W' || cb == 'W' ? Kind.DOOR : Kind.WALK;
                    nav.link(a, b, 1, k, 0);
                }
            }
        // 暗格：H 背對地面那一格是躲藏空間
        for (int z = 0; z < h; z++)
            for (int x = 0; x < w; x++) {
                if (at(x, z) != 'H') continue;
                int[] side = null;
                for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}})
                    if (walkable(at(x + d[0], z + d[1])) && at(x + d[0], z + d[1]) != 'H') side = d;
                if (side == null) continue;
                int px = x - side[0], pz = z - side[1];
                int pocket = nav.add(px, 65, pz);
                nav.link(nav.id(x, 65, z), pocket, 1, Kind.HOLE, 0);
                hideouts.add(pocket);
            }
        for (List<Integer> pair : hatch.values()) if (pair.size() == 2) nav.link(pair.get(0), pair.get(1), 4, Kind.HATCH, 0);
        // 鬼的起點：鬼房鐵欄杆門 I 外面那一格
        for (int z = 0; z < h && ghostStart < 0; z++)
            for (int x = 0; x < w && ghostStart < 0; x++) {
                if (at(x, z) != 'I') continue;
                for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    int id = nav.id(x + d[0], 65, z + d[1]);
                    if (id >= 0) ghostStart = id;
                }
            }
        // 主要區域：從出生點、不經過小洞和地洞走得到
        main = new boolean[nav.size()];
        if (spawn >= 0) {
            ArrayDeque<Integer> q = new ArrayDeque<>();
            q.add(spawn);
            main[spawn] = true;
            while (!q.isEmpty()) {
                int v = q.poll();
                for (GridNav.Edge e : nav.edges(v)) {
                    if (e.kind() == Kind.HOLE || e.kind() == Kind.HATCH || main[e.to()]) continue;
                    main[e.to()] = true;
                    q.add(e.to());
                }
            }
        }
        vis = VisibilityTable.build(new VisibilityTable.Grid() {
            public int size() { return nav.size(); }
            public int x(int id) { return nav.x(id); }
            public int y(int id) { return nav.y(id); }
            public int z(int id) { return nav.z(id); }
            public boolean opaque(int x, int y, int z) { return SimMap.this.opaque(x, z); }
            public int doorAt(int x, int y, int z) { return -1; }
        }, 30);
    }

    GridNav nav() {
        return nav;
    }

    VisibilityTable vis() {
        return vis;
    }

    IntPredicate ghostReachable() {
        return i -> i >= 0 && i < main.length && main[i];
    }

    List<Integer> hideoutNodes() {
        return hideouts;
    }

    int spawn() {
        return spawn;
    }

    int ghostStart() {
        return ghostStart;
    }
}
