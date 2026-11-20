package com.ghosthunt.building;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Levelled;
import org.bukkit.block.data.type.Door;
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
import java.util.Random;
import java.util.Set;

/**
 * 不規則大樓：固定的凹凸外型（config 的 map.shape），裡面的房間每局隨機。
 * <ol>
 *   <li>外型切成 cell-size 大小的小格，小格隨機拼成 1～4 格的房間，部分大房間再用不對齊的牆切開。</li>
 *   <li>每層一條彎來彎去的短走廊（沿著小格的邊走），其他地方房間直接用門互通，轉角多。</li>
 *   <li>房間之間：先用門連成一棵樹（每間都走得到），再多開幾扇門做出繞圈的路線；另外有鑽洞通道和牆中暗格。</li>
 *   <li>樓梯間 2 間，3 層疊在同一個位置。家具依房間種類和大小用規則擺。</li>
 * </ol>
 * 產生出來的 {@link Building} 欄位跟舊的九宮格大樓一樣，遊戲系統不用改。
 */
final class IrregularGenerator {

    private static final int OUT = -1, WALL = 0, CORR = 1;
    private static final int MAX_ATTEMPTS = 30;
    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    // ---- 資料 ----

    /** 一間房間（或一段走廊、一個暗格）。 */
    static final class Room {
        final int id;
        RoomType type;
        boolean corridor, hideout;
        final List<int[]> tiles = new ArrayList<>();
        /** 門口內側（從門走進來的第一格），擺家具後檢查用。 */
        final List<Integer> entries = new ArrayList<>();
        int jailX = -1, jailZ = -1;

        Room(int id) {
            this.id = id;
        }

        int area() {
            return tiles.size();
        }

        int[] bbox() {
            int x0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z0 = Integer.MAX_VALUE, z1 = Integer.MIN_VALUE;
            for (int[] t : tiles) {
                x0 = Math.min(x0, t[0]);
                x1 = Math.max(x1, t[0]);
                z0 = Math.min(z0, t[1]);
                z1 = Math.max(z1, t[1]);
            }
            return new int[]{x0, x1, z0, z1};
        }

        int[] center() {
            long sx = 0, sz = 0;
            for (int[] t : tiles) {
                sx += t[0];
                sz += t[1];
            }
            int cx = (int) Math.round((double) sx / tiles.size()), cz = (int) Math.round((double) sz / tiles.size());
            // 重心可能不在房間裡（L 形），挑最近的一格
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

    /**
     * 牆上的開口（門、鑽洞）。xn = 兩邊的房間在 x-1／x+1（牆沿 z 方向），否則在 z-1／z+1。
     * neg 是座標小的那一邊，pos 是大的那一邊。
     */
    record Opening(int neg, int pos, List<int[]> tiles, boolean xn) {}

    /** 大門候選：外牆上 3 格寬，dx/dz 是往外的方向。 */
    record GateSpot(int floor, int room, int x, int z, int dx, int dz, boolean alongZ) {}

    /** 一層的平面。 */
    final class Plan {
        final int f;
        final int[][] g = new int[H][W];
        final Map<Integer, Room> rooms = new LinkedHashMap<>();
        /** 不放家具（門口內側、樓梯、大門前面……）。 */
        final Set<Integer> reserved = new HashSet<>();
        /** 不放家具、門也不能開在前面（樓梯、監獄籠子、暗格）。 */
        final Set<Integer> blocked = new HashSet<>();
        final List<Opening> doors = new ArrayList<>();
        final List<Opening> crawls = new ArrayList<>();
        final Set<Integer> usedWall = new HashSet<>();
        final Map<Integer, BlockData[]> furn = new HashMap<>();
        final Map<Integer, BlockData> tops = new HashMap<>();
        final Set<Integer> barrels = new HashSet<>();
        final Set<Integer> cageInside = new HashSet<>();
        final List<int[]> power = new ArrayList<>();
        final List<GateSpot> gateSpots = new ArrayList<>();

        Plan(int f) {
            this.f = f;
        }

        int at(int x, int z) {
            if (x < 0 || z < 0 || x >= W || z >= H) return OUT;
            return g[z][x];
        }

        Room room(int x, int z) {
            return rooms.get(at(x, z));
        }

        boolean occupied(int x, int z) {
            int k = key(x, z);
            return furn.containsKey(k) || barrels.contains(k);
        }

        boolean touchesOut(int x, int z) {
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++)
                    if (at(x + dx, z + dz) == OUT) return true;
            return false;
        }
    }

    // ---- 產生 ----

    private final BuildingSettings s;
    private final boolean[][] cells; // [cz][cx]
    private final int CW, CH, U, W, H, size;
    private Random rnd;
    private Layout L;
    private Building b;
    private int[][] stairCells;
    private final Map<Material, BlockData> cache = new HashMap<>();

    private IrregularGenerator(BuildingSettings s) {
        this.s = s;
        List<String> shape = s.shape();
        this.CH = shape.size();
        int cw = 0;
        for (String row : shape) cw = Math.max(cw, row.replace(" ", "").length());
        this.CW = cw;
        this.cells = new boolean[CH][CW];
        for (int r = 0; r < CH; r++) {
            String row = shape.get(r).replace(" ", "");
            for (int c = 0; c < row.length(); c++) cells[r][c] = row.charAt(c) == '#';
        }
        this.U = s.cellSize();
        this.W = CW * U + 1;
        this.H = CH * U + 1;
        this.size = Math.max(W, H);
    }

    static BuildingGenerator.Result generate(BuildingSettings s, long seed) {
        IrregularGenerator gen = new IrregularGenerator(s);
        List<String> problems = gen.checkSettings();
        if (!problems.isEmpty()) return new BuildingGenerator.Result(null, null, 0, problems, List.of());
        List<String> last = List.of();
        List<String> rejected = new ArrayList<>();
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            long sd = seed + attempt;
            List<String> errors = new ArrayList<>();
            BlockMap map = null;
            try {
                map = gen.attempt(sd, errors);
            } catch (RuntimeException e) {
                errors.add("產生時出錯：" + e);
            }
            if (errors.isEmpty() && map != null) return new BuildingGenerator.Result(gen.b, map, attempt + 1, List.of(), rejected);
            last = errors.isEmpty() ? List.of("不明原因") : errors;
            rejected.add(last.get(0));
        }
        return new BuildingGenerator.Result(null, null, MAX_ATTEMPTS, last, rejected);
    }

    private List<String> checkSettings() {
        List<String> e = new ArrayList<>();
        if (s.floorHeight() < 5) e.add("層高至少要 5 格（4 格高的牆、3 格高的門）");
        if (U < s.floorHeight() + 4) e.add("map.cell-size 太小放不下樓梯：至少要層高 + 4（" + (s.floorHeight() + 4) + "）");
        int n = 0;
        for (boolean[] r : cells) for (boolean c : r) if (c) n++;
        if (n < 12) e.add("map.shape 的大樓範圍太小（至少 12 格）");
        if (s.floors() < 1) e.add("至少要 1 層");
        return e;
    }

    private BlockMap attempt(long seed, List<String> errors) {
        rnd = new Random(seed);
        cache.clear();
        if (!pickStairCells()) {
            errors.add("外型裡找不到兩個夠遠的樓梯間位置");
            return null;
        }
        int sx = stairCells[0][0] * U + 7, sz = stairCells[0][1] * U + 6;
        L = Layout.irregular(size, s.floors(), s.floorHeight(), s.originX(), s.originY(), s.originZ(), sx, sz);
        b = new Building(seed, L);

        // 整棟的分配：鬼房在哪層、電力室（1～2 間，不同層）、保全室（2 間，不同層）
        int floors = L.floors;
        int ghostFloor = rnd.nextInt(floors);
        List<Integer> fl = new ArrayList<>();
        for (int f = 0; f < floors; f++) fl.add(f);
        Collections.shuffle(fl, rnd);
        Set<Integer> electricalFloors = new HashSet<>(fl.subList(0, Math.min(floors, 1 + (rnd.nextInt(3) == 0 ? 1 : 0))));
        Collections.shuffle(fl, rnd);
        List<Integer> securityFloors = new ArrayList<>(fl.subList(0, Math.min(floors, 2)));
        if (securityFloors.size() < 2) securityFloors.add(securityFloors.get(0));

        List<Plan> plans = new ArrayList<>();
        for (int f = 0; f < floors; f++) {
            Plan p = new Plan(f);
            String err = layoutFloor(p, f == ghostFloor, electricalFloors.contains(f),
                    (int) securityFloors.stream().filter(x -> x == p.f).count());
            if (err != null) {
                errors.add((f + 1) + "F：" + err);
                return null;
            }
            plans.add(p);
        }
        String gateErr = pickGates(plans);
        if (gateErr != null) {
            errors.add(gateErr);
            return null;
        }
        for (Plan p : plans) furnishFloor(p);

        BlockMap map = new BlockMap();
        compose(map, plans);
        map.connectPanes();
        for (Pos p : b.ghostDoors) b.ghostDoorBlocks.put(p, map.get(p));
        for (Building.Gate g : b.gates) for (Pos p : g.opening()) b.gateBlocks.put(p, map.get(p));
        errors.addAll(validate(map, plans));
        if (!errors.isEmpty()) return null;
        record(plans);
        return map;
    }

    // ---- 樓梯間的位置（3 層同一個位置） ----

    private boolean pickStairCells() {
        List<int[]> all = new ArrayList<>();
        for (int cz = 0; cz < CH; cz++) for (int cx = 0; cx < CW; cx++) if (cells[cz][cx]) all.add(new int[]{cx, cz});
        List<int[][]> pairs = new ArrayList<>();
        double best = 0;
        for (int i = 0; i < all.size(); i++)
            for (int j = i + 1; j < all.size(); j++)
                best = Math.max(best, Math.hypot(all.get(i)[0] - all.get(j)[0], all.get(i)[1] - all.get(j)[1]));
        for (int i = 0; i < all.size(); i++) {
            for (int j = i + 1; j < all.size(); j++) {
                double d = Math.hypot(all.get(i)[0] - all.get(j)[0], all.get(i)[1] - all.get(j)[1]);
                if (d >= best * 0.6) pairs.add(new int[][]{all.get(i), all.get(j)});
            }
        }
        if (pairs.isEmpty()) return false;
        stairCells = pairs.get(rnd.nextInt(pairs.size()));
        return true;
    }

