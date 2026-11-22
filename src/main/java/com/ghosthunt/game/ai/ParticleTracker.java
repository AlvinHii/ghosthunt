package com.ghosthunt.game.ai;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntPredicate;
import java.util.random.RandomGenerator;

/**
 * 粒子濾波（純邏輯）：追丟一個逃生者時，猜「他現在可能在哪」。
 * <ol>
 *   <li>追丟的那一刻在最後看到的格子撒粒子（感應時在那一區平均撒）。</li>
 *   <li>每一步每顆粒子照逃生者跑速沿著邊走：fleeWeight 的機率往離鬼更遠的鄰格、goalWeight 往遊戲目標、其他隨機。
 *       用逃生者的邊成本，所以會鑽小洞、進暗格、下地洞，但過不了障礙物。</li>
 *   <li>鬼看得到的格子裡的粒子刪掉（{@link #removeVisible}）。</li>
 *   <li>聽到聲音、看到痕跡時，附近的粒子權重加倍再重新取樣（{@link #weightNear}）。</li>
 *   <li>搜尋目標＝粒子最密、鬼還沒看過、而且鬼走得到的格子（{@link #bestTarget}）。</li>
 * </ol>
 */
public final class ParticleTracker {

    /**
     * count：粒子數；blocksPerSecond：逃生者跑速；fleeWeight／goalWeight：往離鬼遠、往遊戲目標的機率（其他是隨機）；
     * soundSigma：聲音、痕跡的高斯半徑（格）；minAlive：剩不到幾顆就放棄；maxAgeTicks：最多追蹤多久。
     */
    public record Config(int count, double blocksPerSecond, double fleeWeight, double goalWeight, double soundSigma, int minAlive, long maxAgeTicks) {}

    private final GridNav nav;
    private final GridNav.Cost cost;
    private final Config cfg;
    private final RandomGenerator rnd;
    private int[] node = new int[0];
    private double[] weight = new double[0];
    /** 每顆粒子上一步超支的成本（走很貴的邊時，下一步要先扣掉）。 */
    private double[] debt = new double[0];
    private long seedTick;
    /** 隨機那幾步的偏好（格子 → 權重，預設全部 1）：以前常看到人走的路線、熱點。 */
    private Bias bias;

    /** 從 from 走到相鄰的 to 的偏好權重（1 = 平常）。 */
    public interface Bias {
        double weight(int from, int to);
    }

    /** 設定隨機步的偏好（null = 平均）。只影響隨機的那部分，不影響「往離鬼遠」「往目標」。 */
    public void setBias(Bias bias) {
        this.bias = bias;
    }

    public ParticleTracker(GridNav nav, GridNav.Cost escaperCost, Config cfg, RandomGenerator rnd) {
        this.nav = nav;
        this.cost = escaperCost;
        this.cfg = cfg;
        this.rnd = rnd;
    }

    /** 全部粒子撒在同一格（node < 0 或不在圖上：追蹤器保持空的）。 */
    public void seed(int at, long tick) {
        seedTick = tick;
        if (at < 0 || at >= nav.size()) {
            node = new int[0];
            weight = new double[0];
            debt = new double[0];
            return;
        }
        node = new int[cfg.count()];
        Arrays.fill(node, at);
        weight = new double[cfg.count()];
        Arrays.fill(weight, 1);
        debt = new double[cfg.count()];
    }

    /** 在這些格子裡平均（隨機）撒。 */
    public void seedUniform(Collection<Integer> nodes, long tick) {
        seedTick = tick;
        List<Integer> ok = new ArrayList<>();
        for (int v : nodes) if (v >= 0 && v < nav.size()) ok.add(v);
        if (ok.isEmpty()) {
            node = new int[0];
            weight = new double[0];
            debt = new double[0];
            return;
        }
        node = new int[cfg.count()];
        for (int i = 0; i < node.length; i++) node[i] = ok.get(rnd.nextInt(ok.size()));
        weight = new double[cfg.count()];
        Arrays.fill(weight, 1);
        debt = new double[cfg.count()];
    }

