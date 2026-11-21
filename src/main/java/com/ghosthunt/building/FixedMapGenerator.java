package com.ghosthunt.building;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Levelled;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.block.data.type.Stairs;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;

/**
 * 照 {@link FixedMap} 的平面圖蓋 PvE 地圖：格局固定，每次蓋（每局）隨機挑大門 2 扇、保全室 2 間、木桶位置；
 * 電源從所有候選裡由遊戲開局時挑。產生的 {@link Building} 欄位跟不規則大樓一樣，遊戲系統不用改。
 *
 * <p>符號（詳細見 docs/PvE地圖格式.md）：
 * {@code . , ;} 室內地板／室外地面／室外路面，{@code # % +} 牆／高樹籬／矮欄杆，{@code D W O I} 門／雙開門／開口／鐵欄杆門，
 * {@code h H} 蹲伏小洞／暗格入口，{@code ^ v < >} 樓梯、{@code =} 上一層的樓梯開口，{@code 1-9} 地下通道出入口，
 * {@code G Q E S K J C} 大門候選／保全室終端機候選／電源候選／出生點／鬼房／監獄籠子／可推倒的櫃子，小寫字母是家具。</p>
 */
final class FixedMapGenerator {

    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final String MARKERS = "SKJEQC";

    private final BuildingSettings s;
    private final FixedMap fm;
    private final Random rnd;
    private final int W, H, F, fh;
    private Layout L;
    private Building b;
    private final BlockMap map = new BlockMap();
    private final List<String> errors = new ArrayList<>(), warnings = new ArrayList<>();
    private final Map<Material, BlockData> cache = new HashMap<>();

    // 材質
    private Material wallM, outerM, ceilM, hedgeM, hedgeBaseM, fenceM, doorM;
    private List<Material> outdoorM, deckM, pathM, roomFloorsM;

    // 分析結果
    private int[][][] region;            // [f][z][x] 區域編號，-1 = 不是區域
    private final List<Region> regions = new ArrayList<>();
    private final Set<Long> holes = new HashSet<>();      // 樓梯開口（上一層，前 4 格）
    private final Set<Long> stairTiles = new HashSet<>(); // 樓梯本身（下一層）
    private final Set<Long> reserved = new HashSet<>();   // 不放木桶
    private final Set<Long> occupied = new HashSet<>();   // 走路高度有東西（家具、終端機、櫃子、欄杆……）
    private final List<int[]> stairs = new ArrayList<>(); // 每格樓梯 {f, x, z, dx, dz, 第幾階 i, 哪一組}
    private int groupCount;

    /** 一個區域：房間或一塊戶外空地（牆、門、開口切開）。 */
    private static final class Region {
        final int f, id;
        final List<int[]> tiles = new ArrayList<>();
        boolean indoor, ghost, jail, security;
        int plainIndoor, plainOutdoor, path;

        Region(int f, int id) {
            this.f = f;
            this.id = id;
        }

        int[] center() {
            long sx = 0, sz = 0;
            for (int[] t : tiles) {
                sx += t[0];
                sz += t[1];
            }
            int cx = Math.round((float) sx / tiles.size()), cz = Math.round((float) sz / tiles.size());
            int[] best = tiles.get(0);
            long bd = Long.MAX_VALUE;
            for (int[] t : tiles) {
                long d = (long) (t[0] - cx) * (t[0] - cx) + (long) (t[1] - cz) * (t[1] - cz);
                if (d < bd) {
                    bd = d;
                    best = t;
                }
            }
            return best;
        }
    }

    private FixedMapGenerator(BuildingSettings s, FixedMap fm, long seed) {
        this.s = s;
        this.fm = fm;
        this.rnd = new Random(seed);
        this.W = fm.W;
        this.H = fm.H;
        this.F = fm.floorCount();
        this.fh = s.floorHeight();
    }

    static BuildingGenerator.Result generate(BuildingSettings s, FixedMap fm, long seed) {
        FixedMapGenerator g = new FixedMapGenerator(s, fm, seed);
        try {
            g.run(seed);
        } catch (RuntimeException e) {
            g.errors.add("產生時出錯：" + e);
            Bukkit.getLogger().log(java.util.logging.Level.WARNING, "[GhostHunt] 固定地圖產生失敗", e);
        }
        List<String> warn = new ArrayList<>(fm.problems);
        warn.addAll(g.warnings);
        if (!g.errors.isEmpty()) return new BuildingGenerator.Result(null, null, 1, g.errors, warn);
        return new BuildingGenerator.Result(g.b, g.map, 1, List.of(), warn);
    }

    // =====================================================================

    private char at(int f, int x, int z) {
        return fm.at(f, x, z);
    }

    private static boolean floorLike(char c) {
        return c == '.' || c == ',' || c == ';' || MARKERS.indexOf(c) >= 0 || Character.isDigit(c);
    }

    private static boolean furn(char c) {
        return Character.isLowerCase(c) && c != 'h' && c != 'v';
    }

    private static boolean arrow(char c) {
        return c == '>' || c == '<' || c == '^' || c == 'v';
    }

    private static boolean regionChar(char c) {
        return floorLike(c) || furn(c) || arrow(c) || c == '=';
    }

    private static boolean wallChar(char c) {
        return c == '#' || c == '%' || c == '+';
    }

    private long key(int f, int x, int z) {
        return ((long) f * H + z) * W + x;
    }

    private Pos at(int f, int x, int dy, int z) {
        return L.rel(x, L.floorY(f) + 1 + dy, z);
    }

    private BlockData bd(Material m) {
        return cache.computeIfAbsent(m, Bukkit::createBlockData);
    }

    /** 樹葉放下去要設成「不會掉」，不然遠離原木會慢慢消失。 */
    private BlockData solidData(Material m) {
        BlockData d = Bukkit.createBlockData(m);
        if (d instanceof Leaves lv) lv.setPersistent(true);
        return d;
    }

    private BlockData light() {
        Levelled d = (Levelled) Bukkit.createBlockData(Material.LIGHT);
        d.setLevel(s.emergencyLight());
        return d;
    }

    // =====================================================================

    private void run(long seed) {
        if (F < 1 || W < 10 || H < 10) {
            errors.add("地圖太小或沒有樓層（" + W + "×" + H + "，" + F + " 層）");
            return;
        }
        loadPalette();
        int[] start = findStart();
        if (start == null) {
            errors.add("1F 沒有出生點 S");
            return;
        }
        L = Layout.irregular(Math.max(W, H), F, fh, s.originX(), s.originY(), s.originZ(), start[0], start[1]);
        b = new Building(seed, L);
        b.pve = true;
        b.mapName = fm.id;
        b.sounds = List.copyOf(fm.sounds);

        findRegions();
        findStairs();
        if (!errors.isEmpty()) return;
        Region ghost = regions.stream().filter(r -> r.ghost).findFirst().orElse(null);
        Region jail = regions.stream().filter(r -> r.jail).findFirst().orElse(null);
        if (ghost == null) errors.add("沒有鬼房（K）");
        if (jail == null) errors.add("沒有監獄（J）");
        if (!errors.isEmpty()) return;

        List<int[]> securityTiles = pickSecurity();
        List<GateRun> gates = pickGates();
        if (!errors.isEmpty()) return;

        clearAndGround();
        walls();
        stairBlocks();
        openings(gates);
        markers(securityTiles, jail, ghost);
        furniture();
        basement();
        ceilings();
        lights();
        map.connectPanes();
        barrels();
        for (Pos p : b.ghostDoors) b.ghostDoorBlocks.put(p, map.get(p));
        for (Building.Gate g : b.gates) for (Pos p : g.opening()) b.gateBlocks.put(p, map.get(p));
        for (Pos p : b.doors) {
            b.doorBlocks.put(p, map.get(p));
            b.doorBlocks.put(p.add(0, 1, 0), map.get(p.add(0, 1, 0)));
        }
        validate();
        record();
    }

