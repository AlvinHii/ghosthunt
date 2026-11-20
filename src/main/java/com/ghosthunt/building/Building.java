package com.ghosthunt.building;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 蓋好的大樓，以及之後的遊戲系統要用到的位置（容器、監獄、大門、燈……）。
 */
public final class Building {

    /** 一扇大門：走廊通到外牆的末端。 */
    public record Gate(int floor, Pos center, List<Pos> opening, Pos reader, Pos exit) {}

    /** 一個房間格。 */
    public record Cell(int floor, int cx, int cz, RoomType type) {}

    /**
     * 蹲伏破洞：牆上 1.5 格高的洞（下面一格空、上面放上半磚）。逃生者蹲下鑽得過，鬼會被插件擋住；
     * 鬼可以花時間把洞打大（拿掉半磚），之後大家都能走。
     */
    public record CrawlHole(Pos lower, Pos slab, org.bukkit.block.data.BlockData slabData) {}

    public final List<CrawlHole> crawlHoles = new ArrayList<>();

    /** 可以放障礙物的門口：blocks 是放障礙物時要填滿的 4 格，markers 是地上的黃色記號。 */
    public record BarricadeSpot(List<Pos> blocks, List<Pos> markers) {}

    public final List<BarricadeSpot> barricadeSpots = new ArrayList<>();
    /** 走廊上的告示牌和要寫的字（蓋好後才寫得上去）。 */
    public final Map<Pos, String[]> signs = new HashMap<>();
    /** 雙開門的下半部位置。 */
    public final List<Pos> doors = new ArrayList<>();
    /** 鬼房門口原本的方塊（每局開始時重新封起來用）。 */
    public final Map<Pos, org.bukkit.block.data.BlockData> ghostDoorBlocks = new HashMap<>();
    /** 兩扇大門開口原本的方塊（鐵欄杆），每局開始時封回去。 */
    public final Map<Pos, org.bukkit.block.data.BlockData> gateBlocks = new HashMap<>();
    /** 上一局放的總電源拉桿（下一局開始時拿掉）。 */
    public Pos placedLever;

    public final long seed;
    public final Layout layout;
    public final RoomType[][][] types; // [floor][cx][cz]

    public final List<Pos> containers = new ArrayList<>();
    public final List<Pos> powerCandidates = new ArrayList<>();
    public final Map<Integer, Pos> jailCages = new HashMap<>();
    public final List<Pos> ghostDoors = new ArrayList<>();
    public Pos ghostRoomCenter;
    public Cell ghostRoom;
    public final List<Gate> gateCandidates = new ArrayList<>();
    public final List<Gate> gates = new ArrayList<>();
    public final List<Pos> lights = new ArrayList<>();
    public final List<Cell> stairwells = new ArrayList<>();
    /** 每層可以站的位置（排除樓梯間、監獄、鬼房），之後挑出生點用。 */
    public final Map<Integer, Set<Pos>> spawnTiles = new HashMap<>();
    /** 每層所有走得到的格子（含樓梯間、監獄），模擬／匯出用。 */
    public final Map<Integer, Set<Pos>> walkTiles = new HashMap<>();
    /** 樓梯：{下層平台, 上層抵達點}。 */
    public final List<Pos[]> stairLinks = new ArrayList<>();

    public Building(long seed, Layout layout) {
        this.seed = seed;
        this.layout = layout;
        this.types = new RoomType[layout.floors][layout.n][layout.n];
    }

    public RoomType type(int f, int cx, int cz) {
        return types[f][cx][cz];
    }
}
