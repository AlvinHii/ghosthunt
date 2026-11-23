package com.ghosthunt;

import com.sun.net.httpserver.HttpServer;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.Executors;

/**
 * 資源包（地圖音效）：jar 裡的 ghosthunt-pack.zip 用一個小網頁伺服器送出去，玩家進伺服器時問要不要下載。
 * config.yml 的 resource-pack：enabled、port、host（玩家連得到的位址；跟朋友連線要改成公開 IP／網域）、url（自己架的話直接給網址）、required。
 */
final class ResourcePackHost implements Listener {

    private final JavaPlugin plugin;
    private final byte[] data;
    private final String sha1;
    private final UUID id;
    private final String url;
    private final boolean required;
    private HttpServer server;
    /** 額外的資源包（其他插件產生的 zip，例如 BetterHud 的 HUD；plugins 資料夾底下的路徑），一起送給玩家。 */
    private java.util.List<String> extraPacks = java.util.List.of();
    private String base;
    private final java.util.Map<String, String[]> extraSha = new java.util.HashMap<>(); // 路徑 → {最後修改時間, sha1}

    private ResourcePackHost(JavaPlugin plugin, byte[] data, String url, boolean required) throws Exception {
        this.plugin = plugin;
        this.data = data;
        this.sha1 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(data));
        this.id = UUID.nameUUIDFromBytes(("ghosthunt-pack-" + sha1).getBytes());
        this.url = url;
        this.required = required;
    }

    static ResourcePackHost start(JavaPlugin plugin) {
        FileConfiguration cfg = plugin.getConfig();
        if (!cfg.getBoolean("resource-pack.enabled", true)) return null;
        byte[] data;
        try (InputStream in = plugin.getResource("ghosthunt-pack.zip")) {
            if (in == null) {
                plugin.getLogger().warning("jar 裡沒有 ghosthunt-pack.zip，不送資源包（地圖音效會沒聲音）");
                return null;
            }
            data = in.readAllBytes();
        } catch (IOException e) {
            plugin.getLogger().warning("讀不到資源包：" + e.getMessage());
            return null;
        }
        int port = cfg.getInt("resource-pack.port", 8164);
        String custom = cfg.getString("resource-pack.url", "");
        boolean self = custom == null || custom.isBlank();
        // host 可以自己帶 port（例如 playit.gg 的隧道 xxx.gl.at.ply.gg:12345：外面的 port 跟本機的 8164 不一樣）
        String host = cfg.getString("resource-pack.host", "localhost").trim();
        String url = self ? "http://" + (host.contains(":") ? host : host + ":" + port) + "/ghosthunt-pack.zip" : custom;
        try {
            ResourcePackHost h = new ResourcePackHost(plugin, data, url, cfg.getBoolean("resource-pack.required", false));
            if (self) {
                h.server = HttpServer.create(new InetSocketAddress(port), 0);
                h.server.createContext("/ghosthunt-pack.zip", ex -> {
                    ex.getResponseHeaders().add("Content-Type", "application/zip");
                    ex.sendResponseHeaders(200, h.data.length);
                    try (OutputStream os = ex.getResponseBody()) {
                        os.write(h.data);
                    }
                });
                h.base = "http://" + (host.contains(":") ? host : host + ":" + port);
                h.extraPacks = cfg.getStringList("resource-pack.extra-packs");
                for (int i = 0; i < h.extraPacks.size(); i++) {
                    final java.io.File f = h.extraFile(i);
                    h.server.createContext("/extra/" + i + ".zip", ex -> {
                        byte[] b;
                        try {
                            b = java.nio.file.Files.readAllBytes(f.toPath());
                        } catch (IOException e) {
                            ex.sendResponseHeaders(404, -1);
                            ex.close();
                            return;
                        }
                        ex.getResponseHeaders().add("Content-Type", "application/zip");
                        ex.sendResponseHeaders(200, b.length);
                        try (OutputStream os = ex.getResponseBody()) {
                            os.write(b);
                        }
                    });
                }
                h.server.setExecutor(Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "GhostHunt-ResourcePack");
                    t.setDaemon(true);
                    return t;
                }));
                h.server.start();
            }
            plugin.getServer().getPluginManager().registerEvents(h, plugin);
            plugin.getLogger().info("資源包：" + url + "（" + data.length / 1024 + " KB，sha1 " + h.sha1 + "）");
            return h;
        } catch (Exception e) {
            plugin.getLogger().warning("資源包伺服器開不起來（port " + port + " 被佔用？）：" + e.getMessage());
            return null;
        }
    }

    private java.io.File extraFile(int i) {
        return new java.io.File(plugin.getDataFolder().getParentFile(), extraPacks.get(i));
    }

    /** 額外資源包的 sha1（檔案改過才重算）；檔案不在就是 null。 */
    private String extraSha1(int i) {
        java.io.File f = extraFile(i);
        if (!f.isFile()) return null;
        String[] cached = extraSha.get(f.getPath());
        String mod = String.valueOf(f.lastModified());
        if (cached != null && cached[0].equals(mod)) return cached[1];
        try {
            String s = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(java.nio.file.Files.readAllBytes(f.toPath())));
            extraSha.put(f.getPath(), new String[]{mod, s});
            return s;
        } catch (Exception e) {
            return null;
        }
    }

    void send(Player p) {
        java.util.List<ResourcePackInfo> packs = new java.util.ArrayList<>();
        packs.add(ResourcePackInfo.resourcePackInfo(id, URI.create(url), sha1));
        if (base != null)
            for (int i = 0; i < extraPacks.size(); i++) {
                String s = extraSha1(i);
                if (s == null) {
                    plugin.getLogger().warning("額外的資源包找不到：" + extraFile(i));
                    continue;
                }
                packs.add(ResourcePackInfo.resourcePackInfo(UUID.nameUUIDFromBytes(("ghosthunt-extra-" + i + "-" + s).getBytes()),
                        URI.create(base + "/extra/" + i + ".zip"), s));
            }
        p.sendResourcePacks(ResourcePackRequest.resourcePackRequest()
                .packs(packs)
                .required(required)
                .replace(false)
                .prompt(Component.text("鬼抓人的地圖音效（環境音、事件音）。不裝也能玩，只是沒有這些聲音。", NamedTextColor.GRAY))
                .build());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        send(e.getPlayer());
    }

    /** 資源包裝上了沒：寫進記錄；沒裝上時告訴玩家（小鬼、心跳、環境音都要資源包）。 */
    @EventHandler
    public void onStatus(org.bukkit.event.player.PlayerResourcePackStatusEvent e) {
        var st = e.getStatus();
        plugin.getLogger().info("資源包狀態：" + e.getPlayer().getName() + " → " + st);
        switch (st) {
            case DECLINED -> e.getPlayer().sendMessage(Component.text("你沒有裝資源包：小鬼、心跳、環境音都聽不到。打 /gh pack 可以再裝一次。", NamedTextColor.YELLOW));
            case FAILED_DOWNLOAD, INVALID_URL, FAILED_RELOAD, DISCARDED ->
                    e.getPlayer().sendMessage(Component.text("資源包沒裝上（" + st + "）：小鬼、心跳、環境音都聽不到。打 /gh pack 再試一次。", NamedTextColor.RED));
            case SUCCESSFULLY_LOADED -> e.getPlayer().sendMessage(Component.text("資源包裝好了（小鬼、心跳、環境音）。", NamedTextColor.GRAY));
            default -> { }
        }
    }

    void stop() {
        if (server != null) server.stop(0);
    }
}
