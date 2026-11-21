package com.ghosthunt.game.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchRulesTest {

    // ---- C1：有粒子時什麼情況繼續搜、什麼情況讓聲音贏 ----

    @Test
    void noNewerNoise_keepsSearching() {
        assertTrue(SearchRules.keepSearchFresh(100, 0, 200, 0));
    }

    @Test
    void newerNoiseFarFromParticles_letsNoiseWin() {
        assertFalse(SearchRules.keepSearchFresh(300, 1, 200, 0.02));
    }

    @Test
    void newerNoiseNearParticles_keepsSearching() {
        assertTrue(SearchRules.keepSearchFresh(300, 0, 200, 0.4));
    }

    @Test
    void lure_alwaysWins() {
        // 迎賓鈴（高等級聲音）一律讓聲音贏，就算剛好在粒子附近
        assertFalse(SearchRules.keepSearchFresh(300, 2, 200, 0.9));
    }

    // ---- I5：之後有沒有聲音（同一個 tick 的不算「之後」）----

    @Test
    void sameTickNoise_isStillQuiet() {
        assertTrue(SearchRules.quietSince(200, 200));
    }

    @Test
    void laterNoise_isNotQuiet() {
        assertFalse(SearchRules.quietSince(205, 200));
    }

    // ---- I2：失明、蒸氣時不能用視線推理 ----

    @Test
    void blinded_cannotUseSight() {
        assertFalse(SearchRules.sightUsable(100, 120, false, false, 10));
        assertTrue(SearchRules.sightUsable(130, 120, false, false, 10));
    }

    @Test
    void smoke_blocksBeyondTwoBlocks() {
        assertFalse(SearchRules.sightUsable(100, 0, true, false, 5));
        assertFalse(SearchRules.sightUsable(100, 0, false, true, 5));
        assertTrue(SearchRules.sightUsable(100, 0, true, true, 1.5));
    }

    // ---- I6：追丟的判定不依賴 think 剛好落在哪個 tick ----

    @Test
    void lostAfterOneSecond_onlyOnce() {
        assertFalse(SearchRules.lostTarget(110, 100, false));
        assertTrue(SearchRules.lostTarget(130, 100, false));
        assertTrue(SearchRules.lostTarget(500, 100, false)); // 很久以後才 think 也算
        assertFalse(SearchRules.lostTarget(130, 100, true)); // 這次追丟已經處理過
    }
}
