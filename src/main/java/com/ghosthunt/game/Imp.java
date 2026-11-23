package com.ghosthunt.game;

import com.ghosthunt.building.Pos;
import com.ghosthunt.game.ai.GridNav;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 小鬼（2026-11-23）：不會抓人，只會干擾。半個人高的黑色小孩，走得比人慢，一直哼歌、咯咯笑（聽得到它在哪）。
 * <ul>
 *   <li>碰到逃生者：正在做的事被打斷，笑得很大聲 → 大鬼聽得到（真正的威脅）；有機率搶走一個道具藏到附近的木桶。</li>
 *   <li>路過打開的門會關上；會去把修好的配電箱關掉（mall2）。</li>
 *   <li>手電筒照到、煙霧彈打到：嚇跑一陣子。蹲著走的人它不會追（碰到還是會碰）。</li>
 * </ul>
 * 一局一隻，開局一段時間後、或大鬼太久沒接觸任何人時才出來。數值在 config 的 imp:。
 */
final class Imp {

    private enum Mode { SEEK, FLEE, MISCHIEF, WANDER }

    private final Match m;
    private final double speed, touchRange, laughRange, seekRange, stealChance, doorCloseChance;
    private final int startAfter, minStart, quietTrigger, touchCooldown, scareTicks, humInterval;
    private final boolean breakers;

    private Mannequin body;
    private Mode mode = Mode.WANDER;
    private List<Integer> path = List.of();
    private int pathIdx;
    private long nextDecide, cooldownUntil, scaredUntil, nextHum, stuckSince;
    private LivingEntity target;
    private Pos targetBreaker;
    /** 自己打開、還沒關的門：走過去以後關上。 */
    private Pos openedDoor;
    private final Map<Pos, Long> doorClosedAt = new HashMap<>();
    private int touches, interrupts, steals, doorsClosed, breakersOff, scares;
    private long spawnedAt = -1;

    Imp(Match m) {
        this.m = m;
        FileConfiguration c = m.plugin.getConfig();
        speed = c.getDouble("imp.speed", 3.2) / 20.0;
        touchRange = c.getDouble("imp.touch-range", 1.3);
        laughRange = c.getDouble("imp.laugh-range", 40);
        seekRange = c.getDouble("imp.seek-range", 48);
        stealChance = c.getDouble("imp.steal-chance", 0.3);
        doorCloseChance = c.getDouble("imp.door-close-chance", 0.5);
        startAfter = c.getInt("imp.start-after", 150) * 20;
        minStart = c.getInt("imp.min-start", 60) * 20;
        quietTrigger = c.getInt("imp.quiet-trigger", 40) * 20;
        touchCooldown = c.getInt("imp.touch-cooldown", 45) * 20;
        scareTicks = c.getInt("imp.scare-time", 20) * 20;
        humInterval = c.getInt("imp.hum-interval", 5) * 20;
        breakers = c.getBoolean("imp.breakers", true);
    }

    /** 這局要不要有小鬼：imp.enabled，PvP 另外要 imp.pvp。 */
    static boolean enabled(Match m) {
        FileConfiguration c = m.plugin.getConfig();
        return c.getBoolean("imp.enabled", true) && (m.aiGhost != null || c.getBoolean("imp.pvp", false));
    }

    LivingEntity entity() {
        return body != null && body.isValid() ? body : null;
    }

    void tick() {
        NavMap nav = m.navForImp();
        if (nav == null) return;
        if (body == null) {
            if (shouldSpawn()) spawn(nav, false);
            return;
        }
        if (!body.isValid()) return;
        if (m.tick >= nextDecide) {
            nextDecide = m.tick + 10;
            decide(nav);
        }
        move(nav);
        if (m.tick >= nextHum) hum();
        touch();
        closeDoorsNearby(nav);
        if (mode == Mode.MISCHIEF && targetBreaker != null && body.getLocation().distance(m.loc(targetBreaker).add(0.5, 0, 0.5)) <= 2.5) {
            if (m.side != null && m.side.breakerOff(targetBreaker)) {
                breakersOff++;
                body.getWorld().playSound(m.loc(targetBreaker).add(0.5, 0.5, 0.5), "ghosthunt:imp.lights_out", SoundCategory.HOSTILE, 1f, pitch());
                snicker();
            }
            targetBreaker = null;
            mode = Mode.WANDER;
            path = List.of();
        }
    }

