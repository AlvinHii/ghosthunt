package com.ghosthunt.game;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;

/** 對局用的數值，全部從 config.yml 讀（秒換成 tick）。 */
public record GameSettings(
        int releaseDelay, List<Double> ghostSpeedBySurvivors, int captureCooldown, int breakBarricade,
        int attackFatigue, double fatigueSlow,
        int dashDuration, double dashBonus, int dashCooldown,
        List<Integer> rescueBySurvivors, double campRadius, int campTime, int campRescue, int campRecover,
        int rescueBoostTime, double rescueBoostBonus, int rescueProtection, int maxCaptures,
        int nmFirstUse, int nmDuration, int nmCooldown, double nmTrailRadius, int nmIdleHint, double nmWhisperRadius,
        String nmFog, String normalFog,
        int glowStart, int timeLimit, int emergencyLight, double holeSpeedBonus) {

    private static int t(FileConfiguration c, String path, double def) {
        return (int) Math.round(c.getDouble(path, def) * 20);
    }

    public static GameSettings from(FileConfiguration c) {
        List<Double> speed = c.getDoubleList("ghost.speed-bonus-by-survivors");
        if (speed.isEmpty()) speed = List.of(0.03, 0.05, 0.07);
        List<Integer> rescue = c.getIntegerList("jail.rescue-time-by-survivors");
        if (rescue.isEmpty()) rescue = List.of(4, 5, 6);
        return new GameSettings(
                t(c, "ghost.release-delay", 30), speed, t(c, "ghost.capture-cooldown", 3), t(c, "ghost.break-barricade", 3.5),
                t(c, "ghost.attack-fatigue", 1.5), c.getDouble("ghost.attack-fatigue-slow", 0.9),
                t(c, "survivor.dash-duration", 1.5), c.getDouble("survivor.dash-speed-bonus", 0.5), t(c, "survivor.dash-cooldown", 45),
                rescue, c.getDouble("jail.camp-radius", 12), t(c, "jail.camp-time", 10), t(c, "jail.camp-rescue-time", 2),
                t(c, "jail.camp-recover-time", 10),
                t(c, "jail.rescue-speed-boost", 3), c.getDouble("jail.rescue-speed-bonus", 0.4), t(c, "jail.rescue-protection", 3),
                c.getInt("jail.max-captures", 2),
                t(c, "nightmare.first-use-delay", 60), t(c, "nightmare.lights-out-duration", 90), t(c, "nightmare.lights-out-cooldown", 150),
                c.getDouble("nightmare.trail-radius", 12), t(c, "nightmare.idle-hint-after", 15), c.getDouble("nightmare.whisper-radius", 8),
                c.getString("nightmare.fog-distance", "8"), c.getString("map.fog-distance", "off"),
                t(c, "match.glow-start", 900), t(c, "match.time-limit", 1020), c.getInt("map.emergency-light-level", 1),
                c.getDouble("survivor.crawl-hole-speed-bonus", 0.3));
    }

    /** 依逃生者人數取值：3 人用第 1 個，4 人第 2 個，5 人以上第 3 個（少於 3 人也用第 1 個）。 */
    public static <T> T bySurvivors(List<T> list, int survivors) {
        int i = Math.max(0, Math.min(list.size() - 1, survivors - 3));
        return list.get(i);
    }
}
