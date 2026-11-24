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
                            boolean someoneJailed, Point jail, Point here, Point ambush, Point readerWatch) {
        /** 第二版的欄位（沒有埋伏點、守讀卡機點）。 */
        public Situation(long tick, boolean blinded, Seen seen, Point lastSeen, long lastSeenTick,
                         Point noise, long noiseTick, Point sensed,
                         boolean cardRevealOpen, boolean cardMoveAvailable,
                         boolean powerOn, boolean cutPowerUsed, List<Point> sabotageableGates,
                         boolean someoneJailed, Point jail, Point here) {
            this(tick, blinded, seen, lastSeen, lastSeenTick, noise, noiseTick, sensed, cardRevealOpen, cardMoveAvailable,
                    powerOn, cutPowerUsed, sabotageableGates, someoneJailed, jail, here, null, null);
        }
    }

    public enum Kind { CHASE, ATTACK, SEARCH_LAST_SEEN, INVESTIGATE_NOISE, GO_SENSED, MOVE_CARD, CUT_POWER, SABOTAGE_GATE, GUARD_READER, GUARD_JAIL, AMBUSH, PATROL }

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
            // 守下一個目標：沒電守總電源、還沒授權守保全室終端機、授權後守讀卡機（AiGhost 決定點、時間上限；guard-objective）
            new Rule(Kind.GUARD_READER, s -> s.readerWatch() != null, s -> new Goal(Kind.GUARD_READER, s.readerWatch(), null)),
            new Rule(Kind.GUARD_JAIL, s -> s.someoneJailed() && s.jail() != null && rnd.nextDouble() < cfg.guardJailChance(),
                    s -> new Goal(Kind.GUARD_JAIL, s.jail(), null)),
            // 冷靜期：退場、繞到逃生者下一個目標的咽喉點埋伏（優先度在聲音之下、巡邏之上）
            new Rule(Kind.AMBUSH, s -> s.ambush() != null, s -> new Goal(Kind.AMBUSH, s.ambush(), null)),
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

    // ---- 第三版：Utility AI ----

    /** 個性權重（沒列的目標是 1）。 */
    public record Weights(java.util.Map<Kind, Double> mul) {
        public static final Weights STANDARD = new Weights(java.util.Map.of());

        public static Weights of(java.util.Map<Kind, Double> mul) {
            return new Weights(java.util.Map.copyOf(mul));
        }

        public double of(Kind k) {
            return mul.getOrDefault(k, 1.0);
        }
    }

    public record Scored(Kind kind, double score) {}

    /**
     * 企劃第 16 項表格裡其他的考量（都是 0～1；NEUTRAL = 不影響，行為跟沒給一樣）：
     * noiseLevel 聲音等級（0 低～1 高；NaN = 不用）、noiseDistance 聲音多遠（格；NaN = 不用）、particleConcentration 粒子集中程度、
     * gateSlack 弄壞大門的餘裕（剩下時間的比例，越少越急）、jailGuarded／readerGuarded 已經守了多久（÷ 上限）、
     * jailApproach 有人可能正在靠近監獄（粒子、聲音）、cutPowerValue 關電源的價值。
     */
    public record Factors(double noiseLevel, double noiseDistance, double particleConcentration, double gateSlack,
                          double jailGuarded, double jailApproach, double readerGuarded, double cutPowerValue) {
        public static final Factors NEUTRAL = new Factors(Double.NaN, Double.NaN, 1, 0, 0, 1, 0, 1);

        public Factors withNoise(double level, double distance) {
            return new Factors(level, distance, particleConcentration, gateSlack, jailGuarded, jailApproach, readerGuarded, cutPowerValue);
        }

        public Factors withReaderGuarded(double v) {
            return new Factors(noiseLevel, noiseDistance, particleConcentration, gateSlack, jailGuarded, jailApproach, v, cutPowerValue);
        }
    }

    /** 相鄰兩級的基本分差幾倍；考量都在 0.5～1，所以標準個性的順序跟第二版一樣，只有「差不多」的情況才會被個性改變。 */
    private static final double TIER = 2.0;
    private static final double STICKY = 1.15;
    private static final int MIN_HOLD = 30;

    private Kind current;
    private long currentSince = Long.MIN_VALUE / 2;
    private List<Scored> lastScores = List.of();

    /**
     * Utility 版：第二版的規則當作「硬條件」（不成立 = 0 分），成立的目標分數 = 級數基本分 × 考量（0.5～1）× 個性權重，
     * 目前的目標 +15%；換目標後至少維持 1.5 秒（攻擊、追除外）。
     *
     * @param pickBest 困難：選最高分；否則從「最高分 90% 以內」的目標裡照分數加權隨機選
     */
    public Goal decideUtility(Situation s, Weights w, boolean pickBest) {
        return decideUtility(s, w, pickBest, Factors.NEUTRAL);
    }

    public Goal decideUtility(Situation s, Weights w, boolean pickBest, Factors f) {
        int n = rules.size();
        List<Scored> scored = new java.util.ArrayList<>();
        java.util.Map<Kind, Rule> valid = new java.util.EnumMap<>(Kind.class);
        for (int i = 0; i < n; i++) {
            Rule r = rules.get(i);
            if (!r.when().test(s)) continue;
            double score = Math.pow(TIER, n - 1 - i) * consideration(r.kind(), s, f) * w.of(r.kind());
            if (r.kind() == current) score *= STICKY;
            scored.add(new Scored(r.kind(), score));
            valid.put(r.kind(), r);
        }
        if (scored.isEmpty()) return new Goal(Kind.PATROL, null, null);
        // 列表保持規則順序：平手時順序前面的贏
        Scored best = scored.get(0);
        for (Scored sc : scored) if (sc.score() > best.score()) best = sc;
        Kind pick = best.kind();
        if (!pickBest) {
            double max = best.score(), sum = 0;
            for (Scored sc : scored) if (sc.score() >= 0.9 * max) sum += sc.score();
            double x = rnd.nextDouble() * sum;
            for (Scored sc : scored) {
                if (sc.score() < 0.9 * max) continue;
                x -= sc.score();
                if (x <= 0) {
                    pick = sc.kind();
                    break;
                }
            }
        }
        boolean urgent = pick == Kind.ATTACK || pick == Kind.CHASE;
        if (current != null && pick != current && !urgent && s.tick() - currentSince < MIN_HOLD && valid.containsKey(current)) pick = current;
        if (pick != current) {
            current = pick;
            currentSince = s.tick();
        }
        List<Scored> sorted = new java.util.ArrayList<>(scored);
        sorted.sort((a, b) -> Double.compare(b.score(), a.score()));
        lastScores = List.copyOf(sorted);
        return valid.get(pick).make().apply(s);
    }

    /** 最近一次 decideUtility 每個成立的目標和分數（高到低；除錯顯示前 3 名）。 */
    public List<Scored> lastScores() {
        return lastScores;
    }

    /**
     * 考量（相乘後夾在 0.5～1，所以不會掉到下一級以下）：追 = 越近越高；找最後位置 = 越新、粒子越集中越高；
     * 聲音 = 越新、等級越高、越近越高；弄壞大門 = 餘裕越少越急；守監獄 = 守越久越低、有人靠近越高；守讀卡機 = 守越久越低；
     * 關電源 = 價值越高越高。
     */
    private double consideration(Kind k, Situation s, Factors f) {
        double v = switch (k) {
            case CHASE -> 1 - 0.5 * Math.min(1, s.seen().distance() / 24.0);
            case SEARCH_LAST_SEEN -> (1 - 0.5 * Math.min(1, (s.tick() - s.lastSeenTick()) / (double) Math.max(1, cfg.searchTicks())))
                    * (0.75 + 0.25 * clamp01(f.particleConcentration()));
            case INVESTIGATE_NOISE -> {
                double x = 1 - 0.5 * Math.min(1, (s.tick() - s.noiseTick()) / (double) Math.max(1, cfg.noiseMemoryTicks()));
                if (!Double.isNaN(f.noiseLevel())) x *= 0.75 + 0.25 * clamp01(f.noiseLevel());
                if (!Double.isNaN(f.noiseDistance())) x *= 1 - 0.25 * Math.min(1, f.noiseDistance() / 32.0);
                yield x;
            }
            case SABOTAGE_GATE -> 0.75 + 0.25 * (1 - clamp01(f.gateSlack()));
            case GUARD_JAIL -> (1 - 0.5 * clamp01(f.jailGuarded())) * (0.75 + 0.25 * clamp01(f.jailApproach()));
            case GUARD_READER -> 1 - 0.5 * clamp01(f.readerGuarded());
            case CUT_POWER -> 0.5 + 0.5 * clamp01(f.cutPowerValue());
            default -> 1;
        };
        return Math.max(0.5, Math.min(1, v));
    }

    private static double clamp01(double v) {
        return Double.isNaN(v) ? 1 : Math.max(0, Math.min(1, v));
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
