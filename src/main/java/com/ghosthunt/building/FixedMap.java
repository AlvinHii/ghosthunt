package com.ghosthunt.building;

import org.bukkit.Material;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * PvE 固定地圖（plugins/GhostHunt/pve-maps/名字.txt）：一個字元一格的平面圖，每層一張。格式見 docs/PvE地圖格式.md。
 * <pre>
 * name: 夜間辦公園區
 * type: mixed
 * [1F]
 * %%%%%%%%
 * %,,,,,,%
 * [furniture]
 * t = oak_log, oak_leaves, oak_leaves
 * [palette]
 * wall = smooth_quartz
 * </pre>
 */
public final class FixedMap {

    /** 家具：由下往上的方塊；light = 頂上加一盞跟著電源亮暗的燈。 */
    /**
     * 一種家具：由下往上的方塊、要不要接電源的燈，以及「|」後面的選項（{@code key=value} 或單字）：
     * face=wall|aisle|table（正面朝向的規則）、axis=long（原木沿長邊）、front0／front1=方塊或 item_frame:物品／glow_item_frame:物品
     * （正面那一格第 0／1 層貼的東西；只能是按鈕、拉桿或物品展示框這種不擋路的）、back1=活板門（第 1 層、椅背那一側）、
     * top=方塊:one（每一座挑一格放在頂上，例如收銀台的螢幕）、vary0／vary1=方塊,方塊…（第 0／1 層照位置輪流換）、books（雕紋書架插滿書）。
     */
    public record Furniture(List<Material> stack, boolean light, Map<String, String> opts) {
        public Furniture(List<Material> stack, boolean light) {
            this(stack, light, Map.of());
        }

        public String opt(String k) {
            return opts.get(k);
        }
    }

    public final String id;
    public final String name, type, description;
    /** 地圖自己設的木桶數量（meta 的 barrels:；沒寫是 -1，照 pve.barrel-area 的密度算）。 */
    public final int barrels;
    /** [層][z] 一行字串，已經用空白補齊成 W 寬。 */
    public final List<List<String>> floors = new ArrayList<>();
    public final Map<Character, Furniture> furniture = new HashMap<>();
    /** 房間名稱（[rooms]）：樓層（0 起算）、範圍（含兩角）、名稱。門口的告示牌用。 */
    public record RoomName(int floor, int x1, int z1, int x2, int z2, String name) {
        public boolean contains(int f, int x, int z) {
            return f == floor && x >= Math.min(x1, x2) && x <= Math.max(x1, x2) && z >= Math.min(z1, z2) && z <= Math.max(z1, z2);
        }
    }
    public final List<RoomName> rooms = new ArrayList<>();
    public final Map<String, String> palette = new LinkedHashMap<>();
    /** [sounds] 區塊：每行一筆「key = value」（同一個 key 可以重複，例如好幾個 zone），見 game.MapAudio。 */
    public final List<String[]> sounds = new ArrayList<>();
    /** [interactions] 區塊：地圖專屬機關（迎賓鈴、球車、蒸氣閥門），見 FixedMapGenerator.gimmicks()。 */
    public final List<String[]> interactions = new ArrayList<>();
    /** 讀檔時發現的問題（不認識的方塊……），蓋的時候會一起列出來。 */
    public final List<String> problems = new ArrayList<>();
    public final int W, H;

    private FixedMap(String id, Map<String, String> meta, List<List<String>> raw) {
        this.id = id;
        this.name = meta.getOrDefault("name", id);
        this.type = meta.getOrDefault("type", "indoor");
        this.description = meta.getOrDefault("description", "");
        int bc = -1;
        try {
            bc = Integer.parseInt(meta.getOrDefault("barrels", "-1").trim());
        } catch (NumberFormatException ignored) {
        }
        this.barrels = bc;
        int w = 0, h = 0;
        for (List<String> f : raw) {
            h = Math.max(h, f.size());
            for (String r : f) w = Math.max(w, r.length());
        }
        this.W = w;
        this.H = h;
        for (List<String> f : raw) {
            List<String> rows = new ArrayList<>();
            for (int z = 0; z < h; z++) {
                String r = z < f.size() ? f.get(z) : "";
                rows.add(r + " ".repeat(w - r.length()));
            }
            floors.add(rows);
        }
    }

    public int floorCount() {
        return floors.size();
    }

    /** 第 f 層 (x, z) 的字元，範圍外是空白。 */
    public char at(int f, int x, int z) {
        if (f < 0 || f >= floors.size() || x < 0 || z < 0 || x >= W || z >= H) return ' ';
        return floors.get(f).get(z).charAt(x);
    }

    public static FixedMap load(Path file) throws IOException {
        String id = file.getFileName().toString().replaceFirst("\\.txt$", "");
        return parse(id, Files.readAllLines(file, StandardCharsets.UTF_8));
    }

