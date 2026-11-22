package com.ghosthunt.game;

import com.ghosthunt.building.Building;
import com.ghosthunt.building.Pos;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Switch;
import org.bukkit.block.data.type.TrapDoor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * v2 地圖（mall2）的支線任務和額外的逃生路線（docs/支線任務候選.md）：
 * <ul>
 *   <li>逃生路線：貨梯（保險絲 ×2 → 呼叫 → 30 秒）、屋頂直升機（屋頂鑰匙開上鎖的門 → 無線電 → 守停機坪 60 秒）、
 *       下水道（3 張紙條湊密碼 → 鐵柵輸入密碼）。大門以外每局隨機開放幾條。</li>
 *   <li>支線：監控室（切換攝影機）、廣播（遠處放聲音引開鬼）、販賣機（硬幣換道具）、區域配電箱（修好那一區的燈）、
 *       鐵捲門（按開關打開捷徑）、急救包（被打到免抓一次）、雙人備用電源、今晚的規則。</li>
 * </ul>
 * 地圖沒有這些物件時對應的功能自動關掉。數值在 config 的 side:。
 */
final class SideQuests {

    private final Match m;
    private final Building b;
    private final FileConfiguration c;

    // ---- 每局的設定 ----
    boolean elevatorOpen, heliOpen, sewerOpen;
    String rule = "";

    // ---- 貨梯 ----
    private int fuses;
    private boolean elevatorCalled, elevatorSabotaged, elevatorArrived;
    private int elevatorLeft;
    private long elevatorArrivedAt;
    private final Set<Long> elevatorXZ = new HashSet<>();

    // ---- 直升機 ----
    private boolean heliCalled;
    private int heliHeld;
    private long heliNoiseAt;
    private final Set<Long> padXZ = new HashSet<>();
    private final Set<Pos> unlocked = new HashSet<>();

    // ---- 下水道 ----
    private final Map<Pos, Integer> noteDigit = new LinkedHashMap<>();
    private final List<Pos> noteOrder = new ArrayList<>();
    private final Set<Pos> notesTaken = new HashSet<>();
    private boolean sewerOpened;
    private final Set<Long> sewerXZ = new HashSet<>();

    // ---- 監控室 ----
    private Pos monitor;
    private final Map<UUID, CamView> viewers = new HashMap<>();
    private long camNoticeAt = -1_000_000;

    private record CamView(Mannequin body, ArmorStand eye, Location back, int cam) {}

    // ---- 廣播 ----
    private Pos paDesk;
    private int paUses;
    private Location paAt;
    private long paUntil, paNextSound;

    // ---- 配電箱、鐵捲門、雙人電源 ----
    private final Set<Integer> litRegions = new HashSet<>();
    private final Set<Pos> repaired = new HashSet<>();
    private final Set<Integer> shuttersOpen = new HashSet<>();
    private final List<Pos> backupLevers = new ArrayList<>();
    private final long[] backupDone = {-1_000_000, -1_000_000};

    SideQuests(Match m) {
        this.m = m;
        this.b = m.b;
        this.c = m.plugin.getConfig();
        for (Pos p : b.elevatorCells) elevatorXZ.add(xz(p));
        for (Pos p : b.helipadCells) padXZ.add(xz(p));
        for (Pos p : b.sewerCells) sewerXZ.add(xz(p));
    }

    static boolean present(Building b) {
        return !b.elevatorCells.isEmpty() || !b.helipadCells.isEmpty() || !b.sewerCells.isEmpty() || !b.monitors.isEmpty()
                || !b.paDesks.isEmpty() || !b.breakers.isEmpty() || !b.shutters.isEmpty() || !b.lockedDoors.isEmpty();
    }

    private static long xz(Pos p) {
        return ((long) p.x() << 32) ^ (p.z() & 0xffffffffL);
    }

    private static long xz(Location l) {
        return ((long) l.getBlockX() << 32) ^ (l.getBlockZ() & 0xffffffffL);
    }

    private int ticks(String key, double defSec) {
        return (int) Math.round(c.getDouble("side." + key, defSec) * 20);
    }

    // =====================================================================
    // 開局
    // =====================================================================

    /** 開局：挑今晚開放的逃生路線、紙條、監控室、廣播台、備用電源、今晚的規則。 */
    void setup() {
        List<String> routes = new ArrayList<>();
        if (!b.elevatorCells.isEmpty() && !b.fusePanels.isEmpty()) routes.add("elevator");
        if (!b.helipadCells.isEmpty() && b.radio != null) routes.add("heli");
        if (!b.sewerCells.isEmpty() && b.noteSpots.size() >= c.getInt("side.notes", 3)) routes.add("sewer");
        Collections.shuffle(routes, m.rnd);
        List<Integer> range = c.getIntegerList("side.extra-routes");
        int lo = range.size() > 0 ? range.get(0) : 1, hi = range.size() > 1 ? range.get(1) : 2;
        int n = Math.min(routes.size(), lo + (hi > lo ? m.rnd.nextInt(hi - lo + 1) : 0));
        for (String r : routes.subList(0, n)) {
            switch (r) {
                case "elevator" -> elevatorOpen = true;
                case "heli" -> heliOpen = true;
                case "sewer" -> sewerOpen = true;
                default -> { }
            }
        }
        // 紙條：挑 3 張，每張一位數
        if (sewerOpen) {
            List<Pos> spots = new ArrayList<>(b.noteSpots);
            Collections.shuffle(spots, m.rnd);
            for (Pos p : spots.subList(0, c.getInt("side.notes", 3))) {
                noteDigit.put(p, m.rnd.nextInt(10));
                noteOrder.add(p);
            }
        }
        if (!b.monitors.isEmpty()) monitor = b.monitors.get(m.rnd.nextInt(b.monitors.size()));
        if (!b.paDesks.isEmpty()) {
            paDesk = b.paDesks.get(m.rnd.nextInt(b.paDesks.size()));
            paUses = c.getInt("side.pa-uses", 2);
        }
        setupBackupPower();
        pickRule();
        announce();
    }