    /** 每顆粒子走 seconds 秒（goalField 可以是 null，那部分當成隨機）。 */
    public void step(double seconds, DistanceField ghostField, DistanceField goalField) {
        double budget = cfg.blocksPerSecond() * seconds;
        for (int i = 0; i < node.length; i++) {
            int cur = node[i];
            double left = budget - debt[i];
            for (int guard = 0; left > 0 && guard < 16; guard++) {
                int next = choose(cur, ghostField, goalField);
                if (next < 0) {
                    left = 0;
                    break;
                }
                GridNav.Edge e = nav.edge(cur, next);
                left -= e.length() + cost.extra(e);
                cur = next;
            }
            debt[i] = Math.max(0, -left);
            node[i] = cur;
        }
    }

    /** 下一格；-1 表示這一步留在原地（沒有路，或往離鬼遠／往目標的方向已經沒有更好的格子）。 */
    private int choose(int cur, DistanceField ghost, DistanceField goal) {
        List<GridNav.Edge> es = nav.edges(cur);
        List<Integer> ok = new ArrayList<>(es.size());
        for (GridNav.Edge e : es) if (!Double.isInfinite(cost.extra(e))) ok.add(e.to());
        if (ok.isEmpty()) return -1;
        double r = rnd.nextDouble();
        if (r < cfg.fleeWeight() && ghost != null) {
            int best = -1;
            double bv = ghost.seconds(cur);
            for (int v : ok) {
                double sv = ghost.seconds(v);
                // 鬼走不到的格子（無窮大）不算「最遠」：不然粒子會整群鑽進鬼過不去的小洞、通道後面
                if (!Double.isInfinite(sv) && sv > bv) {
                    bv = sv;
                    best = v;
                }
            }
            return best;
        }
        if (r < cfg.fleeWeight() + cfg.goalWeight() && goal != null) {
            int best = -1;
            double bv = goal.seconds(cur);
            for (int v : ok) if (goal.seconds(v) < bv) {
                bv = goal.seconds(v);
                best = v;
            }
            return best;
        }
        if (bias == null) return ok.get(rnd.nextInt(ok.size()));
        double sum = 0;
        double[] w = new double[ok.size()];
        for (int i = 0; i < w.length; i++) sum += w[i] = Math.max(0, bias.weight(cur, ok.get(i)));
        if (sum <= 0) return ok.get(rnd.nextInt(ok.size()));
        double x = rnd.nextDouble() * sum;
        for (int i = 0; i < w.length; i++) if ((x -= w[i]) <= 0) return ok.get(i);
        return ok.get(ok.size() - 1);
    }

    /** 鬼看得到的格子裡的粒子全部刪掉。 */
    public void removeVisible(IntPredicate visibleToGhost) {
        int k = 0;
        for (int i = 0; i < node.length; i++) {
            if (visibleToGhost.test(node[i])) continue;
            node[k] = node[i];
            weight[k] = weight[i];
            debt[k] = debt[i];
            k++;
        }
        node = Arrays.copyOf(node, k);
        weight = Arrays.copyOf(weight, k);
        debt = Arrays.copyOf(debt, k);
    }

    /**
     * 聲音、痕跡：(x, y, z) 附近（同一層、高斯 σ = soundSigma）的粒子權重乘上最多 factor 倍，然後重新取樣回 count 顆。
     * 一顆都沒有時不動（聲音本身另外由大腦處理）。
     */
    public void weightNear(double x, double y, double z, double factor) {
        if (node.length == 0) return;
        double s2 = 2 * cfg.soundSigma() * cfg.soundSigma();
        for (int i = 0; i < node.length; i++) {
            if (Math.abs(nav.y(node[i]) - y) > 1.5) continue;
            double dx = nav.x(node[i]) + 0.5 - x, dz = nav.z(node[i]) + 0.5 - z;
            weight[i] *= 1 + (factor - 1) * Math.exp(-(dx * dx + dz * dz) / s2);
        }
        resample();
    }

    /** systematic resampling：照權重重新抽回 count 顆，權重歸 1。 */
    private void resample() {
        double total = 0;
        for (double w : weight) total += w;
        if (total <= 0) return;
        int n = cfg.count();
        int[] out = new int[n];
        double step = total / n, u = rnd.nextDouble() * step, acc = weight[0];
        int j = 0;
        for (int i = 0; i < n; i++) {
            double target = u + i * step;
            while (acc < target && j < node.length - 1) acc += weight[++j];
            out[i] = node[j];
        }
        node = out;
        weight = new double[n];
        Arrays.fill(weight, 1);
        debt = new double[n];
    }

