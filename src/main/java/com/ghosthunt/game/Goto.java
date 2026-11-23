package com.ghosthunt.game;

import com.ghosthunt.building.Building;
import com.ghosthunt.building.Pos;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** /gh goto：測試用，傳送到電源、保全室、大門、支線設施旁邊。 */
public final class Goto {

    /** 名稱 → 中文。順序就是 /gh goto 列出來的順序。 */
    public static final Map<String, String> LABELS = new LinkedHashMap<>();

    static {
        LABELS.put("power", "總電源");
        LABELS.put("security", "保全室終端機");
        LABELS.put("gate", "大門讀卡機");
        LABELS.put("jail", "監獄");
        LABELS.put("monitor", "監控室螢幕");
        LABELS.put("pa", "廣播台");
        LABELS.put("elevator", "貨梯配電盤");
        LABELS.put("heli", "停機坪（無線電、上鎖的門）");
        LABELS.put("sewer", "下水道鐵柵");
        LABELS.put("note", "密碼紙條");
        LABELS.put("backup", "雙人備用電源拉桿");
        LABELS.put("breaker", "區域配電箱");
        LABELS.put("shutter", "鐵捲門開關");
        LABELS.put("vending", "販賣機");
        LABELS.put("bell", "迎賓鈴");
    }

    private Goto() {
    }

    /** 每種設施的位置。有對局時用今晚實際抽到的（電源、監控室、廣播台、紙條、備用電源），沒有就列全部候選。 */
    public static Map<String, List<Pos>> targets(Building b, Match m) {
        Map<String, List<Pos>> out = new LinkedHashMap<>();
        Map<String, List<Pos>> chosen = m != null && m.side != null ? m.side.chosen() : Map.of();
        put(out, "power", m != null && b.placedLever != null ? List.of(b.placedLever) : b.powerCandidates);
        put(out, "security", b.securityTerminals);
        List<Pos> readers = new ArrayList<>();
        for (Building.Gate g : b.gates) readers.add(g.reader());
        put(out, "gate", readers);
        put(out, "jail", b.jailCages.values());
        put(out, "monitor", chosen.getOrDefault("monitor", b.monitors));
        put(out, "pa", chosen.getOrDefault("pa", b.paDesks));
        List<Pos> elev = new ArrayList<>(b.fusePanels);
        if (elev.isEmpty() && !b.elevatorCells.isEmpty()) elev.add(b.elevatorCells.get(0));
        put(out, "elevator", elev);
        List<Pos> heli = new ArrayList<>();
        if (b.radio != null) heli.add(b.radio);
        heli.addAll(b.lockedDoors);
        put(out, "heli", heli);
        put(out, "sewer", b.sewerCells);
        put(out, "note", chosen.getOrDefault("note", b.noteSpots));
        put(out, "backup", chosen.getOrDefault("backup", List.of()));
        put(out, "breaker", b.breakers.keySet());
        List<Pos> levers = new ArrayList<>();
        for (Building.Shutter s : b.shutters) if (s.lever() != null) levers.add(s.lever());
        put(out, "shutter", levers);
        put(out, "vending", b.vendingCells);
        put(out, "bell", b.bells);
        return out;
    }

    /** 相距 2 格內的算同一個（門、貨梯廂這種好幾格的東西只列一次）。 */
    private static void put(Map<String, List<Pos>> out, String key, java.util.Collection<Pos> in) {
        List<Pos> list = new ArrayList<>(in);
        if (!(in instanceof List)) list.sort(java.util.Comparator.comparingInt(Pos::y).thenComparingInt(Pos::x).thenComparingInt(Pos::z));
        List<Pos> uniq = new ArrayList<>();
        for (Pos p : list) {
            if (p == null) continue;
            boolean near = false;
            for (Pos q : uniq) if (q.distance(p) <= 2.5) near = true;
            if (!near) uniq.add(p);
        }
        if (!uniq.isEmpty()) out.put(key, uniq);
    }

    /** 設施旁邊最近、站得下的格子，臉朝設施。 */
    public static Location standNear(Building b, World w, Pos target) {
        Pos best = null;
        double bd = Double.MAX_VALUE;
        for (Set<Pos> tiles : b.walkTiles.values())
            for (Pos t : tiles) {
                double dy = Math.abs(t.y() - target.y());
                if (dy > 3 || (t.x() == target.x() && t.z() == target.z())) continue; // 不要站進門、設施裡
                double d = t.distance(target) + dy * 2;
                if (d < bd) {
                    bd = d;
                    best = t;
                }
            }
        Location to = best == null
                ? new Location(w, target.x() + 0.5, target.y() + 1, target.z() + 0.5)
                : new Location(w, best.x() + 0.5, best.y(), best.z() + 0.5);
        Location look = new Location(w, target.x() + 0.5, target.y() + 0.5, target.z() + 0.5);
        org.bukkit.util.Vector dir = look.toVector().subtract(to.clone().add(0, 1.6, 0).toVector());
        if (dir.lengthSquared() > 0.01) to.setDirection(dir);
        return to;
    }

    /** 最近的房間名稱。 */
    public static String regionName(Building b, Pos p) {
        String best = null;
        double bd = Double.MAX_VALUE;
        for (Map.Entry<Integer, Pos> e : b.regionCenters.entrySet()) {
            Pos cp = e.getValue();
            if (Math.abs(cp.y() - p.y()) > 3 || !b.regionNames.containsKey(e.getKey())) continue;
            double d = cp.distance(p);
            if (d < bd) {
                bd = d;
                best = b.regionNames.get(e.getKey());
            }
        }
        return best;
    }
}
