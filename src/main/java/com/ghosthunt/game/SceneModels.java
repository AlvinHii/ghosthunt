package com.ghosthunt.game;

import com.ghosthunt.building.Building;
import com.ghosthunt.building.Pos;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Display;
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
 * 功能方塊換成看不見的屏障（碰撞、右鍵照舊，因為互動都是看位置），同一格放一個 block display 顯示模型
 * （資源包把紅石線的方塊狀態換成我們的模型，所以模型一定對齊格子、朝向照方塊 facing 的規則；
 * config models.scene-mode: item 可以換回舊的 item display）；
 * 每半秒照狀態換模型（讀卡機紅燈／倒數／綠燈、木桶上鎖／翻過、配電箱修好……）。遊戲結束放回原本的方塊。
 * 會動的東西（門、大門、監獄、鐵捲門、下水道鐵柵、地下通道活板門）還是原本的方塊。
 */
final class SceneModels {

    private final Match m;
    private final Building b;
    private final String style;
    private final float yawOffset;
    private final boolean blockMode;

    /** at：狀態用的位置；height：換成屏障的格數（solid 才換）；ox/oy/oz：模型那一格的西北下角（通常就是 at）。 */
    private record Spot(Pos at, int height, BlockFace front, boolean solid, double ox, double oy, double oz) {}

    /** 不在 modelFor 裡的物件（電源、備用電源、迎賓鈴、蒸氣閥門、球車）：位置 → 現在的模型。 */
    private final Map<Pos, java.util.function.Supplier<String>> custom = new HashMap<>();
    /** 球車：點車身任何一格都當成點開關。 */
    private final Map<Pos, Pos> alias = new HashMap<>();

    private final Map<Pos, Spot> spots = new LinkedHashMap<>();
    private final Map<Pos, Display> displays = new HashMap<>();
    private final Map<Pos, String> current = new HashMap<>();
    private final Map<Pos, BlockData> original = new HashMap<>();
    private final Map<ItemFrame, ItemStack> hiddenFrames = new HashMap<>();

    SceneModels(Match m) {
        this.m = m;
        this.b = m.b;
        this.style = m.plugin.getConfig().getString("models.style", "classic");
        this.yawOffset = (float) m.plugin.getConfig().getDouble("models.yaw-offset", 0);
        this.blockMode = Models.blockMode(m.plugin);
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
        for (int i = 0; i < b.cameras.size(); i++) {
            Building.Camera c = b.cameras.get(i);
            Pos obs = c.eye().add(0, 1, 0);
            float yaw = m.side != null ? m.side.camYaw(i) : c.yaw(); // 跟監視器實際看的方向一樣
            if (m.block(obs).getType() == Material.OBSERVER) addAt(obs, 1, Models.faceOfYaw(yaw));
        }
        // 總電源：拉桿換成看不見的絆線鉤（點得到、不擋路），下面那格放 2 格高的電源箱模型（不擋路）
        if (m.obj.lever != null) {
            BlockFace f = hideLever(m.obj.lever);
            if (f != null) {
                Pos at = m.obj.lever.add(0, -1, 0);
                addAt(at, 2, f, false);
                custom.put(at, () -> m.obj.powerOn() ? "power_panel_on" : "power_panel");
            }
        }
        // 雙人備用電源
        if (m.side != null) {
            List<Pos> bl = m.side.backupLevers();
            for (int i = 0; i < bl.size(); i++) {
                BlockFace f = hideLever(bl.get(i));
                if (f == null) continue;
                final int k = i;
                addAt(bl.get(i), 1, f, false);
                custom.put(bl.get(i), () -> m.side.backupUp(k) ? "backup_lever_up" : "backup_lever");
            }
        }
        // 度假村機關
        for (Pos bell : b.bells) {
            addAt(bell, 1, frontFaceLow(bell), true);
            custom.put(bell, () -> "welcome_bell");
        }
        for (Building.Gadget g : b.valves) {
            addAt(g.trigger(), 1, frontFaceLow(g.trigger()), true);
            custom.put(g.trigger(), () -> "steam_valve");
        }
        for (int i = 0; i < b.carts.size(); i++) addCart(i);
        hideFrontDecor();
        tick();
    }

