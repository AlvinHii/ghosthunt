package com.ghosthunt.game;

import com.ghosthunt.building.Building;
import com.ghosthunt.building.Pos;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.FaceAttachable;
import org.bukkit.block.data.type.Switch;
import org.bukkit.block.data.type.TrapDoor;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 逃生流程：總電源 → 門禁卡 → 大門（企劃書 v3／v3.1）。
 * 容器（木桶）每局重新分配內容：2 張門禁卡（盡量不同樓層）、依人數的道具、幾個裝道具的上鎖箱（誘餌）。
 */
final class Objectives {

    private static final BlockFace[] SIDES = {BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST};

    private final Match m;
    private final Building b;
    private final ObjectiveSettings o;

    // ---- 容器 ----
    private final Map<Pos, String> content = new HashMap<>();
    private final Set<Pos> searched = new HashSet<>();
    private final Set<Pos> locked = new HashSet<>();

    // ---- 電源 ----
    Pos lever;
    boolean powerOn;
    private boolean everOn;
    private boolean ghostCutUsed;
    private long blackoutAt = -1;
    private int blackouts;
    private long graceUntil;

    // ---- 門禁卡 ----
    private final Map<Integer, Integer> moves = new HashMap<>();
    private long nextReveal = Long.MAX_VALUE;
    long revealUntil = -1;
    private boolean movedThisReveal;
    /** 已經在保全室授權過的門禁卡編號。 */
    final Set<Integer> authorized = new HashSet<>();
    /** 亮起期間標出門禁卡箱子的發光方塊（只有鬼看得到，外框會穿牆）。 */
    private final Map<Pos, BlockDisplay> markers = new HashMap<>();

    // ---- 大門 ----
    static final class GateState {
        final Building.Gate g;
        final int index;
        boolean unlocked, opened, sabotaged;
        int remaining = -1; // >= 0 表示正在倒數
        BossBar bar;

        GateState(Building.Gate g, int index) {
            this.g = g;
            this.index = index;
        }

        boolean running() {
            return remaining >= 0;
        }
    }

    final List<GateState> gates = new ArrayList<>();

    Objectives(Match m) {
        this.m = m;
        this.b = m.b;
        this.o = m.o;
    }

    int floorOf(Pos p) {
        return Math.max(0, Math.min(b.layout.floors - 1, (p.y() - b.layout.floorY(0) - 1) / b.layout.floorHeight));
    }

    // =====================================================================
    // 開局
    // =====================================================================

    void setup() {
        // 復原上一局：容器上的鎖、拉桿、大門
        for (Pos p : b.containers) {
            Directional d = (Directional) Bukkit.createBlockData(Material.BARREL);
            d.setFacing(BlockFace.UP);
            m.block(p).setBlockData(d, false);
            Block above = m.block(p.add(0, 1, 0));
            if (above.getType() == Material.IRON_TRAPDOOR) above.setType(Material.AIR, false);
        }
        if (b.placedLever != null) m.block(b.placedLever).setType(Material.AIR, false);
        for (Map.Entry<Pos, BlockData> e : b.gateBlocks.entrySet()) m.block(e.getKey()).setBlockData(e.getValue(), false);
        for (int i = 0; i < b.gates.size(); i++) gates.add(new GateState(b.gates.get(i), i));

        placePowerSwitch();
        assignContents();
        m.refreshLights();
    }

    /** 從電力室的候選位置挑一個，在旁邊的牆上（眼睛高度）裝拉桿。 */
    private void placePowerSwitch() {
        List<Pos> cands = new ArrayList<>(b.powerCandidates);
        Collections.shuffle(cands, m.rnd);
        for (Pos p : cands) {
            for (BlockFace f : SIDES) {
                Pos wall = p.add(f.getModX(), 1, f.getModZ());
                Pos at = p.add(0, 1, 0);
                if (!m.block(wall).getType().isOccluding() || !m.block(at).getType().isAir()) continue;
                Switch sw = (Switch) Bukkit.createBlockData(Material.LEVER);
                sw.setAttachedFace(FaceAttachable.AttachedFace.WALL);
                sw.setFacing(f.getOppositeFace());
                sw.setPowered(false);
                m.block(at).setBlockData(sw, false);
                lever = at;
                b.placedLever = at;
                return;
            }
        }
        m.plugin.getLogger().warning("找不到可以裝總電源開關的牆（電力室候選 " + b.powerCandidates.size() + " 個）");
    }

