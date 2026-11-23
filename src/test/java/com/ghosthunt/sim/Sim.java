package com.ghosthunt.sim;

import com.ghosthunt.game.ai.DistanceField;
import com.ghosthunt.game.ai.EscaperCost;
import com.ghosthunt.game.ai.GridNav;
import com.ghosthunt.game.ai.GridNav.Kind;
import com.ghosthunt.game.ai.HideoutOdds;
import com.ghosthunt.game.ai.ParticleTracker;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 最小模擬器（第三版第 1 批）：在 ASCII 地圖的 1F 上讓簡化的鬼對簡化的逃生者，比較第二版和第三版的「追丟以後怎麼找」。
 * 只驅動純邏輯類別（GridNav、DistanceField、VisibilityTable、ParticleTracker、HideoutOdds），不碰 AiGhost。
 * 移動是「每 0.25 秒照速度沿著邊走」，沒有物理、沒有門的開關、沒有卡住；只看大方向。
 * <p>
 * 用法：./gradlew sim --args="--map hydro_plant --runs 200 --seconds 300 --search v3 --seed 1"
 */
public final class Sim {

    static final double DT = 0.25;
    static final double GHOST_SPEED = 5.3, CHASE = 1.0, SEARCH = 0.9, PATROL = 0.75;
    static final double RUN = 5.6, WALK = 4.3;
    static final double SIGHT = 24, FOV_HALF = 60, NEAR_AWARE = 3, FOG = 14, CATCH = 1.5, HEAR_RUN = 8;

    record Stats(int catches, int losses, int reacquired, double firstContact, double reDelay) {}

    public static void main(String[] args) throws Exception {
        String map = "hydro_plant", search = "v3", tune = null;
        int runs = 100, seconds = 300, candidates = 24;
        long seed = 1;
        double target = -1;
        for (int i = 0; i + 1 < args.length; i += 2) {
            switch (args[i]) {
                case "--map" -> map = args[i + 1];
                case "--runs" -> runs = Integer.parseInt(args[i + 1]);
                case "--seconds" -> seconds = Integer.parseInt(args[i + 1]);
                case "--search" -> search = args[i + 1];
                case "--seed" -> seed = Long.parseLong(args[i + 1]);
                case "--tune" -> tune = args[i + 1];
                case "--candidates" -> candidates = Integer.parseInt(args[i + 1]);
                case "--target-reacquire" -> target = Double.parseDouble(args[i + 1]);
                default -> throw new IllegalArgumentException("不認識的參數 " + args[i]);
            }
        }
        SimMap m = SimMap.load(Path.of("src/main/resources/pve-maps/" + map + ".txt"));
        if (tune != null) {
            tune(m, map, tune, target, runs, seconds, candidates, seed);
            return;
        }
        int catches = 0, losses = 0, re = 0, contacts = 0;
        double first = 0, delay = 0;
        for (int r = 0; r < runs; r++) {
            Stats s = new Sim(m, search.equals("v3"), new Random(seed + r)).run(seconds);
            catches += s.catches;
            losses += s.losses;
            re += s.reacquired;
            delay += s.reDelay;
            if (s.firstContact >= 0) {
                first += s.firstContact;
                contacts++;
            }
        }
        System.out.printf("地圖 %s｜搜尋 %s｜%d 局 × %d 秒｜每分鐘抓 %.2f 次｜追丟 %d 次｜追丟後 30 秒內重新找到 %.1f%%（平均 %.1f 秒）｜第一次接觸平均 %.0f 秒%n",
                map, search, runs, seconds, catches / (runs * seconds / 60.0), losses, losses == 0 ? 0 : 100.0 * re / losses,
                re == 0 ? 0 : delay / re, contacts == 0 ? -1 : first / contacts);
    }

    /** 一組設定跑 runs 局的結果。 */
    record Result(double reacquire, double catchesPerMin, double firstContact) {}

