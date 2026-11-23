package com.ghosthunt;

import com.ghosthunt.game.Items;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.FaceAttachable;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Switch;
import org.bukkit.block.data.type.TrapDoor;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.GlowItemFrame;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 遊戲大廳（2026-11-23）：玩家進伺服器自動傳送過來。
 * <ul>
 *   <li>北牆：設定（模式、地圖、AI 難度、AI 個性、小鬼、PvP 的鬼和鬼的種類），告示牌兩邊的按鈕切換；所有人都能改，改了大家的準備會取消。上面一排道具展示框。</li>
 *   <li>西牆：準備按鈕和準備狀況。大廳裡的人全部準備好就倒數，倒數完自動蓋地圖開局；有人取消就停。</li>
 *   <li>東牆、南牆、西牆：功能方塊的展示，告示牌寫名字和一句話，右鍵方塊或告示牌在聊天窗看完整說明。</li>
 * </ul>
 * 大廳在自己的虛空世界（lobby.world），每次插件啟動照程式重蓋一次（改了這裡的格局重開就更新）。遊戲結束後所有人回到大廳。
 */
public final class Lobby implements Listener {

    /** 房間：x -18～18、z -12～12 是牆，地板 y=64，室內 65～70，天花板 71。 */
    private static final int HX = 18, HZ = 12, FLOOR = 64, CEIL = 71;

    private final GhostHuntPlugin plugin;
    private World world;

    // ---- 設定 ----
    private String mode = "pve", map, diff = "normal", persona = "auto", ghost = "random", gtype = "nightmare";
    private boolean imp;

    private enum Setting { MODE, MAP, DIFF, PERSONA, IMP, GHOST, GTYPE }

    private final Map<Setting, Block> settingSigns = new LinkedHashMap<>();
    private final Map<Long, Runnable> buttons = new HashMap<>();
    private final Map<Long, String> exhibitAt = new HashMap<>();
    private final Map<UUID, String> frameItem = new HashMap<>();
    private Block readySign;
    private final Set<UUID> ready = new HashSet<>();
    private final Map<UUID, Long> lastClick = new HashMap<>();
    private int countdown = -1;
    private boolean starting;
    private long startingSince;
    private BukkitTask task;

    Lobby(GhostHuntPlugin plugin) {
        this.plugin = plugin;
        FileConfiguration c = plugin.getConfig();
        map = c.getString("pve.map", "mall2");
        imp = c.getBoolean("imp.enabled", true);
    }

    static boolean enabled(GhostHuntPlugin plugin) {
        return plugin.getConfig().getBoolean("lobby.enabled", true);
    }

    World world() {
        return world;
    }

    // =====================================================================
    // 世界、蓋大廳
    // =====================================================================

    void start() {
        String name = plugin.getConfig().getString("lobby.world", "ghosthunt_lobby");
        world = Bukkit.getWorld(name);
        if (world == null) world = new WorldCreator(name).generator(new ArenaWorld.VoidGenerator()).generateStructures(false).createWorld();
        if (world == null) {
            plugin.getLogger().warning("大廳世界建立失敗");
            return;
        }
        world.setGameRule(GameRules.SPAWN_MOBS, false);
        world.setGameRule(GameRules.SPAWN_MONSTERS, false);
        world.setGameRule(GameRules.ADVANCE_TIME, false);
        world.setGameRule(GameRules.ADVANCE_WEATHER, false);
        world.setGameRule(GameRules.FALL_DAMAGE, false);
        world.setGameRule(GameRules.PVP, false);
        world.setGameRule(GameRules.IMMEDIATE_RESPAWN, true);
        world.setTime(18000);
        world.setStorm(false);
        world.setSpawnLocation(spawn());
        // 大廳的區塊一直載入（展示品是不存檔的實體，區塊卸載就會不見）
        for (int cx = -2; cx <= 1; cx++)
            for (int cz = -1; cz <= 0; cz++) world.addPluginChunkTicket(cx, cz, plugin);
        build();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
        for (Player p : Bukkit.getOnlinePlayers()) if (!plugin.inGame(p)) send(p);
    }

    void stop() {
        if (task != null) task.cancel();
    }

    Location spawn() {
        Location l = new Location(world, 0.5, FLOOR + 1, 6.5);
        l.setYaw(180); // 面向北牆（設定）
        return l;
    }

    private Block at(int x, int y, int z) {
        return world.getBlockAt(x, y, z);
    }

    private static long key(int x, int y, int z) {
        return ((long) (x + 1000) << 40) | ((long) (y + 1000) << 20) | (z + 1000);
    }

    private static long key(Block b) {
        return key(b.getX(), b.getY(), b.getZ());
    }

