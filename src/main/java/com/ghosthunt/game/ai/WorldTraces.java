package com.ghosthunt.game.ai;

import java.util.HashMap;
import java.util.Map;

/**
 * 看痕跡（純邏輯）：鬼每次看到一個物件就記下它的狀態，下次看到時狀態變了（而且不是鬼自己改的），就是一個痕跡。
 * 跟真人鬼一樣：「剛才還關著的門現在開了」「這個木桶被翻過了」。
 * 狀態編碼：門 0 關／1 開／2 卡住；木桶 0 沒翻／1 翻過；櫃子 0 站著／1 推倒；小洞 0 原本／1 打大。
 */
public final class WorldTraces {

    public enum Type { DOOR, BARREL, CABINET, HOLE }

    /** 一個痕跡：gapTicks＝上次看到到這次看到隔了多久（越短越有用）。 */
    public record Trace(Type type, int id, double x, double y, double z, long gapTicks, long tick) {
        /** 60 秒內看過的東西變了：很有用（1）；更久以前看過的：只加一點（0.25）。 */
        public double usefulness() {
            return gapTicks <= 1200 ? 1.0 : 0.25;
        }
    }

    private record Seen(int state, long tick) {}

    private final Map<Long, Seen> seen = new HashMap<>();
    /** 鬼自己造成的新狀態：下次看到是這個狀態時不算痕跡。 */
    private record Mark(int state, long tick) {}

    private final Map<Long, Mark> ghostMade = new HashMap<>();
    /** 鬼的記號多久沒兌現就失效（動作被取消時不要一直吞掉別人造成的痕跡）。 */
    private static final long MARK_TTL = 200;

    private static long key(Type type, int id) {
        return ((long) type.ordinal() << 32) | (id & 0xffffffffL);
    }

    /** 鬼看到這個物件；狀態跟上次看到不同、而且不是鬼自己改的 → 回傳痕跡，否則 null。 */
    public Trace observe(Type type, int id, int state, double x, double y, double z, long tick) {
        long k = key(type, id);
        Seen before = seen.put(k, new Seen(state, tick));
        Mark mine = ghostMade.get(k);
        if (mine != null && tick - mine.tick() > MARK_TTL) {
            ghostMade.remove(k);
            mine = null;
        }
        // 鬼自己的動作可能要時間（打大洞 4 秒）：看到的狀態變成鬼要的那個時才把記號用掉
        if (mine != null && mine.state() == state) {
            ghostMade.remove(k);
            return null;
        }
        if (before == null || before.state() == state) return null;
        return new Trace(type, id, x, y, z, tick - before.tick(), tick);
    }

    /** 鬼自己把這個物件改成 newState（開門、撞開卡門、拆障礙物、打大洞）。 */
    public void ghostChanged(Type type, int id, int newState, long tick) {
        ghostMade.put(key(type, id), new Mark(newState, tick));
    }
}
