package com.ghosthunt.game;

import com.ghosthunt.building.Building;
import com.ghosthunt.building.Pos;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.ItemFrame;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 場景物件的 3D 模型（資源包 assets/ghosthunt/items/&lt;風格&gt;/，2026-11-23 使用者選經典款）：
 * 功能方塊換成看不見的屏障（碰撞、右鍵照舊，因為互動都是看位置），上面疊一個 item display 顯示模型；
 * 每半秒照狀態換模型（讀卡機紅燈／倒數／綠燈、木桶上鎖／翻過、配電箱修好……）。遊戲結束放回原本的方塊。
 * 會動的東西（門、大門、監獄、鐵捲門、下水道鐵柵、地下通道活板門）還是原本的方塊。
 */
final class SceneModels {

    private final Match m;
    private final Building b;
    private final String style;
    private final float yawOffset;

    private record Spot(Pos at, int height, float yaw) {}

    private final Map<Pos, Spot> spots = new LinkedHashMap<>();
    private final Map<Pos, ItemDisplay> displays = new HashMap<>();
    private final Map<Pos, String> current = new HashMap<>();
    private final Map<Pos, BlockData> original = new HashMap<>();
    private final Map<ItemFrame, ItemStack> hiddenFrames = new HashMap<>();

    SceneModels(Match m) {
        this.m = m;
        this.b = m.b;
        this.style = m.plugin.getConfig().getString("models.style", "classic");
        this.yawOffset = (float) m.plugin.getConfig().getDouble("models.yaw-offset", 0);
    }

    static boolean enabled(Match m) {
        return m.plugin.getConfig().getBoolean("models.scene", true);
    }

    boolean active() {
        return !spots.isEmpty();
    }

    void setup() {
        for (Pos c : b.containers) {
            add(c, 1);
            Block above = m.block(c.add(0, 1, 0));
            if (above.getType() == Material.IRON_TRAPDOOR) above.setType(Material.AIR, false); // 掛鎖改用模型顯示（下一局 setup 會清乾淨）
        }
        for (Pos t : b.securityTerminals) add(t, 1);
        for (Objectives.GateState gs : m.obj.gates) add(gs.g.reader(), 1);
        if (b.radio != null) add(b.radio, 1);
        for (Pos p : b.monitors) add(p, 2);
        for (Pos p : b.paDesks) add(p, 2);
        for (Pos p : b.breakers.keySet()) add(p, 2);
        for (Pos p : b.fusePanels) add(p, 2);
        for (Pos p : b.vendingCells) if (!b.vendingCells.contains(p.add(0, -1, 0))) add(p, 2);
        for (Pos p : b.noteSpots) add(p, 1);
        for (Building.Camera c : b.cameras) {
            Pos obs = c.eye().add(0, 1, 0);
            if (m.block(obs).getType() == Material.OBSERVER) addAt(obs, 1, c.yaw() + 180);
        }
        hideFrontDecor();
        tick();
    }

    /** 加一個物件：正面朝旁邊走得到的那一格。 */
    private void add(Pos p, int height) {
        addAt(p, height, frontYaw(p));
    }

    private void addAt(Pos p, int height, float yaw) {
        if (spots.containsKey(p)) return;
        spots.put(p, new Spot(p, height, yaw));
        for (int dy = 0; dy < height; dy++) {
            Pos q = p.add(0, dy, 0);
            Block bl = m.block(q);
            if (bl.getType().isAir()) continue;
            original.put(q, bl.getBlockData());
            bl.setType(Material.BARRIER, false);
        }
    }

    /** 正面的方向（模型的正面朝北 -Z）：旁邊哪一格走得到就朝哪邊。 */
    private float frontYaw(Pos p) {
        BlockFace[] faces = {BlockFace.SOUTH, BlockFace.NORTH, BlockFace.EAST, BlockFace.WEST};
        for (Set<Pos> tiles : b.walkTiles.values())
            for (BlockFace f : faces) {
                Pos n = p.add(f.getModX(), 0, f.getModZ());
                if (tiles.contains(n)) return yawOf(f);
            }
        return yawOf(BlockFace.SOUTH);
    }

    /** 模型正面朝 f 的 yaw（模型原本朝北）。 */
    private float yawOf(BlockFace f) {
        float y = switch (f) {
            case NORTH -> 0;
            case SOUTH -> 180;
            case EAST -> 90;
            case WEST -> -90;
            default -> 0;
        };
        return y + yawOffset;
    }