    void build() {
        buttons.clear();
        exhibitAt.clear();
        settingSigns.clear();
        frameItem.clear();
        for (Entity e : world.getEntities())
            if (e instanceof ItemFrame || e instanceof org.bukkit.entity.Display || e instanceof org.bukkit.entity.Interaction) e.remove();
        for (int x = -HX - 2; x <= HX + 2; x++)
            for (int z = -HZ - 2; z <= HZ + 2; z++)
                for (int y = FLOOR - 3; y <= CEIL + 1; y++) at(x, y, z).setType(Material.AIR, false);
        // 地板、天花板、牆
        for (int x = -HX; x <= HX; x++)
            for (int z = -HZ; z <= HZ; z++) {
                boolean edge = Math.abs(x) == HX || Math.abs(z) == HZ;
                boolean border = Math.abs(x) == HX - 1 || Math.abs(z) == HZ - 1;
                at(x, FLOOR, z).setType(edge ? Material.POLISHED_DEEPSLATE : border ? Material.POLISHED_BLACKSTONE_BRICKS
                        : ((x + z) & 1) == 0 ? Material.POLISHED_ANDESITE : Material.SMOOTH_STONE, false);
                at(x, CEIL, z).setType(x % 4 == 0 && z % 4 == 0 && !edge ? Material.SEA_LANTERN : Material.DARK_OAK_PLANKS, false);
                if (edge) for (int y = FLOOR + 1; y < CEIL; y++)
                    at(x, y, z).setType(y == FLOOR + 1 ? Material.POLISHED_BLACKSTONE_BRICKS : y == CEIL - 1 ? Material.DARK_OAK_PLANKS : Material.DEEPSLATE_TILES, false);
            }
        // 室內補光（不然牆邊的告示牌很暗）
        for (int x = -HX + 2; x <= HX - 2; x += 4)
            for (int z = -HZ + 2; z <= HZ - 2; z += 4) {
                BlockData light = Bukkit.createBlockData("minecraft:light[level=12]");
                at(x, FLOOR + 4, z).setBlockData(light, false);
            }
        // 標題
        wallSign(0, FLOOR + 5, -HZ + 1, BlockFace.SOUTH, "§6§l鬼抓人", "§c大樓逃生", "§7── 遊戲大廳 ──", "");
        buildSettings();
        buildItems();
        buildReady();
        buildExhibits();
        refreshSigns();
        plugin.getLogger().info("大廳蓋好了：設定 " + settingSigns.size() + " 項、按鈕 " + buttons.size() + " 個、功能方塊展示 " + EXHIBITS.size()
                + " 種（" + exhibitAt.size() + " 格）、展示框 " + frameItem.size() + " 個");
    }

    /** 測試用（/gh lobby start）：不等準備，直接用大廳現在的設定開局。 */
    String forceStart() {
        if (plugin.matchRunning() || plugin.isBuilding() || starting) return "遊戲進行中或正在蓋地圖。";
        starting = true;
        startingSince = System.currentTimeMillis();
        countdown = -1;
        plugin.startFromLobby(mode.equals("pve"), map, diff, persona, ghost, gtype, imp);
        return "用大廳的設定開局：" + summary();
    }

    // ---- 北牆：設定 ----

    private void buildSettings() {
        Setting[] order = Setting.values();
        for (int i = 0; i < order.length; i++) {
            int x = -12 + i * 4, z = -HZ + 1, y = FLOOR + 2;
            Setting s = order[i];
            Block sign = wallSign(x, y, z, BlockFace.SOUTH, "", "", "", "");
            settingSigns.put(s, sign);
            button(x - 1, y, z, BlockFace.SOUTH, () -> change(s, -1));
            button(x + 1, y, z, BlockFace.SOUTH, () -> change(s, +1));
        }
        wallSign(-15, FLOOR + 2, -HZ + 1, BlockFace.SOUTH, "§e設定", "按告示牌兩邊的", "按鈕切換", "§7（大家都能改）");
        wallSign(15, FLOOR + 2, -HZ + 1, BlockFace.SOUTH, "§e改了設定", "大家的準備", "會取消", "§7再按一次準備");
    }

    private void buildItems() {
        String[] ids = {Items.KEYCARD, Items.SMOKE, Items.SPEED, Items.FLASHLIGHT, Items.SCANNER, Items.BARRICADE, Items.FUSE, Items.ROOF_KEY, Items.COIN, Items.MEDKIT};
        for (int i = 0; i < ids.length; i++) {
            int x = -13 + i * 3;
            String id = ids[i];
            ItemStack it = id.equals(Items.KEYCARD) ? Items.keycard(1, false) : id.equals(Items.BARRICADE) ? Items.barricade(1) : Items.of(id);
            // 浮在牆前的 3D 道具（背包圖示的斜角，比展示框看得清楚）＋一個看不見的點擊框（右鍵看說明）
            Location l = new Location(world, x + 0.5, FLOOR + 4.5, -HZ + 1.45);
            world.spawn(l, org.bukkit.entity.ItemDisplay.class, d -> {
                d.setItemStack(it);
                d.setItemDisplayTransform(org.bukkit.entity.ItemDisplay.ItemDisplayTransform.GUI);
                d.setPersistent(false);
                d.setTransformation(new org.bukkit.util.Transformation(new org.joml.Vector3f(), new org.joml.AxisAngle4f(),
                        new org.joml.Vector3f(0.9f, 0.9f, 0.9f), new org.joml.AxisAngle4f()));
            });
            org.bukkit.entity.Interaction hit = world.spawn(new Location(world, x + 0.5, FLOOR + 4.05, -HZ + 1.45), org.bukkit.entity.Interaction.class, in -> {
                in.setInteractionWidth(0.9f);
                in.setInteractionHeight(0.9f);
                in.setResponsive(true);
                in.setPersistent(false);
            });
            frameItem.put(hit.getUniqueId(), id);
        }
        wallSign(-16, FLOOR + 4, -HZ + 1, BlockFace.SOUTH, "§b道具", "§7右鍵道具", "§7看說明", "");
    }

    // ---- 西牆：準備 ----

    private void buildReady() {
        int x = -HX + 1, y = FLOOR + 2;
        wallSign(x, y, 0, BlockFace.EAST, "§a§l準備", "§7按下面的按鈕", "§7準備／取消", "");
        button(x, y - 1, 0, BlockFace.EAST, null);
        buttons.put(key(x, y - 1, 0), null); // 準備按鈕：特別處理（要知道是誰按的）
        readySign = wallSign(x, y, -2, BlockFace.EAST, "", "", "", "");
        wallSign(x, y, 2, BlockFace.EAST, "§e怎麼開局", "大廳裡的人", "全部準備好", "倒數後自動開");
    }

    // ---- 功能方塊展示 ----

    private record Slot(int x, int z, BlockFace in) {
        BlockFace wallSide() {
            return in.getOppositeFace();
        }

        int wx() {
            return x + wallSide().getModX();
        }

