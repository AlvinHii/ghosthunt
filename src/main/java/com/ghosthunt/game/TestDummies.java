package com.ghosthunt.game;

import com.ghosthunt.building.Pos;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Villager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 假逃生者（測試用，/gh dummy）：村民被 AI 鬼當成逃生者，看得到、聽得到、抓得到。
 * 平常在房間之間走，偶爾停下來「做事」（發出聲音）；看到鬼就往遠離鬼的方向跑（跟玩家跑步一樣快）。
 * 被抓到不會進監獄，只記一筆，然後傳到遠處繼續。
 */
final class TestDummies {

    private final Match m;
    private final List<Villager> dummies = new ArrayList<>();
    private final Map<UUID, Long> busyUntil = new HashMap<>(), nextWander = new HashMap<>(), fleeingUntil = new HashMap<>();
    int caught;

    TestDummies(Match m) {
        this.m = m;
    }

    List<Villager> alive() {
        dummies.removeIf(v -> !v.isValid());
        return dummies;
    }

    boolean isDummy(LivingEntity e) {
        return e instanceof Villager v && dummies.contains(v);
    }

    /** 正在做事（AI 看得到的行為）。 */
    boolean busy(LivingEntity e) {
        return m.tick < busyUntil.getOrDefault(e.getUniqueId(), 0L);
    }

    /** 正在跑（給 AI 的「聽腳步聲」用）。 */
    boolean running(LivingEntity e) {
        return m.tick < fleeingUntil.getOrDefault(e.getUniqueId(), 0L);
    }

    void spawn(int n) {
        List<Pos> tiles = new ArrayList<>();
        for (Set<Pos> s : m.b.spawnTiles.values()) tiles.addAll(s);
        if (tiles.isEmpty()) return;
        for (int i = 0; i < n; i++) {
            Location at = m.loc(tiles.get(m.rnd.nextInt(tiles.size())));
            Villager v = at.getWorld().spawn(at, Villager.class, e -> {
                e.setPersistent(false);
                e.setInvulnerable(true);
                e.setAdult();
                e.customName(Component.text("假逃生者 " + (dummies.size() + 1), NamedTextColor.GREEN));
                e.setCustomNameVisible(true);
            });
            Bukkit.getMobGoals().removeAllGoals(v);
            var speed = v.getAttribute(Attribute.MOVEMENT_SPEED);
            if (speed != null) speed.setBaseValue(m.s.ai().baseSpeed()); // 跟玩家跑步一樣快
            var step = v.getAttribute(Attribute.STEP_HEIGHT);
            if (step != null) step.setBaseValue(0.6);
            dummies.add(v);
        }
    }

    void tick(LivingEntity ghost) {
        if (m.tick % 10 != 0 || ghost == null) return;
        NavMap nav = m.aiGhost == null ? null : m.aiGhost.navMap();
        for (Villager v : alive()) {
            UUID id = v.getUniqueId();
            boolean seesGhost = v.getWorld().equals(ghost.getWorld()) && v.getLocation().distance(ghost.getLocation()) < 14 && v.hasLineOfSight(ghost);
            if (seesGhost) {
                busyUntil.remove(id);
                fleeingUntil.put(id, m.tick + 40);
                Location to = fleeTarget(v, ghost, nav);
                if (to != null) v.getPathfinder().moveTo(to, 1.0);
                continue;
            }
            if (busy(v)) continue;
            if (m.tick >= nextWander.getOrDefault(id, 0L) || !v.getPathfinder().hasPath()) {
                nextWander.put(id, m.tick + 100 + m.rnd.nextInt(100));
                if (m.rnd.nextInt(4) == 0) {
                    // 停下來「做事」4 秒：發出聲音（像翻木桶）
                    v.getPathfinder().stopPathfinding();
                    busyUntil.put(id, m.tick + 80);
                    m.noise(v.getLocation(), Match.NOISE_LOW);
                    continue;
                }
                Location to = randomNode(v.getLocation(), 6, 18, nav);
                if (to != null) v.getPathfinder().moveTo(to, 0.75);
            }
        }
    }

    private Location fleeTarget(Mob v, LivingEntity ghost, NavMap nav) {
        Location best = null;
        double bd = -1;
        for (int i = 0; i < 25; i++) {
            Location c = randomNode(v.getLocation(), 6, 14, nav);
            if (c == null) continue;
            double d = c.distance(ghost.getLocation()) - c.distance(v.getLocation()) * 0.3;
            if (d > bd) {
                bd = d;
                best = c;
            }
        }
        return best;
    }

    private Location randomNode(Location near, double min, double max, NavMap nav) {
        if (nav == null) return null;
        for (int i = 0; i < 30; i++) {
            int id = m.rnd.nextInt(nav.nav.size());
            if (!nav.isMain(id)) continue;
            Location l = nav.loc(id);
            if (!l.getWorld().equals(near.getWorld())) continue;
            double d = l.distance(near);
            if (d >= min && d <= max && Math.abs(l.getY() - near.getY()) < 6) return l;
        }
        return null;
    }

    /** 被 AI 鬼抓到：記一筆，傳到遠處。 */
    void caught(LivingEntity e, LivingEntity ghost) {
        caught++;
        m.broadcast("［測試］" + (e.customName() == null ? "假逃生者" : net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(e.customName()))
                + " 被抓到了（第 " + caught + " 次）", NamedTextColor.GRAY);
        List<Pos> tiles = new ArrayList<>();
        for (Set<Pos> s : m.b.spawnTiles.values()) tiles.addAll(s);
        Location far = null;
        double bd = -1;
        for (int i = 0; i < 40 && !tiles.isEmpty(); i++) {
            Location c = m.loc(tiles.get(m.rnd.nextInt(tiles.size())));
            double d = c.distance(ghost.getLocation());
            if (d > bd) {
                bd = d;
                far = c;
            }
        }
        if (far != null) e.teleport(far);
    }

    void removeAll() {
        dummies.forEach(org.bukkit.entity.Entity::remove);
        dummies.clear();
    }
}