    private void assignContents() {
        List<Pos> all = new ArrayList<>(b.containers);
        Collections.shuffle(all, m.rnd);
        // 門禁卡：盡量放在不同樓層
        Set<Integer> usedFloors = new HashSet<>();
        int card = 1;
        for (Pos p : all) {
            if (card > o.cards()) break;
            if (usedFloors.add(floorOf(p)) || usedFloors.size() >= b.layout.floors) {
                content.put(p, Items.KEYCARD + card++);
            }
        }
        // 道具：依逃生者人數
        List<String> loot = new ArrayList<>();
        for (Map.Entry<String, List<Integer>> e : o.spawn().entrySet()) {
            int n = GameSettings.bySurvivors(e.getValue(), m.survivors.size());
            for (int i = 0; i < n; i++) loot.add(e.getKey());
        }
        Collections.shuffle(loot, m.rnd);
        List<Pos> free = new ArrayList<>();
        for (Pos p : all) if (!content.containsKey(p)) free.add(p);
        int i = 0;
        for (String it : loot) {
            if (i >= free.size()) break;
            content.put(free.get(i++), it);
        }
        // 誘餌上鎖箱：挑有道具的箱子鎖起來，「上鎖」不等於「卡在這裡」
        int decoys = 0;
        for (Pos p : free) {
            if (decoys >= o.decoys()) break;
            if (content.containsKey(p)) {
                lock(p);
                decoys++;
            }
        }
    }

    // =====================================================================
    // 容器
    // =====================================================================

    boolean isContainer(Block block) {
        return block.getType() == Material.BARREL && b.containers.contains(pos(block));
    }

    void onContainer(Player p, Block block) {
        Pos pos = pos(block);
        if (!m.isFreeSurvivor(p)) return;
        Location at = block.getLocation().add(0.5, 0.5, 0.5);
        String key = "container:" + pos;
        if (locked.contains(pos)) {
            m.acts.start(p, key, "撬開上鎖箱", o.pry(), false, true, at, () -> {
                unlock(pos);
                collect(p, pos);
            });
        } else if (searched.contains(pos) && !content.containsKey(pos)) {
            p.sendActionBar(Component.text("這個翻過了，空的", NamedTextColor.GRAY));
        } else {
            m.acts.start(p, key, "翻箱子", o.search(), false, true, at, () -> collect(p, pos));
        }
    }