        int wz() {
            return z + wallSide().getModZ();
        }

        /** 沿著牆往右一格（面向牆時的右邊）。 */
        int[] side(int d) {
            // 面向牆（wallSide）的右手邊
            BlockFace right = switch (wallSide()) {
                case NORTH -> BlockFace.EAST;
                case SOUTH -> BlockFace.WEST;
                case EAST -> BlockFace.SOUTH;
                default -> BlockFace.NORTH;
            };
            return new int[]{x + right.getModX() * d, z + right.getModZ() * d};
        }
    }

    private void buildExhibits() {
        List<Slot> east = new ArrayList<>(), south = new ArrayList<>(), west = new ArrayList<>();
        for (int z = -9; z <= 9; z += 3) east.add(new Slot(HX - 1, z, BlockFace.WEST));
        for (int x = 15; x >= -15; x -= 3) south.add(new Slot(x, HZ - 1, BlockFace.NORTH));
        for (int z : new int[]{8, 5, -5, -8}) west.add(new Slot(-HX + 1, z, BlockFace.EAST));
        String[] eastKeys = {"barrel", "locked", "power", "terminal", "reader", "jail", "barricade"};
        String[] southKeys = {"door", "lockeddoor", "cabinet", "hole", "hatch", "fuse", "radio", "sewer", "lectern", "vending", "camera"};
        String[] westKeys = {"monitor", "pa", "breaker", "shutter"};
        for (int i = 0; i < eastKeys.length; i++) exhibit(eastKeys[i], east.get(i));
        for (int i = 0; i < southKeys.length; i++) exhibit(southKeys[i], south.get(i));
        for (int i = 0; i < westKeys.length; i++) exhibit(westKeys[i], west.get(i));
    }

    private void exhibit(String k, Slot s) {
        int x = s.x(), z = s.z(), y = FLOOR + 1;
        List<Block> parts = new ArrayList<>();
        switch (k) {
            case "barrel" -> parts.add(set(x, y, z, facing(Material.BARREL, BlockFace.UP)));
            case "locked" -> {
                parts.add(set(x, y, z, facing(Material.BARREL, BlockFace.UP)));
                TrapDoor t = (TrapDoor) Bukkit.createBlockData(Material.IRON_TRAPDOOR);
                t.setHalf(Bisected.Half.BOTTOM);
                parts.add(set(x, y + 1, z, t));
            }
            case "power" -> parts.add(set(x, y + 1, z, wallSwitch(Material.LEVER, s.in())));
            case "terminal" -> parts.add(set(x, y, z, Material.CRAFTER));
            case "reader" -> {
                parts.add(set(x, y, z, Material.LODESTONE));
                int[] g = s.side(-1);
                parts.add(set(g[0], y, g[1], Material.IRON_BARS));
                parts.add(set(g[0], y + 1, g[1], Material.IRON_BARS));
            }
            case "jail" -> {
                for (int d = -1; d <= 1; d++) {
                    int[] g = s.side(d);
                    parts.add(set(g[0], y, g[1], Material.IRON_BARS));
                    parts.add(set(g[0], y + 1, g[1], Material.IRON_BARS));
                }
            }
            case "barricade" -> parts.add(set(x, y, z, Material.YELLOW_CARPET));
            case "door", "lockeddoor", "shutter" -> {
                Material dm = k.equals("door") ? Material.SPRUCE_DOOR : Material.IRON_DOOR;
                for (Bisected.Half h : Bisected.Half.values()) {
                    Door d = (Door) Bukkit.createBlockData(dm);
                    d.setFacing(s.in());
                    d.setHalf(h);
                    d.setOpen(false);
                    parts.add(set(s.wx(), h == Bisected.Half.BOTTOM ? y : y + 1, s.wz(), d));
                }
                // 門後面封起來（外面是虛空）
                BlockFace out = s.wallSide();
                set(s.wx() + out.getModX(), y, s.wz() + out.getModZ(), Material.DEEPSLATE_TILES);
                set(s.wx() + out.getModX(), y + 1, s.wz() + out.getModZ(), Material.DEEPSLATE_TILES);
                if (k.equals("shutter")) {
                    parts.add(set(s.wx(), y + 2, s.wz(), Material.IRON_BLOCK));
                    int[] g = s.side(1);
                    parts.add(set(g[0], y + 1, g[1], wallSwitch(Material.LEVER, s.in())));
                }
            }
            case "cabinet" -> {
                parts.add(set(x, y, z, Material.DARK_OAK_LOG));
                parts.add(set(x, y + 1, z, Material.DARK_OAK_LOG));
            }
            case "hole" -> {
                parts.add(set(s.wx(), y, s.wz(), Material.AIR));
                Slab sl = (Slab) Bukkit.createBlockData(Material.DEEPSLATE_TILE_SLAB);
                sl.setType(Slab.Type.TOP);
                parts.add(set(s.wx(), y + 1, s.wz(), sl));
                BlockFace out = s.wallSide();
                set(s.wx() + out.getModX(), y, s.wz() + out.getModZ(), Material.DEEPSLATE_TILES);
            }
            case "hatch" -> {
                TrapDoor t = (TrapDoor) Bukkit.createBlockData(Material.SPRUCE_TRAPDOOR);
                t.setHalf(Bisected.Half.BOTTOM);
                parts.add(set(x, y, z, t));
            }
            case "fuse" -> {
                parts.add(set(x, y, z, Material.IRON_BLOCK));
                parts.add(set(x, y + 1, z, Material.REDSTONE_LAMP));
            }
            case "radio" -> parts.add(set(x, y, z, Material.LODESTONE));
            case "sewer" -> {
                TrapDoor t = (TrapDoor) Bukkit.createBlockData(Material.IRON_TRAPDOOR);
                t.setHalf(Bisected.Half.BOTTOM);
                parts.add(set(x, y, z, t));
            }
            case "lectern" -> parts.add(set(x, y, z, facing(Material.LECTERN, s.in())));
            case "vending" -> {
                parts.add(set(x, y, z, Material.WHITE_CONCRETE));
                parts.add(set(x, y + 1, z, Material.RED_CONCRETE));
                parts.add(set(x + s.in().getModX(), y, z + s.in().getModZ(), wallSwitch(Material.STONE_BUTTON, s.in())));
                frame(x + s.in().getModX(), y + 1, z + s.in().getModZ(), s.in(), new ItemStack(Material.POTION), k);
            }
            case "camera" -> parts.add(set(x, CEIL - 1, z, facing(Material.OBSERVER, BlockFace.DOWN)));
            case "monitor" -> {
                parts.add(set(x, y, z, Material.POLISHED_BLACKSTONE));
                parts.add(set(x, y + 1, z, Material.BLACK_CONCRETE));
                frame(x + s.in().getModX(), y + 1, z + s.in().getModZ(), s.in(), new ItemStack(Material.FILLED_MAP), k);
            }
            case "pa" -> {
                parts.add(set(x, y, z, Material.NOTE_BLOCK));
                parts.add(set(x, y + 1, z, Material.LIGHTNING_ROD));
            }
            case "breaker" -> {
                parts.add(set(x, y, z, Material.IRON_BLOCK));
                parts.add(set(x, y + 1, z, Material.IRON_BLOCK));
            }
            default -> {
            }
        }
        overlayModel(k, s, parts);
        String[] t = EXHIBITS.get(k);
        Block sign = wallSign(x, FLOOR + 3, z, s.in(), "§e" + t[0], t[1], t[2], "§7右鍵看說明");
        parts.add(sign);
        for (Block b : parts) exhibitAt.put(key(b), k);
    }