    private void loadPalette() {
        wallM = fm.pal("wall", Material.SMOOTH_QUARTZ);
        outerM = fm.pal("outer-wall", wallM);
        ceilM = fm.pal("ceiling", Material.SMOOTH_STONE);
        hedgeM = fm.pal("hedge", Material.DARK_OAK_LEAVES);
        fenceM = fm.pal("fence", Material.DARK_OAK_FENCE);
        outdoorM = fm.palList("outdoor", List.of(Material.GRASS_BLOCK));
        hedgeBaseM = fm.pal("hedge-base", outdoorM.get(0));
        deckM = fm.palList("deck", outdoorM); // 2F 以上的戶外地面（陽台、天橋）
        pathM = fm.palList("path", List.of(Material.STONE_BRICKS));
        roomFloorsM = fm.palList("room-floors", List.of(Material.WHITE_CONCRETE, Material.LIGHT_BLUE_TERRACOTTA, Material.CYAN_WOOL,
                Material.LIME_TERRACOTTA, Material.PINK_TERRACOTTA, Material.YELLOW_TERRACOTTA, Material.LIGHT_GRAY_WOOL, Material.ORANGE_TERRACOTTA));
        doorM = fm.doorMaterial();
    }

    private int[] findStart() {
        for (int z = 0; z < H; z++) for (int x = 0; x < W; x++) if (at(0, x, z) == 'S') return new int[]{x, z};
        return null;
    }

    // ---- 區域 ----

    private void findRegions() {
        region = new int[F][H][W];
        for (int f = 0; f < F; f++) for (int z = 0; z < H; z++) java.util.Arrays.fill(region[f][z], -1);
        for (int f = 0; f < F; f++) {
            for (int z = 0; z < H; z++) {
                for (int x = 0; x < W; x++) {
                    if (region[f][z][x] >= 0 || !regionChar(at(f, x, z))) continue;
                    Region r = new Region(f, regions.size());
                    ArrayDeque<int[]> q = new ArrayDeque<>();
                    q.add(new int[]{x, z});
                    region[f][z][x] = r.id;
                    while (!q.isEmpty()) {
                        int[] t = q.poll();
                        r.tiles.add(t);
                        char c = at(f, t[0], t[1]);
                        if (c == '.') r.plainIndoor++;
                        else if (c == ',') r.plainOutdoor++;
                        else if (c == ';') {
                            r.plainOutdoor++;
                            r.path++;
                        } else if (c == 'K') r.ghost = true;
                        else if (c == 'J') r.jail = true;
                        for (int[] d : DIRS) {
                            int nx = t[0] + d[0], nz = t[1] + d[1];
                            if (nx < 0 || nz < 0 || nx >= W || nz >= H || region[f][nz][nx] >= 0 || !regionChar(at(f, nx, nz))) continue;
                            region[f][nz][nx] = r.id;
                            q.add(new int[]{nx, nz});
                        }
                    }
                    r.indoor = r.plainIndoor >= r.plainOutdoor && (r.plainIndoor > 0 || r.ghost);
                    regions.add(r);
                }
            }
        }
    }

    private Region regionAt(int f, int x, int z) {
        if (f < 0 || f >= F || x < 0 || z < 0 || x >= W || z >= H) return null;
        int id = region[f][z][x];
        return id < 0 ? null : regions.get(id);
    }