    public static FixedMap parse(String id, List<String> lines) {
        Map<String, String> meta = new HashMap<>();
        List<List<String>> raw = new ArrayList<>();
        Map<String, String> furn = new LinkedHashMap<>(), pal = new LinkedHashMap<>();
        List<String[]> snd = new ArrayList<>(), inter = new ArrayList<>();
        String sec = null;
        List<String> cur = null;
        List<RoomName> roomNames = new ArrayList<>();
        List<String> problems0 = new ArrayList<>();
        for (String line0 : lines) {
            String line = line0.replace("\r", "").replace("\t", "    ");
            String st = line.strip();
            if (st.startsWith("[") && st.endsWith("]")) {
                String name = st.substring(1, st.length() - 1).strip();
                if (name.toUpperCase(Locale.ROOT).endsWith("F") && name.length() > 1 && name.substring(0, name.length() - 1).chars().allMatch(Character::isDigit)) {
                    cur = new ArrayList<>();
                    raw.add(cur);
                    sec = "map";
                } else sec = name.toLowerCase(Locale.ROOT);
                continue;
            }
            if ("map".equals(sec)) {
                cur.add(line); // 上面空的列也算（2F 只有一部分時要對齊 1F）
                continue;
            }
            int hash = line.indexOf('#');
            if (hash >= 0) line = line.substring(0, hash);
            st = line.strip();
            if (st.isEmpty()) continue;
            if (sec == null) {
                int c = st.indexOf(':');
                if (c > 0) meta.put(st.substring(0, c).strip(), st.substring(c + 1).strip());
            } else if (sec.equals("furniture") || sec.equals("palette")) {
                int e = st.indexOf('=');
                if (e > 0) (sec.equals("furniture") ? furn : pal).put(st.substring(0, e).strip(), st.substring(e + 1).strip());
            } else if (sec.equals("rooms")) {
                // 1F x1,z1 x2,z2 = 名稱
                int e = st.indexOf('=');
                if (e > 0) {
                    String[] k = st.substring(0, e).trim().split("[ \\t]+");
                    String name = st.substring(e + 1).trim();
                    try {
                        int f = Integer.parseInt(k[0].replaceAll("[^0-9]", "")) - 1;
                        String[] a = k[1].split(","), b2 = k[2].split(",");
                        roomNames.add(new RoomName(f, Integer.parseInt(a[0]), Integer.parseInt(a[1]), Integer.parseInt(b2[0]), Integer.parseInt(b2[1]), name));
                    } catch (RuntimeException ex) {
                        problems0.add("[rooms] 看不懂：" + st);
                    }
                }
            } else if (sec.equals("sounds") || sec.equals("interactions")) {
                int e = st.indexOf('=');
                if (e > 0) (sec.equals("sounds") ? snd : inter).add(new String[]{st.substring(0, e).strip().toLowerCase(Locale.ROOT), st.substring(e + 1).strip()});
            }
        }
        for (List<String> f : raw) while (!f.isEmpty() && f.get(f.size() - 1).isBlank()) f.remove(f.size() - 1);
        FixedMap m = new FixedMap(id, meta, raw);
        m.rooms.addAll(roomNames);
        m.problems.addAll(problems0);
        m.palette.putAll(pal);
        m.sounds.addAll(snd);
        m.interactions.addAll(inter);
        for (Map.Entry<String, String> e : furn.entrySet()) {
            if (e.getKey().length() != 1) {
                m.problems.add("家具代號要是一個字母：" + e.getKey());
                continue;
            }
            String v = e.getValue();
            boolean light = false;
            Map<String, String> opts = new HashMap<>();
            int bar = v.indexOf('|');
            if (bar >= 0) {
                for (String tok : v.substring(bar + 1).trim().split("\s+")) {
                    if (tok.isEmpty()) continue;
                    int eq = tok.indexOf('=');
                    if (eq > 0) opts.put(tok.substring(0, eq).toLowerCase(Locale.ROOT), tok.substring(eq + 1).toLowerCase(Locale.ROOT));
                    else if (tok.equalsIgnoreCase("light")) light = true;
                    else opts.put(tok.toLowerCase(Locale.ROOT), "true");
                }
                v = v.substring(0, bar);
            }
            List<Material> stack = new ArrayList<>();
            for (String part : v.split(",")) {
                Material mat = m.material(part.strip(), null);
                if (mat != null) stack.add(mat);
            }
            if (stack.isEmpty()) stack.add(Material.SPRUCE_PLANKS);
            m.furniture.put(e.getKey().charAt(0), new Furniture(stack, light, Map.copyOf(opts)));
        }
        if (m.floors.isEmpty()) m.problems.add("沒有任何一層（[1F]）");
        return m;
    }

    /** 方塊名稱轉成 Material；不認識就記下來，回傳 fallback。 */
    /** 物品（展示框裡放的東西，不一定是方塊）。 */
    public Material item(String name, Material fallback) {
        if (name == null || name.isBlank()) return fallback;
        Material mat = Material.matchMaterial(name.strip().toLowerCase(Locale.ROOT));
        if (mat == null || !mat.isItem()) {
            problems.add("不認識的物品：" + name);
            return fallback;
        }
        return mat;
    }

    public Material material(String name, Material fallback) {
        if (name == null || name.isBlank()) return fallback;
        Material mat = Material.matchMaterial(name.strip().toLowerCase(Locale.ROOT));
        if (mat == null || !mat.isBlock()) {
            problems.add("不認識的方塊：" + name);
            return fallback;
        }
        return mat;
    }

    /** 調色盤的一個方塊。 */
    public Material pal(String key, Material fallback) {
        return material(palette.get(key), fallback);
    }

    /** 調色盤的方塊清單（逗號分開）。 */
    public List<Material> palList(String key, List<Material> fallback) {
        String v = palette.get(key);
        if (v == null || v.isBlank()) return fallback;
        List<Material> out = new ArrayList<>();
        for (String part : v.split(",")) {
            Material mat = material(part.strip(), null);
            if (mat != null) out.add(mat);
        }
        return out.isEmpty() ? fallback : out;
    }

    /** 門的方塊：palette 的 door 可以寫 spruce 或 spruce_door。 */
    public Material doorMaterial() {
        String v = palette.get("door");
        if (v == null) return Material.SPRUCE_DOOR;
        String n = v.strip().toLowerCase(Locale.ROOT);
        if (!n.endsWith("_door")) n = n + "_door";
        Material mat = Material.matchMaterial(n);
        if (mat == null || !org.bukkit.Tag.DOORS.isTagged(mat)) {
            problems.add("不認識的門：" + v);
            return Material.SPRUCE_DOOR;
        }
        return mat;
    }
}
