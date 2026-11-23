package com.ghosthunt.game;

import com.ghosthunt.building.Pos;
import com.ghosthunt.game.ai.DistanceField;
import com.ghosthunt.game.ai.GridNav;
import org.bukkit.Location;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.IntPredicate;

/** 沒有鬼房的開局：逃生者隨機分散出生，鬼出生在離所有人走路最遠的地方（PvE 的 AI 鬼、PvP 的真人鬼共用）。 */
final class Spawns {

    private Spawns() {
    }

    /**
     * 逃生者的隨機出生點：尋路圖上的一般格子，離監獄 10 格以上、彼此至少 minEach 格（找不到就放寬）。
     * allow 不是 null 時只挑它允許的格子（PvP：排除鬼房、樓梯間）。
     */
    static List<Location> random(Match m, NavMap nav, int n, double minEach, IntPredicate allow) {
        List<Integer> cells = new ArrayList<>();
        for (int i = 0; i < nav.nav.size(); i++) {
            if (!nav.isMain(i) || (allow != null && !allow.test(i))) continue;
            Location l = nav.loc(i);
            boolean nearJail = false;
            for (Pos cage : m.b.jailCages.values()) if (m.loc(cage).getWorld().equals(l.getWorld()) && m.loc(cage).distance(l) < 10) nearJail = true;
            if (!nearJail) cells.add(i);
        }
        Collections.shuffle(cells, m.rnd);
        for (double min = minEach; min >= 0; min -= 3) {
            List<Location> out = new ArrayList<>();
            for (int c0 : cells) {
                Location l = nav.loc(c0);
                boolean ok = true;
                for (Location o : out) if (o.distance(l) < min) ok = false;
                if (!ok) continue;
                out.add(l);
                if (out.size() == n) return out;
            }
        }
        List<Location> out = new ArrayList<>();
        for (int i = 0; i < Math.min(n, cells.size()); i++) out.add(nav.loc(cells.get(i)));
        return out;
    }

    /** 鬼的出生點：離「最近的逃生者」走路最遠的格子（多起點距離場）；沒有逃生者回傳 null。 */
    static Location farthest(Match m, NavMap nav, List<Location> people) {
        List<Integer> src = new ArrayList<>();
        for (Location l : people) {
            int n = nav.nearest(l, 3);
            if (n >= 0) src.add(n);
        }
        if (src.isEmpty()) return null;
        DistanceField f = DistanceField.compute(nav.nav, e -> e.kind() == GridNav.Kind.WALK || e.kind() == GridNav.Kind.STAIR
                || e.kind() == GridNav.Kind.DOOR ? 0 : Double.POSITIVE_INFINITY, 1.0, src.stream().mapToInt(Integer::intValue).toArray());
        int best = -1;
        double bd = -1;
        for (int i = 0; i < nav.nav.size(); i++) {
            if (!nav.isMain(i)) continue;
            double d = f.seconds(i);
            if (!Double.isInfinite(d) && d > bd) {
                bd = d;
                best = i;
            }
        }
        if (best < 0) return null;
        m.plugin.getLogger().info("[開局] 鬼的出生點：離最近的逃生者走路 " + Math.round(bd) + " 格");
        return nav.loc(best);
    }
}
