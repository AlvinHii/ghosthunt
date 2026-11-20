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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * 第一版的房間模板，用程式產生（之後可以換成 .nbt 結構檔）。
 * 房間格內的相對座標：0 和 room-1 是牆，1..room-2 是房間內部。
 * 每個房間格在 x 軸和 z 軸上各有一面牆接走廊，門口開在那面牆的正中間（2 格寬）。
 * 門口有三種：可以開關的雙開門、空門口（地上有黃色記號，逃生者可以放障礙物）、鬼房的鐵欄杆。
 */
final class RoomBuilder {

    static final Material RAILING = Material.POLISHED_ANDESITE;
    static final Material BARRICADE_MARK = Material.YELLOW_CARPET;

    private final Layout L;
    private final BlockMap map;
    private final Building b;
    private final int lightLevel;
    private final Random rnd;
    private final double doorChance;
    private final double crawlChance;
    /** 門口外面那一格（走廊上），放走廊雜物時要避開。 */
    final Set<Pos> keepClear = new HashSet<>();

    RoomBuilder(Layout layout, BlockMap map, Building building, int lightLevel, Random rnd, double doorChance, double crawlChance) {
        this.crawlChance = crawlChance;
        this.L = layout;
        this.map = map;
        this.b = building;
        this.lightLevel = lightLevel;
        this.rnd = rnd;
        this.doorChance = doorChance;
    }

    // ---- 各樓層材質：1F 磁磚、2F 地毯、3F 水泥 ----

    static Material floorMaterial(int f, int x, int z) {
        return switch (f % 3) {
            case 0 -> ((x + z) & 1) == 0 ? Material.WHITE_CONCRETE : Material.LIGHT_GRAY_CONCRETE;
            case 1 -> Material.RED_WOOL;
            default -> Material.GRAY_CONCRETE;
        };
    }

    static Material wallMaterial(int f) {
        return switch (f % 3) {
            case 0 -> Material.SMOOTH_QUARTZ;
            case 1 -> Material.BIRCH_PLANKS;
            default -> Material.POLISHED_ANDESITE;
        };
    }

    static Material signMaterial(int f) {
        return switch (f % 3) {
            case 0 -> Material.OAK_WALL_SIGN;
            case 1 -> Material.BIRCH_WALL_SIGN;
            default -> Material.SPRUCE_WALL_SIGN;
        };
    }

    static Material doorMaterial(int f) {
        return switch (f % 3) {
            case 0 -> Material.OAK_DOOR;
            case 1 -> Material.BIRCH_DOOR;
            default -> Material.SPRUCE_DOOR;
        };
    }

    BlockData light() {
        Levelled d = (Levelled) Bukkit.createBlockData(Material.LIGHT);
        d.setLevel(lightLevel);
        return d;
    }

    // ---- 房間 ----

    private int cx, cz, f, R, w;
    private final Set<Long> reserved = new HashSet<>();