    private boolean shouldSpawn() {
        if (m.tick >= startAfter) return true;
        return m.tick >= minStart && m.aiGhost != null && m.aiGhost.quietTicks() >= quietTrigger;
    }

    /** 生出來：離所有人至少 25 格的格子。force = 測試指令（馬上生，離最近的人 12 格以上就好）。 */
    boolean spawn(NavMap nav, boolean force) {
        if (body != null) return false;
        List<Location> people = new ArrayList<>();
        for (LivingEntity t : targets(true)) people.add(t.getLocation());
        double minD = force ? 12 : 25;
        Location at = null;
        for (int tries = 0; tries < 600 && at == null; tries++) {
            int i = m.rnd.nextInt(nav.nav.size());
            if (!nav.isMain(i)) continue;
            Location l = nav.loc(i);
            boolean ok = true;
            for (Location p : people) if (p.getWorld().equals(l.getWorld()) && p.distance(l) < minD) ok = false;
            if (ok) at = l;
        }
        if (at == null) return false;
        body = m.world.spawn(at, Mannequin.class, mq -> {
            mq.setInvulnerable(true);
            mq.setPersistent(false);
            mq.setSilent(true);
            mq.setGravity(false);
            mq.setCollidable(false);
            mq.setImmovable(true);
            mq.setDescription(null);
            AttributeInstance scale = mq.getAttribute(Attribute.SCALE);
            if (scale != null) scale.setBaseValue(0.5);
            mq.getEquipment().setHelmet(new ItemStack(Material.WITHER_SKELETON_SKULL));
            mq.getEquipment().setChestplate(dyed(Material.LEATHER_CHESTPLATE));
            mq.getEquipment().setLeggings(dyed(Material.LEATHER_LEGGINGS));
            mq.getEquipment().setBoots(dyed(Material.LEATHER_BOOTS));
        });
        spawnedAt = m.tick;
        nextHum = m.tick + 200; // 登場音樂盒放完再開始哼歌
        // 登場：全圖的逃生者都聽得到（在每個人自己身上播，不帶位置）
        for (Player p : m.survivorPlayers()) p.playSound(p, "ghosthunt:imp.arrive", SoundCategory.HOSTILE, 1f, 1f);
        m.survivorMessage("……遠處傳來小孩的哼歌聲。小鬼出來了：它不會抓人，但被它碰到會笑得很大聲（手電筒、煙霧彈可以趕走它）", NamedTextColor.DARK_PURPLE);
        m.plugin.getLogger().info("[小鬼] 出來了：" + at.getBlockX() + "," + at.getBlockY() + "," + at.getBlockZ());
        return true;
    }

    private static ItemStack dyed(Material mat) {
        ItemStack it = new ItemStack(mat);
        if (it.getItemMeta() instanceof LeatherArmorMeta meta) {
            meta.setColor(Color.fromRGB(0x141414));
            it.setItemMeta(meta);
        }
        return it;
    }

    /** 可以碰的人：自由的逃生者（不在看監視器）＋測試用的假逃生者。includeSneak=false 時不算蹲著的人（追的時候）。 */
    private List<LivingEntity> targets(boolean includeSneak) {
        List<LivingEntity> out = new ArrayList<>();
        for (Player p : m.survivorPlayers()) {
            if (!m.isFreeSurvivor(p) || (m.side != null && m.side.isViewing(p))) continue;
            if (!includeSneak && p.isSneaking()) continue;
            out.add(p);
        }
        out.addAll(m.dummies.alive());
        return out;
    }

