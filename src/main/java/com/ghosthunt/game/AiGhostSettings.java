package com.ghosthunt.game;

import org.bukkit.configuration.file.FileConfiguration;

/** AI 鬼的數值（config 的 ai-ghost: 區塊；時間已經換成 tick）。 */
record AiGhostSettings(
        String mob, double baseSpeed, double sightRange, double sightRangeDark, int thinkEvery, int reaction,
        double attackRange, int searchTime, double noiseError, int senseInterval, double senseError,
        double guardJailChance, double checkHideoutChance, double followTunnelChance, int stuckTime,
        double chaseSpeed, double searchSpeed, double patrolSpeed) {

    private static int t(FileConfiguration c, String path, double def) {
        return (int) Math.round(c.getDouble(path, def) * 20);
    }

    static AiGhostSettings from(FileConfiguration c) {
        return new AiGhostSettings(
                c.getString("ai-ghost.mob", "HUSK"),
                c.getDouble("ai-ghost.base-speed", 0.45),
                c.getDouble("ai-ghost.sight-range", 24), c.getDouble("ai-ghost.sight-range-dark", 14),
                Math.max(1, c.getInt("ai-ghost.think-every", 5)), t(c, "ai-ghost.reaction", 0.3),
                c.getDouble("ai-ghost.attack-range", 2.5), t(c, "ai-ghost.search-time", 10),
                c.getDouble("ai-ghost.noise-error", 3), t(c, "ai-ghost.sense-interval", 60), c.getDouble("ai-ghost.sense-error", 8),
                c.getDouble("ai-ghost.guard-jail-chance", 0.5), c.getDouble("ai-ghost.check-hideout-chance", 0.4),
                c.getDouble("ai-ghost.follow-tunnel-chance", 0.5), t(c, "ai-ghost.stuck-time", 3),
                c.getDouble("ai-ghost.chase-speed", 1.0), c.getDouble("ai-ghost.search-speed", 0.9), c.getDouble("ai-ghost.patrol-speed", 0.75));
    }
}