    /** 雙人備用電源：2 人以上時，挑兩個相距 20 格以上的電源候選各裝一根拉桿，兩個人 3 秒內都拉完才通電。 */
    private void setupBackupPower() {
        if (m.survivors.size() < 2 || b.powerCandidates.size() < 3) return;
        Pos main = b.placedLever == null ? null : b.placedLever.add(0, -1, 0);
        List<Pos> cand = new ArrayList<>(b.powerCandidates);
        cand.remove(main);
        Collections.shuffle(cand, m.rnd);
        for (int i = 0; i < cand.size(); i++)
            for (int j = i + 1; j < cand.size(); j++) {
                Pos a = cand.get(i), q = cand.get(j);
                if (a.distance(q) < 20) continue;
                Pos la = placeWallLever(a), lb = placeWallLever(q);
                if (la == null || lb == null) continue;
                backupLevers.add(la);
                backupLevers.add(lb);
                return;
            }
    }

    private Pos placeWallLever(Pos floor) {
        for (org.bukkit.block.BlockFace f : new org.bukkit.block.BlockFace[]{org.bukkit.block.BlockFace.NORTH, org.bukkit.block.BlockFace.SOUTH,
                org.bukkit.block.BlockFace.EAST, org.bukkit.block.BlockFace.WEST}) {
            Pos wall = floor.add(f.getModX(), 1, f.getModZ()), at = floor.add(0, 1, 0);
            if (!m.block(wall).getType().isOccluding() || !m.block(at).getType().isAir()) continue;
            Switch sw = (Switch) Bukkit.createBlockData(Material.LEVER);
            sw.setAttachedFace(org.bukkit.block.data.FaceAttachable.AttachedFace.WALL);
            sw.setFacing(f.getOppositeFace());
            m.block(at).setBlockData(sw, false);
            return at;
        }
        return null;
    }

    private void pickRule() {
        if (!c.getBoolean("side.rules", true)) return;
        String[] rules = {"none", "persona", "coins", "smoke", "noscanner", "medkit"};
        rule = rules[m.rnd.nextInt(rules.length)];
    }

    /** 規則對開局的影響（在 Objectives 分配木桶內容之後、玩家拿到開局道具時）。 */
    void applyRuleToPlayers() {
        if (rule.equals("smoke"))
            for (Player p : m.survivorPlayers()) p.getInventory().addItem(Items.of(Items.SMOKE));
    }

    private void announce() {
        List<String> open = new ArrayList<>();
        open.add("大門");
        if (elevatorOpen) open.add("貨梯");
        if (heliOpen) open.add("屋頂直升機");
        if (sewerOpen) open.add("下水道");
        m.broadcast("今晚開放的逃生路線：" + String.join("、", open), NamedTextColor.GOLD);
        if (elevatorOpen) m.survivorMessage("・貨梯：找 2 個保險絲裝到卸貨區貨梯旁的配電盤，呼叫後 30 秒到，所有人站進貨梯廂。", NamedTextColor.YELLOW);
        if (heliOpen) m.survivorMessage("・直升機：找到屋頂鑰匙打開上鎖的鐵門，在停機坪用無線電呼叫，守住停機坪 60 秒。", NamedTextColor.YELLOW);
        if (sewerOpen) m.survivorMessage("・下水道：找到 3 張寫著密碼的紙條（講台），到倉庫的鐵柵輸入密碼。", NamedTextColor.YELLOW);
        if (monitor != null) m.survivorMessage("・監控室（" + regionNameAt(monitor) + "）可以看攝影機，但被鬼發現有人在看，它會過來。", NamedTextColor.GRAY);
        String r = switch (rule) {
            case "persona" -> m.aiGhost != null ? "今晚的鬼是「" + m.aiGhost.persona().label() + "」" : "";
            case "coins" -> "今晚販賣機的硬幣比較多";
            case "smoke" -> "今晚每個人開局多一顆煙霧彈";
            case "noscanner" -> "今晚掃描器全部壞掉了（地圖上沒有掃描器）";
            case "medkit" -> "今晚急救包比較多";
            default -> "";
        };
        if (!r.isEmpty()) m.broadcast("今晚的規則：" + r, NamedTextColor.LIGHT_PURPLE);
    }

    private String regionNameAt(Pos p) {
        String best = null;
        double bd = Double.MAX_VALUE;
        for (Map.Entry<Integer, Pos> e : b.regionCenters.entrySet()) {
            Pos cp = e.getValue();
            if (Math.abs(cp.y() - p.y()) > 2) continue;
            double d = cp.distance(p);
            if (d < bd && b.regionNames.containsKey(e.getKey())) {
                bd = d;
                best = b.regionNames.get(e.getKey());
            }
        }
        return best == null ? "某個房間" : best;
    }

    /** 木桶裡額外放的東西（保險絲、屋頂鑰匙、硬幣、急救包）。 */
    List<String> extraLoot() {
        List<String> out = new ArrayList<>();
        if (elevatorOpen) {
            out.add(Items.FUSE);
            out.add(Items.FUSE);
        }
        if (heliOpen) out.add(Items.ROOF_KEY);
        if (!b.vendingCells.isEmpty()) {
            int coins = GameSettings.bySurvivors(c.getIntegerList("side.coins"), m.survivors.size());
            if (rule.equals("coins")) coins *= 2;
            for (int i = 0; i < coins; i++) out.add(Items.COIN);
        }
        int kits = GameSettings.bySurvivors(c.getIntegerList("side.medkits"), m.survivors.size());
        if (rule.equals("medkit")) kits += 2;
        for (int i = 0; i < kits; i++) out.add(Items.MEDKIT);
        return out;
    }

