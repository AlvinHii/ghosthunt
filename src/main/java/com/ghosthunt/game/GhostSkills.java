package com.ghosthunt.game;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * 鬼的技能（2026-11-24 使用者決定；PvE 的 AI 鬼追人時用，數值在 config 的 ai-ghost.skill-*）：
 * <ul>
 *   <li>詛咒減速：一個人變慢（skill-slow-amount）幾秒。</li>
 *   <li>黑暗：一個人畫面變暗（原版 Darkness 效果）幾秒。</li>
 *   <li>封印道具：鬼身邊幾格內的人幾秒不能用道具（煙霧彈、藥水、手電筒、掃描器、障礙物；卡門、推櫃子可以）。</li>
 * </ul>
 * 模擬器的實驗見 ai/README.md「鬼的技能」。
 */
final class GhostSkills {

    static final NamespacedKey CURSE_KEY = new NamespacedKey("ghosthunt", "curse");

    private final Match m;
    private final Map<UUID, Long> curseUntil = new HashMap<>();
    private final Map<UUID, Long> sealUntil = new HashMap<>();
    int curses, darks, seals;

    GhostSkills(Match m) {
        this.m = m;
    }

    boolean sealed(UUID id) {
        Long t = sealUntil.get(id);
        return t != null && m.tick < t;
    }

    /** 被封印時用道具：提示還剩幾秒。 */
    void sealedMessage(Player p) {
        long left = Math.max(1, (sealUntil.getOrDefault(p.getUniqueId(), m.tick) - m.tick + 19) / 20);
        p.sendActionBar(Component.text("道具被鬼封印了（還有 " + left + " 秒）", NamedTextColor.DARK_PURPLE));
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.6f);
    }

    void curse(Player p, double amount, int ticks) {
        curseUntil.put(p.getUniqueId(), m.tick + ticks);
        AttributeInstance sp = p.getAttribute(Attribute.MOVEMENT_SPEED);
        if (sp != null) {
            sp.removeModifier(CURSE_KEY);
            sp.addTransientModifier(new AttributeModifier(CURSE_KEY, -amount, AttributeModifier.Operation.MULTIPLY_SCALAR_1));
        }
        Location l = p.getLocation().add(0, 1, 0);
        m.world.spawnParticle(Particle.SOUL, l, 12, 0.3, 0.5, 0.3, 0.02);
        m.world.playSound(l, Sound.ENTITY_EVOKER_CAST_SPELL, 1f, 0.7f);
        p.sendActionBar(Component.text("你被詛咒了：變慢 " + (ticks / 20) + " 秒", NamedTextColor.DARK_PURPLE));
        curses++;
    }

    void darken(Player p, int ticks) {
        p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, ticks, 0, false, false, true));
        p.playSound(p.getLocation(), Sound.ENTITY_WARDEN_NEARBY_CLOSER, 0.7f, 0.8f);
        p.sendActionBar(Component.text("眼前一片漆黑……", NamedTextColor.DARK_GRAY));
        darks++;
    }

    /** 封印鬼身邊 radius 格內的人；回傳封印了幾個人。 */
    int seal(Location center, double radius, int ticks) {
        int n = 0;
        for (Match.Survivor sv : m.survivors.values()) {
            if (sv.status != Match.Status.FREE) continue;
            Player p = Bukkit.getPlayer(sv.id);
            if (p == null || !p.getWorld().equals(center.getWorld()) || p.getLocation().distance(center) > radius) continue;
            if (m.aiIgnores(p.getUniqueId())) continue;
            sealUntil.put(p.getUniqueId(), m.tick + ticks);
            m.items.sealedNow(p);
            p.sendActionBar(Component.text("道具被鬼封印了 " + (ticks / 20) + " 秒", NamedTextColor.DARK_PURPLE));
            n++;
        }
        if (n > 0) {
            m.world.playSound(center, Sound.BLOCK_BEACON_DEACTIVATE, 1.2f, 0.6f);
            m.world.spawnParticle(Particle.WITCH, center.clone().add(0, 1, 0), 30, radius / 3, 0.5, radius / 3, 0.05);
            seals++;
        }
        return n;
    }

    /** 每 tick：詛咒時間到就拿掉減速。 */
    void tick() {
        for (Iterator<Map.Entry<UUID, Long>> it = curseUntil.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Long> e = it.next();
            if (m.tick < e.getValue()) continue;
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null) clear(p);
            it.remove();
        }
    }

    static void clear(Player p) {
        AttributeInstance sp = p.getAttribute(Attribute.MOVEMENT_SPEED);
        if (sp != null) sp.removeModifier(CURSE_KEY);
    }
}
