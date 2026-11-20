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
}
