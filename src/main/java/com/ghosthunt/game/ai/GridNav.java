package com.ghosthunt.game.ai;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.function.BiPredicate;

/**
 * AI 鬼自己的尋路圖（純邏輯）：節點是「站得住的格子」，邊是相鄰格子之間怎麼過去。
 * 原版尋路假設怪物能跳上 1 格，靠 API 關不掉；長路線也會超過它的搜尋上限。
 * 所以由插件在這張圖上跑 A*，原版尋路只負責走到下一個幾格遠的路點。
 * <p>
 * 有些邊「過得去但要花時間」（卡住的門、障礙物、蹲伏小洞、地洞），額外成本由 {@link Cost} 依當下狀態決定
 * （例如沒卡住的門成本 0、卡住的門 2.5 秒 × 速度；不能用時回傳正無窮大）。
 */
public final class GridNav {

    public enum Kind { WALK, STAIR, DOOR, BARRICADE, HOLE, HATCH }

    /** 一條邊：to 是另一端的節點，length 是走過去的距離（格），ref 是那個東西的編號（障礙物、洞、地洞……）。 */
    public record Edge(int to, double length, Kind kind, int ref) {}

    /**
     * 依當下狀態決定邊的額外成本（格）；正無窮大表示不能用。
     * 特殊格子（門口的障礙物、洞……）穿過去會經過進、出兩條邊，所以每條邊回傳一半的成本。
     */
    public interface Cost {
        double extra(Edge e);
    }

    private final Map<Long, Integer> index = new HashMap<>();
    private int[] xs = new int[1024], ys = new int[1024], zs = new int[1024];
    private final List<List<Edge>> edges = new ArrayList<>();
    private int n;

    private static long key(int x, int y, int z) {
        return ((long) (x + 1_000_000) << 42) | ((long) (y + 2048) << 30) | (z + 1_000_000L);
    }

    /** 加一個節點（已經有就回傳原本的編號）。 */
    public int add(int x, int y, int z) {
        Integer have = index.get(key(x, y, z));
        if (have != null) return have;
        if (n == xs.length) {
            xs = Arrays.copyOf(xs, n * 2);
            ys = Arrays.copyOf(ys, n * 2);
            zs = Arrays.copyOf(zs, n * 2);
        }
        xs[n] = x;
        ys[n] = y;
        zs[n] = z;
        edges.add(new ArrayList<>(4));
        index.put(key(x, y, z), n);
        return n++;
    }

    /** 節點編號，沒有回傳 -1。 */
    public int id(int x, int y, int z) {
        Integer have = index.get(key(x, y, z));
        return have == null ? -1 : have;
    }

    public int size() {
        return n;
    }

    public int x(int id) {
        return xs[id];
    }

    public int y(int id) {
        return ys[id];
    }

    public int z(int id) {
        return zs[id];
    }

    /** 雙向連起來。 */
    public void link(int a, int b, double length, Kind kind, int ref) {
        if (a < 0 || b < 0 || a == b) return;
        for (Edge e : edges.get(a)) if (e.to() == b) return;
        edges.get(a).add(new Edge(b, length, kind, ref));
        edges.get(b).add(new Edge(a, length, kind, ref));
    }

    public List<Edge> edges(int id) {
        return edges.get(id);
    }

    /** a 到 b 那條邊（沒有回傳 null）。 */
    public Edge edge(int a, int b) {
        for (Edge e : edges.get(a)) if (e.to() == b) return e;
        return null;
    }

    /** 離 (x, y, z) 最近、而且在 maxDist 格內的節點（高度差算 3 倍，免得抓到別層）；沒有回傳 -1。 */
    public int nearest(double x, double y, double z, double maxDist) {
        int best = -1;
        double bd = maxDist * maxDist;
        for (int i = 0; i < n; i++) {
            double dx = xs[i] + 0.5 - x, dz = zs[i] + 0.5 - z, dy = (ys[i] - y) * 3;
            double d = dx * dx + dy * dy + dz * dz;
            if (d <= bd) {
                bd = d;
                best = i;
            }
        }
        return best;
    }

    private double h(int a, int b) {
        double dx = xs[a] - xs[b], dy = ys[a] - ys[b], dz = zs[a] - zs[b];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** A*：from 到 to 的節點清單（含頭尾）；走不到回傳空清單。 */
    public List<Integer> path(int from, int to, Cost cost) {
        if (from < 0 || to < 0) return List.of();
        if (from == to) return List.of(from);
        double[] g = new double[n];
        Arrays.fill(g, Double.POSITIVE_INFINITY);
        int[] prev = new int[n];
        Arrays.fill(prev, -1);
        boolean[] closed = new boolean[n];
        PriorityQueue<double[]> open = new PriorityQueue<>((p, q) -> Double.compare(p[0], q[0]));
        g[from] = 0;
        open.add(new double[]{h(from, to), from});
        while (!open.isEmpty()) {
            int cur = (int) open.poll()[1];
            if (closed[cur]) continue;
            if (cur == to) break;
            closed[cur] = true;
            for (Edge e : edges.get(cur)) {
                if (closed[e.to()]) continue;
                double extra = cost.extra(e);
                if (Double.isInfinite(extra)) continue;
                double ng = g[cur] + e.length() + extra;
                if (ng < g[e.to()]) {
                    g[e.to()] = ng;
                    prev[e.to()] = cur;
                    open.add(new double[]{ng + h(e.to(), to), e.to()});
                }
            }
        }
        if (prev[to] < 0) return List.of();
        List<Integer> out = new ArrayList<>();
        for (int v = to; v != -1; v = prev[v]) out.add(v);
        Collections.reverse(out);
        return out;
    }

    /**
     * 拉直路線：同一高度、兩點之間 clear 回傳 true（直線上都能走）時跳過中間的點。
     * 不是 WALK 的邊（樓梯、門、障礙物、洞、地洞）兩端一定保留，執行時才知道要在哪裡停下來處理。
     */
    public List<Integer> simplify(List<Integer> path, BiPredicate<Integer, Integer> clear) {
        if (path.size() <= 2) return path;
        List<Integer> out = new ArrayList<>();
        out.add(path.get(0));
        int anchor = 0;
        for (int i = 1; i < path.size(); i++) {
            int a = path.get(i - 1), b = path.get(i);
            Edge e = edge(a, b);
            boolean special = e == null || e.kind() != Kind.WALK;
            if (special) {
                if (out.get(out.size() - 1) != a) out.add(a);
                out.add(b);
                anchor = i;
                continue;
            }
            int from = path.get(anchor);
            boolean ok = ys[from] == ys[b] && clear.test(from, b);
            if (!ok) {
                if (out.get(out.size() - 1) != a) out.add(a);
                anchor = i - 1;
            }
        }
        int last = path.get(path.size() - 1);
        if (out.get(out.size() - 1) != last) out.add(last);
        return out;
    }
}
