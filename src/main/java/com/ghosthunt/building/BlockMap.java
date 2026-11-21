package com.ghosthunt.building;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.MultipleFacing;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 在記憶體裡先把整棟大樓「畫」出來，檢查通過後才真的放到世界上。
 * 沒寫到的位置視為空氣。
 */
public final class BlockMap {

    private static final BlockData AIR = Bukkit.createBlockData(Material.AIR);
    private static final List<BlockFace> SIDES = List.of(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST);

    private final Map<Pos, BlockData> blocks = new HashMap<>();
    private final Map<Material, BlockData> cache = new HashMap<>();

    public void set(Pos p, Material m) {
        blocks.put(p, cache.computeIfAbsent(m, Bukkit::createBlockData));
    }

    public void set(Pos p, BlockData data) {
        blocks.put(p, data);
    }

    public BlockData get(Pos p) {
        return blocks.getOrDefault(p, AIR);
    }

    public Material type(Pos p) {
        return get(p).getMaterial();
    }

    /** 填滿長方體（兩角都包含）。 */
    public void fill(Pos a, Pos b, Material m) {
        for (int x = Math.min(a.x(), b.x()); x <= Math.max(a.x(), b.x()); x++)
            for (int y = Math.min(a.y(), b.y()); y <= Math.max(a.y(), b.y()); y++)
                for (int z = Math.min(a.z(), b.z()); z <= Math.max(a.z(), b.z()); z++)
                    set(new Pos(x, y, z), m);
    }

    /** 人可以穿過去的方塊：空氣、看不見的光源方塊、地毯（障礙物記號），門也算（可以打開）；家具正面的按鈕、拉桿沒有碰撞。 */
    public boolean passable(Pos p) {
        Material m = type(p);
        return m.isAir() || m == Material.LIGHT || org.bukkit.Tag.WOOL_CARPETS.isTagged(m) || org.bukkit.Tag.DOORS.isTagged(m)
                || org.bukkit.Tag.WALL_SIGNS.isTagged(m) || org.bukkit.Tag.BUTTONS.isTagged(m) || m == Material.LEVER;
    }

    public boolean solid(Pos p) {
        return !passable(p);
    }

    /**
     * 因為放方塊時關掉物理更新，鐵欄杆、柵欄不會自己接起來，
     * 只剩中間細細一根，人可能擠得過去。這裡手動把它們跟旁邊的欄杆或實心方塊接起來。
     */
    public void connectPanes() {
        for (Map.Entry<Pos, BlockData> e : blocks.entrySet()) {
            Material self = e.getValue().getMaterial();
            boolean bars = self == Material.IRON_BARS, fence = org.bukkit.Tag.FENCES.isTagged(self);
            if (!bars && !fence) continue;
            MultipleFacing mf = (MultipleFacing) Bukkit.createBlockData(self);
            Pos p = e.getKey();
            for (BlockFace f : SIDES) {
                Pos q = p.add(f.getModX(), 0, f.getModZ());
                Material m = type(q);
                boolean same = bars ? m == Material.IRON_BARS : org.bukkit.Tag.FENCES.isTagged(m);
                if (same || (m.isSolid() && m.isOccluding())) {
                    mf.setFace(f, true);
                }
            }
            e.setValue(mf);
        }
    }

    public Map<Pos, BlockData> blocks() {
        return blocks;
    }
}
