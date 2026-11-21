package com.ghosthunt.game.ai;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI 鬼的長期記憶（第三版第 19～22 項），每張地圖一份（plugins/GhostHunt/ai-memory/&lt;地圖&gt;.yml）：
 * 被哪一招甩掉的次數、熱點、路線轉移表、個性拉桿的 Beta 分布。每局結束舊資料 ×decay，舊習慣會慢慢淡掉。
 * 純資料：讀寫 yml 由插件那邊用 {@link #toFlat()}／{@link #fromFlat(Map)} 做（key 不含點）。
 */
public final class AiMemory {

    /** 甩掉鬼的招式。 */
    public enum Trick { HIDEOUT, HATCH, HOLE, DEAD_END, LURE, SMOKE }

    private final EnumMap<Trick, Double> escapes = new EnumMap<>(Trick.class);
    public final Hotspots heat = new Hotspots();
    public final RouteModel routes = new RouteModel();
    public final Map<String, double[]> bandit = new HashMap<>();
    private int matches;

    public void escaped(Trick t) {
        escapes.merge(t, 1.0, Double::sum);
    }

    public double escapes(Trick t) {
        return escapes.getOrDefault(t, 0.0);
    }

    /** 學到的加成：第一次被甩掉前是 0，之後 cap × (1 − 0.5^次數)（1 次一半、2 次 75%……）。 */
    public double bonus(Trick t, double cap) {
        return cap * (1 - Math.pow(0.5, escapes(t)));
    }

    public int matches() {
        return matches;
    }

    /** 每局結束：舊資料 ×f（拉桿只縮「超過先驗 1 的部分」）。 */
    public void endMatch(double f) {
        matches++;
        escapes.replaceAll((k, v) -> v * f);
        heat.decay(f);
        routes.decay(f);
        for (double[] ab : bandit.values()) {
            ab[0] = 1 + (ab[0] - 1) * f;
            ab[1] = 1 + (ab[1] - 1) * f;
        }
    }

    public Map<String, Object> toFlat() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("matches", matches);
        for (Map.Entry<Trick, Double> e : escapes.entrySet()) out.put("tricks." + e.getKey().name().toLowerCase(), round(e.getValue()));
        for (Map.Entry<String, double[]> e : bandit.entrySet()) {
            out.put("bandit." + e.getKey() + ".alpha", round(e.getValue()[0]));
            out.put("bandit." + e.getKey() + ".beta", round(e.getValue()[1]));
        }
        for (Map.Entry<String, Double> e : routes.flat().entrySet()) out.put("routes." + e.getKey(), round(e.getValue()));
        for (Map.Entry<String, Double> e : heat.flat().entrySet()) out.put("heat." + e.getKey(), e.getValue());
        return out;
    }

    /** 讀 yml 的「深層 key → 值」（只看數字）。 */
    public static AiMemory fromFlat(Map<String, ?> flat) {
        AiMemory m = new AiMemory();
        for (Map.Entry<String, ?> e : flat.entrySet()) {
            if (!(e.getValue() instanceof Number num)) continue;
            double v = num.doubleValue();
            String k = e.getKey();
            if (k.equals("matches")) m.matches = (int) v;
            else if (k.startsWith("tricks.")) {
                try {
                    m.escapes.put(Trick.valueOf(k.substring(7).toUpperCase()), v);
                } catch (IllegalArgumentException ignored) {
                    // 不認得的招式
                }
            } else if (k.startsWith("bandit.")) {
                String[] p = k.split("\\.");
                if (p.length != 3) continue;
                double[] ab = m.bandit.computeIfAbsent(p[1], x -> new double[]{1, 1});
                if (p[2].equals("alpha")) ab[0] = v;
                else if (p[2].equals("beta")) ab[1] = v;
            } else if (k.startsWith("routes.")) m.routes.load(k.substring(7), v);
            else if (k.startsWith("heat.")) m.heat.load(k.substring(5), v);
        }
        return m;
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