    void build(int f, int cx, int cz, RoomType type) {
        this.f = f;
        this.cx = cx;
        this.cz = cz;
        this.R = L.room;
        int fy = L.floorY(f);
        this.w = fy + 1;
        int top = fy + L.floorHeight - 1;
        Material wall = type == RoomType.GHOST_ROOM ? Material.BLACKSTONE : wallMaterial(f);
        reserved.clear();

        // 四面牆
        for (int i = 0; i < R; i++) {
            for (int y = fy + 1; y <= top; y++) {
                map.set(L.cell(cx, cz, i, y, 0), wall);
                map.set(L.cell(cx, cz, i, y, R - 1), wall);
                map.set(L.cell(cx, cz, 0, y, i), wall);
                map.set(L.cell(cx, cz, R - 1, y, i), wall);
            }
        }

        // 門口：每一面接走廊的牆都開一個；廁所只開一個（死路）
        List<Object[]> walls = new ArrayList<>();
        if (L.corridorAfter(cx)) walls.add(new Object[]{true, R - 1, BlockFace.EAST});
        if (L.corridorBefore(cx)) walls.add(new Object[]{true, 0, BlockFace.WEST});
        if (L.corridorAfter(cz)) walls.add(new Object[]{false, R - 1, BlockFace.SOUTH});
        if (L.corridorBefore(cz)) walls.add(new Object[]{false, 0, BlockFace.NORTH});
        List<Object[]> allWalls = new ArrayList<>(walls);
        if (type.deadEnd()) walls = walls.subList(0, 1);
        deadEndDoor = type.deadEnd() ? walls.get(0) : null;
        for (Object[] wd : walls) doorway(type, (Boolean) wd[0], (Integer) wd[1], (BlockFace) wd[2], wall);
        // 蹲伏破洞：一般房間有機會在某面接走廊的牆上多一個只有逃生者鑽得過的小洞（廁所的話就是第二個出口）
        if (type.normal() && rnd.nextDouble() < crawlChance) {
            Object[] wd = allWalls.get(rnd.nextInt(allWalls.size()));
            crawlHole((Boolean) wd[0], (Integer) wd[1], (BlockFace) wd[2]);
        }

        // 燈（天花板下面一格）：大房間放 4 盞
        for (int lx : new int[]{R / 4, R - 1 - R / 4}) {
            for (int lz : new int[]{R / 4, R - 1 - R / 4}) {
                Pos lp = L.cell(cx, cz, lx, top, lz);
                map.set(lp, light());
                b.lights.add(lp);
            }
        }

        // 天花板角落的蜘蛛網（廢棄感；在頭頂上方，碰不到所以不會讓人變慢）
        for (int[] c : new int[][]{{1, 1}, {1, R - 2}, {R - 2, 1}, {R - 2, R - 2}}) {
            if (rnd.nextInt(3) == 0) map.set(L.cell(cx, cz, c[0], top, c[1]), Material.COBWEB);
        }

        switch (type) {
            case STAIRWELL -> {
                b.stairwells.add(new Building.Cell(f, cx, cz, type));
                if (f < L.floors - 1) stairs(f, cx, cz);
                // 大廳：樓梯在一個角落，其他地方放幾根 3 格高的柱子擋視線
                boolean mx = !L.corridorAfter(cx), mz = !L.corridorAfter(cz);
                for (int[] c : new int[][]{{6, 12}, {12, 6}, {12, 12}, {15, 15}}) {
                    if (c[0] + 1 > R - 3 || c[1] + 1 > R - 3) continue;
                    for (int dx = 0; dx <= 1; dx++)
                        for (int dz = 0; dz <= 1; dz++) {
                            int lx = c[0] + dx, lz = c[1] + dz;
                            lx = mx ? R - 1 - lx : lx;
                            lz = mz ? R - 1 - lz : lz;
                            if (isReserved(lx, lz)) continue;
                            for (int y = w; y <= w + 2; y++) map.set(L.cell(cx, cz, lx, y, lz), pillar());
                        }
                }
            }
            default -> {
                if (type == RoomType.JAIL) b.jailCages.put(f, L.cellCenter(f, cx, cz));
                if (type == RoomType.GHOST_ROOM) {
                    for (int x = 1; x <= R - 2; x++)
                        for (int z = 1; z <= R - 2; z++) map.set(L.cell(cx, cz, x, fy, z), Material.SCULK);
                    b.ghostRoom = new Building.Cell(f, cx, cz, type);
                    b.ghostRoomCenter = L.cellCenter(f, cx, cz);
                }
                template(type);
            }
        }
    }

    /** 2 格高隔板的材質（依樓層）。 */
    private Material partition() {
        return switch (f % 3) {
            case 0 -> Material.WHITE_TERRACOTTA;
            case 1 -> Material.STRIPPED_BIRCH_WOOD;
            default -> Material.GRAY_CONCRETE;
        };
    }

    private Material pillar() {
        return rnd.nextInt(4) == 0 ? Material.CRACKED_STONE_BRICKS : Material.STONE_BRICKS;
    }

    /** 只有一個門的房間，那個門在哪面牆（{onX, wallL, facing}）。 */
    private Object[] deadEndDoor;