    boolean noScanners() {
        return rule.equals("noscanner");
    }

    // =====================================================================
    // 點擊
    // =====================================================================

    /** 右鍵方塊：處理了回傳 true（事件要取消）。 */
    boolean onClick(Player p, Block clicked) {
        Pos pos = new Pos(clicked.getX(), clicked.getY(), clicked.getZ());
        boolean ghost = m.isGhost(p);
        if (!ghost && !m.isFreeSurvivor(p)) return false;
        // 貨梯配電盤
        for (Pos f : b.fusePanels) if (pos.equals(f) || pos.equals(f.add(0, 1, 0))) {
            if (ghost) return true;
            onFusePanel(p, f);
            return true;
        }
        // 無線電
        if (b.radio != null && pos.equals(b.radio)) {
            if (!ghost) onRadio(p);
            return true;
        }
        // 上鎖的門（鐵門下半或上半）
        for (Pos d : b.lockedDoors) if (pos.equals(d) || pos.equals(d.add(0, 1, 0))) {
            if (!ghost) onLockedDoor(p, d);
            return true;
        }
        // 下水道鐵柵
        for (Pos x : b.sewerCells) if (pos.equals(x.add(0, -1, 0))) {
            if (!ghost) onSewer(p, x);
            return true;
        }
        // 紙條（講台）
        if (b.noteSpots.contains(pos)) {
            if (!ghost) onNote(p, pos);
            return true;
        }
        // 監控室螢幕
        for (Pos mo : b.monitors) if (pos.equals(mo) || pos.equals(mo.add(0, 1, 0))) {
            if (!ghost) onMonitor(p, mo);
            return true;
        }
        // 廣播台
        for (Pos a : b.paDesks) if (pos.equals(a) || pos.equals(a.add(0, 1, 0))) {
            if (!ghost) onPaDesk(p, a);
            return true;
        }
        // 區域配電箱
        for (Pos z : b.breakers.keySet()) if (pos.equals(z) || pos.equals(z.add(0, 1, 0))) {
            if (!ghost) onBreaker(p, z);
            return true;
        }
        // 鐵捲門開關
        for (int i = 0; i < b.shutters.size(); i++) {
            Building.Shutter s = b.shutters.get(i);
            if (s.lever() != null && pos.equals(s.lever())) {
                if (!ghost) onShutterLever(p, i);
                return true;
            }
            for (Pos d : s.doors()) if (pos.equals(d) || pos.equals(d.add(0, 1, 0))) {
                p.sendActionBar(Component.text(shuttersOpen.contains(i) ? "鐵捲門開著" : "鐵捲門鎖著：店裡那一側有開關", NamedTextColor.GRAY));
                return true;
            }
        }
        // 備用電源
        for (int i = 0; i < backupLevers.size(); i++) if (pos.equals(backupLevers.get(i))) {
            if (!ghost) onBackupLever(p, i);
            return true;
        }
        // 販賣機
        if (b.vendingCells.contains(pos)) {
            if (!ghost) onVending(p, pos);
            return true;
        }
        return false;
    }

    private void onFusePanel(Player p, Pos f) {
        Location at = m.loc(f).add(0.5, 1, 0.5);
        if (!elevatorOpen) {
            p.sendActionBar(Component.text("貨梯今晚沒有開放", NamedTextColor.GRAY));
            return;
        }
        if (fuses < 2) {
            if (Items.count(p, Items.FUSE) == 0) {
                p.sendActionBar(Component.text("配電盤少了保險絲（" + fuses + "/2）：去木桶找", NamedTextColor.GRAY));
                return;
            }
            m.acts.start(p, "fuse", "裝保險絲", ticks("fuse-install", 4), false, true, at, () -> {
                if (Items.count(p, Items.FUSE) == 0) return;
                Items.take(p, Items.FUSE);
                fuses++;
                m.world.playSound(at, Sound.BLOCK_IRON_TRAPDOOR_CLOSE, 1.5f, 0.8f);
                m.survivorMessage("貨梯的保險絲裝好 " + fuses + "/2", NamedTextColor.AQUA);
                if (fuses >= 2) {
                    Block lamp = m.block(f.add(0, 1, 0));
                    if (lamp.getBlockData() instanceof org.bukkit.block.data.Lightable l) {
                        l.setLit(true);
                        lamp.setBlockData(l, false);
                    }
                }
            });
            return;
        }
        if (elevatorCalled) {
            p.sendActionBar(Component.text(elevatorArrived ? "貨梯到了！站進貨梯廂" : "貨梯還有 " + (elevatorLeft / 20 + 1) + " 秒（要有人站在貨梯廂裡才會動）",
                    NamedTextColor.YELLOW));
            return;
        }
        m.acts.start(p, "elev", "呼叫貨梯", ticks("elevator-call", 3), false, true, at, () -> {
            elevatorCalled = true;
            elevatorLeft = ticks("elevator-countdown", 30);
            m.world.playSound(at, Sound.BLOCK_BELL_USE, 2f, 0.6f);
            m.survivorMessage("貨梯呼叫了！" + elevatorLeft / 20 + " 秒後到，要有人站在貨梯廂裡倒數才會跑。", NamedTextColor.GREEN);
        });
    }