    private void decide(NavMap nav) {
        Location here = body.getLocation();
        // 要去關配電箱：走到為止（被嚇到才放棄）
        if (mode == Mode.MISCHIEF && targetBreaker != null && m.tick >= scaredUntil) {
            if (pathDone()) goTo(nav, m.loc(targetBreaker).add(0.5, 0, 0.5));
            if (!path.isEmpty()) return;
            targetBreaker = null;
        }
        if (m.tick < scaredUntil || m.tick < cooldownUntil) {
            // 剛碰完人（還沒決定去哪）：有修好的配電箱就 40% 去關掉，不然逃到遠處
            if (mode != Mode.FLEE && mode != Mode.MISCHIEF && m.tick >= scaredUntil && breakers && m.side != null) {
                Pos z = m.side.nearestRepairedBreaker(here);
                if (z != null && m.rnd.nextDouble() < 0.4) {
                    targetBreaker = z;
                    mode = Mode.MISCHIEF;
                    goTo(nav, m.loc(z).add(0.5, 0, 0.5));
                    if (!path.isEmpty()) return;
                    targetBreaker = null;
                }
            }
            if (mode != Mode.FLEE || pathDone()) {
                mode = Mode.FLEE;
                targetBreaker = null;
                goTo(nav, farPoint(nav, here, 20));
            }
            return;
        }
        // 找最近、沒蹲著的人
        LivingEntity best = null;
        double bd = seekRange;
        for (LivingEntity t : targets(false)) {
            if (!t.getWorld().equals(here.getWorld())) continue;
            double d = t.getLocation().distance(here);
            if (d < bd) {
                bd = d;
                best = t;
            }
        }
        if (best != null) {
            mode = Mode.SEEK;
            target = best;
            // 每秒重找一次路（人會動）
            if (m.tick % 20 < 10 || pathDone()) goTo(nav, best.getLocation());
            return;
        }
        target = null;
        if (mode != Mode.WANDER || pathDone()) {
            mode = Mode.WANDER;
            goTo(nav, farPoint(nav, here, 10));
        }
    }

    private boolean pathDone() {
        return pathIdx >= path.size();
    }

    private Location farPoint(NavMap nav, Location from, double minD) {
        for (int tries = 0; tries < 200; tries++) {
            int i = m.rnd.nextInt(nav.nav.size());
            if (!nav.isMain(i)) continue;
            Location l = nav.loc(i);
            if (l.distance(from) >= minD && l.distance(from) <= minD + 40) return l;
        }
        return from;
    }

    private void goTo(NavMap nav, Location to) {
        int a = nav.nearest(body.getLocation(), 3), b = nav.nearest(to, 3);
        if (a < 0 || b < 0) {
            path = List.of();
            return;
        }
        path = nav.nav.path(a, b, cost(nav));
        pathIdx = path.isEmpty() ? 0 : 1;
        stuckSince = m.tick;
    }

    /** 小鬼的找路：上鎖、卡住的門、擋住的門口、地洞都不走；蹲伏小洞鑽得過（它很小）。 */
    private GridNav.Cost cost(NavMap nav) {
        return e -> switch (e.kind()) {
            case WALK, STAIR, HOLE -> 0;
            case DOOR -> {
                Pos d = nav.doors.get(e.ref());
                yield m.isDoorLocked(d) || m.isDoorJammed(d) ? Double.POSITIVE_INFINITY : 0.2;
            }
            case BARRICADE -> m.barricadeIndexBarricaded(e.ref()) ? Double.POSITIVE_INFINITY : 0;
            case HATCH -> Double.POSITIVE_INFINITY;
        };
    }

