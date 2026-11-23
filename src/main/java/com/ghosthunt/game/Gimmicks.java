package com.ghosthunt.game;

import com.ghosthunt.building.Building;
import com.ghosthunt.building.Pos;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;

/**
 * 地圖專屬機關（固定地圖的 [interactions]，目前是度假村）：
 * <ul>
 *   <li>迎賓鈴：逃生者右鍵鐘 → 遠處（≥ lure-min-distance 格）一座涼亭響起廣播，連續 lure-seconds 秒；
 *       AI 鬼不管多遠都聽得到（高等級聲音），全場冷卻 bell-cooldown 秒。真人鬼聽得到聲音，畫面下方也有方向箭頭。</li>
 *   <li>球車遠光燈：逃生者按球車上的按鈕 → 朝自己面對的方向射出扇形強光（cart-range 格、半角 cart-angle 度）；
 *       照到的鬼 cart-effect 秒看不到人（AI 接煙霧彈那套；真人鬼失明）＋緩速 II。每台 cart-uses 次。會鳴笛（鬼聽得到）。</li>
 *   <li>溫泉蒸氣閥門：逃生者拉拉桿 → 周圍 steam-radius 格濃煙 steam-seconds 秒；
 *       鬼在煙裡、或人在煙裡，AI 鬼都看不到（貼臉 2 格內除外）；真人鬼在煙裡會失明。閥門聲 16 格內聽得到。每座冷卻 steam-cooldown 秒。</li>
 * </ul>
 * 數值在 config.yml 的 gimmicks。
 */
final class Gimmicks {

    private final Match m;
    private final int bellCooldown, lureTicks, lureMinDist, cartUses, cartEffect, steamTicks, steamCooldown;
    private final double cartRange, cartAngle, steamRadius;

    private long bellReadyAt;
    private Location lureAt;
    private long lureUntil;
    private final Map<Integer, Integer> cartUsed = new HashMap<>();
    private final Map<Integer, Long> beamUntil = new HashMap<>();
    private final Map<Integer, Long> steamUntil = new HashMap<>(), steamReadyAt = new HashMap<>();

    private Gimmicks(Match m) {
        this.m = m;
        FileConfiguration c = m.plugin.getConfig();
        bellCooldown = (int) (20 * c.getDouble("gimmicks.bell-cooldown", 45));
        lureTicks = (int) (20 * c.getDouble("gimmicks.lure-seconds", 8));
        lureMinDist = c.getInt("gimmicks.lure-min-distance", 25);
        cartUses = c.getInt("gimmicks.cart-uses", 2);
        cartRange = c.getDouble("gimmicks.cart-range", 8);
        cartAngle = c.getDouble("gimmicks.cart-angle", 35);
        cartEffect = (int) (20 * c.getDouble("gimmicks.cart-effect", 3));
        steamTicks = (int) (20 * c.getDouble("gimmicks.steam-seconds", 10));
        steamRadius = c.getDouble("gimmicks.steam-radius", 6);
        steamCooldown = (int) (20 * c.getDouble("gimmicks.steam-cooldown", 60));
    }

    static Gimmicks create(Match m) {
        Building b = m.b;
        return b.bells.isEmpty() && b.carts.isEmpty() && b.valves.isEmpty() ? null : new Gimmicks(m);
    }

    /** 右鍵方塊；是機關就處理並回傳 true（取消原版動作）。 */
    boolean onClick(Player p, Block clicked) {
        Pos at = new Pos(clicked.getX(), clicked.getY(), clicked.getZ());
        if (m.scene != null) at = m.scene.alias(at); // 3D 模型：點球車任何一格都算按到開關
        if (m.b.bells.contains(at)) {
            if (m.isFreeSurvivor(p)) ringBell(p, at);
            return true;
        }
        for (int i = 0; i < m.b.carts.size(); i++) {
            if (!m.b.carts.get(i).trigger().equals(at)) continue;
            if (m.isFreeSurvivor(p)) headlights(p, i);
            return true;
        }
        for (int i = 0; i < m.b.valves.size(); i++) {
            if (!m.b.valves.get(i).trigger().equals(at)) continue;
            if (m.isFreeSurvivor(p)) steam(p, i);
            return true;
        }
        return false;
    }

    // ---- 迎賓鈴 ----

    private void ringBell(Player p, Pos bell) {
        if (m.b.lureSpots.isEmpty()) return;
        if (m.tick < bellReadyAt) {
            if (p == null) return;
            p.sendActionBar(Component.text("廣播系統冷卻中（" + (bellReadyAt - m.tick + 19) / 20 + " 秒）", NamedTextColor.GRAY));
            return;
        }
        Location from = m.loc(bell);
        // 離鐘夠遠的聲源裡隨機挑一個；都不夠遠就挑最遠的
        java.util.List<Pos> far = m.b.lureSpots.stream().filter(s -> m.loc(s).distance(from) >= lureMinDist).toList();
        Pos spot = far.isEmpty()
                ? m.b.lureSpots.stream().max(java.util.Comparator.comparingDouble(s -> m.loc(s).distance(from))).orElseThrow()
                : far.get(m.rnd.nextInt(far.size()));
        lureAt = m.loc(spot).add(0.5, 1, 0.5);
        lureUntil = m.tick + lureTicks;
        bellReadyAt = m.tick + bellCooldown;
        m.world.playSound(from.clone().add(0.5, 0.5, 0.5), Sound.BLOCK_BELL_USE, 0.5f, 1.4f);
        if (p != null) p.sendActionBar(Component.text("遠處的涼亭響起度假村廣播，鬼會被吸過去一陣子", NamedTextColor.GOLD));
        m.plugin.getLogger().info("[機關] 迎賓鈴 " + bell + " → 聲源 " + spot);
        m.stats.merge("bellRung", 1, (x, y) -> (Integer) x + (Integer) y);
    }