    // 目前正在放的平面圖（給椅子找桌子方向用）
    private String[] tpl;
    private int tplRot;
    private boolean tplMirror;

    private char tplAt(int lx, int lz) {
        return RoomTemplates.at(tpl, lz - 1, lx - 1, tplRot, tplMirror);
    }

    /**
     * 依房間種類放平面圖（docs/房間設計v2.md），隨機旋轉、鏡像。
     * 平面圖對應房間內部（內部座標 1..R-2），房間格大小不是 SIZE + 2 時就不放。
     */
    private void template(RoomType type) {
        tpl = RoomTemplates.TEMPLATES.get(type);
        if (tpl == null || R - 2 != RoomTemplates.SIZE) return;
        tplRot = rnd.nextInt(4);
        tplMirror = rnd.nextBoolean();
        if (deadEndDoor != null) {
            // 只有一個門的房間（廁所）：平面圖的門畫在南面（最下面一排），轉到對齊實際的門
            boolean onX = (Boolean) deadEndDoor[0];
            int wallL = (Integer) deadEndDoor[1];
            int n = RoomTemplates.SIZE, mid = n / 2;
            int r = onX ? mid : (wallL == 0 ? 0 : n - 1);
            int c = onX ? (wallL == 0 ? 0 : n - 1) : mid;
            for (int rot = 0; rot < 4; rot++) {
                if (RoomTemplates.source(r, c, rot, tplMirror)[0] == n - 1) {
                    tplRot = rot;
                    break;
                }
            }
        }
        Material wall = wallMaterial(f);
        boolean dining = type == RoomType.BOARDROOM || type == RoomType.LOUNGE;
        for (int lz = 1; lz <= R - 2; lz++) {
            for (int lx = 1; lx <= R - 2; lx++) {
                char ch = tplAt(lx, lz);
                switch (ch) {
                    case 'X' -> barrel(lx, lz);
                    case 'E' -> {
                        if (type == RoomType.ELECTRICAL) b.powerCandidates.add(L.cell(cx, cz, lx, w, lz));
                    }
                    case 'T' -> {
                        if (put(lx, lz, dining ? Material.STRIPPED_SPRUCE_WOOD : Material.DARK_OAK_PLANKS)) {
                            // 桌上的東西（不影響走路）：辦公桌放電腦、影印室放紙
                            if ((type == RoomType.OPEN_OFFICE || type == RoomType.CUBICLES) && rnd.nextInt(10) < 5)
                                top(lx, lz, rnd.nextInt(5) == 0 ? Material.FLOWER_POT : Material.DAYLIGHT_DETECTOR);
                            else if (type == RoomType.COPY_ROOM && rnd.nextInt(10) < 6) top(lx, lz, Material.WHITE_CARPET);
                        }
                    }
                    case 'C' -> chair(lx, lz);
                    case 'F' -> put(lx, lz, Material.GRAY_WOOL);
                    case 'Q' -> {
                        // 吧台上偶爾放微波爐、咖啡機、盆栽
                        if (put(lx, lz, Material.POLISHED_DIORITE) && rnd.nextInt(100) < 35) {
                            Material[] app = {Material.SMOKER, Material.BREWING_STAND, Material.FLOWER_POT};
                            top(lx, lz, app[rnd.nextInt(app.length)]);
                        }
                    }
                    case 'U' -> toilet(lx, lz);
                    case 'd' -> stallDoor(lx, lz);
                    case 'Y' -> stack(lx, lz, wall, Material.WHITE_CONCRETE, Material.WHITE_CONCRETE);
                    case 'M' -> put(lx, lz, Material.DROPPER);
                    case 'K' -> put(lx, lz, Material.CAULDRON);
                    case 'H' -> stack(lx, lz, partition(), partition());
                    case 'S' -> stack(lx, lz, Material.STRIPPED_OAK_WOOD, Material.STRIPPED_OAK_WOOD);
                    case 'G' -> {
                        stack(lx, lz, Material.BLAST_FURNACE, Material.IRON_BLOCK);
                        // 從天花板垂下來的電纜
                        if (rnd.nextInt(3) == 0) {
                            for (int y = w + 2; y < w + L.floorHeight - 1; y++) {
                                Pos p = L.cell(cx, cz, lx, y, lz);
                                if (map.type(p) == Material.AIR) map.set(p, Material.IRON_CHAIN);
                            }
                        }
                    }
                    case 'V' -> stack(lx, lz, Material.RED_CONCRETE, Material.RED_CONCRETE);
                    case 'B' -> stack(lx, lz, Material.BOOKSHELF,
                            rnd.nextInt(10) < 3 ? Material.CHISELED_BOOKSHELF : Material.BOOKSHELF, Material.BOOKSHELF);
                    case 'R' -> {
                        if (type == RoomType.SERVER) {
                            // 機櫃：黑色外殼，中間是金屬面板，偶爾一格是（不亮的）指示燈
                            Material mid = rnd.nextInt(4) == 0 ? Material.REDSTONE_LAMP : Material.IRON_BLOCK;
                            stack(lx, lz, Material.POLISHED_BLACKSTONE, mid, Material.POLISHED_BLACKSTONE);
                        } else {
                            // 倉庫貨架：全部用完整方塊（半磚會留半格空隙，看起來像歪掉）
                            Material[] goods = {Material.CHISELED_BOOKSHELF, Material.SPRUCE_PLANKS, Material.HAY_BLOCK,
                                    Material.STRIPPED_SPRUCE_WOOD};
                            stack(lx, lz, Material.STRIPPED_SPRUCE_WOOD, goods[rnd.nextInt(goods.length)], Material.SPRUCE_PLANKS);
                        }
                    }
                    case 'P' -> stack(lx, lz, pillar(), pillar(), pillar());
                    case 'W' -> stack(lx, lz, wall, wall, wall);
                    case 'J' -> stack(lx, lz, Material.IRON_BARS, Material.IRON_BARS, Material.IRON_BARS);
                    default -> { }
                }
            }
        }
    }