    private void move(NavMap nav) {
        if (pathDone()) return;
        Location here = body.getLocation();
        int node = path.get(pathIdx);
        // 下一段是門：關著就打開（小鬼自己開門，走過去以後可能關上）
        if (pathIdx > 0) {
            GridNav.Edge e = nav.nav.edge(path.get(pathIdx - 1), node);
            if (e != null && e.kind() == GridNav.Kind.DOOR) {
                Pos d = nav.doors.get(e.ref());
                if (m.block(d).getBlockData() instanceof org.bukkit.block.data.Openable o && !o.isOpen()) {
                    m.setDoorOpenPublic(d, true);
                    m.world.playSound(m.loc(d), Sound.BLOCK_WOODEN_DOOR_OPEN, 0.6f, 1.3f);
                    openedDoor = d;
                }
            }
        }
        Location to = nav.loc(node);
        org.bukkit.util.Vector v = to.toVector().subtract(here.toVector());
        double len = v.length();
        if (len <= speed) {
            Location next = to.clone();
            next.setYaw(here.getYaw());
            body.teleport(next);
            pathIdx++;
            stuckSince = m.tick;
            // 走過自己開的門兩格以後關上
            if (openedDoor != null && m.loc(openedDoor).distance(next) > 2.2) {
                if (m.rnd.nextDouble() < doorCloseChance && shut(openedDoor)) doorsClosed++;
                openedDoor = null;
            }
            return;
        }
        v.multiply(speed / len);
        Location next = here.clone().add(v);
        next.setYaw((float) Math.toDegrees(Math.atan2(-v.getX(), v.getZ())));
        next.setPitch(0);
        body.teleport(next);
        if (m.tick - stuckSince > 100) path = List.of(); // 卡住 5 秒：重找
    }

    /** 路過別人打開的門（1.5 格內）：有機率關上（同一扇 20 秒內不再關）。 */
    private void closeDoorsNearby(NavMap nav) {
        if (m.tick % 10 != 0 || doorCloseChance <= 0) return;
        Location here = body.getLocation();
        for (Pos d : m.b.doors) {
            if (d.equals(openedDoor)) continue;
            Location dl = m.loc(d).add(0.5, 0, 0.5);
            if (!dl.getWorld().equals(here.getWorld()) || dl.distance(here) > 1.8) continue;
            if (!(m.block(d).getBlockData() instanceof org.bukkit.block.data.Openable o) || !o.isOpen()) continue;
            if (m.tick - doorClosedAt.getOrDefault(d, -1_000_000L) < 400) continue;
            doorClosedAt.put(d, m.tick);
            if (m.rnd.nextDouble() < doorCloseChance * 0.5 && shut(d)) doorsClosed++;
        }
    }

    /** 關門（有人站在門口就不關）。 */
    private boolean shut(Pos d) {
        Location dl = m.loc(d).add(0.5, 0, 0.5);
        for (Player p : m.survivorPlayers()) if (p.getWorld().equals(dl.getWorld()) && p.getLocation().distance(dl) < 1.2) return false;
        m.setDoorOpenPublic(d, false);
        m.world.playSound(dl, Sound.BLOCK_WOODEN_DOOR_CLOSE, 1f, 0.8f);
        snicker();
        return true;
    }

    /** 平常的聲音：哼歌（每 hum-interval ～ 1.6 倍秒），偶爾換成遠處的笑聲。大鬼不當成線索。 */
    private void hum() {
        nextHum = m.tick + humInterval + m.rnd.nextInt(Math.max(1, humInterval * 3 / 5));
        Location l = body.getLocation().add(0, 0.5, 0);
        body.getWorld().playSound(l, m.rnd.nextDouble() < 0.2 ? "ghosthunt:imp.distant_laugh" : "ghosthunt:imp.hum", SoundCategory.HOSTILE, 1f, pitch());
    }

    /** 搗蛋（關門、關配電箱）後的偷笑。 */
    private void snicker() {
        if (body != null) body.getWorld().playSound(body.getLocation().add(0, 0.5, 0), "ghosthunt:imp.snicker", SoundCategory.HOSTILE, 1f, pitch());
    }

    private float pitch() {
        return 0.9f + m.rnd.nextFloat() * 0.2f;
    }

