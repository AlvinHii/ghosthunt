package com.ghosthunt.game;

/**
 * 判斷 AI 鬼有沒有卡住：每 windowTicks 比一次位置，期間移動不到 minMove 格就算卡住。
 * 純邏輯，不碰伺服器。
 */
final class StuckTracker {

    private final int windowTicks;
    private final double minMove;
    private long anchorTick = Long.MIN_VALUE, lastTick = Long.MIN_VALUE;
    private double ax, ay, az, commanded;

    StuckTracker(int windowTicks, double minMove) {
        this.windowTicks = windowTicks;
        this.minMove = minMove;
    }

    /** 每 tick 餵目前位置（不知道指令走多遠時當成一直在全速走）。 */
    boolean update(long tick, double x, double y, double z) {
        return update(tick, x, y, z, 1.0);
    }

    /**
     * 每 tick 餵目前位置和這個 tick 叫它走多遠（commandedStep）。剛好滿一個窗、
     * 而且「叫它走的距離」夠長（≥ 2 倍 minMove）但實際移動太少時回傳 true。
     * 減速（揮拳疲勞、慢慢靠近目標）時叫它走的距離本來就短，不算卡住；
     * 中間有一段沒餵（在做別的事），重新起算，不會拿很久以前的位置來比。
     */
    boolean update(long tick, double x, double y, double z, double commandedStep) {
        if (anchorTick == Long.MIN_VALUE || tick - lastTick > 5) {
            anchor(tick, x, y, z);
            lastTick = tick;
            return false;
        }
        lastTick = tick;
        commanded += commandedStep;
        if (tick - anchorTick < windowTicks) return false;
        double dx = x - ax, dy = y - ay, dz = z - az;
        boolean stuck = Math.sqrt(dx * dx + dy * dy + dz * dz) < minMove && commanded >= minMove * 2;
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
        commanded = 0;
    }
}