    private boolean isStairCell(int cx, int cz) {
        for (int[] c : stairCells) if (c[0] == cx && c[1] == cz) return true;
        return false;
    }

    /** 方塊 (x, z) 在不在大樓範圍內（含外牆）。 */
    private boolean inside(int x, int z) {
        for (int cx : new int[]{x / U, (x - 1) / U}) {
            for (int cz : new int[]{z / U, (z - 1) / U}) {
                if (cx < 0 || cz < 0 || cx >= CW || cz >= CH || !cells[cz][cx]) continue;
                if (x >= cx * U && x <= cx * U + U && z >= cz * U && z <= cz * U + U) return true;
            }
        }
        return false;
    }

    private boolean cell(int cx, int cz) {
        return cx >= 0 && cz >= 0 && cx < CW && cz < CH && cells[cz][cx];
    }

    private int key(int x, int z) {
        return z * W + x;
    }

    // =====================================================================
    // 1. 平面：房間、走廊、門、鑽洞、暗格
    // =====================================================================

    private String layoutFloor(Plan p, boolean ghost, boolean electrical, int security) {
        int[][] g = p.g;
        for (int z = 0; z < H; z++) for (int x = 0; x < W; x++) g[z][x] = inside(x, z) ? WALL : OUT;

        // 小格拼成房間（樓梯間固定一格一間）
        Map<Long, Integer> assign = new HashMap<>();
        int rid = 2;
        for (int[] sc : stairCells) assign.put(ck(sc[0], sc[1]), rid++);
        List<int[]> order = new ArrayList<>();
        for (int cz = 0; cz < CH; cz++) for (int cx = 0; cx < CW; cx++) if (cells[cz][cx]) order.add(new int[]{cx, cz});
        Collections.shuffle(order, rnd);
        int[] targets = {1, 2, 2, 2, 3, 3, 4};
        for (int[] c : order) {
            if (assign.containsKey(ck(c[0], c[1]))) continue;
            int target = targets[rnd.nextInt(targets.length)];
            List<int[]> region = new ArrayList<>();
            region.add(c);
            assign.put(ck(c[0], c[1]), rid);
            while (region.size() < target) {
                List<int[]> cand = new ArrayList<>();
                for (int[] r : region)
                    for (int[] d : DIRS) {
                        int nx = r[0] + d[0], nz = r[1] + d[1];
                        if (cell(nx, nz) && !assign.containsKey(ck(nx, nz))) cand.add(new int[]{nx, nz});
                    }
                if (cand.isEmpty()) break;
                int[] n = cand.get(rnd.nextInt(cand.size()));
                assign.put(ck(n[0], n[1]), rid);
                region.add(n);
            }
            rid++;
        }
        for (Map.Entry<Long, Integer> e : assign.entrySet()) {
            int cx = (int) (e.getKey() >> 16), cz = (int) (e.getKey() & 0xffff), r = e.getValue();
            for (int z = cz * U + 1; z < cz * U + U; z++) for (int x = cx * U + 1; x < cx * U + U; x++) g[z][x] = r;
        }
        // 同一間的小格之間的牆打掉
        for (Map.Entry<Long, Integer> e : assign.entrySet()) {
            int cx = (int) (e.getKey() >> 16), cz = (int) (e.getKey() & 0xffff), r = e.getValue();
            Integer east = assign.get(ck(cx + 1, cz)), south = assign.get(ck(cx, cz + 1)), se = assign.get(ck(cx + 1, cz + 1));
            if (east != null && east == r) for (int z = cz * U + 1; z < cz * U + U; z++) g[z][cx * U + U] = r;
            if (south != null && south == r) for (int x = cx * U + 1; x < cx * U + U; x++) g[cz * U + U][x] = r;
            if (east != null && east == r && south != null && south == r && se != null && se == r) g[cz * U + U][cx * U + U] = r;
        }

        // 大房間再用不對齊的牆切開
        Set<Integer> stairIds = new HashSet<>();
        for (int[] sc : stairCells) stairIds.add(assign.get(ck(sc[0], sc[1])));
        Map<Integer, List<int[]>> byId = new HashMap<>();
        for (int z = 0; z < H; z++) for (int x = 0; x < W; x++) if (g[z][x] >= 2) byId.computeIfAbsent(g[z][x], k -> new ArrayList<>()).add(new int[]{x, z});
        for (Map.Entry<Integer, List<int[]>> e : byId.entrySet()) {
            List<int[]> pts = e.getValue();
            if (stairIds.contains(e.getKey()) || pts.size() < 200 || rnd.nextDouble() < 0.4) continue;
            int x0 = Integer.MAX_VALUE, x1 = 0, z0 = Integer.MAX_VALUE, z1 = 0;
            for (int[] t : pts) {
                x0 = Math.min(x0, t[0]);
                x1 = Math.max(x1, t[0]);
                z0 = Math.min(z0, t[1]);
                z1 = Math.max(z1, t[1]);
            }
            boolean cutX = x1 - x0 >= z1 - z0;
            int lo = (cutX ? x0 : z0) + 5, hi = (cutX ? x1 : z1) - 5;
            if (hi < lo) continue;
            int cut = lo + rnd.nextInt(hi - lo + 1);
            for (int[] t : pts) {
                int v = cutX ? t[0] : t[1];
                if (v == cut) g[t[1]][t[0]] = WALL;
                else if (v > cut) g[t[1]][t[0]] = rid;
            }
            rid++;
        }

        // 走廊：沿著小格的邊隨機走幾段（會轉彎），寬 3
        corridor(p);

        // 重新編號（切開、走廊切過之後可能變成好幾塊），太小的碎塊變成牆
        relabel(p, stairIds);

        // 房間種類
        String err = assignTypes(p, ghost, electrical, security);
        if (err != null) return err;

        // 樓梯、監獄籠子的保留區
        for (Room r : p.rooms.values()) {
            if (r.type == RoomType.STAIRWELL) {
                int[] c = stairCellOf(r);
                for (int lx = 1; lx <= s.floorHeight() + 2; lx++)
                    for (int lz = 1; lz <= 3; lz++) p.blocked.add(key(c[0] * U + lx, c[1] * U + lz));
            }
            if (r.type == RoomType.JAIL) {
                for (int dx = 0; dx < 9; dx++)
                    for (int dz = 0; dz < 9; dz++) {
                        int k = key(r.jailX + dx, r.jailZ + dz);
                        if (dx >= 2 && dx <= 7 && dz >= 2 && dz <= 7) p.blocked.add(k);
                        else p.reserved.add(k);
                        if (dx >= 3 && dx <= 6 && dz >= 3 && dz <= 6) p.cageInside.add(k);
                    }
            }
        }

        hideouts(p);
        if (!doors(p)) return "房間沒辦法全部用門連起來";
        crawlTunnels(p);
        gateSpots(p);
        if (p.gateSpots.isEmpty()) return "外牆上找不到可以放大門的地方";
        return null;
    }

    private static long ck(int cx, int cz) {
        return ((long) cx << 16) | cz;
    }

    private int[] stairCellOf(Room r) {
        int[] t = r.tiles.get(0);
        for (int[] c : stairCells) if (t[0] / U == c[0] && t[1] / U == c[1]) return c;
        return stairCells[0];
    }

    private void corridor(Plan p) {
        int[][] g = p.g;
        // 格點 (i, j) = 方塊 (i*U, j*U)；一段 = 相鄰格點之間的邊，兩側的小格都要在大樓裡、不能是樓梯間
        List<int[]> starts = new ArrayList<>();
        for (int j = 1; j < CH; j++) for (int i = 1; i < CW; i++) starts.add(new int[]{i, j});
        Collections.shuffle(starts, rnd);
        int steps = 4 + rnd.nextInt(4);
        for (int[] st : starts) {
            List<int[]> path = new ArrayList<>();
            path.add(st);
            Set<Long> seen = new HashSet<>();
            seen.add(ck(st[0], st[1]));
            int[] dir = DIRS[rnd.nextInt(4)];
            for (int k = 0; k < steps * 3 && path.size() <= steps; k++) {
                int[] cur = path.get(path.size() - 1);
                if (rnd.nextDouble() < 0.45 || k > 0 && !segmentOk(cur, dir)) dir = DIRS[rnd.nextInt(4)];
                int[] nxt = {cur[0] + dir[0], cur[1] + dir[1]};
                if (!segmentOk(cur, dir) || seen.contains(ck(nxt[0], nxt[1]))) continue;
                path.add(nxt);
                seen.add(ck(nxt[0], nxt[1]));
            }
            if (path.size() < 4) continue;
            for (int i = 0; i + 1 < path.size(); i++) {
                int[] a = path.get(i), c = path.get(i + 1);
                carve(p, Math.min(a[0], c[0]) * U - 1, Math.min(a[1], c[1]) * U - 1, Math.max(a[0], c[0]) * U + 1, Math.max(a[1], c[1]) * U + 1);
            }
            break;
        }
        // 走廊兩側補牆
        for (int z = 0; z < H; z++)
            for (int x = 0; x < W; x++) {
                if (g[z][x] != CORR) continue;
                for (int dx = -1; dx <= 1; dx++)
                    for (int dz = -1; dz <= 1; dz++)
                        if (p.at(x + dx, z + dz) >= 2) g[z + dz][x + dx] = WALL;
            }
    }

    /** 從格點 cur 往 dir 走一格，這條邊能不能當走廊。 */
    private boolean segmentOk(int[] cur, int[] dir) {
        int i = cur[0], j = cur[1];
        if (dir[0] != 0) {
            int ci = dir[0] > 0 ? i : i - 1; // 這條邊沿 x 方向，兩側是 (ci, j-1)、(ci, j)
            return cell(ci, j - 1) && cell(ci, j) && !isStairCell(ci, j - 1) && !isStairCell(ci, j);
        }
        int cj = dir[1] > 0 ? j : j - 1;
        return cell(i - 1, cj) && cell(i, cj) && !isStairCell(i - 1, cj) && !isStairCell(i, cj);
    }

