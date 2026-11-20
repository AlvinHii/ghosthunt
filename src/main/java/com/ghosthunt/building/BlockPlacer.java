package com.ghosthunt.building;

import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** 分批把方塊放到世界上，每 tick 放一部分，避免伺服器卡頓。 */
public final class BlockPlacer {

    private BlockPlacer() {}

    /**
     * @param onDone 放完後在主執行緒呼叫，參數是花了幾 tick
     */
    public static BukkitTask place(Plugin plugin, World world, BlockMap map, int perTick, Consumer<Integer> onDone) {
        List<Map.Entry<Pos, BlockData>> entries = new ArrayList<>(map.blocks().entrySet());
        // 先讓這片區塊保持載入（之後遊戲進行時也需要）
        Set<Long> chunks = new HashSet<>();
        for (Map.Entry<Pos, BlockData> e : entries) {
            int cx = e.getKey().x() >> 4, cz = e.getKey().z() >> 4;
            if (chunks.add(((long) cx << 32) ^ (cz & 0xffffffffL))) world.addPluginChunkTicket(cx, cz, plugin);
        }
        Iterator<Map.Entry<Pos, BlockData>> it = entries.iterator();
        int[] ticks = {0};
        BukkitTask[] self = new BukkitTask[1];
        self[0] = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            ticks[0]++;
            for (int i = 0; i < perTick && it.hasNext(); i++) {
                Map.Entry<Pos, BlockData> e = it.next();
                Pos p = e.getKey();
                world.getBlockAt(p.x(), p.y(), p.z()).setBlockData(e.getValue(), false);
            }
            if (!it.hasNext()) {
                self[0].cancel();
                onDone.accept(ticks[0]);
            }
        }, 1L, 1L);
        return self[0];
    }
}
