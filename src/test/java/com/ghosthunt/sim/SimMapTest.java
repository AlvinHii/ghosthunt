package com.ghosthunt.sim;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimMapTest {

    private static final Path HYDRO = Path.of("src/main/resources/pve-maps/hydro_plant.txt");

    @Test
    void loadsHydroPlant() throws Exception {
        SimMap m = SimMap.load(HYDRO);
        assertTrue(m.nav().size() > 1000, "格子數 " + m.nav().size());
        assertEquals(5, m.hideoutNodes().size());
        assertTrue(m.spawn() >= 0);
        assertTrue(m.ghostStart() >= 0);
        assertTrue(m.ghostReachable().test(m.spawn()), "出生點要在鬼走得到的主要區域");
        assertTrue(m.ghostReachable().test(m.ghostStart()), "鬼房門口外面要在主要區域");
    }

    @Test
    void hideoutsAreEscaperOnly() throws Exception {
        SimMap m = SimMap.load(HYDRO);
        for (int h : m.hideoutNodes()) assertTrue(!m.ghostReachable().test(h), "暗格裡面鬼走不到");
    }
}
