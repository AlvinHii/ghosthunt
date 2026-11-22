package com.ghosthunt.game.ai;

/**
 * 逃生者的心跳（純邏輯）：鬼越近，心跳越快、越大聲、音調越高。距離是「鬼走過來要走的路」（沿路距離），
 * 隔一道牆但要繞很遠的鬼不會讓你心跳狂跳。超過 range 沒有心跳。
 *
 * @param range     幾格內有心跳
 * @param minBpm    range 邊緣的每分鐘幾下
 * @param maxBpm    貼臉時的每分鐘幾下
 * @param minVolume range 邊緣的音量
 * @param maxVolume 貼臉時的音量
 * @param closePitch 貼臉時的音調（邊緣是 1.0）
 */
public record Heartbeat(double range, double minBpm, double maxBpm, double minVolume, double maxVolume, double closePitch) {

    /** 0（邊緣）～1（貼臉）；超過範圍是負的。 */
    private double closeness(double d) {
        return 1 - d / range;
    }

    public double bpm(double d) {
        return minBpm + (maxBpm - minBpm) * Math.max(0, closeness(d));
    }

    /** 兩下之間幾 tick；超過範圍回傳 -1（沒有心跳）。 */
    public int intervalTicks(double d) {
        if (Double.isNaN(d) || d > range) return -1;
        return (int) Math.round(1200.0 / bpm(d));
    }

    public double volume(double d) {
        return minVolume + (maxVolume - minVolume) * Math.max(0, closeness(d));
    }

    public double pitch(double d) {
        return 1 + (closePitch - 1) * Math.max(0, closeness(d));
    }
}
