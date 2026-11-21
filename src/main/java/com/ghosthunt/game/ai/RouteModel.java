package com.ghosthunt.game.ai;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * 路線預測（第三版第 22 項，一階馬可夫鏈）：鬼每次看到逃生者從 A 區走到 B 區就記 T[A][B] += 1。
 * 「從 A 出來下一步去 B」的機率 = (T[A][B] + 1) ÷ (T[A] 的總和 + 選項數)（加 1 平滑）。
 * 區域用穩定的字串（房間中心座標）當 key，這樣存檔後下一局還用得上。
 */
public final class RouteModel {

    private final Map<String, Map<String, Double>> t = new HashMap<>();

    public void transition(String from, String to) {
        if (from == null || to == null || from.equals(to)) return;
        t.computeIfAbsent(from, k -> new HashMap<>()).merge(to, 1.0, Double::sum);
    }

    /** 從 from 出來、下一步去 to 的機率（只在 options 之間比較）。 */
    public double probability(String from, String to, Collection<String> options) {
        Map<String, Double> row = t.getOrDefault(from, Map.of());
        double sum = 0;
        for (String o : options) sum += row.getOrDefault(o, 0.0);
        return (row.getOrDefault(to, 0.0) + 1) / (sum + options.size());
    }

    public double count(String from, String to) {
        return t.getOrDefault(from, Map.of()).getOrDefault(to, 0.0);
    }

    public void decay(double f) {
        for (Map<String, Double> row : t.values()) row.replaceAll((k, v) -> v * f);
        for (Map<String, Double> row : t.values()) row.values().removeIf(v -> v < 0.05);
        t.values().removeIf(Map::isEmpty);
    }

    /** 存檔用：key 是 "A>B"。 */
    public Map<String, Double> flat() {
        Map<String, Double> out = new HashMap<>();
        for (Map.Entry<String, Map<String, Double>> r : t.entrySet())
            for (Map.Entry<String, Double> e : r.getValue().entrySet()) out.put(r.getKey() + ">" + e.getKey(), e.getValue());
        return out;
    }

    public void load(String key, double v) {
        int i = key.indexOf('>');
        if (i <= 0) return;
        t.computeIfAbsent(key.substring(0, i), k -> new HashMap<>()).put(key.substring(i + 1), v);
    }
}