    private void onRadio(Player p) {
        if (!heliOpen) {
            p.sendActionBar(Component.text("無線電沒有回應（今晚直升機沒有開放）", NamedTextColor.GRAY));
            return;
        }
        if (heliCalled) {
            p.sendActionBar(Component.text("直升機要來了！守住停機坪（" + heliHeld / 20 + "/" + ticks("heli-hold", 60) / 20 + " 秒）", NamedTextColor.YELLOW));
            return;
        }
        Location at = m.loc(b.radio).add(0.5, 1, 0.5);
        m.acts.start(p, "radio", "用無線電呼叫直升機", ticks("radio-call", 5), false, false, at, () -> {
            heliCalled = true;
            m.lureNoise(at); // 全圖聽得到：鬼一定會來
            m.survivorMessage("直升機收到了！守住停機坪 " + ticks("heli-hold", 60) / 20 + " 秒（停機坪上有人時才計時）", NamedTextColor.GREEN);
        });
    }

    private void onLockedDoor(Player p, Pos d) {
        if (unlocked.contains(d)) return;
        if (Items.count(p, Items.ROOF_KEY) == 0) {
            p.sendActionBar(Component.text("上鎖的鐵門：要屋頂鑰匙", NamedTextColor.GRAY));
            return;
        }
        unlocked.add(d);
        m.setDoorOpenPublic(d, true);
        m.world.playSound(m.loc(d), Sound.BLOCK_IRON_DOOR_OPEN, 1.5f, 0.8f);
        m.mapVersion++;
        p.sendActionBar(Component.text("用屋頂鑰匙打開了鐵門", NamedTextColor.GREEN));
    }

    private void onSewer(Player p, Pos x) {
        if (!sewerOpen) {
            p.sendActionBar(Component.text("鐵柵鎖得很緊（今晚下水道沒有開放）", NamedTextColor.GRAY));
            return;
        }
        if (sewerOpened) return;
        if (notesTaken.size() < noteOrder.size()) {
            p.sendActionBar(Component.text("鐵柵有一個密碼鎖：還差 " + (noteOrder.size() - notesTaken.size()) + " 張紙條", NamedTextColor.GRAY));
            return;
        }
        Location at = m.loc(x).add(0.5, 0, 0.5);
        m.acts.start(p, "sewer", "輸入密碼 " + code(), ticks("sewer-code", 5), false, true, at, () -> {
            sewerOpened = true;
            for (Pos q : b.sewerCells) {
                Block bl = m.block(q.add(0, -1, 0));
                if (bl.getBlockData() instanceof TrapDoor td) {
                    td.setOpen(true);
                    bl.setBlockData(td, false);
                }
            }
            m.world.playSound(at, Sound.BLOCK_IRON_TRAPDOOR_OPEN, 2f, 0.5f);
            m.survivorMessage("下水道的鐵柵打開了！跳下去就能逃出去。", NamedTextColor.GREEN);
        });
    }

    private String code() {
        StringBuilder sb = new StringBuilder();
        for (Pos p : noteOrder) sb.append(notesTaken.contains(p) ? String.valueOf(noteDigit.get(p)) : "?");
        return sb.toString();
    }

    private void onNote(Player p, Pos pos) {
        Integer d = noteDigit.get(pos);
        if (d == null) {
            p.sendActionBar(Component.text("講台上什麼都沒有", NamedTextColor.GRAY));
            return;
        }
        if (notesTaken.add(pos)) {
            int idx = noteOrder.indexOf(pos) + 1;
            m.survivorMessage(p.getName() + " 找到紙條：密碼第 " + idx + " 位是 " + d + "（" + notesTaken.size() + "/" + noteOrder.size() + "，密碼 " + code() + "）",
                    NamedTextColor.AQUA);
        } else p.sendActionBar(Component.text("這張紙條已經看過了：密碼 " + code(), NamedTextColor.GRAY));
    }

    private void onMonitor(Player p, Pos mo) {
        if (!mo.equals(monitor)) {
            p.sendActionBar(Component.text("這台螢幕壞掉了（今晚的監控室在 " + regionNameAt(monitor) + "）", NamedTextColor.GRAY));
            return;
        }
        if (!m.obj.powerOn()) {
            p.sendActionBar(Component.text("監視器沒電，先開總電源", NamedTextColor.GRAY));
            return;
        }
        if (b.cameras.isEmpty()) return;
        cameraMenu(p);
    }

    /** 聊天窗的攝影機清單（可以點）。 */
    private void cameraMenu(Player p) {
        Component line = Component.text("監視器：", NamedTextColor.GOLD);
        for (int i = 0; i < b.cameras.size(); i++) {
            Building.Camera cam = b.cameras.get(i);
            line = line.append(Component.text("［" + cam.name() + "］", NamedTextColor.AQUA)
                    .clickEvent(ClickEvent.runCommand("/gh cam " + i))
                    .hoverEvent(HoverEvent.showText(Component.text("切到這台攝影機"))));
        }
        line = line.append(Component.text("［離開］", NamedTextColor.RED).clickEvent(ClickEvent.runCommand("/gh cam exit")));
        p.sendMessage(line);
    }