    /** 這一格算室內（要蓋天花板）：室內區域的格子，或旁邊（含斜角）有室內格子的牆、門。 */
    private boolean indoorTile(int f, int x, int z) {
        char c = at(f, x, z);
        Region r = regionAt(f, x, z);
        if (r != null) {
            if (c == '.') return true;
            if (c == ',' || c == ';') return false;
            return r.indoor;
        }
        if (c == '%' || c == '+' || c == ' ') return false;
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++) {
                Region n = regionAt(f, x + dx, z + dz);
                char nc = at(f, x + dx, z + dz);
                if (n != null && (nc == '.' || (nc != ',' && nc != ';' && n.indoor))) return true;
            }
        return false;
    }

    private boolean touchesOut(int f, int x, int z) {
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) if (at(f, x + dx, z + dz) == ' ') return true;
        return false;
    }

    // ---- 樓梯 ----

    private void findStairs() {
        Set<Long> seen = new HashSet<>();
        for (int f = 0; f < F; f++) {
            for (int z = 0; z < H; z++) {
                for (int x = 0; x < W; x++) {
                    char c = at(f, x, z);
                    if (!arrow(c) || seen.contains(key(f, x, z))) continue;
                    List<int[]> comp = new ArrayList<>();
                    ArrayDeque<int[]> q = new ArrayDeque<>();
                    q.add(new int[]{x, z});
                    seen.add(key(f, x, z));
                    while (!q.isEmpty()) {
                        int[] t = q.poll();
                        comp.add(t);
                        for (int[] d : DIRS) {
                            int nx = t[0] + d[0], nz = t[1] + d[1];
                            if (at(f, nx, nz) == c && seen.add(key(f, nx, nz))) q.add(new int[]{nx, nz});
                        }
                    }
                    int dx = c == '>' ? 1 : c == '<' ? -1 : 0, dz = c == 'v' ? 1 : c == '^' ? -1 : 0;
                    int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
                    Set<Integer> lanes = new HashSet<>();
                    for (int[] t : comp) {
                        int a = t[0] * dx + t[1] * dz;
                        lo = Math.min(lo, a);
                        hi = Math.max(hi, a);
                        lanes.add(dx != 0 ? t[1] : t[0]);
                    }
                    if (hi - lo != fh - 1 || comp.size() != fh * 2 || lanes.size() != 2) {
                        errors.add((f + 1) + "F 樓梯 " + c + "（" + x + "," + z + "）形狀不對：要 " + fh + " 格長 × 2 格寬");
                        continue;
                    }
                    if (f + 1 >= F) {
                        errors.add((f + 1) + "F 樓梯（" + x + "," + z + "）上面沒有樓層");
                        continue;
                    }
                    int group = groupCount++;
                    for (int[] t : comp) {
                        int i = t[0] * dx + t[1] * dz - lo;
                        stairTiles.add(key(f, t[0], t[1]));
                        if (i < fh - 1) holes.add(key(f + 1, t[0], t[1]));
                        if (at(f + 1, t[0], t[1]) != '=') warnings.add((f + 2) + "F（" + t[0] + "," + t[1] + "）應該畫 =（樓梯開口），照樓梯開洞");
                        stairs.add(new int[]{f, t[0], t[1], dx, dz, i, group});
                    }
                }
            }
        }
    }

    // ---- 保全室、大門 ----

    private List<int[]> pickSecurity() {
        Map<Integer, List<int[]>> byRegion = new LinkedHashMap<>();
        for (int f = 0; f < F; f++)
            for (int z = 0; z < H; z++)
                for (int x = 0; x < W; x++)
                    if (at(f, x, z) == 'Q' && regionAt(f, x, z) != null) byRegion.computeIfAbsent(region[f][z][x], k -> new ArrayList<>()).add(new int[]{f, x, z});
        List<Integer> ids = new ArrayList<>(byRegion.keySet());
        if (ids.size() < 2) {
            errors.add("保全室候選（Q）只有 " + ids.size() + " 間房間，至少要 2 間");
            return List.of();
        }
        // 所有組合裡，挑符合條件（不同樓層、相距 ≥ 20）的，平均隨機；沒有就放寬
        List<int[]> pairs = new ArrayList<>();
        for (int level = 0; level < 3 && pairs.isEmpty(); level++) {
            for (int i = 0; i < ids.size(); i++)
                for (int j = i + 1; j < ids.size(); j++) {
                    Region ra = regions.get(ids.get(i)), rb = regions.get(ids.get(j));
                    int[] ca = ra.center(), cb = rb.center();
                    double d = Math.hypot(ca[0] - cb[0], ca[1] - cb[1]);
                    boolean diffFloor = ra.f != rb.f || F == 1;
                    if (level == 0 && (!diffFloor || d < 20)) continue;
                    if (level == 1 && d < 20) continue;
                    pairs.add(new int[]{ids.get(i), ids.get(j)});
                }
        }
        int[] pr = pairs.get(rnd.nextInt(pairs.size()));
        int a = pr[0], best = pr[1];
        List<int[]> out = new ArrayList<>();
        for (int id : List.of(a, best)) {
            List<int[]> qs = byRegion.get(id);
            out.add(qs.get(rnd.nextInt(qs.size())));
            regions.get(id).security = true;
        }
        return out;
    }

    /** 一個大門候選：連在一起的 G，dx/dz 往外。 */
    private record GateRun(int f, List<int[]> tiles, int dx, int dz) {
        int[] mid() {
            return tiles.get(tiles.size() / 2);
        }
    }

    private List<GateRun> gateRuns() {
        List<GateRun> out = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (int f = 0; f < F; f++) {
            for (int z = 0; z < H; z++) {
                for (int x = 0; x < W; x++) {
                    if (at(f, x, z) != 'G' || !seen.add(key(f, x, z))) continue;
                    List<int[]> comp = new ArrayList<>();
                    ArrayDeque<int[]> q = new ArrayDeque<>();
                    q.add(new int[]{x, z});
                    while (!q.isEmpty()) {
                        int[] t = q.poll();
                        comp.add(t);
                        for (int[] d : DIRS) if (at(f, t[0] + d[0], t[1] + d[1]) == 'G' && seen.add(key(f, t[0] + d[0], t[1] + d[1])))
                            q.add(new int[]{t[0] + d[0], t[1] + d[1]});
                    }
                    comp.sort(Comparator.comparingInt((int[] t) -> t[0]).thenComparingInt(t -> t[1]));
                    int[] m = comp.get(comp.size() / 2);
                    int[] out1 = null;
                    for (int[] d : DIRS)
                        if (at(f, m[0] + d[0], m[1] + d[1]) == ' ' && regionAt(f, m[0] - d[0], m[1] - d[1]) != null) out1 = d;
                    if (out1 == null) {
                        warnings.add((f + 1) + "F 大門候選（" + m[0] + "," + m[1] + "）不在最外圈，跳過");
                        continue;
                    }
                    int rx = m[0] - 2 * out1[0], rz = m[1] - 2 * out1[1];
                    if (regionAt(f, rx, rz) == null || !floorLike(at(f, rx, rz))) {
                        warnings.add((f + 1) + "F 大門候選（" + m[0] + "," + m[1] + "）往內 2 格不是空地，放不了讀卡機，跳過");
                        continue;
                    }
                    out.add(new GateRun(f, comp, out1[0], out1[1]));
                }
            }
        }
        return out;
    }

    private int nearestStair(GateRun g) {
        int best = -1;
        double bd = Double.MAX_VALUE;
        for (int i = 0; i < stairs.size(); i++) {
            int[] st = stairs.get(i);
            if (st[5] != 0) continue;
            double d = Math.hypot(st[1] - g.mid()[0], st[2] - g.mid()[1]);
            if (d < bd) {
                bd = d;
                best = st[6];
            }
        }
        return best;
    }

    private List<GateRun> pickGates() {
        List<GateRun> all = gateRuns();
        int want = Math.min(s.gateCount(), all.size());
        if (all.size() < 2) {
            errors.add("大門候選（G）只有 " + all.size() + " 個能用，至少要 2 個");
            return List.of();
        }
        // 所有兩扇的組合裡，挑符合條件的，平均隨機；條件由嚴到寬：不同樓層、距離、最近的樓梯不同
        double minD = s.gateMinDistance();
        // 大門候選全在同一層（例如 2F 不想開在半空中）：「不同樓層」「靠不同樓梯」都不適用，直接只看距離
        boolean oneFloor = all.stream().mapToInt(GateRun::f).distinct().count() == 1;
        int start = oneFloor ? 3 : 0;
        for (int level = start; level < 6; level++) {
            boolean diffFloor = level < 2 && F > 1, diffStair = level < 1 || level == 2;
            double dist = level < 4 ? minD : level == 4 ? minD / 2 : 0;
            List<int[]> pairs = new ArrayList<>();
            for (int i = 0; i < all.size(); i++)
                for (int j = i + 1; j < all.size(); j++) {
                    GateRun g = all.get(i), o = all.get(j);
                    if (diffFloor && o.f() == g.f()) continue;
                    if (Math.hypot(o.mid()[0] - g.mid()[0], o.mid()[1] - g.mid()[1]) < dist) continue;
                    if (diffStair && !stairs.isEmpty() && nearestStair(o) == nearestStair(g)) continue;
                    pairs.add(new int[]{i, j});
                }
            if (!pairs.isEmpty()) {
                if (level > start) warnings.add("大門放寬了條件才挑得到（第 " + level + " 級）");
                int[] pr = pairs.get(rnd.nextInt(pairs.size()));
                return want >= 2 ? List.of(all.get(pr[0]), all.get(pr[1])) : List.of(all.get(pr[0]));
            }
        }
        return all.subList(0, want);
    }

    // =====================================================================
    // 放方塊
    // =====================================================================

    private Material groundFor(int f, int x, int z) {
        char c = at(f, x, z);
        Region r = regionAt(f, x, z);
        if (r != null) {
            if (c == 'J') return Material.POLISHED_BLACKSTONE_BRICKS;
            if (r.ghost) return Material.SCULK;
            if (c == ',') return pick(f > 0 ? deckM : outdoorM, x, z);
            if (c == ';') return pick(pathM, x, z);
            if (c == '.' || r.indoor) return r.security ? Material.BLUE_TERRACOTTA : roomFloorsM.get(Math.floorMod(r.id * 7 + f, roomFloorsM.size()));
            return r.path * 2 > r.plainOutdoor ? pick(pathM, x, z) : pick(f > 0 ? deckM : outdoorM, x, z);
        }
        if (c == '#') return wallM;
        if (c == '%') return hedgeBaseM;
        // 門、開口、欄杆：跟旁邊的地面一樣
        for (int[] d : DIRS) {
            Region n = regionAt(f, x + d[0], z + d[1]);
            if (n != null && regionChar(at(f, x + d[0], z + d[1]))) return groundFor(f, x + d[0], z + d[1]);
        }
        return outdoorM.get(0);
    }

    /** 主要材質為主，偶爾混第二種（看起來比較自然）。 */
    private static Material pick(List<Material> list, int x, int z) {
        if (list.size() == 1) return list.get(0);
        int h = Math.floorMod(x * 73856093 ^ z * 19349663, 100);
        if (h < 75) return list.get(0);
        return list.get(1 + h % (list.size() - 1));
    }

    private void clearAndGround() {
        int size = Math.max(W, H);
        int clear = Math.max(size, s.clearSize());
        int clearTop = L.floorY(0) + Math.max(F, s.clearFloors()) * fh + 1;
        map.fill(L.rel(-4, L.floorY(0) - 4, -4), L.rel(clear + 3, clearTop, clear + 3), Material.AIR);
        for (int f = 0; f < F; f++) {
            int fy = L.floorY(f);
            for (int z = 0; z < H; z++)
                for (int x = 0; x < W; x++) {
                    if (at(f, x, z) == ' ' || holes.contains(key(f, x, z))) continue;
                    map.set(L.rel(x, fy, z), groundFor(f, x, z));
                }
        }
    }

    private Material wallMaterialAt(int f, int x, int z) {
        return touchesOut(f, x, z) ? outerM : wallM;
    }

    private void walls() {
        for (int f = 0; f < F; f++) {
            for (int z = 0; z < H; z++) {
                for (int x = 0; x < W; x++) {
                    char c = at(f, x, z);
                    if (c == '#') {
                        Material m = wallMaterialAt(f, x, z);
                        for (int dy = 0; dy < fh - 1; dy++) map.set(at(f, x, dy, z), m);
                    } else if (c == '%') {
                        for (int dy = 0; dy < 3; dy++) map.set(at(f, x, dy, z), solidData(hedgeM));
                    } else if (c == '+') {
                        map.set(at(f, x, 0, z), fenceM);
                    }
                    if (wallChar(c)) occupied.add(key(f, x, z));
                }
            }
        }
    }

    private void stairBlocks() {
        Material fill = Material.STONE_BRICKS;
        for (int[] st : stairs) {
            int f = st[0], x = st[1], z = st[2], i = st[5];
            int fy = L.floorY(f);
            Stairs sd = (Stairs) Bukkit.createBlockData(Material.STONE_BRICK_STAIRS);
            sd.setHalf(Bisected.Half.BOTTOM);
            sd.setFacing(face(st[3], st[4]));
            map.set(L.rel(x, fy + 1 + i, z), sd);
            for (int y = fy + 1; y <= fy + i; y++) map.set(L.rel(x, y, z), fill);
            for (int y = fy + i + 2; y <= fy + fh; y++) if (i < fh - 1) map.set(L.rel(x, y, z), Material.AIR);
            reserved.add(key(f, x, z));
            reserve(f, x, z, 2);
        }
        // 上一層開口旁邊：走得到的格子放欄杆，免得掉下去
        for (int[] st : stairs) {
            int f = st[0] + 1, x = st[1], z = st[2];
            if (!holes.contains(key(f, x, z))) continue;
            for (int[] d : DIRS) {
                int nx = x + d[0], nz = z + d[1];
                long k = key(f, nx, nz);
                if (holes.contains(k) || stairTiles.contains(key(f - 1, nx, nz)) && at(f, nx, nz) == '=') continue;
                char c = at(f, nx, nz);
                if (!floorLike(c) && c != '=') continue;
                map.set(at(f, nx, 0, nz), RoomBuilder.RAILING);
                occupied.add(k);
            }
            reserve(f, x, z, 2);
        }
        // 欄杆圍出來的死角（例如開口斜對角那一格，四邊都被擋住）：一起補欄杆，不要留走不進去的格子
        for (int pass = 0; pass < 2; pass++) {
            for (int[] st : stairs) {
                int f = st[0] + 1;
                for (int dx = -2; dx <= 2; dx++)
                    for (int dz = -2; dz <= 2; dz++) {
                        int x = st[1] + dx, z = st[2] + dz;
                        long k = key(f, x, z);
                        if (!floorLike(at(f, x, z)) || occupied.contains(k) || holes.contains(k)) continue;
                        boolean open = false;
                        for (int[] d : DIRS) {
                            long nk = key(f, x + d[0], z + d[1]);
                            char c = at(f, x + d[0], z + d[1]);
                            if ((regionChar(c) || "DWOG".indexOf(c) >= 0) && !furn(c) && !occupied.contains(nk) && !holes.contains(nk)) open = true;
                        }
                        if (open) continue;
                        map.set(at(f, x, 0, z), RoomBuilder.RAILING);
                        occupied.add(k);
                    }
            }
        }
    }

    private static BlockFace face(int dx, int dz) {
        if (dx > 0) return BlockFace.EAST;
        if (dx < 0) return BlockFace.WEST;
        if (dz > 0) return BlockFace.SOUTH;
        return BlockFace.NORTH;
    }

    private void reserve(int f, int x, int z, int r) {
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) reserved.add(key(f, x + dx, z + dz));
    }

    /** 牆上這一格（門、洞）兩邊的方向：true = 東西兩邊是地面（穿過去沿 x）。 */
    private boolean passX(int f, int x, int z) {
        return walkSide(f, x - 1, z) && walkSide(f, x + 1, z);
    }

    private boolean walkSide(int f, int x, int z) {
        char c = at(f, x, z);
        return regionChar(c) && !furn(c);
    }

    /** 開口那一條線的牆是哪一種（# % +），決定上面補什麼。 */
    private char lineWall(int f, int x, int z, boolean px) {
        int tx = px ? 0 : 1, tz = px ? 1 : 0;
        for (int k = 1; k <= 3; k++) {
            for (int sg : new int[]{1, -1}) {
                char c = at(f, x + tx * k * sg, z + tz * k * sg);
                if (wallChar(c)) return c;
            }
        }
        return '#';
    }

    /** 開口上面補牆（從 dy 開始到牆頂）。 */
    private void topUp(int f, int x, int z, int fromDy, char wall) {
        int top = wall == '#' ? fh - 1 : wall == '%' ? 3 : 0;
        for (int dy = fromDy; dy < top; dy++) {
            if (wall == '%') map.set(at(f, x, dy, z), solidData(hedgeM));
            else map.set(at(f, x, dy, z), wallMaterialAt(f, x, z));
        }
    }

    private void openings(List<GateRun> chosenGates) {
        Set<Long> done = new HashSet<>();
        for (int f = 0; f < F; f++) {
            for (int z = 0; z < H; z++) {
                for (int x = 0; x < W; x++) {
                    char c = at(f, x, z);
                    if ("DWOIh".indexOf(c) < 0 || done.contains(key(f, x, z))) continue;
                    boolean px = passX(f, x, z);
                    if (!px && !(walkSide(f, x, z - 1) && walkSide(f, x, z + 1))) {
                        warnings.add((f + 1) + "F " + c + "（" + x + "," + z + "）兩側不是地面，當成牆");
                        Material m = wallMaterialAt(f, x, z);
                        for (int dy = 0; dy < fh - 1; dy++) map.set(at(f, x, dy, z), m);
                        continue;
                    }
                    char wall = lineWall(f, x, z, px);
                    int tx = px ? 0 : 1, tz = px ? 1 : 0;
                    // 同一種符號沿著牆連在一起的一段
                    List<int[]> run = new ArrayList<>();
                    int sx = x, sz = z;
                    while (at(f, sx - tx, sz - tz) == c) {
                        sx -= tx;
                        sz -= tz;
                    }
                    for (int k = 0; at(f, sx + tx * k, sz + tz * k) == c; k++) run.add(new int[]{sx + tx * k, sz + tz * k});
                    for (int[] t : run) done.add(key(f, t[0], t[1]));
                    for (int[] t : run) reserve(f, t[0], t[1], 2);
                    switch (c) {
                        case 'D' -> {
                            for (int[] t : run) {
                                placeDoor(f, t[0], t[1], px, Door.Hinge.LEFT, false);
                                topUp(f, t[0], t[1], 2, wall);
                            }
                        }
                        case 'W' -> {
                            for (int i = 0; i + 1 < run.size(); i += 2) {
                                int[] a = run.get(i), bb = run.get(i + 1);
                                BlockFace facing = px ? BlockFace.EAST : BlockFace.SOUTH;
                                BlockFace left = facing == BlockFace.EAST ? BlockFace.NORTH : BlockFace.EAST;
                                int dot = (bb[0] - a[0]) * left.getModX() + (bb[1] - a[1]) * left.getModZ();
                                placeDoor(f, a[0], a[1], px, dot > 0 ? Door.Hinge.RIGHT : Door.Hinge.LEFT, true);
                                placeDoor(f, bb[0], bb[1], px, dot > 0 ? Door.Hinge.LEFT : Door.Hinge.RIGHT, true);
                                topUp(f, a[0], a[1], 2, wall);
                                topUp(f, bb[0], bb[1], 2, wall);
                            }
                            if (run.size() % 2 == 1) {
                                int[] t = run.get(run.size() - 1);
                                placeDoor(f, t[0], t[1], px, Door.Hinge.LEFT, false);
                                topUp(f, t[0], t[1], 2, wall);
                                warnings.add((f + 1) + "F 雙開門（" + t[0] + "," + t[1] + "）落單，當成一般門");
                            }
                        }
                        case 'O' -> {
                            List<Pos> lower = new ArrayList<>(), blocks = new ArrayList<>();
                            for (int[] t : run) {
                                for (int dy = 0; dy <= 2; dy++) map.set(at(f, t[0], dy, t[1]), Material.AIR);
                                topUp(f, t[0], t[1], 3, wall);
                                Pos p = at(f, t[0], 0, t[1]);
                                lower.add(p);
                                blocks.add(p);
                                blocks.add(p.add(0, 1, 0));
                            }
                            if (run.size() <= 2) { // 1～2 格寬：可以放障礙物
                                for (Pos p : lower) map.set(p, RoomBuilder.BARRICADE_MARK);
                                b.barricadeSpots.add(new Building.BarricadeSpot(blocks, lower));
                            }
                        }
                        case 'I' -> {
                            for (int[] t : run) {
                                for (int dy = 0; dy <= 2; dy++) {
                                    Pos p = at(f, t[0], dy, t[1]);
                                    map.set(p, Material.IRON_BARS);
                                    b.ghostDoors.add(p);
                                }
                                topUp(f, t[0], t[1], 3, wall);
                            }
                        }
                        case 'h' -> {
                            for (int[] t : run) crawlHole(f, t[0], t[1], wall);
                        }
                        default -> { }
                    }
                }
            }
        }
        // 暗格：H 是入口的小洞，H 背對地面那一格（牆／樹籬）挖成躲藏空間
        for (int f = 0; f < F; f++) {
            for (int z = 0; z < H; z++) {
                for (int x = 0; x < W; x++) {
                    if (at(f, x, z) != 'H') continue;
                    int[] side = null;
                    int n = 0;
                    for (int[] d : DIRS) if (walkSide(f, x + d[0], z + d[1])) {
                        side = d;
                        n++;
                    }
                    int px = side == null ? -1 : x - side[0], pz = side == null ? -1 : z - side[1];
                    char back = side == null ? ' ' : at(f, px, pz);
                    if (n != 1 || (back != '#' && back != '%')) {
                        warnings.add((f + 1) + "F 暗格 H（" + x + "," + z + "）旁邊要剛好 1 格地面、後面要是牆，當成小洞處理");
                        crawlHole(f, x, z, back == '%' ? '%' : '#');
                        continue;
                    }
                    crawlHole(f, x, z, back);
                    int top = back == '#' ? 3 : 2;
                    for (int dy = 0; dy < top; dy++) map.set(at(f, px, dy, pz), Material.AIR);
                    b.rooms.add(new Building.Room(f, RoomType.STORAGE, at(f, px, 0, pz), 1, true));
                    reserve(f, x + side[0], z + side[1], 1);
                }
            }
        }
        // 大門
        for (int f = 0; f < F; f++) {
            for (int z = 0; z < H; z++) {
                for (int x = 0; x < W; x++) {
                    if (at(f, x, z) != 'G') continue;
                    boolean chosen = false;
                    for (GateRun g : chosenGates) for (int[] t : g.tiles()) if (g.f() == f && t[0] == x && t[1] == z) chosen = true;
                    if (chosen) continue;
                    // 沒挑到的大門候選補成牆
                    char wall = '#';
                    for (int[] d : DIRS) if (at(f, x + d[0], z + d[1]) == '%') wall = '%';
                    if (wall == '%') for (int dy = 0; dy < 3; dy++) map.set(at(f, x, dy, z), solidData(hedgeM));
                    else for (int dy = 0; dy < fh - 1; dy++) map.set(at(f, x, dy, z), wallMaterialAt(f, x, z));
                }
            }
        }
        for (GateRun g : chosenGates) {
            int f = g.f();
            List<Pos> opening = new ArrayList<>();
            boolean hedge = false;
            for (int[] t : g.tiles()) for (int[] d : DIRS) if (at(f, t[0] + d[0], t[1] + d[1]) == '%') hedge = true;
            for (int[] t : g.tiles()) {
                for (int dy = 0; dy <= 2; dy++) {
                    Pos p = at(f, t[0], dy, t[1]);
                    map.set(p, Material.IRON_BARS);
                    opening.add(p);
                }
                if (!hedge) topUp(f, t[0], t[1], 3, '#');
                reserve(f, t[0], t[1], 3);
            }
            int[] m = g.mid();
            Pos center = at(f, m[0], 0, m[1]);
            Pos reader = at(f, m[0] - 2 * g.dx(), 0, m[1] - 2 * g.dz());
            Pos exit = at(f, m[0] + 2 * g.dx(), 0, m[1] + 2 * g.dz());
            map.set(reader, Material.LODESTONE);
            occupied.add(key(f, m[0] - 2 * g.dx(), m[1] - 2 * g.dz()));
            map.fill(exit.add(-1, -1, -1), exit.add(1, -1, 1), Material.POLISHED_BLACKSTONE);
            b.gates.add(new Building.Gate(f, center, opening, reader, exit));
        }
        for (GateRun g : gateRuns()) {
            int[] m = g.mid();
            b.gateCandidates.add(new Building.Gate(g.f(), at(g.f(), m[0], 0, m[1]), List.of(), at(g.f(), m[0] - 2 * g.dx(), 0, m[1] - 2 * g.dz()),
                    at(g.f(), m[0] + 2 * g.dx(), 0, m[1] + 2 * g.dz())));
        }
    }

    private void crawlHole(int f, int x, int z, char wall) {
        Pos lower = at(f, x, 0, z);
        org.bukkit.block.data.type.Slab slab = (org.bukkit.block.data.type.Slab) Bukkit.createBlockData(Material.STONE_BRICK_SLAB);
        slab.setType(org.bukkit.block.data.type.Slab.Type.TOP);
        map.set(lower, Material.AIR);
        map.set(lower.add(0, 1, 0), slab);
        topUp(f, x, z, 2, wall == '+' ? '#' : wall);
        b.crawlHoles.add(new Building.CrawlHole(lower, lower.add(0, 1, 0), slab));
        reserve(f, x, z, 2);
    }

    private void placeDoor(int f, int x, int z, boolean px, Door.Hinge hinge, boolean jammable) {
        Pos p = at(f, x, 0, z);
        for (Bisected.Half half : Bisected.Half.values()) {
            Door d = (Door) Bukkit.createBlockData(doorM);
            d.setFacing(px ? BlockFace.EAST : BlockFace.SOUTH);
            d.setHinge(hinge);
            d.setHalf(half);
            d.setOpen(false);
            map.set(half == Bisected.Half.BOTTOM ? p : p.add(0, 1, 0), d);
        }
        if (jammable) b.doors.add(p);
    }

    // ---- 標記：保全室、電源、櫃子、監獄、鬼房、出生點、地下通道 ----

    private final List<int[]> hatchTiles = new ArrayList<>();

    private void markers(List<int[]> securityTiles, Region jail, Region ghost) {
        for (int[] q : securityTiles) {
            Pos p = at(q[0], q[1], 0, q[2]);
            map.set(p, IrregularGenerator.TERMINAL);
            occupied.add(key(q[0], q[1], q[2]));
            reserve(q[0], q[1], q[2], 2);
            b.securityTerminals.add(p);
            b.securityRooms.add(at(q[0], regionAt(q[0], q[1], q[2]).center()[0], 0, regionAt(q[0], q[1], q[2]).center()[1]));
        }
        Map<Character, List<int[]>> hatch = new HashMap<>();
        for (int f = 0; f < F; f++) {
            for (int z = 0; z < H; z++) {
                for (int x = 0; x < W; x++) {
                    char c = at(f, x, z);
                    if (c == 'E') {
                        powerSpot(f, x, z);
                        reserve(f, x, z, 1);
                    } else if (c == 'C') {
                        cabinet(f, x, z);
                    } else if (c == 'S' || c == 'K') {
                        reserve(f, x, z, 1);
                    } else if (Character.isDigit(c)) {
                        if (f != 0) warnings.add((f + 1) + "F 地下通道出入口（" + x + "," + z + "）只能在 1F，跳過");
                        else hatch.computeIfAbsent(c, k -> new ArrayList<>()).add(new int[]{x, z});
                    }
                }
            }
        }
        // 監獄：J 是籠子裡面，外面一圈蓋鐵欄杆
        int jf = jail.f;
        long sx = 0, sz = 0;
        int nJ = 0;
        Set<Long> jailSet = new HashSet<>();
        for (int[] t : jail.tiles) if (at(jf, t[0], t[1]) == 'J') {
            jailSet.add(key(jf, t[0], t[1]));
            sx += t[0];
            sz += t[1];
            nJ++;
        }
        for (int[] t : jail.tiles) {
            if (at(jf, t[0], t[1]) != 'J') continue;
            reserve(jf, t[0], t[1], 2);
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++) {
                    int nx = t[0] + dx, nz = t[1] + dz;
                    if (jailSet.contains(key(jf, nx, nz)) || !regionChar(at(jf, nx, nz))) continue;
                    for (int dy = 0; dy <= 2; dy++) map.set(at(jf, nx, dy, nz), Material.IRON_BARS);
                    occupied.add(key(jf, nx, nz));
                }
        }
        int cx = Math.round((float) sx / nJ), cz = Math.round((float) sz / nJ);
        b.jailCages.put(jf, at(jf, cx, 0, cz));
        // 鬼房
        int[] gc = ghost.center();
        b.ghostRoomCenter = at(ghost.f, gc[0], 0, gc[1]);
        b.ghostRoom = new Building.Cell(ghost.f, 0, 0, RoomType.GHOST_ROOM);
        // 地下通道
        int pairs = 0;
        for (Map.Entry<Character, List<int[]>> e : hatch.entrySet()) {
            List<int[]> v = e.getValue();
            if (v.size() != 2) {
                warnings.add("地下通道 " + e.getKey() + " 要剛好 2 個出入口（現在 " + v.size() + " 個），跳過");
                continue;
            }
            hatchTiles.add(new int[]{v.get(0)[0], v.get(0)[1], v.get(1)[0], v.get(1)[1]});
            pairs++;
        }
        b.tunnels = pairs;
    }

    /** 電源開關候選：旁邊（眼睛高度）要有不透光的牆給拉桿貼；樹籬、欄杆的話補一個配電箱。 */
    private void powerSpot(int f, int x, int z) {
        int[] wall = null;
        for (int[] d : DIRS) {
            char c = at(f, x + d[0], z + d[1]);
            if (c == '#') {
                wall = d;
                break;
            }
            if (wallChar(c) && wall == null) wall = d;
        }
        if (wall == null) {
            warnings.add((f + 1) + "F 電源候選 E（" + x + "," + z + "）旁邊沒有牆，跳過");
            return;
        }
        if (at(f, x + wall[0], z + wall[1]) != '#') {
            for (int dy = 0; dy <= 1; dy++) map.set(at(f, x + wall[0], dy, z + wall[1]), Material.IRON_BLOCK); // 配電箱
        }
        b.powerCandidates.add(at(f, x, 0, z));
    }

    /** 可推倒的櫃子：連到 2 格內的開口（障礙物門口）。 */
    private void cabinet(int f, int x, int z) {
        int best = -1;
        double bd = Double.MAX_VALUE;
        for (int i = 0; i < b.barricadeSpots.size(); i++) {
            for (Pos p : b.barricadeSpots.get(i).markers()) {
                if (p.y() != L.floorY(f) + 1) continue;
                int dx = Math.abs(p.x() - L.ox - x), dz = Math.abs(p.z() - L.oz - z);
                if (Math.max(dx, dz) > 2) continue;
                double d = Math.hypot(dx, dz);
                if (d < bd) {
                    bd = d;
                    best = i;
                }
            }
        }
        if (best < 0) {
            warnings.add((f + 1) + "F 櫃子 C（" + x + "," + z + "）2 格內沒有開口 O，不放");
            return;
        }
        Map<Pos, BlockData> blocks = new HashMap<>();
        for (int dy = 0; dy <= 1; dy++) {
            Pos p = at(f, x, dy, z);
            map.set(p, IrregularGenerator.CABINET);
            blocks.put(p, bd(IrregularGenerator.CABINET));
        }
        occupied.add(key(f, x, z));
        reserve(f, x, z, 1);
        b.toppleSpots.add(new Building.ToppleSpot(blocks, best));
    }

    private void furniture() {
        for (int f = 0; f < F; f++) {
            for (int z = 0; z < H; z++) {
                for (int x = 0; x < W; x++) {
                    char c = at(f, x, z);
                    if (!furn(c)) continue;
                    FixedMap.Furniture fu = fm.furniture.get(c);
                    if (fu == null) {
                        warnings.add("家具 " + c + " 沒有定義，用木板代替");
                        fu = new FixedMap.Furniture(List.of(Material.SPRUCE_PLANKS), false);
                    }
                    for (int i = 0; i < fu.stack().size() && i < fh - 1; i++) map.set(at(f, x, i, z), solidData(fu.stack().get(i)));
                    if (fu.light() && fu.stack().size() < fh - 1) {
                        Pos lp = at(f, x, fu.stack().size(), z);
                        if (map.passable(lp)) {
                            map.set(lp, light());
                            b.lights.add(lp);
                        }
                    }
                    occupied.add(key(f, x, z));
                }
            }
        }
    }

    // ---- 地下通道 ----

    private void basement() {
        int fy = L.floorY(0);
        for (int z = 0; z < H; z++)
            for (int x = 0; x < W; x++) {
                if (at(0, x, z) == ' ') continue;
                for (int y = fy - 4; y <= fy - 1; y++) map.set(L.rel(x, y, z), y == fy - 4 ? Material.DEEPSLATE_TILES : Material.STONE);
            }
        Set<Long> protect = new HashSet<>();
        List<int[]> hatches = new ArrayList<>(); // {x, z, backDx, backDz}
        for (int[] pr : hatchTiles) {
            int ax = pr[0], az = pr[1], bx = pr[2], bz = pr[3];
            int[] da = towards(ax, az, bx, bz), db = towards(bx, bz, ax, az);
            // 梯子背靠遠離另一端的那面
            hatches.add(new int[]{ax, az, -da[0], -da[1], bx, bz});
            hatches.add(new int[]{bx, bz, -db[0], -db[1], ax, az});
        }
        for (int[] h : hatches) {
            protect.add(key(0, h[0], h[1]));
            protect.add(key(0, h[0] + h[2], h[1] + h[3]));
        }
        Set<Long> tunnel = new HashSet<>();
        for (int i = 0; i < hatches.size(); i += 2) {
            int[] a = hatches.get(i), c = hatches.get(i + 1);
            int[] sa = {a[0] - a[2], a[1] - a[3]}, sc = {c[0] - c[2], c[1] - c[3]};
            Set<Long> prot = new HashSet<>(protect);
            prot.remove(key(0, sa[0], sa[1]));
            prot.remove(key(0, sc[0], sc[1]));
            List<int[]> path = tunnelPath(sa, sc, prot);
            if (path == null) {
                warnings.add("地下通道（" + a[0] + "," + a[1] + "）→（" + c[0] + "," + c[1] + "）挖不通，跳過");
                b.tunnels--;
                continue;
            }
            for (int[] t : path) tunnel.add(key(0, t[0], t[1]));
            for (int[] h : new int[][]{a, c}) {
                int x = h[0], z = h[1];
                BlockFace facing = face(-h[2], -h[3]);
                org.bukkit.block.data.type.Ladder ladder = (org.bukkit.block.data.type.Ladder) Bukkit.createBlockData(Material.LADDER);
                ladder.setFacing(facing);
                for (int y = fy - 3; y <= fy - 1; y++) map.set(L.rel(x, y, z), ladder);
                map.set(L.rel(x + h[2], fy - 3, z + h[3]), Material.STONE);
                map.set(L.rel(x + h[2], fy - 2, z + h[3]), Material.STONE);
                org.bukkit.block.data.type.TrapDoor td = (org.bukkit.block.data.type.TrapDoor) Bukkit.createBlockData(Material.SPRUCE_TRAPDOOR);
                td.setFacing(facing);
                td.setHalf(Bisected.Half.TOP);
                td.setOpen(false);
                Pos top = L.rel(x, fy, z);
                map.set(top, td);
                b.hatches.add(new Building.Hatch(top, td, L.rel(x, fy - 1, z), ladder));
                reserve(0, x, z, 1);
            }
        }
        for (long k : tunnel) {
            int x = (int) (k % W), z = (int) (k / W % H);
            if (protect.contains(k)) continue;
            map.set(L.rel(x, fy - 3, z), Material.AIR);
            map.set(L.rel(x, fy - 2, z), Material.AIR);
            if (Math.floorMod(x * 7 + z * 13, 9) == 0) {
                Pos lp = L.rel(x, fy - 2, z);
                map.set(lp, light());
                b.lights.add(lp);
            }
        }
    }

    private static int[] towards(int ax, int az, int bx, int bz) {
        int dx = bx - ax, dz = bz - az;
        if (Math.abs(dx) >= Math.abs(dz)) return new int[]{Integer.signum(dx), 0};
        return new int[]{0, Integer.signum(dz)};
    }

    /** 地下通道路線：1F 範圍內、繞開梯子，用帶一點隨機成本的最短路（會彎但不會亂繞）。 */
    private List<int[]> tunnelPath(int[] a, int[] c, Set<Long> protect) {
        if (at(0, a[0], a[1]) == ' ' || at(0, c[0], c[1]) == ' ') return null;
        Map<Long, Double> dist = new HashMap<>();
        Map<Long, Long> prev = new HashMap<>();
        PriorityQueue<double[]> pq = new PriorityQueue<>(Comparator.comparingDouble(t -> t[0]));
        long start = key(0, a[0], a[1]), goal = key(0, c[0], c[1]);
        dist.put(start, 0.0);
        pq.add(new double[]{0, a[0], a[1]});
        double[] noise = new double[W * H];
        for (int i = 0; i < noise.length; i++) noise[i] = 1 + rnd.nextDouble() * 1.5;
        while (!pq.isEmpty()) {
            double[] t = pq.poll();
            int x = (int) t[1], z = (int) t[2];
            long k = key(0, x, z);
            if (t[0] > dist.getOrDefault(k, Double.MAX_VALUE)) continue;
            if (k == goal) break;
            for (int[] d : DIRS) {
                int nx = x + d[0], nz = z + d[1];
                if (nx < 1 || nz < 1 || nx >= W - 1 || nz >= H - 1 || at(0, nx, nz) == ' ') continue;
                long nk = key(0, nx, nz);
                if (protect.contains(nk)) continue;
                double nd = t[0] + noise[nz * W + nx];
                if (nd < dist.getOrDefault(nk, Double.MAX_VALUE)) {
                    dist.put(nk, nd);
                    prev.put(nk, k);
                    pq.add(new double[]{nd, nx, nz});
                }
            }
        }
        if (!dist.containsKey(goal)) return null;
        List<int[]> path = new ArrayList<>();
        for (Long k = goal; k != null; k = prev.get(k)) path.add(new int[]{(int) (k % W), (int) (k / W % H)});
        Collections.reverse(path);
        return path;
    }

    // ---- 天花板、燈 ----

    private void ceilings() {
        for (int f = 0; f < F; f++) {
            int up = L.floorY(f + 1);
            for (int z = 0; z < H; z++)
                for (int x = 0; x < W; x++) {
                    if (at(f, x, z) == ' ' || holes.contains(key(f, x, z))) continue;
                    if (f + 1 < F && at(f + 1, x, z) != ' ') continue; // 上一層自己的樓板
                    if (stairTiles.contains(key(f, x, z))) continue;
                    if (!indoorTile(f, x, z)) continue;
                    map.set(L.rel(x, up, z), ceilM);
                }
        }
    }

    private void lights() {
        for (Region r : regions) {
            int f = r.f;
            boolean any = false;
            int sp = r.indoor ? 6 : 7, off = 3;
            int dy = r.indoor ? fh - 2 : 3;
            for (int[] t : r.tiles) {
                if (Math.floorMod(t[0], sp) != off || Math.floorMod(t[1], sp) != off) continue;
                Pos lp = at(f, t[0], dy, t[1]);
                if (!map.passable(lp) || holes.contains(key(f, t[0], t[1]))) continue;
                map.set(lp, light());
                b.lights.add(lp);
                any = true;
            }
            if (!any && r.indoor) {
                int[] c = r.center();
                Pos lp = at(f, c[0], dy, c[1]);
                if (map.passable(lp)) {
                    map.set(lp, light());
                    b.lights.add(lp);
                }
            }
        }
    }

    // ---- 木桶（每局隨機） ----

    private boolean freeWalk(int f, int x, int z) {
        char c = at(f, x, z);
        return regionChar(c) && !furn(c) && !arrow(c) && c != '=' && !occupied.contains(key(f, x, z)) && !holes.contains(key(f, x, z));
    }

    private void barrels() {
        // 候選：靠牆（或家具）的空地，往內兩格也是空地（不會把 2 格寬的路堵成 1 格）
        Map<Integer, List<int[]>> cands = new HashMap<>();
        for (Region r : regions) {
            if (r.ghost) continue;
            for (int[] t : r.tiles) {
                int f = r.f, x = t[0], z = t[1];
                char c = at(f, x, z);
                if (c != '.' && c != ',' && c != ';') continue;
                if (!freeWalk(f, x, z) || reserved.contains(key(f, x, z))) continue;
                int[] wall = null;
                int walls = 0;
                for (int[] d : DIRS) {
                    char nc = at(f, x + d[0], z + d[1]);
                    if (wallChar(nc) || furn(nc)) {
                        wall = d;
                        walls++;
                    }
                }
                if (walls != 1) continue;
                if (!freeWalk(f, x - wall[0], z - wall[1]) || !freeWalk(f, x - 2 * wall[0], z - 2 * wall[1])) continue;
                // 兩側要能繞過（沿牆那兩格至少一格空）
                int sx = wall[1], sz = wall[0];
                if (!freeWalk(f, x + sx, z + sz) && !freeWalk(f, x - sx, z - sz)) continue;
                cands.computeIfAbsent(r.id, k -> new ArrayList<>()).add(t);
            }
        }
        int area = 0;
        for (Region r : regions) if (!r.ghost) area += r.tiles.size();
        int target = Math.max(s.minContainers(), (int) Math.round(area / (double) s.barrelArea()));
        List<Region> order = new ArrayList<>();
        for (Region r : regions) if (cands.containsKey(r.id)) order.add(r);
        // 每區依面積分配
        Map<Integer, Integer> quota = new HashMap<>();
        int placed = 0;
        for (Region r : order) {
            double want = r.tiles.size() / (double) s.barrelArea();
            int n = (int) want;
            if (rnd.nextDouble() < want - n) n++;
            if (n == 0 && r.tiles.size() >= 30 && rnd.nextInt(3) > 0) n = 1;
            quota.put(r.id, n);
        }
        Set<Long> barrels = new HashSet<>();
        for (int pass = 0; pass < 2 && placed < target; pass++) {
            Collections.shuffle(order, rnd);
            for (Region r : order) {
                List<int[]> list = new ArrayList<>(cands.get(r.id));
                Collections.shuffle(list, rnd);
                int n = pass == 0 ? quota.get(r.id) : 1;
                for (int[] t : list) {
                    if (n <= 0 || placed >= target && pass > 0) break;
                    int f = r.f, x = t[0], z = t[1];
                    if (!freeWalk(f, x, z) || nearBarrel(barrels, f, x, z, 3)) continue;
                    occupied.add(key(f, x, z));
                    if (!locallyConnected(f, x, z)) {
                        occupied.remove(key(f, x, z));
                        continue;
                    }
                    barrels.add(key(f, x, z));
                    Directional d = (Directional) Bukkit.createBlockData(Material.BARREL);
                    d.setFacing(BlockFace.UP);
                    Pos pos = at(f, x, 0, z);
                    map.set(pos, d);
                    b.containers.add(pos);
                    placed++;
                    n--;
                }
            }
        }
        if (placed < s.minContainers()) warnings.add("木桶只放得下 " + placed + " 個（至少要 " + s.minContainers() + " 個）");
    }

    private boolean nearBarrel(Set<Long> barrels, int f, int x, int z, int r) {
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) if (barrels.contains(key(f, x + dx, z + dz))) return true;
        return false;
    }

    /** 放了東西之後，旁邊的空地彼此還連得通（在 6 格範圍內繞得過去）。 */
    private boolean locallyConnected(int f, int x, int z) {
        List<int[]> nb = new ArrayList<>();
        for (int[] d : DIRS) if (freeWalk(f, x + d[0], z + d[1])) nb.add(new int[]{x + d[0], z + d[1]});
        if (nb.size() <= 1) return nb.size() == 1;
        Set<Long> seen = new HashSet<>();
        ArrayDeque<int[]> q = new ArrayDeque<>();
        q.add(nb.get(0));
        seen.add(key(f, nb.get(0)[0], nb.get(0)[1]));
        while (!q.isEmpty()) {
            int[] t = q.poll();
            for (int[] d : DIRS) {
                int nx = t[0] + d[0], nz = t[1] + d[1];
                if (Math.abs(nx - x) > 6 || Math.abs(nz - z) > 6 || !freeWalk(f, nx, nz) && !walkableOpening(f, nx, nz)) continue;
                if (seen.add(key(f, nx, nz))) q.add(new int[]{nx, nz});
            }
        }
        for (int[] t : nb) if (!seen.contains(key(f, t[0], t[1]))) return false;
        return true;
    }

    private boolean walkableOpening(int f, int x, int z) {
        char c = at(f, x, z);
        return c == 'D' || c == 'W' || c == 'O';
    }

    // =====================================================================
    // 檢查、記錄
    // =====================================================================

    private Set<Pos> walk = new HashSet<>();

    private void validate() {
        Set<Pos> ghostDoors = new HashSet<>(b.ghostDoors);
        Pos start = L.crossing(0);
        if (!standable(start, Set.of())) {
            errors.add("出生點站不住：" + start);
            return;
        }
        walk = flood(start, Set.of());
        Set<Pos> withHoles = new HashSet<>();
        for (Building.CrawlHole h : b.crawlHoles) withHoles.add(h.slab());
        withHoles.addAll(ghostDoors);
        Set<Pos> all = flood(start, withHoles);
        // 走不到的地面
        int lost = 0;
        List<String> examples = new ArrayList<>();
        for (Region r : regions) {
            if (r.ghost) continue;
            for (int[] t : r.tiles) {
                if (at(r.f, t[0], t[1]) == 'J') continue; // 監獄籠子裡面本來就走不到
                Pos p = at(r.f, t[0], 0, t[1]);
                if (!standable(p, Set.of()) || all.contains(p)) continue;
                lost++;
                if (examples.size() < 4) examples.add((r.f + 1) + "F(" + t[0] + "," + t[1] + ")");
            }
        }
        if (lost > 0) warnings.add("有 " + lost + " 格地面走不到（例如 " + String.join("、", examples) + "）");
        for (Building.Gate g : b.gates) {
            boolean ok = false;
            for (int[] d : DIRS) if (walk.contains(g.reader().add(d[0], 0, d[1]))) ok = true;
            if (!ok) errors.add((g.floor() + 1) + "F 大門讀卡機走不到：" + g.reader());
        }
        for (Pos t : b.securityTerminals) {
            boolean ok = false;
            for (int[] d : DIRS) if (walk.contains(t.add(d[0], 0, d[1]))) ok = true;
            if (!ok) errors.add("保全室終端機走不到：" + t);
        }
        b.powerCandidates.removeIf(q -> !walk.contains(q));
        if (b.powerCandidates.isEmpty()) errors.add("沒有走得到的電源開關位置（E）");
        int usable = 0;
        for (Pos c : b.containers) for (int[] d : DIRS) if (walk.contains(c.add(d[0], 0, d[1]))) {
            usable++;
            break;
        }
        if (usable < s.minContainers()) warnings.add("走得到的木桶只有 " + usable + " 個（建議 ≥ " + s.minContainers() + "）");
        for (int f = 0; f < F - 1; f++) {
            for (int[] st : stairs) {
                if (st[0] != f || st[5] != 0) continue;
                Pos bottom = at(f, st[1] - st[3], 0, st[2] - st[4]);
                Pos top = at(f + 1, st[1] + st[3] * fh, 0, st[2] + st[4] * fh);
                b.stairLinks.add(new Pos[]{bottom, top});
                if (!walk.contains(bottom)) warnings.add((f + 1) + "F 樓梯底部走不到：" + bottom);
                if (!walk.contains(top)) warnings.add((f + 2) + "F 樓梯頂端走不到：" + top);
            }
        }
    }

    private boolean standable(Pos p, Set<Pos> extra) {
        return map.solid(p.add(0, -1, 0)) && (map.passable(p) || extra.contains(p)) && (map.passable(p.add(0, 1, 0)) || extra.contains(p.add(0, 1, 0)));
    }

    private boolean isStair(Pos p) {
        return map.type(p) == Material.STONE_BRICK_STAIRS;
    }

    /** 從 start 走得到的格子：同高度相鄰，或爬上／走下樓梯（差 1 格、比較高那格腳下是樓梯）。 */
    private Set<Pos> flood(Pos start, Set<Pos> extra) {
        Set<Pos> seen = new HashSet<>();
        ArrayDeque<Pos> q = new ArrayDeque<>();
        q.add(start);
        seen.add(start);
        int minY = L.floorY(0) - 3, maxY = L.topY() + 1;
        while (!q.isEmpty()) {
            Pos p = q.poll();
            for (int[] d : DIRS) {
                for (int dy = -1; dy <= 1; dy++) {
                    Pos n = p.add(d[0], dy, d[1]);
                    if (n.y() < minY || n.y() > maxY || n.x() < L.ox - 1 || n.z() < L.oz - 1 || n.x() > L.ox + W || n.z() > L.oz + H) continue;
                    if (seen.contains(n) || !standable(n, extra)) continue;
                    if (dy == 1 && (!isStair(n.add(0, -1, 0)) || !map.passable(p.add(0, 2, 0)))) continue;
                    if (dy == -1 && !isStair(p.add(0, -1, 0))) continue;
                    seen.add(n);
                    q.add(n);
                }
            }
        }
        // 地下通道：梯子兩端
        for (Building.Hatch h : b.hatches) {
            Pos top = h.trapdoor().add(0, 1, 0), bottom = h.trapdoor().add(0, -3, 0);
            if (seen.contains(top) && !seen.contains(bottom) && standable(bottom, Set.of(bottom, bottom.add(0, 1, 0)))) {
                Set<Pos> more = flood(bottom, extra);
                seen.addAll(more);
            }
        }
        return seen;
    }

    private void record() {
        // 房間：室內區域一間；戶外大區域切成 10×10 的小塊（巡邏點）
        String[] types = {"OPEN_OFFICE", "CUBICLES", "STORAGE", "LOUNGE", "ARCHIVE", "COPY_ROOM", "BOARDROOM", "SERVER"};
        for (Region r : regions) {
            int[] c = r.center();
            if (r.ghost) {
                b.rooms.add(new Building.Room(r.f, RoomType.GHOST_ROOM, at(r.f, c[0], 0, c[1]), r.tiles.size(), false));
                continue;
            }
            if (r.jail) {
                b.rooms.add(new Building.Room(r.f, RoomType.JAIL, b.jailCages.get(r.f), r.tiles.size(), false));
                if (r.indoor || r.tiles.size() < 120) continue;
            }
            if (r.indoor) {
                RoomType t = r.security ? RoomType.SECURITY : RoomType.valueOf(types[r.id % types.length]);
                b.rooms.add(new Building.Room(r.f, t, at(r.f, c[0], 0, c[1]), r.tiles.size(), false));
                continue;
            }
            Map<Long, List<int[]>> chunks = new HashMap<>();
            for (int[] t : r.tiles) {
                if (!freeWalk(r.f, t[0], t[1])) continue;
                chunks.computeIfAbsent(((long) (t[0] / 10) << 16) | (t[1] / 10), k -> new ArrayList<>()).add(t);
            }
            for (List<int[]> ch : chunks.values()) {
                if (ch.size() < 15) continue;
                long sx = 0, sz = 0;
                for (int[] t : ch) {
                    sx += t[0];
                    sz += t[1];
                }
                int mx = Math.round((float) sx / ch.size()), mz = Math.round((float) sz / ch.size());
                int[] best = ch.get(0);
                long bd = Long.MAX_VALUE;
                for (int[] t : ch) {
                    long d = (long) (t[0] - mx) * (t[0] - mx) + (long) (t[1] - mz) * (t[1] - mz);
                    if (d < bd) {
                        bd = d;
                        best = t;
                    }
                }
                b.rooms.add(new Building.Room(r.f, RoomType.YARD, at(r.f, best[0], 0, best[1]), ch.size(), false));
            }
        }
        // 出生點：S 和旁邊走得到的格子
        for (int f = 0; f < F; f++) {
            Set<Pos> spawns = new HashSet<>();
            for (int z = 0; z < H; z++)
                for (int x = 0; x < W; x++) {
                    if (at(f, x, z) != 'S') continue;
                    for (int dx = -1; dx <= 1; dx++)
                        for (int dz = -1; dz <= 1; dz++) {
                            Pos p = at(f, x + dx, 0, z + dz);
                            if (walk.contains(p)) spawns.add(p);
                        }
                }
            if (!spawns.isEmpty()) b.spawnTiles.put(f, spawns);
            Set<Pos> wf = new HashSet<>();
            int y = L.floorY(f) + 1;
            for (Pos p : walk) if (p.y() == y) wf.add(p);
            b.walkTiles.put(f, wf);
        }
        // 俯視圖（/gh info）：4×4 一格
        for (int f = 0; f < F; f++) {
            List<String> rows = new ArrayList<>();
            for (int z = 0; z < H; z += 4) {
                StringBuilder sb = new StringBuilder();
                for (int x = 0; x < W; x += 4) {
                    int in = 0, out = 0, wall = 0, none = 0;
                    boolean ghost = false, jail = false, sec = false;
                    for (int dz = 0; dz < 4; dz++)
                        for (int dx = 0; dx < 4; dx++) {
                            char c = at(f, x + dx, z + dz);
                            Region r = regionAt(f, x + dx, z + dz);
                            if (c == ' ') none++;
                            else if (r == null) wall++;
                            else {
                                if (r.ghost) ghost = true;
                                if (c == 'J') jail = true;
                                if (r.security) sec = true;
                                if (indoorTile(f, x + dx, z + dz)) in++;
                                else out++;
                            }
                        }
                    if (ghost) sb.append('鬼');
                    else if (jail) sb.append('監');
                    else if (sec) sb.append('保');
                    else if (none >= 8) sb.append('　');
                    else if (wall >= 10) sb.append('＃');
                    else sb.append(in >= out ? '室' : '外');
                }
                rows.add(sb.toString());
            }
            b.plans.put(f, rows);
        }
    }
}
