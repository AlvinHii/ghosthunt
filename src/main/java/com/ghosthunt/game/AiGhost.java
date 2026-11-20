package com.ghosthunt.game;

import com.destroystokyo.paper.entity.Pathfinder;
import com.ghosthunt.building.Building;
import com.ghosthunt.building.Pos;
import com.ghosthunt.game.ai.GhostBrain;
import com.ghosthunt.game.ai.GhostBrain.Goal;
import com.ghosthunt.game.ai.GhostBrain.Kind;
import com.ghosthunt.game.ai.GhostBrain.Point;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Door;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * AI 鬼（PvE）：一隻關掉原版 AI 的怪物，走路用 Paper 內建的尋路，每一步做什麼由 {@link GhostBrain} 決定。
 * 規則跟真人鬼一樣（速度、揮拳減速、看得到的距離、能力的時間），另外每隔一段時間「感應」到最近的逃生者大概在哪。
 */
final class AiGhost {

    private final Match m;
    private final AiGhostSettings c;
    private final GhostBrain brain;
    private final StuckTracker stuck;
    private Mob mob;

    // ---- 記憶 ----
    private Location lastSeen;
    private long lastSeenTick = -1_000_000;
    private UUID lastSeenId;
    private Location noise;
    private long noiseTick = -1_000_000;
    private Location sensed;
    private long sensedUntil;
    private long nextSense;
    private long blindUntil;
    private UUID attackTarget;
    private long attackSince;
    private boolean guardRolled, guard;
    private int jailedBefore;
    private final ArrayDeque<Location> visited = new ArrayDeque<>();
    private Location patrolTarget;
    private final Set<Integer> checkedHoles = new HashSet<>();
    private final Set<Location> followedTunnel = new HashSet<>();

    // ---- 走路 ----
    private Location moveTarget;
    private int stuckCount;
    private Goal goal;

    AiGhost(Match m, AiGhostSettings c) {
        this.m = m;
        this.c = c;
        // 守監獄的機率在這裡擲（每次有人被關擲一次），大腦這邊永遠照做
        this.brain = new GhostBrain(new GhostBrain.Config(c.attackRange(), c.searchTime(), c.searchTime(), 1.0), m.rnd);
        this.stuck = new StuckTracker(Math.max(20, c.stuckTime()), 1.0);
    }

