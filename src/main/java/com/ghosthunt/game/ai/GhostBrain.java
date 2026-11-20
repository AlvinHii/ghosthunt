package com.ghosthunt.game.ai;

import java.util.List;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * AI 鬼的大腦：看現在的情況，決定下一步要做什麼。純邏輯，不碰伺服器（可以單元測試）。
 * 優先順序（設計文件第 4 節）：攻擊 ＞ 追人 ＞ 找最後看到的位置 ＞ 去聽到聲音的地方 ＞ 去感應到的位置
 * ＞ 搬卡 ＞ 弄壞大門 ＞ 關電源 ＞ 守監獄（機率）＞ 巡邏。
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
    }

    public Goal decide(Situation s) {
        if (!s.blinded() && s.seen() != null) {
            Seen v = s.seen();
            return v.distance() <= cfg.attackRange()
                    ? new Goal(Kind.ATTACK, v.at(), v.id())
                    : new Goal(Kind.CHASE, v.at(), v.id());
        }
        if (s.lastSeen() != null && s.tick() - s.lastSeenTick() <= cfg.searchTicks())
            return new Goal(Kind.SEARCH_LAST_SEEN, s.lastSeen(), null);
        if (s.noise() != null && s.tick() - s.noiseTick() <= cfg.noiseMemoryTicks())
            return new Goal(Kind.INVESTIGATE_NOISE, s.noise(), null);
        if (s.sensed() != null) return new Goal(Kind.GO_SENSED, s.sensed(), null);
        if (s.cardRevealOpen() && s.cardMoveAvailable()) return new Goal(Kind.MOVE_CARD, null, null);
        if (!s.sabotageableGates().isEmpty()) return new Goal(Kind.SABOTAGE_GATE, nearest(s.here(), s.sabotageableGates()), null);
        if (s.powerOn() && !s.cutPowerUsed()) return new Goal(Kind.CUT_POWER, null, null);
        if (s.someoneJailed() && s.jail() != null && rnd.nextDouble() < cfg.guardJailChance())
            return new Goal(Kind.GUARD_JAIL, s.jail(), null);
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
