package com.ghosthunt.game;

import com.ghosthunt.building.Building;
import com.ghosthunt.building.Layout;
import com.ghosthunt.building.Pos;
import com.ghosthunt.game.ai.GridNav;
import com.ghosthunt.game.ai.GridNav.Kind;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 從世界的方塊建出 AI 鬼的尋路圖（{@link GridNav}）。開局（鬼被放出來時）建一次。
 * <ul>
 *   <li>節點：站得住的格子。腳下要是「樓板」或樓梯／半磚，所以桌子、木桶、影印機、矮欄杆上面都不算（鬼不能走上家具）。</li>
 *   <li>邊：前後左右相鄰。上下差 1 格只有在樓梯上才行。門、障礙物、蹲伏小洞的格子是特殊邊；
 *       地下通道的出入口是一條從 1F 地板連到梯子底的邊。</li>
 *   <li>會變的東西（障礙物放上去、門被卡住、洞被打大、地洞被撬開）不用重建：邊的成本在找路時依當下狀態算。</li>
 * </ul>
 */
final class NavMap {

    final GridNav nav = new GridNav();
    /** 門（下半部）的位置，DOOR 邊的 ref 是這裡的編號。 */
    final List<Pos> doors = new ArrayList<>();
    /** 特殊格子（門、障礙物、洞、地洞出入口），拉直路線時不能跨過。 */
    final Set<Integer> special = new HashSet<>();
    private final Match m;

    private NavMap(Match m) {
        this.m = m;
    }

    static NavMap build(Match m) {
        NavMap nm = new NavMap(m);
        nm.scan();
        return nm;
    }

    private Block block(int x, int y, int z) {
        return m.world.getBlockAt(x, y, z);
    }

    private boolean isFloorLevel(int y) {
        Layout L = m.b.layout;
        if (y == L.floorY(0) - 4) return true; // 地下通道的地板
        for (int f = 0; f < L.floors; f++) if (y == L.floorY(f)) return true;
        return false;
    }

    /** 腳或頭的位置過得去（可以穿過的方塊、門、門口的障礙物、鬼房的鐵欄杆）。 */
    private boolean open(int x, int y, int z, Set<Pos> ghostDoors) {
        Block bl = block(x, y, z);
        if (bl.isPassable()) return true;
        Material t = bl.getType();
        if (Tag.DOORS.isTagged(t)) return true;
        Pos p = new Pos(x, y, z);
        return m.barricadeAt(p) >= 0 || ghostDoors.contains(p) || m.isBarricadeSpot(p);
    }

    private boolean standable(int x, int y, int z, Set<Pos> ghostDoors) {
        Block below = block(x, y - 1, z);
        Material bt = below.getType();
        // 只有大樓的石磚樓梯算樓梯（椅子、馬桶也是樓梯方塊，但那是家具，不能走上去）
        boolean ground = !below.isPassable() && (isFloorLevel(y - 1) || bt == Material.STONE_BRICK_STAIRS);
        if (!ground) return false;
        if (!open(x, y, z, ghostDoors)) return false;
        // 頭：可以穿過，或是蹲伏小洞（下面一格空、上面半磚）
        return open(x, y + 1, z, ghostDoors) || m.holeIndexAt(new Pos(x, y, z)) >= 0;
    }