    /** /gh cam &lt;編號|exit&gt;。 */
    void camCommand(Player p, String arg) {
        if (arg.equalsIgnoreCase("exit")) {
            exitCamera(p);
            return;
        }
        int i;
        try {
            i = Integer.parseInt(arg);
        } catch (NumberFormatException e) {
            return;
        }
        if (i < 0 || i >= b.cameras.size()) return;
        CamView v = viewers.get(p.getUniqueId());
        if (v == null) {
            // 要站在監控室的螢幕旁邊才能開
            if (monitor == null || !m.isFreeSurvivor(p) || p.getLocation().distance(m.loc(monitor)) > 4 || !m.obj.powerOn()) return;
            Location back = p.getLocation();
            Mannequin body = m.world.spawn(back, Mannequin.class, mq -> {
                mq.setProfile(io.papermc.paper.datacomponent.item.ResolvableProfile.resolvableProfile(p.getPlayerProfile()));
                mq.setImmovable(true);
                mq.setInvulnerable(true);
                mq.setPersistent(false);
                mq.customName(Component.text(p.getName() + "（看監視器）", NamedTextColor.GRAY));
                mq.setCustomNameVisible(true);
            });
            ArmorStand eye = spawnEye(i);
            v = new CamView(body, eye, back, i);
            viewers.put(p.getUniqueId(), v);
            p.setGameMode(GameMode.SPECTATOR);
            final ArmorStand target = eye;
            Bukkit.getScheduler().runTaskLater(m.plugin, () -> {
                if (viewers.containsKey(p.getUniqueId())) p.setSpectatorTarget(target);
            }, 2L);
        } else {
            ArmorStand eye = spawnEye(i);
            v.eye().remove();
            v = new CamView(v.body(), eye, v.back(), i);
            viewers.put(p.getUniqueId(), v);
            final ArmorStand target = eye;
            Bukkit.getScheduler().runTaskLater(m.plugin, () -> {
                if (viewers.containsKey(p.getUniqueId())) p.setSpectatorTarget(target);
            }, 1L);
        }
        p.sendActionBar(Component.text("攝影機：" + b.cameras.get(i).name() + "（在聊天窗切換或離開）", NamedTextColor.AQUA));
        cameraMenu(p);
    }

    private ArmorStand spawnEye(int i) {
        Building.Camera cam = b.cameras.get(i);
        Location l = m.loc(cam.eye()).add(0.5, 0, 0.5);
        l.setYaw(cam.yaw());
        l.setPitch(20);
        return m.world.spawn(l, ArmorStand.class, as -> {
            as.setInvisible(true);
            as.setMarker(true);
            as.setGravity(false);
            as.setInvulnerable(true);
            as.setPersistent(false);
            as.setRotation(cam.yaw(), 20);
        });
    }

    void exitCamera(Player p) {
        CamView v = viewers.remove(p.getUniqueId());
        if (v == null) return;
        p.setSpectatorTarget(null);
        p.teleport(v.back());
        p.setGameMode(GameMode.ADVENTURE);
        v.body().remove();
        v.eye().remove();
    }

    boolean isViewing(Player p) {
        return viewers.containsKey(p.getUniqueId());
    }

    /** 正在看監視器的人留在原地的身體（AI 鬼看得到、抓得到）。 */
    List<LivingEntity> cameraBodies() {
        List<LivingEntity> out = new ArrayList<>();
        for (CamView v : viewers.values()) if (v.body().isValid()) out.add(v.body());
        return out;
    }

    /** 鬼打到看監視器的人的身體：先拉回身體的位置，照一般規則被抓。回傳 true 表示是身體。 */
    boolean hitBody(LivingEntity e) {
        for (Map.Entry<UUID, CamView> en : viewers.entrySet()) {
            if (!en.getValue().body().equals(e)) continue;
            Player p = Bukkit.getPlayer(en.getKey());
            if (p != null) {
                exitCamera(p);
                m.ghostHit(p);
            }
            return true;
        }
        return false;
    }

    private void onPaDesk(Player p, Pos a) {
        if (!a.equals(paDesk)) {
            p.sendActionBar(Component.text("這個廣播台沒有反應（今晚能用的在 " + regionNameAt(paDesk) + "）", NamedTextColor.GRAY));
            return;
        }
        if (paUses <= 0) {
            p.sendActionBar(Component.text("廣播系統燒掉了（今晚用完了）", NamedTextColor.GRAY));
            return;
        }
        if (!m.obj.powerOn()) {
            p.sendActionBar(Component.text("廣播系統沒電，先開總電源", NamedTextColor.GRAY));
            return;
        }
        Component line = Component.text("廣播到（剩 " + paUses + " 次）：", NamedTextColor.GOLD);
        for (Map.Entry<Integer, String> e : b.regionNames.entrySet()) {
            String name = e.getValue();
            if (name.contains("監獄") || name.contains("保全室")) continue;
            line = line.append(Component.text("［" + name + "］", NamedTextColor.AQUA).clickEvent(ClickEvent.runCommand("/gh pa " + e.getKey())));
        }
        p.sendMessage(line);
    }

    /** /gh pa &lt;區域&gt;：站在廣播台旁 3 秒，那一區的喇叭響 10 秒（AI 不管多遠都聽得到）。 */
    void paCommand(Player p, String arg) {
        if (paDesk == null || paUses <= 0 || !m.isFreeSurvivor(p) || p.getLocation().distance(m.loc(paDesk)) > 4 || !m.obj.powerOn()) return;
        int key;
        try {
            key = Integer.parseInt(arg);
        } catch (NumberFormatException e) {
            return;
        }
        Pos center = b.regionCenters.get(key);
        if (center == null) return;
        Location target = m.loc(center).add(0.5, 1, 0.5);
        m.acts.start(p, "pa", "廣播到 " + b.regionNames.getOrDefault(key, "那一區"), ticks("pa-operate", 3), false, false, m.loc(paDesk), () -> {
            if (paUses <= 0) return;
            paUses--;
            paAt = target;
            paUntil = m.tick + ticks("pa-duration", 10);
            paNextSound = m.tick;
            m.lureNoise(target);
            m.survivorMessage("廣播：" + b.regionNames.getOrDefault(key, "那一區") + " 的喇叭響了（剩 " + paUses + " 次）", NamedTextColor.AQUA);
        });
    }