    /** 在 1 格高的家具上面放東西（w + 1 那層）。 */
    private void top(int lx, int lz, Material m) {
        map.set(L.cell(cx, cz, lx, w + 1, lz), m);
    }

    /** 馬桶：背靠牆、面向隔間門。 */
    private void toilet(int lx, int lz) {
        BlockFace toDoor = neighbor(lx, lz, '.');
        if (toDoor == null) toDoor = neighbor(lx, lz, 'd');
        Stairs s = (Stairs) Bukkit.createBlockData(Material.QUARTZ_STAIRS);
        s.setFacing(toDoor == null ? BlockFace.NORTH : toDoor.getOppositeFace()); // 椅背（高的那半）靠牆
        s.setHalf(Bisected.Half.BOTTOM);
        if (!isReserved(lx, lz)) map.set(L.cell(cx, cz, lx, w, lz), s);
    }

    /** 廁所隔間的門：門板關起來時在走道那一側，人可以躲在門後。 */
    private void stallDoor(int lx, int lz) {
        BlockFace toToilet = neighbor(lx, lz, 'U');
        if (toToilet == null) { // 隔間 2 格深：門前面一格是空地，再過去才是馬桶
            for (BlockFace f2 : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.WEST, BlockFace.EAST}) {
                if (tplAt(lx + f2.getModX(), lz + f2.getModZ()) == '.' && tplAt(lx + 2 * f2.getModX(), lz + 2 * f2.getModZ()) == 'U') {
                    toToilet = f2;
                    break;
                }
            }
        }
        if (toToilet == null || isReserved(lx, lz)) return;
        for (Bisected.Half half : Bisected.Half.values()) {
            Door d = (Door) Bukkit.createBlockData(Material.BIRCH_DOOR);
            // 門的方向朝馬桶時，關起來的門板貼在反方向（走道那側）
            d.setFacing(toToilet);
            d.setHinge(Door.Hinge.LEFT);
            d.setHalf(half);
            d.setOpen(true);
            map.set(L.cell(cx, cz, lx, half == Bisected.Half.BOTTOM ? w : w + 1, lz), d);
        }
    }

    /** 平面圖上 (lx, lz) 四周哪個方向是字元 ch。 */
    private BlockFace neighbor(int lx, int lz, char ch) {
        if (tplAt(lx, lz - 1) == ch) return BlockFace.NORTH;
        if (tplAt(lx, lz + 1) == ch) return BlockFace.SOUTH;
        if (tplAt(lx - 1, lz) == ch) return BlockFace.WEST;
        if (tplAt(lx + 1, lz) == ch) return BlockFace.EAST;
        return null;
    }

    /** 由下往上疊方塊。 */
    private void stack(int lx, int lz, Material... ms) {
        if (isReserved(lx, lz)) return;
        for (int i = 0; i < ms.length; i++) map.set(L.cell(cx, cz, lx, w + i, lz), ms[i]);
    }

    /** 椅子：背對旁邊的桌子。 */
    private void chair(int lx, int lz) {
        BlockFace facing = BlockFace.NORTH;
        if (tplAt(lx, lz - 1) == 'T') facing = BlockFace.SOUTH;
        else if (tplAt(lx, lz + 1) == 'T') facing = BlockFace.NORTH;
        else if (tplAt(lx - 1, lz) == 'T') facing = BlockFace.EAST;
        else if (tplAt(lx + 1, lz) == 'T') facing = BlockFace.WEST;
        if (isReserved(lx, lz)) return;
        Stairs s = (Stairs) Bukkit.createBlockData(Material.OAK_STAIRS);
        s.setFacing(facing);
        s.setHalf(Bisected.Half.BOTTOM);
        map.set(L.cell(cx, cz, lx, w, lz), s);
    }

    private void column(int lx, int lz, Material m, int height) {
        if (isReserved(lx, lz)) return;
        for (int y = w; y < w + height; y++) map.set(L.cell(cx, cz, lx, y, lz), m);
    }

    /**
     * 開一個門口。onX = 門在 x 軸那面牆（牆在 lx = wallL），否則在 z 軸那面（牆在 lz = wallL）。
     * facing 是牆往走廊的方向。
     */
    private void doorway(RoomType type, boolean onX, int wallL, BlockFace facing, Material wall) {
        int m0 = R / 2 - 1, m1 = R / 2;
        int inner = wallL == 0 ? 1 : R - 2;
        List<Pos> lower = new ArrayList<>();
        for (int l = m0; l <= m1; l++) {
            lower.add(onX ? L.cell(cx, cz, wallL, w, l) : L.cell(cx, cz, l, w, wallL));
            reserve(onX ? inner : l, onX ? l : inner); // 門口內側那格不放家具
        }
        for (Pos p : lower) keepClear.add(p.add(facing.getModX(), 0, facing.getModZ()));

        // 門口旁邊的告示牌（走廊那側、眼睛高度）：樓層＋房間名稱
        if (type != RoomType.GHOST_ROOM) {
            Pos wallBlock = onX ? L.cell(cx, cz, wallL, w + 1, m0 - 1) : L.cell(cx, cz, m0 - 1, w + 1, wallL);
            Pos signPos = wallBlock.add(facing.getModX(), 0, facing.getModZ());
            org.bukkit.block.data.type.WallSign sign = (org.bukkit.block.data.type.WallSign) Bukkit.createBlockData(signMaterial(f));
            sign.setFacing(facing);
            map.set(signPos, sign);
            b.signs.put(signPos, new String[]{(f + 1) + "F", type.label});
        }

        if (type == RoomType.GHOST_ROOM) {
            for (Pos p : lower)
                for (int dy = 0; dy <= 2; dy++) {
                    map.set(p.add(0, dy, 0), Material.IRON_BARS);
                    b.ghostDoors.add(p.add(0, dy, 0));
                }
            return;
        }
        boolean canHaveDoor = type.normal();
        if (canHaveDoor && rnd.nextDouble() < doorChance) {
            // 雙開門（2 格高），上面補一格牆當門框
            BlockFace left = switch (facing) {
                case EAST -> BlockFace.NORTH;
                case NORTH -> BlockFace.WEST;
                case WEST -> BlockFace.SOUTH;
                default -> BlockFace.EAST;
            };
            Pos p0 = lower.get(0), p1 = lower.get(1);
            int dot = (p1.x() - p0.x()) * left.getModX() + (p1.z() - p0.z()) * left.getModZ();
            placeDoor(p0, facing, dot > 0 ? Door.Hinge.RIGHT : Door.Hinge.LEFT);
            placeDoor(p1, facing, dot > 0 ? Door.Hinge.LEFT : Door.Hinge.RIGHT);
            for (Pos p : lower) map.set(p.add(0, 2, 0), wall);
            return;
        }
        // 空門口（3 格高）
        for (Pos p : lower) for (int dy = 0; dy <= 2; dy++) map.set(p.add(0, dy, 0), Material.AIR);
        if (canHaveDoor) {
            // 逃生者可以在這裡放障礙物（填滿下面 2 格）
            List<Pos> blocks = new ArrayList<>();
            for (Pos p : lower) {
                blocks.add(p);
                blocks.add(p.add(0, 1, 0));
                map.set(p, BARRICADE_MARK);
            }
            b.barricadeSpots.add(new Building.BarricadeSpot(blocks, lower));
        }
    }

    private void crawlHole(boolean onX, int wallL, BlockFace facing) {
        int l = rnd.nextBoolean() ? 4 : R - 5; // 離門口（中間）遠一點
        int inner = wallL == 0 ? 1 : R - 2;
        Pos lower = onX ? L.cell(cx, cz, wallL, w, l) : L.cell(cx, cz, l, w, wallL);
        Pos slab = lower.add(0, 1, 0);
        reserve(onX ? inner : l, onX ? l : inner);
        keepClear.add(lower.add(facing.getModX(), 0, facing.getModZ()));
        org.bukkit.block.data.type.Slab s = (org.bukkit.block.data.type.Slab) Bukkit.createBlockData(Material.STONE_BRICK_SLAB);
        s.setType(org.bukkit.block.data.type.Slab.Type.TOP);
        map.set(lower, Material.AIR);
        map.set(slab, s);
        b.crawlHoles.add(new Building.CrawlHole(lower, slab, s));
    }

    private void placeDoor(Pos p, BlockFace facing, Door.Hinge hinge) {
        for (Bisected.Half half : Bisected.Half.values()) {
            Door d = (Door) Bukkit.createBlockData(doorMaterial(f));
            d.setFacing(facing);
            d.setHinge(hinge);
            d.setHalf(half);
            d.setOpen(false);
            map.set(half == Bisected.Half.BOTTOM ? p : p.add(0, 1, 0), d);
        }
        b.doors.add(p);
    }

    private void reserve(int lx, int lz) {
        reserved.add(((long) lx << 32) | (lz & 0xffffffffL));
    }

    private boolean isReserved(int lx, int lz) {
        return reserved.contains(((long) lx << 32) | (lz & 0xffffffffL));
    }

    /** 在房間內地面放一個方塊（門口內側那格會跳過）。 */
    private boolean put(int lx, int lz, Material m) {
        if (isReserved(lx, lz)) return false;
        map.set(L.cell(cx, cz, lx, w, lz), m);
        return true;
    }

    private void barrel(int lx, int lz) {
        if (isReserved(lx, lz)) return;
        Directional d = (Directional) Bukkit.createBlockData(Material.BARREL);
        d.setFacing(BlockFace.UP);
        Pos p = L.cell(cx, cz, lx, w, lz);
        map.set(p, d);
        b.containers.add(p);
    }



    // ---- 樓梯 ----

    /**
     * 往上一層的樓梯，每層都疊在同一個位置（像真的樓梯井）。
     * 先用「門在 +x、+z 牆」的標準方向設計，再依房間格實際的門位置鏡像。
     * 樓梯佔 z = 1..2 兩格寬：x = 1 是平台，x = 2..H+1 是 H 階往 +x 爬，最後一階就在上一層的樓板高度。
     * 原版樓梯方塊只能從低的那一側走上去（從側面走會撞到 1 格高的部分），所以第一階前面要留平台；
     * 大家都不能跳，每一階都要靠樓梯方塊的半格高度走上去。
     * 需要 room - 2 >= H + 1（8×8 房間格配 5 格層高剛好）。
     */
    private void stairs(int f, int cx, int cz) {
        int R = L.room;
        int H = L.floorHeight;
        int fy = L.floorY(f);
        int upper = L.floorY(f + 1);
        boolean mx = !L.corridorAfter(cx);
        boolean mz = !L.corridorAfter(cz);

        for (int k = 1; k <= H; k++) {
            int lx = k + 1;
            for (int lane = 1; lane <= 2; lane++) {
                Stairs s = (Stairs) Bukkit.createBlockData(Material.STONE_BRICK_STAIRS);
                s.setHalf(Bisected.Half.BOTTOM);
                s.setFacing(mx ? BlockFace.WEST : BlockFace.EAST);
                map.set(at(cx, cz, lx, fy + k, lane, mx, mz, R), s);
            }
        }
        // 上一層樓板開洞：第 1～H-1 階上方全部挖空。
        // 只挖最上面兩階不夠：人的碰撞箱有 0.6 格寬，走上下一階時身體還壓在上一格的範圍，頭會撞到樓板卡住（實測）。
        for (int k = 1; k <= H - 1; k++) {
            for (int lane = 1; lane <= 2; lane++) {
                map.set(at(cx, cz, k + 1, upper, lane, mx, mz, R), Material.AIR);
            }
        }
        // 上一層洞口旁的矮牆（1 格高；大家都不能跳，所以擋得住），只留樓梯頂端的出口
        for (int k = 1; k <= H - 1; k++) {
            map.set(at(cx, cz, k + 1, upper + 1, 3, mx, mz, R), RAILING);
        }
        // 頂樓沒有再往上的樓梯，平台會直接掉進洞裡，也擋起來
        if (f + 1 == L.floors - 1) {
            for (int lane = 1; lane <= 2; lane++) {
                map.set(at(cx, cz, 1, upper + 1, lane, mx, mz, R), RAILING);
            }
        }
    }

    /**
     * 爬樓梯時頭頂必須是空的位置（給檢查用）。站在第 k 階時腳在 fy+k+1，頭佔 fy+k+1、fy+k+2 兩格；
     * 人的碰撞箱 0.6 格寬，跨到下一階時身體還壓在前一格，所以前一格的同樣高度也要空。
     */
    java.util.List<Pos> stairHeadroom(int f, int cx, int cz) {
        int R = L.room;
        int H = L.floorHeight;
        int fy = L.floorY(f);
        boolean mx = !L.corridorAfter(cx);
        boolean mz = !L.corridorAfter(cz);
        java.util.List<Pos> out = new java.util.ArrayList<>();
        for (int k = 1; k <= H; k++) {
            for (int lx = k; lx <= k + 1; lx++) {
                for (int lane = 1; lane <= 2; lane++) {
                    out.add(at(cx, cz, lx, fy + k + 1, lane, mx, mz, R));
                    out.add(at(cx, cz, lx, fy + k + 2, lane, mx, mz, R));
                }
            }
        }
        return out;
    }

    /** 樓梯底部的平台格和上一層的抵達格（給檢查用），回傳 {底部, 頂端}。 */
    Pos[] stairEnds(int f, int cx, int cz) {
        int R = L.room;
        int H = L.floorHeight;
        boolean mx = !L.corridorAfter(cx);
        boolean mz = !L.corridorAfter(cz);
        Pos bottom = at(cx, cz, 1, L.floorY(f) + 1, 1, mx, mz, R);
        Pos top = at(cx, cz, H + 1, L.floorY(f + 1) + 1, 1, mx, mz, R);
        return new Pos[]{bottom, top};
    }

    private Pos at(int cx, int cz, int lx, int y, int lz, boolean mx, boolean mz, int R) {
        return L.cell(cx, cz, mx ? R - 1 - lx : lx, y, mz ? R - 1 - lz : lz);
    }
}
