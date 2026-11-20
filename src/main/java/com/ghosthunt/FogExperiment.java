package com.ghosthunt;

import com.ghosthunt.building.Layout;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 霧的實驗：用資料包定義幾個只差在霧距離的生態域，把大樓那一區換成指定的生態域。
 * 霧距離是 26.3 的環境屬性 visual/fog_start_distance、visual/fog_end_distance，可以寫在生態域裡，
 * 原版客戶端連線時會收到，不用裝模組。
 * 限制：生態域是世界資料，同一區所有人（包括鬼）看到的霧都一樣。
 */
public final class FogExperiment {

    /** 霧完全看不見的距離（格）。 */
    public static final List<Integer> DISTANCES = List.of(4, 6, 8, 12, 14, 16, 24, 32, 48);
    private static final String PACK = "ghosthunt_fog";

    private FogExperiment() {}

    public static String biomeName(int d) {
        return "fog_" + d;
    }

    /**
     * 把資料包寫到主世界的 datapacks 資料夾。生態域只在伺服器啟動時載入，第一次寫入後要重開一次。
     * @return true 表示內容有更新（需要重開）
     */
    public static boolean install(JavaPlugin plugin) {
        World main = Bukkit.getWorlds().get(0);
        Path root = Bukkit.getWorldContainer().toPath().resolve(main.getName()).resolve("datapacks").resolve(PACK);
        boolean changed = false;
        try {
            changed |= write(root.resolve("pack.mcmeta"),
                    "{\"pack\":{\"description\":\"GhostHunt fog experiment\",\"min_format\":121,\"max_format\":121}}");
            for (int d : DISTANCES) {
                changed |= write(root.resolve("data/ghosthunt/worldgen/biome/" + biomeName(d) + ".json"), biomeJson(d));
            }
        } catch (IOException e) {
            plugin.getLogger().warning("寫入霧的資料包失敗：" + e.getMessage());
        }
        return changed;
    }

    private static String biomeJson(int end) {
        float start = end * 0.3f;
        return """
                {
                  "attributes": {
                    "minecraft:visual/fog_start_distance": %s,
                    "minecraft:visual/fog_end_distance": %s,
                    "minecraft:visual/fog_color": "#000000",
                    "minecraft:visual/sky_color": "#000000"
                  },
                  "carvers": [],
                  "downfall": 0.0,
                  "effects": { "water_color": "#3f76e4" },
                  "features": [],
                  "has_precipitation": false,
                  "temperature": 0.5
                }
                """.formatted(String.format(Locale.ROOT, "%.1f", start), String.format(Locale.ROOT, "%.1f", (float) end));
    }

    private static boolean write(Path p, String content) throws IOException {
        if (Files.exists(p) && Files.readString(p, StandardCharsets.UTF_8).equals(content)) return false;
        Files.createDirectories(p.getParent());
        Files.writeString(p, content, StandardCharsets.UTF_8);
        return true;
    }

    /** 取得生態域；資料包還沒載入時回傳 null。 */
    public static Biome biome(int distance) {
        return RegistryAccess.registryAccess().getRegistry(RegistryKey.BIOME)
                .get(new NamespacedKey("ghosthunt", biomeName(distance)));
    }

    public static Biome defaultBiome() {
        return RegistryAccess.registryAccess().getRegistry(RegistryKey.BIOME)
                .get(NamespacedKey.minecraft("the_void"));
    }

    /** 把大樓範圍換成指定生態域，並重新傳區塊給所有玩家。回傳換了幾個區塊。 */
    public static int apply(World world, Layout L, Biome biome) {
        Set<Long> chunks = setBiomes(world, L, biome);
        for (long c : chunks) world.refreshChunk((int) (c >> 32), (int) c);
        return chunks.size();
    }

    private static Set<Long> setBiomes(World world, Layout L, Biome biome) {
        // 客戶端算霧時會把周圍約 ±12 格的生態域混在一起平均（spatially interpolated），
        // 所以範圍要比大樓大一圈，不然靠近邊緣（例如 1F 往下）會混到沒有霧的生態域，霧就變淡或消失。
        int pad = 16;
        int x0 = L.ox - pad, x1 = L.ox + L.size + pad;
        int z0 = L.oz - pad, z1 = L.oz + L.size + pad;
        int y0 = L.floorY(0) - pad, y1 = L.topY() + pad;
        Set<Long> chunks = new HashSet<>();
        for (int x = x0; x <= x1; x += 4) {
            for (int z = z0; z <= z1; z += 4) {
                for (int y = y0; y <= y1; y += 4) world.setBiome(x, y, z, biome);
                chunks.add(((long) (x >> 4) << 32) ^ ((z >> 4) & 0xffffffffL));
            }
        }
        return chunks;
    }

    /**
     * 只讓指定的玩家看到 show 這個生態域（例如熄燈的霧），世界本身維持 actual。
     * 做法：暫時把世界換成 show → 用「只更新生態域」的封包（ClientboundChunksBiomesPacket）把當下的生態域打包 →
     * 世界換回 actual → 只把封包送給指定的玩家。其他人（例如鬼）不會收到，看到的還是 actual。
     * 這個封包不在 Paper 的公開 API 裡，所以用反射呼叫伺服器內部（Mojang 名稱）；升級 Minecraft 版本後可能要調整。
     * 注意：如果玩家的區塊之後被整個重傳（例如走太遠再回來），霧會變回 actual。
     *
     * @return false 表示反射失敗（呼叫的人應該改用 apply 讓所有人都有霧）
     */
    public static boolean sendPersonal(World world, Layout L, Biome show, Biome actual,
                                       java.util.Collection<? extends org.bukkit.entity.Player> players,
                                       java.util.logging.Logger log) {
        Set<Long> chunks = setBiomes(world, L, show);
        Object packet;
        try {
            Object level = world.getClass().getMethod("getHandle").invoke(world);
            java.lang.reflect.Method getChunk = level.getClass().getMethod("getChunk", int.class, int.class);
            java.util.List<Object> levelChunks = new java.util.ArrayList<>();
            for (long c : chunks) levelChunks.add(getChunk.invoke(level, (int) (c >> 32), (int) c));
            Class<?> pk = Class.forName("net.minecraft.network.protocol.game.ClientboundChunksBiomesPacket");
            packet = pk.getMethod("forChunks", java.util.List.class).invoke(null, levelChunks);
        } catch (ReflectiveOperationException | RuntimeException e) {
            log.warning("打包生態域封包失敗，改成所有人都有霧：" + e);
            setBiomes(world, L, actual);
            return false;
        }
        // 封包建好時已經把生態域資料複製出來了，可以馬上換回去
        setBiomes(world, L, actual);
        try {
            Class<?> packetClass = Class.forName("net.minecraft.network.protocol.Packet");
            for (org.bukkit.entity.Player p : players) {
                Object handle = p.getClass().getMethod("getHandle").invoke(p);
                Object conn = handle.getClass().getField("connection").get(handle);
                conn.getClass().getMethod("send", packetClass).invoke(conn, packet);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            log.warning("送生態域封包失敗，改成所有人都有霧：" + e);
            return false;
        }
        return true;
    }
}
