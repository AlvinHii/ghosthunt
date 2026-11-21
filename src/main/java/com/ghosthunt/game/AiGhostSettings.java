package com.ghosthunt.game;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * AI 鬼的數值（config 的 ai-ghost: 區塊；時間已經換成 tick）。
 * 難度（easy／normal／hard）是 ai-ghost.presets 底下的一組覆寫值，只調感知和決策，不調跑速。
 */
record AiGhostSettings(
        String difficulty, String mob, double baseSpeed, double sightRange, double sightRangeDark, double fovDegrees, double nearAware,
        int thinkEvery, int reaction, double attackRange, double whiffChance,
        int searchTime, int searchCap, double noiseError, double hearSprint, double hearWalk, double hearDoor,
        int senseQuiet, int senseGap, double senseError, double pressureLimit, int calmTime,
        double guardJailChance, int guardCap, double checkHideoutChance, double checkHideoutNoiseChance, double followTunnelChance,
        int stuckTime, int powerCutDelay,
        int flashTime, int flashBlind, int flashImmune,
        double chaseSpeed, double searchSpeed, double patrolSpeed,
        int particles, double particleFlee, double particleGoal, double particleSigma, int particleMin, int particleMaxAge,
        double escaperSpeed,
        double interceptChance, int ambushTime, int guardReaderCap, double cutPowerAdvantage, int cutPowerMaxWait) {

    /** 依難度讀：先看 ai-ghost.presets.<難度>.<key>，沒有再看 ai-ghost.<key>。 */
    private static double d(FileConfiguration c, ConfigurationSection preset, String key, double def) {
        if (preset != null && preset.contains(key)) return preset.getDouble(key);
        return c.getDouble("ai-ghost." + key, def);
    }

    private static int t(FileConfiguration c, ConfigurationSection preset, String key, double def) {
        return (int) Math.round(d(c, preset, key, def) * 20);
    }

    static AiGhostSettings from(FileConfiguration c) {
        return from(c, c.getString("ai-ghost.difficulty", "normal"));
    }

    static AiGhostSettings from(FileConfiguration c, String difficulty) {
        ConfigurationSection p = c.getConfigurationSection("ai-ghost.presets." + difficulty);
        return new AiGhostSettings(
                p == null ? "normal" : difficulty,
                c.getString("ai-ghost.mob", "HUSK"),
                d(c, p, "base-speed", 0.45),
                d(c, p, "sight-range", 24), d(c, p, "sight-range-dark", 14), d(c, p, "fov", 120), d(c, p, "near-aware", 3),
                Math.max(1, (int) d(c, p, "think-every", 5)), t(c, p, "reaction", 0.3), d(c, p, "attack-range", 2.5),
                d(c, p, "whiff-chance", 0.3),
                t(c, p, "search-time", 10), t(c, p, "search-cap", 25), d(c, p, "noise-error", 3),
                d(c, p, "hear-sprint", 8), d(c, p, "hear-walk", 4), d(c, p, "hear-door", 12),
                t(c, p, "sense-quiet", 45), t(c, p, "sense-gap", 45), d(c, p, "sense-error", 8),
                d(c, p, "pressure-limit", 60), t(c, p, "calm-time", 30),
                d(c, p, "guard-jail-chance", 0.5), t(c, p, "guard-cap", 45),
                d(c, p, "check-hideout-base", d(c, p, "check-hideout-chance", 0.4)), d(c, p, "check-hideout-noise-chance", 0.2), d(c, p, "follow-tunnel-chance", 0.5),
                t(c, p, "stuck-time", 2), t(c, p, "power-cut-delay", 20),
                t(c, p, "flash-time", 1.0), t(c, p, "flash-blind", 1.5), t(c, p, "flash-immune", 12),
                d(c, p, "chase-speed", 1.0), d(c, p, "search-speed", 0.9), d(c, p, "patrol-speed", 0.75),
                (int) d(c, p, "particles", 200), d(c, p, "particle-flee", 0.6), d(c, p, "particle-goal", 0.25),
                d(c, p, "particle-sigma", 3), (int) d(c, p, "particle-min", 10), t(c, p, "particle-max-age", 40),
                d(c, p, "escaper-speed", 5.6),
                d(c, p, "intercept-chance", 0.5), t(c, p, "ambush-time", 25), t(c, p, "guard-reader-cap", 40),
                d(c, p, "cut-power-advantage", 15), t(c, p, "cut-power-max-wait", 90));
    }
}
