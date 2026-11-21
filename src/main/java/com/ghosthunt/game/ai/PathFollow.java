package com.ghosthunt.game.ai;

/**
 * 沿路線走時的兩個小調整（純函式）：轉角提早轉、快到終點時減速。
 * 「沿線走、過門對準」的邏輯在 AiGhost，這裡只決定數字。
 */
public final class PathFollow {

    private PathFollow() {}

    /**
     * 瞄準點要在「自己在路線上的投影」往前幾格。
     * 下一個路點是轉角（轉超過 30 度）時拉長到 1.75 格，提早開始轉；靠近門、樓梯、障礙物、洞、地洞時維持 1 格。
     */
    public static double lookahead(double turnDegrees, boolean nearSpecial) {
        if (nearSpecial) return 1.0;
        return turnDegrees > 30 ? 1.75 : 1.0;
    }

    /** 同上；chasing（追人、攻擊）時一律 1：最後一個路點就是對方剛才的位置，減速等於在追擊最後一段變慢。 */
    public static double arrivalScale(double distToEnd, boolean lastWaypoint, boolean chasing) {
        return chasing ? 1.0 : arrivalScale(distToEnd, lastWaypoint);
    }

    /** 最後一個路點、離終點 2 格內：步長乘上這個倍率（最少 0.35），不衝過頭。 */
    public static double arrivalScale(double distToEnd, boolean lastWaypoint) {
        if (!lastWaypoint || distToEnd >= 2) return 1.0;
        return Math.max(0.35, distToEnd / 2);
    }
}
