package com.ghosthunt.game;

import com.ghosthunt.building.Pos;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.data.Levelled;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** 逃生者的道具：煙霧彈、加速藥水、手電筒、掃描器（障礙物在 Match）。 */
final class ItemUse {

    private final Match m;
    private final ObjectiveSettings o;
    /** 手電筒：誰開著、剩多少電、目前放的光源方塊。 */
    private final Map<UUID, Integer> battery = new HashMap<>();
    private final Map<UUID, Boolean> flashOn = new HashMap<>();
    private final Map<UUID, Pos> flashLight = new HashMap<>();
    private final Map<UUID, Long> scanUntil = new HashMap<>();
    private final Map<UUID, Location> scanTarget = new HashMap<>();
    private long lastFlashHit;

    ItemUse(Match m) {
        this.m = m;
        this.o = m.o;
    }

    /** 右鍵使用道具。回傳 true 表示要取消原版的動作。煙霧彈讓原版把雪球丟出去。 */
    boolean use(Player p, String id) {
        Match.Survivor sv = m.survivors.get(p.getUniqueId());
        if (sv == null || sv.status != Match.Status.FREE) return true;
        switch (id) {
            case Items.SMOKE -> {
                return false; // 原版丟雪球，打到東西時在 onSmokeHit 處理
            }
            case Items.SPEED -> {
                Items.take(p, Items.SPEED);
                m.boost(sv, o.speedBonus(), o.speedDuration());
                p.playSound(p.getLocation(), Sound.ENTITY_GENERIC_DRINK, 1f, 1.2f);
                p.sendActionBar(Component.text("加速！", NamedTextColor.GREEN));
            }
            case Items.FLASHLIGHT -> {
                int left = battery.computeIfAbsent(p.getUniqueId(), k -> o.flashBattery());
                if (left <= 0) {
                    p.sendActionBar(Component.text("手電筒沒電了", NamedTextColor.GRAY));
                    return true;
                }
                boolean on = !flashOn.getOrDefault(p.getUniqueId(), false);
                flashOn.put(p.getUniqueId(), on);
                if (!on) clearLight(p.getUniqueId());
                p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, on ? 1.4f : 0.8f);
            }
            case Items.SCANNER -> {
                Location target = m.obj.scanTarget(p.getLocation());
                if (target == null) {
                    p.sendActionBar(Component.text("掃描不到東西", NamedTextColor.GRAY));
                    return true;
                }
                Items.take(p, Items.SCANNER);
                scanUntil.put(p.getUniqueId(), m.tick + o.scannerDuration());
                scanTarget.put(p.getUniqueId(), target);
                m.world.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 2f, 1.8f); // 會發出聲音
                m.noise(p.getLocation());
                p.sendActionBar(Component.text("掃描中……跟著藍色光點走", NamedTextColor.BLUE));
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    /** 煙霧彈落地：附近的鬼失明，正在做的事被打斷。 */
    void onSmokeHit(Location at) {
        m.world.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, at, 80, 1.6, 1.2, 1.6, 0.01);
        m.world.playSound(at, Sound.BLOCK_FIRE_EXTINGUISH, 2f, 0.7f);
        LivingEntity g = m.ghostEntity();
        if (g == null || !g.getWorld().equals(at.getWorld())) return;
        if (g.getLocation().distance(at) <= o.smokeRadius() || g.getEyeLocation().distance(at) <= o.smokeRadius()) {
            g.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, o.smokeBlind(), 0, false, false));
            if (m.aiGhost != null) m.aiGhost.smoked(o.smokeBlind()); // AI 鬼：一段時間看不到人（也記下「被煙霧彈甩掉」）
            m.acts.cancel(g.getUniqueId(), "煙霧彈");
            m.ghostMessage(Component.text("被煙霧彈打到了！", NamedTextColor.GRAY));
        }
    }

    void tick(LivingEntity g) {
        for (Map.Entry<UUID, Boolean> e : flashOn.entrySet()) {
            if (!e.getValue()) continue;
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null || !m.isFreeSurvivor(p) || Items.count(p, Items.FLASHLIGHT) == 0) {
                e.setValue(false);
                clearLight(e.getKey());
                continue;
            }
            int left = battery.merge(e.getKey(), -1, Integer::sum);
            if (left <= 0) {
                e.setValue(false);
                clearLight(e.getKey());
                p.sendActionBar(Component.text("手電筒沒電了", NamedTextColor.GRAY));
                continue;
            }
            if (m.tick % 10 == 0) p.sendActionBar(Component.text("手電筒 電量 " + Match.bar(left, o.flashBattery()), NamedTextColor.YELLOW));
            // 開著時拿掉自己的黑暗效果（原版黑暗會壓暗整個畫面，只放光源照不亮）
            p.removePotionEffect(PotionEffectType.DARKNESS);
            moveLight(p);
            flashNightmare(p, g);
            if (m.aiGhost != null && beamHits(p, g)) m.aiGhost.flashed(p); // 手電筒照 AI 鬼的臉
        }
        // 掃描器：往目標方向冒藍色光點（只有自己看得到）
        var it = scanUntil.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null || m.tick > e.getValue()) {
                it.remove();
                scanTarget.remove(e.getKey());
                continue;
            }
            if (m.tick % 4 != 0) continue;
            Location from = p.getEyeLocation().subtract(0, 0.4, 0);
            Vector dir = scanTarget.get(e.getKey()).toVector().subtract(from.toVector());
            if (dir.lengthSquared() < 0.01) continue;
            dir.normalize();
            for (int i = 1; i <= 6; i++) {
                p.spawnParticle(Particle.DUST, from.clone().add(dir.clone().multiply(i * 0.6)), 1, 0, 0, 0, 0,
                        new Particle.DustOptions(org.bukkit.Color.fromRGB(80, 160, 255), 1f));
            }
        }
    }

    /** 光源方塊放在視線打到的地方（或前方），跟著視線移動；只放在空氣格，不會蓋掉大樓的燈。 */
    private void moveLight(Player p) {
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection();
        RayTraceResult hit = p.getWorld().rayTraceBlocks(eye, dir, o.flashRange(), FluidCollisionMode.NEVER, true);
        Location spot = hit != null ? hit.getHitPosition().toLocation(p.getWorld()).subtract(dir.clone().multiply(0.6))
                : eye.clone().add(dir.clone().multiply(o.flashRange() * 0.7));
        Pos target = new Pos(spot.getBlockX(), spot.getBlockY(), spot.getBlockZ());
        Pos old = flashLight.get(p.getUniqueId());
        if (target.equals(old)) return;
        clearLight(p.getUniqueId());
        Block bl = m.block(target);
        if (bl.getType() != Material.AIR) return;
        Levelled d = (Levelled) Bukkit.createBlockData(Material.LIGHT);
        d.setLevel(13);
        bl.setBlockData(d, false);
        flashLight.put(p.getUniqueId(), target);
    }

    private void clearLight(UUID id) {
        Pos old = flashLight.remove(id);
        if (old != null && m.block(old).getType() == Material.LIGHT) m.block(old).setType(Material.AIR, false);
    }

    /** 手電筒照到夢魘：短暫打斷牠的追蹤。 */
    /** 手電筒的光照不照得到 g（距離、角度、視線）。 */
    private boolean beamHits(Player p, LivingEntity g) {
        if (!p.getWorld().equals(g.getWorld())) return false;
        Vector to = g.getEyeLocation().toVector().subtract(p.getEyeLocation().toVector());
        if (to.length() > o.flashRange() || to.lengthSquared() < 1e-6) return false;
        double angle = Math.toDegrees(p.getEyeLocation().getDirection().angle(to));
        return angle <= o.flashAngle() / 2 && p.hasLineOfSight(g);
    }

    private void flashNightmare(Player p, LivingEntity g) {
        if (m.nightmare == null || !m.nightmare.active || m.tick - lastFlashHit < 20) return;
        if (!p.getWorld().equals(g.getWorld())) return;
        Vector to = g.getEyeLocation().toVector().subtract(p.getEyeLocation().toVector());
        double dist = to.length();
        if (dist > o.flashRange() || dist < 0.1) return;
        double angle = Math.toDegrees(p.getEyeLocation().getDirection().angle(to));
        if (angle > o.flashAngle() / 2 || !p.hasLineOfSight(g)) return;
        lastFlashHit = m.tick;
        m.nightmare.disrupt(o.flashDisrupt());
        p.sendActionBar(Component.text("照到夢魘了！", NamedTextColor.YELLOW));
    }

    void cleanup() {
        for (UUID id : new java.util.ArrayList<>(flashLight.keySet())) clearLight(id);
        flashOn.clear();
    }
}
