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

    /**
     * 從實際位置 (ax, az) 走直線到 (bx, bz)，身體左右各 halfWidth 格掃過的格子都要能走（walkable(x, z)）。
     * 切轉角前用：要用鬼的實際位置，不是格子中心（鬼常偏離中線 0.3～0.5 格，用中心算會擦到桌角、木桶）。
     */
    public static boolean segmentClear(double ax, double az, double bx, double bz, double halfWidth,
                                       java.util.function.BiPredicate<Integer, Integer> walkable) {
        double len = Math.hypot(bx - ax, bz - az);
        double px = len < 1e-6 ? 0 : -(bz - az) / len * halfWidth, pz = len < 1e-6 ? 0 : (bx - ax) / len * halfWidth;
        for (double t = 0; t <= len + 1e-9; t += 0.2) {
            double cx = len < 1e-6 ? ax : ax + (bx - ax) * t / len, cz = len < 1e-6 ? az : az + (bz - az) * t / len;
            for (int s = -1; s <= 1; s++)
                if (!walkable.test((int) Math.floor(cx + px * s), (int) Math.floor(cz + pz * s))) return false;
        }
        return true;
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
