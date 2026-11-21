package com.ghosthunt.game.ai;

import java.util.Arrays;
import java.util.PriorityQueue;

/**
 * 距離場（Dijkstra map，純邏輯）：從一個或多個起點出發，走到圖上每一格要幾秒。
 * 邊的成本跟 A* 一樣：（邊長度 ＋ {@link GridNav.Cost#extra}）÷ 每秒幾格；成本是無窮大的邊不走。
 * 用來回答「誰先到哪一格」「往離鬼遠的方向跑」這種 A* 不好問的問題。鬼實際走路照舊用 A*。
 */
public final class DistanceField {

    private final GridNav nav;
    private final GridNav.Cost cost;
    private final double[] sec;

    private DistanceField(GridNav nav, GridNav.Cost cost, double[] sec) {
        this.nav = nav;
        this.cost = cost;
        this.sec = sec;
    }

    public static DistanceField compute(GridNav nav, GridNav.Cost cost, double blocksPerSecond, int... sources) {
        int n = nav.size();
        double[] d = new double[n];
        Arrays.fill(d, Double.POSITIVE_INFINITY);
        PriorityQueue<double[]> open = new PriorityQueue<>((p, q) -> Double.compare(p[0], q[0]));
        for (int s : sources) {
            if (s < 0 || s >= n) continue;
            d[s] = 0;
            open.add(new double[]{0, s});
        }
        while (!open.isEmpty()) {
            double[] top = open.poll();
            int cur = (int) top[1];
            if (top[0] > d[cur]) continue;
            for (GridNav.Edge e : nav.edges(cur)) {
                double extra = cost.extra(e);
                if (Double.isInfinite(extra)) continue;
                double nd = d[cur] + (e.length() + extra) / blocksPerSecond;
                if (nd < d[e.to()]) {
                    d[e.to()] = nd;
                    open.add(new double[]{nd, e.to()});
                }
            }
        }
        return new DistanceField(nav, cost, d);
    }

    /** 走到這格要幾秒；走不到（或編號不對）是正無窮大。 */
    public double seconds(int node) {
        return node < 0 || node >= sec.length ? Double.POSITIVE_INFINITY : sec[node];
    }

    /** 往起點走的下一格：值比自己小、而且邊能走的鄰格裡最小的那個；沒有回傳 -1。 */
    public int downhill(int node) {
        if (node < 0 || node >= sec.length) return -1;
        int best = -1;
        double bv = sec[node];
        for (GridNav.Edge e : nav.edges(node)) {
            if (Double.isInfinite(cost.extra(e))) continue;
            if (sec[e.to()] < bv) {
                bv = sec[e.to()];
                best = e.to();
            }
        }
        return best;
    }

    public int size() {
        return sec.length;
    }
}
