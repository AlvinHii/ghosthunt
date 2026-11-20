package com.ghosthunt.game.ai;

/**
 * 感應的時機＋節奏控制（像《異形：孤立》的「導演」，純邏輯）。
 * <ul>
 *   <li>多久沒接觸才感應：quietTicks 內沒看到、沒聽到任何逃生者才感應一次，兩次感應至少隔 minGapTicks。
 *       剛追丟人不會馬上被感應；人多、聲音多時自然感應得少。</li>
 *   <li>壓力值：鬼在近距離看得到逃生者時每秒 +1，否則每秒 -0.5。到 pressureLimit（連續緊追太久）或剛抓到人時，
 *       進入 calmTicks 的冷靜期：不感應、巡邏偏向遠的地方，讓一局有緊有鬆。「追」本身不受影響。</li>
 * </ul>
 */
public final class SenseDirector {

    private final int quietTicks, minGapTicks, calmTicks;
    private final double pressureLimit;
    private long lastContact, lastSense = Long.MIN_VALUE / 2, calmUntil = Long.MIN_VALUE / 2;
    private double pressure;

    public SenseDirector(int quietTicks, int minGapTicks, double pressureLimit, int calmTicks) {
        this.quietTicks = quietTicks;
        this.minGapTicks = minGapTicks;
        this.pressureLimit = pressureLimit;
        this.calmTicks = calmTicks;
    }

    /** 看到或聽到逃生者。 */
    public void contact(long now) {
        lastContact = now;
    }

    /** 每隔 dtTicks 呼叫一次：nearVisible = 近距離看得到逃生者。 */
    public void pressure(long now, boolean nearVisible, int dtTicks) {
        pressure = Math.max(0, pressure + (nearVisible ? 1.0 : -0.5) * dtTicks / 20.0);
        if (pressure >= pressureLimit) {
            calmUntil = now + calmTicks;
            pressure = 0;
        }
    }

    public void captured(long now) {
        calmUntil = now + calmTicks;
        pressure = 0;
    }

    public boolean calm(long now) {
        return now < calmUntil;
    }

    public boolean shouldSense(long now) {
        return now - lastContact >= quietTicks && now - lastSense >= minGapTicks && !calm(now);
    }

    public void sensed(long now) {
        lastSense = now;
    }

    public double pressure() {
        return pressure;
    }
}
