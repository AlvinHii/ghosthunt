package com.ghosthunt.game.ai;

import com.ghosthunt.game.ai.GhostBrain.Goal;
import com.ghosthunt.game.ai.GhostBrain.Kind;
import com.ghosthunt.game.ai.GhostBrain.Point;
import com.ghosthunt.game.ai.GhostBrain.Seen;
import com.ghosthunt.game.ai.GhostBrain.Situation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class GhostBrainTest {

    private static final long NOW = 10_000;
    private static final Point HERE = new Point(0, 65, 0);
    private static final UUID VICTIM = UUID.randomUUID();

    private final GhostBrain brain = new GhostBrain(new GhostBrain.Config(2.5, 200, 200, 0.5), new Random(1));

    /** 什麼都沒有的情況，測試只改需要的欄位。 */
    private static Situation empty() {
        return new Situation(NOW, false, null, null, -1_000_000, null, -1_000_000, null,
                false, false, false, false, List.of(), false, null, HERE);
    }

    private static Situation with(Situation s, Seen seen) {
        return new Situation(s.tick(), s.blinded(), seen, s.lastSeen(), s.lastSeenTick(), s.noise(), s.noiseTick(), s.sensed(),
                s.cardRevealOpen(), s.cardMoveAvailable(), s.powerOn(), s.cutPowerUsed(), s.sabotageableGates(),
                s.someoneJailed(), s.jail(), s.here());
    }

    @Test
    void seenWithinRange_attacks() {
        Goal g = brain.decide(with(empty(), new Seen(VICTIM, new Point(2, 65, 0), 2.0)));
        assertEquals(Kind.ATTACK, g.kind());
        assertEquals(VICTIM, g.victim());
    }

    @Test
    void seenFar_chases() {
        Point at = new Point(10, 65, 0);
        Goal g = brain.decide(with(empty(), new Seen(VICTIM, at, 10)));
        assertEquals(Kind.CHASE, g.kind());
        assertEquals(at, g.target());
    }

    @Test
    void blinded_ignoresSeen() {
        Situation s = with(empty(), new Seen(VICTIM, new Point(2, 65, 0), 2.0));
        s = new Situation(s.tick(), true, s.seen(), null, -1_000_000, null, -1_000_000, null,
                false, false, false, false, List.of(), false, null, HERE);
        Kind k = brain.decide(s).kind();
        assertNotEquals(Kind.ATTACK, k);
        assertNotEquals(Kind.CHASE, k);
    }

    @Test
    void lostTarget_searchesLastSeen_withinSearchTicks() {
        Point last = new Point(5, 65, 5);
        Situation recent = new Situation(NOW, false, null, last, NOW - 100, null, -1_000_000, null,
                false, false, false, false, List.of(), false, null, HERE);
        assertEquals(Kind.SEARCH_LAST_SEEN, brain.decide(recent).kind());
        assertEquals(last, brain.decide(recent).target());
        Situation old = new Situation(NOW, false, null, last, NOW - 300, null, -1_000_000, null,
                false, false, false, false, List.of(), false, null, HERE);
        assertNotEquals(Kind.SEARCH_LAST_SEEN, brain.decide(old).kind());
    }

    @Test
    void noise_isInvestigated() {
        Point noise = new Point(8, 65, -3);
        Situation s = new Situation(NOW, false, null, null, -1_000_000, noise, NOW - 50, null,
                false, false, false, false, List.of(), false, null, HERE);
        Goal g = brain.decide(s);
        assertEquals(Kind.INVESTIGATE_NOISE, g.kind());
        assertEquals(noise, g.target());
    }

    @Test
    void sensed_isFollowed_whenNothingElse() {
        Point sensed = new Point(-20, 65, 4);
        Situation s = new Situation(NOW, false, null, null, -1_000_000, null, -1_000_000, sensed,
                false, false, false, false, List.of(), false, null, HERE);
        Goal g = brain.decide(s);
        assertEquals(Kind.GO_SENSED, g.kind());
        assertEquals(sensed, g.target());
    }

    @Test
    void cardMove_beatsPatrol() {
        Situation s = new Situation(NOW, false, null, null, -1_000_000, null, -1_000_000, null,
                true, true, false, false, List.of(), false, null, HERE);
        assertEquals(Kind.MOVE_CARD, brain.decide(s).kind());
    }

    @Test
    void gateSabotage_beatsCutPower() {
        Point gate = new Point(30, 65, 30);
        Situation s = new Situation(NOW, false, null, null, -1_000_000, null, -1_000_000, null,
                false, false, true, false, List.of(gate), false, null, HERE);
        Goal g = brain.decide(s);
        assertEquals(Kind.SABOTAGE_GATE, g.kind());
        assertEquals(gate, g.target());
    }

    @Test
    void cutPower_whenPowerOnAndUnused() {
        Situation s = new Situation(NOW, false, null, null, -1_000_000, null, -1_000_000, null,
                false, false, true, false, List.of(), false, null, HERE);
        assertEquals(Kind.CUT_POWER, brain.decide(s).kind());
    }

    @Test
    void cutPower_notTwice() {
        Situation s = new Situation(NOW, false, null, null, -1_000_000, null, -1_000_000, null,
                false, false, true, true, List.of(), false, null, HERE);
        assertNotEquals(Kind.CUT_POWER, brain.decide(s).kind());
    }

    @Test
    void jailed_guardsJail_whenChanceHits() {
        Point jail = new Point(40, 65, 10);
        GhostBrain always = new GhostBrain(new GhostBrain.Config(2.5, 200, 200, 1.0), new Random(1));
        Situation s = new Situation(NOW, false, null, null, -1_000_000, null, -1_000_000, null,
                false, false, false, false, List.of(), true, jail, HERE);
        Goal g = always.decide(s);
        assertEquals(Kind.GUARD_JAIL, g.kind());
        assertEquals(jail, g.target());
        GhostBrain never = new GhostBrain(new GhostBrain.Config(2.5, 200, 200, 0.0), new Random(1));
        assertEquals(Kind.PATROL, never.decide(s).kind());
    }

    @Test
    void nothing_patrols() {
        assertEquals(Kind.PATROL, brain.decide(empty()).kind());
    }

    @Test
    void attack_beatsEverythingElse() {
        Situation s = new Situation(NOW, false, new Seen(VICTIM, new Point(1, 65, 0), 1.5), new Point(5, 65, 5), NOW - 10,
                new Point(8, 65, 8), NOW - 10, new Point(9, 65, 9), true, true, true, false, List.of(new Point(3, 65, 3)),
                true, new Point(4, 65, 4), HERE);
        assertEquals(Kind.ATTACK, brain.decide(s).kind());
    }

    @Test
    void gate_beatsNoiseSensedAndLastSeen() {
        // 大門倒數只有 25 秒：就算有聲音、感應、剛追丟的位置，也先去弄壞大門（只輸給看得到人）
        Point gate = new Point(3, 65, 3);
        Situation s = new Situation(NOW, false, null, new Point(5, 65, 5), NOW - 10, new Point(8, 65, -3), NOW - 10, new Point(-20, 65, 4),
                true, true, true, false, List.of(gate), false, null, HERE);
        assertEquals(Kind.SABOTAGE_GATE, brain.decide(s).kind());
    }

    @Test
    void noise_beatsSensed_andCard() {
        Point noise = new Point(8, 65, -3);
        Situation s = new Situation(NOW, false, null, null, -1_000_000, noise, NOW - 10, new Point(-20, 65, 4),
                true, true, true, false, List.of(), false, null, HERE);
        assertEquals(Kind.INVESTIGATE_NOISE, brain.decide(s).kind());
    }

    @Test
    void newerNoise_beatsOlderLastSeen() {
        // 9 秒前看到人在 A，1 秒前聽到 B 有聲音：去 B
        Point noise = new Point(8, 65, -3);
        Situation s = new Situation(NOW, false, null, new Point(5, 65, 5), NOW - 180, noise, NOW - 20, null,
                false, false, false, false, List.of(), false, null, HERE);
        assertEquals(Kind.INVESTIGATE_NOISE, brain.decide(s).kind());
    }

    @Test
    void newerLastSeen_beatsOlderNoise() {
        Situation s = new Situation(NOW, false, null, new Point(5, 65, 5), NOW - 20, new Point(8, 65, -3), NOW - 180, null,
                false, false, false, false, List.of(), false, null, HERE);
        assertEquals(Kind.SEARCH_LAST_SEEN, brain.decide(s).kind());
    }

    @Test
    void priorityOrder_isData() {
        assertEquals(List.of(Kind.ATTACK, Kind.CHASE, Kind.SABOTAGE_GATE, Kind.SEARCH_LAST_SEEN, Kind.INVESTIGATE_NOISE, Kind.GO_SENSED,
                Kind.MOVE_CARD, Kind.CUT_POWER, Kind.GUARD_READER, Kind.GUARD_JAIL, Kind.AMBUSH, Kind.PATROL), brain.order());
    }

    // ---- 第三版第 2 批：埋伏、守讀卡機 ----

    private static Situation calm(Point ambush, Point reader, boolean jailed) {
        return new Situation(NOW, false, null, null, -1_000_000, null, -1_000_000, null,
                false, false, false, false, List.of(), jailed, jailed ? new Point(5, 65, 5) : null, HERE, ambush, reader);
    }

    @Test
    void ambush_whenPointGiven_beforePatrol() {
        Goal g = brain.decide(calm(new Point(3, 65, 3), null, false));
        assertEquals(Kind.AMBUSH, g.kind());
        assertEquals(new Point(3, 65, 3), g.target());
    }

    @Test
    void noise_beatsAmbush() {
        Situation s = calm(new Point(3, 65, 3), null, false);
        s = new Situation(s.tick(), false, null, null, -1_000_000, new Point(9, 65, 9), NOW - 10, null,
                false, false, false, false, List.of(), false, null, HERE, s.ambush(), null);
        assertEquals(Kind.INVESTIGATE_NOISE, brain.decide(s).kind());
    }

    @Test
    void guardReader_whenSomeoneAuthorized() {
        Goal g = brain.decide(calm(null, new Point(7, 65, 7), false));
        assertEquals(Kind.GUARD_READER, g.kind());
    }

    // ---- 第三版第 3 批：Utility（個性＝標準時要跟第二版一樣） ----

    /** 第二版每個測試情境（不含機率的守監獄）。 */
    private static List<Situation> v2Cases() {
        Point gate = new Point(30, 65, 30);
        return List.of(
                with(empty(), new Seen(VICTIM, new Point(2, 65, 0), 2.0)),
                with(empty(), new Seen(VICTIM, new Point(10, 65, 0), 10)),
                new Situation(NOW, false, null, new Point(5, 65, 5), NOW - 100, null, -1_000_000, null,
                        false, false, false, false, List.of(), false, null, HERE),
                new Situation(NOW, false, null, null, -1_000_000, new Point(8, 65, -3), NOW - 50, null,
                        false, false, false, false, List.of(), false, null, HERE),
                new Situation(NOW, false, null, null, -1_000_000, null, -1_000_000, new Point(-20, 65, 4),
                        false, false, false, false, List.of(), false, null, HERE),
                new Situation(NOW, false, null, null, -1_000_000, null, -1_000_000, null,
                        true, true, false, false, List.of(), false, null, HERE),
                new Situation(NOW, false, null, null, -1_000_000, null, -1_000_000, null,
                        false, false, true, false, List.of(gate), false, null, HERE),
                new Situation(NOW, false, null, null, -1_000_000, null, -1_000_000, null,
                        false, false, true, false, List.of(), false, null, HERE),
                empty(),
                new Situation(NOW, false, new Seen(VICTIM, new Point(1, 65, 0), 1.5), new Point(5, 65, 5), NOW - 10,
                        new Point(8, 65, 8), NOW - 10, new Point(9, 65, 9), true, true, true, false, List.of(new Point(3, 65, 3)),
                        true, new Point(4, 65, 4), HERE),
                new Situation(NOW, false, null, new Point(5, 65, 5), NOW - 10, new Point(8, 65, -3), NOW - 10, new Point(-20, 65, 4),
                        true, true, true, false, List.of(new Point(3, 65, 3)), false, null, HERE),
                new Situation(NOW, false, null, new Point(5, 65, 5), NOW - 180, new Point(8, 65, -3), NOW - 20, null,
                        false, false, false, false, List.of(), false, null, HERE),
                new Situation(NOW, false, null, new Point(5, 65, 5), NOW - 20, new Point(8, 65, -3), NOW - 180, null,
                        false, false, false, false, List.of(), false, null, HERE),
                calm(new Point(3, 65, 3), null, false),
                calm(null, new Point(7, 65, 7), false));
    }

    @Test
    void utility_standardPersona_matchesV2Order() {
        for (Situation s : v2Cases()) {
            GhostBrain u = new GhostBrain(new GhostBrain.Config(2.5, 200, 200, 0.5), new Random(1));
            assertEquals(brain.decide(s).kind(), u.decideUtility(s, GhostBrain.Weights.STANDARD, true).kind(), s.toString());
        }
    }

    @Test
    void utility_personaWeight_canTipCloseCall() {
        // 遠處看到人（24 格）而大門在倒數：標準的鬼平手時選順序前面的（追），把追的權重壓低就去弄壞大門
        Situation s = new Situation(NOW, false, new Seen(VICTIM, new Point(24, 65, 0), 24), null, -1_000_000, null, -1_000_000, null,
                false, false, false, false, List.of(new Point(3, 65, 3)), false, null, HERE);
        GhostBrain u = new GhostBrain(new GhostBrain.Config(2.5, 200, 200, 0.5), new Random(1));
        assertEquals(Kind.CHASE, u.decideUtility(s, GhostBrain.Weights.STANDARD, true).kind());
        GhostBrain v = new GhostBrain(new GhostBrain.Config(2.5, 200, 200, 0.5), new Random(1));
        assertEquals(Kind.SABOTAGE_GATE, v.decideUtility(s, GhostBrain.Weights.of(java.util.Map.of(Kind.CHASE, 0.9)), true).kind());
    }

    @Test
    void utility_holdsGoalAtLeast1_5s_exceptChase() {
        GhostBrain u = new GhostBrain(new GhostBrain.Config(2.5, 200, 200, 0.5), new Random(1));
        Situation patrol = empty();
        assertEquals(Kind.PATROL, u.decideUtility(patrol, GhostBrain.Weights.STANDARD, true).kind());
        // 10 tick 後出現埋伏點：還在 1.5 秒內，繼續巡邏
        Situation amb = new Situation(NOW + 10, false, null, null, -1_000_000, null, -1_000_000, null,
                false, false, false, false, List.of(), false, null, HERE, new Point(3, 65, 3), null);
        assertEquals(Kind.PATROL, u.decideUtility(amb, GhostBrain.Weights.STANDARD, true).kind());
        // 但看到人就馬上追
        Situation seen = new Situation(NOW + 12, false, new Seen(VICTIM, new Point(9, 65, 0), 9), null, -1_000_000, null, -1_000_000, null,
                false, false, false, false, List.of(), false, null, HERE);
        assertEquals(Kind.CHASE, u.decideUtility(seen, GhostBrain.Weights.STANDARD, true).kind());
        // 過了 1.5 秒：換成埋伏
        Situation later = new Situation(NOW + 40, false, null, null, -1_000_000, null, -1_000_000, null,
                false, false, false, false, List.of(), false, null, HERE, new Point(3, 65, 3), null);
        GhostBrain w = new GhostBrain(new GhostBrain.Config(2.5, 200, 200, 0.5), new Random(1));
        w.decideUtility(patrol, GhostBrain.Weights.STANDARD, true);
        assertEquals(Kind.AMBUSH, w.decideUtility(later, GhostBrain.Weights.STANDARD, true).kind());
    }

    @Test
    void utility_scoresListed_forDebug() {
        GhostBrain u = new GhostBrain(new GhostBrain.Config(2.5, 200, 200, 0.5), new Random(1));
        u.decideUtility(new Situation(NOW, false, null, null, -1_000_000, new Point(8, 65, -3), NOW - 50, null,
                false, false, true, false, List.of(), false, null, HERE), GhostBrain.Weights.STANDARD, true);
        List<GhostBrain.Scored> top = u.lastScores();
        assertEquals(Kind.INVESTIGATE_NOISE, top.get(0).kind());
        assertEquals(Kind.CUT_POWER, top.get(1).kind());
        assertEquals(Kind.PATROL, top.get(top.size() - 1).kind());
    }
}