    /** 在 at 生出 AI 鬼。 */
    Mob spawn(Location at) {
        EntityType type;
        try {
            type = EntityType.valueOf(c.mob().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            type = EntityType.HUSK;
        }
        if (type.getEntityClass() == null || !Mob.class.isAssignableFrom(type.getEntityClass())) type = EntityType.HUSK;
        mob = (Mob) at.getWorld().spawnEntity(at, type);
        Bukkit.getMobGoals().removeAllGoals(mob);
        mob.setTarget(null);
        mob.setPersistent(false);
        mob.setRemoveWhenFarAway(false);
        mob.setInvulnerable(true);
        mob.setSilent(false);
        mob.setCanPickupItems(false);
        mob.customName(Component.text("鬼", NamedTextColor.DARK_RED));
        mob.setCustomNameVisible(false);
        if (mob instanceof Zombie z) {
            z.setAdult();
            z.setShouldBurnInDay(false);
            z.setCanBreakDoors(false);
        }
        var follow = mob.getAttribute(Attribute.FOLLOW_RANGE);
        if (follow != null) follow.setBaseValue(128);
        var speed = mob.getAttribute(Attribute.MOVEMENT_SPEED);
        if (speed != null) speed.setBaseValue(c.baseSpeed()); // 0.13 ≈ 玩家跑步的速度，加成和揮拳減速由 Match 處理
        var kb = mob.getAttribute(Attribute.KNOCKBACK_RESISTANCE);
        if (kb != null) kb.setBaseValue(1.0);
        Pathfinder pf = mob.getPathfinder();
        pf.setCanOpenDoors(true);
        pf.setCanPassDoors(true);
        pf.setCanFloat(false);
        dress(mob.getEquipment());
        return mob;
    }

    private static void dress(EntityEquipment eq) {
        if (eq == null) return;
        eq.setHelmet(new ItemStack(Material.WITHER_SKELETON_SKULL));
        for (Material m : new Material[]{Material.LEATHER_CHESTPLATE, Material.LEATHER_LEGGINGS, Material.LEATHER_BOOTS}) {
            ItemStack it = new ItemStack(m);
            if (it.getItemMeta() instanceof LeatherArmorMeta meta) {
                meta.setColor(Color.fromRGB(20, 18, 24));
                it.setItemMeta(meta);
            }
            switch (m) {
                case LEATHER_CHESTPLATE -> eq.setChestplate(it);
                case LEATHER_LEGGINGS -> eq.setLeggings(it);
                default -> eq.setBoots(it);
            }
        }
        eq.setItemInMainHand(null);
        eq.setHelmetDropChance(0);
        eq.setChestplateDropChance(0);
        eq.setLeggingsDropChance(0);
        eq.setBootsDropChance(0);
    }

    Mob mob() {
        return mob;
    }

    void remove() {
        if (mob != null) mob.remove();
        mob = null;
    }

    /** 煙霧彈：一段時間看不到人，正在做的事也中斷。 */
    void blind(int ticks) {
        blindUntil = Math.max(blindUntil, m.tick + ticks);
        if (mob != null) {
            m.acts.cancel(mob.getUniqueId(), null);
            mob.getPathfinder().stopPathfinding();
        }
    }

    /** 逃生者做事發出的聲音（Match.noise 已經判斷過同一層、距離）：記下大概的位置。 */
    void hearNoise(Location at) {
        noise = jitter(at, c.noiseError());
        noiseTick = m.tick;
    }

    // =====================================================================

    void tick() {
        if (mob == null || !mob.isValid()) return;
        if (!m.released) {
            mob.getPathfinder().stopPathfinding();
            return;
        }
        openDoorsAhead();
        boolean busy = m.acts.busy(mob.getUniqueId());
        if (!busy && moveTarget != null && stuck.update(m.tick, mob.getLocation().getX(), mob.getLocation().getY(), mob.getLocation().getZ()))
            onStuck();
        if (m.tick % c.thinkEvery() != 0) return;

        Player seen = perceive();
        if (busy) {
            // 正在做事（拆障礙物、關電源……）時看到人就放下去追
            if (seen != null && seen.getLocation().distance(mob.getLocation()) < 10) m.acts.cancel(mob.getUniqueId(), null);
            else return;
        }
        sense();
        goal = brain.decide(situation(seen));
        act(goal, seen);
    }

    // ---- 感知 ----

    /** 看得到的最近自由逃生者（同一層、距離內、視線沒被擋；被煙霧彈打到時看不到）。 */
    private Player perceive() {
        if (m.tick < blindUntil) return null;
        double range = m.obj.powerOn() ? c.sightRange() : c.sightRangeDark();
        Player best = null;
        double bd = Double.MAX_VALUE;
        for (Player p : m.freeSurvivors()) {
            if (!p.getWorld().equals(mob.getWorld()) || !m.sameFloor(p.getLocation(), mob.getLocation())) continue;
            double d = p.getLocation().distance(mob.getLocation());
            if (d > range || d >= bd || !mob.hasLineOfSight(p)) continue;
            best = p;
            bd = d;
        }
        if (best != null) {
            lastSeen = best.getLocation();
            lastSeenTick = m.tick;
            lastSeenId = best.getUniqueId();
        }
        return best;
    }

    /** 感應（作弊）：每隔一段時間得到最近的自由逃生者大概在哪；那個人會聽到心跳加快。 */
    private void sense() {
        if (nextSense == 0) nextSense = m.tick + c.senseInterval();
        if (sensed != null && (m.tick > sensedUntil || sensed.getWorld().equals(mob.getWorld()) && sensed.distance(mob.getLocation()) < 3)) sensed = null;
        if (m.tick < nextSense) return;
        nextSense = m.tick + c.senseInterval();
        Player best = null;
        double bd = Double.MAX_VALUE;
        for (Player p : m.freeSurvivors()) {
            double d = p.getLocation().distance(mob.getLocation()) + (m.sameFloor(p.getLocation(), mob.getLocation()) ? 0 : 30);
            if (d < bd) {
                bd = d;
                best = p;
            }
        }
        if (best == null) return;
        sensed = walkableNear(jitter(best.getLocation(), c.senseError()));
        sensedUntil = m.tick + 30 * 20;
        best.playSound(best.getLocation(), Sound.ENTITY_WARDEN_HEARTBEAT, 1f, 1.6f);
        Player target = best;
        Bukkit.getScheduler().runTaskLater(m.plugin, () -> target.playSound(target.getLocation(), Sound.ENTITY_WARDEN_HEARTBEAT, 1f, 1.8f), 6L);
        best.sendActionBar(Component.text("你覺得有東西盯上你了……", NamedTextColor.DARK_RED));
    }

    private GhostBrain.Situation situation(Player seen) {
        GhostBrain.Seen s = seen == null ? null
                : new GhostBrain.Seen(seen.getUniqueId(), pt(seen.getLocation()), seen.getLocation().distance(mob.getLocation()));
        List<Point> gates = new ArrayList<>();
        for (Objectives.GateState gs : m.obj.runningUnsabotagedGates()) gates.add(pt(m.loc(gs.g.reader())));
        int jailed = 0;
        for (Match.Survivor sv : m.survivors.values()) if (sv.status == Match.Status.JAILED) jailed++;
        if (jailed > jailedBefore) {
            guardRolled = true;
            guard = m.rnd.nextDouble() < c.guardJailChance();
        }
        if (jailed == 0) guard = false;
        jailedBefore = jailed;
        Location jail = m.obj.jailNearestTo(mob.getLocation());
        return new GhostBrain.Situation(m.tick, m.tick < blindUntil, s, pt(lastSeen), lastSeenTick, pt(noise), noiseTick, pt(sensed),
                m.obj.cardRevealOpen(), m.obj.cardMoveAvailable(), m.obj.powerOn(), m.obj.cutPowerUsed(), gates,
                guard && jailed > 0, pt(jail), pt(mob.getLocation()));
    }

    // ---- 行動 ----

    private void act(Goal g, Player seen) {
        Location here = mob.getLocation();
        switch (g.kind()) {
            case ATTACK -> {
                moveTo(seen.getLocation());
                if (!g.victim().equals(attackTarget)) {
                    attackTarget = g.victim();
                    attackSince = m.tick;
                }
                if (m.tick - attackSince >= c.reaction() && m.tick >= m.fatigueUntil) {
                    mob.swingMainHand();
                    if (seen.getLocation().distance(here) <= c.attackRange() && mob.hasLineOfSight(seen)) m.ghostHit(seen);
                    else m.ghostSwing();
                    attackSince = m.tick + c.reaction();
                }
            }
            case CHASE -> {
                attackTarget = null;
                moveTo(seen.getLocation());
            }
            case SEARCH_LAST_SEEN -> {
                attackTarget = null;
                if (lastSeen != null && lastSeen.distance(here) < 2.5) {
                    if (!followTunnel() && !checkHideout()) wander(lastSeen, 6);
                } else moveTo(lastSeen);
            }
            case INVESTIGATE_NOISE -> {
                if (noise.distance(here) < 2.5) {
                    noise = null;
                    wander(here, 6);
                } else moveTo(noise);
            }
            case GO_SENSED -> moveTo(sensed);
            case MOVE_CARD -> {
                mob.getPathfinder().stopPathfinding();
                moveTarget = null;
                m.obj.ghostMoveCard(mob);
            }
            case SABOTAGE_GATE -> {
                Location reader = toLoc(g.target());
                if (reader.distance(here) <= 2.5) {
                    for (Objectives.GateState gs : m.obj.runningUnsabotagedGates()) {
                        if (m.loc(gs.g.reader()).distance(reader) < 0.5) {
                            mob.getPathfinder().stopPathfinding();
                            m.obj.ghostSabotage(mob, gs);
                        }
                    }
                } else moveTo(reader);
            }
            case CUT_POWER -> {
                Location lever = m.obj.leverLocation();
                if (lever == null) return;
                if (lever.clone().add(0.5, 0, 0.5).distance(here) <= 2.5) {
                    mob.getPathfinder().stopPathfinding();
                    m.obj.ghostCutPower(mob);
                } else moveTo(lever);
            }
            case GUARD_JAIL -> {
                Location jail = toLoc(g.target());
                if (jail.distance(here) > 8) moveTo(jail);
                else wander(jail, 7);
            }
            case PATROL -> patrol();
        }
    }

    /** 追丟時，最後看到的位置在地洞出入口旁邊：有機率走去那條通道的另一端。 */
    private boolean followTunnel() {
        if (lastSeen == null || followedTunnel.contains(lastSeen)) return false;
        followedTunnel.add(lastSeen);
        List<Building.Hatch> hs = m.b.hatches;
        for (int i = 0; i < hs.size(); i++) {
            Location t = m.loc(hs.get(i).trapdoor()).add(0.5, 1, 0.5);
            if (t.distance(lastSeen) > 2 || m.rnd.nextDouble() >= c.followTunnelChance()) continue;
            int other = i ^ 1; // 生成時兩個出入口一組：0-1、2-3……
            if (other >= hs.size()) return false;
            Location end = m.loc(hs.get(other).trapdoor()).add(0.5, 1, 0.5);
            lastSeen = end;
            lastSeenTick = m.tick;
            moveTo(end);
            return true;
        }
        return false;
    }

    /** 追丟時，附近有暗格入口：有機率去打大入口查看。 */
    private boolean checkHideout() {
        for (Building.Room r : m.b.rooms) {
            if (!r.hideout() || m.loc(r.center()).distance(lastSeen) > 8) continue;
            for (int i = 0; i < m.b.crawlHoles.size(); i++) {
                Building.CrawlHole h = m.b.crawlHoles.get(i);
                if (m.holeIndexAt(h.lower()) < 0 || checkedHoles.contains(i)) continue;
                if (m.loc(h.lower()).distance(m.loc(r.center())) > 3) continue;
                checkedHoles.add(i);
                if (m.rnd.nextDouble() >= c.checkHideoutChance()) continue;
                Location hole = m.loc(h.lower()).add(0.5, 0, 0.5);
                if (hole.distance(mob.getLocation()) <= 2.5) {
                    mob.getPathfinder().stopPathfinding();
                    m.ghostWidenHole(i);
                } else {
                    moveTo(hole);
                    checkedHoles.remove(i); // 還沒走到，下次再查
                }
                return true;
            }
        }
        return false;
    }

    /** 巡邏：挑一間最近沒去過的房間（偶爾換樓層）。 */
    private void patrol() {
        Location here = mob.getLocation();
        if (patrolTarget != null && patrolTarget.distance(here) > 2.5 && moveTarget != null) return;
        if (patrolTarget != null) {
            visited.addLast(patrolTarget);
            while (visited.size() > 4) visited.removeFirst();
        }
        List<Location> same = new ArrayList<>(), other = new ArrayList<>();
        for (Building.Room r : m.b.rooms) {
            if (r.hideout() || r.type() == com.ghosthunt.building.RoomType.GHOST_ROOM) continue;
            Location l = m.loc(r.center()).add(0.5, 0, 0.5);
            boolean seenRecently = false;
            for (Location v : visited) if (v.distance(l) < 3) seenRecently = true;
            if (seenRecently || l.distance(here) < 4) continue;
            (m.sameFloor(l, here) ? same : other).add(l);
        }
        List<Location> pool = !other.isEmpty() && (same.isEmpty() || m.rnd.nextDouble() < 0.25) ? other : same;
        if (pool.isEmpty()) return;
        pool.sort(java.util.Comparator.comparingDouble(l -> l.distance(here)));
        patrolTarget = pool.get(m.rnd.nextInt(Math.min(3, pool.size())));
        moveTo(patrolTarget);
    }

    /** 在 center 附近 radius 格內隨便找個走得到的地方。 */
    private void wander(Location center, double radius) {
        if (moveTarget != null && moveTarget.distance(mob.getLocation()) > 2) return;
        moveTo(walkableNear(jitter(center, radius)));
    }

    private void moveTo(Location to) {
        if (to == null) return;
        if (moveTarget == null || moveTarget.distance(to) > 1.5 || !mob.getPathfinder().hasPath()) {
            moveTarget = to.clone();
            mob.getPathfinder().moveTo(to, 1.0);
        }
    }

    /** 路線上前面 1.5 格內關著的雙開門：幫它開門（卡住的門不開，要撞）。 */
    private void openDoorsAhead() {
        Location l = mob.getLocation();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                Block bl = l.getWorld().getBlockAt(l.getBlockX() + dx, l.getBlockY(), l.getBlockZ() + dz);
                if (!(bl.getBlockData() instanceof Door d) || d.isOpen()) continue;
                Pos lower = new Pos(bl.getX(), bl.getY(), bl.getZ());
                if (d.getHalf() == org.bukkit.block.data.Bisected.Half.TOP) lower = lower.add(0, -1, 0);
                if (m.isDoorJammed(lower)) continue;
                for (int dy = 0; dy <= 1; dy++) {
                    Block part = l.getWorld().getBlockAt(lower.x(), lower.y() + dy, lower.z());
                    if (part.getBlockData() instanceof Door pd) {
                        pd.setOpen(true);
                        part.setBlockData(pd, false);
                    }
                }
                l.getWorld().playSound(bl.getLocation(), Sound.BLOCK_WOODEN_DOOR_OPEN, 1f, 0.9f);
            }
        }
    }

    /** 卡住了：先看是不是被擋住（障礙物、卡住的門、蹲伏小洞、地洞），是的話拆；不是就重新找路，一直卡就換目標，最後把它傳回走得到的地方。 */
    private void onStuck() {
        if (breakBlockerNearby()) {
            stuckCount = 0;
            return;
        }
        stuckCount++;
        Location target = moveTarget;
        if (stuckCount >= 6) {
            Location back = walkableNear(mob.getLocation());
            if (back != null) mob.teleport(back);
            stuckCount = 0;
            moveTarget = null;
            m.plugin.getLogger().info("[AI 鬼] 卡住太久，傳回走得到的地方 " + back);
        } else if (stuckCount >= 3) {
            moveTarget = null;
            patrolTarget = null;
            moveTo(walkableNear(jitter(mob.getLocation(), 8)));
        } else if (target != null) {
            moveTarget = null;
            moveTo(target);
        }
    }

    /** 身邊 2 格內有擋路的東西就拆／撞／打大。回傳 true 表示開始做了。 */
    private boolean breakBlockerNearby() {
        Location l = mob.getLocation();
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++)
                for (int dy = 0; dy <= 1; dy++) {
                    Pos p = new Pos(l.getBlockX() + dx, l.getBlockY() + dy, l.getBlockZ() + dz);
                    int bi = m.barricadeAt(p);
                    if (bi >= 0 && m.ghostBreakBarricade(bi)) return true;
                    Pos door = m.jammedDoorAt(p);
                    if (door != null && m.ghostBreakJam(door)) return true;
                    int hole = m.holeIndexAt(p);
                    if (hole >= 0 && lastSeen != null && m.tick - lastSeenTick < c.searchTime() && m.ghostWidenHole(hole)) return true;
                }
        return false;
    }

    // ---- 工具 ----

    private Location jitter(Location at, double r) {
        if (at == null) return null;
        return at.clone().add((m.rnd.nextDouble() * 2 - 1) * r, 0, (m.rnd.nextDouble() * 2 - 1) * r);
    }

    /** 離 at 最近的「走得到的格子」（同一層）。 */
    private Location walkableNear(Location at) {
        if (at == null) return null;
        Building b = m.b;
        int f = Math.max(0, Math.min(b.layout.floors - 1, (int) Math.floor((at.getY() - b.layout.floorY(0) - 1) / b.layout.floorHeight)));
        Set<Pos> tiles = b.walkTiles.get(f);
        if (tiles == null || tiles.isEmpty()) return at;
        Pos best = null;
        double bd = Double.MAX_VALUE;
        for (Pos p : tiles) {
            double dx = p.x() + 0.5 - at.getX(), dz = p.z() + 0.5 - at.getZ();
            double d = dx * dx + dz * dz;
            if (d < bd) {
                bd = d;
                best = p;
            }
        }
        return best == null ? at : m.loc(best).add(0.5, 0, 0.5);
    }

    private static Point pt(Location l) {
        return l == null ? null : new Point(l.getX(), l.getY(), l.getZ());
    }

    private Location toLoc(Point p) {
        return new Location(mob.getWorld(), p.x(), p.y(), p.z());
    }

    String debug() {
        return goal == null ? "（還沒想）" : goal.kind() + (moveTarget == null ? "" : " → " + moveTarget.getBlockX() + "," + moveTarget.getBlockY() + "," + moveTarget.getBlockZ());
    }
}
