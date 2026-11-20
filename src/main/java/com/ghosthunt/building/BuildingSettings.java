package com.ghosthunt.building;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;

/** 從 config.yml 讀出跟大樓有關的設定。 */
public record BuildingSettings(
        String worldName,
        int originX, int originY, int originZ,
        int floors, int roomsPerSide, int roomSize, int corridorWidth, int floorHeight,
        int maxDeadEndsPerFloor,
        int gateCount, double gateMinDistance,
        int emergencyLight, int blocksPerTick,
        double doorChance, double corridorClutter, double crawlHoleChance,
        int minContainers,
        String generator, List<String> shape, int cellSize, int hideoutsPerFloor, int crawlTunnelsPerFloor, double toppleChance, int tunnels,
        int barrelArea, boolean pve, int clearSize, int clearFloors) {

    /** 不規則大樓的預設外型（# = 大樓範圍，. = 外面；一格 cell-size × cell-size）。 */
    public static final List<String> DEFAULT_SHAPE = List.of(
            "..#####..",
            "########.",
            "#########",
            "########.",
            ".########",
            ".#######.",
            "....###..");

    /** PvE 小地圖的預設外型。 */
    public static final List<String> DEFAULT_PVE_SHAPE = List.of(
            ".####.",
            "######",
            "#####.",
            "######",
            ".###..");

    public boolean irregular() {
        return !"grid".equalsIgnoreCase(generator);
    }

    public static BuildingSettings from(FileConfiguration c) {
        return from(c, false);
    }

    /** 外型圖的外框邊長（方塊，含外牆）。 */
    private static int shapeSize(List<String> shape, int cell) {
        int w = 0;
        for (String r : shape) w = Math.max(w, r.replace(" ", "").length());
        return Math.max(w, shape.size()) * cell + 1;
    }

    /**
     * pve = true：PvE 小地圖（config 的 pve: 區塊蓋掉外型、層數、暗格／鑽洞／地下通道數量、木桶密度）。
     * clearSize／clearFloors：蓋之前要清空的範圍，取大樓和小地圖比較大的那個（換小地圖時舊大樓不會留下來）。
     */
    public static BuildingSettings from(FileConfiguration c, boolean pve) {
        int cards = c.getInt("keycard.count", 2);
        int cell = c.getInt("map.cell-size", 10);
        List<String> bigShape = c.getStringList("map.shape").isEmpty() ? DEFAULT_SHAPE : c.getStringList("map.shape");
        List<String> pveShape = c.getStringList("pve.shape").isEmpty() ? DEFAULT_PVE_SHAPE : c.getStringList("pve.shape");
        int decoys = c.getInt("keycard.decoy-locked-containers", 3);
        int items = 0;
        ConfigurationSection spawn = c.getConfigurationSection("items.spawn-by-survivors");
        if (spawn != null) {
            for (String key : spawn.getKeys(false)) {
                List<Integer> v = spawn.getIntegerList(key);
                items += v.stream().mapToInt(Integer::intValue).max().orElse(0);
            }
        }
        return new BuildingSettings(
                c.getString("map.world", "ghosthunt_arena"),
                c.getInt("map.origin.x", 0), c.getInt("map.origin.y", 64), c.getInt("map.origin.z", 0),
                pve ? c.getInt("pve.floors", 2) : c.getInt("map.floors", 3),
                c.getInt("map.rooms-per-side", 3),
                c.getInt("map.room-size", 20),
                c.getInt("map.corridor-width", 3),
                c.getInt("map.floor-height", 5),
                c.getInt("map.max-dead-ends-per-floor", 2),
                c.getInt("gate.count", 2),
                c.getDouble("gate.min-distance", 25),
                c.getInt("map.emergency-light-level", 1),
                c.getInt("map.blocks-per-tick", 4000),
                c.getDouble("map.door-chance", 0.5),
                c.getDouble("map.corridor-clutter", 0.06),
                c.getDouble("map.crawl-hole-chance", 0.4),
                cards + decoys + items,
                c.getString("map.generator", "irregular"),
                pve ? pveShape : bigShape,
                c.getInt("map.cell-size", 10),
                pve ? c.getInt("pve.hideouts-per-floor", 2) : c.getInt("map.hideouts-per-floor", 3),
                pve ? c.getInt("pve.crawl-tunnels-per-floor", 2) : c.getInt("map.crawl-tunnels-per-floor", 4),
                c.getDouble("map.topple-cabinet-chance", 0.45),
                pve ? c.getInt("pve.tunnels", 2) : c.getInt("map.tunnels", 4),
                pve ? c.getInt("pve.barrel-area", 90) : c.getInt("map.barrel-area", 140),
                pve,
                Math.max(shapeSize(bigShape, cell), shapeSize(pveShape, cell)),
                Math.max(c.getInt("map.floors", 3), c.getInt("pve.floors", 2)));
    }

    public Layout layout() {
        return new Layout(roomsPerSide, roomSize, corridorWidth, floors, floorHeight, originX, originY, originZ);
    }
}
