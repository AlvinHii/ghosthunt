package com.ghosthunt.game.ai;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;

/**
 * 聲音沿路傳播（純邏輯，《神偷》系列的做法）：聲音不穿牆，沿著可走的格子傳；關著的門、小洞要多算距離（penalty）。
 * 聽到的位置不是「真實位置 ± 誤差」，而是「聲音從哪個方向、哪個門口傳過來」：路徑上離聽的人 towardDist 格的那一格。
 */
public final class SoundField {

    private SoundField() {}

    /** distance：傳過來的距離（格）；pointNode：聽起來聲音從哪裡來（走過去自然會走進聲音的房間）。 */
    public record Heard(double distance, int pointNode) {}

    /** 傳不到或超過 maxDist 回傳 null。penalty 是每條邊額外的距離（例如關著的門每條邊 +2，穿過去 +4）。 */
    public static Heard hear(GridNav nav, int source, int listener, double maxDist, GridNav.Cost penalty, double towardDist) {
        if (source < 0 || listener < 0) return null;
        if (source == listener) return new Heard(0, source);
        int n = nav.size();
        double[] d = new double[n];
        Arrays.fill(d, Double.POSITIVE_INFINITY);
        int[] prev = new int[n];
        Arrays.fill(prev, -1);
        PriorityQueue<double[]> open = new PriorityQueue<>((p, q) -> Double.compare(p[0], q[0]));
        d[listener] = 0;
        open.add(new double[]{0, listener});
        while (!open.isEmpty()) {
            double[] top = open.poll();
            int cur = (int) top[1];
            if (top[0] > d[cur]) continue;
            if (cur == source) break;
            for (GridNav.Edge e : nav.edges(cur)) {
                double extra = penalty.extra(e);
                if (Double.isInfinite(extra)) continue;
                double nd = d[cur] + e.length() + extra;
                if (nd > maxDist || nd >= d[e.to()]) continue;
                d[e.to()] = nd;
                prev[e.to()] = cur;
                open.add(new double[]{nd, e.to()});
            }
        }
        if (Double.isInfinite(d[source])) return null;
        // 路徑：聽的人 → 聲音；走 towardDist 格停下
        List<Integer> path = new ArrayList<>();
        for (int v = source; v != -1; v = prev[v]) path.add(v);
        Collections.reverse(path);
        int point = source;
        for (int v : path) {
            if (d[v] >= towardDist) {
                point = v;
                break;
            }
        }
        return new Heard(d[source], point);
    }
}
