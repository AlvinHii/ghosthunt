package com.ghosthunt.game.ai;

import com.ghosthunt.game.ai.GhostBrain.Kind;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 鬼的個性（第三版第 18 項）：只是一組 Utility 權重和幾個參數，不改規則。
 * <ul>
 *   <li>獵犬 hound：一直追、很難甩掉（追 ×1.3、找最後位置 ×1.2；推理粒子的時間短）。</li>
 *   <li>埋伏者 ambusher：每次追人都算繞前堵、愛埋伏（埋伏 ×1.5、追 ×0.9、埋伏時間 ×1.4）。</li>
 *   <li>守望者 watcher：守監獄、守讀卡機 ×1.5，巡邏偏向咽喉點，守監獄機率 +0.25。</li>
 * </ul>
 * 給玩家的線索：腳步聲音高、發現人時的吼聲不同（{@link #stepPitch}、{@link #growl}）。
 */
public record GhostPersona(String id, String label, GhostBrain.Weights weights,
                           boolean interceptAlways, double particleAgeMul, double ambushTimeMul,
                           double chokePatrolMul, double guardJailChanceAdd,
                           float stepPitch, String growl) {

    public static final GhostPersona STANDARD = new GhostPersona("standard", "標準", GhostBrain.Weights.STANDARD,
            false, 1, 1, 1, 0, 1.0f, "ENTITY_WARDEN_AGITATED");

    /** 內建的三種（config 的 personas: 可以覆寫數值）。 */
    public static Map<String, GhostPersona> builtIn() {
        Map<String, GhostPersona> m = new LinkedHashMap<>();
        m.put("hound", new GhostPersona("hound", "獵犬", weights(Kind.CHASE, 1.3, Kind.SEARCH_LAST_SEEN, 1.2, Kind.AMBUSH, 0.6),
                false, 0.7, 1, 1, 0, 1.25f, "ENTITY_WOLF_GROWL"));
        m.put("ambusher", new GhostPersona("ambusher", "埋伏者", weights(Kind.AMBUSH, 1.5, Kind.CHASE, 0.9),
                true, 1, 1.4, 1, 0, 0.8f, "ENTITY_WARDEN_SNIFF"));
        m.put("watcher", new GhostPersona("watcher", "守望者", weights(Kind.GUARD_JAIL, 1.5, Kind.GUARD_READER, 1.5),
                false, 1, 1, 1.5, 0.25, 0.65f, "ENTITY_WARDEN_ROAR"));
        return m;
    }

    /** 三種個性的 id（多臂吃角子老虎的拉桿）。 */
    public static List<String> arms() {
        return List.copyOf(builtIn().keySet());
    }

    private static GhostBrain.Weights weights(Object... kv) {
        Map<Kind, Double> m = new EnumMap<>(Kind.class);
        for (int i = 0; i < kv.length; i += 2) m.put((Kind) kv[i], (Double) kv[i + 1]);
        return GhostBrain.Weights.of(m);
    }

    /** 用 config 的值覆寫（key 是 Kind 名稱小寫，例如 chase: 1.3；以及 particle-age、ambush-time、choke-patrol、guard-jail-add）。 */
    public GhostPersona with(Map<String, Double> over) {
        Map<Kind, Double> w = new EnumMap<>(Kind.class);
        w.putAll(weights.mul());
        double age = particleAgeMul, amb = ambushTimeMul, choke = chokePatrolMul, jail = guardJailChanceAdd;
        for (Map.Entry<String, Double> e : over.entrySet()) {
            switch (e.getKey()) {
                case "particle-age" -> age = e.getValue();
                case "ambush-time" -> amb = e.getValue();
                case "choke-patrol" -> choke = e.getValue();
                case "guard-jail-add" -> jail = e.getValue();
                default -> {
                    try {
                        w.put(Kind.valueOf(e.getKey().toUpperCase().replace('-', '_')), e.getValue());
                    } catch (IllegalArgumentException ignored) {
                        // 不認得的 key 忽略
                    }
                }
            }
        }
        return new GhostPersona(id, label, GhostBrain.Weights.of(w), interceptAlways, age, amb, choke, jail, stepPitch, growl);
    }
}
