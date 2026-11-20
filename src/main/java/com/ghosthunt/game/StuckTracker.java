package com.ghosthunt.game;

/**
 * 判斷 AI 鬼有沒有卡住：每 windowTicks 比一次位置，期間移動不到 minMove 格就算卡住。
 * 純邏輯，不碰伺服器。
 */
final class StuckTracker {

    private final int windowTicks;
    private final double minMove;
    private long anchorTick = Long.MIN_VALUE;
    private double ax, ay, az;

    StuckTracker(int windowTicks, double minMove) {
        this.windowTicks = windowTicks;
        this.minMove = minMove;
    }

    /** 每 tick（或每幾 tick）餵目前位置。剛好滿一個窗、而且移動太少時回傳 true。 */
    boolean update(long tick, double x, double y, double z) {
        if (anchorTick == Long.MIN_VALUE) {
            anchor(tick, x, y, z);
            return false;
        }
        if (tick - anchorTick < windowTicks) return false;
        double dx = x - ax, dy = y - ay, dz = z - az;
        boolean stuck = Math.sqrt(dx * dx + dy * dy + dz * dz) < minMove;
        anchor(tick, x, y, z);
        return stuck;
    }

    void reset() {
        anchorTick = Long.MIN_VALUE;
    }

    private void anchor(long tick, double x, double y, double z) {
        anchorTick = tick;
        ax = x;
        ay = y;
        az = z;
    }
}
