package com.ghosthunt.game.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PathFollowTest {

    @Test
    void straight_isOne() {
        assertEquals(1.0, PathFollow.lookahead(10, false), 1e-9);
    }

    @Test
    void corner_looksFurther() {
        assertEquals(1.75, PathFollow.lookahead(45, false), 1e-9);
    }

    @Test
    void door_staysOne() {
        assertEquals(1.0, PathFollow.lookahead(90, true), 1e-9);
    }

    @Test
    void arrival_slowsNearEnd() {
        assertEquals(0.5, PathFollow.arrivalScale(1.0, true), 1e-9);
        assertEquals(0.35, PathFollow.arrivalScale(0.2, true), 1e-9);
        assertEquals(1.0, PathFollow.arrivalScale(3.0, true), 1e-9);
    }

    @Test
    void chasing_noSlow() {
        assertEquals(1.0, PathFollow.arrivalScale(0.5, true, true), 1e-9);
        assertEquals(0.5, PathFollow.arrivalScale(1.0, true, false), 1e-9);
    }

    @Test
    void notLast_noSlow() {
        assertEquals(1.0, PathFollow.arrivalScale(0.5, false), 1e-9);
    }
}