    private void collect(Player p, Pos pos) {
        m.world.playSound(m.loc(pos), Sound.BLOCK_BARREL_OPEN, 1f, 1f);
        String c = content.get(pos);
        if (c == null) {
            searched.add(pos);
            p.sendActionBar(Component.text("什麼都沒有", NamedTextColor.GRAY));
            return;
        }
        if (c.startsWith(Items.KEYCARD)) {
            int n = Integer.parseInt(c.substring(Items.KEYCARD.length()));
            content.remove(pos);
            searched.add(pos);
            p.getInventory().addItem(Items.keycard(n, !authRequired() || authorized.contains(n)));
            p.showTitle(Title.title(Component.text("找到門禁卡！", NamedTextColor.AQUA),
                    Component.text(authRequired() && !authorized.contains(n) ? "先拿到保全室的終端機授權，再去大門刷"
                            : "拿去大門旁的讀卡機刷（要先開電源）", NamedTextColor.GRAY)));
            p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f);
            m.survivorMessage(p.getName() + " 找到了門禁卡 #" + n + "。", NamedTextColor.AQUA);
            m.stat("firstCardAt", m.tick / 20, false);
            return;
        }
        if (Items.count(p, c) >= o.maxOf(c)) {
            p.sendActionBar(Component.text("裡面有" + Items.label(c) + "，但你帶不下了", NamedTextColor.GOLD));
            return;
        }
        content.remove(pos);
        searched.add(pos);
        p.getInventory().addItem(Items.of(c));
        p.sendActionBar(Component.text("找到 " + Items.label(c), NamedTextColor.GREEN));
    }

    private void lock(Pos pos) {
        locked.add(pos);
        TrapDoor t = (TrapDoor) Bukkit.createBlockData(Material.IRON_TRAPDOOR);
        t.setHalf(Bisected.Half.BOTTOM);
        t.setOpen(false);
        Block above = m.block(pos.add(0, 1, 0));
        if (above.getType().isAir()) above.setBlockData(t, false);
    }

    private void unlock(Pos pos) {
        locked.remove(pos);
        Block above = m.block(pos.add(0, 1, 0));
        if (above.getType() == Material.IRON_TRAPDOOR) above.setType(Material.AIR, false);
        m.world.playSound(m.loc(pos), Sound.BLOCK_IRON_TRAPDOOR_OPEN, 1f, 0.8f);
    }

    // =====================================================================
    // 電源
    // =====================================================================

    boolean isLever(Block block) {
        return lever != null && pos(block).equals(lever);
    }

    void onLever(Player p) {
        Location at = m.loc(lever);
        if (m.isGhost(p)) {
            if (!powerOn) {
                p.sendActionBar(Component.text("電源本來就沒開", NamedTextColor.GRAY));
            } else if (ghostCutUsed) {
                p.sendActionBar(Component.text("這局已經關過一次電源了", NamedTextColor.GRAY));
            } else if (m.tick < graceUntil) {
                p.sendActionBar(Component.text("電源剛修好，" + ((graceUntil - m.tick) / 20 + 1) + " 秒內關不掉", NamedTextColor.GRAY));
            } else {
                m.acts.start(p, "ghost-cut", "關電源", o.ghostCutPower(), false, false, at, () -> {
                    ghostCutUsed = true;
                    setPower(false, "鬼把電源關掉了！");
                });
            }
            return;
        }
        if (!m.isFreeSurvivor(p)) return;
        if (powerOn) {
            p.sendActionBar(Component.text("電源已經開著", NamedTextColor.GRAY));
            return;
        }
        int needed = everOn ? o.powerRepair() : o.powerOn();
        m.acts.start(p, "power", everOn ? "修電源" : "開電源", needed, true, true, at,
                () -> setPower(true, everOn ? "電源修好了！" : "總電源開啟了！讀卡機有電了"));
    }

    private void setPower(boolean on, String message) {
        powerOn = on;
        if (lever != null && m.block(lever).getBlockData() instanceof Switch sw) {
            sw.setPowered(on);
            m.block(lever).setBlockData(sw, false);
        }
        if (on) {
            if (!everOn) {
                everOn = true;
                blackoutAt = m.tick + o.blackoutMin() + m.rnd.nextInt(Math.max(1, o.blackoutMaxDelay() - o.blackoutMin()));
                m.stat("powerOnAt", m.tick / 20, false);
            }
            graceUntil = m.tick + o.blackoutGrace();
            m.world.playSound(m.loc(lever), Sound.BLOCK_BEACON_ACTIVATE, 3f, 1f);
        } else {
            for (Player p : m.participants()) p.playSound(p.getLocation(), Sound.BLOCK_BEACON_DEACTIVATE, 1f, 0.6f);
        }
        m.refreshLights();
        m.broadcast(message, on ? NamedTextColor.YELLOW : NamedTextColor.DARK_RED);
    }

    // =====================================================================
    // 門禁卡
    // =====================================================================

    void onRelease() {
        nextReveal = m.tick + o.revealInterval();
    }

    void onGhostMoveCard(Player g) {
        if (m.tick > revealUntil) {
            g.sendActionBar(Component.text("要等門禁卡位置亮起才能搬（還要 " + Math.max(0, (nextReveal - m.tick) / 20) + " 秒）",
                    NamedTextColor.GRAY));
            return;
        }
        if (movedThisReveal) {
            g.sendActionBar(Component.text("這次亮起已經搬過一張了", NamedTextColor.GRAY));
            return;
        }
        if (cardToMove(g) == null) {
            g.sendActionBar(Component.text("沒有可以搬的卡（卡在逃生者身上，或已經搬過了）", NamedTextColor.GRAY));
            return;
        }
        // 只要在亮起期間開始就算數，進度可以超過亮起的時間
        m.acts.start(g, "move-card", "搬卡", o.moveChannel(), false, false, g.getLocation(), () -> {
            Pos from = cardToMove(g);
            if (from == null) return;
            String card = content.get(from);
            Pos to = farthestFromSurvivors(from);
            if (to == null) {
                g.sendActionBar(Component.text("找不到可以放的箱子，卡留在原地", NamedTextColor.GRAY));
                return;
            }
            int n = Integer.parseInt(card.substring(Items.KEYCARD.length()));
            content.remove(from);
            content.put(to, card);
            searched.remove(to);
            lock(to);
            moves.merge(n, 1, Integer::sum);
            movedThisReveal = true;
            m.world.playSound(m.loc(from), Sound.BLOCK_CHAIN_PLACE, 3f, 0.6f); // 原本的箱子會發出聲音
            g.sendActionBar(Component.text("門禁卡 #" + n + " 搬到 " + (floorOf(to) + 1) + "F，鎖起來了", NamedTextColor.DARK_AQUA));
        });
    }

    /** 離鬼最近、還在箱子裡、還沒被搬過的卡所在的箱子。 */
    private Pos cardToMove(Player g) {
        Pos best = null;
        double bd = Double.MAX_VALUE;
        for (Map.Entry<Pos, String> e : content.entrySet()) {
            if (!e.getValue().startsWith(Items.KEYCARD)) continue;
            int n = Integer.parseInt(e.getValue().substring(Items.KEYCARD.length()));
            if (moves.getOrDefault(n, 0) >= o.movesPerCard()) continue;
            double d = m.loc(e.getKey()).distance(g.getLocation());
            if (d < bd) {
                bd = d;
                best = e.getKey();
            }
        }
        return best;
    }

    /** 離「最近的逃生者」最遠的箱子（直線距離），優先挑空箱子。 */
    private Pos farthestFromSurvivors(Pos exclude) {
        List<Location> people = new ArrayList<>();
        for (Player p : m.freeSurvivors()) people.add(p.getLocation());
        Pos best = null;
        double bd = -1;
        for (int pass = 0; pass < 2 && best == null; pass++) {
            for (Pos c : b.containers) {
                if (c.equals(exclude)) continue;
                String inside = content.get(c);
                if (inside != null && (pass == 0 || inside.startsWith(Items.KEYCARD))) continue;
                double d = people.isEmpty() ? m.rnd.nextDouble() : Double.MAX_VALUE;
                for (Location l : people) d = Math.min(d, m.loc(c).distance(l));
                if (d > bd) {
                    bd = d;
                    best = c;
                }
            }
        }
        return best;
    }

    /** 拿卡的人被抓／淘汰／逃出／離線：卡藏回同一層附近的箱子（不算鬼的搬卡次數）。 */
    void dropCards(Player p, Location near) {
        int n;
        while ((n = Items.keycardOn(p)) != 0) {
            Items.take(p, Items.KEYCARD + n);
            Pos to = containerNear(near);
            if (to == null) continue;
            content.put(to, Items.KEYCARD + n);
            searched.remove(to);
            m.survivorMessage("門禁卡 #" + n + " 掉了，藏在 " + (floorOf(to) + 1) + "F 的某個箱子裡。", NamedTextColor.AQUA);
        }
    }

    private Pos containerNear(Location near) {
        int floor = floorOf(new Pos(near.getBlockX(), near.getBlockY(), near.getBlockZ()));
        List<Pos> close = new ArrayList<>(), sameFloor = new ArrayList<>(), any = new ArrayList<>();
        for (Pos c : b.containers) {
            String inside = content.get(c);
            if (inside != null && inside.startsWith(Items.KEYCARD)) continue;
            any.add(c);
            if (floorOf(c) != floor) continue;
            sameFloor.add(c);
            if (m.loc(c).distance(near) <= o.droppedCardRadius()) close.add(c);
        }
        for (List<Pos> l : List.of(close, sameFloor, any)) {
            if (!l.isEmpty()) return l.get(m.rnd.nextInt(l.size()));
        }
        return null;
    }

    // =====================================================================
    // 大門
    // =====================================================================

    GateState gateAt(Block block) {
        Pos p = pos(block);
        for (GateState gs : gates) if (gs.g.reader().equals(p)) return gs;
        return null;
    }

    void onReader(Player p, GateState gs) {
        String where = (gs.g.floor() + 1) + "F 大門";
        Location at = m.loc(gs.g.reader());
        if (gs.opened) {
            p.sendActionBar(Component.text(where + "已經開了，快逃！", NamedTextColor.GREEN));
            return;
        }
        if (m.isGhost(p)) {
            if (!gs.running()) {
                p.sendActionBar(Component.text(where + "沒有在倒數", NamedTextColor.GRAY));
            } else if (gs.sabotaged) {
                p.sendActionBar(Component.text("這扇門已經弄壞過一次了", NamedTextColor.GRAY));
            } else {
                Interactions.Act a = m.acts.startAct(p, "sabotage:" + gs.index, "弄壞大門", o.ghostSabotage(), false, false, at, () -> {
                    gs.sabotaged = true;
                    stopCountdown(gs);
                    m.broadcast(where + "故障了！倒數歸零，逃生者站 " + o.restartGate() / 20 + " 秒就能重新啟動（不用卡）。", NamedTextColor.RED);
                    m.world.playSound(at, Sound.BLOCK_ANVIL_LAND, 3f, 0.5f);
                });
                if (a != null) m.survivorMessage("鬼正在弄壞" + where + "！丟煙霧彈可以打斷。", NamedTextColor.RED);
            }
            return;
        }
        if (!m.isFreeSurvivor(p)) return;
        if (gs.running()) {
            p.sendActionBar(Component.text(where + "倒數中，剩 " + (gs.remaining / 20 + 1) + " 秒", NamedTextColor.YELLOW));
            return;
        }
        if (gs.unlocked) {
            m.acts.start(p, "gate:" + gs.index, "重新啟動大門", o.restartGate(), false, true, at, () -> startCountdown(gs));
            return;
        }
        if (Items.keycardOn(p) == 0) {
            p.sendActionBar(Component.text("要門禁卡才能刷", NamedTextColor.GRAY));
            return;
        }
        if (usableCard(p) == 0) {
            p.sendActionBar(Component.text("這張卡還沒授權：先拿到保全室的終端機授權", NamedTextColor.GRAY));
            return;
        }
        if (!powerOn) {
            p.sendActionBar(Component.text("讀卡機沒電，先去電力室開總電源", NamedTextColor.GRAY));
            return;
        }
        m.acts.start(p, "gate:" + gs.index, "刷卡", o.swipe(), false, true, at, () -> {
            int n = usableCard(p);
            if (n == 0) return;
            Items.take(p, Items.KEYCARD + n);
            gs.unlocked = true;
            startCountdown(gs);
        });
    }

    private void startCountdown(GateState gs) {
        gs.remaining = o.openCountdown();
        String where = (gs.g.floor() + 1) + "F 大門";
        gs.bar = BossBar.bossBar(Component.text(where + " 開啟中", NamedTextColor.GOLD), 0f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
        for (Player p : m.participants()) p.showBossBar(gs.bar);
        m.broadcast(where + "開始倒數 " + gs.remaining / 20 + " 秒！警報響了，鬼知道是哪一扇。", NamedTextColor.GOLD);
        Player g = m.ghost();
        if (g != null) g.showTitle(Title.title(Component.empty(), Component.text(where + "開始倒數", NamedTextColor.RED),
                Title.Times.times(Duration.ZERO, Duration.ofSeconds(2), Duration.ofMillis(500))));
    }

    private void stopCountdown(GateState gs) {
        gs.remaining = -1;
        if (gs.bar != null) for (Player p : Bukkit.getOnlinePlayers()) p.hideBossBar(gs.bar);
    }

    private void open(GateState gs) {
        stopCountdown(gs);
        gs.opened = true;
        for (Pos p : gs.g.opening()) m.block(p).setType(Material.AIR, false);
        m.world.playSound(m.loc(gs.g.center()), Sound.BLOCK_IRON_DOOR_OPEN, 6f, 0.6f);
        m.broadcast((gs.g.floor() + 1) + "F 大門打開了！穿過大門就算逃出。", NamedTextColor.GREEN);
        m.stat("gateOpenedAt", m.tick / 20, true);
    }

    /** 逃生者走出已經打開的大門了嗎（在大樓外面、靠近那扇門的出口）。 */
    GateState escapedThrough(Player p) {
        Location l = p.getLocation();
        var L = b.layout;
        boolean outside = l.getX() < L.ox || l.getZ() < L.oz || l.getX() >= L.ox + L.size || l.getZ() >= L.oz + L.size;
        if (!outside) return null;
        for (GateState gs : gates) {
            if (gs.opened && Match.horiz(l, m.loc(gs.g.exit())) < 4 && Math.abs(l.getY() - gs.g.exit().y()) < 3) return gs;
        }
        return null;
    }

    // =====================================================================
    // 每 tick
    // =====================================================================

    void tick(Player g) {
        // 隨機斷電：一局最多幾次，修好後一段時間內不會再斷
        if (powerOn && blackoutAt >= 0 && blackouts < o.blackoutMax() && m.tick >= blackoutAt && m.tick >= graceUntil) {
            blackouts++;
            setPower(false, "停電了！整棟回到緊急照明，到電力室修電源（站 " + o.powerRepair() / 20 + " 秒）。");
        }
        // 門禁卡位置亮起（只有鬼看得到）
        if (m.released && m.tick >= nextReveal) {
            revealUntil = m.tick + o.revealDuration();
            nextReveal = m.tick + o.revealInterval();
            movedThisReveal = false;
            g.showTitle(Title.title(Component.empty(), Component.text("門禁卡位置亮起（拿「搬卡」右鍵可以搬走一張）", NamedTextColor.AQUA),
                    Title.Times.times(Duration.ZERO, Duration.ofSeconds(2), Duration.ofMillis(300))));
        }
        if (m.tick <= revealUntil) {
            if (m.tick % 5 == 0) {
                syncMarkers(g);
                for (Pos p : cardPositions()) {
                    Location l = m.loc(p).add(0, 1, 0);
                    for (int i = 0; i < 6; i++) g.spawnParticle(Particle.END_ROD, l.clone().add(0, i * 0.8, 0), 1, 0.05, 0.05, 0.05, 0);
                }
            }
        } else if (!markers.isEmpty()) {
            clearMarkers();
        }
        // 大門倒數
        for (GateState gs : gates) {
            if (!gs.running()) continue;
            gs.remaining--;
            if (gs.bar != null) gs.bar.progress(Math.max(0f, Math.min(1f, 1f - gs.remaining / (float) o.openCountdown())));
            if (m.tick % 20 == 0) m.world.playSound(m.loc(gs.g.center()), Sound.BLOCK_BELL_USE, 4f, 1.4f);
            if (gs.remaining <= 0) open(gs);
        }
    }

    List<String> selfTestReport() {
        List<String> out = new ArrayList<>();
        out.add("總電源拉桿：" + (lever == null ? "沒有（找不到牆）" : lever + " → " + m.block(lever).getType()));
        out.add("保全室終端機 " + b.securityTerminals.size() + " 台（" + (authRequired() ? "門禁卡要授權" : "不用授權") + "）｜可推倒的櫃子 "
                + b.toppleSpots.size() + " 個｜雙開門 " + b.doors.size() / 2 + " 組（可以卡門）"); 
        long cards = content.values().stream().filter(c -> c.startsWith(Items.KEYCARD)).count();
        Map<String, Integer> items = new HashMap<>();
        for (String c : content.values()) if (!c.startsWith(Items.KEYCARD)) items.merge(Items.label(c), 1, Integer::sum);
        out.add("門禁卡 " + cards + " 張，樓層：" + content.entrySet().stream().filter(e -> e.getValue().startsWith(Items.KEYCARD))
                .map(e -> (floorOf(e.getKey()) + 1) + "F").toList());
        out.add("道具：" + items + "｜上鎖箱 " + locked.size() + " 個（鎖：" + locked.stream()
                .filter(p -> m.block(p.add(0, 1, 0)).getType() == Material.IRON_TRAPDOOR).count() + " 個有鐵活板門）");
        out.add("大門 " + gates.size() + " 扇：" + gates.stream().map(g -> (g.g.floor() + 1) + "F 讀卡機 "
                + m.block(g.g.reader()).getType()).toList());
        return out;
    }

    /** 模擬開電源 → 鬼關電源 → 開大門，確認方塊有跟著變。 */
    List<String> selfTestActions() {
        List<String> out = new ArrayList<>();
        setPower(true, "（自我檢查）開電源");
        out.add("開電源後：拉桿 " + (lever != null && m.block(lever).getBlockData() instanceof Switch sw && sw.isPowered())
                + "，燈光 " + m.ambientLight());
        setPower(false, "（自我檢查）關電源");
        if (!gates.isEmpty()) {
            GateState gs = gates.get(0);
            open(gs);
            boolean clear = gs.g.opening().stream().allMatch(p -> m.block(p).getType().isAir());
            out.add("開大門後：開口" + (clear ? "清空了" : "沒清空！"));
            for (Map.Entry<Pos, BlockData> e : b.gateBlocks.entrySet()) m.block(e.getKey()).setBlockData(e.getValue(), false);
        }
        return out;
    }

    private List<Pos> cardPositions() {
        List<Pos> out = new ArrayList<>();
        for (Map.Entry<Pos, String> e : content.entrySet()) if (e.getValue().startsWith(Items.KEYCARD)) out.add(e.getKey());
        return out;
    }

    /**
     * 門禁卡箱子上放一個發光的方塊展示實體：只給鬼看（其他人看不到），發光外框會穿牆、不受亮度影響，
     * 不同樓層、隔好幾面牆也看得到（原本的粒子會被牆擋住、離太遠也不會畫）。
     */
    private void syncMarkers(Player g) {
        List<Pos> cards = cardPositions();
        markers.entrySet().removeIf(e -> {
            if (cards.contains(e.getKey()) && e.getValue().isValid()) return false;
            e.getValue().remove();
            return true;
        });
        for (Pos p : cards) {
            if (markers.containsKey(p)) continue;
            BlockData data = m.block(p).getBlockData();
            BlockDisplay d = m.world.spawn(m.loc(p), BlockDisplay.class, e -> {
                e.setBlock(data);
                e.setPersistent(false);
                e.setVisibleByDefault(false);
                e.setGlowing(true);
                e.setGlowColorOverride(org.bukkit.Color.AQUA);
                e.setBrightness(new Display.Brightness(15, 15));
                e.setViewRange(4f);
                e.setTransformation(new Transformation(new Vector3f(-0.02f, -0.02f, -0.02f), new AxisAngle4f(),
                        new Vector3f(1.04f, 1.04f, 1.04f), new AxisAngle4f()));
            });
            g.showEntity(m.plugin, d);
            markers.put(p, d);
        }
    }

    private void clearMarkers() {
        markers.values().forEach(org.bukkit.entity.Entity::remove);
        markers.clear();
    }

    /** 門禁卡在哪：「2F 西北 23 格」，給鬼看的。 */
    private String cardDirections(Player g) {
        Location me = g.getLocation();
        List<String> parts = new ArrayList<>();
        for (Pos p : cardPositions()) {
            Location l = m.loc(p);
            double dx = l.getX() + 0.5 - me.getX(), dz = l.getZ() + 0.5 - me.getZ();
            double dist = Math.hypot(dx, dz);
            String dir = dist < 3 ? "這附近" : compass(dx, dz);
            parts.add((floorOf(p) + 1) + "F " + dir + " " + Math.round(dist) + " 格");
        }
        return String.join("｜", parts);
    }

    private static String compass(double dx, double dz) {
        String[] names = {"北", "東北", "東", "東南", "南", "西南", "西", "西北"};
        double deg = (Math.toDegrees(Math.atan2(dx, -dz)) + 360) % 360; // 0 = 北（-z），順時針
        return names[(int) Math.round(deg / 45.0) % 8];
    }

    String ghostHud(Player g) {
        if (m.tick <= revealUntil) return "門禁卡亮起：" + cardDirections(g) + (movedThisReveal ? "（這次已搬過）" : "（拿搬卡右鍵）");
        return "下次門禁卡亮起：" + Math.max(0, (nextReveal - m.tick) / 20) + " 秒" + (powerOn ? "" : "｜停電中");
    }

    // =====================================================================
    // 門禁卡授權（保全室的終端機）
    // =====================================================================

    /** 要不要授權：設定打開而且大樓有保全室終端機（舊的九宮格大樓沒有）。 */
    boolean authRequired() {
        return o.requireAuth() && !b.securityTerminals.isEmpty();
    }

    /** 身上可以刷的卡（授權過的；不用授權時任何一張）。沒有回傳 0。 */
    int usableCard(Player p) {
        for (int n : Items.keycardsOn(p)) if (!authRequired() || authorized.contains(n)) return n;
        return 0;
    }

    boolean isTerminal(Block block) {
        return b.securityTerminals.contains(pos(block));
    }

    void onTerminal(Player p, Block block) {
        if (m.isGhost(p)) {
            p.sendActionBar(Component.text("保全室的終端機：逃生者在這裡授權門禁卡", NamedTextColor.GRAY));
            return;
        }
        if (!m.isFreeSurvivor(p)) return;
        if (!authRequired()) {
            p.sendActionBar(Component.text("這局門禁卡不用授權，直接去大門刷", NamedTextColor.GRAY));
            return;
        }
        int n = 0;
        for (int c : Items.keycardsOn(p)) if (!authorized.contains(c)) {
            n = c;
            break;
        }
        if (n == 0) {
            p.sendActionBar(Component.text(Items.keycardOn(p) == 0 ? "要拿門禁卡來授權" : "你的卡已經授權過了，去大門刷", NamedTextColor.GRAY));
            return;
        }
        if (!powerOn) {
            p.sendActionBar(Component.text("終端機沒電，先去電力室開總電源", NamedTextColor.GRAY));
            return;
        }
        final int card = n;
        Location at = m.loc(pos(block)).add(0.5, 0.5, 0.5);
        m.acts.start(p, "authorize:" + pos(block), "授權門禁卡 #" + card, o.authorize(), false, true, at, () -> {
            if (!Items.keycardsOn(p).contains(card)) return;
            authorized.add(card);
            Items.replaceKeycard(p, card, true);
            m.world.playSound(at, Sound.BLOCK_NOTE_BLOCK_CHIME, 1.5f, 1.4f);
            m.survivorMessage(p.getName() + " 把門禁卡 #" + card + " 授權了，可以去大門刷了。", NamedTextColor.AQUA);
            m.stat("firstAuthorizeAt", m.tick / 20, false);
        });
    }

    void cleanup() {
        clearMarkers();
        for (GateState gs : gates) stopCountdown(gs);
    }

    /** 最近的目標（掃描器用）：電源沒開時是電源開關，否則是最近的、還在箱子裡的門禁卡。 */
    Location scanTarget(Location from) {
        if (!powerOn && lever != null) return m.loc(lever);
        return content.entrySet().stream()
                .filter(e -> e.getValue().startsWith(Items.KEYCARD))
                .map(e -> m.loc(e.getKey()))
                .min(Comparator.comparingDouble(l -> l.distance(from)))
                .orElse(null);
    }

    private static Pos pos(Block block) {
        return new Pos(block.getX(), block.getY(), block.getZ());
    }
}
