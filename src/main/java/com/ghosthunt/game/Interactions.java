package com.ghosthunt.game;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 「點一下開始、站著不動等進度條」的互動（企劃書 v3.1：原版客戶端沒辦法讓伺服器偵測「按住」）。
 * 走動超過半格、被抓、或玩家離開都會中斷，進度歸零。
 * coop = true 的互動（開／修電源）最多兩個人一起做，共用一條進度，速度變成 coopMultiplier 倍；
 * 其中一人離開時進度保留，變回單人速度。
 */
final class Interactions {

    static final class Act {
        final String key;
        final String label;
        final int needed;
        final boolean coop;
        final boolean noisy;
        final Location at;
        final Runnable onDone;
        final Map<UUID, Location> who = new LinkedHashMap<>();
        double progress;
        Runnable onCancel = () -> { };
        /** 每 tick 做的事（例如鬼拆障礙物時全場都聽得到的聲音）。 */
        Runnable onTick = () -> { };

        Act(String key, String label, int needed, boolean coop, boolean noisy, Location at, Runnable onDone) {
            this.key = key;
            this.label = label;
            this.needed = needed;
            this.coop = coop;
            this.noisy = noisy;
            this.at = at;
            this.onDone = onDone;
        }
    }

    private final Match m;
    private final Map<String, Act> byKey = new LinkedHashMap<>();
    private final Map<UUID, Act> byPlayer = new HashMap<>();

    Interactions(Match m) {
        this.m = m;
    }

    /**
     * 開始（或加入）一個互動。
     * @param key    同一個東西用同一個 key（例如 "power"、"container:x,y,z"），用來判斷是不是同一件事
     * @param noisy  做的時候會不會發出聲音讓鬼聽到方向
     * @return 開始或加入成功
     */
    boolean start(Entity p, String key, String label, int neededTicks, boolean coop, boolean noisy, Location at, Runnable onDone) {
        return startAct(p, key, label, neededTicks, coop, noisy, at, onDone) != null;
    }

    /** 同 start，但回傳互動本身（失敗時回傳 null），可以再設定 onTick、onCancel。 */
    Act startAct(Entity p, String key, String label, int neededTicks, boolean coop, boolean noisy, Location at, Runnable onDone) {
        if (byPlayer.containsKey(p.getUniqueId())) return null;
        Act a = byKey.get(key);
        if (a != null) {
            if (!a.coop || a.who.size() >= m.o.coopMax()) {
                p.sendActionBar(Component.text("有人正在" + a.label, NamedTextColor.GRAY)); // 不是玩家時沒有效果
                return null;
            }
            a.who.put(p.getUniqueId(), p.getLocation());
            byPlayer.put(p.getUniqueId(), a);
            return a;
        }
        a = new Act(key, label, neededTicks, coop, noisy, at, onDone);
        a.who.put(p.getUniqueId(), p.getLocation());
        byKey.put(key, a);
        byPlayer.put(p.getUniqueId(), a);
        if (noisy) m.noise(at);
        return a;
    }

    boolean busy(UUID id) {
        return byPlayer.containsKey(id);
    }

    boolean running(String key) {
        return byKey.containsKey(key);
    }

    /** 中斷某個玩家正在做的事（例如被抓、被煙霧彈打到）。 */
    void cancel(UUID id, String reason) {
        Act a = byPlayer.remove(id);
        if (a == null) return;
        a.who.remove(id);
        Player p = Bukkit.getPlayer(id);
        if (p != null && reason != null) p.sendActionBar(Component.text(a.label + "中斷了（" + reason + "）", NamedTextColor.RED));
        if (a.who.isEmpty()) {
            byKey.remove(a.key);
            a.onCancel.run();
        }
    }

    void cancelAll() {
        byKey.clear();
        byPlayer.clear();
    }

    void tick() {
        // 用複本走一遍：完成時的動作可能會開始新的互動
        for (Act a : new java.util.ArrayList<>(byKey.values())) {
            // 走開、被抓、離線的人退出
            a.who.entrySet().removeIf(e -> {
                Entity p = Bukkit.getEntity(e.getKey());
                boolean ok = p != null && p.isValid() && p.getWorld().equals(e.getValue().getWorld())
                        && p.getLocation().distanceSquared(e.getValue()) <= 0.25
                        && (m.isGhostId(p.getUniqueId()) || p instanceof Player pl && m.isFreeSurvivor(pl));
                if (!ok) {
                    byPlayer.remove(e.getKey());
                    if (p != null) p.sendActionBar(Component.text(a.label + "中斷了", NamedTextColor.RED));
                }
                return !ok;
            });
            if (a.who.isEmpty()) {
                byKey.remove(a.key);
                a.onCancel.run();
                continue;
            }
            double rate = a.coop && a.who.size() >= 2 ? m.o.coopMultiplier() : 1.0;
            a.progress += rate;
            a.onTick.run();
            if (a.noisy && m.tick % 20 == 0) m.noise(a.at);
            Component bar = Component.text(a.label + " " + Match.bar((int) a.progress, a.needed)
                    + (a.who.size() >= 2 ? "（" + a.who.size() + " 人）" : ""), NamedTextColor.YELLOW);
            for (UUID id : a.who.keySet()) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) p.sendActionBar(bar);
            }
            if (a.progress >= a.needed) {
                byKey.remove(a.key);
                for (UUID id : a.who.keySet()) byPlayer.remove(id);
                a.onDone.run();
            }
        }
    }
}
