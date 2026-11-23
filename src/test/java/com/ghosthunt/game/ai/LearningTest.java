package com.ghosthunt.game.ai;

import com.ghosthunt.game.ai.GhostBrain.Kind;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 第三版第 18～22 項：個性、記憶、熱點、路線、拉桿。 */
class LearningTest {

    @Test
    void bonus_zeroUntilFirstEscape_thenGrowsToCap() {
        AiMemory m = new AiMemory();
        assertEquals(0, m.bonus(AiMemory.Trick.HIDEOUT, 0.35), 1e-9);
        m.escaped(AiMemory.Trick.HIDEOUT);
        assertEquals(0.175, m.bonus(AiMemory.Trick.HIDEOUT, 0.35), 1e-9);
        m.escaped(AiMemory.Trick.HIDEOUT);
        assertEquals(0.2625, m.bonus(AiMemory.Trick.HIDEOUT, 0.35), 1e-9);
    }

    @Test
    void endMatch_decaysOldHabits() {
        AiMemory m = new AiMemory();
        m.escaped(AiMemory.Trick.HATCH);
        m.endMatch(0.85);
        assertEquals(0.85, m.escapes(AiMemory.Trick.HATCH), 1e-9);
        assertEquals(1, m.matches());
    }

    @Test
    void habits_growDecayAndRoundTrip() {
        AiMemory m = new AiMemory();
        assertEquals(0, m.habit("monitor", 1), 1e-9);
        m.used("monitor");
        assertEquals(0.5, m.habit("monitor", 1), 1e-9);
        m.used("monitor");
        assertEquals(0.75, m.habit("monitor", 1), 1e-9);
        m.endMatch(0.5);
        assertEquals(1.0, m.uses("monitor"), 1e-9);
        AiMemory back = AiMemory.fromFlat(m.toFlat());
        assertEquals(1.0, back.uses("monitor"), 1e-9);
        assertEquals(0, back.uses("sewer"), 1e-9);
    }

    @Test
    void flat_roundTrip() {
        AiMemory m = new AiMemory();
        m.escaped(AiMemory.Trick.LURE);
        m.heat.see(-12, 65, 40, 1);
        m.routes.transition("1_65_2", "3_65_4");
        PersonaBandit.update(m.bandit, "hound", 0.8);
        Map<String, Object> flat = m.toFlat();
        for (String k : flat.keySet()) assertTrue(!k.contains(" "), k);
        AiMemory back = AiMemory.fromFlat(flat);
        assertEquals(1, back.escapes(AiMemory.Trick.LURE), 1e-9);
        assertEquals(1, back.heat.at(-12, 65, 40), 1e-9);
        assertEquals(1, back.routes.count("1_65_2", "3_65_4"), 1e-9);
        assertEquals(1.8, back.bandit.get("hound")[0], 1e-9);
        assertEquals(1.2, back.bandit.get("hound")[1], 1e-9);
    }

    @Test
    void hotspot_spreadsAndFades() {
        Hotspots h = new Hotspots();
        h.see(0, 65, 0, 1);
        assertEquals(1, h.at(0, 65, 0), 1e-9);
        assertTrue(h.at(2, 65, 0) > 0 && h.at(2, 65, 0) < 1);
        assertEquals(0, h.at(4, 65, 0), 1e-9);
        assertEquals(0, h.at(0, 66, 0), 1e-9);
        h.decay(0.01);
        assertEquals(0, h.size());
    }

    @Test
    void route_laplaceSmoothed() {
        RouteModel r = new RouteModel();
        List<String> opts = List.of("B", "C");
        assertEquals(0.5, r.probability("A", "B", opts), 1e-9);
        r.transition("A", "B");
        r.transition("A", "B");
        assertEquals(0.75, r.probability("A", "B", opts), 1e-9);
        assertEquals(0.25, r.probability("A", "C", opts), 1e-9);
    }

    @Test
    void bandit_prefersArmThatWorked() {
        Map<String, double[]> ab = new HashMap<>();
        for (int i = 0; i < 30; i++) {
            PersonaBandit.update(ab, "watcher", 1);
            PersonaBandit.update(ab, "hound", 0);
            PersonaBandit.update(ab, "ambusher", 0);
        }
        Random rnd = new Random(3);
        int watcher = 0;
        for (int i = 0; i < 200; i++) if (PersonaBandit.choose(ab, GhostPersona.arms(), rnd).equals("watcher")) watcher++;
        assertTrue(watcher > 190, "watcher " + watcher);
    }

    @Test
    void bandit_withoutData_triesAll() {
        Random rnd = new Random(5);
        Map<String, Integer> n = new HashMap<>();
        for (int i = 0; i < 300; i++) n.merge(PersonaBandit.choose(new HashMap<>(), GhostPersona.arms(), rnd), 1, Integer::sum);
        for (String a : GhostPersona.arms()) assertTrue(n.getOrDefault(a, 0) > 60, a + " " + n);
    }

    @Test
    void closeMatchScore() {
        assertEquals(1, PersonaBandit.score(0.5, 0.5), 1e-9);
        assertEquals(0.5, PersonaBandit.score(1.0, 0.5), 1e-9);
        assertEquals(0.3, PersonaBandit.target("hard"), 1e-9);
    }

    @Test
    void persona_overrides() {
        GhostPersona h = GhostPersona.builtIn().get("hound").with(Map.of("chase", 1.5, "particle-age", 0.5));
        assertEquals(1.5, h.weights().of(Kind.CHASE), 1e-9);
        assertEquals(0.5, h.particleAgeMul(), 1e-9);
        assertEquals(1.0, GhostPersona.STANDARD.weights().of(Kind.PATROL), 1e-9);
    }
}
