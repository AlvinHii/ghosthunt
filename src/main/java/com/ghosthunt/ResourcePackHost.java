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
        String url = self ? "http://" + cfg.getString("resource-pack.host", "localhost") + ":" + port + "/ghosthunt-pack.zip" : custom;
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

    void send(Player p) {
        p.sendResourcePacks(ResourcePackRequest.resourcePackRequest()
                .packs(ResourcePackInfo.resourcePackInfo(id, URI.create(url), sha1))
                .required(required)
                .replace(false)
                .prompt(Component.text("鬼抓人的地圖音效（環境音、事件音）。不裝也能玩，只是沒有這些聲音。", NamedTextColor.GRAY))
                .build());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        send(e.getPlayer());
    }

    void stop() {
        if (server != null) server.stop(0);
    }
}
