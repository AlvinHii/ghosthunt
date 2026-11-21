package com.ghosthunt.game.ai;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 割點與咽喉點（純邏輯）：在「區域圖」（房間、走廊、戶外區塊之間的相鄰關係）上算
 * <ul>
 *   <li>割點（Tarjan）：拿掉這一區，地圖就斷成兩半。</li>
 *   <li>介數中心性（Brandes，無權重）：這一區出現在「任兩區之間最短路」上的比例（0～1），很多人會經過的地方。</li>
 *   <li>死路：只有一個相鄰區（不算蹲伏小洞）。</li>
 * </ul>
 */
public final class Chokepoints {

    private final List<Set<Integer>> adj = new ArrayList<>();
    private final boolean[] articulation;
    private final double[] betweenness;

    /** n：區域數；edges：相鄰的兩區（同一對可以重複，例如兩區之間有兩個門）。 */
    public Chokepoints(int n, List<int[]> edges) {
        for (int i = 0; i < n; i++) adj.add(new LinkedHashSet<>());
        for (int[] e : edges) {
            if (e[0] == e[1] || e[0] < 0 || e[1] < 0 || e[0] >= n || e[1] >= n) continue;
            adj.get(e[0]).add(e[1]);
            adj.get(e[1]).add(e[0]);
        }
        articulation = tarjan(n);
        betweenness = brandes(n);
    }

    private boolean[] tarjan(int n) {
        int[] disc = new int[n], low = new int[n], parent = new int[n];
        Arrays.fill(disc, -1);
        Arrays.fill(parent, -1);
        boolean[] ap = new boolean[n];
        int[] time = {0};
        for (int s = 0; s < n; s++) {
            if (disc[s] >= 0) continue;
            // 迭代版 DFS（區域圖不大，但避免遞迴太深）
            ArrayDeque<int[]> st = new ArrayDeque<>();
            disc[s] = low[s] = time[0]++;
            int rootChildren = 0;
            st.push(new int[]{s, 0});
            List<List<Integer>> nb = new ArrayList<>();
            for (int i = 0; i < n; i++) nb.add(new ArrayList<>(adj.get(i)));
            while (!st.isEmpty()) {
                int[] top = st.peek();
                int u = top[0];
                if (top[1] < nb.get(u).size()) {
                    int v = nb.get(u).get(top[1]++);
                    if (disc[v] < 0) {
                        parent[v] = u;
                        if (u == s) rootChildren++;
                        disc[v] = low[v] = time[0]++;
                        st.push(new int[]{v, 0});
                    } else if (v != parent[u]) low[u] = Math.min(low[u], disc[v]);
                } else {
                    st.pop();
                    int p = parent[u];
                    if (p >= 0) {
                        low[p] = Math.min(low[p], low[u]);
                        if (p != s && low[u] >= disc[p]) ap[p] = true;
                    }
                }
            }
            if (rootChildren > 1) ap[s] = true;
        }
        return ap;
    }

    private double[] brandes(int n) {
        double[] cb = new double[n];
        for (int s = 0; s < n; s++) {
            ArrayDeque<Integer> stack = new ArrayDeque<>();
            List<List<Integer>> pred = new ArrayList<>();
            for (int i = 0; i < n; i++) pred.add(new ArrayList<>());
            double[] sigma = new double[n];
            int[] dist = new int[n];
            Arrays.fill(dist, -1);
            sigma[s] = 1;
            dist[s] = 0;
            ArrayDeque<Integer> q = new ArrayDeque<>();
            q.add(s);
            while (!q.isEmpty()) {
                int v = q.poll();
                stack.push(v);
                for (int w : adj.get(v)) {
                    if (dist[w] < 0) {
                        dist[w] = dist[v] + 1;
                        q.add(w);
                    }
                    if (dist[w] == dist[v] + 1) {
                        sigma[w] += sigma[v];
                        pred.get(w).add(v);
                    }
                }
            }
            double[] delta = new double[n];
            while (!stack.isEmpty()) {
                int w = stack.pop();
                for (int v : pred.get(w)) delta[v] += sigma[v] / sigma[w] * (1 + delta[w]);
                if (w != s) cb[w] += delta[w];
            }
        }
        double max = 0;
        for (double v : cb) max = Math.max(max, v);
        if (max > 0) for (int i = 0; i < n; i++) cb[i] /= max;
        return cb;
    }

    public boolean isArticulation(int v) {
        return v >= 0 && v < articulation.length && articulation[v];
    }

    /** 0～1（最高的那區是 1）。 */
    public double betweenness(int v) {
        return v < 0 || v >= betweenness.length ? 0 : betweenness[v];
    }

    /** 有幾個不同的相鄰區。 */
    public int degree(int v) {
        return v < 0 || v >= adj.size() ? 0 : adj.get(v).size();
    }

    public boolean deadEnd(int v) {
        return degree(v) <= 1;
    }

    public Set<Integer> neighbors(int v) {
        return adj.get(v);
    }

    public int size() {
        return adj.size();
    }
}
