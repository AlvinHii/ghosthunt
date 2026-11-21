package com.ghosthunt.game.ai;

/**
 * 搜尋時的幾個小判斷（純函式）：什麼時候讓聲音贏過粒子推理、「之後很安靜」、失明／蒸氣時能不能用視線推理、追丟的判定。
 */
public final class SearchRules {

    private SearchRules() {}

    /** 聲音等級（跟 Match.NOISE_* 一樣）：0 低、1 中、2 高（刷卡、迎賓鈴）。 */
    public static final int NOISE_HIGH = 2;

    /**
     * 有粒子推理時，要不要繼續搜（把「最後看到」的時間推到現在，讓搜尋贏過聲音）：
     * 沒有比真的看到人更新的聲音 → 繼續；新的聲音是高等級（刷卡、迎賓鈴）→ 讓聲音贏；
     * 新的聲音附近（2σ 內）有 10% 以上的粒子 → 聲音已經把推理拉過去了，繼續；否則讓聲音贏。
     */
    public static boolean keepSearchFresh(long noiseTick, int noiseLevel, long trueSeenTick, double particleShareNearNoise) {
        if (noiseTick <= trueSeenTick) return true;
        if (noiseLevel >= NOISE_HIGH) return false;
        return particleShareNearNoise >= 0.1;
    }

    /** 參考時間之後沒有聲音（同一個 tick 的不算「之後」：最後一次看到人時聽到他的腳步很正常）。 */
    public static boolean quietSince(long lastNoiseTick, long refTick) {
        return lastNoiseTick <= refTick;
    }

    /** 能不能用視線推理（刪粒子、看痕跡）：失明中不行；鬼或那一格在溫泉蒸氣裡、又超過 2 格不行（跟「看到人」的規則一樣）。 */
    public static boolean sightUsable(long tick, long blindUntil, boolean ghostInSmoke, boolean cellInSmoke, double dist) {
        if (tick < blindUntil) return false;
        return !((ghostInSmoke || cellInSmoke) && dist > 2);
    }

    /** 追丟了：超過 1 秒（15 tick）沒真的看到，而且這次追丟還沒處理過（不管 think 剛好落在哪個 tick）。 */
    public static boolean lostTarget(long tick, long trueSeenTick, boolean alreadyHandled) {
        return !alreadyHandled && tick - trueSeenTick > 15;
    }
}
