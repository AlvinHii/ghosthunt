package com.ghosthunt.game.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HideoutOddsTest {

    @Test
    void inFrontOfGhost_atLeast80Percent() {
        assertTrue(HideoutOdds.chance(0.4, 1, 2, true, 0, 0) >= 0.8);
        assertEquals(0.95, HideoutOdds.chance(0.4, 1, 2, true, 0, 0), 1e-9);
    }

    @Test
    void farAway_atMost20Percent() {
        assertTrue(HideoutOdds.chance(0.4, 12, 2, true, 0, 0) <= 0.2);
        assertEquals(0.12, HideoutOdds.chance(0.4, 12, 2, true, 0, 0), 1e-9);
    }

    @Test
    void noisyAfter_noBonus() {
        assertEquals(0.4, HideoutOdds.chance(0.4, 1, 2, false, 0, 0), 1e-9);
    }

    @Test
    void lateSighting_noBonus() {
        assertEquals(0.4, HideoutOdds.chance(0.4, 1, 6, true, 0, 0), 1e-9);
    }

    @Test
    void particles_double() {
        assertEquals(0.8, HideoutOdds.chance(0.4, 5, 20, false, 0.5, 0), 1e-9);
    }

    @Test
    void learnBonus_multiplies() {
        assertEquals(0.6, HideoutOdds.chance(0.4, 5, 20, false, 0, 0.5), 1e-9);
    }

    @Test
    void capped() {
        assertEquals(0.95, HideoutOdds.chance(0.9, 1, 1, true, 0.5, 0), 1e-9);
    }
}