    private void scan() {
        Building b = m.b;
        Layout L = b.layout;
        Set<Pos> ghostDoors = new HashSet<>(b.ghostDoors);
        int y0 = L.floorY(0) - 3, y1 = L.topY();
        for (int y = y0; y <= y1; y++)
            for (int x = L.ox; x < L.ox + L.size; x++)
                for (int z = L.oz; z < L.oz + L.size; z++)
                    if (standable(x, y, z, ghostDoors)) nav.add(x, y, z);
        int n = nav.size();
        for (int a = 0; a < n; a++) {
            int x = nav.x(a), y = nav.y(a), z = nav.z(a);
            for (int[] d : new int[][]{{1, 0}, {0, 1}}) {
                for (int dy = -1; dy <= 1; dy++) {
                    int c = nav.id(x + d[0], y + dy, z + d[1]);
                    if (c < 0) continue;
                    if (dy != 0) {
                        // 上下差 1 格：比較高的那格腳下要是樓梯
                        int hx = dy > 0 ? x + d[0] : x, hy = Math.max(y, y + dy), hz = dy > 0 ? z + d[1] : z;
                        Block stair = block(hx, hy - 1, hz);
                        if (stair.getType() != Material.STONE_BRICK_STAIRS) continue;
                        // 樓梯只能從低的那一側沿著它的方向走上去（從側面上不去）
                        int lowX = dy > 0 ? x : x + d[0], lowZ = dy > 0 ? z : z + d[1];
                        if (stair.getBlockData() instanceof org.bukkit.block.data.type.Stairs st
                                && (st.getFacing().getModX() != hx - lowX || st.getFacing().getModZ() != hz - lowZ)) continue;
                        // 頭頂（走上去時）要空：比較低那格的頭上兩格
                        int lx = dy > 0 ? x : x + d[0], ly = Math.min(y, y + dy), lz = dy > 0 ? z : z + d[1];
                        if (!open(lx, ly + 2, lz, ghostDoors)) continue;
                        nav.link(a, c, 1.3, Kind.STAIR, 0);
                        continue;
                    }
                    link(a, c);
                }
            }
        }
        computeMain(ghostDoors);
        // 地下通道的出入口：1F 地板（活板門上面那格）連到梯子底
        for (int i = 0; i < b.hatches.size(); i++) {
            Pos t = b.hatches.get(i).trapdoor();
            int top = nav.id(t.x(), t.y() + 1, t.z()), bottom = nav.id(t.x(), t.y() - 3, t.z());
            if (top >= 0 && bottom >= 0) {
                nav.link(top, bottom, 4, Kind.HATCH, i);
                special.add(top);
                special.add(bottom);
            }
        }
    }

    /**
     * 主要區域：從 1F 起點出發、不經過蹲伏小洞（和地洞，這時還沒連）走得到的格子。
     * 監獄籠子裡面、暗格裡面、地下通道都不算，找目標格子時只挑主要區域（不會把目標對到籠子裡、暗格裡）。
     */
    private boolean[] main = new boolean[0];

    private void computeMain(Set<Pos> ghostDoors) {
        main = new boolean[nav.size()];
        int start = nav.nearest(m.loc(m.b.layout.crossing(0)).getX(), m.b.layout.floorY(0) + 1, m.loc(m.b.layout.crossing(0)).getZ(), 3);
        if (start < 0) return;
        java.util.ArrayDeque<Integer> q = new java.util.ArrayDeque<>();
        q.add(start);
        main[start] = true;
        while (!q.isEmpty()) {
            int v = q.poll();
            for (GridNav.Edge e : nav.edges(v)) {
                if (e.kind() == Kind.HOLE || e.kind() == Kind.HATCH || main[e.to()]) continue;
                main[e.to()] = true;
                q.add(e.to());
            }
        }
    }

    boolean isMain(int id) {
        return id >= 0 && id < main.length && main[id];
    }

    /** 離 l 最近、maxDist 格內、而且在主要區域的格子；沒有回傳 -1。 */
    int nearestMain(Location l, double maxDist) {
        if (l == null) return -1;
        int best = -1;
        double bd = maxDist * maxDist;
        for (int i = 0; i < nav.size(); i++) {
            if (!main[i]) continue;
            double dx = nav.x(i) + 0.5 - l.getX(), dz = nav.z(i) + 0.5 - l.getZ(), dy = (nav.y(i) - l.getY()) * 3;
            double d = dx * dx + dy * dy + dz * dz;
            if (d <= bd) {
                bd = d;
                best = i;
            }
        }
        return best;
    }

    private void link(int a, int c) {
        Kind k = Kind.WALK;
        int ref = 0;
        for (int v : new int[]{a, c}) {
            Pos p = new Pos(nav.x(v), nav.y(v), nav.z(v));
            int spot = m.barricadeSpotAt(p);
            int hole = m.holeAt(p);
            if (Tag.DOORS.isTagged(block(p.x(), p.y(), p.z()).getType())) {
                Pos lower = p;
                k = Kind.DOOR;
                ref = doorRef(lower);
            } else if (spot >= 0) {
                k = Kind.BARRICADE;
                ref = spot;
            } else if (hole >= 0) {
                k = Kind.HOLE;
                ref = hole;
            } else continue;
            special.add(v);
        }
        nav.link(a, c, 1, k, ref);
    }

    private int doorRef(Pos lower) {
        int i = doors.indexOf(lower);
        if (i >= 0) return i;
        doors.add(lower);
        return doors.size() - 1;
    }

