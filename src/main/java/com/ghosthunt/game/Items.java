package com.ghosthunt.game;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.List;

/** 遊戲用的特殊物品，用 PersistentDataContainer 標記種類。 */
public final class Items {

    // 鬼
    public static final String LIGHTS_OUT = "lights_out";
    public static final String MOVE_CARD = "move_card";
    // 逃生者
    public static final String BARRICADE = "barricade";
    public static final String SMOKE = "smoke";
    public static final String SPEED = "speed";
    public static final String FLASHLIGHT = "flashlight";
    public static final String SCANNER = "scanner";
    /** 門禁卡：keycard_1、keycard_2。 */
    public static final String KEYCARD = "keycard_";

    /** 地圖上可以撿到的道具（障礙物也會出現在容器裡）。 */
    public static final List<String> LOOT = List.of(SMOKE, SPEED, FLASHLIGHT, SCANNER, BARRICADE);

    private static NamespacedKey key;

    private Items() {}

    public static void init(Plugin plugin) {
        key = new NamespacedKey(plugin, "item");
    }

    public static ItemStack lightsOut() {
        return make(Material.ECHO_SHARD, LIGHTS_OUT, "熄燈", NamedTextColor.DARK_PURPLE,
                "右鍵：關掉整棟樓的燈", "關燈期間看得到附近逃生者的移動軌跡");
    }

    public static ItemStack moveCard() {
        return make(Material.ENDER_EYE, MOVE_CARD, "搬卡", NamedTextColor.DARK_AQUA,
                "門禁卡位置亮起時右鍵（原地站 4 秒）", "把離你最近的那張卡搬到離逃生者最遠的箱子，並鎖起來");
    }

    public static ItemStack keycard(int n) {
        return make(Material.TRIAL_KEY, KEYCARD + n, "門禁卡 #" + n, NamedTextColor.AQUA,
                "拿著它右鍵大門旁的讀卡機（要先開電源）", "刷了就會用掉");
    }

    public static ItemStack of(String id) {
        return switch (id) {
            case BARRICADE -> barricade(1);
            case SMOKE -> make(Material.SNOWBALL, SMOKE, "煙霧彈", NamedTextColor.GRAY,
                    "右鍵丟出去：附近的鬼短暫失明", "也能打斷鬼正在做的事（例如弄壞大門）");
            case SPEED -> make(Material.SUGAR, SPEED, "加速藥水", NamedTextColor.GREEN,
                    "右鍵：幾秒內跑比較快", "不能跟衝刺或其他加速疊加");
            case FLASHLIGHT -> make(Material.BLAZE_ROD, FLASHLIGHT, "手電筒", NamedTextColor.YELLOW,
                    "右鍵開關：照亮前方，電量有限", "照到夢魘會打斷牠的追蹤，但也可能讓鬼看到你");
            case SCANNER -> make(Material.COMPASS, SCANNER, "掃描器", NamedTextColor.BLUE,
                    "右鍵：顯示最近的門禁卡（電源沒開時是電源開關）方向", "使用時會發出聲音");
            default -> null;
        };
    }

    public static ItemStack barricade(int amount) {
        ItemStack it = make(Material.SPRUCE_PLANKS, BARRICADE, "障礙物", NamedTextColor.GOLD,
                "右鍵門口地上的黃色記號：把門口擋起來", "鬼要花時間才能拆掉，拆的時候很大聲");
        it.setAmount(amount);
        return it;
    }

    public static String label(String id) {
        if (id.startsWith(KEYCARD)) return "門禁卡";
        return switch (id) {
            case BARRICADE -> "障礙物";
            case SMOKE -> "煙霧彈";
            case SPEED -> "加速藥水";
            case FLASHLIGHT -> "手電筒";
            case SCANNER -> "掃描器";
            default -> id;
        };
    }

    private static ItemStack make(Material m, String id, String name, NamedTextColor color, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Component.text(name, color).decoration(TextDecoration.ITALIC, false));
        meta.lore(java.util.Arrays.stream(lore)
                .map(l -> (Component) Component.text(l, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false))
                .toList());
        meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, id);
        it.setItemMeta(meta);
        return it;
    }

    public static String id(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
    }

    public static boolean is(ItemStack it, String id) {
        return id.equals(id(it));
    }

    /** 玩家身上某種道具有幾個。 */
    public static int count(Player p, String id) {
        int n = 0;
        for (ItemStack it : p.getInventory().getContents()) if (it != null && id.equals(id(it))) n += it.getAmount();
        return n;
    }

    /** 從玩家身上拿走一個。 */
    public static boolean take(Player p, String id) {
        ItemStack[] contents = p.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack it = contents[i];
            if (it != null && id.equals(id(it))) {
                it.setAmount(it.getAmount() - 1);
                p.getInventory().setItem(i, it.getAmount() <= 0 ? null : it);
                return true;
            }
        }
        return false;
    }

    /** 玩家身上的門禁卡編號（沒有回傳 0）。 */
    public static int keycardOn(Player p) {
        for (ItemStack it : p.getInventory().getContents()) {
            String id = id(it);
            if (id != null && id.startsWith(KEYCARD)) return Integer.parseInt(id.substring(KEYCARD.length()));
        }
        return 0;
    }
}
