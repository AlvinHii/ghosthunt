package com.ghosthunt.game.ai;

/**
 * 證據式查暗格、跟地洞（純函式）：機率 = 難度基礎 × 證據倍率，上限 95%。
 * 在鬼眼前鑽進去、之後沒聲音 → 幾乎一定被查；離很遠的暗格很少被查。
 */
public final class HideoutOdds {

    private HideoutOdds() {}

    /**
     * @param base             難度基礎機率（check-hideout-base／follow-tunnel-chance）
     * @param distToEntrance   最後看到的位置離入口幾格
     * @param secondsSinceSeen 最後看到到現在幾秒
     * @param quietSince       最後看到之後沒有聽到腳步、開門或其他聲音
     * @param particleShare    粒子在這個暗格（或地洞另一端）的比例
     * @param learnBonus       學習加成（第三版第 3 批才有，現在傳 0）
     */
    public static double chance(double base, double distToEntrance, double secondsSinceSeen, boolean quietSince,
                                double particleShare, double learnBonus) {
        double p = base;
        if (distToEntrance <= 3 && secondsSinceSeen <= 5 && quietSince) p *= 2.5;
        if (particleShare >= 0.3) p *= 2;
        if (distToEntrance > 8) p *= 0.3;
        p *= 1 + learnBonus;
        return Math.min(0.95, p);
    }
}