    private void carve(Plan p, int x0, int z0, int x1, int z1) {
        for (int z = z0; z <= z1; z++)
            for (int x = x0; x <= x1; x++) {
                if (p.at(x, z) == OUT || p.touchesOut(x, z)) continue;
                if (isStairCell(Math.floorDiv(x, U), Math.floorDiv(z, U)) || isStairCell(Math.floorDiv(x - 1, U), Math.floorDiv(z, U))
                        || isStairCell(Math.floorDiv(x, U), Math.floorDiv(z - 1, U))) continue;
                p.g[z][x] = CORR;
            }
    }

    private void relabel(Plan p, Set<Integer> stairIds) {
        int[][] g = p.g;
        int[][] out = new int[H][W];
        for (int z = 0; z < H; z++) for (int x = 0; x < W; x++) out[z][x] = g[z][x] < 1 ? g[z][x] : WALL;
        boolean[][] seen = new boolean[H][W];
        int nid = 2;
        for (int z = 0; z < H; z++) {
            for (int x = 0; x < W; x++) {
                int v = g[z][x];
                if (v < 1 || seen[z][x]) continue;
                List<int[]> pts = new ArrayList<>();
                ArrayDeque<int[]> q = new ArrayDeque<>();
                q.add(new int[]{x, z});
                seen[z][x] = true;
                while (!q.isEmpty()) {
                    int[] t = q.poll();
                    pts.add(t);
                    for (int[] d : DIRS) {
                        int nx = t[0] + d[0], nz = t[1] + d[1];
                        if (p.at(nx, nz) == v && !seen[nz][nx]) {
                            seen[nz][nx] = true;
                            q.add(new int[]{nx, nz});
                        }
                    }
                }
                if (pts.size() < 16) continue; // 碎塊變成牆
                Room r = new Room(nid);
                r.corridor = v == CORR;
                if (stairIds.contains(v)) r.type = RoomType.STAIRWELL;
                for (int[] t : pts) {
                    out[t[1]][t[0]] = nid;
                    r.tiles.add(t);
                }
                p.rooms.put(nid, r);
                nid++;
            }
        }
        for (int z = 0; z < H; z++) System.arraycopy(out[z], 0, g[z], 0, W);
    }

    /** 房間裡有沒有 n×n 的空地，有的話回傳左上角。 */
    private int[] square(Plan p, Room r, int n) {
        List<int[]> hits = new ArrayList<>();
        for (int[] t : r.tiles) {
            boolean ok = true;
            for (int dx = 0; dx < n && ok; dx++)
                for (int dz = 0; dz < n && ok; dz++)
                    if (p.at(t[0] + dx, t[1] + dz) != r.id) ok = false;
            if (ok) hits.add(t);
        }
        return hits.isEmpty() ? null : hits.get(rnd.nextInt(hits.size()));
    }

    private String assignTypes(Plan p, boolean ghost, boolean electrical, int security) {
        List<Room> free = new ArrayList<>();
        for (Room r : p.rooms.values()) if (!r.corridor && r.type == null) free.add(r);
        Collections.shuffle(free, rnd);
        // 監獄：要放得下 9×9（6×6 的籠子＋外圍走道）
        Room jail = null;
        for (Room r : free) {
            int[] sq = square(p, r, 9);
            if (sq != null) {
                jail = r;
                r.jailX = sq[0];
                r.jailZ = sq[1];
                break;
            }
        }
        if (jail == null) return "沒有放得下監獄的房間";
        jail.type = RoomType.JAIL;
        free.remove(jail);
        if (ghost) {
            Room gr = null;
            for (Room r : free) if (square(p, r, 6) != null && r.area() <= 200) {
                gr = r;
                break;
            }
            if (gr == null) for (Room r : free) if (square(p, r, 6) != null) {
                gr = r;
                break;
            }
            if (gr == null) return "沒有放得下鬼房的房間";
            gr.type = RoomType.GHOST_ROOM;
            free.remove(gr);
        }
        if (electrical && !takeRoom(free, RoomType.ELECTRICAL, 40)) return "沒有放得下電力室的房間";
        for (int i = 0; i < security; i++) if (!takeRoom(free, RoomType.SECURITY, 40)) return "沒有放得下保全室的房間";
        if (s.maxDeadEndsPerFloor() >= 1) takeRoom(free, RoomType.TOILET, 30);
        List<RoomType> theme = new ArrayList<>(themeOf(p.f));
        List<RoomType> others = new ArrayList<>(List.of(RoomType.OPEN_OFFICE, RoomType.CUBICLES, RoomType.BOARDROOM,
                RoomType.ARCHIVE, RoomType.STORAGE, RoomType.LOUNGE, RoomType.SERVER, RoomType.COPY_ROOM));
        for (Room r : free) {
            List<RoomType> src = rnd.nextInt(3) < 2 ? theme : others;
            RoomType t = src.get(rnd.nextInt(src.size()));
            int[] bb = r.bbox();
            int shortSide = Math.min(bb[1] - bb[0], bb[3] - bb[2]) + 1;
            if (t == RoomType.BOARDROOM && shortSide < 5) t = RoomType.STORAGE;
            r.type = t;
        }
        return null;
    }

    private boolean takeRoom(List<Room> free, RoomType t, int minArea) {
        for (Room r : free) {
            if (r.area() >= minArea) {
                r.type = t;
                free.remove(r);
                return true;
            }
        }
        return false;
    }

    private static List<RoomType> themeOf(int floor) {
        return switch (floor % 3) {
            case 0 -> List.of(RoomType.LOUNGE, RoomType.COPY_ROOM, RoomType.STORAGE, RoomType.OPEN_OFFICE);
            case 1 -> List.of(RoomType.OPEN_OFFICE, RoomType.CUBICLES, RoomType.BOARDROOM, RoomType.ARCHIVE);
            default -> List.of(RoomType.SERVER, RoomType.STORAGE, RoomType.ARCHIVE, RoomType.COPY_ROOM);
        };
    }

    private static boolean normalType(RoomType t) {
        return t != null && t.normal() || t == RoomType.SECURITY;
    }

    // ---- 牆中暗格 ----

    private void hideouts(Plan p) {
        List<Room> cands = new ArrayList<>();
        for (Room r : p.rooms.values()) if (!r.corridor && normalType(r.type) && r.area() >= 60) cands.add(r);
        Collections.shuffle(cands, rnd);
        int made = 0;
        int nid = p.rooms.keySet().stream().mapToInt(Integer::intValue).max().orElse(1) + 1;
        for (Room r : cands) {
            if (made >= s.hideoutsPerFloor()) break;
            List<int[][]> options = new ArrayList<>(); // {box..., walls..., entrance}
            for (int[] t : r.tiles) {
                for (int[] wh : new int[][]{{2, 3}, {3, 2}}) {
                    for (int sx : new int[]{1, -1}) {
                        for (int sz : new int[]{1, -1}) {
                            int x = t[0], z = t[1], w = wh[0], h = wh[1];
                            // 角落：box 在 (x, z) 起往 (sx, sz) 方向延伸，背後兩面是原本的牆
                            if (p.at(x - sx, z) >= 2 || p.at(x, z - sz) >= 2) continue;
                            if (p.at(x - sx, z) == OUT || p.at(x, z - sz) == OUT) continue;
                            List<int[]> box = new ArrayList<>(), walls = new ArrayList<>();
                            for (int i = 0; i < w; i++) for (int j = 0; j < h; j++) box.add(new int[]{x + sx * i, z + sz * j});
                            for (int j = 0; j <= h; j++) walls.add(new int[]{x + sx * w, z + sz * j});
                            for (int i = 0; i < w; i++) walls.add(new int[]{x + sx * i, z + sz * h});
                            boolean ok = true;
                            for (int[] q : box) if (p.at(q[0], q[1]) != r.id || p.blocked.contains(key(q[0], q[1])) || p.reserved.contains(key(q[0], q[1]))) ok = false;
                            for (int[] q : walls) if (p.at(q[0], q[1]) != r.id || p.blocked.contains(key(q[0], q[1])) || p.reserved.contains(key(q[0], q[1]))) ok = false;
                            if (!ok) continue;
                            // 入口：長邊那面新牆的中間，外面兩格要是房間
                            int[] ent = w == 2 ? new int[]{x + sx * w, z + sz} : new int[]{x + sx, z + sz * h};
                            int ox = w == 2 ? sx : 0, oz = w == 2 ? 0 : sz;
                            if (p.at(ent[0] + ox, ent[1] + oz) != r.id || p.at(ent[0] + 2 * ox, ent[1] + 2 * oz) != r.id) continue;
                            List<int[]> all = new ArrayList<>(box);
                            all.add(null);
                            all.addAll(walls);
                            all.add(null);
                            all.add(ent);
                            options.add(all.toArray(new int[0][]));
                        }
                    }
                }
            }
            if (options.isEmpty()) continue;
            int[][] opt = options.get(rnd.nextInt(options.size()));
            Room h = new Room(nid++);
            h.hideout = true;
            h.type = r.type;
            int part = 0;
            int[] ent = null;
            for (int[] q : opt) {
                if (q == null) {
                    part++;
                    continue;
                }
                if (part == 0) {
                    p.g[q[1]][q[0]] = h.id;
                    h.tiles.add(q);
                    p.blocked.add(key(q[0], q[1]));
                } else if (part == 1) {
                    p.g[q[1]][q[0]] = WALL;
                } else ent = q;
            }
            r.tiles.removeIf(q -> p.at(q[0], q[1]) != r.id);
            p.rooms.put(h.id, h);
            // 入口的鑽洞：暗格在哪一邊
            boolean xn = p.at(ent[0] - 1, ent[1]) >= 2 && p.at(ent[0] + 1, ent[1]) >= 2;
            int neg = xn ? p.at(ent[0] - 1, ent[1]) : p.at(ent[0], ent[1] - 1);
            int pos = xn ? p.at(ent[0] + 1, ent[1]) : p.at(ent[0], ent[1] + 1);
            Opening o = new Opening(neg, pos, List.of(ent), xn);
            p.crawls.add(o);
            markOpening(p, o, 2);
            made++;
        }
    }

