package com.ghosthunt.game.ai;

import java.util.List;
import java.util.function.ToDoubleFunction;
import java.util.random.RandomGenerator;

/**
 * 巡邏用的「這間房可能有人」分數（潛行遊戲常用的 occupancy map，純邏輯）。
 * <ul>
 *   <li>分數隨「多久沒去看」慢慢上升（每秒 growth，最多 cap）；鬼進去看過就歸零。</li>
 *   <li>房間裡有聲音就加分。</li>
 *   <li>選房間：分數 × 進度倍率 ÷ 走過去要幾秒，做加權隨機（不要每次挑最高分，免得被摸出規律）。</li>
 * </ul>
 */
public final class RoomScores {

    public record RoomInfo(int id, double x, double y, double z, int floor) {}

    private final List<RoomInfo> rooms;
    private final double growth, cap;
    private final long[] lastVisit;
    private final double[] bonus;

    public RoomScores(List<RoomInfo> rooms, double growthPerSecond, double cap) {
        this.rooms = rooms;
        this.growth = growthPerSecond;
        this.cap = cap;
        this.lastVisit = new long[rooms.size()];
        this.bonus = new double[rooms.size()];
    }

    public List<RoomInfo> rooms() {
        return rooms;
    }

    public double score(int id, long now) {
        return Math.min(cap, Math.max(0, now - lastVisit[id]) / 20.0 * growth) + bonus[id];
    }

    public void visited(int id, long now) {
        if (id < 0 || id >= rooms.size()) return;
        lastVisit[id] = now;
        bonus[id] = 0;
    }

    public void noise(int id, double amount) {
        if (id >= 0 && id < rooms.size()) bonus[id] += amount;
    }

    /**
     * @param exclude    不選的房間（例如現在所在的房間），-1 表示不排除
     * @param multiplier 進度倍率（0 表示不選）
     * @param seconds    走過去大約要幾秒
     * @return 選到的房間編號；全部不能選回傳 -1
     */
    public int choose(RandomGenerator rnd, long now, int exclude, ToDoubleFunction<RoomInfo> multiplier, ToDoubleFunction<RoomInfo> seconds) {
        double[] w = new double[rooms.size()];
        double total = 0;
        for (RoomInfo r : rooms) {
            if (r.id() == exclude) continue;
            double v = (score(r.id(), now) + 1) * Math.max(0, multiplier.applyAsDouble(r)) / (Math.max(0, seconds.applyAsDouble(r)) + 5);
            w[r.id()] = v;
            total += v;
        }
        if (total <= 0) return -1;
        double roll = rnd.nextDouble() * total;
        for (RoomInfo r : rooms) {
            roll -= w[r.id()];
            if (w[r.id()] > 0 && roll <= 0) return r.id();
        }
        for (int i = rooms.size() - 1; i >= 0; i--) if (w[i] > 0) return i;
        return -1;
    }
}
