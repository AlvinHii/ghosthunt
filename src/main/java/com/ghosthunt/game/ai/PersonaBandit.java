package com.ghosthunt.game.ai;

import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

/**
 * 戰術選擇（第三版第 21 項）：三種個性當三隻拉桿，Thompson sampling。
 * 每隻拉桿一個 Beta(α, β)（沒資料是 Beta(1, 1)），開局各抽一個數選最大的；局末用「勢均力敵分數」更新。
 */
public final class PersonaBandit {

    private PersonaBandit() {}

    /** ab：每個拉桿的 {α, β}（沒有就當 {1, 1}）。 */
    public static String choose(Map<String, double[]> ab, List<String> arms, RandomGenerator rnd) {
        String best = arms.get(0);
        double bv = -1;
        for (String a : arms) {
            double[] p = ab.getOrDefault(a, new double[]{1, 1});
            double v = sampleBeta(Math.max(1e-3, p[0]), Math.max(1e-3, p[1]), rnd);
            if (v > bv) {
                bv = v;
                best = a;
            }
        }
        return best;
    }

    /** 勢均力敵分數：1 − |逃出比例 − 目標比例|（只優化抓人數會越來越強、越來越不好玩）。 */
    public static double score(double escapeRatio, double target) {
        return Math.max(0, Math.min(1, 1 - Math.abs(escapeRatio - target)));
    }

    /** 這個難度的目標逃出比例：簡單 0.7、普通 0.5、困難 0.3。 */
    public static double target(String difficulty) {
        return switch (difficulty) {
            case "easy" -> 0.7;
            case "hard" -> 0.3;
            default -> 0.5;
        };
    }

    /** 局末更新：α += 分數、β += 1 − 分數。 */
    public static void update(Map<String, double[]> ab, String arm, double score) {
        double[] p = ab.computeIfAbsent(arm, k -> new double[]{1, 1});
        p[0] += score;
        p[1] += 1 - score;
    }

    static double sampleBeta(double a, double b, RandomGenerator rnd) {
        double x = sampleGamma(a, rnd), y = sampleGamma(b, rnd);
        return x + y <= 0 ? 0.5 : x / (x + y);
    }

    /** Marsaglia–Tsang；shape < 1 用 Gamma(a+1)·U^(1/a)。 */
    static double sampleGamma(double shape, RandomGenerator rnd) {
        if (shape < 1) return sampleGamma(shape + 1, rnd) * Math.pow(rnd.nextDouble(), 1 / shape);
        double d = shape - 1.0 / 3, c = 1 / Math.sqrt(9 * d);
        while (true) {
            double x, v;
            do {
                x = rnd.nextGaussian();
                v = 1 + c * x;
            } while (v <= 0);
            v = v * v * v;
            double u = rnd.nextDouble();
            if (u < 1 - 0.0331 * x * x * x * x) return d * v;
            if (Math.log(u) < 0.5 * x * x + d * (1 - v + Math.log(v))) return d * v;
        }
    }
}
