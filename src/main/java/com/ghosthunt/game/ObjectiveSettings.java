package com.ghosthunt.game;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 電源、門禁卡、大門、容器、道具的數值（秒換成 tick）。 */
record ObjectiveSettings(
        // 互動
        int search, int pry, int powerOn, int powerRepair, int swipe, int restartGate, int ghostSabotage, int ghostCutPower,
        int coopMax, double coopMultiplier, double noiseRadius,
        // 電源
        int blackoutMax, int blackoutMin, int blackoutMaxDelay, int blackoutGrace, int normalLight,
        // 門禁卡
        int cards, int revealInterval, int revealDuration, int moveChannel, int movesPerCard, int decoys, double droppedCardRadius,
        // 大門
        int openCountdown,
        // 道具
        Map<String, List<Integer>> spawn, int maxCarry, int maxBarricades,
        double smokeRadius, int smokeBlind, double speedBonus, int speedDuration,
        double flashRange, double flashAngle, int flashBattery, int flashDisrupt, int scannerDuration,
        // 蹲伏破洞
        int widenHole,
        // 卡門、門禁卡授權
        int jamDoor, int breakJam, int authorize, boolean requireAuth) {

    private static int t(FileConfiguration c, String path, double def) {
        return (int) Math.round(c.getDouble(path, def) * 20);
    }

    static ObjectiveSettings from(FileConfiguration c) {
        List<Integer> window = c.getIntegerList("power.random-blackout-window");
        if (window.size() < 2) window = List.of(300, 600);
        Map<String, List<Integer>> spawn = new HashMap<>();
        // 設定檔的鍵名 → 道具 id
        Map<String, String> keys = Map.of("smoke", Items.SMOKE, "speed-potion", Items.SPEED, "flashlight", Items.FLASHLIGHT,
                "scanner", Items.SCANNER, "barricade", Items.BARRICADE);
        ConfigurationSection sec = c.getConfigurationSection("items.spawn-by-survivors");
        Map<String, List<Integer>> defaults = Map.of(Items.SMOKE, List.of(3, 4, 5), Items.SPEED, List.of(3, 4, 5),
                Items.FLASHLIGHT, List.of(2, 2, 3), Items.SCANNER, List.of(2, 3, 3), Items.BARRICADE, List.of(2, 3, 4));
        for (Map.Entry<String, String> e : keys.entrySet()) {
            List<Integer> v = sec == null ? List.of() : sec.getIntegerList(e.getKey());
            spawn.put(e.getValue(), v.isEmpty() ? defaults.get(e.getValue()) : v);
        }
        return new ObjectiveSettings(
                t(c, "interaction.search-container", 2), t(c, "interaction.pry-locked-container", 5),
                t(c, "interaction.power-on", 5), t(c, "interaction.power-repair", 6), t(c, "interaction.swipe-card", 3),
                t(c, "interaction.restart-gate", 3), t(c, "gate.ghost-sabotage-time", 4), t(c, "ghost.cut-power-time", 3),
                2, c.getDouble("interaction.team-power-multiplier", 1.5), c.getDouble("interaction.noise-radius", 16),
                c.getInt("power.random-blackout-max", 1), window.get(0) * 20, window.get(1) * 20, t(c, "power.blackout-grace", 90),
                c.getInt("map.normal-light-level", 10),
                c.getInt("keycard.count", 2), t(c, "keycard.reveal-interval", 90), t(c, "keycard.reveal-duration", 5),
                t(c, "keycard.ghost-move-channel", 4), c.getInt("keycard.ghost-moves-per-card", 1),
                c.getInt("keycard.decoy-locked-containers", 3), c.getDouble("jail.dropped-card-radius", 15),
                t(c, "gate.open-countdown", 25),
                spawn, c.getInt("items.max-carry", 1), c.getInt("items.max-barricades", 2),
                c.getDouble("items.smoke-radius", 4), t(c, "items.smoke-blind", 3),
                c.getDouble("items.speed-potion-bonus", 0.4), t(c, "items.speed-potion-duration", 5),
                c.getDouble("items.flashlight-range", 8), c.getDouble("items.flashlight-angle", 30),
                t(c, "items.flashlight-battery", 20), t(c, "items.flashlight-disrupt", 5), t(c, "items.scanner-duration", 5),
                t(c, "ghost.widen-crawl-hole", 4),
                t(c, "interaction.jam-door", 1), t(c, "ghost.break-door-jam", 2.5), t(c, "interaction.authorize-card", 5),
                c.getBoolean("keycard.require-authorization", true));
    }

    int maxOf(String itemId) {
        return Items.BARRICADE.equals(itemId) ? maxBarricades : maxCarry;
    }
}
