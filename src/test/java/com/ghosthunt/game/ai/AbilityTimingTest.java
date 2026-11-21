package com.ghosthunt.game.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AbilityTimingTest {

    @Test
    void everyoneFarFromLever_worthCutting() {
        // 大家要 40 秒才走得回電源，鬼 10 秒到 → 值得關
        assertTrue(AbilityTiming.shouldCutPower(new double[]{40, 45}, 10, 15));
    }

    @Test
    void someoneNearLever_notWorth() {
        assertFalse(AbilityTiming.shouldCutPower(new double[]{5, 12}, 10, 15));
    }

    @Test
    void noInfo_usesDefault() {
        // 完全不知道人在哪：當成 30 秒（跟第二版「開了 20 秒就去關」差不多積極）
        assertTrue(AbilityTiming.shouldCutPower(new double[0], 10, 15));
        assertFalse(AbilityTiming.shouldCutPower(new double[0], 20, 15));
    }
}