    /** 展示品疊上 3D 模型（跟遊戲裡一樣：方塊換成屏障，同一格放模型）；一般的木門沒有模型，維持原版的門。 */
    private void overlayModel(String k, Slot s, List<Block> parts) {
        if (!plugin.getConfig().getBoolean("models.scene", true)) return;
        String model;
        int height = 1;
        float scale = 1f;
        int y = FLOOR + 1;
        boolean inWall = false;
        switch (k) {
            case "barrel" -> model = "container";
            case "locked" -> model = "container_locked";
            case "power" -> {
                model = "power_panel_on";
                height = 2;
            }
            case "terminal" -> model = "security_terminal_on";
            case "reader" -> model = "card_reader_idle";
            case "cabinet" -> {
                model = "cabinet";
                height = 2;
                scale = 1.4388f;
            }
            case "fuse" -> {
                model = "elevator_panel_2";
                height = 2;
            }
            case "radio" -> model = "radio";
            case "lectern" -> model = "note_stand";
            case "vending" -> {
                model = "vending_machine";
                height = 2;
            }
            case "monitor" -> {
                model = "cctv_monitor_on";
                height = 2;
            }
            case "pa" -> {
                model = "pa_desk";
                height = 2;
            }
            case "breaker" -> {
                model = "breaker_box_fixed";
                height = 2;
            }
            case "camera" -> {
                model = "cctv_camera";
                y = CEIL - 1;
            }
            case "lockeddoor" -> {
                model = "locked_door";
                height = 2;
                inWall = true;
            }
            case "shutter" -> {
                model = "shutter";
                height = 2;
                inWall = true;
            }
            case "hatch" -> model = "hatch";
            case "sewer" -> model = "sewer_grate";
            case "barricade" -> model = "barricade_mark";
            default -> {
                return;
            }
        }
        // 模型自己有的拉桿（電源）、按鈕（販賣機）、掛鎖（上鎖的木桶）拿掉；鐵捲門旁邊的開關留著
        for (Block b : new ArrayList<>(parts)) {
            Material t = b.getType();
            if (k.equals("power") && t == Material.LEVER || k.equals("vending") && t == Material.STONE_BUTTON
                    || k.equals("locked") && t == Material.IRON_TRAPDOOR) b.setType(Material.AIR, false);
        }
        int px = inWall ? s.wx() : s.x(), pz = inWall ? s.wz() : s.z();
        for (int dy = 0; dy < height; dy++) {
            Block b = at(px, y + dy, pz);
            b.setType(Material.BARRIER, false);
            parts.add(b);
        }
        for (Entity e : world.getNearbyEntities(new Location(world, s.x() + 0.5, y + 1, s.z() + 0.5), 1.2, 1.2, 1.2))
            if (e instanceof ItemFrame) e.remove();
        if (com.ghosthunt.game.Models.blockMode(plugin))
            com.ghosthunt.game.Models.spawnBlock(world, px, y, pz, s.in(), model, scale, false);
        else
            com.ghosthunt.game.Models.spawn(plugin, world, px, y, pz, com.ghosthunt.game.Models.yaw(plugin, s.in(), model), model, scale, false);
    }

    // ---- 方塊小工具 ----

    private Block set(int x, int y, int z, Material m) {
        Block b = at(x, y, z);
        b.setType(m, false);
        return b;
    }

    private Block set(int x, int y, int z, BlockData d) {
        Block b = at(x, y, z);
        b.setBlockData(d, false);
        return b;
    }

    private static BlockData facing(Material m, BlockFace f) {
        BlockData d = Bukkit.createBlockData(m);
        if (d instanceof Directional dir) dir.setFacing(f);
        return d;
    }

    private static BlockData wallSwitch(Material m, BlockFace out) {
        Switch sw = (Switch) Bukkit.createBlockData(m);
        sw.setAttachedFace(FaceAttachable.AttachedFace.WALL);
        sw.setFacing(out);
        return sw;
    }