    // ---- 門 ----

    /** 所有可以開 width 格寬開口的位置，依兩邊的房間分組。 */
    private Map<Long, List<Opening>> spots(Plan p, int width) {
        Map<Long, List<Opening>> out = new HashMap<>();
        for (int z = 1; z < H - 1; z++) {
            for (int x = 1; x < W - 1; x++) {
                if (p.at(x, z) != WALL || p.touchesOut(x, z)) continue;
                for (boolean xn : new boolean[]{true, false}) {
                    int ax = xn ? -1 : 0, az = xn ? 0 : -1; // 往 neg 那邊
                    int tx = xn ? 0 : 1, tz = xn ? 1 : 0;   // 沿著牆
                    int a = p.at(x + ax, z + az), c = p.at(x - ax, z - az);
                    if (a < 2 || c < 2 || a == c) continue;
                    Room ra = p.rooms.get(a), rc = p.rooms.get(c);
                    if (ra == null || rc == null) continue;
                    boolean ok = true;
                    List<int[]> tiles = new ArrayList<>();
                    for (int i = 0; i < width && ok; i++) {
                        int wx = x + tx * i, wz = z + tz * i;
                        if (p.at(wx, wz) != WALL || p.touchesOut(wx, wz)) ok = false;
                        // 兩邊各兩格深都要是那間房間，而且不在保留區
                        for (int d = 1; d <= 2 && ok; d++) {
                            if (p.at(wx + ax * d, wz + az * d) != a || p.at(wx - ax * d, wz - az * d) != c) ok = false;
                            if (p.blocked.contains(key(wx + ax * d, wz + az * d)) || p.blocked.contains(key(wx - ax * d, wz - az * d))) ok = false;
                        }
                        tiles.add(new int[]{wx, wz});
                    }
                    if (!ok) continue;
                    // 兩端要是牆（門框）
                    if (p.at(x - tx, z - tz) != WALL || p.at(x + tx * width, z + tz * width) != WALL) continue;
                    long k = pairKey(a, c);
                    out.computeIfAbsent(k, kk -> new ArrayList<>()).add(new Opening(a, c, tiles, xn));
                }
            }
        }
        return out;
    }

    private static long pairKey(int a, int b) {
        return ((long) Math.min(a, b) << 32) | Math.max(a, b);
    }

    private boolean doors(Plan p) {
        Map<Long, List<Opening>> spots = spots(p, 2);
        List<Long> edges = new ArrayList<>(spots.keySet());
        Collections.shuffle(edges, rnd);
        // 房間直接互通優先，走廊排後面（走廊不要太多）
        edges.sort(Comparator.comparingInt(k -> (p.rooms.get((int) (k >> 32)).corridor || p.rooms.get((int) (long) k).corridor) ? 1 : 0));
        Map<Integer, Integer> parent = new HashMap<>();
        Map<Integer, Integer> degree = new HashMap<>();
        Set<Long> connected = new HashSet<>();
        // 1. 不是「只能有一個門」的房間先連成一棵樹
        for (long e : edges) {
            Room a = p.rooms.get((int) (e >> 32)), c = p.rooms.get((int) e);
            if (leaf(a) || leaf(c) || a.hideout || c.hideout) continue;
            int ra = find(parent, a.id), rc = find(parent, c.id);
            if (ra == rc) continue;
            if (addDoor(p, spots.get(e))) {
                parent.put(ra, rc);
                connected.add(e);
                degree.merge(a.id, 1, Integer::sum);
                degree.merge(c.id, 1, Integer::sum);
            }
        }
        // 2. 廁所、鬼房：只接一扇門
        for (Room r : p.rooms.values()) {
            if (!leaf(r) || r.hideout) continue;
            List<Long> mine = new ArrayList<>();
            for (long e : edges) {
                if ((int) (e >> 32) != r.id && (int) e != r.id) continue;
                Room o = p.rooms.get((int) (e >> 32) == r.id ? (int) e : (int) (e >> 32));
                if (!leaf(o) && !o.hideout) mine.add(e);
            }
            Collections.shuffle(mine, rnd);
            for (long e : mine) {
                if (addDoor(p, spots.get(e))) {
                    int o = (int) (e >> 32) == r.id ? (int) e : (int) (e >> 32);
                    parent.put(find(parent, r.id), find(parent, o));
                    connected.add(e);
                    break;
                }
            }
        }
        // 全部連起來了嗎
        Integer root = null;
        for (Room r : p.rooms.values()) {
            if (r.hideout) continue;
            int rr = find(parent, r.id);
            if (root == null) root = rr;
            else if (root != rr) return false;
        }
        // 3. 只有一扇門的房間（死路）多半再開一扇，另外隨機多開幾扇做出繞圈的路線
        for (long e : edges) {
            if (connected.contains(e)) continue;
            Room a = p.rooms.get((int) (e >> 32)), c = p.rooms.get((int) e);
            if (leaf(a) || leaf(c) || a.hideout || c.hideout) continue;
            boolean deadEnd = degree.getOrDefault(a.id, 0) <= 1 || degree.getOrDefault(c.id, 0) <= 1;
            if (rnd.nextDouble() < (deadEnd ? 0.75 : 0.12) && addDoor(p, spots.get(e))) {
                connected.add(e);
                degree.merge(a.id, 1, Integer::sum);
                degree.merge(c.id, 1, Integer::sum);
            }
        }
        return true;
    }

    private static boolean leaf(Room r) {
        return r.type == RoomType.TOILET || r.type == RoomType.GHOST_ROOM;
    }

    private static int find(Map<Integer, Integer> parent, int a) {
        while (parent.getOrDefault(a, a) != a) a = parent.get(a);
        return a;
    }

    /** 從候選裡挑一個不會跟已經開的門、洞擠在一起的位置開門。 */
    private boolean addDoor(Plan p, List<Opening> cands) {
        List<Opening> list = new ArrayList<>(cands);
        Collections.shuffle(list, rnd);
        for (Opening o : list) {
            if (!clearOf(p, o)) continue;
            p.doors.add(o);
            markOpening(p, o, 2);
            return true;
        }
        return false;
    }

    private boolean clearOf(Plan p, Opening o) {
        for (int[] t : o.tiles())
            for (int dx = -2; dx <= 2; dx++)
                for (int dz = -2; dz <= 2; dz++)
                    if (p.usedWall.contains(key(t[0] + dx, t[1] + dz))) return false;
        return true;
    }

    /** 開口占用的牆、兩邊的門口保留區（depth 格深），記錄門口內側。 */
    private void markOpening(Plan p, Opening o, int depth) {
        int ax = o.xn() ? -1 : 0, az = o.xn() ? 0 : -1;
        for (int[] t : o.tiles()) {
            p.usedWall.add(key(t[0], t[1]));
            for (int d = 1; d <= depth; d++) {
                p.reserved.add(key(t[0] + ax * d, t[1] + az * d));
                p.reserved.add(key(t[0] - ax * d, t[1] - az * d));
            }
            Room a = p.rooms.get(o.neg()), c = p.rooms.get(o.pos());
            if (a != null) a.entries.add(key(t[0] + ax, t[1] + az));
            if (c != null) c.entries.add(key(t[0] - ax, t[1] - az));
        }
    }

    // ---- 鑽洞通道 ----

    private void crawlTunnels(Plan p) {
        Set<Long> doorPairs = new HashSet<>();
        for (Opening o : p.doors) doorPairs.add(pairKey(o.neg(), o.pos()));
        Map<Long, List<Opening>> spots = spots(p, 1);
        List<Long> edges = new ArrayList<>(spots.keySet());
        Collections.shuffle(edges, rnd);
        int made = 0;
        for (long e : edges) {
            if (made >= s.crawlTunnelsPerFloor()) break;
            if (doorPairs.contains(e)) continue;
            Room a = p.rooms.get((int) (e >> 32)), c = p.rooms.get((int) e);
            if (a.hideout || c.hideout || a.type == RoomType.GHOST_ROOM || c.type == RoomType.GHOST_ROOM) continue;
            if (a.corridor && c.corridor) continue;
            List<Opening> list = new ArrayList<>(spots.get(e));
            Collections.shuffle(list, rnd);
            for (Opening o : list) {
                if (!clearOf(p, o)) continue;
                p.crawls.add(o);
                markOpening(p, o, 2);
                made++;
                break;
            }
        }
    }

    // ---- 大門 ----

    private void gateSpots(Plan p) {
        for (int z = 1; z < H - 1; z++) {
            for (int x = 1; x < W - 1; x++) {
                if (p.at(x, z) != WALL) continue;
                for (int[] d : DIRS) {
                    int dx = d[0], dz = d[1]; // 往外
                    if (p.at(x + dx, z + dz) != OUT) continue;
                    int r = p.at(x - dx, z - dz);
                    Room room = p.rooms.get(r);
                    if (room == null || room.hideout || room.type == RoomType.GHOST_ROOM || room.type == RoomType.JAIL
                            || room.type == RoomType.STAIRWELL) continue;
                    boolean alongZ = dx != 0;
                    int tx = alongZ ? 0 : 1, tz = alongZ ? 1 : 0;
                    boolean ok = p.at(x - tx, z - tz) == WALL && p.at(x + 3 * tx, z + 3 * tz) == WALL;
                    for (int i = 0; i < 3 && ok; i++) {
                        int wx = x + tx * i, wz = z + tz * i;
                        if (p.at(wx, wz) != WALL || p.at(wx + dx, wz + dz) != OUT) ok = false;
                        for (int dep = 1; dep <= 3 && ok; dep++) {
                            int ix = wx - dx * dep, iz = wz - dz * dep;
                            if (p.at(ix, iz) != r || p.blocked.contains(key(ix, iz)) || p.reserved.contains(key(ix, iz))) ok = false;
                        }
                    }
                    if (ok) p.gateSpots.add(new GateSpot(p.f, r, x, z, dx, dz, alongZ));
                }
            }
        }
    }