    /** 販賣機前面的按鈕和展示框、監視器前面的展示框：模型已經有了，先藏起來（結束時放回）。 */
    private void hideFrontDecor() {
        for (Spot s : spots.values()) {
            if (s.height() < 2) continue;
            Location c = m.loc(s.at()).add(0.5, 1, 0.5);
            for (Entity e : c.getWorld().getNearbyEntities(c, 1.6, 1.6, 1.6)) {
                if (e instanceof ItemFrame f && !hiddenFrames.containsKey(f)) {
                    hiddenFrames.put(f, f.getItem());
                    f.setItem(null, false);
                    f.setVisible(false);
                }
            }
        }
        for (Pos v : b.vendingCells) {
            if (!spots.containsKey(v)) continue;
            for (BlockFace f : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
                Pos n = v.add(f.getModX(), 0, f.getModZ());
                Block bl = m.block(n);
                if (org.bukkit.Tag.BUTTONS.isTagged(bl.getType())) {
                    original.put(n, bl.getBlockData());
                    bl.setType(Material.AIR, false);
                }
            }
        }
    }

    /** 每半秒：照狀態換模型。 */
    void tick() {
        for (Spot s : spots.values()) {
            String model = modelFor(s.at());
            if (model == null || model.equals(current.get(s.at()))) continue;
            current.put(s.at(), model);
            ItemDisplay d = displays.get(s.at());
            ItemStack it = item(model);
            if (d == null || !d.isValid()) {
                Location l = m.loc(s.at()).add(0.5, 0.5, 0.5);
                l.setYaw(s.yaw() + Models.flip(model));
                l.setPitch(0);
                d = m.world.spawn(l, ItemDisplay.class, e -> {
                    e.setItemStack(it);
                    e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                    e.setPersistent(false);
                    e.setViewRange(1.5f);
                });
                displays.put(s.at(), d);
            } else d.setItemStack(it);
        }
    }

    private ItemStack item(String model) {
        ItemStack it = new ItemStack(Material.PAPER);
        ItemMeta meta = it.getItemMeta();
        meta.setItemModel(new NamespacedKey("ghosthunt", style + "/" + model));
        it.setItemMeta(meta);
        return it;
    }

    private String modelFor(Pos p) {
        boolean power = m.obj.powerOn();
        if (b.containers.contains(p)) return m.obj.containerModel(p);
        if (b.securityTerminals.contains(p)) return power ? "security_terminal_on" : "security_terminal";
        for (Objectives.GateState gs : m.obj.gates)
            if (gs.g.reader().equals(p)) {
                if (gs.opened) return "card_reader_open";
                if (!power) return "card_reader";
                return gs.running() ? "card_reader_countdown" : "card_reader_idle";
            }
        if (p.equals(b.radio)) return m.side != null && m.side.heliCalling() ? "radio_calling" : "radio";
        if (b.monitors.contains(p)) return power ? "cctv_monitor_on" : "cctv_monitor";
        if (b.paDesks.contains(p)) return "pa_desk";
        if (b.breakers.containsKey(p)) return m.side != null && m.side.isRepaired(p) ? "breaker_box_fixed" : "breaker_box";
        if (b.fusePanels.contains(p)) {
            int f = m.side == null ? 0 : m.side.fusesInstalled();
            return f >= 2 ? "elevator_panel_2" : f == 1 ? "elevator_panel_1" : "elevator_panel";
        }
        if (b.vendingCells.contains(p)) return power ? "vending_machine" : "vending_machine_off";
        if (b.noteSpots.contains(p)) return m.side != null && m.side.noteAvailable(p) ? "note_stand" : "note_stand_taken";
        return "cctv_camera";
    }

    /** 遊戲結束：拿掉模型，放回原本的方塊和展示框。 */
    void cleanup() {
        for (ItemDisplay d : displays.values()) d.remove();
        displays.clear();
        for (Map.Entry<Pos, BlockData> e : original.entrySet()) m.block(e.getKey()).setBlockData(e.getValue(), false);
        for (Map.Entry<ItemFrame, ItemStack> e : hiddenFrames.entrySet()) {
            if (!e.getKey().isValid()) continue;
            e.getKey().setVisible(true);
            e.getKey().setItem(e.getValue(), false);
        }
        spots.clear();
    }

    /** 換成屏障的格子原本會不會擋視線（AI 的視線、手電筒照射照原本的方塊算）。 */
    boolean blocksSight(int x, int y, int z) {
        BlockData o = original.get(new Pos(x, y, z));
        return o != null && o.getMaterial().isOccluding();
    }

    List<String> summary() {
        List<String> out = new ArrayList<>();
        out.add("3D 模型：" + spots.size() + " 個物件（" + style + "），方塊換成屏障 " + original.size() + " 格");
        return out;
    }
}