    private void button(int x, int y, int z, BlockFace out, Runnable action) {
        Block b = set(x, y, z, wallSwitch(Material.STONE_BUTTON, out));
        if (action != null) buttons.put(key(b), action);
    }

    private void frame(int x, int y, int z, BlockFace facing, ItemStack it, String exhibit) {
        try {
            GlowItemFrame f = world.spawn(new Location(world, x, y, z), GlowItemFrame.class, fr -> {
                fr.setFacingDirection(facing, true);
                fr.setItem(it, false);
                fr.setFixed(true);
                fr.setInvulnerable(true);
                fr.setPersistent(false);
            });
            frameItem.put(f.getUniqueId(), "exhibit:" + exhibit);
        } catch (IllegalArgumentException ignored) {
            // 掛不上去就算了
        }
    }

    private Block wallSign(int x, int y, int z, BlockFace facing, String l0, String l1, String l2, String l3) {
        Block b = at(x, y, z);
        org.bukkit.block.data.type.WallSign ws = (org.bukkit.block.data.type.WallSign) Bukkit.createBlockData(Material.DARK_OAK_WALL_SIGN);
        ws.setFacing(facing);
        b.setBlockData(ws, false);
        writeSign(b, l0, l1, l2, l3);
        return b;
    }

    @SuppressWarnings("deprecation")
    private static void writeSign(Block b, String... lines) {
        if (!(b.getState() instanceof Sign sign)) return;
        SignSide side = sign.getSide(Side.FRONT);
        for (int i = 0; i < 4; i++) {
            String s = i < lines.length && lines[i] != null ? lines[i] : "";
            side.line(i, net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().deserialize(s.isEmpty() ? "" : (s.startsWith("§") ? s : "§f" + s)));
        }
        side.setGlowingText(true);
        sign.setWaxed(true);
        sign.update(true, false);
    }

    // =====================================================================
    // 設定
    // =====================================================================

    private static final Map<String, String> MAP_NAMES = Map.of(
            "mall2", "夜半購物城", "mall", "購物中心", "mall_m", "小購物中心",
            "office_park", "辦公園區", "hydro_plant", "水力發電廠", "seaside_resort", "海邊度假村");
    private static final Map<String, String> PERSONA_NAMES = Map.of("auto", "自動", "hound", "獵犬", "ambusher", "埋伏者", "watcher", "守望者", "standard", "標準");
    private static final Map<String, String> DIFF_NAMES = Map.of("easy", "簡單", "normal", "普通", "hard", "困難");

    private List<String> maps() {
        List<String> out = new ArrayList<>();
        java.io.File dir = new java.io.File(plugin.getDataFolder(), "pve-maps");
        String[] files = dir.list((d, n) -> n.endsWith(".txt"));
        if (files != null) for (String f : files) out.add(f.substring(0, f.length() - 4));
        out.sort((a, b) -> a.equals("mall2") ? -1 : b.equals("mall2") ? 1 : a.compareTo(b));
        if (out.isEmpty()) out.add("mall2");
        return out;
    }

    private List<String> ghostChoices() {
        List<String> out = new ArrayList<>(List.of("random"));
        for (Player p : lobbyPlayers()) out.add(p.getName());
        return out;
    }

    private static String cycle(List<String> opts, String cur, int d) {
        int i = opts.indexOf(cur);
        if (i < 0) i = 0;
        return opts.get(Math.floorMod(i + d, opts.size()));
    }

    private void change(Setting s, int d) {
        switch (s) {
            case MODE -> mode = mode.equals("pve") ? "pvp" : "pve";
            case MAP -> map = cycle(maps(), map, d);
            case DIFF -> diff = cycle(List.of("easy", "normal", "hard"), diff, d);
            case PERSONA -> persona = cycle(List.of("auto", "hound", "ambusher", "watcher"), persona, d);
            case IMP -> imp = !imp;
            case GHOST -> ghost = cycle(ghostChoices(), ghost, d);
            case GTYPE -> gtype = gtype.equals("nightmare") ? "basic" : "nightmare";
        }
        boolean hadReady = !ready.isEmpty();
        ready.clear();
        cancelCountdown(null);
        refreshSigns();
        String[] lines = settingLines(s);
        say("設定改了：" + strip(lines[0]) + " → " + strip(lines[1]) + (hadReady ? "（大家的準備取消了，再按一次）" : ""), NamedTextColor.YELLOW);
    }

    private static String strip(String s) {
        return s.replaceAll("§.", "");
    }

    private String[] settingLines(Setting s) {
        boolean pve = mode.equals("pve");
        String off = "§8（PvP 不用）", offE = "§8（PvE 不用）";
        return switch (s) {
            case MODE -> new String[]{"§6模式", pve ? "§aPvE（AI 鬼）" : "§cPvP（真人鬼）", pve ? "大家當逃生者" : "一個人當鬼", ""};
            case MAP -> new String[]{"§6地圖", pve ? "§b" + MAP_NAMES.getOrDefault(map, map) : "§7隨機大樓", pve ? "§7" + map : "§7每局隨機生成", pve ? "" : off};
            case DIFF -> new String[]{"§6AI 難度", "§b" + DIFF_NAMES.getOrDefault(diff, diff), "", pve ? "" : off};
            case PERSONA -> new String[]{"§6AI 個性", "§b" + PERSONA_NAMES.getOrDefault(persona, persona), persona.equals("auto") ? "§7依記憶自動選" : "", pve ? "" : off};
            case IMP -> new String[]{"§6小鬼", imp ? "§a開" : "§c關", "§7不抓人、會干擾", pve ? "" : "§8（PvP 也有效）"};
            case GHOST -> new String[]{"§6誰當鬼", "§b" + (ghost.equals("random") ? "隨機" : ghost), "", pve ? offE : ""};
            case GTYPE -> new String[]{"§6鬼的種類", "§b" + (gtype.equals("nightmare") ? "夢魘鬼" : "基本鬼"), gtype.equals("nightmare") ? "§7會熄燈" : "", pve ? offE : ""};
        };
    }