    /** 加一個物件：正面朝旁邊走得到的那一格。 */
    private void add(Pos p, int height) {
        addAt(p, height, frontFace(p));
    }

    private void addAt(Pos p, int height, BlockFace front) {
        addAt(p, height, front, true);
    }

    private void addAt(Pos p, int height, BlockFace front, boolean solid) {
        if (spots.containsKey(p)) return;
        spots.put(p, new Spot(p, height, front, solid, p.x(), p.y(), p.z()));
        if (!solid) return;
        for (int dy = 0; dy < height; dy++) {
            Pos q = p.add(0, dy, 0);
            Block bl = m.block(q);
            if (bl.getType().isAir()) continue;
            original.put(q, bl.getBlockData());
            bl.setType(Material.BARRIER, false);
        }
    }

    /**
     * 正面的方向：先找背後的牆（旁邊是實心、會擋視線的方塊，而且對面那格走得到），背貼牆、正面朝對面；
     * 旁邊都沒有牆（擺在空地上）才朝旁邊走得到的那一格。
     */
    private BlockFace frontFace(Pos p) {
        BlockFace[] faces = {BlockFace.SOUTH, BlockFace.NORTH, BlockFace.EAST, BlockFace.WEST};
        for (BlockFace back : faces) {
            Pos w = p.add(back.getModX(), 0, back.getModZ());
            Pos front = p.add(-back.getModX(), 0, -back.getModZ());
            if (!m.block(w).getType().isOccluding() || spots.containsKey(w)) continue;
            if (walkable(front)) return back.getOppositeFace();
        }
        for (BlockFace f : faces)
            if (walkable(p.add(f.getModX(), 0, f.getModZ()))) return f;
        return BlockFace.SOUTH;
    }

    /** 擺在家具上面的東西（迎賓鈴、蒸氣閥門）：往下找 2 格，看哪一邊站得到人。 */
    private BlockFace frontFaceLow(Pos p) {
        for (int dy = 0; dy <= 2; dy++)
            for (BlockFace f : new BlockFace[]{BlockFace.SOUTH, BlockFace.NORTH, BlockFace.EAST, BlockFace.WEST})
                if (walkable(p.add(f.getModX(), -dy, f.getModZ()))) return f;
        return BlockFace.SOUTH;
    }

    /** 牆上的拉桿換成看不見的絆線鉤（資源包把絆線鉤的外觀換成空的），回傳拉桿朝哪邊；不是牆上的拉桿回傳 null。 */
    private BlockFace hideLever(Pos p) {
        Block bl = m.block(p);
        if (!(bl.getBlockData() instanceof org.bukkit.block.data.type.Switch sw)) return null;
        if (sw.getAttachedFace() != org.bukkit.block.data.FaceAttachable.AttachedFace.WALL) return null;
        BlockFace f = sw.getFacing();
        if (!blockMode) return f; // item display 模式沒有換外觀，拉桿留著
        original.put(p, bl.getBlockData());
        org.bukkit.block.data.type.TripwireHook hook = (org.bukkit.block.data.type.TripwireHook) org.bukkit.Bukkit.createBlockData(Material.TRIPWIRE_HOOK);
        hook.setFacing(f);
        bl.setBlockData(hook, false);
        return f;
    }