    private Building.Gate toGate(GateSpot gs) {
        int fy = L.floorY(gs.floor());
        int tx = gs.alongZ() ? 0 : 1, tz = gs.alongZ() ? 1 : 0;
        List<Pos> opening = new ArrayList<>();
        for (int i = 0; i < 3; i++)
            for (int y = fy + 1; y <= fy + 3; y++) opening.add(L.rel(gs.x() + tx * i, y, gs.z() + tz * i));
        Pos center = L.rel(gs.x() + tx, fy + 1, gs.z() + tz);
        Pos reader = L.rel(gs.x() - 2 * gs.dx(), fy + 1, gs.z() - 2 * gs.dz());
        Pos exit = L.rel(gs.x() + tx + 2 * gs.dx(), fy + 1, gs.z() + tz + 2 * gs.dz());
        return new Building.Gate(gs.floor(), center, opening, reader, exit);
    }

    private String pickGates(List<Plan> plans) {
        List<GateSpot> all = new ArrayList<>();
        for (Plan p : plans) all.addAll(p.gateSpots);
        Collections.shuffle(all, rnd);
        List<GateSpot> chosen = new ArrayList<>();
        outer:
        for (GateSpot g : all) {
            Building.Gate gg = toGate(g);
            for (GateSpot o : chosen) {
                Building.Gate og = toGate(o);
                if (o.floor() == g.floor()) continue outer;
                if (og.center().distance(gg.center()) < s.gateMinDistance()) continue outer;
                if (nearestStair(og) == nearestStair(gg)) continue outer;
            }
            chosen.add(g);
            if (chosen.size() == s.gateCount()) break;
        }
        if (chosen.size() < s.gateCount())
            return "找不到符合條件的大門位置（不同樓層、至少相隔 " + s.gateMinDistance() + " 格、最近的樓梯間不同）";
        for (GateSpot g : chosen) {
            Plan p = plans.get(g.floor());
            int tx = g.alongZ() ? 0 : 1, tz = g.alongZ() ? 1 : 0;
            for (int i = 0; i < 3; i++)
                for (int dep = 1; dep <= 3; dep++) p.reserved.add(key(g.x() + tx * i - g.dx() * dep, g.z() + tz * i - g.dz() * dep));
            b.gates.add(toGate(g));
        }
        for (GateSpot g : all) b.gateCandidates.add(toGate(g));
        return null;
    }

    private int nearestStair(Building.Gate g) {
        double best = Double.MAX_VALUE;
        int idx = 0;
        for (int i = 0; i < stairCells.length; i++) {
            Pos c = L.rel(stairCells[i][0] * U + U / 2, g.center().y(), stairCells[i][1] * U + U / 2);
            double d = c.distance(g.center());
            if (d < best) {
                best = d;
                idx = i;
            }
        }
        return idx;
    }

    // =====================================================================
    // 2. 家具
    // =====================================================================

    private BlockData bd(Material m) {
        return cache.computeIfAbsent(m, Bukkit::createBlockData);
    }

    private BlockData[] stack(Material... ms) {
        BlockData[] out = new BlockData[ms.length];
        for (int i = 0; i < ms.length; i++) out[i] = bd(ms[i]);
        return out;
    }

    private Material pillar() {
        return rnd.nextInt(4) == 0 ? Material.CRACKED_STONE_BRICKS : Material.STONE_BRICKS;
    }

    private Material partition(int f) {
        return switch (f % 3) {
            case 0 -> Material.WHITE_TERRACOTTA;
            case 1 -> Material.STRIPPED_BIRCH_WOOD;
            default -> Material.GRAY_CONCRETE;
        };
    }

    private void furnishFloor(Plan p) {
        for (Room r : p.rooms.values()) {
            if (r.hideout) continue;
            if (r.corridor) clutter(p, r);
            else furnish(p, r);
            repair(p, r);
            if (r.type == RoomType.ELECTRICAL) powerTiles(p, r);
        }
    }

    private boolean free(Plan p, Room r, int x, int z) {
        int k = key(x, z);
        return p.at(x, z) == r.id && !p.reserved.contains(k) && !p.blocked.contains(k) && !p.occupied(x, z);
    }

    /** 靠牆的那一圈（四周有一邊不是這間房間）。 */
    private boolean ring(Plan p, Room r, int x, int z) {
        if (p.at(x, z) != r.id) return false;
        for (int[] d : DIRS) if (p.at(x + d[0], z + d[1]) != r.id) return true;
        return false;
    }

    /** 靠牆那一圈、但不在角落以外的「往內那一格」也是空的（放東西後還走得到）。 */
    private int[] wallSide(Plan p, Room r, int x, int z) {
        for (int[] d : DIRS) if (p.at(x + d[0], z + d[1]) == WALL) return d;
        return null;
    }

    private void put(Plan p, int x, int z, BlockData... st) {
        p.furn.put(key(x, z), st);
    }

    private void furnish(Plan p, Room r) {
        int[] bb = r.bbox();
        int f = p.f;
        switch (r.type) {
            case STAIRWELL -> { }
            case JAIL -> {
                for (int dx = 2; dx <= 7; dx++)
                    for (int dz = 2; dz <= 7; dz++)
                        if (dx == 2 || dx == 7 || dz == 2 || dz == 7)
                            put(p, r.jailX + dx, r.jailZ + dz, stack(Material.IRON_BARS, Material.IRON_BARS, Material.IRON_BARS));
            }
            case GHOST_ROOM -> {
                for (int[] t : r.tiles)
                    if (!ring(p, r, t[0], t[1]) && rnd.nextInt(40) == 0 && free(p, r, t[0], t[1]))
                        put(p, t[0], t[1], stack(pillar(), pillar(), pillar()));
            }
            case ARCHIVE -> rows(p, r, bb, () -> stack(Material.BOOKSHELF,
                    rnd.nextInt(10) < 3 ? Material.CHISELED_BOOKSHELF : Material.BOOKSHELF, Material.BOOKSHELF));
            case STORAGE -> {
                Material[] goods = {Material.CHISELED_BOOKSHELF, Material.SPRUCE_PLANKS, Material.HAY_BLOCK, Material.STRIPPED_SPRUCE_WOOD};
                rows(p, r, bb, () -> stack(Material.STRIPPED_SPRUCE_WOOD, goods[rnd.nextInt(goods.length)], Material.SPRUCE_PLANKS));
            }
            case SERVER -> rows(p, r, bb, () -> stack(Material.POLISHED_BLACKSTONE,
                    rnd.nextInt(4) == 0 ? Material.REDSTONE_LAMP : Material.IRON_BLOCK, Material.POLISHED_BLACKSTONE));
            case OPEN_OFFICE -> desks(p, r, bb, false, false);
            case CUBICLES -> desks(p, r, bb, true, false);
            case SECURITY -> {
                desks(p, r, bb, false, true);
                for (int[] t : r.tiles)
                    if (ring(p, r, t[0], t[1]) && wallSide(p, r, t[0], t[1]) != null && rnd.nextInt(5) == 0 && free(p, r, t[0], t[1]))
                        put(p, t[0], t[1], stack(Material.IRON_BLOCK, Material.IRON_BLOCK)); // 置物櫃
            }
            case BOARDROOM -> boardroom(p, r, bb);
            case LOUNGE -> {
                for (int[] t : r.tiles) {
                    if (!ring(p, r, t[0], t[1]) || wallSide(p, r, t[0], t[1]) == null || !free(p, r, t[0], t[1])) continue;
                    int roll = rnd.nextInt(100);
                    if (roll < 22) put(p, t[0], t[1], stack(Material.GRAY_WOOL));
                    else if (roll < 32) {
                        put(p, t[0], t[1], stack(Material.POLISHED_DIORITE));
                        if (rnd.nextInt(3) == 0) {
                            Material[] app = {Material.SMOKER, Material.BREWING_STAND, Material.FLOWER_POT};
                            p.tops.put(key(t[0], t[1]), bd(app[rnd.nextInt(app.length)]));
                        }
                    }
                }
                tables(p, r, bb, Material.STRIPPED_SPRUCE_WOOD);
            }
            case COPY_ROOM -> {
                for (int[] t : r.tiles) {
                    if (!ring(p, r, t[0], t[1]) || wallSide(p, r, t[0], t[1]) == null || !free(p, r, t[0], t[1])) continue;
                    int roll = rnd.nextInt(100);
                    if (roll < 18) put(p, t[0], t[1], stack(Material.DROPPER));
                    else if (roll < 28) put(p, t[0], t[1], stack(Material.CAULDRON));
                }
                tables(p, r, bb, Material.DARK_OAK_PLANKS);
            }
            case TOILET -> toilet(p, r);
            case ELECTRICAL -> {
                for (int[] t : r.tiles) {
                    if (!ring(p, r, t[0], t[1]) || wallSide(p, r, t[0], t[1]) == null || !free(p, r, t[0], t[1])) continue;
                    if (rnd.nextInt(100) < 35) put(p, t[0], t[1], stack(Material.BLAST_FURNACE, Material.IRON_BLOCK));
                }
            }
            default -> { }
        }
        // 木桶：一般房間依面積放（大約每 140 格一個，小房間一半機率有一個），整棟跟舊版差不多（約 70 個）
        if (normalType(r.type)) {
            int n = (int) Math.round(r.area() / 140.0);
            if (n == 0 && rnd.nextBoolean()) n = 1;
            List<int[]> cand = new ArrayList<>();
            for (int[] t : r.tiles) {
                if (!ring(p, r, t[0], t[1]) || !free(p, r, t[0], t[1])) continue;
                int[] d = wallSide(p, r, t[0], t[1]);
                if (d == null) continue;
                if (free(p, r, t[0] - d[0], t[1] - d[1]) || p.reserved.contains(key(t[0] - d[0], t[1] - d[1]))) cand.add(t);
            }
            Collections.shuffle(cand, rnd);
            for (int i = 0; i < n && i < cand.size(); i++) p.barrels.add(key(cand.get(i)[0], cand.get(i)[1]));
        }
    }