    /**
     * 可見性表要的快照（主執行緒呼叫）：每個節點高度那一層，眼睛那格（y+1）是不是不透明、是不是門。
     * 不透明＝會擋視線的實心方塊或樹葉；柵欄、鐵欄杆、玻璃、門算透明（門另外記）。回傳的物件不再碰世界，可以給別的執行緒建表。
     */
    com.ghosthunt.game.ai.VisibilityTable.Grid visibilityGrid() {
        Layout L = m.b.layout;
        int ox = L.ox, oz = L.oz, size = L.size;
        java.util.Map<Integer, boolean[]> opaque = new java.util.HashMap<>();
        for (int i = 0; i < nav.size(); i++) {
            int y = nav.y(i);
            opaque.computeIfAbsent(y, yy -> {
                boolean[] a = new boolean[size * size];
                for (int x = 0; x < size; x++)
                    for (int z = 0; z < size; z++) {
                        Material t = block(ox + x, yy + 1, oz + z).getType();
                        a[x * size + z] = t.isOccluding() || Tag.LEAVES.isTagged(t)
                                || (m.scene != null && m.scene.blocksSight(ox + x, yy + 1, oz + z)); // 3D 模型底下的屏障照原本的方塊算
                    }
                return a;
            });
        }
        java.util.Map<Pos, Integer> doorIdx = new java.util.HashMap<>();
        for (int i = 0; i < doors.size(); i++) doorIdx.put(doors.get(i), i);
        // 障礙物位置（門口）：放了障礙物或推倒的櫃子會擋視線，查的時候才知道有沒有放
        java.util.Map<Pos, Integer> spotIdx = new java.util.HashMap<>();
        for (int i = 0; i < nav.size(); i++) {
            Pos p = new Pos(nav.x(i), nav.y(i), nav.z(i));
            int spot = m.barricadeSpotAt(p);
            if (spot >= 0) spotIdx.put(p, spot);
        }
        int n = nav.size();
        int[] xs = new int[n], ys = new int[n], zs = new int[n];
        for (int i = 0; i < n; i++) {
            xs[i] = nav.x(i);
            ys[i] = nav.y(i);
            zs[i] = nav.z(i);
        }
        return new com.ghosthunt.game.ai.VisibilityTable.Grid() {
            public int size() { return n; }
            public int x(int id) { return xs[id]; }
            public int y(int id) { return ys[id]; }
            public int z(int id) { return zs[id]; }

            public boolean opaque(int x, int y, int z) {
                boolean[] a = opaque.get(y);
                int lx = x - ox, lz = z - oz;
                if (a == null || lx < 0 || lz < 0 || lx >= size || lz >= size) return true;
                return a[lx * size + lz];
            }

            public int doorAt(int x, int y, int z) {
                Integer d = doorIdx.get(new Pos(x, y, z));
                return d == null ? -1 : d;
            }

            public int barricadeAt(int x, int y, int z) {
                Integer b = spotIdx.get(new Pos(x, y, z));
                return b == null ? -1 : b;
            }
        };
    }

    // ---- 給 AI 用的小工具 ----

    Location loc(int id) {
        return new Location(m.world, nav.x(id) + 0.5, nav.y(id), nav.z(id) + 0.5);
    }

    int nearest(Location l, double maxDist) {
        return l == null ? -1 : nav.nearest(l.getX(), l.getY(), l.getZ(), maxDist);
    }

    /** 同一高度、兩個節點之間的直線（加上身體寬度）都是普通的可走格子。 */
    boolean clear(int a, int b) {
        int y = nav.y(a);
        double ax = nav.x(a) + 0.5, az = nav.z(a) + 0.5, bx = nav.x(b) + 0.5, bz = nav.z(b) + 0.5;
        double len = Math.hypot(bx - ax, bz - az);
        if (len < 0.01) return true;
        double px = -(bz - az) / len * 0.35, pz = (bx - ax) / len * 0.35;
        for (double t = 0; t <= len; t += 0.25) {
            double cx = ax + (bx - ax) * t / len, cz = az + (bz - az) * t / len;
            for (int s = -1; s <= 1; s++) {
                int id = nav.id((int) Math.floor(cx + px * s), y, (int) Math.floor(cz + pz * s));
                if (id < 0 || special.contains(id)) return false;
            }
        }
        return true;
    }
}
