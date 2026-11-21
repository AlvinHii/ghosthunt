package com.ghosthunt.game;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 地圖音效設定（固定地圖的 [sounds] 區塊），純資料、不碰伺服器（有單元測試）。
 *
 * <pre>
 * loop = 30                                      # 環境音多久重播一次（秒，等於音檔長度）
 * global = hydro.global 0.35                     # 全場一直播的底層環境音：名稱 音量
 * zone = hydro.turbine 0.8 1F 2,28 13,36 37,10 47,16   # 區域環境音：名稱 音量 樓層 角,角 角,角（可以好幾個方塊）
 * tunnel = hydro.tunnel 0.9                      # 在地下通道裡
 * random = hydro.creak 45 100 0.5                # 隨機事件音：名稱 最短秒 最長秒 音量
 * power-on = hydro.relay 1.0                     # 電源打開時
 * power-off = hydro.relay 1.0                    # 電源被關掉時
 * </pre>
 * 名稱沒寫命名空間就是 ghosthunt:（資源包 resourcepack/assets/ghosthunt/sounds.json）。
 * 座標是地圖檔的格子座標（x 從左數、z 從上數，從 0 開始）。
 */
public final class AudioConfig {

    public record Track(String sound, float volume) {}

    public record Rect(int x1, int z1, int x2, int z2) {
        boolean contains(int x, int z) {
            return x >= x1 && x <= x2 && z >= z1 && z <= z2;
        }
    }

    public record Zone(Track track, int floor, List<Rect> rects) {}

    public record Random(Track track, int minSec, int maxSec) {}

    public int loopSeconds = 30;
    public Track global, tunnel, powerOn, powerOff;
    public final List<Zone> zones = new ArrayList<>();
    public final List<Random> randoms = new ArrayList<>();
    public final List<String> problems = new ArrayList<>();

    public boolean isEmpty() {
        return global == null && tunnel == null && zones.isEmpty() && randoms.isEmpty() && powerOn == null && powerOff == null;
    }

    public static AudioConfig parse(List<String[]> lines) {
        AudioConfig c = new AudioConfig();
        for (String[] kv : lines) {
            String key = kv[0].toLowerCase(Locale.ROOT);
            String[] v = kv[1].trim().split("\\s+");
            try {
                switch (key) {
                    case "loop" -> c.loopSeconds = Math.max(1, Integer.parseInt(v[0]));
                    case "global" -> c.global = track(v, 0);
                    case "tunnel" -> c.tunnel = track(v, 0);
                    case "power-on" -> c.powerOn = track(v, 0);
                    case "power-off" -> c.powerOff = track(v, 0);
                    case "random" -> {
                        int lo = Integer.parseInt(v[1]), hi = Integer.parseInt(v[2]);
                        float vol = v.length > 3 ? Float.parseFloat(v[3]) : 1f;
                        c.randoms.add(new Random(new Track(name(v[0]), vol), Math.max(1, Math.min(lo, hi)), Math.max(lo, hi)));
                    }
                    case "zone" -> {
                        Track t = track(v, 0);
                        int floor = Integer.parseInt(v[2].toUpperCase(Locale.ROOT).replace("F", "")) - 1;
                        List<Rect> rects = new ArrayList<>();
                        for (int i = 3; i + 1 < v.length; i += 2) {
                            int[] a = xz(v[i]), b = xz(v[i + 1]);
                            rects.add(new Rect(Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.max(a[0], b[0]), Math.max(a[1], b[1])));
                        }
                        if (rects.isEmpty()) c.problems.add("zone 沒有範圍：" + kv[1]);
                        else c.zones.add(new Zone(t, floor, rects));
                    }
                    default -> c.problems.add("不認識的音效設定：" + key);
                }
            } catch (RuntimeException e) {
                c.problems.add("音效設定看不懂：" + key + " = " + kv[1]);
            }
        }
        return c;
    }

    /** 玩家在這裡該播哪個區域環境音（沒有就 null）。floor 從 0 開始；underground＝在 1F 地板下面（地下通道）。 */
    public Track zoneAt(int floor, int x, int z, boolean underground) {
        if (underground) return tunnel;
        for (Zone zn : zones) {
            if (zn.floor() != floor) continue;
            for (Rect r : zn.rects()) if (r.contains(x, z)) return zn.track();
        }
        return null;
    }

    private static Track track(String[] v, int i) {
        return new Track(name(v[i]), v.length > i + 1 ? Float.parseFloat(v[i + 1]) : 1f);
    }

    private static String name(String s) {
        return s.contains(":") ? s : "ghosthunt:" + s;
    }

    private static int[] xz(String s) {
        String[] p = s.split(",");
        return new int[]{Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim())};
    }
}