    /**
     * 球車（地圖上 2 格高的家具，開關按鈕放在車頂）：整台換成屏障、按鈕拿掉，模型放在整台的正中間，
     * 車頭朝外面最空的那一邊；點車身任何一格都算按開關。
     */
    private void addCart(int i) {
        Building.Gadget g = b.carts.get(i);
        Pos trig = g.trigger();
        Material body = m.block(trig.add(0, -1, 0)).getType();
        if (body.isAir()) return;
        java.util.Set<Pos> foot = new java.util.LinkedHashSet<>();
        java.util.ArrayDeque<Pos> q = new java.util.ArrayDeque<>();
        q.add(trig.add(0, -1, 0));
        foot.add(trig.add(0, -1, 0));
        BlockFace[] faces = {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};
        while (!q.isEmpty()) {
            Pos c = q.poll();
            for (BlockFace f : faces) {
                Pos n = c.add(f.getModX(), 0, f.getModZ());
                if (Math.abs(n.x() - trig.x()) > 3 || Math.abs(n.z() - trig.z()) > 3) continue;
                if (m.block(n).getType() == body && foot.add(n)) q.add(n);
            }
        }
        int base = trig.y() - 2;
        Map<BlockFace, Integer> open = new HashMap<>();
        for (Pos c : foot)
            for (BlockFace f : faces)
                for (int dy = 1; dy <= 3; dy++) {
                    Pos n = c.add(f.getModX(), -dy, f.getModZ());
                    if (foot.contains(n.add(0, dy, 0)) || !walkable(n)) continue;
                    base = n.y();
                    open.merge(f, 1, Integer::sum);
                    break;
                }
        BlockFace front = open.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(BlockFace.SOUTH);
        double sx = 0, sz = 0;
        for (Pos c : foot) {
            sx += c.x();
            sz += c.z();
            for (int y = base; y < trig.y(); y++) {
                Pos cell = new Pos(c.x(), y, c.z());
                Block bl = m.block(cell);
                if (bl.getType().isAir()) continue;
                original.put(cell, bl.getBlockData());
                bl.setType(Material.BARRIER, false);
                alias.put(cell, trig);
            }
        }
        Block button = m.block(trig);
        if (!button.getType().isAir()) {
            original.put(trig, button.getBlockData());
            button.setType(Material.AIR, false);
        }
        spots.put(trig, new Spot(trig, 0, front, true, sx / foot.size(), base, sz / foot.size()));
        custom.put(trig, () -> m.gimmicks != null && m.gimmicks.beamOn(i) ? "golf_cart_high" : "golf_cart");
    }

    /** 點到的格子要當成哪一格（球車車身 → 車頂開關）。 */
    Pos alias(Pos p) {
        return alias.getOrDefault(p, p);
    }

    private boolean walkable(Pos q) {
        for (Set<Pos> tiles : b.walkTiles.values()) if (tiles.contains(q)) return true;
        return false;
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
            if (s.height() < 2 || !s.solid()) continue;
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
            if (blockMode) {
                BlockData data = SceneBlocks.data(model, s.front());
                if (data == null) continue;
                Display d = displays.get(s.at());
                if (d instanceof org.bukkit.entity.BlockDisplay bd && bd.isValid()) bd.setBlock(data);
                else displays.put(s.at(), m.world.spawn(new Location(m.world, s.ox(), s.oy(), s.oz()), org.bukkit.entity.BlockDisplay.class, e -> {
                    e.setBlock(data);
                    e.setPersistent(false);
                    e.setViewRange(1.5f);
                }));
                continue;
            }
            Display old = displays.get(s.at());
            ItemDisplay d = old instanceof ItemDisplay id ? id : null;
            ItemStack it = item(model);
            if (d == null || !d.isValid()) {
                Location l = new Location(m.world, s.ox() + 0.5, s.oy() + 0.5, s.oz() + 0.5);
                l.setYaw(yawOf(s.front()) + Models.flip(model));
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
        java.util.function.Supplier<String> c = custom.get(p);
        if (c != null) return c.get();
        boolean power = m.obj.powerOn();
        if (b.containers.contains(p)) return m.obj.containerModel(p);
        if (b.securityTerminals.contains(p)) return m.obj.terminalModel(p);
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
        for (Display d : displays.values()) if (d != null) d.remove();
        displays.clear();
        for (Map.Entry<Pos, BlockData> e : original.entrySet()) m.block(e.getKey()).setBlockData(e.getValue(), false);
        for (Map.Entry<ItemFrame, ItemStack> e : hiddenFrames.entrySet()) {
            if (!e.getKey().isValid()) continue;
            e.getKey().setVisible(true);
            e.getKey().setItem(e.getValue(), false);
        }
        spots.clear();
        custom.clear();
        alias.clear();
    }

    /** 換成屏障的格子原本會不會擋視線（AI 的視線、手電筒照射照原本的方塊算）。 */
    boolean blocksSight(int x, int y, int z) {
        BlockData o = original.get(new Pos(x, y, z));
        return o != null && o.getMaterial().isOccluding();
    }

    List<String> summary() {
        List<String> out = new ArrayList<>();
        out.add("3D 模型：" + spots.size() + " 個物件（" + style + "，" + (blockMode ? "方塊外觀" : "item display") + "），方塊換成屏障 " + original.size() + " 格");
        return out;
    }
}