    private void onBreaker(Player p, Pos z) {
        if (repaired.contains(z)) {
            p.sendActionBar(Component.text("這一區的燈已經修好了", NamedTextColor.GRAY));
            return;
        }
        Integer region = b.breakers.get(z);
        m.acts.start(p, "breaker:" + z, "修配電箱", ticks("breaker-repair", 4), false, true, m.loc(z), () -> {
            repaired.add(z);
            if (region != null) litRegions.add(region);
            m.refreshLights();
            m.world.playSound(m.loc(z), Sound.BLOCK_BEACON_ACTIVATE, 1f, 1.4f);
            p.sendActionBar(Component.text("這一區的燈亮了（停電也會亮；鬼在亮的地方也看得比較遠）", NamedTextColor.GREEN));
        });
    }

    private void onShutterLever(Player p, int i) {
        if (shuttersOpen.contains(i)) return;
        Building.Shutter s = b.shutters.get(i);
        m.acts.start(p, "shutter:" + i, "打開鐵捲門", ticks("shutter-open", 3), false, true, m.loc(s.lever()), () -> {
            shuttersOpen.add(i);
            for (Pos d : s.doors()) {
                unlocked.add(d);
                m.setDoorOpenPublic(d, true);
            }
            if (m.block(s.lever()).getBlockData() instanceof Switch sw) {
                sw.setPowered(true);
                m.block(s.lever()).setBlockData(sw, false);
            }
            m.world.playSound(m.loc(s.doors().get(0)), Sound.BLOCK_PISTON_EXTEND, 2f, 0.5f);
            m.mapVersion++;
        });
    }

    private void onBackupLever(Player p, int i) {
        if (m.obj.powerOn()) {
            p.sendActionBar(Component.text("電源已經開著", NamedTextColor.GRAY));
            return;
        }
        Location at = m.loc(backupLevers.get(i));
        m.acts.start(p, "backup:" + i, "拉備用電源（兩邊要同時）", ticks("backup-power-hold", 3), false, true, at, () -> {
            backupDone[i] = m.tick;
            if (Math.abs(backupDone[0] - backupDone[1]) <= 60) m.obj.forcePowerOn("備用電源接上了！兩個人同時拉成功");
            else m.survivorMessage("備用電源：另一根拉桿要在 3 秒內一起拉（相隔很遠，要分頭）", NamedTextColor.YELLOW);
        });
    }

    private void onVending(Player p, Pos pos) {
        if (Items.count(p, Items.COIN) == 0) {
            p.sendActionBar(Component.text("販賣機：要投硬幣（木桶裡找）", NamedTextColor.GRAY));
            return;
        }
        m.acts.start(p, "vend:" + pos, "投幣", ticks("vending-time", 2), false, true, m.loc(pos), () -> {
            if (Items.count(p, Items.COIN) == 0) return;
            Items.take(p, Items.COIN);
            String[] pool = {Items.SMOKE, Items.SPEED, Items.FLASHLIGHT, Items.SMOKE, Items.SPEED};
            String it = pool[m.rnd.nextInt(pool.length)];
            p.getInventory().addItem(Items.of(it));
            m.world.playSound(m.loc(pos), Sound.BLOCK_NOTE_BLOCK_CHIME, 1.5f, 1.2f);
            p.sendActionBar(Component.text("販賣機掉出了 " + Items.label(it), NamedTextColor.GREEN));
        });
    }

    // =====================================================================
    // 每 tick
    // =====================================================================

    void tick() {
        tickElevator();
        tickHeli();
        tickSewer();
        tickCameras();
        tickPa();
    }

    private boolean inArea(Player p, Set<Long> area, int y) {
        Location l = p.getLocation();
        return area.contains(xz(l)) && Math.abs(l.getBlockY() - y) <= 1;
    }

    private void tickElevator() {
        if (!elevatorCalled || b.elevatorCells.isEmpty()) return;
        int y = b.elevatorCells.get(0).y();
        List<Player> inside = new ArrayList<>();
        for (Player p : m.freeSurvivors()) if (inArea(p, elevatorXZ, y)) inside.add(p);
        if (!elevatorArrived) {
            if (inside.isEmpty()) return;
            elevatorLeft--;
            if (elevatorLeft % 20 == 0) for (Player p : inside)
                p.sendActionBar(Component.text("貨梯還有 " + elevatorLeft / 20 + " 秒", NamedTextColor.YELLOW));
            if (elevatorLeft <= 0) {
                elevatorArrived = true;
                elevatorArrivedAt = m.tick;
                m.world.playSound(m.loc(b.elevatorCells.get(0)), Sound.BLOCK_BELL_RESONATE, 2f, 1f);
            }
            return;
        }
        for (Player p : inside) m.escapeVia(p, "搭貨梯逃出去了！", m.loc(b.elevatorCells.get(0)));
        if (m.tick - elevatorArrivedAt > 15 * 20) { // 貨梯只停 15 秒，之後要再呼叫
            elevatorArrived = false;
            elevatorCalled = false;
            m.survivorMessage("貨梯走了，要再呼叫一次", NamedTextColor.GRAY);
        }
    }

    private void tickHeli() {
        if (!heliCalled || b.helipadCells.isEmpty()) return;
        int y = b.helipadCells.get(0).y();
        List<Player> on = new ArrayList<>();
        for (Player p : m.freeSurvivors()) if (inArea(p, padXZ, y)) on.add(p);
        Location pad = m.loc(b.helipadCells.get(b.helipadCells.size() / 2)).add(0.5, 1, 0.5);
        if (m.tick - heliNoiseAt >= ticks("heli-noise-every", 10)) {
            heliNoiseAt = m.tick;
            m.lureNoise(pad); // 直升機越來越近：鬼不管多遠都聽得到
            for (Player p : m.world.getPlayers()) p.playSound(pad, Sound.ITEM_ELYTRA_FLYING, SoundCategory.AMBIENT, 2f, 0.6f);
        }
        if (on.isEmpty()) return;
        heliHeld++;
        int need = ticks("heli-hold", 60);
        if (heliHeld % 20 == 0) for (Player p : on)
            p.sendActionBar(Component.text("守住停機坪：" + heliHeld / 20 + "/" + need / 20 + " 秒", NamedTextColor.YELLOW));
        if (heliHeld >= need) for (Player p : on) m.escapeVia(p, "搭直升機逃出去了！", pad);
    }

