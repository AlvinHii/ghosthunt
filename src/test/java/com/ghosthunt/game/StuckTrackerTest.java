package com.ghosthunt.game;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StuckTrackerTest {

    @Test
    void stillForWindow_isStuck() {
        StuckTracker t = new StuckTracker(60, 1.0);
        boolean stuck = false;
        for (long tick = 0; tick <= 60; tick++) stuck |= t.update(tick, 0.2 * tick / 60.0, 65, 0);
        assertTrue(stuck);
    }

    @Test
    void moving_isNotStuck() {
        StuckTracker t = new StuckTracker(60, 1.0);
        boolean stuck = false;
        for (long tick = 0; tick <= 200; tick++) stuck |= t.update(tick, 0.2 * tick, 65, 0);
        assertFalse(stuck);
    }

    @Test
    void beforeWindowEnds_isNotStuck() {
        StuckTracker t = new StuckTracker(60, 1.0);
        boolean stuck = false;
        for (long tick = 0; tick < 59; tick++) stuck |= t.update(tick, 0, 65, 0);
        assertFalse(stuck);
    }

    @Test
    void reset_clears() {
        StuckTracker t = new StuckTracker(60, 1.0);
        for (long tick = 0; tick < 50; tick++) t.update(tick, 0, 65, 0);
        t.reset();
        boolean stuck = false;
        for (long tick = 50; tick < 100; tick++) stuck |= t.update(tick, 0, 65, 0);
        assertFalse(stuck); // reset 之後要重新累積滿一個窗
    }

    @Test
    void slowCommanded_isNotStuck() {
        StuckTracker t = new StuckTracker(60, 1.0);
        boolean stuck = false;
        for (long tick = 0; tick <= 200; tick++) stuck |= t.update(tick, 0.01 * tick, 65, 0, 0.02); // 疲勞時只叫它走一點點
        assertFalse(stuck);
    }

    @Test
    void blockedWhileCommanded_isStuck() {
        StuckTracker t = new StuckTracker(60, 1.0);
        boolean stuck = false;
        for (long tick = 0; tick <= 61; tick++) stuck |= t.update(tick, 0, 65, 0, 0.2);
        assertTrue(stuck);
    }

    @Test
    void gapInUpdates_reanchors() {
        StuckTracker t = new StuckTracker(60, 1.0);
        for (long tick = 0; tick < 10; tick++) t.update(tick, 0, 65, 0, 0.2);
        // 去做別的事 100 tick，回來剛好在原地附近：不能拿 100 tick 前的位置比
        boolean stuck = false;
        for (long tick = 110; tick < 130; tick++) stuck |= t.update(tick, 0.3 + 0.2 * (tick - 110), 65, 0, 0.2);
        assertFalse(stuck);
    }
}
