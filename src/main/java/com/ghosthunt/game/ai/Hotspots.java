package com.ghosthunt.game.ai;

import java.util.HashMap;
import java.util.Map;

/**
 * 熱點記憶（第三版第 20 項）：鬼每次看到逃生者，就在那一格加熱度（周圍 3 格一起加，越遠越少）。
 * 用方塊座標當 key（固定地圖的格局每局一樣），所以可以存檔帶到下一局。
 */
public final class Hotspots {

    public static final int RADIUS = 3;
    private final Map<Long, Double> heat = new HashMap<>();

    static long key(int x, int y, int z) {
        return ((long) (x & 0x1FFFFF) << 42) | ((long) (y & 0x1FFFFF) << 21) | (z & 0x1FFFFF);
    }

    private static int unpack(long v, int shift) {
        int raw = (int) ((v >>> shift) & 0x1FFFFF);
        return (raw << 11) >> 11; // 21 位元有號
    }

    /** 看到人：(x, y, z) 加 amount，周圍 RADIUS 格線性遞減（同一高度）。 */
    public void see(int x, int y, int z, double amount) {
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                double d = Math.hypot(dx, dz);
                if (d > RADIUS) continue;
                heat.merge(key(x + dx, y, z + dz), amount * (1 - d / (RADIUS + 1)), Double::sum);
            }
        }
    }

    public double at(int x, int y, int z) {
        return heat.getOrDefault(key(x, y, z), 0.0);
    }

    public void decay(double f) {
        heat.replaceAll((k, v) -> v * f);
        heat.values().removeIf(v -> v < 0.05);
    }

    public int size() {
        return heat.size();
    }

    /** 存檔用：key 是 "x_y_z"（Bukkit 的 yml 用點當路徑分隔）。 */
    public Map<String, Double> flat() {
        Map<String, Double> out = new HashMap<>();
        for (Map.Entry<Long, Double> e : heat.entrySet()) {
            long k = e.getKey();
            out.put(unpack(k, 42) + "_" + unpack(k, 21) + "_" + unpack(k, 0), Math.round(e.getValue() * 100) / 100.0);
        }
        return out;
    }

    public void load(String key, double v) {
        String[] p = key.split("_");
        if (p.length != 3) return;
        try {
            heat.put(key(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])), v);
        } catch (NumberFormatException ignored) {
            // 壞掉的 key 忽略
        }
    }
}