    /** 一排一排的櫃子（檔案室、儲藏室、機房）：沿長邊，每排隔 2 格走道，每 4～6 格斷開一格。 */
    private void rows(Plan p, Room r, int[] bb, java.util.function.Supplier<BlockData[]> what) {
        boolean alongX = bb[1] - bb[0] >= bb[3] - bb[2];
        int lo = alongX ? bb[2] : bb[0], hi = alongX ? bb[3] : bb[1];
        int a0 = alongX ? bb[0] : bb[2], a1 = alongX ? bb[1] : bb[3];
        for (int line = lo + 2; line <= hi - 2; line += 3) {
            int run = 0, limit = 4 + rnd.nextInt(3);
            for (int a = a0 + 2; a <= a1 - 2; a++) {
                int x = alongX ? a : line, z = alongX ? line : a;
                boolean ok = free(p, r, x, z) && !ring(p, r, x, z);
                int px = alongX ? 0 : 1, pz = alongX ? 1 : 0;
                if (ok && (p.at(x + px, z + pz) != r.id || p.at(x - px, z - pz) != r.id)) ok = false;
                if (!ok || run >= limit) {
                    run = 0;
                    limit = 4 + rnd.nextInt(3);
                    continue;
                }
                put(p, x, z, what.get());
                run++;
            }
        }
    }

    /** 辦公桌：2 格的桌子，前後各兩張椅子；隔間辦公室旁邊加 2 格高的隔板；保全室桌上放螢幕。 */
    private void desks(Plan p, Room r, int[] bb, boolean cubicle, boolean security) {
        for (int x0 = bb[0] + 2; x0 + 1 <= bb[1] - 2; x0 += 4) {
            for (int z0 = bb[2] + 2; z0 <= bb[3] - 2; z0 += 4) {
                boolean ok = true;
                for (int dx = 0; dx <= (cubicle ? 2 : 1) && ok; dx++)
                    for (int dz = -1; dz <= 1 && ok; dz++)
                        if (!free(p, r, x0 + dx, z0 + dz) || ring(p, r, x0 + dx, z0 + dz)) ok = false;
                if (!ok) continue;
                Material desk = security ? Material.POLISHED_BLACKSTONE : Material.DARK_OAK_PLANKS;
                for (int dx = 0; dx <= 1; dx++) {
                    put(p, x0 + dx, z0, stack(desk));
                    if (security) p.tops.put(key(x0 + dx, z0), bd(Material.DAYLIGHT_DETECTOR));
                    else if (rnd.nextInt(10) < 5)
                        p.tops.put(key(x0 + dx, z0), bd(rnd.nextInt(5) == 0 ? Material.FLOWER_POT : Material.DAYLIGHT_DETECTOR));
                    if (rnd.nextInt(10) < 8) put(p, x0 + dx, z0 - 1, chair(BlockFace.NORTH));
                    if (rnd.nextInt(10) < 8) put(p, x0 + dx, z0 + 1, chair(BlockFace.SOUTH));
                }
                if (cubicle) for (int dz = -1; dz <= 1; dz++) put(p, x0 + 2, z0 + dz, stack(partition(p.f), partition(p.f)));
            }
        }
    }

    /** 椅子：back 是椅背那一邊（背對桌子）。 */
    private BlockData[] chair(BlockFace back) {
        Stairs st = (Stairs) Bukkit.createBlockData(Material.OAK_STAIRS);
        st.setFacing(back);
        st.setHalf(Bisected.Half.BOTTOM);
        return new BlockData[]{st};
    }

    private void boardroom(Plan p, Room r, int[] bb) {
        boolean alongX = bb[1] - bb[0] >= bb[3] - bb[2];
        int mid = alongX ? (bb[2] + bb[3]) / 2 : (bb[0] + bb[1]) / 2;
        int shortSide = alongX ? bb[3] - bb[2] + 1 : bb[1] - bb[0] + 1;
        int width = shortSide >= 8 ? 2 : 1;
        int a0 = (alongX ? bb[0] : bb[2]) + 2, a1 = (alongX ? bb[1] : bb[3]) - 2;
        for (int a = a0; a <= a1; a++) {
            boolean ok = true;
            for (int wv = -1; wv <= width && ok; wv++) {
                int x = alongX ? a : mid + wv, z = alongX ? mid + wv : a;
                if (!free(p, r, x, z) || ring(p, r, x, z)) ok = false;
            }
            if (!ok) continue;
            for (int wv = 0; wv < width; wv++) {
                int x = alongX ? a : mid + wv, z = alongX ? mid + wv : a;
                put(p, x, z, stack(Material.STRIPPED_SPRUCE_WOOD));
            }
            if (a == a0 || a == a1) continue;
            int x1 = alongX ? a : mid - 1, z1 = alongX ? mid - 1 : a;
            int x2 = alongX ? a : mid + width, z2 = alongX ? mid + width : a;
            put(p, x1, z1, chair(alongX ? BlockFace.NORTH : BlockFace.WEST));
            put(p, x2, z2, chair(alongX ? BlockFace.SOUTH : BlockFace.EAST));
        }
    }

    /** 小桌子配椅子（休息室、影印室）。 */
    private void tables(Plan p, Room r, int[] bb, Material table) {
        for (int x = bb[0] + 2; x <= bb[1] - 2; x += 4) {
            for (int z = bb[2] + 2; z <= bb[3] - 2; z += 4) {
                boolean ok = true;
                for (int dx = -1; dx <= 1 && ok; dx++)
                    for (int dz = -1; dz <= 1 && ok; dz++)
                        if (!free(p, r, x + dx, z + dz) || ring(p, r, x + dx, z + dz)) ok = false;
                if (!ok || rnd.nextInt(3) == 0) continue;
                put(p, x, z, stack(table));
                if (table == Material.DARK_OAK_PLANKS && rnd.nextBoolean()) p.tops.put(key(x, z), bd(Material.WHITE_CARPET));
                if (rnd.nextBoolean()) put(p, x - 1, z, chair(BlockFace.WEST));
                if (rnd.nextBoolean()) put(p, x + 1, z, chair(BlockFace.EAST));
            }
        }
    }

    /** 廁所：一面牆排馬桶，中間用 2 格高的隔板隔開；對面牆放洗手台。 */
    private void toilet(Plan p, Room r) {
        int[][] sides = {{0, -1}, {0, 1}, {-1, 0}, {1, 0}};
        int[] side = sides[rnd.nextInt(4)];
        int i = 0;
        List<int[]> along = new ArrayList<>();
        for (int[] t : r.tiles) if (p.at(t[0] + side[0], t[1] + side[1]) == WALL) along.add(t);
        along.sort(Comparator.comparingInt(t -> side[0] == 0 ? t[0] : t[1]));
        for (int[] t : along) {
            if (!free(p, r, t[0], t[1])) {
                i = 0;
                continue;
            }
            if (i % 2 == 0) {
                Stairs st = (Stairs) Bukkit.createBlockData(Material.QUARTZ_STAIRS);
                st.setFacing(face(side));
                st.setHalf(Bisected.Half.BOTTOM);
                put(p, t[0], t[1], st);
            } else {
                put(p, t[0], t[1], stack(partition(p.f), partition(p.f)));
            }
            i++;
        }
        for (int[] t : r.tiles) {
            if (p.at(t[0] - side[0], t[1] - side[1]) == WALL && free(p, r, t[0], t[1]) && rnd.nextBoolean())
                put(p, t[0], t[1], stack(Material.CAULDRON));
        }
    }

    private static BlockFace face(int[] d) {
        if (d[0] > 0) return BlockFace.EAST;
        if (d[0] < 0) return BlockFace.WEST;
        if (d[1] > 0) return BlockFace.SOUTH;
        return BlockFace.NORTH;
    }

    /** 走廊雜物：沿著牆隨機放單格的東西。 */
    private static final Material[] CLUTTER = {
            Material.COMPOSTER, Material.POTTED_FERN, Material.POTTED_BAMBOO, Material.SPRUCE_PLANKS,
            Material.CHISELED_BOOKSHELF, Material.CAULDRON, Material.LOOM, Material.SMITHING_TABLE};