    private void refreshSigns() {
        if (!ghostChoices().contains(ghost)) ghost = "random";
        for (Map.Entry<Setting, Block> e : settingSigns.entrySet()) {
            String[] l = settingLines(e.getKey());
            writeSign(e.getValue(), l[0], "§f◀ " + l[1] + " §f▶", l[2], l[3]);
        }
        if (readySign != null) {
            List<Player> ps = lobbyPlayers();
            int n = 0;
            StringBuilder names = new StringBuilder();
            for (Player p : ps) if (ready.contains(p.getUniqueId())) {
                n++;
                if (names.length() < 14) names.append(names.length() > 0 ? " " : "").append(p.getName());
            }
            String state = plugin.matchRunning() ? "§c遊戲進行中" : starting ? "§e正在蓋地圖…" : countdown >= 0 ? "§a" + countdown + " 秒後開局" : "";
            writeSign(readySign, "§6準備狀況", "§f" + n + " / " + ps.size() + " 人", "§7" + names, state);
        }
    }

    // =====================================================================
    // 準備、倒數、開局
    // =====================================================================

    List<Player> lobbyPlayers() {
        List<Player> out = new ArrayList<>();
        if (world == null) return out;
        for (Player p : world.getPlayers()) if (p.isOnline()) out.add(p);
        return out;
    }

