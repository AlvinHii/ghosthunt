package com.ghosthunt.game;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
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

    /** 模型正面（原本朝北 -Z）轉到朝 f 的 yaw（再加 config 的 models.yaw-offset）。 */
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
}