    private void clutter(Plan p, Room r) {
        for (int[] t : r.tiles) {
            if (!ring(p, r, t[0], t[1]) || wallSide(p, r, t[0], t[1]) == null || !free(p, r, t[0], t[1])) continue;
            if (rnd.nextDouble() >= s.corridorClutter()) continue;
            boolean crowded = false;
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) if (p.occupied(t[0] + dx, t[1] + dz)) crowded = true;
            if (crowded) continue;
            put(p, t[0], t[1], stack(CLUTTER[rnd.nextInt(CLUTTER.length)]));
        }
    }

    /** 電源開關候選：電力室裡靠牆、走得到的空格（拉桿裝在旁邊的牆上）。 */
    private void powerTiles(Plan p, Room r) {
        List<int[]> cand = new ArrayList<>();
        for (int[] t : r.tiles) if (wallSide(p, r, t[0], t[1]) != null && free(p, r, t[0], t[1])) cand.add(t);
        Collections.shuffle(cand, rnd);
        for (int i = 0; i < 4 && i < cand.size(); i++) p.power.add(cand.get(i));
    }

    /**
     * 擺完家具後檢查：從門口走進來，房間裡每個空格都要走得到、每個木桶旁邊都要站得到人。
     * 走不到的話把擋住的家具拿掉，直到通為止。
     */
    private void repair(Plan p, Room r) {
        for (int guard = 0; guard < 300; guard++) {
            Set<Integer> reached = new HashSet<>();
            ArrayDeque<int[]> q = new ArrayDeque<>();
            for (int k : r.entries) {
                int x = k % W, z = k / W;
                if (p.at(x, z) == r.id && !p.occupied(x, z) && !p.cageInside.contains(k) && reached.add(k)) q.add(new int[]{x, z});
            }
            if (q.isEmpty()) {
                for (int[] t : r.tiles) {
                    int k = key(t[0], t[1]);
                    if (!p.occupied(t[0], t[1]) && !p.cageInside.contains(k)) {
                        reached.add(k);
                        q.add(t);
                        break;
                    }
                }
            }
            while (!q.isEmpty()) {
                int[] t = q.poll();
                for (int[] d : DIRS) {
                    int nx = t[0] + d[0], nz = t[1] + d[1], k = key(nx, nz);
                    if (p.at(nx, nz) != r.id || p.occupied(nx, nz) || p.cageInside.contains(k) || !reached.add(k)) continue;
                    q.add(new int[]{nx, nz});
                }
            }
            int[] stuck = null;
            for (int[] t : r.tiles) {
                int k = key(t[0], t[1]);
                if (!p.occupied(t[0], t[1]) && !p.cageInside.contains(k) && !reached.contains(k)) {
                    stuck = t;
                    break;
                }
            }
            if (stuck == null) {
                // 木桶旁邊要有走得到的格子
                p.barrels.removeIf(k -> {
                    int x = k % W, z = k / W;
                    if (p.at(x, z) != r.id) return false;
                    for (int[] d : DIRS) if (reached.contains(key(x + d[0], z + d[1]))) return false;
                    return true;
                });
                return;
            }
            // 拿掉離走不到的格子最近、而且旁邊是走得到的家具
            int[] best = null;
            int bd = Integer.MAX_VALUE;
            for (int[] t : r.tiles) {
                if (!p.occupied(t[0], t[1]) || p.blocked.contains(key(t[0], t[1]))) continue;
                boolean nearReached = false;
                for (int[] d : DIRS) if (reached.contains(key(t[0] + d[0], t[1] + d[1]))) nearReached = true;
                if (!nearReached) continue;
                int d = Math.abs(t[0] - stuck[0]) + Math.abs(t[1] - stuck[1]);
                if (d < bd) {
                    bd = d;
                    best = t;
                }
            }
            if (best == null) return; // 交給最後的檢查
            p.furn.remove(key(best[0], best[1]));
            p.tops.remove(key(best[0], best[1]));
            p.barrels.remove(key(best[0], best[1]));
        }
    }

    // =====================================================================
    // 3. 放方塊
    // =====================================================================

    private Pos at(int f, int x, int dy, int z) {
        return L.rel(x, L.floorY(f) + 1 + dy, z);
    }

    private BlockData light() {
        Levelled d = (Levelled) Bukkit.createBlockData(Material.LIGHT);
        d.setLevel(s.emergencyLight());
        return d;
    }

    private void compose(BlockMap map, List<Plan> plans) {
        int floors = L.floors, fh = L.floorHeight;
        map.fill(L.rel(-4, L.floorY(0), -4), L.rel(size + 3, L.topY() + 1, size + 3), Material.AIR);
        // 第一輪：每層的樓板、牆（樓梯會在上一層樓板挖洞，所以要等所有樓板都放好）
        for (Plan p : plans) {
            int f = p.f, fy = L.floorY(f);
            Material wallM = RoomBuilder.wallMaterial(f);
            for (int z = 0; z < H; z++) {
                for (int x = 0; x < W; x++) {
                    int v = p.at(x, z);
                    if (v == OUT) continue;
                    Room r = p.rooms.get(v);
                    map.set(L.rel(x, fy, z), r != null && r.type == RoomType.GHOST_ROOM && !r.hideout ? Material.SCULK
                            : RoomBuilder.floorMaterial(f, x, z));
                    if (v == WALL) {
                        Material m = p.touchesOut(x, z) ? Material.LIGHT_GRAY_CONCRETE : wallM;
                        for (int y = fy + 1; y < fy + fh; y++) map.set(L.rel(x, y, z), m);
                    }
                }
            }
        }
        // 第二輪：樓梯
        for (int f = 0; f < floors - 1; f++) for (int[] sc : stairCells) stairs(map, f, sc);
        // 第三輪：門、鑽洞、家具、燈
        for (Plan p : plans) {
            int f = p.f;
            Material wallM = RoomBuilder.wallMaterial(f);
            // 門
            for (Opening o : p.doors) door(map, p, o, wallM);
            // 鑽洞
            for (Opening o : p.crawls) {
                int[] t = o.tiles().get(0);
                Pos lower = at(f, t[0], 0, t[1]);
                org.bukkit.block.data.type.Slab slab = (org.bukkit.block.data.type.Slab) Bukkit.createBlockData(Material.STONE_BRICK_SLAB);
                slab.setType(org.bukkit.block.data.type.Slab.Type.TOP);
                map.set(lower, Material.AIR);
                map.set(lower.add(0, 1, 0), slab);
                b.crawlHoles.add(new Building.CrawlHole(lower, lower.add(0, 1, 0), slab));
            }
            // 家具
            for (Map.Entry<Integer, BlockData[]> e : p.furn.entrySet()) {
                int x = e.getKey() % W, z = e.getKey() / W;
                BlockData[] st = e.getValue();
                for (int i = 0; i < st.length; i++) map.set(at(f, x, i, z), st[i]);
                BlockData top = p.tops.get(e.getKey());
                if (top != null && st.length == 1) map.set(at(f, x, 1, z), top);
            }
            for (int k : p.barrels) {
                Directional d = (Directional) Bukkit.createBlockData(Material.BARREL);
                d.setFacing(BlockFace.UP);
                Pos pos = at(f, k % W, 0, k / W);
                map.set(pos, d);
                b.containers.add(pos);
            }
            for (int[] t : p.power) b.powerCandidates.add(at(f, t[0], 0, t[1]));
            // 燈、蜘蛛網
            for (Room r : p.rooms.values()) {
                if (r.hideout) continue;
                int top = fh - 2; // 天花板下面一格（相對走路高度）
                boolean any = false;
                int sp = r.corridor ? 5 : 6, off = r.corridor ? 0 : 3;
                for (int[] t : r.tiles) {
                    if (Math.floorMod(t[0], sp) != off || Math.floorMod(t[1], sp) != off) continue;
                    Pos lp = at(f, t[0], top, t[1]);
                    if (!map.passable(lp)) continue;
                    map.set(lp, light());
                    b.lights.add(lp);
                    any = true;
                }
                if (!any) {
                    int[] c = r.center();
                    Pos lp = at(f, c[0], top, c[1]);
                    if (map.passable(lp)) {
                        map.set(lp, light());
                        b.lights.add(lp);
                    }
                }
                if (!r.corridor) {
                    for (int[] t : r.tiles) {
                        int walls = 0;
                        for (int[] d : DIRS) if (p.at(t[0] + d[0], t[1] + d[1]) == WALL) walls++;
                        if (walls >= 2 && rnd.nextInt(3) == 0) {
                            Pos cp = at(f, t[0], top, t[1]);
                            if (map.type(cp) == Material.AIR) map.set(cp, Material.COBWEB);
                        }
                    }
                }
                if (r.type == RoomType.JAIL && !r.hideout) b.jailCages.put(f, at(f, r.jailX + 5, 0, r.jailZ + 5));
                if (r.type == RoomType.GHOST_ROOM && !r.hideout) {
                    int[] c = r.center();
                    b.ghostRoomCenter = at(f, c[0], 0, c[1]);
                    b.ghostRoom = new Building.Cell(f, 0, 0, RoomType.GHOST_ROOM);
                }
                if (r.type == RoomType.SECURITY && !r.hideout) {
                    int[] c = r.center();
                    b.securityRooms.add(at(f, c[0], 0, c[1]));
                }
            }
        }
        // 屋頂
        Plan top = plans.get(0);
        for (int z = 0; z < H; z++)
            for (int x = 0; x < W; x++)
                if (top.at(x, z) != OUT) map.set(L.rel(x, L.topY(), z), Material.SMOOTH_STONE);
        // 大門
        for (Building.Gate g : b.gates) {
            for (Pos p : g.opening()) map.set(p, Material.IRON_BARS);
            map.set(g.reader(), Material.LODESTONE);
            Pos e = g.exit();
            map.fill(e.add(-1, -1, -1), e.add(1, -1, 1), Material.POLISHED_BLACKSTONE);
        }
    }

    private void door(BlockMap map, Plan p, Opening o, Material wallM) {
        int f = p.f;
        Room a = p.rooms.get(o.neg()), c = p.rooms.get(o.pos());
        List<Pos> lower = new ArrayList<>();
        for (int[] t : o.tiles()) lower.add(at(f, t[0], 0, t[1]));
        boolean ghost = a.type == RoomType.GHOST_ROOM || c.type == RoomType.GHOST_ROOM;
        if (ghost) {
            for (Pos q : lower)
                for (int dy = 0; dy <= 2; dy++) {
                    map.set(q.add(0, dy, 0), Material.IRON_BARS);
                    b.ghostDoors.add(q.add(0, dy, 0));
                }
            return;
        }
        // 告示牌：門口旁邊，寫另一邊的房間名稱（走廊那邊不寫）
        sign(map, p, o, true, c);
        sign(map, p, o, false, a);
        boolean special = a.type == RoomType.STAIRWELL || c.type == RoomType.STAIRWELL || a.type == RoomType.JAIL || c.type == RoomType.JAIL;
        BlockFace facing = o.xn() ? BlockFace.EAST : BlockFace.SOUTH;
        if (!special && rnd.nextDouble() < s.doorChance()) {
            BlockFace left = facing == BlockFace.EAST ? BlockFace.NORTH : BlockFace.EAST;
            Pos p0 = lower.get(0), p1 = lower.get(1);
            int dot = (p1.x() - p0.x()) * left.getModX() + (p1.z() - p0.z()) * left.getModZ();
            placeDoor(map, f, p0, facing, dot > 0 ? Door.Hinge.RIGHT : Door.Hinge.LEFT);
            placeDoor(map, f, p1, facing, dot > 0 ? Door.Hinge.LEFT : Door.Hinge.RIGHT);
            for (Pos q : lower) map.set(q.add(0, 2, 0), wallM);
            return;
        }
        for (Pos q : lower) for (int dy = 0; dy <= 2; dy++) map.set(q.add(0, dy, 0), Material.AIR);
        if (!special) {
            List<Pos> blocks = new ArrayList<>();
            for (Pos q : lower) {
                blocks.add(q);
                blocks.add(q.add(0, 1, 0));
                map.set(q, RoomBuilder.BARRICADE_MARK);
            }
            b.barricadeSpots.add(new Building.BarricadeSpot(blocks, lower));
        }
    }

    /** 在開口 neg 那一邊（negSide = true）或 pos 那一邊的門框牆上掛告示牌，寫 other 的名稱。 */
    private void sign(BlockMap map, Plan p, Opening o, boolean negSide, Room other) {
        Room here = p.rooms.get(negSide ? o.neg() : o.pos());
        if (here == null || other == null || other.corridor || other.hideout) return;
        int ax = o.xn() ? -1 : 0, az = o.xn() ? 0 : -1;
        int sx = negSide ? ax : -ax, sz = negSide ? az : -az; // 往這一邊
        int tx = o.xn() ? 0 : 1, tz = o.xn() ? 1 : 0;
        int[] first = o.tiles().get(0);
        int[] frame = {first[0] - tx, first[1] - tz}; // 門框（開口前一格的牆）
        int fx = frame[0] + sx, fz = frame[1] + sz;
        if (p.at(fx, fz) != here.id || p.occupied(fx, fz) || p.blocked.contains(key(fx, fz))) return;
        Pos signPos = at(p.f, fx, 1, fz);
        if (!map.passable(signPos)) return;
        org.bukkit.block.data.type.WallSign ws = (org.bukkit.block.data.type.WallSign) Bukkit.createBlockData(RoomBuilder.signMaterial(p.f));
        ws.setFacing(face(new int[]{sx, sz}));
        map.set(signPos, ws);
        b.signs.put(signPos, new String[]{(p.f + 1) + "F", other.type.label});
    }

    private void placeDoor(BlockMap map, int f, Pos p, BlockFace facing, Door.Hinge hinge) {
        for (Bisected.Half half : Bisected.Half.values()) {
            Door d = (Door) Bukkit.createBlockData(RoomBuilder.doorMaterial(f));
            d.setFacing(facing);
            d.setHinge(hinge);
            d.setHalf(half);
            d.setOpen(false);
            map.set(half == Bisected.Half.BOTTOM ? p : p.add(0, 1, 0), d);
        }
        b.doors.add(p);
    }

    // ---- 樓梯（跟舊版同一套尺寸：平台 1 格、往 +x 爬 H 階、2 格寬） ----

    private Pos stairAt(int[] sc, int lx, int y, int lz) {
        return L.rel(sc[0] * U + lx, y, sc[1] * U + lz);
    }

    private void stairs(BlockMap map, int f, int[] sc) {
        int H5 = L.floorHeight, fy = L.floorY(f), upper = L.floorY(f + 1);
        for (int k = 1; k <= H5; k++) {
            for (int lane = 1; lane <= 2; lane++) {
                Stairs st = (Stairs) Bukkit.createBlockData(Material.STONE_BRICK_STAIRS);
                st.setHalf(Bisected.Half.BOTTOM);
                st.setFacing(BlockFace.EAST);
                map.set(stairAt(sc, k + 1, fy + k, lane), st);
            }
        }
        for (int k = 1; k <= H5 - 1; k++)
            for (int lane = 1; lane <= 2; lane++) map.set(stairAt(sc, k + 1, upper, lane), Material.AIR);
        for (int k = 1; k <= H5 - 1; k++) map.set(stairAt(sc, k + 1, upper + 1, 3), RoomBuilder.RAILING);
        if (f + 1 == L.floors - 1)
            for (int lane = 1; lane <= 2; lane++) map.set(stairAt(sc, 1, upper + 1, lane), RoomBuilder.RAILING);
    }

    private List<Pos> stairHeadroom(int f, int[] sc) {
        int H5 = L.floorHeight, fy = L.floorY(f);
        List<Pos> out = new ArrayList<>();
        for (int k = 1; k <= H5; k++)
            for (int lx = k; lx <= k + 1; lx++)
                for (int lane = 1; lane <= 2; lane++) {
                    out.add(stairAt(sc, lx, fy + k + 1, lane));
                    out.add(stairAt(sc, lx, fy + k + 2, lane));
                }
        return out;
    }

    private Pos[] stairEnds(int f, int[] sc) {
        return new Pos[]{stairAt(sc, 1, L.floorY(f) + 1, 1), stairAt(sc, L.floorHeight + 1, L.floorY(f + 1) + 1, 1)};
    }

    // =====================================================================
    // 4. 檢查
    // =====================================================================

    private List<String> validate(BlockMap map, List<Plan> plans) {
        List<String> errors = new ArrayList<>();
        Set<Pos> slabs = new HashSet<>();
        for (Building.CrawlHole h : b.crawlHoles) slabs.add(h.slab());
        Set<Pos> ghostDoors = new HashSet<>(b.ghostDoors);
        Set<Pos> both = new HashSet<>(slabs);
        both.addAll(ghostDoors);
        List<Set<Pos>> walk = new ArrayList<>(), open = new ArrayList<>(), all = new ArrayList<>();
        for (int f = 0; f < L.floors; f++) {
            walk.add(flood(map, f, Set.of()));
            open.add(flood(map, f, ghostDoors));
            all.add(flood(map, f, both));
        }
        for (Plan p : plans) {
            int f = p.f;
            String fl = (f + 1) + "F";
            if (walk.get(f).isEmpty()) {
                errors.add(fl + "：起點站不住");
                continue;
            }
            for (Room r : p.rooms.values()) {
                Set<Pos> set = r.hideout ? all.get(f) : open.get(f);
                boolean ok = false;
                for (int[] t : r.tiles) if (set.contains(at(f, t[0], 0, t[1]))) {
                    ok = true;
                    break;
                }
                if (!ok) {
                    int[] c = r.center();
                    errors.add(fl + " " + (r.corridor ? "走廊" : r.hideout ? "暗格" : r.type.label) + " 走不到（" + at(f, c[0], 0, c[1]) + "）");
                }
            }
            // 走不進去的角落
            for (int z = 0; z < H; z++) {
                for (int x = 0; x < W; x++) {
                    if (p.at(x, z) < 2 || p.cageInside.contains(key(x, z))) continue;
                    Pos q = at(f, x, 0, z);
                    if (standable(map, q, Set.of()) && !all.get(f).contains(q)) {
                        errors.add(fl + " 有走不進去的角落：" + q);
                        z = H;
                        break;
                    }
                }
            }
            if (!b.jailCages.containsKey(f)) errors.add(fl + " 沒有監獄");
            if (f < L.floors - 1) {
                for (int[] sc : stairCells) {
                    Pos[] ends = stairEnds(f, sc);
                    b.stairLinks.add(ends);
                    if (!walk.get(f).contains(ends[0])) errors.add(fl + " 樓梯底部走不到");
                    if (!walk.get(f + 1).contains(ends[1])) errors.add((f + 2) + "F 樓梯頂端出不去");
                    for (Pos q : stairHeadroom(f, sc)) {
                        if (!map.passable(q)) {
                            errors.add(fl + " 樓梯會撞頭：" + q);
                            break;
                        }
                    }
                }
            }
        }
        Set<Pos> reach = new HashSet<>();
        for (Set<Pos> r : walk) reach.addAll(r);
        int usable = 0;
        for (Pos c : b.containers) {
            for (int[] d : DIRS) if (reach.contains(c.add(d[0], 0, d[1]))) {
                usable++;
                break;
            }
        }
        if (usable < s.minContainers())
            errors.add("走得到的容器只有 " + usable + " 個，至少要 " + s.minContainers() + " 個");
        b.powerCandidates.removeIf(q -> !reach.contains(q));
        if (b.powerCandidates.isEmpty()) errors.add("電力室沒有走得到的電源開關位置");
        for (Building.Gate g : b.gates) {
            boolean ok = false;
            for (int[] d : DIRS) if (reach.contains(g.reader().add(d[0], 0, d[1]))) ok = true;
            if (!ok) errors.add((g.floor() + 1) + "F 大門讀卡機走不到");
        }
        if (b.ghostRoom == null) errors.add("沒有鬼房");
        if (errors.isEmpty()) {
            for (Plan p : plans) {
                int f = p.f;
                Set<Pos> spawns = new HashSet<>();
                for (Pos q : walk.get(f)) {
                    Room r = p.room(q.x() - L.ox, q.z() - L.oz);
                    if (r == null || r.hideout || r.type == RoomType.STAIRWELL || r.type == RoomType.JAIL || r.type == RoomType.GHOST_ROOM) continue;
                    spawns.add(q);
                }
                b.spawnTiles.put(f, spawns);
                b.walkTiles.put(f, walk.get(f));
            }
        }
        return errors;
    }

    private Set<Pos> flood(BlockMap map, int f, Set<Pos> extra) {
        Set<Pos> seen = new HashSet<>();
        Pos start = L.crossing(f);
        if (!standable(map, start, extra)) return seen;
        ArrayDeque<Pos> q = new ArrayDeque<>();
        q.add(start);
        seen.add(start);
        while (!q.isEmpty()) {
            Pos p = q.poll();
            for (int[] d : DIRS) {
                Pos n = p.add(d[0], 0, d[1]);
                if (n.x() <= L.ox || n.z() <= L.oz || n.x() >= L.ox + size - 1 || n.z() >= L.oz + size - 1) continue;
                if (seen.contains(n) || !standable(map, n, extra)) continue;
                seen.add(n);
                q.add(n);
            }
        }
        return seen;
    }

    private static boolean standable(BlockMap map, Pos p, Set<Pos> extra) {
        return map.solid(p.add(0, -1, 0))
                && (map.passable(p) || extra.contains(p))
                && (map.passable(p.add(0, 1, 0)) || extra.contains(p.add(0, 1, 0)));
    }

    // ---- 記錄房間、平面圖（info 用） ----

    private void record(List<Plan> plans) {
        for (Plan p : plans) {
            for (Room r : p.rooms.values()) {
                if (r.corridor) continue;
                int[] c = r.center();
                b.rooms.add(new Building.Room(p.f, r.type, at(p.f, c[0], 0, c[1]), r.area(), r.hideout));
            }
            List<String> rows = new ArrayList<>();
            for (int cz = 0; cz < CH; cz++) {
                StringBuilder sb = new StringBuilder();
                for (int cx = 0; cx < CW; cx++) {
                    if (!cells[cz][cx]) {
                        sb.append('　');
                        continue;
                    }
                    Room r = p.room(cx * U + U / 2, cz * U + U / 2);
                    if (r == null) sb.append('＃');
                    else if (r.corridor) sb.append('＝');
                    else sb.append(r.type.symbol);
                }
                rows.add(sb.toString());
            }
            b.plans.put(p.f, rows);
        }
    }
}