    private void toggleReady(Player p) {
        if (plugin.matchRunning() || starting) {
            p.sendActionBar(Component.text(plugin.matchRunning() ? "遊戲進行中，等這局結束" : "正在蓋地圖，馬上開始", NamedTextColor.GRAY));
            return;
        }
        if (ready.remove(p.getUniqueId())) {
            say(p.getName() + " 取消準備", NamedTextColor.GRAY);
            cancelCountdown(p.getName() + " 取消準備，倒數停止");
        } else {
            ready.add(p.getUniqueId());
            say(p.getName() + " 準備好了", NamedTextColor.GREEN);
        }
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1.2f);
        refreshSigns();
    }

    private boolean allReady() {
        List<Player> ps = lobbyPlayers();
        if (ps.isEmpty()) return false;
        if (mode.equals("pvp") && ps.size() < 2) return false;
        for (Player p : ps) if (!ready.contains(p.getUniqueId())) return false;
        return true;
    }

    private void cancelCountdown(String why) {
        if (countdown < 0) return;
        countdown = -1;
        if (why != null) say(why, NamedTextColor.YELLOW);
    }

    private void tick() {
        if (world == null) return;
        ready.removeIf(id -> {
            Player p = Bukkit.getPlayer(id);
            return p == null || !p.getWorld().equals(world);
        });
        if (starting) {
            if (plugin.matchRunning()) {
                starting = false;
                ready.clear();
            } else if (!plugin.isBuilding() && System.currentTimeMillis() - startingSince > 5000) {
                starting = false;
                say("開局失敗（看伺服器記錄），可以再準備一次", NamedTextColor.RED);
            }
            refreshSigns();
            return;
        }
        if (plugin.matchRunning() || plugin.isBuilding()) {
            cancelCountdown(null);
            refreshSigns();
            return;
        }
        if (countdown < 0) {
            if (allReady()) {
                countdown = plugin.getConfig().getInt("lobby.countdown", 10);
                say("全部準備好了！" + countdown + " 秒後開局（按準備按鈕可以取消）", NamedTextColor.GREEN);
            }
        } else if (!allReady()) {
            cancelCountdown(lobbyPlayers().isEmpty() ? null : mode.equals("pvp") && lobbyPlayers().size() < 2 ? "PvP 至少要 2 個人，倒數停止" : "有人還沒準備，倒數停止");
        } else {
            for (Player p : lobbyPlayers()) {
                if (countdown <= 5) p.showTitle(Title.title(Component.text(countdown == 0 ? "開始！" : String.valueOf(countdown), NamedTextColor.GOLD),
                        Component.text(summary(), NamedTextColor.GRAY), Title.Times.times(java.time.Duration.ZERO, java.time.Duration.ofMillis(1100), java.time.Duration.ZERO)));
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 1f, countdown == 0 ? 2f : 1f);
            }
            if (countdown == 0) {
                countdown = -1;
                starting = true;
                startingSince = System.currentTimeMillis();
                say("開局：" + summary() + "。正在蓋地圖，請稍等…", NamedTextColor.GOLD);
                plugin.startFromLobby(mode.equals("pve"), map, diff, persona, ghost, gtype, imp);
            } else countdown--;
        }
        refreshSigns();
    }

    private String summary() {
        if (mode.equals("pve"))
            return "PvE｜" + MAP_NAMES.getOrDefault(map, map) + "｜" + DIFF_NAMES.getOrDefault(diff, diff) + "｜" + PERSONA_NAMES.getOrDefault(persona, persona) + "｜小鬼" + (imp ? "開" : "關");
        return "PvP｜隨機大樓｜鬼：" + (ghost.equals("random") ? "隨機" : ghost) + "（" + (gtype.equals("nightmare") ? "夢魘" : "基本") + "）｜小鬼" + (imp ? "開" : "關");
    }

    /** 遊戲結束後：5 秒後所有沒在遊戲裡的人回大廳。 */
    void afterMatch() {
        ready.clear();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) if (!plugin.inGame(p)) send(p);
            refreshSigns();
        }, 100L);
    }

    /** 傳送到大廳：冒險模式、清空背包、補血補飽。 */
    void send(Player p) {
        if (world == null) return;
        for (PotionEffect e : p.getActivePotionEffects()) p.removePotionEffect(e.getType());
        p.getInventory().clear();
        p.setGameMode(GameMode.ADVENTURE);
        p.setHealth(p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue());
        p.setFoodLevel(20);
        p.setSaturation(20);
        p.setFireTicks(0);
        p.teleport(spawn());
        p.sendMessage(Component.text("歡迎來到遊戲大廳！", NamedTextColor.GOLD));
        p.sendMessage(Component.text("北牆：按告示牌兩邊的按鈕改設定｜西牆：按按鈕準備（全部人準備好就倒數開局）｜牆邊的方塊、上面的道具：右鍵看說明", NamedTextColor.GRAY));
        refreshSigns();
    }

    private void say(String text, NamedTextColor color) {
        for (Player p : lobbyPlayers()) p.sendMessage(Component.text("［大廳］" + text, color));
    }

    // =====================================================================
    // 事件
    // =====================================================================

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        Block b = e.getClickedBlock();
        if (b == null || world == null || !b.getWorld().equals(world)) return;
        e.setCancelled(true); // 大廳的方塊都不能真的用（拉桿、門、箱子、講台、合成器……）
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK && e.getAction() != Action.LEFT_CLICK_BLOCK) return;
        Player p = e.getPlayer();
        long now = System.currentTimeMillis();
        if (now - lastClick.getOrDefault(p.getUniqueId(), 0L) < 300) return;
        lastClick.put(p.getUniqueId(), now);
        long k = key(b);
        if (buttons.containsKey(k)) {
            Runnable r = buttons.get(k);
            if (r == null) toggleReady(p);
            else {
                p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1f);
                r.run();
            }
            return;
        }
        String ex = exhibitAt.get(k);
        if (ex != null) explain(p, EXHIBITS.get(ex));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        if (world == null || !e.getRightClicked().getWorld().equals(world)) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        String id = frameItem.get(e.getRightClicked().getUniqueId());
        if (id == null) return;
        explain(e.getPlayer(), id.startsWith("exhibit:") ? EXHIBITS.get(id.substring(8)) : ITEMS.get(id));
    }

    private void explain(Player p, String[] t) {
        if (t == null) return;
        p.sendMessage(Component.text("【" + t[0] + "】", NamedTextColor.GOLD).append(Component.text(t[3], NamedTextColor.WHITE)));
        p.playSound(p.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 0.8f, 1.2f);
    }

    /** 舊版存進世界的展示框、展示實體（現在的都不存檔）：區塊載入時清掉，不然會跟新的疊在一起。 */
    @EventHandler
    public void onEntitiesLoad(org.bukkit.event.world.EntitiesLoadEvent e) {
        if (world == null || !e.getWorld().equals(world)) return;
        for (Entity en : e.getEntities())
            if (en.isPersistent() && (en instanceof ItemFrame || en instanceof org.bukkit.entity.Display || en instanceof org.bukkit.entity.Interaction))
                en.remove();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline() && !plugin.inGame(p)) send(p);
        }, 5L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        ready.remove(e.getPlayer().getUniqueId());
        Bukkit.getScheduler().runTask(plugin, this::refreshSigns);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        if (world != null && !plugin.inGame(e.getPlayer())) e.setRespawnLocation(spawn());
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (world != null && e.getEntity().getWorld().equals(world)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onFood(FoodLevelChangeEvent e) {
        if (world != null && e.getEntity().getWorld().equals(world)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onHangingBreak(HangingBreakEvent e) {
        if (world != null && e.getEntity().getWorld().equals(world)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (world != null && e.getBlock().getWorld().equals(world) && e.getPlayer().getGameMode() != GameMode.CREATIVE) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (world != null && e.getBlock().getWorld().equals(world) && e.getPlayer().getGameMode() != GameMode.CREATIVE) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (world != null && e.getPlayer().getWorld().equals(world)) e.setCancelled(true);
    }

    // =====================================================================
    // 說明文字：{名稱, 告示牌第 2 行, 第 3 行, 完整說明}
    // =====================================================================

    private static final Map<String, String[]> EXHIBITS = new HashMap<>();
    private static final Map<String, String[]> ITEMS = new HashMap<>();

    private static void ex(String k, String name, String l1, String l2, String full) {
        EXHIBITS.put(k, new String[]{name, l1, l2, full});
    }

    private static void it(String k, String name, String full) {
        ITEMS.put(k, new String[]{name, "", "", full});
    }

    static {
        ex("barrel", "木桶", "翻找道具", "門禁卡", "右鍵翻（站著不動 2 秒，小聲）。裡面可能有門禁卡、道具、保險絲、硬幣、急救包，也可能是空的。翻過的就空了（除非小鬼把你的道具藏進來）。");
        ex("locked", "上鎖的木桶", "上面蓋鐵活板門", "撬開 5 秒", "右鍵撬開要 5 秒，比較大聲。通常放比較好的東西，例如屋頂鑰匙。");
        ex("power", "總電源拉桿", "開電源 5 秒", "很大聲", "每局在某一間電力室（門口告示牌會多一行「⚡ 電源」）。右鍵站 5 秒開電源，兩個人一起比較快，很大聲。有電：燈會亮、讀卡機和保全室終端機能用、監視器和廣播能用。鬼可以把電源關掉一次。");
        ex("terminal", "保全室終端機", "門禁卡授權", "要有電", "全棟 2 間保全室（門口告示牌寫「★保全室★」）。拿著門禁卡右鍵，站 5 秒授權，要有電。授權過的卡才能刷大門。");
        ex("reader", "大門讀卡機", "左邊是大門", "刷卡逃出", "拿授權過的門禁卡右鍵刷 3 秒，25 秒倒數後大門（鐵欄杆）打開，穿過去就逃出。鬼會來讓大門故障（倒數回到滿，每扇一次）。");
        ex("jail", "監獄", "被抓關這裡", "隊友來救", "被鬼打到就關進監獄。隊友站在籠子旁邊 4～6 秒就能把裡面的人全部救出來，救出來有 3 秒加速、3 秒不會被抓。第 2 次被抓就淘汰（變觀戰）。");
        ex("barricade", "障礙物記號", "門口的黃地毯", "放障礙物", "拿著障礙物右鍵門口地上的黃色記號，就把門口擋住。鬼要拆 3.5 秒，很大聲。每人開局有 1 個，木桶裡還有。");
        ex("door", "門", "右鍵開關", "蹲下右鍵卡門", "右鍵開關。蹲下右鍵可以「卡門」（每扇一局一次），卡住後誰都打不開，鬼要撞開。");
        ex("lockeddoor", "上鎖的鐵門", "要屋頂鑰匙", "通往停機坪", "手打不開，要屋頂鑰匙（上鎖的木桶裡）。打開後通往屋頂停機坪（直升機路線）。鬼也打不開，會繞路。");
        ex("cabinet", "可推倒的櫃子", "門口旁的深色木頭", "推倒擋門", "門口旁邊兩格高的深色木頭。右鍵推倒，把門口擋住，鬼要拆。");
        ex("hole", "蹲伏小洞", "蹲著鑽過去", "鬼過不去", "牆上的矮洞。逃生者蹲著鑽得過，鬼過不去，要先打大（4 秒）。甩掉鬼的好地方。");
        ex("hatch", "地下通道入口", "地上的木活板門", "通到遠處", "1F 地上的活板門，下面是梯子和地下通道，通到很遠的另一個房間。逃生者隨便進出，鬼要先撬開（4 秒、很大聲）。");
        ex("fuse", "貨梯配電盤", "裝 2 個保險絲", "貨梯路線", "貨梯路線用（不是每局都開放）。木桶裡找 2 個保險絲裝上（各 4 秒、很大聲），燈亮了就能呼叫貨梯；30 秒後到（貨梯廂裡有人才倒數），到了廂裡的人就逃出，只停 15 秒。");
        ex("radio", "無線電", "停機坪上", "直升機路線", "直升機路線用（不是每局都開放）。用屋頂鑰匙打開上鎖的鐵門上屋頂，在這裡呼叫（5 秒），守住停機坪 60 秒（上面有人才計時）就逃出。全圖都聽得到，鬼一定會來。");
        ex("sewer", "下水道鐵柵", "輸入密碼", "下水道路線", "下水道路線用（不是每局都開放）。找齊 3 張紙條的密碼，在這裡輸入（5 秒、很大聲），鐵柵打開，跳下去就逃出。");
        ex("lectern", "紙條講台", "撿密碼紙條", "沒有聲音", "右鍵撿紙條，沒有聲音，全隊共享。每張是下水道密碼的一位數，湊齊 3 位就能開鐵柵。");
        ex("vending", "販賣機", "投硬幣", "換道具", "拿木桶找到的硬幣右鍵投幣（2 秒），隨機掉煙霧彈、加速藥水或手電筒。");
        ex("camera", "攝影機", "天花板上", "監控室看", "天花板下的偵測器。在監控室可以切換畫面看這裡。攝影機拍到鬼的話，鬼就知道監控室有人在看，會過去。");
        ex("monitor", "監控螢幕", "看攝影機", "要有電", "今晚的監控室才能用（每局 3 間挑 1 間），要有電。右鍵後在聊天窗點［大廳］［美食街］……切換 12 台攝影機，［離開］回來。看的時候身體留在原地不能動，鬼打到就算抓到你。");
        ex("pa", "廣播台", "引開鬼", "要有電", "要有電。右鍵後在聊天窗選一區，站 3 秒，那一區的喇叭響 10 秒，把鬼引過去（一局 2 次）。小心：鬼有時候不去喇叭，直接來廣播台抓你。");
        ex("breaker", "區域配電箱", "修好那區會亮", "停電也亮", "兩格高的鐵塊。右鍵修 4 秒，那一區停電也會亮。亮的地方你看得清楚，鬼也看得比較遠。小鬼會來把它關掉。");
        ex("shutter", "鐵捲門", "右邊是開關", "打開變捷徑", "店與店之間的捷徑，平常鎖著。在店裡那一側拉開關（3 秒、很大聲），鐵捲門整扇捲上去，之後一直開著，鬼也能走。");

        it(Items.KEYCARD, "門禁卡", "全場 2 張、在不同樓層的木桶裡。先到保全室終端機授權，再去大門讀卡機刷。拿卡的人被抓，卡會藏到附近的箱子裡。");
        it(Items.SMOKE, "煙霧彈", "丟出去，4 格內的鬼失明 3 秒（隔牆也有效），正在做的事也會被打斷；也能把小鬼嚇跑。");
        it(Items.SPEED, "加速藥水", "喝了 5 秒 +40% 速度（加速不疊加）。");
        it(Items.FLASHLIGHT, "手電筒", "拿在手上右鍵開關，照亮前方，會沒電。照到鬼的臉 1 秒，鬼 1.5 秒看不到人；照到小鬼會把它嚇跑。");
        it(Items.SCANNER, "掃描器", "顯示最近的門禁卡在哪個方向（沒電時指向電源），用的時候會發出聲音。");
        it(Items.BARRICADE, "障礙物", "拿著右鍵門口地上的黃色記號，把門口擋住，鬼要拆 3.5 秒。");
        it(Items.FUSE, "保險絲", "貨梯路線用，要 2 個，裝到卸貨區貨梯旁的配電盤。");
        it(Items.ROOF_KEY, "屋頂鑰匙", "在上鎖的木桶裡。打開通往停機坪的上鎖鐵門（直升機路線）。");
        it(Items.COIN, "硬幣", "投進販賣機換道具，最多帶 3 個。");
        it(Items.MEDKIT, "急救包", "被鬼打到時自動用掉：倒地 3 秒，但不進監獄。");
    }
}