    private void tickSewer() {
        if (!sewerOpened || b.sewerCells.isEmpty()) return;
        int y = b.sewerCells.get(0).y();
        for (Player p : m.freeSurvivors()) {
            Location l = p.getLocation();
            if (sewerXZ.contains(xz(l)) && l.getY() <= y + 0.2) m.escapeVia(p, "從下水道逃出去了！", l);
        }
    }

    /** 攝影機畫面裡看到鬼、或看的是鬼所在的區域：鬼會知道有人在看（AI 聽到監控室那邊的聲音）。 */
    private void tickCameras() {
        if (viewers.isEmpty() || m.tick % 20 != 0) return;
        LivingEntity g = m.ghostEntity();
        if (g == null) return;
        for (Map.Entry<UUID, CamView> en : new ArrayList<>(viewers.entrySet())) {
            Player p = Bukkit.getPlayer(en.getKey());
            CamView v = en.getValue();
            if (p == null || !m.isFreeSurvivor(p) || !m.obj.powerOn()) {
                if (p != null) {
                    exitCamera(p);
                    if (!m.obj.powerOn()) p.sendActionBar(Component.text("停電了，監視器黑掉", NamedTextColor.GRAY));
                }
                continue;
            }
            Location eye = v.eye().getLocation();
            boolean sees = false;
            if (eye.getWorld().equals(g.getWorld()) && eye.distance(g.getLocation()) <= 24) {
                org.bukkit.util.Vector dir = g.getEyeLocation().toVector().subtract(eye.toVector());
                var hit = eye.getWorld().rayTraceBlocks(eye, dir.clone().normalize(), dir.length(), org.bukkit.FluidCollisionMode.NEVER, true,
                        bl -> bl.getType() != Material.BARRIER);
                sees = hit == null || hit.getHitBlock() == null;
            }
            if (sees && m.tick - camNoticeAt >= ticks("camera-notice-cooldown", 10)) {
                camNoticeAt = m.tick;
                if (m.aiGhost != null) m.aiGhost.hearNoise(v.body().getLocation(), Match.NOISE_HIGH);
                p.sendActionBar(Component.text("鬼發現有人在看監視器了！", NamedTextColor.RED));
                m.ghostMessage(Component.text("你感覺到有人在監視器看你（監控室）", NamedTextColor.DARK_RED));
            }
        }
    }

    private void tickPa() {
        if (paAt == null || m.tick > paUntil) return;
        if (m.tick >= paNextSound) {
            paNextSound = m.tick + 20;
            m.world.playSound(paAt, Sound.BLOCK_NOTE_BLOCK_BELL, 3f, 0.7f + m.rnd.nextFloat() * 0.6f);
            m.world.playSound(paAt, Sound.BLOCK_NOTE_BLOCK_IRON_XYLOPHONE, 3f, 0.5f);
        }
    }

    // =====================================================================
    // 給 Match、AI 用
    // =====================================================================

    /** 這扇門鎖著嗎（上鎖的門、還沒打開的鐵捲門）：誰都過不去。 */
    boolean isLocked(Pos lower) {
        if (unlocked.contains(lower)) return false;
        if (b.lockedDoors.contains(lower)) return true;
        for (Building.Shutter s : b.shutters) if (s.doors().contains(lower)) return true;
        return false;
    }

    /** 這盞燈所在的區域修好配電箱了嗎（停電也亮）。 */
    boolean litLight(Pos light) {
        Integer r = b.lightRegion.get(light);
        return r != null && litRegions.contains(r);
    }

    /** 這個位置附近（6 格內同一層）有修好的燈：AI 照有電算視距。 */
    boolean litAt(Location l) {
        if (litRegions.isEmpty()) return false;
        for (Map.Entry<Pos, Integer> e : b.lightRegion.entrySet()) {
            if (!litRegions.contains(e.getValue())) continue;
            Pos p = e.getKey();
            if (Math.abs(p.y() - l.getY()) < 5 && Math.hypot(p.x() + 0.5 - l.getX(), p.z() + 0.5 - l.getZ()) <= 6) return true;
        }
        return false;
    }

    /** AI 的「要去弄壞的目標」：倒數中的貨梯（配電盤的位置）；沒有回傳 null。 */
    Location elevatorTarget() {
        if (!elevatorCalled || elevatorArrived || elevatorSabotaged || b.fusePanels.isEmpty()) return null;
        return m.loc(b.fusePanels.get(0));
    }

    int elevatorTicksLeft() {
        return elevatorLeft;
    }

    /** 鬼讓貨梯故障：站 4 秒，倒數回到滿（一次）。 */
    void ghostSabotageElevator(LivingEntity ghost) {
        if (elevatorTarget() == null) return;
        Location at = m.loc(b.fusePanels.get(0));
        m.acts.start(ghost, "elev-sab", "讓貨梯故障", m.o.ghostSabotage(), false, false, at, () -> {
            elevatorSabotaged = true;
            elevatorLeft = ticks("elevator-countdown", 30);
            m.world.playSound(at, Sound.BLOCK_ANVIL_LAND, 1.5f, 0.6f);
            m.survivorMessage("鬼把貨梯弄壞了！倒數重來", NamedTextColor.RED);
        });
    }

