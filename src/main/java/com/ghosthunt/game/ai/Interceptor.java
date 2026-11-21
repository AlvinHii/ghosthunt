package com.ghosthunt.game.ai;

import java.util.List;

/**
 * 繞前堵（純邏輯，小精靈的粉紅鬼）：猜對方要往哪個出口跑，沿著他到那裡的路找「鬼會先到」的格子，去那裡堵。
 */
public final class Interceptor {

    private Interceptor() {}

    /**
     * 每個候選目標的機率：分數 = 移動方向跟「往那裡的方向」的夾角餘弦 ＋ 過去 1 秒離它變近多少（格），再 softmax。
     *
     * @param candidates 每個候選 {x, z, 過去 1 秒變近了幾格}
     * @param temperature softmax 溫度（越小越果斷）
     */
    public static double[] targetProbabilities(double px, double pz, double vx, double vz, double[][] candidates, double temperature) {
        int n = candidates.length;
        double[] score = new double[n];
        double vl = Math.hypot(vx, vz);
        double max = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < n; i++) {
            double dx = candidates[i][0] - px, dz = candidates[i][1] - pz, dl = Math.hypot(dx, dz);
            double cos = vl < 1e-6 || dl < 1e-6 ? 0 : (dx * vx + dz * vz) / (dl * vl);
            score[i] = (cos + candidates[i][2]) / temperature;
            max = Math.max(max, score[i]);
        }
        double sum = 0;
        double[] p = new double[n];
        for (int i = 0; i < n; i++) {
            p[i] = Math.exp(score[i] - max);
            sum += p[i];
        }
        for (int i = 0; i < n; i++) p[i] /= sum;
        return p;
    }

    /**
     * 沿著對方到目標的路（第一個是對方現在的格子），找第一個「鬼到那裡的秒數 ＋ margin ＜ 對方到那裡的秒數」的格子；
     * 沒有回傳 -1（照舊直追）。
     */
    public static int interceptNode(List<Integer> pathFromEscaper, DistanceField ghost, DistanceField escaper, double margin) {
        for (int v : pathFromEscaper) {
            double g = ghost.seconds(v), e = escaper.seconds(v);
            if (Double.isInfinite(g) || Double.isInfinite(e)) continue;
            if (g + margin < e) return v;
        }
        return -1;
    }
}
