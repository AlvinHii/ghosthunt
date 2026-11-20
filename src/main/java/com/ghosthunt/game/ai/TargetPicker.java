package com.ghosthunt.game.ai;

import java.util.List;
import java.util.UUID;

/**
 * 選要追的人（純邏輯）。
 * <ul>
 *   <li>黏性：正在追 A 時，只有 B 的有效距離不到 A 的 switchRatio 倍才換。</li>
 *   <li>A 剛看不到（graceTicks 內）還是繼續追 A，不換人。</li>
 *   <li>只用看得到的資訊加權（不看背包）：正在做事（授權、刷卡、開電源……）的人有效距離減 busyBonus；
 *       剛被救出來的人加 rescuedPenalty（不要一直盯同一個人）。</li>
 * </ul>
 */
public final class TargetPicker {

    /** 一個看得到的逃生者：distance 是距離，busy 是正在做事，justRescued 是剛被救出來。 */
    public record Candidate(UUID id, double distance, boolean busy, boolean justRescued) {}

    private final double switchRatio;
    private final int graceTicks;
    private final double busyBonus, rescuedPenalty;

    public TargetPicker(double switchRatio, int graceTicks, double busyBonus, double rescuedPenalty) {
        this.switchRatio = switchRatio;
        this.graceTicks = graceTicks;
        this.busyBonus = busyBonus;
        this.rescuedPenalty = rescuedPenalty;
    }

    private double effective(Candidate c) {
        return Math.max(0.5, c.distance() - (c.busy() ? busyBonus : 0) + (c.justRescued() ? rescuedPenalty : 0));
    }

    /**
     * @param current         正在追的人（沒有是 null）
     * @param currentSeenTick 最後一次看到 current 的 tick
     * @param visible         現在看得到的人
     * @return 要追的人；current 剛看不到時回傳 current（繼續追）；沒有人可追回傳 null
     */
    public UUID pick(UUID current, long currentSeenTick, long now, List<Candidate> visible) {
        Candidate best = null, cur = null;
        for (Candidate c : visible) {
            if (c.id().equals(current)) cur = c;
            if (best == null || effective(c) < effective(best)) best = c;
        }
        if (cur != null) {
            if (best != null && best != cur && effective(best) < effective(cur) * switchRatio) return best.id();
            return cur.id();
        }
        if (current != null && now - currentSeenTick <= graceTicks) return current;
        return best == null ? null : best.id();
    }
}
