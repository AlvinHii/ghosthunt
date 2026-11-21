package com.ghosthunt.game.ai;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntPredicate;

/**
 * 可見性表（純邏輯）：尋路圖上「這格看得到那格嗎」的粗略答案，事先算好存起來（粒子濾波、痕跡、守望點要問很多次）。
 * <ul>
 *   <li>只存同一層（同一個 y）、水平距離 ≤ maxRange 的格子對；其他回答「不知道」（{@link #known} 是 false）。</li>
 *   <li>射線是格子中心到格子中心，經過的每一格（supercover）只要有一格不透明就擋住；剛好穿過角落時兩側都不透明才擋。</li>
 *   <li>門不算擋，但記下每一對經過哪些門，查的時候門關著就看不到（門會開關，不用重建）。</li>
 * </ul>
 * 「鬼看到人」照舊用真的方塊射線，這個表只拿來推理。
 */
public final class VisibilityTable {

    /** 建表要的資料（主執行緒拍好的快照，建表可以在別的執行緒）。opaque／doorAt 問的是 (x, 節點的 y, z) 那一格。 */
    public interface Grid {
        int size();

        int x(int id);

        int y(int id);

        int z(int id);

        boolean opaque(int x, int y, int z);

        /** 這格是門的話回傳門的編號，否則 -1。 */
        int doorAt(int x, int y, int z);

        /** 這格是障礙物位置（門口，放了障礙物或推倒的櫃子會擋住）的話回傳它的編號，否則 -1。 */
        default int barricadeAt(int x, int y, int z) {
            return -1;
        }
    }

    private final int[] xs, ys, zs;
    private final BitSet[] bits;
    private final Map<Long, int[]> doorsOnPair = new HashMap<>(), barricadesOnPair = new HashMap<>();
    private final double range;

    private VisibilityTable(int n, double range) {
        xs = new int[n];
        ys = new int[n];
        zs = new int[n];
        bits = new BitSet[n];
        this.range = range;
    }

    public static VisibilityTable build(Grid g, double maxRange) {
        int n = g.size();
        VisibilityTable t = new VisibilityTable(n, maxRange);
        Map<Integer, List<Integer>> byY = new HashMap<>();
        for (int i = 0; i < n; i++) {
            t.xs[i] = g.x(i);
            t.ys[i] = g.y(i);
            t.zs[i] = g.z(i);
            t.bits[i] = new BitSet();
            byY.computeIfAbsent(t.ys[i], k -> new ArrayList<>()).add(i);
        }
        int r = (int) Math.ceil(maxRange);
        Set<Integer> doors = new LinkedHashSet<>(), barricades = new LinkedHashSet<>();
        for (List<Integer> layer : byY.values()) {
            // 同一層的格子：用 x 排序，只比較 x 差 ≤ r 的
            layer.sort((p, q) -> Integer.compare(t.xs[p], t.xs[q]));
            for (int ia = 0; ia < layer.size(); ia++) {
                int a = layer.get(ia);
                t.bits[a].set(a);
                for (int ib = ia + 1; ib < layer.size(); ib++) {
                    int b = layer.get(ib);
                    if (t.xs[b] - t.xs[a] > r) break;
                    int dx = t.xs[b] - t.xs[a], dz = t.zs[b] - t.zs[a];
                    if (dx * dx + dz * dz > maxRange * maxRange) continue;
                    doors.clear();
                    barricades.clear();
                    if (!t.cast(g, a, b, doors, barricades)) continue;
                    t.bits[a].set(b);
                    t.bits[b].set(a);
                    if (!doors.isEmpty()) t.doorsOnPair.put(t.pairKey(a, b), doors.stream().mapToInt(Integer::intValue).toArray());
                    if (!barricades.isEmpty()) t.barricadesOnPair.put(t.pairKey(a, b), barricades.stream().mapToInt(Integer::intValue).toArray());
                }
            }
        }
        return t;
    }

    /** supercover 射線：沒被擋住回傳 true，路上的門加進 doors。起點、終點那兩格不算。 */
    private boolean cast(Grid g, int a, int b, Set<Integer> doors, Set<Integer> barricades) {
        int y = ys[a];
        int x0 = xs[a], z0 = zs[a], x1 = xs[b], z1 = zs[b];
        int dx = Math.abs(x1 - x0), dz = Math.abs(z1 - z0);
        int sx = Integer.signum(x1 - x0), sz = Integer.signum(z1 - z0);
        int x = x0, z = z0;
        int n = 1 + dx + dz, err = dx - dz;
        dx *= 2;
        dz *= 2;
        for (; n > 0; n--) {
            if (!(x == x0 && z == z0) && !(x == x1 && z == z1)) {
                if (g.opaque(x, y, z)) return false;
                int d = g.doorAt(x, y, z);
                if (d >= 0) doors.add(d);
                int bi = g.barricadeAt(x, y, z);
                if (bi >= 0) barricades.add(bi);
            }
            if (err > 0) {
                x += sx;
                err -= dz;
            } else if (err < 0) {
                z += sz;
                err += dx;
            } else {
                // 正好穿過角落：兩側都不透明才擋住
                int ax = x + sx, bz = z + sz;
                boolean sideA = !(ax == x1 && z == z1) && g.opaque(ax, y, z);
                boolean sideB = !(x == x1 && bz == z1) && g.opaque(x, y, bz);
                if (sideA && sideB) return false;
                x += sx;
                z += sz;
                err += dx - dz;
                n--;
            }
        }
        return true;
    }

    private long pairKey(int a, int b) {
        int lo = Math.min(a, b), hi = Math.max(a, b);
        return (long) lo * xs.length + hi;
    }

    /** 這一對在表裡（同一層、在範圍內）。 */
    public boolean known(int a, int b) {
        if (a < 0 || b < 0 || a >= xs.length || b >= xs.length || ys[a] != ys[b]) return false;
        double dx = xs[a] - xs[b], dz = zs[a] - zs[b];
        return dx * dx + dz * dz <= range * range;
    }

    /** 看得到：表裡有、路上的門都沒關、路上的障礙物位置都沒放東西。 */
    public boolean visible(int a, int b, IntPredicate doorClosed, IntPredicate barricaded) {
        if (!visible(a, b, doorClosed)) return false;
        int[] bs = a == b ? null : barricadesOnPair.get(pairKey(a, b));
        if (bs != null) for (int x : bs) if (barricaded.test(x)) return false;
        return true;
    }

    /** 看得到：表裡有、而且路上的門都沒關。不在表裡（不同層、太遠）一律 false。 */
    public boolean visible(int a, int b, IntPredicate doorClosed) {
        if (a == b && a >= 0 && a < xs.length) return true;
        if (!known(a, b) || !bits[a].get(b)) return false;
        int[] ds = doorsOnPair.get(pairKey(a, b));
        if (ds != null) for (int d : ds) if (doorClosed.test(d)) return false;
        return true;
    }

    /** 大約佔多少記憶體（位元組，除錯顯示用）。 */
    public long approxBytes() {
        long sum = 0;
        for (BitSet b : bits) sum += b.size() / 8;
        return sum + doorsOnPair.size() * 48L;
    }
}