    /** 巡邏偏向「有人在推進」的支線附近：裝過保險絲 → 貨梯、撿過紙條 → 下水道、開過上鎖的門 → 停機坪。 */
    double patrolBias(Location roomCenter) {
        double w = 1;
        if (fuses > 0 && !b.fusePanels.isEmpty() && near(roomCenter, m.loc(b.fusePanels.get(0)), 16)) w *= 3;
        if (!notesTaken.isEmpty() && !b.sewerCells.isEmpty() && near(roomCenter, m.loc(b.sewerCells.get(0)), 16)) w *= 3;
        boolean anyUnlocked = false;
        for (Pos d : b.lockedDoors) if (unlocked.contains(d)) anyUnlocked = true;
        if (anyUnlocked && !b.helipadCells.isEmpty() && near(roomCenter, m.loc(b.helipadCells.get(0)), 20)) w *= 3;
        return w;
    }

    private static boolean near(Location a, Location q, double r) {
        return a.getWorld().equals(q.getWorld()) && Math.abs(a.getY() - q.getY()) < 4 && a.distance(q) <= r;
    }

    /** 急救包：被打到時用掉，打倒 3 秒不進監獄。回傳 true 表示用了急救包。 */
    boolean tryMedkit(Player p) {
        if (Items.count(p, Items.MEDKIT) == 0) return false;
        Items.take(p, Items.MEDKIT);
        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, ticks("medkit-down", 3), 4, false, false));
        p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 30, 0, false, false));
        p.showTitle(Title.title(Component.text("急救包救了你一命", NamedTextColor.GOLD), Component.text("快跑！", NamedTextColor.GRAY)));
        m.broadcast(p.getName() + " 被打倒，但用急救包撐住了！", NamedTextColor.GOLD);
        return true;
    }

    void cleanup() {
        for (UUID id : new ArrayList<>(viewers.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) exitCamera(p);
            else {
                CamView v = viewers.remove(id);
                v.body().remove();
                v.eye().remove();
            }
        }
    }

    /**
     * 測試用（/gh sidetest）：不用玩家，直接把每個支線做一遍（裝保險絲、呼叫貨梯、開鎖、開鐵捲門、修配電箱、廣播、撿紙條、開下水道、
     * 呼叫直升機），回報結果。用來檢查方塊有沒有變、AI 有沒有反應、尋路有沒有更新。
     */
    List<String> selfTest() {
        List<String> out = new ArrayList<>();
        if (elevatorOpen) {
            fuses = 2;
            elevatorCalled = true;
            elevatorLeft = ticks("elevator-countdown", 30);
            out.add("貨梯：保險絲 2/2、呼叫了（倒數 " + elevatorLeft / 20 + " 秒，AI 的弄壞目標 " + (elevatorTarget() != null) + "）");
        } else out.add("貨梯：今晚沒開放");
        int doors = 0;
        for (Pos d : b.lockedDoors) {
            unlocked.add(d);
            m.setDoorOpenPublic(d, true);
            doors++;
        }
        int sh = 0;
        for (int i = 0; i < b.shutters.size(); i++) {
            shuttersOpen.add(i);
            for (Pos d : b.shutters.get(i).doors()) {
                unlocked.add(d);
                m.setDoorOpenPublic(d, true);
            }
            sh++;
        }
        m.mapVersion++;
        out.add("上鎖的門打開 " + doors + " 扇、鐵捲門打開 " + sh + " 組（尋路圖版本 " + m.mapVersion + "）");
        for (Map.Entry<Pos, Integer> e : b.breakers.entrySet()) {
            repaired.add(e.getKey());
            litRegions.add(e.getValue());
        }
        m.refreshLights();
        int lit = 0;
        for (Pos l : b.lights) if (litLight(l)) lit++;
        out.add("配電箱修好 " + repaired.size() + " 個，停電也亮的燈 " + lit + " 盞");
        if (sewerOpen) {
            notesTaken.addAll(noteOrder);
            sewerOpened = true;
            for (Pos q : b.sewerCells) {
                Block bl = m.block(q.add(0, -1, 0));
                if (bl.getBlockData() instanceof TrapDoor td) {
                    td.setOpen(true);
                    bl.setBlockData(td, false);
                }
            }
            out.add("下水道：密碼 " + code() + "，鐵柵打開了");
        } else out.add("下水道：今晚沒開放");
        if (heliOpen) {
            heliCalled = true;
            out.add("直升機：呼叫了（停機坪 " + b.helipadCells.size() + " 格）");
        } else out.add("直升機：今晚沒開放");
        if (paDesk != null && !b.regionNames.isEmpty()) {
            Map.Entry<Integer, String> e = b.regionNames.entrySet().iterator().next();
            paAt = m.loc(b.regionCenters.get(e.getKey())).add(0.5, 1, 0.5);
            paUntil = m.tick + ticks("pa-duration", 10);
            m.lureNoise(paAt);
            out.add("廣播：" + e.getValue() + "（AI 應該會過去）");
        }
        out.add("監控室：" + (monitor == null ? "沒有" : regionNameAt(monitor)) + "，攝影機 " + b.cameras.size() + " 台；販賣機 " + b.vendingCells.size()
                + " 格；備用電源拉桿 " + backupLevers.size() + " 根；今晚的規則 " + rule);
        return out;
    }

    Map<String, Object> stats() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("sideRoutes", (elevatorOpen ? "elevator," : "") + (heliOpen ? "heli," : "") + (sewerOpen ? "sewer," : ""));
        s.put("sideRule", rule);
        s.put("sideFuses", fuses);
        s.put("sideHeliHeldSec", heliHeld / 20);
        s.put("sideNotes", notesTaken.size());
        s.put("sideBreakers", repaired.size());
        s.put("sideShutters", shuttersOpen.size());
        s.put("sidePaUsed", c.getInt("side.pa-uses", 2) - paUses);
        return s;
    }
}
