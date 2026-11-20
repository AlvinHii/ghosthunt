package com.ghosthunt;

import org.bukkit.Bukkit;
import org.bukkit.GameRules;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;

import java.util.Random;

/** 遊戲用的虛空世界：什麼都沒有，大樓每局重新蓋在這裡。 */
public final class ArenaWorld {

    private ArenaWorld() {}

    public static World getOrCreate(String name) {
        World w = Bukkit.getWorld(name);
        if (w == null) {
            w = new WorldCreator(name)
                    .generator(new VoidGenerator())
                    .generateStructures(false)
                    .createWorld();
        }
        if (w != null) {
            w.setGameRule(GameRules.SPAWN_MOBS, false);
            w.setGameRule(GameRules.SPAWN_MONSTERS, false);
            w.setGameRule(GameRules.ADVANCE_TIME, false);
            w.setGameRule(GameRules.ADVANCE_WEATHER, false);
            w.setGameRule(GameRules.FALL_DAMAGE, false);
            w.setGameRule(GameRules.PVP, true);            // 鬼要打得到人
            w.setGameRule(GameRules.LOCATOR_BAR, false);   // 經驗條上的玩家方向會洩漏位置
            w.setGameRule(GameRules.IMMEDIATE_RESPAWN, true);
            w.setTime(18000); // 半夜
            w.setStorm(false);
        }
        return w;
    }

    static final class VoidGenerator extends ChunkGenerator {
        @Override public boolean shouldGenerateNoise() { return false; }
        @Override public boolean shouldGenerateSurface() { return false; }
        @Override public boolean shouldGenerateCaves() { return false; }
        @Override public boolean shouldGenerateDecorations() { return false; }
        @Override public boolean shouldGenerateMobs() { return false; }
        @Override public boolean shouldGenerateStructures() { return false; }

        @Override
        public org.bukkit.Location getFixedSpawnLocation(World world, Random random) {
            return new org.bukkit.Location(world, 0, 65, 0);
        }

        @Override
        public int getBaseHeight(WorldInfo worldInfo, Random random, int x, int z, org.bukkit.HeightMap heightMap) {
            return 64;
        }
    }
}
