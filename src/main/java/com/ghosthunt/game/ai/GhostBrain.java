package com.ghosthunt.game.ai;

import java.util.List;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * AI 鬼的大腦：看現在的情況，決定下一步要做什麼。純邏輯，不碰伺服器（可以單元測試）。
 * 優先順序：攻擊 ＞ 追人 ＞ 弄壞大門 ＞ 找最後看到的位置 ＞ 去聽到聲音的地方（比較新的情報優先）＞ 去感應到的位置
 * ＞ 搬卡 ＞ 關電源 ＞ 守監獄（機率）＞ 巡邏。
 */
public final class GhostBrain {

    public record Point(double x, double y, double z) {}

    /** 看得到的最近逃生者。 */
    public record Seen(UUID id, Point at, double distance) {}

    /**
     * 現在的情況。沒有的東西用 null（seen、lastSeen、noise、sensed、jail）。
     * blinded：被煙霧彈打到，暫時看不到人。
     */
    public record Situation(long tick, boolean blinded, Seen seen, Point lastSeen, long lastSeenTick,
                            Point noise, long noiseTick, Point sensed,
                            boolean cardRevealOpen, boolean cardMoveAvailable,
                            boolean powerOn, boolean cutPowerUsed, List<Point> sabotageableGates,
                            boolean someoneJailed, Point jail, Point here) {}

    public enum Kind { CHASE, ATTACK, SEARCH_LAST_SEEN, INVESTIGATE_NOISE, GO_SENSED, MOVE_CARD, CUT_POWER, SABOTAGE_GATE, GUARD_JAIL, PATROL }

    public record Goal(Kind kind, Point target, UUID victim) {}

    /**
     * attackRange：多近可以揮拳；searchTicks：追丟後找多久；noiseMemoryTicks：聽到的聲音記多久；
     * guardJailChance：監獄有人時去守的機率。
     */
    public record Config(double attackRange, int searchTicks, int noiseMemoryTicks, double guardJailChance) {}

    private final Config cfg;
    private final RandomGenerator rnd;

    public GhostBrain(Config cfg, RandomGenerator rnd) {
        this.cfg = cfg;
        this.rnd = rnd;
        this.rules = buildRules();
    }

    /** 一條規則：條件成立時產生目標。順序就是優先順序（寫成資料，之後調順序、加其他鬼種的能力比較簡單）。 */
    private record Rule(Kind kind, java.util.function.Predicate<Situation> when, java.util.function.Function<Situation, Goal> make) {}

    private boolean seesSomeone(Situation s) {
        return !s.blinded() && s.seen() != null;
    }

    private boolean lastSeenFresh(Situation s) {
        return s.lastSeen() != null && s.tick() - s.lastSeenTick() <= cfg.searchTicks();
    }

    private boolean noiseFresh(Situation s) {
        return s.noise() != null && s.tick() - s.noiseTick() <= cfg.noiseMemoryTicks();
    }

    private final List<Rule> rules;

    private List<Rule> buildRules() {
        return List.of(
            new Rule(Kind.ATTACK, s -> seesSomeone(s) && s.seen().distance() <= cfg.attackRange(),
                    s -> new Goal(Kind.ATTACK, s.seen().at(), s.seen().id())),
            new Rule(Kind.CHASE, this::seesSomeone, s -> new Goal(Kind.CHASE, s.seen().at(), s.seen().id())),
            // 大門倒數只有 25 秒：排在「追」下面、所有搜尋的上面
            new Rule(Kind.SABOTAGE_GATE, s -> !s.sabotageableGates().isEmpty(),
                    s -> new Goal(Kind.SABOTAGE_GATE, nearest(s.here(), s.sabotageableGates()), null)),
            // 情報的新鮮度：比聲音舊的「最後看到的位置」讓給聲音
            new Rule(Kind.SEARCH_LAST_SEEN, s -> lastSeenFresh(s) && !(noiseFresh(s) && s.noiseTick() > s.lastSeenTick()),
                    s -> new Goal(Kind.SEARCH_LAST_SEEN, s.lastSeen(), null)),
            new Rule(Kind.INVESTIGATE_NOISE, this::noiseFresh, s -> new Goal(Kind.INVESTIGATE_NOISE, s.noise(), null)),
            new Rule(Kind.GO_SENSED, s -> s.sensed() != null, s -> new Goal(Kind.GO_SENSED, s.sensed(), null)),
            new Rule(Kind.MOVE_CARD, s -> s.cardRevealOpen() && s.cardMoveAvailable(), s -> new Goal(Kind.MOVE_CARD, null, null)),
            new Rule(Kind.CUT_POWER, s -> s.powerOn() && !s.cutPowerUsed(), s -> new Goal(Kind.CUT_POWER, null, null)),
            new Rule(Kind.GUARD_JAIL, s -> s.someoneJailed() && s.jail() != null && rnd.nextDouble() < cfg.guardJailChance(),
                    s -> new Goal(Kind.GUARD_JAIL, s.jail(), null)),
            new Rule(Kind.PATROL, s -> true, s -> new Goal(Kind.PATROL, null, null)));
    }

    /** 優先順序（測試、除錯顯示用）。 */
    public List<Kind> order() {
        return rules.stream().map(Rule::kind).toList();
    }

    public Goal decide(Situation s) {
        for (Rule r : rules) if (r.when().test(s)) return r.make().apply(s);
        return new Goal(Kind.PATROL, null, null);
    }

    private static Point nearest(Point from, List<Point> ps) {
        Point best = ps.get(0);
        double bd = Double.MAX_VALUE;
        for (Point p : ps) {
            double dx = p.x() - from.x(), dy = p.y() - from.y(), dz = p.z() - from.z();
            double d = dx * dx + dy * dy + dz * dz;
            if (d < bd) {
                bd = d;
                best = p;
            }
        }
        return best;
    }
}