    private void touch() {
        if (m.tick < cooldownUntil || m.tick < scaredUntil || mode == Mode.MISCHIEF) return; // 去關配電箱的路上不碰人
        Location here = body.getLocation();
        for (LivingEntity t : targets(true)) {
            if (!t.getWorld().equals(here.getWorld()) || t.getLocation().distance(here) > touchRange) continue;
            touches++;
            cooldownUntil = m.tick + touchCooldown;
            laugh(here);
            if (t instanceof Player p) {
                if (m.acts.busy(p.getUniqueId())) {
                    m.acts.cancel(p.getUniqueId(), "小鬼");
                    interrupts++;
                }
                String stolen = steal(p);
                p.showTitle(Title.title(Component.text("小鬼碰到你了", NamedTextColor.DARK_PURPLE),
                        Component.text(stolen != null ? "它搶走了你的" + Items.label(stolen) + "，藏到附近的木桶裡" : "它笑得好大聲……鬼聽到了", NamedTextColor.GRAY),
                        Title.Times.times(Duration.ZERO, Duration.ofMillis(1500), Duration.ofMillis(400))));
            }
            mode = Mode.WANDER; // decide() 馬上決定：去關配電箱或逃到遠處
            path = List.of();
            nextDecide = m.tick;
            return;
        }
    }

    private void laugh(Location at) {
        at.getWorld().playSound(at, "ghosthunt:imp.laugh", SoundCategory.HOSTILE, 1f, pitch());
        at.getWorld().spawnParticle(Particle.SOUL, at.clone().add(0, 0.6, 0), 8, 0.3, 0.3, 0.3, 0.02);
        m.impLaugh(at, laughRange);
    }

    /** 有機率搶走一個道具（不搶門禁卡、屋頂鑰匙、保險絲這種逃生關鍵），藏到附近的木桶。 */
    private String steal(Player p) {
        if (m.rnd.nextDouble() >= stealChance) return null;
        List<String> have = new ArrayList<>();
        for (String id : new String[]{Items.SMOKE, Items.SPEED, Items.FLASHLIGHT, Items.SCANNER, Items.COIN, Items.MEDKIT, Items.BARRICADE})
            if (Items.count(p, id) > 0) have.add(id);
        if (have.isEmpty()) return null;
        String id = have.get(m.rnd.nextInt(have.size()));
        if (!m.obj.hideItem(p.getLocation(), id)) return null;
        Items.take(p, id);
        steals++;
        return id;
    }

    /** 手電筒照到、煙霧彈打到：嚇跑一陣子。 */
    void scare(Player by, String how) {
        if (body == null || m.tick < scaredUntil) return;
        scares++;
        scaredUntil = m.tick + scareTicks;
        mode = Mode.FLEE;
        targetBreaker = null;
        path = List.of();
        nextDecide = m.tick;
        body.getWorld().playSound(body.getLocation(), Sound.ENTITY_VEX_HURT, SoundCategory.HOSTILE, 1.5f, 0.7f);
        if (by != null) by.sendActionBar(Component.text(how + "把小鬼嚇跑了", NamedTextColor.YELLOW));
    }

    void cleanup() {
        if (body != null) body.remove();
        body = null;
    }

    Map<String, Object> stats() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("impSpawnedSec", spawnedAt < 0 ? -1 : spawnedAt / 20);
        s.put("impTouches", touches);
        s.put("impInterrupts", interrupts);
        s.put("impSteals", steals);
        s.put("impDoorsClosed", doorsClosed);
        s.put("impBreakersOff", breakersOff);
        s.put("impScared", scares);
        return s;
    }

    String debug() {
        if (body == null) return "小鬼還沒出來";
        Location l = body.getLocation();
        return "小鬼 " + mode + " @" + l.getBlockX() + "," + l.getBlockY() + "," + l.getBlockZ() + "｜碰 " + touches + "（打斷 " + interrupts + "、搶 " + steals
                + "）｜關門 " + doorsClosed + "｜關配電箱 " + breakersOff + "｜被嚇跑 " + scares;
    }
}
