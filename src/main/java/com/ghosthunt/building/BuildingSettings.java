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
        int minContainers) {

    public static BuildingSettings from(FileConfiguration c) {
        int cards = c.getInt("keycard.count", 2);
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
                c.getInt("map.floors", 3),
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
                cards + decoys + items);
    }

    public Layout layout() {
        return new Layout(roomsPerSide, roomSize, corridorWidth, floors, floorHeight, originX, originY, originZ);
    }
}