    static Result evaluate(SimMap m, ParticleTracker.Config pc, int runs, int seconds, long seed) {
        int catches = 0, losses = 0, re = 0, contacts = 0;
        double first = 0;
        for (int r = 0; r < runs; r++) {
            Stats s = new Sim(m, true, new Random(seed + r), pc).run(seconds);
            catches += s.catches;
            losses += s.losses;
            re += s.reacquired;
            if (s.firstContact >= 0) {
                first += s.firstContact;
                contacts++;
            }
        }
        return new Result(losses == 0 ? 0 : re / (double) losses, catches / (runs * seconds / 60.0), contacts == 0 ? -1 : first / contacts);
    }

    /**
     * 第三版第 24 項：自動調參（先用隨機搜尋）。模擬沒有大門、木桶，量不到逃出比例，所以目標是
     * 「追丟後 30 秒內重新找到」的比例照難度（簡單 85%、普通 92%、困難 97%），再加上第一次接觸 60～120 秒。
     * 只印出建議值，不改 config（調出來的只是起點，真人玩過以後要用 stats.log 校正）。
     */
    static void tune(SimMap m, String map, String difficulty, double target, int runs, int seconds, int candidates, long seed) {
        if (target < 0) target = switch (difficulty) {
            case "easy" -> 0.85;
            case "hard" -> 0.97;
            default -> 0.92;
        };
        Random rnd = new Random(seed * 31 + difficulty.hashCode());
        record Cand(int particles, double flee, double sigma, Result r, double loss) {}
        List<Cand> all = new ArrayList<>();
        for (int i = 0; i < candidates; i++) {
            int n = i == 0 ? 200 : 60 + rnd.nextInt(8) * 50;
            double flee = i == 0 ? 0.6 : Math.round(rnd.nextDouble() * 10) / 10.0;
            double sigma = i == 0 ? 3 : 1.5 + Math.round(rnd.nextDouble() * 7) / 2.0;
            ParticleTracker.Config pc = new ParticleTracker.Config(n, RUN, flee, 0.0, sigma, 10, 40 * 20);
            Result r = evaluate(m, pc, runs, seconds, seed);
            double fc = r.firstContact() < 0 ? 300 : r.firstContact();
            double loss = Math.abs(r.reacquire() - target) + 0.05 * Math.max(0, Math.max(60 - fc, fc - 120)) / 30;
            all.add(new Cand(n, flee, sigma, r, loss));
            System.out.printf("  [%2d/%d] particles %3d｜flee %.1f｜sigma %.1f → 重新找到 %.1f%%｜每分鐘抓 %.2f｜第一次接觸 %.0f 秒｜偏差 %.3f%n",
                    i + 1, candidates, n, flee, sigma, 100 * r.reacquire(), r.catchesPerMin(), r.firstContact(), loss);
        }
        all.sort((a, b) -> Double.compare(a.loss(), b.loss()));
        System.out.printf("%n地圖 %s｜難度 %s｜目標重新找到 %.0f%%｜每組 %d 局 × %d 秒｜最接近的前 5 組（上面清單的 [1] 是現在的普通設定 200/0.6/3，可以拿來比）：%n",
                map, difficulty, 100 * target, runs, seconds);
        for (int i = 0; i < Math.min(5, all.size()); i++) {
            Cand c = all.get(i);
            System.out.printf("  %d. particles: %d｜particle-flee: %.1f｜particle-sigma: %.1f → 重新找到 %.1f%%｜每分鐘抓 %.2f｜第一次接觸 %.0f 秒%n",
                    i + 1, c.particles(), c.flee(), c.sigma(), 100 * c.r().reacquire(), c.r().catchesPerMin(), c.r().firstContact());
        }
    }

    // =====================================================================

