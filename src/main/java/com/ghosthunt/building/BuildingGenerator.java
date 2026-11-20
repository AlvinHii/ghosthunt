package com.ghosthunt.building;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 產生一棟大樓：決定每個房間格放什麼 → 在記憶體裡畫出方塊 → 檢查 → 不通過就換一個種子重來。
 */
public final class BuildingGenerator {

    public record Result(Building building, BlockMap blocks, int attempts, List<String> errors, List<String> rejected) {
        public boolean ok() {
            return errors.isEmpty();
        }
    }

    private static final int MAX_ATTEMPTS = 20;

    public static Result generate(BuildingSettings s, long seed) {
        if (s.irregular()) return IrregularGenerator.generate(s, seed);
        List<String> problems = checkSettings(s);
        if (!problems.isEmpty()) return new Result(null, null, 0, problems, List.of());

        List<String> last = List.of();
        List<String> rejected = new ArrayList<>();
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            long sd = seed + attempt;
            Building b = new Building(sd, s.layout());
            BlockMap map = new BlockMap();
            Random rnd = new Random(sd);
            plan(b, s, rnd);
            RoomBuilder rooms = compose(b, map, s, rnd);
            List<String> gateErrors = pickGates(b, map, s, rnd);
            map.connectPanes();
            for (Pos p : b.ghostDoors) b.ghostDoorBlocks.put(p, map.get(p));
            for (Building.Gate g : b.gates) for (Pos p : g.opening()) b.gateBlocks.put(p, map.get(p));
            List<String> errors = new ArrayList<>(gateErrors);
            errors.addAll(Validator.validate(b, map, s, rooms));
            if (errors.isEmpty()) return new Result(b, map, attempt + 1, List.of(), rejected);
            last = errors;
            rejected.add(errors.get(0));
        }
        return new Result(null, null, MAX_ATTEMPTS, last, rejected);
    }

    private static List<String> checkSettings(BuildingSettings s) {
        List<String> e = new ArrayList<>();
        if (s.roomsPerSide() != 3 && s.roomsPerSide() != 4) e.add("目前只支援每層 3×3 或 4×4 個房間格（map.rooms-per-side）");
        if (s.roomSize() - 2 < s.floorHeight() + 1)
            e.add("房間格太小放不下樓梯：需要 room-size - 2 >= floor-height + 1");
        if (s.floorHeight() < 5) e.add("層高至少要 5 格（4 格高的牆、3 格高的門）");
        if (s.floors() < 1) e.add("至少要 1 層");
        return e;
    }

    // ---- 1. 決定每個房間格放什麼 ----

    private static void plan(Building b, BuildingSettings s, Random rnd) {
        Layout L = b.layout;
        int n = L.n;
        for (int f = 0; f < L.floors; f++) {
            List<int[]> free = new ArrayList<>();
            for (int cx = 0; cx < n; cx++)
                for (int cz = 0; cz < n; cz++) free.add(new int[]{cx, cz});
            // 兩個樓梯間在對角
            set(b, f, 0, 0, RoomType.STAIRWELL, free);
            set(b, f, n - 1, n - 1, RoomType.STAIRWELL, free);
            // 每層一間監獄
            int[] j = free.get(rnd.nextInt(free.size()));
            set(b, f, j[0], j[1], RoomType.JAIL, free);
        }
        // 鬼房：隨機一層、隨機一格
        int gf = rnd.nextInt(L.floors);
        List<int[]> freeOnGf = new ArrayList<>();
        for (int cx = 0; cx < n; cx++)
            for (int cz = 0; cz < n; cz++)
                if (b.types[gf][cx][cz] == null) freeOnGf.add(new int[]{cx, cz});
        int[] g = freeOnGf.get(rnd.nextInt(freeOnGf.size()));
        b.types[gf][g[0]][g[1]] = RoomType.GHOST_ROOM;

        // 其他房間依樓層用途分配（1F 公共區、2F 辦公區、3F 設備區），每層約 2/3 是該層的房間、其餘混搭，
        // 同一層不重複。電力室（總電源）另外處理：整棟 1～2 間、樓層隨機，免得電源永遠在同一層。
        List<int[]> freeCells = new ArrayList<>();
        for (int f = 0; f < L.floors; f++)
            for (int cx = 0; cx < n; cx++)
                for (int cz = 0; cz < n; cz++)
                    if (b.types[f][cx][cz] == null) freeCells.add(new int[]{f, cx, cz});
        java.util.Collections.shuffle(freeCells, rnd);
        int electrical = Math.min(freeCells.size(), 1 + (rnd.nextInt(3) == 0 ? 1 : 0));
        java.util.Set<Integer> electricalFloors = new java.util.HashSet<>();
        for (int[] c : freeCells) {
            if (electrical == 0) break;
            if (!electricalFloors.add(c[0])) continue; // 兩間電力室不放同一層
            b.types[c[0]][c[1]][c[2]] = RoomType.ELECTRICAL;
            electrical--;
        }
        for (int f = 0; f < L.floors; f++) {
            List<RoomType> theme = new ArrayList<>(themeOf(f));
            List<RoomType> others = new ArrayList<>(RoomType.NORMAL);
            others.removeAll(theme);
            others.remove(RoomType.ELECTRICAL);
            theme.remove(RoomType.ELECTRICAL);
            java.util.Collections.shuffle(theme, rnd);
            java.util.Collections.shuffle(others, rnd);
            java.util.Set<RoomType> used = new java.util.HashSet<>();
            for (int cx = 0; cx < n; cx++)
                for (int cz = 0; cz < n; cz++)
                    if (b.types[f][cx][cz] != null) used.add(b.types[f][cx][cz]);
            for (int cx = 0; cx < n; cx++) {
                for (int cz = 0; cz < n; cz++) {
                    if (b.types[f][cx][cz] != null) continue;
                    boolean fromTheme = rnd.nextInt(3) < 2;
                    RoomType t = pick(fromTheme ? theme : others, used);
                    if (t == null) t = pick(fromTheme ? others : theme, used);
                    if (t == null) t = RoomType.STORAGE; // 種類用完了（房間格很多時）
                    if (t == RoomType.TOILET && s.maxDeadEndsPerFloor() < 1) t = RoomType.STORAGE;
                    b.types[f][cx][cz] = t;
                    used.add(t);
                }
            }
        }
    }

    /** 各樓層偏好的房間（樓層超過 3 層時循環）。 */
    private static List<RoomType> themeOf(int floor) {
        return switch (floor % 3) {
            case 0 -> List.of(RoomType.LOUNGE, RoomType.COPY_ROOM, RoomType.STORAGE, RoomType.TOILET, RoomType.OPEN_OFFICE);
            case 1 -> List.of(RoomType.OPEN_OFFICE, RoomType.CUBICLES, RoomType.BOARDROOM, RoomType.ARCHIVE, RoomType.TOILET);
            default -> List.of(RoomType.SERVER, RoomType.STORAGE, RoomType.ARCHIVE, RoomType.TOILET, RoomType.COPY_ROOM);
        };
    }

    private static RoomType pick(List<RoomType> list, java.util.Set<RoomType> used) {
        for (RoomType t : list) if (!used.contains(t)) return t;
        return null;
    }

    private static void set(Building b, int f, int cx, int cz, RoomType t, List<int[]> free) {
        b.types[f][cx][cz] = t;
        free.removeIf(c -> c[0] == cx && c[1] == cz);
    }

    // ---- 2. 畫出方塊 ----

    private static RoomBuilder compose(Building b, BlockMap map, BuildingSettings s, Random rnd) {
        Layout L = b.layout;
        int size = L.size;
        // 先把整個範圍（含外面放出口平台的空間）清成空氣
        map.fill(L.rel(-4, L.floorY(0), -4), L.rel(size + 3, L.topY() + 1, size + 3), Material.AIR);

        for (int f = 0; f < L.floors; f++) {
            int fy = L.floorY(f);
            for (int x = 0; x < size; x++)
                for (int z = 0; z < size; z++)
                    map.set(L.rel(x, fy, z), RoomBuilder.floorMaterial(f, x, z));
            // 外牆
            for (int i = 0; i < size; i++) {
                for (int y = fy + 1; y < fy + L.floorHeight; y++) {
                    map.set(L.rel(i, y, 0), Material.LIGHT_GRAY_CONCRETE);
                    map.set(L.rel(i, y, size - 1), Material.LIGHT_GRAY_CONCRETE);
                    map.set(L.rel(0, y, i), Material.LIGHT_GRAY_CONCRETE);
                    map.set(L.rel(size - 1, y, i), Material.LIGHT_GRAY_CONCRETE);
                }
            }
        }
        map.fill(L.rel(0, L.topY(), 0), L.rel(size - 1, L.topY(), size - 1), Material.SMOOTH_STONE);

        RoomBuilder rooms = new RoomBuilder(L, map, b, s.emergencyLight(), rnd, s.doorChance(), s.crawlHoleChance());
        for (int f = 0; f < L.floors; f++)
            for (int cx = 0; cx < L.n; cx++)
                for (int cz = 0; cz < L.n; cz++)
                    rooms.build(f, cx, cz, b.types[f][cx][cz]);

        clutter(L, map, rooms, rnd, s.corridorClutter());

        // 走廊天花板的通風管（鐵欄杆，貼著走廊一側，碰不到）
        for (int f = 0; f < L.floors; f++) {
            int y = L.floorY(f) + L.floorHeight - 1;
            for (int c : L.corridorStarts()) {
                for (int i = 1; i < size - 1; i++) {
                    for (Pos p : List.of(L.rel(i, y, c), L.rel(c, y, i))) {
                        if (map.type(p) == Material.AIR) map.set(p, Material.IRON_BARS);
                    }
                }
            }
        }

        // 走廊的燈：每 5 格一盞
        for (int f = 0; f < L.floors; f++) {
            int y = L.floorY(f) + L.floorHeight - 1;
            for (int c : L.corridorStarts()) {
                int mid = c + L.corridor / 2;
                for (int i = 3; i < size - 1; i += 5) {
                    for (Pos p : List.of(L.rel(mid, y, i), L.rel(i, y, mid))) {
                        if (map.passable(p)) {
                            map.set(p, rooms.light());
                            b.lights.add(p);
                        }
                    }
                }
            }
        }

        // 大門候選：每條走廊的兩端（每層 8 個）
        for (int f = 0; f < L.floors; f++) {
            for (int c : L.corridorStarts()) {
                b.gateCandidates.add(gateAt(L, f, c, true, false));
                b.gateCandidates.add(gateAt(L, f, c, true, true));
                b.gateCandidates.add(gateAt(L, f, c, false, false));
                b.gateCandidates.add(gateAt(L, f, c, false, true));
            }
        }
        return rooms;
    }

    /**
     * 走廊末端的大門。alongX = 走廊沿 x 方向延伸（佔 z = c..c+w-1，末端在 x = 0 或 size-1 的外牆）。
     */
    private static Building.Gate gateAt(Layout L, int f, int c, boolean alongX, boolean far) {
        int w = L.corridor;
        int fy = L.floorY(f);
        int wall = far ? L.size - 1 : 0;
        int out = far ? 1 : -1; // 往外的方向
        List<Pos> opening = new ArrayList<>();
        for (int i = 0; i < w; i++)
            for (int y = fy + 1; y <= fy + 3; y++)
                opening.add(alongX ? L.rel(wall, y, c + i) : L.rel(c + i, y, wall));
        int mid = c + w / 2;
        Pos center = alongX ? L.rel(wall, fy + 1, mid) : L.rel(mid, fy + 1, wall);
        // 讀卡機：走廊裡、離外牆 2 格、靠走廊一側
        Pos reader = alongX ? L.rel(wall - 2 * out, fy + 1, c) : L.rel(c, fy + 1, wall - 2 * out);
        Pos exit = alongX ? L.rel(wall + 2 * out, fy + 1, mid) : L.rel(mid, fy + 1, wall + 2 * out);
        return new Building.Gate(f, center, opening, reader, exit);
    }

    /** 走廊雜物：沿走廊兩側隨機放單格的東西，讓走廊不要太空、追逐時有東西可以繞。 */
    private static final Material[] CLUTTER = {
            Material.COMPOSTER, Material.POTTED_FERN, Material.POTTED_BAMBOO, Material.SPRUCE_PLANKS,
            Material.CHISELED_BOOKSHELF, Material.CAULDRON, Material.LOOM, Material.SMITHING_TABLE};

    private static void clutter(Layout L, BlockMap map, RoomBuilder rooms, Random rnd, double density) {
        if (density <= 0) return;
        List<Integer> cs = L.corridorStarts();
        for (int f = 0; f < L.floors; f++) {
            int y = L.floorY(f) + 1;
            for (int c : cs) {
                for (boolean alongX : new boolean[]{true, false}) {
                    for (int i = 5; i < L.size - 5; i++) {
                        if (rnd.nextDouble() >= density) continue;
                        // 避開走廊交叉口
                        boolean nearCross = false;
                        for (int c2 : cs) if (i >= c2 - 1 && i <= c2 + L.corridor) nearCross = true;
                        if (nearCross) continue;
                        int lane = rnd.nextBoolean() ? c : c + L.corridor - 1;
                        Pos p = alongX ? L.rel(i, y, lane) : L.rel(lane, y, i);
                        boolean blocked = false;
                        for (int d = -1; d <= 1 && !blocked; d++) {
                            Pos q = alongX ? p.add(d, 0, 0) : p.add(0, 0, d);
                            if (rooms.keepClear.contains(q) || !map.passable(q)) blocked = true;
                        }
                        if (blocked) continue;
                        map.set(p, CLUTTER[rnd.nextInt(CLUTTER.length)]);
                    }
                }
            }
        }
    }

    // ---- 3. 挑兩扇大門 ----

    private static List<String> pickGates(Building b, BlockMap map, BuildingSettings s, Random rnd) {
        Layout L = b.layout;
        List<Building.Gate> cands = new ArrayList<>(b.gateCandidates);
        java.util.Collections.shuffle(cands, rnd);
        List<Building.Gate> chosen = new ArrayList<>();
        outer:
        for (Building.Gate g : cands) {
            for (Building.Gate o : chosen) {
                if (o.floor() == g.floor()) continue outer;
                if (o.center().distance(g.center()) < s.gateMinDistance()) continue outer;
                if (nearestStair(L, o) == nearestStair(L, g)) continue outer;
            }
            chosen.add(g);
            if (chosen.size() == s.gateCount()) break;
        }
        if (chosen.size() < s.gateCount()) {
            return List.of("找不到符合條件的大門位置（不同樓層、至少相隔 " + s.gateMinDistance() + " 格、最近的樓梯間不同）");
        }
        for (Building.Gate g : chosen) {
            for (Pos p : g.opening()) map.set(p, Material.IRON_BARS);
            map.set(g.reader(), Material.LODESTONE);
            // 門外的出口平台（3×3）
            Pos e = g.exit();
            map.fill(e.add(-1, -1, -1), e.add(1, -1, 1), Material.POLISHED_BLACKSTONE);
        }
        b.gates.addAll(chosen);
        return List.of();
    }

    /** 0 = 左上角的樓梯間，1 = 右下角的樓梯間（依水平距離）。 */
    private static int nearestStair(Layout L, Building.Gate g) {
        Pos a = L.cellCenter(g.floor(), 0, 0);
        Pos z = L.cellCenter(g.floor(), L.n - 1, L.n - 1);
        return g.center().distance(a) <= g.center().distance(z) ? 0 : 1;
    }
}