    public int alive() {
        return node.length;
    }

    /** 放棄這一組：剩不到 minAlive 顆，或追蹤超過 maxAgeTicks。 */
    public boolean expired(long tick) {
        return node.length < cfg.minAlive() || tick - seedTick > cfg.maxAgeTicks();
    }

    /**
     * 搜尋目標：每格的分數＝同一層水平 2 格內每格的粒子數 ÷（1＋距離²），在「鬼沒看過、而且走得到」的格子裡取最高（同分取編號小的）。
     * 粒子在鬼走不到的格子（暗格裡、通道裡）時，算到最近的走得到的格子（最多 3 步）。沒有回傳 -1。
     */
    public int bestTarget(IntPredicate unseen, IntPredicate reachable) {
        Map<Integer, Integer> counts = new HashMap<>();
        Map<Integer, Integer> mapped = new HashMap<>();
        for (int v : node) {
            int r = mapped.computeIfAbsent(v, k -> nearestReachable(k, reachable));
            if (r >= 0) counts.merge(r, 1, Integer::sum);
        }
        int best = -1;
        double bs = -1;
        List<Integer> cells = new ArrayList<>(counts.keySet());
        // 候選：有粒子的格子，加上它們同一層 2 格內的格子（粒子都在看過的格子時，旁邊沒看過的地方還是值得去）
        Set<Integer> cands = new HashSet<>(cells);
        for (int c : cells)
            for (int dx = -2; dx <= 2; dx++)
                for (int dz = -2; dz <= 2; dz++) {
                    if (dx * dx + dz * dz > 4) continue;
                    int o = nav.id(nav.x(c) + dx, nav.y(c), nav.z(c) + dz);
                    if (o >= 0) cands.add(o);
                }
        for (int c : cands) {
            if (!unseen.test(c) || !reachable.test(c)) continue;
            double score = 0;
            for (int dx = -2; dx <= 2; dx++)
                for (int dz = -2; dz <= 2; dz++) {
                    int d2 = dx * dx + dz * dz;
                    if (d2 > 4) continue;
                    int o = nav.id(nav.x(c) + dx, nav.y(c), nav.z(c) + dz);
                    Integer k = o < 0 ? null : counts.get(o);
                    if (k != null) score += k / (1.0 + d2); // 越近權重越大：分數最高的是粒子最密的地方
                }
            if (score > bs || (score == bs && c < best)) {
                bs = score;
                best = c;
            }
        }
        return best;
    }

    private int nearestReachable(int start, IntPredicate reachable) {
        if (reachable.test(start)) return start;
        ArrayDeque<int[]> q = new ArrayDeque<>();
        Set<Integer> seen = new HashSet<>();
        q.add(new int[]{start, 0});
        seen.add(start);
        while (!q.isEmpty()) {
            int[] t = q.poll();
            if (t[1] >= 3) continue;
            for (GridNav.Edge e : nav.edges(t[0])) {
                if (!seen.add(e.to())) continue;
                if (reachable.test(e.to())) return e.to();
                q.add(new int[]{e.to(), t[1] + 1});
            }
        }
        return -1;
    }

    /** 在這些格子裡的粒子比例（0～1；沒有粒子是 0）。 */
    public double shareWithin(Collection<Integer> nodes) {
        if (node.length == 0) return 0;
        Set<Integer> set = nodes instanceof Set<Integer> s ? s : new HashSet<>(nodes);
        int k = 0;
        for (int v : node) if (set.contains(v)) k++;
        return k / (double) node.length;
    }

    /** 同一層、水平 r 格內的粒子比例（0～1）。 */
    public double shareNear(double x, double y, double z, double r) {
        if (node.length == 0) return 0;
        int k = 0;
        for (int v : node) {
            if (Math.abs(nav.y(v) - y) > 1.5) continue;
            double dx = nav.x(v) + 0.5 - x, dz = nav.z(v) + 0.5 - z;
            if (dx * dx + dz * dz <= r * r) k++;
        }
        return k / (double) node.length;
    }

    /** 每顆粒子在哪一格（除錯顯示用）。 */
    public int[] positions() {
        return node.clone();
    }
}
