package com.ghosthunt.game;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** 3D 模型的小工具（場景物件、大廳展示共用）：資源包 assets/ghosthunt/items/&lt;風格&gt;/&lt;名字&gt;.json。 */
public final class Models {

    private Models() {
    }

    public static String style(Plugin plugin) {
        return plugin.getConfig().getString("models.style", "classic");
    }

    /** 顯示用的物品（紙張換成資源包裡的模型）。 */
    public static ItemStack item(Plugin plugin, String model) {
        ItemStack it = new ItemStack(Material.PAPER);
        ItemMeta meta = it.getItemMeta();
        meta.setItemModel(new NamespacedKey("ghosthunt", style(plugin) + "/" + model));
        it.setItemMeta(meta);
        return it;
    }

    /**
     * 模型包裡大部分模型的正面其實朝南（+Z），只有講台、廣播台朝北（2026-11-23 使用者在遊戲裡看的結果）：
     * 這兩個以外都多轉 180 度。
     */
    public static float flip(String model) {
        return model.startsWith("note_stand") || model.startsWith("pa_desk") ? 0 : 180;
    }

    /** 模型正面轉到朝 f 的 yaw（含 flip 和 config 的 models.yaw-offset）。 */
    public static float yaw(Plugin plugin, BlockFace f, String model) {
        return yaw(plugin, f) + flip(model);
    }

    /** 模型正面（照北 -Z 算）轉到朝 f 的 yaw（再加 config 的 models.yaw-offset）；不含 flip。 */
    public static float yaw(Plugin plugin, BlockFace f) {
        float y = switch (f) {
            case SOUTH -> 180;
            case EAST -> 90;
            case WEST -> -90;
            default -> 0;
        };
        return y + (float) plugin.getConfig().getDouble("models.yaw-offset", 0);
    }

    /**
     * 在方塊 (x, y, z) 那一格放一個模型（腳底在這格的底部）；scale ≠ 1 時照比例放大（模型包為了塞進 Java 範圍縮小過的物件），
     * 底部還是貼地。
     */
    public static ItemDisplay spawn(Plugin plugin, World w, int x, int y, int z, float yaw, String model, float scale, boolean persistent) {
        Location l = new Location(w, x + 0.5, y + 0.5, z + 0.5);
        l.setYaw(yaw);
        l.setPitch(0);
        ItemStack it = item(plugin, model);
        return w.spawn(l, ItemDisplay.class, d -> {
            d.setItemStack(it);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            d.setPersistent(persistent);
            d.setViewRange(1.5f);
            if (scale != 1f) d.setTransformation(new Transformation(new Vector3f(0, (scale - 1) * 0.5f, 0),
                    new AxisAngle4f(), new Vector3f(scale, scale, scale), new AxisAngle4f()));
        });
    }

    /**
     * 場景模型的顯示方式（config models.scene-mode）：block（預設）＝方塊展示實體顯示資源包換過外觀的方塊狀態
     * （模型一定對齊格子、朝向照方塊的 facing 規則，見 tools/make_scene_blocks.py）；item ＝舊的 item display。
     */
    public static boolean blockMode(Plugin plugin) {
        return !"item".equalsIgnoreCase(plugin.getConfig().getString("models.scene-mode", "block"));
    }

    /** 方塊 yaw（實體的朝向，南 0、西 90、北 180、東 -90）最接近的水平方向。 */
    public static BlockFace faceOfYaw(float yaw) {
        int q = Math.floorMod(Math.round(yaw / 90f), 4);
        return switch (q) {
            case 0 -> BlockFace.SOUTH;
            case 1 -> BlockFace.WEST;
            case 2 -> BlockFace.NORTH;
            default -> BlockFace.EAST;
        };
    }

    /**
     * 在方塊 (x, y, z) 那一格放一個方塊模型，正面朝 front；scale ≠ 1 時以底部中心放大（底部還是貼地）。
     * 模型不存在（SceneBlocks 沒有）就回傳 null。
     */
    public static BlockDisplay spawnBlock(World w, int x, int y, int z, BlockFace front, String model, float scale, boolean persistent) {
        BlockData data = SceneBlocks.data(model, front);
        if (data == null) return null;
        Location l = new Location(w, x, y, z);
        return w.spawn(l, BlockDisplay.class, d -> {
            d.setBlock(data);
            d.setPersistent(persistent);
            d.setViewRange(1.5f);
            if (scale != 1f) d.setTransformation(new Transformation(new Vector3f(0.5f - 0.5f * scale, 0, 0.5f - 0.5f * scale),
                    new Quaternionf(), new Vector3f(scale, scale, scale), new Quaternionf()));
        });
    }
}
