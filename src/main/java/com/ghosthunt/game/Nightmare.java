package com.ghosthunt.game;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * 夢魘：平常是普通的鬼；熄燈 90 秒期間：
 * 燈全滅（連緊急照明）＋逃生者的霧變短（鬼看不到霧）＋燈光偶爾閃一下；小範圍內看得到逃生者的移動軌跡（只有鬼看得到）；
 * 站太久的逃生者會被標出來；逃生者聽不到心跳，鬼靠近時改成聽到低語聲。
 * 速度不變（v3.1：整場速度一樣）。
 */
final class Nightmare {

    private record Trail(Location at, long tick) {}

    private static final int TRAIL_LIFE = 60;       // 軌跡留 3 秒
    private static final Particle.DustOptions TRAIL_DUST = new Particle.DustOptions(Color.fromRGB(170, 0, 255), 1.2f);

    private final Match m;
    boolean active;
    private long activeUntil;
    private long readyAt = Long.MAX_VALUE;
    private long flickerUntil;
    private long nextFlicker;
    private final ArrayDeque<Trail> trails = new ArrayDeque<>();
    private java.util.List<Player> fogged;
    /** 被手電筒照到：這段時間追蹤能力（軌跡、站太久提示、互動精確位置）失效。 */
    long disruptedUntil;

    Nightmare(Match m) {
        this.m = m;
    }

    void onRelease() {
        readyAt = m.tick + m.s.nmFirstUse();
    }

    /** 鬼右鍵熄燈。 */
    void use(Player g) {
        if (!m.released) {
            g.sendActionBar(Component.text("還在鬼房裡", NamedTextColor.GRAY));
            return;
        }
        if (active) {
            g.sendActionBar(Component.text("燈已經熄了", NamedTextColor.GRAY));
            return;
        }
        if (m.tick < readyAt) {
            g.sendActionBar(Component.text("熄燈還要等 " + ((readyAt - m.tick) / 20 + 1) + " 秒", NamedTextColor.GRAY));
            return;
        }
        active = true;
        activeUntil = m.tick + m.s.nmDuration();
        nextFlicker = m.tick + 60 + m.rnd.nextInt(60);
        m.setLights(0);
        // 只有逃生者看到霧，鬼看得清楚
        fogged = m.survivorPlayers();
        m.personalFog(m.s.nmFog(), fogged);
        for (Match.Survivor sv : m.survivors.values()) {
            Player p = Bukkit.getPlayer(sv.id);
            if (p == null || sv.status == Match.Status.ELIMINATED) continue;
            p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 60, 0, false, false));
            p.playSound(p.getLocation(), Sound.BLOCK_BEACON_DEACTIVATE, 1f, 0.5f);
            p.showTitle(Title.title(Component.empty(), Component.text("燈熄了……", NamedTextColor.DARK_PURPLE),
                    Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(2), Duration.ofSeconds(1))));
        }
        g.playSound(g.getLocation(), Sound.ENTITY_ELDER_GUARDIAN_CURSE, 0.6f, 0.6f);
    }

    void tick(Player g) {
        if (!active) return;
        if (m.tick >= activeUntil) {
            lightsOn();
            readyAt = m.tick + m.s.nmCooldown();
            g.sendActionBar(Component.text("燈亮了，熄燈冷卻中", NamedTextColor.GRAY));
            return;
        }
        // 燈光閃爍：偶爾亮一下
        if (m.tick >= nextFlicker) {
            m.setLights(3);
            flickerUntil = m.tick + 2 + m.rnd.nextInt(3);
            nextFlicker = m.tick + 40 + m.rnd.nextInt(80);
        } else if (flickerUntil != 0 && m.tick >= flickerUntil) {
            m.setLights(0);
            flickerUntil = 0;
        }
        if (m.tick < disruptedUntil) {
            trails.clear();
            return;
        }
        // 軌跡：附近有在移動的逃生者
        if (m.tick % 4 == 0) {
            for (Match.Survivor sv : m.survivors.values()) {
                if (sv.status != Match.Status.FREE) continue;
                Player p = Bukkit.getPlayer(sv.id);
                if (p == null || !p.getWorld().equals(g.getWorld())) continue;
                if (p.getLocation().distance(g.getLocation()) > m.s.nmTrailRadius()) continue;
                if (m.tick - sv.stillSince < 5) trails.add(new Trail(p.getLocation().add(0, 0.1, 0), m.tick));
            }
        }
        Iterator<Trail> it = trails.iterator();
        while (it.hasNext()) {
            Trail t = it.next();
            if (m.tick - t.tick() > TRAIL_LIFE) it.remove();
            else if (m.tick % 4 == 0) g.spawnParticle(Particle.DUST, t.at(), 2, 0.15, 0.02, 0.15, 0, TRAIL_DUST);
        }
        // 站太久的人：每 2 秒冒一次靈魂粒子（只有鬼看得到）
        if (m.tick % 40 == 0) {
            for (Match.Survivor sv : m.survivors.values()) {
                if (sv.status != Match.Status.FREE) continue;
                Player p = Bukkit.getPlayer(sv.id);
                if (p == null || m.tick - sv.stillSince < m.s.nmIdleHint()) continue;
                g.spawnParticle(Particle.SOUL, p.getLocation().add(0, 1, 0), 12, 0.3, 0.6, 0.3, 0.02);
            }
        }
    }

    /** 熄燈期間沒有心跳；鬼在低語範圍內時聽到低語聲。 */
    void whisper(Match.Survivor sv, Player p, Player g) {
        if (!p.getWorld().equals(g.getWorld())) return;
        double d = p.getLocation().distance(g.getLocation());
        if (d > m.s.nmWhisperRadius()) return;
        if (m.tick - sv.lastHeartbeat < 30) return;
        sv.lastHeartbeat = m.tick;
        p.playSound(g.getLocation(), Sound.ENTITY_VEX_AMBIENT, 0.5f, 0.5f);
    }

    private void lightsOn() {
        active = false;
        trails.clear();
        m.refreshLights();
        // 把逃生者的霧換回平常的（世界本身一直是平常的霧，所以送世界現在的樣子就好）
        if (fogged != null) m.personalFog(m.s.normalFog(), fogged);
        fogged = null;
        for (Match.Survivor sv : m.survivors.values()) {
            Player p = Bukkit.getPlayer(sv.id);
            if (p != null) p.playSound(p.getLocation(), Sound.BLOCK_BEACON_DEACTIVATE, 0.5f, 1.4f);
        }
    }

    void disrupt(int ticks) {
        disruptedUntil = Math.max(disruptedUntil, m.tick + ticks);
        Player g = m.ghost();
        if (g != null) g.sendActionBar(Component.text("被手電筒照到了！追蹤暫時失效", NamedTextColor.YELLOW));
    }

    boolean tracking() {
        return active && m.tick >= disruptedUntil;
    }

    void stop() {
        if (active) lightsOn();
    }

    String hud() {
        if (active) return "熄燈中：剩 " + ((activeUntil - m.tick) / 20 + 1) + " 秒" + (m.tick < disruptedUntil ? "（追蹤被打斷）" : "");
        if (m.tick < readyAt) return "熄燈冷卻：" + ((readyAt - m.tick) / 20 + 1) + " 秒";
        return "熄燈可以用了（右鍵）";
    }
}