    private final SimMap m;
    private final GridNav nav;
    private final boolean v3;
    private final Random rnd;
    private final GridNav.Cost ghostCost = e -> e.kind() == Kind.HOLE || e.kind() == Kind.HATCH ? Double.POSITIVE_INFINITY
            : e.kind() == Kind.DOOR ? 0.15 : 0;
    private final GridNav.Cost escCost = EscaperCost.of(i -> false, i -> false, i -> true, 6, 8);
    /** 粒子推理用的成本：跟遊戲一樣，鬼不知道的地洞不走（模擬裡鬼一開始都不知道）。 */
    private final GridNav.Cost trackCost = EscaperCost.of(i -> false, i -> false, i -> false, 6, 8);
    private final List<Integer> mainNodes = new ArrayList<>();
    private double t;

    /** 粒子的設定（自動調參時換）。 */
    private ParticleTracker.Config pc = new ParticleTracker.Config(200, RUN, 0.6, 0.0, 3, 10, 40 * 20);

    Sim(SimMap m, boolean v3, Random rnd, ParticleTracker.Config pc) {
        this(m, v3, rnd);
        this.pc = pc;
    }

    Sim(SimMap m, boolean v3, Random rnd) {
        this.m = m;
        this.nav = m.nav();
        this.v3 = v3;
        this.rnd = rnd;
        for (int i = 0; i < nav.size(); i++) if (m.ghostReachable().test(i)) mainNodes.add(i);
    }

    double dist(int a, int b) {
        return Math.hypot(nav.x(a) - nav.x(b), nav.z(a) - nav.z(b));
    }

    // ---- 逃生者 ----

    final class Escaper {
        int node;
        int target = -1;
        double budget;
        int hideAt = -1; // 躲在哪個暗格（節點），-1 沒躲
        double hideUntil, lastSawGhost = -100;
        boolean running;

        Escaper(int node) {
            this.node = node;
        }

        void tick(Ghost g, DistanceField ghostField) {
            if (hideAt >= 0) {
                if (t >= hideUntil) {
                    hideAt = -1;
                    target = -1;
                }
                running = false;
                return;
            }
            boolean sees = m.vis().visible(node, g.node, i -> false) && dist(node, g.node) <= FOG;
            if (sees) lastSawGhost = t;
            running = t - lastSawGhost < 2;
            if (running) {
                // 跑：往離鬼遠、而且不是死路（鄰格多）的方向，帶一點隨機（跟粒子的推理規則不一樣，不然模型天生猜對）
                budget += RUN * DT;
                while (budget >= 1) {
                    int best = -1;
                    double here = ghostField.seconds(node), bv = Double.isInfinite(here) ? 1e9 : here;
                    for (GridNav.Edge e : nav.edges(node)) {
                        if (Double.isInfinite(escCost.extra(e))) continue;
                        double sv = ghostField.seconds(e.to());
                        if (Double.isInfinite(sv)) sv = bv + 3; // 鬼過不去的地方（小洞、地洞後面）：很想去
                        double score = sv + rnd.nextDouble() * 0.6;
                        if (score > bv) {
                            bv = score;
                            best = e.to();
                        }
                    }
                    if (best < 0) break;
                    node = best;
                    budget -= 1;
                }
                // 甩開以後（看不到鬼）附近有暗格：30% 躲 10 秒
                if (!sees) for (int h : m.hideoutNodes()) {
                    if (dist(node, h) <= 3 && rnd.nextDouble() < 0.3) {
                        hideAt = h;
                        node = h;
                        hideUntil = t + 10;
                        return;
                    }
                }
                return;
            }
            // 沒事：往隨機目標走
            if (target < 0 || node == target) target = mainNodes.get(rnd.nextInt(mainNodes.size()));
            budget += WALK * DT;
            List<Integer> p = nav.path(node, target, escCost);
            int k = 1;
            while (budget >= 1 && k < p.size()) {
                node = p.get(k++);
                budget -= 1;
            }
            if (p.size() <= 1) target = -1;
        }
    }

    // ---- 鬼 ----