    // ---- 球車遠光燈 ----

    private void headlights(Player p, int i) {
        int used = cartUsed.getOrDefault(i, 0);
        Building.Gadget cart = m.b.carts.get(i);
        Location c = m.loc(cart.center()).add(0.5, 1.2, 0.5);
        if (used >= cartUses) {
            p.sendActionBar(Component.text("球車沒電了", NamedTextColor.GRAY));
            m.world.playSound(c, Sound.BLOCK_DISPENSER_FAIL, 1f, 1.2f);
            return;
        }
        cartUsed.put(i, used + 1);
        beamUntil.put(i, m.tick + Math.max(40, cartEffect));
        Vector dir = p.getLocation().getDirection().setY(0);
        if (dir.lengthSquared() < 1e-6) dir = new Vector(1, 0, 0);
        dir.normalize();
        // 鳴笛＋光
        m.world.playSound(c, Sound.BLOCK_NOTE_BLOCK_DIDGERIDOO, 3f, 0.6f);
        m.world.playSound(c, Sound.BLOCK_NOTE_BLOCK_DIDGERIDOO, 3f, 0.75f);
        m.noise(c, Match.NOISE_HIGH);
        for (double a = -cartAngle; a <= cartAngle; a += cartAngle / 3) {
            Vector v = dir.clone().rotateAroundY(Math.toRadians(a));
            for (double d = 1; d <= cartRange; d += 0.7) m.world.spawnParticle(Particle.END_ROD, c.clone().add(v.clone().multiply(d)), 1, 0.05, 0.05, 0.05, 0);
        }
        LivingEntity g = m.ghostEntity();
        boolean hit = g != null && inCone(c, dir, g);
        if (hit) {
            g.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, cartEffect, 1, false, false));
            if (m.aiGhost != null) m.aiGhost.blind(cartEffect);
            else g.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, cartEffect + 20, 0, false, false));
            m.world.playSound(g.getLocation(), Sound.ENTITY_ENDERMAN_HURT, 1.2f, 0.5f);
            m.stats.merge("cartHits", 1, (x, y) -> (Integer) x + (Integer) y);
        }
        p.sendActionBar(Component.text((hit ? "閃到鬼了！" : "遠光燈沒照到鬼") + "（這台還剩 " + (cartUses - used - 1) + " 次）",
                hit ? NamedTextColor.GOLD : NamedTextColor.GRAY));
    }

    /** 球車的遠光燈是不是正亮著（3D 模型換亮燈的樣子）。 */
    boolean beamOn(int i) {
        return m.tick < beamUntil.getOrDefault(i, 0L);
    }

    private boolean inCone(Location from, Vector dir, LivingEntity e) {
        Location eye = e.getEyeLocation();
        Vector to = eye.toVector().subtract(from.toVector());
        double dist = Math.hypot(to.getX(), to.getZ());
        if (dist > cartRange || Math.abs(to.getY()) > 3) return false;
        if (dist > 0.5) {
            Vector flat = new Vector(to.getX(), 0, to.getZ()).normalize();
            if (Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, flat.dot(dir))))) > cartAngle) return false;
        }
        var hit = from.getWorld().rayTraceBlocks(from, to.clone().normalize(), to.length(), FluidCollisionMode.NEVER, true, m::rayHits);
        return hit == null || hit.getHitBlock() == null;
    }

    // ---- 蒸氣閥門 ----

    private void steam(Player p, int i) {
        if (m.tick < steamUntil.getOrDefault(i, -1L)) {
            p.sendActionBar(Component.text("蒸氣還在噴", NamedTextColor.GRAY));
            return;
        }
        long ready = steamReadyAt.getOrDefault(i, -1L);
        if (m.tick < ready) {
            p.sendActionBar(Component.text("閥門還在洩壓（" + (ready - m.tick + 19) / 20 + " 秒）", NamedTextColor.GRAY));
            return;
        }
        steamUntil.put(i, m.tick + steamTicks);
        steamReadyAt.put(i, m.tick + steamTicks + steamCooldown);
        Location c = center(i);
        m.world.playSound(c, Sound.BLOCK_FIRE_EXTINGUISH, 1.2f, 0.6f);
        // 閥門聲：沿路 16 格內的 AI 鬼聽得到；真人鬼照一般聲音規則
        if (m.aiGhost != null) m.aiGhost.hearSound(c, Match.NOISE_MID, 16);
        else m.noise(c, Match.NOISE_MID);
        p.sendActionBar(Component.text("溫泉蒸氣噴出來了！煙裡鬼看不到人（" + steamTicks / 20 + " 秒）", NamedTextColor.GOLD));
        m.stats.merge("steamUsed", 1, (x, y) -> (Integer) x + (Integer) y);
    }

    private Location center(int i) {
        return m.loc(m.b.valves.get(i).center()).add(0.5, 0, 0.5);
    }

    /** 這個位置在噴著的蒸氣裡嗎（同一層、水平距離 steam-radius 內）。 */
    boolean inSmoke(Location at) {
        for (Map.Entry<Integer, Long> e : steamUntil.entrySet()) {
            if (m.tick >= e.getValue()) continue;
            Location c = center(e.getKey());
            if (m.sameFloor(c, at) && Match.horiz(c, at) <= steamRadius) return true;
        }
        return false;
    }

    /** 測試用（/gh lure）：沒有玩家也敲第一個鐘；回傳聲源位置（沒有鐘就 null）。 */
    Location debugLure() {
        if (m.b.bells.isEmpty()) return null;
        bellReadyAt = 0;
        ringBell(null, m.b.bells.get(m.rnd.nextInt(m.b.bells.size())));
        return lureAt;
    }

    /** 自我檢查：方塊有沒有放對、蒸氣範圍、聲源距離。 */
    java.util.List<String> selfTest() {
        java.util.List<String> out = new java.util.ArrayList<>();
        Building b = m.b;
        int bad = 0;
        for (Pos p : b.bells) if (m.block(p).getType() != org.bukkit.Material.BELL) bad++;
        for (Building.Gadget g : b.carts) if (m.block(g.trigger()).getType() != org.bukkit.Material.STONE_BUTTON) bad++;
        for (Building.Gadget g : b.valves) if (m.block(g.trigger()).getType() != org.bukkit.Material.LEVER) bad++;
        StringBuilder far = new StringBuilder();
        for (Pos bell : b.bells) {
            long n = b.lureSpots.stream().filter(s -> m.loc(s).distance(m.loc(bell)) >= lureMinDist).count();
            far.append(far.isEmpty() ? "" : "、").append(n);
        }
        out.add("地圖機關：迎賓鈴 " + b.bells.size() + "（夠遠的聲源 " + far + " 個）｜聲源 " + b.lureSpots.size() + "｜球車 " + b.carts.size()
                + "｜蒸氣閥門 " + b.valves.size() + (bad == 0 ? "｜開關方塊都在" : "｜有 " + bad + " 個開關方塊不見了"));
        if (!b.valves.isEmpty()) {
            steamUntil.put(0, m.tick + 1);
            Location c = center(0);
            boolean in = inSmoke(c.clone().add(steamRadius - 0.5, 0, 0)), out2 = inSmoke(c.clone().add(steamRadius + 1, 0, 0));
            steamUntil.clear();
            out.add("蒸氣範圍：邊緣內 " + (in ? "在煙裡" : "不在（錯）") + "｜邊緣外 " + (out2 ? "在煙裡（錯）" : "不在"));
        }
        return out;
    }

    // ---- 每 tick ----

    void tick() {
        // 迎賓鈴的廣播：每秒響一次，AI 鬼不管多遠都聽得到
        if (lureAt != null && m.tick < lureUntil && (lureUntil - m.tick) % 20 == 0) {
            float pitch = 0.8f + 0.1f * (int) ((lureUntil - m.tick) / 20 % 3);
            m.world.playSound(lureAt, Sound.BLOCK_NOTE_BLOCK_CHIME, 4f, pitch);
            m.world.playSound(lureAt, Sound.BLOCK_NOTE_BLOCK_BELL, 3f, pitch);
            m.world.spawnParticle(Particle.NOTE, lureAt.clone().add(0, 1.5, 0), 6, 0.6, 0.4, 0.6, 1);
            m.lureNoise(lureAt.clone().add(0, -1, 0)); // 腳的高度：不然聲源在 2F 平台底下時，AI 會把聲音對到 2F 去
        }
        if (m.tick % 4 == 0) {
            for (Map.Entry<Integer, Long> e : steamUntil.entrySet()) {
                if (m.tick >= e.getValue()) continue;
                Location c = center(e.getKey());
                for (int k = 0; k < 14; k++) {
                    double a = m.rnd.nextDouble() * Math.PI * 2, r = Math.sqrt(m.rnd.nextDouble()) * steamRadius;
                    m.world.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, c.clone().add(Math.cos(a) * r, 0.3 + m.rnd.nextDouble() * 2, Math.sin(a) * r),
                            0, 0, 0.03, 0, 1);
                }
            }
        }
        // 真人鬼在煙裡：失明
        if (m.tick % 10 == 0 && m.aiGhost == null && m.ghostEntity() instanceof Player gp && inSmoke(gp.getLocation()))
            gp.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 30, 0, false, false));
    }
}
