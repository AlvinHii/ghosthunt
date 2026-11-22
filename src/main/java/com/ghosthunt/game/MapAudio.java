package com.ghosthunt.game;

import com.ghosthunt.building.Layout;
import org.bukkit.Location;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 地圖音效（資源包的自訂聲音）：全場底層環境音＋依玩家所在區域換的環境音＋隨機事件音＋電源開關聲。
 * 音檔是立體聲（Minecraft 立體聲不會依距離變小聲），所以全部直接在玩家身上播、只給那個玩家聽；
 * 「區域」是看玩家站在哪一區決定播哪一個。純裝飾：AI 鬼聽不到這些聲音。
 * 玩家沒裝資源包的話，這些聲音就只是沒聲音，不影響遊戲。
 */
final class MapAudio {

    private final Match m;
    private final AudioConfig c;
    private final Map<UUID, State> states = new HashMap<>();
    private final long[] nextRandom;

    private static final class State {
        AudioConfig.Track zone;
        long zoneStart = Long.MIN_VALUE, globalStart = Long.MIN_VALUE;
    }

    private MapAudio(Match m, AudioConfig c) {
        this.m = m;
        this.c = c;
        this.nextRandom = new long[c.randoms.size()];
        for (int i = 0; i < nextRandom.length; i++) nextRandom[i] = schedule(i);
    }

    static MapAudio create(Match m) {
        AudioConfig c = m.b.sounds == null || m.b.sounds.isEmpty() ? new AudioConfig() : AudioConfig.parse(m.b.sounds);
        for (String p : c.problems) m.plugin.getLogger().warning("地圖 " + m.b.mapName + "：" + p);
        // 地圖沒有自己的全場環境音：播預設的恐怖白噪音（config 的 audio.default-ambient）
        if (c.global == null) {
            var cfg = m.plugin.getConfig();
            String snd = cfg.getString("audio.default-ambient", "ghosthunt:ambient.default");
            if (snd != null && !snd.isBlank()) {
                c.global = new AudioConfig.Track(snd, (float) cfg.getDouble("audio.default-ambient-volume", 1.0));
                if (m.b.sounds == null || m.b.sounds.isEmpty()) c.loopSeconds = Math.max(1, cfg.getInt("audio.default-ambient-loop", 30));
            }
        }
        return c.isEmpty() ? null : new MapAudio(m, c);
    }

    private long schedule(int i) {
        AudioConfig.Random r = c.randoms.get(i);
        return m.tick + 20L * (r.minSec() + m.rnd.nextInt(r.maxSec() - r.minSec() + 1));
    }

    /** 每秒呼叫一次。 */
    void tick() {
        long loop = 20L * c.loopSeconds;
        Layout L = m.b.layout;
        for (Player p : m.world.getPlayers()) {
            State st = states.computeIfAbsent(p.getUniqueId(), k -> new State());
            if (c.global != null && m.tick - st.globalStart >= loop) {
                play(p, c.global, 1f);
                st.globalStart = m.tick;
            }
            Location at = p.getLocation();
            int by = at.getBlockY();
            boolean under = by <= L.floorY(0) - 1;
            int floor = Math.max(0, (by - L.floorY(0) - 1) / L.floorHeight);
            AudioConfig.Track z = c.zoneAt(floor, at.getBlockX() - L.ox, at.getBlockZ() - L.oz, under);
            if (!Objects.equals(z, st.zone)) {
                if (st.zone != null) p.stopSound(st.zone.sound(), SoundCategory.AMBIENT);
                st.zone = z;
                st.zoneStart = Long.MIN_VALUE;
            }
            if (z != null && m.tick - st.zoneStart >= loop) {
                play(p, z, 1f);
                st.zoneStart = m.tick;
            }
        }
        for (int i = 0; i < nextRandom.length; i++) {
            if (m.tick < nextRandom[i]) continue;
            AudioConfig.Track t = c.randoms.get(i).track();
            float pitch = 0.9f + m.rnd.nextFloat() * 0.2f;
            for (Player p : m.world.getPlayers()) play(p, t, pitch);
            nextRandom[i] = schedule(i);
        }
    }

    /** 遊戲事件：power-on、power-off。 */
    void event(String key) {
        AudioConfig.Track t = switch (key) {
            case "power-on" -> c.powerOn;
            case "power-off" -> c.powerOff;
            default -> null;
        };
        if (t == null) return;
        for (Player p : m.world.getPlayers()) play(p, t, 1f);
    }

    void stop() {
        for (Player p : m.world.getPlayers()) {
            if (c.global != null) p.stopSound(c.global.sound(), SoundCategory.AMBIENT);
            if (c.tunnel != null) p.stopSound(c.tunnel.sound(), SoundCategory.AMBIENT);
            for (AudioConfig.Zone z : c.zones) p.stopSound(z.track().sound(), SoundCategory.AMBIENT);
        }
        states.clear();
    }

    private static void play(Player p, AudioConfig.Track t, float pitch) {
        p.playSound(p, t.sound(), SoundCategory.AMBIENT, t.volume(), pitch); // 跟著玩家播：單聲道的音檔走遠也不會變小聲
    }
}