    final class Ghost {
        int node;
        double fx = 1, fz = 0, budget, fatigueUntil;
        Escaper chasing;
        int lastSeen = -1;
        double lastSeenT = -100, lostAt = -100;
        boolean waitReacquire;
        double dirX, dirZ;
        // v2 搜尋
        int phase; // 0 沒在搜、1 去最後位置、2 往逃跑方向、3 張望、4 去查暗格
        double lookUntil;
        int searchTarget = -1;
        int patrolTarget = -1;
        // v3
        ParticleTracker tracker;
        double trackerStepAt;
        int checkingHideout = -1;

        Ghost(int node) {
            this.node = node;
        }

        boolean canSee(Escaper e) {
            if (e.hideAt >= 0) return false;
            double d = dist(node, e.node);
            if (d > SIGHT || !m.vis().visible(node, e.node, i -> false)) return false;
            if (d <= NEAR_AWARE) return true;
            double dx = nav.x(e.node) - nav.x(node), dz = nav.z(e.node) - nav.z(node);
            double cos = (dx * fx + dz * fz) / Math.max(1e-9, Math.hypot(dx, dz) * Math.hypot(fx, fz));
            return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, cos)))) <= FOV_HALF;
        }

        void moveToward(int goal, double speedMul) {
            if (goal < 0 || goal == node) return;
            budget += GHOST_SPEED * speedMul * DT;
            List<Integer> p = nav.path(node, goal, ghostCost);
            int k = 1;
            while (budget >= 1 && k < p.size()) {
                int nx = p.get(k++);
                fx = nav.x(nx) - nav.x(node);
                fz = nav.z(nx) - nav.z(node);
                node = nx;
                budget -= 1;
            }
            if (p.size() <= 1) budget = 0;
        }
    }

    Stats run(int seconds) {
        List<Escaper> es = new ArrayList<>();
        for (int i = 0; i < 3; i++) es.add(new Escaper(mainNodes.get(rnd.nextInt(mainNodes.size()))));
        // 有鬼房：從鐵欄杆門外面出發；沒有鬼房（PvE 現在的規則）：離最近的逃生者走路最遠的地方
        int start = m.ghostStart() >= 0 ? m.ghostStart() : m.farthestFrom(es.stream().mapToInt(e -> e.node).toArray());
        if (start < 0) throw new IllegalStateException("找不到鬼的出生點（地圖沒有鬼房，主要區域也是空的）");
        Ghost g = new Ghost(start);
        int catches = 0, losses = 0, re = 0;
        double first = -1, reDelay = 0;
        ParticleTracker.Config pc = this.pc;
        for (t = 0; t < seconds; t += DT) {
            DistanceField gf = DistanceField.compute(nav, escCost, GHOST_SPEED, g.node);
            for (Escaper e : es) e.tick(g, gf);
            if (t < g.fatigueUntil) continue;
            // 看
            Escaper seen = null;
            double sd = Double.MAX_VALUE;
            for (Escaper e : es) if (g.canSee(e) && dist(g.node, e.node) < sd) {
                sd = dist(g.node, e.node);
                seen = e;
            }
            if (seen != null) {
                if (first < 0) first = t;
                if (g.waitReacquire && t - g.lostAt <= 30) {
                    re++;
                    reDelay += t - g.lostAt;
                }
                g.waitReacquire = false;
                if (g.lastSeen >= 0 && g.lastSeen != seen.node) {
                    g.dirX = nav.x(seen.node) - nav.x(g.lastSeen);
                    g.dirZ = nav.z(seen.node) - nav.z(g.lastSeen);
                }
                g.chasing = seen;
                g.lastSeen = seen.node;
                g.lastSeenT = t;
                g.phase = 0;
                g.tracker = null;
                g.checkingHideout = -1;
                if (sd <= CATCH) {
                    catches++;
                    // 被抓的人傳到離鬼最遠的格子
                    int far = mainNodes.get(0);
                    for (int v : mainNodes) if (gf.seconds(v) > gf.seconds(far) && !Double.isInfinite(gf.seconds(v))) far = v;
                    seen.node = far;
                    seen.target = -1;
                    g.chasing = null;
                    g.fatigueUntil = t + 1.5;
                    continue;
                }
                g.moveToward(seen.node, CHASE);
                continue;
            }
            // 剛看不到（1 秒內）：照對方的真實位置追（跟 AI 鬼一樣）；超過 1 秒才算追丟
            if (g.chasing != null && t - g.lastSeenT < 1.0) {
                g.lastSeen = g.chasing.hideAt >= 0 ? g.lastSeen : g.chasing.node;
                g.moveToward(g.lastSeen, CHASE);
                continue;
            }
            if (g.chasing != null) {
                g.chasing = null;
                losses++;
                g.lostAt = t;
                g.waitReacquire = true;
                if (v3) {
                    g.tracker = new ParticleTracker(nav, trackCost, pc, rnd);
                    g.tracker.seed(g.lastSeen, (long) (t * 20));
                    g.trackerStepAt = t;
                } else g.phase = 1;
            }
            // 聽：8 格內跑步的人
            for (Escaper e : es) if (e.running && e.hideAt < 0 && dist(g.node, e.node) <= HEAR_RUN) {
                if (v3 && g.tracker != null) g.tracker.weightNear(nav.x(e.node) + 0.5, 65, nav.z(e.node) + 0.5, 2);
                else if (g.phase == 0 || g.phase == 3) {
                    g.lastSeen = e.node; // 第二版：去聲音的地方
                    g.phase = 1;
                }
            }
            if (v3) searchV3(g, es, gf);
            else searchV2(g, es);
        }
        return new Stats(catches, losses, re, first, reDelay);
    }

    // ---- 第二版搜尋：最後位置 → 查暗格（40%，8 格內）→ 往逃跑方向 7 格 → 張望 3 秒 → 巡邏 ----

    private void searchV2(Ghost g, List<Escaper> es) {
        switch (g.phase) {
            case 1 -> {
                if (g.node == g.lastSeen || dist(g.node, g.lastSeen) < 1.5) {
                    int h = nearestHideout(g.lastSeen, 8);
                    if (h >= 0 && rnd.nextDouble() < 0.4) {
                        g.searchTarget = entranceOf(h);
                        g.checkingHideout = h;
                        g.phase = 4;
                    } else g.phase = 2;
                    return;
                }
                g.moveToward(g.lastSeen, SEARCH);
            }
            case 4 -> {
                if (dist(g.node, g.searchTarget) <= 1.5) {
                    revealHidden(g, es);
                    g.phase = 2;
                    return;
                }
                g.moveToward(g.searchTarget, SEARCH);
            }
            case 2 -> {
                if (g.searchTarget < 0 || g.phase == 2 && g.searchTarget == entranceOf(g.checkingHideout)) {
                    double len = Math.hypot(g.dirX, g.dirZ);
                    int ax = nav.x(g.lastSeen) + (int) Math.round(len < 0.01 ? 0 : g.dirX / len * 7);
                    int az = nav.z(g.lastSeen) + (int) Math.round(len < 0.01 ? 0 : g.dirZ / len * 7);
                    g.searchTarget = nearestMain(ax, az);
                    g.checkingHideout = -1;
                }
                if (g.searchTarget < 0 || dist(g.node, g.searchTarget) < 1.5) {
                    g.phase = 3;
                    g.lookUntil = t + 3;
                    g.searchTarget = -1;
                    return;
                }
                g.moveToward(g.searchTarget, SEARCH);
            }
            case 3 -> {
                lookAround(g);
                if (t >= g.lookUntil) g.phase = 0;
            }
            default -> patrol(g);
        }
    }

    // ---- 第三版搜尋：粒子 ----

    private void searchV3(Ghost g, List<Escaper> es, DistanceField gf) {
        if (g.checkingHideout >= 0) {
            int ent = entranceOf(g.checkingHideout);
            if (dist(g.node, ent) <= 1.5) {
                revealHidden(g, es);
                g.checkingHideout = -1;
            } else g.moveToward(ent, SEARCH);
            return;
        }
        ParticleTracker tr = g.tracker;
        if (tr == null || tr.expired((long) (t * 20))) {
            g.tracker = null;
            patrol(g);
            return;
        }
        if (t - g.trackerStepAt >= 0.5) {
            g.trackerStepAt = t;
            DistanceField ghostSearch = DistanceField.compute(nav, ghostCost, GHOST_SPEED * SEARCH, g.node);
            tr.step(0.5, ghostSearch, null);
        }
        Ghost gg = g;
        tr.removeVisible(i -> m.vis().visible(gg.node, i, d -> false) && dist(gg.node, i) <= SIGHT && inFov(gg, i));
        int target = tr.bestTarget(i -> true, m.ghostReachable());
        if (target < 0) {
            g.tracker = null;
            patrol(g);
            return;
        }
        if (dist(g.node, target) < 1.5) {
            // 到了：附近的暗格照證據擲骰
            int h = nearestHideout(g.node, 12);
            if (h >= 0) {
                double share = tr.shareWithin(List.of(h));
                double p = HideoutOdds.chance(0.4, dist(g.lastSeen, entranceOf(h)), t - g.lastSeenT, true, share, 0);
                if (rnd.nextDouble() < p) {
                    g.checkingHideout = h;
                    return;
                }
            }
            lookAround(g);
            return;
        }
        g.moveToward(target, SEARCH);
    }

    private boolean inFov(Ghost g, int i) {
        double d = dist(g.node, i);
        if (d <= NEAR_AWARE) return true;
        double dx = nav.x(i) - nav.x(g.node), dz = nav.z(i) - nav.z(g.node);
        double cos = (dx * g.fx + dz * g.fz) / Math.max(1e-9, Math.hypot(dx, dz) * Math.hypot(g.fx, g.fz));
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, cos)))) <= FOV_HALF;
    }

    private void lookAround(Ghost g) {
        double a = rnd.nextDouble() * Math.PI * 2;
        g.fx = Math.cos(a);
        g.fz = Math.sin(a);
    }

    private void patrol(Ghost g) {
        if (g.patrolTarget < 0 || dist(g.node, g.patrolTarget) < 1.5) g.patrolTarget = mainNodes.get(rnd.nextInt(mainNodes.size()));
        g.moveToward(g.patrolTarget, PATROL);
    }

    /** 鬼站在暗格入口：躲在裡面的人被看到（下一次 think 就會追）。 */
    private void revealHidden(Ghost g, List<Escaper> es) {
        for (Escaper e : es) if (e.hideAt >= 0 && dist(g.node, e.hideAt) <= 2.5) {
            e.hideAt = -1;
            e.node = entranceOf(e.node) >= 0 ? entranceOf(e.node) : e.node;
            e.lastSawGhost = t;
        }
    }

    private int nearestHideout(int from, double r) {
        int best = -1;
        double bd = r;
        for (int h : m.hideoutNodes()) if (dist(from, h) <= bd) {
            bd = dist(from, h);
            best = h;
        }
        return best;
    }

    /** 暗格節點的入口（HOLE 邊另一端）。 */
    private int entranceOf(int pocket) {
        if (pocket < 0) return -1;
        for (GridNav.Edge e : nav.edges(pocket)) if (e.kind() == Kind.HOLE) return e.to();
        return -1;
    }

    private int nearestMain(int x, int z) {
        int best = -1;
        double bd = 1e9;
        for (int v : mainNodes) {
            double d = Math.hypot(nav.x(v) - x, nav.z(v) - z);
            if (d < bd) {
                bd = d;
                best = v;
            }
        }
        return best;
    }
}
