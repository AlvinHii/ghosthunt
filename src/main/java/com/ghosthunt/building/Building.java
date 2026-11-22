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

    /** 不規則大樓的一間房間（俯視圖、/ghosthunt info 用）。tiles 是走路高度的位置。 */
    public record Room(int floor, RoomType type, Pos center, int area, boolean hideout) {}

    /** 不規則大樓的所有房間（舊的九宮格大樓是空的，用 types）。 */
    public final List<Room> rooms = new ArrayList<>();
    /** 每層的平面圖（俯視圖用）：一格一個字元。 */
    public final Map<Integer, List<String>> plans = new HashMap<>();
    /** 保全室中心。 */
    public final List<Pos> securityRooms = new ArrayList<>();
    /** 保全室的終端機（門禁卡授權用）。 */
    public final List<Pos> securityTerminals = new ArrayList<>();

    /**
     * 可以推倒的櫃子：blocks 是櫃子本身（2 格高）和原本的方塊，barricade 是推倒後擋住的門口（b.barricadeSpots 的編號）。
     */
    public record ToppleSpot(Map<Pos, org.bukkit.block.data.BlockData> blocks, int barricade) {}

    public final List<ToppleSpot> toppleSpots = new ArrayList<>();

    /**
     * 地下通道的出入口：1F 地板上的活板門（關著時跟地板一樣平），下面是梯子通到地下通道。
     * trapdoor 是活板門的位置和原本（關著）的方塊，shaftTop 是梯子最上面那格（活板門正下方）。
     */
    public record Hatch(Pos trapdoor, org.bukkit.block.data.BlockData closed, Pos shaftTop, org.bukkit.block.data.BlockData ladder) {}

    public final List<Hatch> hatches = new ArrayList<>();
    /** 地下通道的條數（info 用）。 */
    public int tunnels;
    /** 雙開門原本的方塊（上下兩半，關著的），每局開始時復原。 */
    public final Map<Pos, org.bukkit.block.data.BlockData> doorBlocks = new HashMap<>();

    /** 可以放障礙物的門口：blocks 是放障礙物時要填滿的 4 格，markers 是地上的黃色記號。 */
    public record BarricadeSpot(List<Pos> blocks, List<Pos> markers) {}

    public final List<BarricadeSpot> barricadeSpots = new ArrayList<>();
    /** 走廊上的告示牌和要寫的字（蓋好後才寫得上去）。 */
    public final Map<Pos, String[]> signs = new HashMap<>();
    /** 固定地圖：每個告示牌寫的是哪個區域（樓層 × 100000 ＋ 區域編號）；開局時把電源所在的房間標出來用。 */
    public final Map<Pos, Integer> signRegion = new HashMap<>();
    /** 固定地圖：每個電源候選位置在哪個區域。 */
    public final Map<Pos, Integer> powerCandidateRegion = new HashMap<>();
    /** 雙開門的下半部位置。 */
    public final List<Pos> doors = new ArrayList<>();
    /** 鬼房門口原本的方塊（每局開始時重新封起來用）。 */
    public final Map<Pos, org.bukkit.block.data.BlockData> ghostDoorBlocks = new HashMap<>();
    /** 兩扇大門開口原本的方塊（鐵欄杆），每局開始時封回去。 */
    public final Map<Pos, org.bukkit.block.data.BlockData> gateBlocks = new HashMap<>();
    /** 上一局放的總電源拉桿（下一局開始時拿掉）。 */
    public Pos placedLever;

    public final long seed;
    /** PvE 小地圖。 */
    public boolean pve;
    /** 固定地圖的名字（plugins/GhostHunt/pve-maps/名字.txt）；隨機產生的大樓是 null。 */
    public String mapName;
    /** 固定地圖的 [sounds]（key, value）；沒有就是空的。 */
    public List<String[]> sounds = List.of();
    /** 地圖機關的一個裝置：trigger＝玩家右鍵的方塊（鐘、按鈕、拉桿），center＝裝置中心（走路高度）。 */
    public record Gadget(Pos trigger, Pos center) {}
    /** 迎賓鈴（右鍵→遠處的聲源響起，吸引鬼）。 */
    public final List<Pos> bells = new ArrayList<>();

    // ---- 支線（v2 地圖的新符號；格子座標都是「那一格地面上一格」的世界座標） ----
    /** L：貨梯廂的地板格。 */
    public final List<Pos> elevatorCells = new ArrayList<>();
    /** F：貨梯的配電盤（下面那格的方塊）。 */
    public final List<Pos> fusePanels = new ArrayList<>();
    /** P：停機坪的格子。 */
    public final List<Pos> helipadCells = new ArrayList<>();
    /** R：無線電（方塊）。 */
    public Pos radio;
    /** X：下水道鐵柵的格子。 */
    public final List<Pos> sewerCells = new ArrayList<>();
    /** N：密碼紙條候選（講台）。 */
    public final List<Pos> noteSpots = new ArrayList<>();
    /** M：監控室螢幕候選（下面那格的方塊）。 */
    public final List<Pos> monitors = new ArrayList<>();
    /** Y：攝影機（眼睛的位置、朝向、名稱）。 */
    public record Camera(Pos eye, float yaw, String name) {}
    public final List<Camera> cameras = new ArrayList<>();
    /** A：廣播台候選。 */
    public final List<Pos> paDesks = new ArrayList<>();
    /** Z：區域配電箱（下面那格的方塊）和它管的區域（樓層 × 100000 ＋ 區域編號）。 */
    public final Map<Pos, Integer> breakers = new HashMap<>();
    /** 每盞燈在哪個區域（配電箱修好時只亮那一區）。 */
    public final Map<Pos, Integer> lightRegion = new HashMap<>();
    /** 每個區域的名稱（告示牌、攝影機、廣播用）和中心。 */
    public final Map<Integer, String> regionNames = new HashMap<>();
    public final Map<Integer, Pos> regionCenters = new HashMap<>();
    /** T＋V：鐵捲門（鐵門，下半那格）和開關（拉桿）。 */
    public record Shutter(List<Pos> doors, Pos lever) {}
    public final List<Shutter> shutters = new ArrayList<>();
    /** U：上鎖的門（鐵門，下半那格），要屋頂鑰匙。 */
    public final List<Pos> lockedDoors = new ArrayList<>();
    /** 自動販賣機（家具 m）的方塊：投硬幣換道具。 */
    public final java.util.Set<Pos> vendingCells = new java.util.HashSet<>();
    /** 家具的裝飾方塊（書架、櫃子、按鈕、拉桿、椅背活板門……）：逃生者、鬼右鍵沒有反應。 */
    public final java.util.Set<Pos> decor = new java.util.HashSet<>();
    /** 家具正面的物品展示框（蓋完方塊以後才生成；固定、不能拿、不能打掉）。 */
    public record DecorFrame(Pos at, org.bukkit.block.BlockFace facing, boolean glow, org.bukkit.Material item) {}
    public final List<DecorFrame> decorFrames = new ArrayList<>();
    /** 迎賓鈴的聲源位置（例如涼亭）。 */
    public final List<Pos> lureSpots = new ArrayList<>();
    /** 球車遠光燈（按鈕→前方扇形強光，鬼看不到人＋緩速；每台次數有限）。 */
    public final List<Gadget> carts = new ArrayList<>();
    /** 蒸氣閥門（拉桿→周圍一段時間濃煙，鬼在煙裡看不到人）。 */
    public final List<Gadget> valves = new ArrayList<>();
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
