package com.ghosthunt;

import com.ghosthunt.building.BlockMap;
import com.ghosthunt.building.BlockPlacer;
import com.ghosthunt.building.Building;
import com.ghosthunt.building.BuildingGenerator;
import com.ghosthunt.building.BuildingSettings;
import com.ghosthunt.building.Layout;
import com.ghosthunt.building.Pos;
import com.ghosthunt.building.RoomType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

public final class GhostHuntPlugin extends JavaPlugin {

    /** 插件裡附的 PvE 固定地圖（第一次啟動時複製到 plugins/GhostHunt/pve-maps/）。 */
    private static final List<String> BUNDLED_MAPS = List.of("office_park", "hydro_plant", "seaside_resort", "mall", "mall_m");

    private Building building;
    private boolean busy;
    private com.ghosthunt.game.Match match;
    private ResourcePackHost pack;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        for (String m : BUNDLED_MAPS) {
            if (!new java.io.File(getDataFolder(), "pve-maps/" + m + ".txt").exists()) saveResource("pve-maps/" + m + ".txt", false);
        }
        com.ghosthunt.game.Items.init(this);
        pack = ResourcePackHost.start(this);
        getServer().getPluginManager().registerEvents(new com.ghosthunt.game.GameListener(() -> match), this);
        com.ghosthunt.game.Match.recoverAfterCrash(this);
        if (FogExperiment.install(this)) {
            getLogger().warning("霧的資料包已寫入或更新，要重開伺服器一次才會生效。");
        }
        // 旗標檔關機：plugins/GhostHunt/restart.flag 出現時正常存檔關閉（沒有人在線上才關；檔案內容寫 force 就不管有沒有人）。
        // 給「伺服器跑在沒辦法打 stop 的地方」時用（2026-11-23 使用者同意）。
        getServer().getScheduler().runTaskTimer(this, () -> {
            java.io.File flag = new java.io.File(getDataFolder(), "restart.flag");
            if (!flag.exists()) return;
            boolean force = false;
            try {
                force = java.nio.file.Files.readString(flag.toPath()).trim().equalsIgnoreCase("force");
            } catch (java.io.IOException ignored) {
                // 讀不到就當一般旗標
            }
            if (!force && !getServer().getOnlinePlayers().isEmpty()) {
                if (getServer().getCurrentTick() % 1200 == 0)
                    getLogger().info("看到 restart.flag，但還有 " + getServer().getOnlinePlayers().size() + " 人在線上，等沒人再關");
                return;
            }
            if (!flag.delete()) getLogger().warning("restart.flag 刪不掉");
            getLogger().info("看到 restart.flag：正常存檔關閉伺服器");
            getServer().shutdown();
        }, 100L, 40L);
        getLogger().info("GhostHunt enabled");
    }

    @Override
    public void onDisable() {
        if (match != null) match.end("伺服器關閉");
        if (pack != null) pack.stop();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        String sub = args.length == 0 ? "info" : args[0].toLowerCase(Locale.ROOT);
        if (!sub.equals("info") && !sender.hasPermission("ghosthunt.admin")) {
            msg(sender, "沒有權限。", NamedTextColor.RED);
            return true;
        }
        switch (sub) {
            case "reload" -> {
                reloadConfig();
                msg(sender, "設定檔已重新載入。", NamedTextColor.GREEN);
            }
            case "build" -> { if (!inMatch(sender)) build(sender, args); }
            case "clear" -> { if (!inMatch(sender)) clear(sender); }
            case "start" -> start(sender, args);
            case "maps" -> listMaps(sender);
            case "lure" -> msg(sender, match == null ? "沒有進行中的遊戲。" : match.debugLure(), NamedTextColor.GRAY);
            case "pack" -> {
                if (pack == null) msg(sender, "資源包沒開（config 的 resource-pack.enabled，或 port 被佔用，看伺服器 log）。", NamedTextColor.RED);
                else if (sender instanceof org.bukkit.entity.Player p) {
                    pack.send(p);
                    msg(sender, "重新送了資源包。", NamedTextColor.GRAY);
                } else msg(sender, "只有玩家能用。", NamedTextColor.RED);
            }
            case "maptest" -> mapTest(sender, args);
            case "stop" -> {
                if (match == null) msg(sender, "目前沒有在進行的遊戲。", NamedTextColor.GRAY);
                else match.end("管理員結束了遊戲");
            }
            case "tp" -> tp(sender, args);
            case "info" -> info(sender);
            case "fog" -> fog(sender, args);
            case "export" -> exportLayout(sender);
            case "dummy" -> {
                // 測試用：AI 局裡生出假逃生者（村民），AI 鬼會把它們當成逃生者追
                if (match == null || !match.isAi()) {
                    msg(sender, "要先用 /gh start ai 開一局 AI 局。", NamedTextColor.RED);
                    break;
                }
                int n = 1;
                try {
                    if (args.length > 1) n = Math.max(1, Math.min(5, Integer.parseInt(args[1])));
                } catch (NumberFormatException ignored) {
                }
                match.spawnDummies(n);
                msg(sender, "生出 " + n + " 個假逃生者。", NamedTextColor.GREEN);
            }
            case "aimemory" -> {
                // /gh aimemory reset [地圖|all]：清掉 AI 鬼學到的東西
                if (args.length < 2 || !args[1].equalsIgnoreCase("reset")) {
                    msg(sender, "用法：/gh aimemory reset [地圖名|all]", NamedTextColor.YELLOW);
                    break;
                }
                java.io.File dir = new java.io.File(getDataFolder(), "ai-memory");
                String which = args.length > 2 ? args[2] : (building != null && building.mapName != null ? building.mapName : "all");
                int n = 0;
                java.io.File[] files = dir.listFiles((d, name) -> name.endsWith(".yml"));
                if (files != null) for (java.io.File f : files)
                    if (which.equalsIgnoreCase("all") || f.getName().equalsIgnoreCase(which + ".yml")) if (f.delete()) n++;
                msg(sender, "清掉了 " + n + " 份 AI 鬼記憶（" + which + "）。", NamedTextColor.GREEN);
            }
            case "aidebug" -> {
                if (!(sender instanceof Player p) || match == null || !match.isAi()) {
                    msg(sender, "要在 AI 局裡由玩家使用。", NamedTextColor.RED);
                    break;
                }
                msg(sender, match.toggleAiDebug(p) ? "AI 鬼除錯顯示：開" : "AI 鬼除錯顯示：關", NamedTextColor.AQUA);
            }
            case "selftest" -> {
                if (inMatch(sender)) break;
                if (building == null) {
                    msg(sender, "還沒有大樓，先用 /ghosthunt build。", NamedTextColor.RED);
                    break;
                }
                World w = ArenaWorld.getOrCreate(BuildingSettings.from(getConfig()).worldName());
                try {
                    for (String line : com.ghosthunt.game.Match.selfTest(this, building, w, com.ghosthunt.game.GameSettings.from(getConfig()),
                            line -> msg(sender, "[自我檢查] " + line, line.contains("走不到") ? NamedTextColor.RED : NamedTextColor.AQUA)))
                        msg(sender, "[自我檢查] " + line, NamedTextColor.AQUA);
                    msg(sender, "[自我檢查] 完成，沒有錯誤。", NamedTextColor.GREEN);
                } catch (RuntimeException e) {
                    msg(sender, "[自我檢查] 出錯：" + e, NamedTextColor.RED);
                    getLogger().log(java.util.logging.Level.SEVERE, "自我檢查出錯", e);
                }
            }
            default -> msg(sender, "用法：/ghosthunt <start [鬼的名字|random] [basic|nightmare]|stop|build [種子]|clear|tp [樓層]|fog <距離|off>|info|reload>", NamedTextColor.YELLOW);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return List.of("start", "stop", "build", "clear", "tp", "fog", "info", "reload").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("tp")) return List.of("1", "2", "3");
        if (args.length == 2 && args[0].equalsIgnoreCase("start")) {
            List<String> names = new java.util.ArrayList<>(List.of("random"));
            getServer().getOnlinePlayers().forEach(pl -> names.add(pl.getName()));
            return names;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("start")) return List.of("basic", "nightmare");
        if (args.length == 2 && args[0].equalsIgnoreCase("fog")) {
            List<String> opts = new java.util.ArrayList<>();
            FogExperiment.DISTANCES.forEach(d -> opts.add(String.valueOf(d)));
            opts.add("off");
            return opts;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("fog")) return List.of("me", "others");
        return List.of();
    }

    // ---- 指令 ----

    private boolean inMatch(CommandSender sender) {
        if (match != null) {
            msg(sender, "遊戲進行中，先用 /ghosthunt stop 結束。", NamedTextColor.RED);
            return true;
        }
        return false;
    }

    private void start(CommandSender sender, String[] args) {
        start(sender, args, false);
    }

    private void start(CommandSender sender, String[] args, boolean rerolled) {
        if (match != null) {
            msg(sender, "已經有一局在進行。", NamedTextColor.RED);
            return;
        }
        if (busy) {
            msg(sender, "大樓正在蓋，請稍等。", NamedTextColor.RED);
            return;
        }
        if (building == null) {
            msg(sender, "還沒有大樓，先用 /ghosthunt build。", NamedTextColor.RED);
            return;
        }
        List<Player> players = new java.util.ArrayList<>(getServer().getOnlinePlayers());
        // /gh start ai：PvE，所有玩家都是逃生者，鬼是電腦；/gh start ai solo：沒有逃生者，只看 AI 鬼巡邏（測試用）
        if (args.length >= 2 && args[1].equalsIgnoreCase("ai")) {
            boolean solo = false;
            String difficulty = null, persona = null;
            int testDummies = -1;
            for (int i = 2; i < args.length; i++) {
                String a = args[i].toLowerCase(Locale.ROOT);
                if (a.equals("solo")) solo = true;
                else if (a.equals("test")) testDummies = Math.max(0, getConfig().getInt("test-mode.dummies", 3));
                else if (testDummies >= 0 && a.matches("\\d+")) testDummies = Math.min(10, Integer.parseInt(a));
                else if (a.equals("easy") || a.equals("normal") || a.equals("hard")) difficulty = a;
                else if (a.equals("hound") || a.equals("ambusher") || a.equals("watcher") || a.equals("standard") || a.equals("auto")) persona = a;
            }
            if (players.isEmpty() && !solo) {
                msg(sender, "沒有玩家在線上。", NamedTextColor.RED);
                return;
            }
            // 固定地圖：每局重蓋一次（大門、保全室、木桶重新隨機）
            if (!rerolled && building.mapName != null && getConfig().getBoolean("pve.reroll-each-match", true)) {
                msg(sender, "重新隨機大門、保全室、木桶…", NamedTextColor.GRAY);
                buildFixed(sender, building.mapName, ThreadLocalRandom.current().nextLong(1_000_000), false, () -> start(sender, args, true));
                return;
            }
            World world = ArenaWorld.getOrCreate(BuildingSettings.from(getConfig()).worldName());
            match = com.ghosthunt.game.Match.pve(this, building, world, com.ghosthunt.game.GameSettings.from(getConfig()),
                    solo ? List.of() : players, () -> match = null, difficulty, persona, testDummies);
            if (testDummies >= 0)
                msg(sender, "測試模式：你照常當逃生者，被抓一次會立刻重生、之後 AI 鬼無視你；鬼出來時自動生 " + testDummies
                        + " 個假逃生者。這局不寫 AI 的學習記憶。", NamedTextColor.AQUA);
            match.start();
            if (!building.pve) msg(sender, "提醒：這是大樓，不是 PvE 小地圖（/gh build pve）。", NamedTextColor.YELLOW);
            return;
        }
        if (players.isEmpty()) {
            msg(sender, "沒有玩家在線上。", NamedTextColor.RED);
            return;
        }
        Player ghost;
        if (args.length < 2 || args[1].equalsIgnoreCase("random")) {
            ghost = players.get(ThreadLocalRandom.current().nextInt(players.size()));
        } else {
            ghost = getServer().getPlayerExact(args[1]);
            if (ghost == null) {
                msg(sender, "找不到玩家 " + args[1] + "。", NamedTextColor.RED);
                return;
            }
        }
        com.ghosthunt.game.GhostType type = com.ghosthunt.game.GhostType.NIGHTMARE;
        if (args.length >= 3) {
            try {
                type = com.ghosthunt.game.GhostType.valueOf(args[2].toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                msg(sender, "鬼的種類只有 basic 或 nightmare。", NamedTextColor.RED);
                return;
            }
        }
        players.remove(ghost);
        World world = ArenaWorld.getOrCreate(BuildingSettings.from(getConfig()).worldName());
        match = new com.ghosthunt.game.Match(this, building, world, com.ghosthunt.game.GameSettings.from(getConfig()),
                type, ghost, players, () -> match = null);
        match.start();
    }


    private void build(CommandSender sender, String[] args) {
        if (busy) {
            msg(sender, "正在蓋，請稍等。", NamedTextColor.RED);
            return;
        }
        // /gh build [種子]、/gh build pve [地圖名|random] [種子]（PvE：預設是 config 的 pve.map 固定地圖）
        boolean pve = args.length > 1 && args[1].equalsIgnoreCase("pve");
        int seedArg = pve ? 2 : 1;
        String mapName = null;
        if (pve) {
            mapName = getConfig().getString("pve.map", "office_park");
            if (args.length > 2 && !args[2].matches("-?[0-9]+")) {
                mapName = args[2];
                seedArg = 3;
            }
            if (mapName == null || mapName.isBlank() || mapName.equalsIgnoreCase("random")) mapName = null;
        }
        long seed;
        try {
            seed = args.length > seedArg ? Long.parseLong(args[seedArg]) : ThreadLocalRandom.current().nextLong(1_000_000);
        } catch (NumberFormatException e) {
            msg(sender, "種子要是數字。", NamedTextColor.RED);
            return;
        }
        if (mapName != null) {
            buildFixed(sender, mapName, seed, true, null);
            return;
        }
        BuildingSettings s = BuildingSettings.from(getConfig(), pve);
        place(sender, s, BuildingGenerator.generate(s, seed), true, null);
    }

    /** 遊戲世界裡所有的物品展示框和掉在地上的物品（家具裝飾）。 */
    private void clearDecorFrames(World world) {
        for (org.bukkit.entity.Entity e : world.getEntities())
            if (e instanceof org.bukkit.entity.ItemFrame || e instanceof org.bukkit.entity.Item) e.remove();
    }

    /** 家具正面的展示框：固定、無敵（不能拿走物品、不能轉、不能打掉）。 */
    private void spawnDecorFrames(World world, Building b) {
        int n = 0;
        for (Building.DecorFrame fr : b.decorFrames) {
            org.bukkit.Location l = new org.bukkit.Location(world, fr.at().x(), fr.at().y(), fr.at().z());
            if (!l.getBlock().getType().isAir()) continue;
            Class<? extends org.bukkit.entity.ItemFrame> type = fr.glow() ? org.bukkit.entity.GlowItemFrame.class : org.bukkit.entity.ItemFrame.class;
            try {
                world.spawn(l, type, f -> {
                    f.setFacingDirection(fr.facing(), true);
                    f.setItem(new org.bukkit.inventory.ItemStack(fr.item()), false);
                    f.setFixed(true);
                    f.setInvulnerable(true);
                    f.setPersistent(true);
                });
                n++;
            } catch (IllegalArgumentException e) {
                // 這個位置掛不上去（背後不是實心方塊）：跳過
            }
        }
        if (n > 0) getLogger().info("家具展示框：" + n + " 個");
    }

    /** PvE 固定地圖（plugins/GhostHunt/pve-maps/名字.txt）。 */
    private void buildFixed(CommandSender sender, String mapName, long seed, boolean verbose, Runnable after) {
        java.nio.file.Path file = getDataFolder().toPath().resolve("pve-maps").resolve(mapName + ".txt");
        if (!java.nio.file.Files.exists(file)) {
            msg(sender, "找不到地圖 " + mapName + "（" + file + "）。用 /gh maps 看有哪些。", NamedTextColor.RED);
            return;
        }
        com.ghosthunt.building.FixedMap fm;
        try {
            fm = com.ghosthunt.building.FixedMap.load(file);
        } catch (java.io.IOException e) {
            msg(sender, "讀地圖失敗：" + e.getMessage(), NamedTextColor.RED);
            return;
        }
        BuildingSettings s = BuildingSettings.from(getConfig(), true);
        place(sender, s, BuildingGenerator.generateFixed(s, fm, seed), verbose, after);
    }

    private void place(CommandSender sender, BuildingSettings s, BuildingGenerator.Result r, boolean verbose, Runnable after) {
        World world = ArenaWorld.getOrCreate(s.worldName());
        if (world == null) {
            msg(sender, "建立遊戲世界失敗。", NamedTextColor.RED);
            return;
        }
        if (!r.ok()) {
            msg(sender, "大樓產生失敗（試了 " + r.attempts() + " 次）：", NamedTextColor.RED);
            r.errors().stream().limit(10).forEach(e -> msg(sender, " - " + e, NamedTextColor.RED));
            return;
        }
        Building b = r.building();
        if (b.mapName != null) {
            r.rejected().forEach(e -> getLogger().warning("地圖 " + b.mapName + "：" + e));
            if (verbose) r.rejected().stream().limit(8).forEach(e -> msg(sender, "注意：" + e, NamedTextColor.YELLOW));
        } else r.rejected().forEach(e -> getLogger().info("退回的版本：" + e));
        busy = true;
        if (verbose) msg(sender, "產生完成（" + (b.mapName != null ? "地圖 " + b.mapName + "，" : "") + "種子 " + b.seed + "，試了 " + r.attempts() + " 次，"
                + r.blocks().blocks().size() + " 個方塊），開始分批蓋…", NamedTextColor.GRAY);
        clearDecorFrames(world); // 先拿掉上一棟的展示框，不然牆換掉時會掉出物品
        BlockPlacer.place(this, world, r.blocks(), s.blocksPerTick(), ticks -> {
            busy = false;
            building = b;
            int signs = writeSigns(world, b);
            spawnDecorFrames(world, b);
            if (verbose) {
                getLogger().info("告示牌寫好 " + signs + "/" + b.signs.size() + " 個");
                msg(sender, "大樓蓋好了，花了 " + ticks + " tick。用 /ghosthunt tp 1 進去看看。", NamedTextColor.GREEN);
                info(sender);
            }
            if (after != null) after.run();
        });
    }

    /**
     * /gh maptest [地圖] [次數]：用很多個種子產生固定地圖（不放方塊），統計每局隨機的結果：
     * 大門組合、保全室組合、木桶數量、失敗和警告。看「所有可能」是不是都合理。
     */
    private void mapTest(CommandSender sender, String[] args) {
        String name = args.length > 1 ? args[1] : getConfig().getString("pve.map", "office_park");
        int n = 200;
        try {
            if (args.length > 2) n = Integer.parseInt(args[2]);
        } catch (NumberFormatException ignored) {
        }
        java.nio.file.Path file = getDataFolder().toPath().resolve("pve-maps").resolve(name + ".txt");
        com.ghosthunt.building.FixedMap fm;
        try {
            fm = com.ghosthunt.building.FixedMap.load(file);
        } catch (java.io.IOException e) {
            msg(sender, "讀不到地圖 " + name + "：" + e.getMessage(), NamedTextColor.RED);
            return;
        }
        BuildingSettings s = BuildingSettings.from(getConfig(), true);
        java.util.Map<String, Integer> gatePairs = new java.util.TreeMap<>(), secPairs = new java.util.TreeMap<>(), warn = new java.util.TreeMap<>(),
                fails = new java.util.TreeMap<>(), gateFloors = new java.util.TreeMap<>();
        java.util.Map<String, String> warnExample = new java.util.HashMap<>();
        java.util.IntSummaryStatistics barrels = new java.util.IntSummaryStatistics(), power = new java.util.IntSummaryStatistics();
        java.util.DoubleSummaryStatistics gateDist = new java.util.DoubleSummaryStatistics(), secDist = new java.util.DoubleSummaryStatistics();
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) {
            BuildingGenerator.Result r = BuildingGenerator.generateFixed(s, fm, 1000L + i);
            for (String w : r.rejected()) {
                String k = w.replaceAll("[0-9]+", "#");
                if (!warnExample.containsKey(k)) warnExample.put(k, "種子 " + (1000L + i) + "：" + w);
                warn.merge(k, 1, Integer::sum);
            }
            if (!r.ok()) {
                fails.merge(r.errors().get(0).replaceAll("[0-9]+", "#"), 1, Integer::sum);
                continue;
            }
            Building b = r.building();
            java.util.List<String> gs = new java.util.ArrayList<>();
            for (Building.Gate g : b.gates) gs.add((g.floor() + 1) + "F" + (g.center().x() - b.layout.ox) + "," + (g.center().z() - b.layout.oz));
            java.util.Collections.sort(gs);
            gatePairs.merge(String.join(" + ", gs), 1, Integer::sum);
            if (b.gates.size() == 2) {
                gateDist.accept(b.gates.get(0).center().distance(b.gates.get(1).center()));
                gateFloors.merge((b.gates.get(0).floor() == b.gates.get(1).floor() ? "同一層" : "不同層"), 1, Integer::sum);
            }
            java.util.List<String> ss = new java.util.ArrayList<>();
            for (Pos q : b.securityTerminals) ss.add((q.y() > b.layout.floorY(1) ? "2F" : "1F") + (q.x() - b.layout.ox) + "," + (q.z() - b.layout.oz));
            java.util.Collections.sort(ss);
            secPairs.merge(String.join(" + ", ss), 1, Integer::sum);
            if (b.securityTerminals.size() == 2) secDist.accept(b.securityTerminals.get(0).distance(b.securityTerminals.get(1)));
            barrels.accept(b.containers.size());
            power.accept(b.powerCandidates.size());
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        java.util.List<String> out = new java.util.ArrayList<>();
        out.add("地圖 " + name + "：產生 " + n + " 次（" + ms + " ms），失敗 " + fails.values().stream().mapToInt(Integer::intValue).sum() + " 次");
        fails.forEach((k, v) -> out.add("  失敗 " + v + "×：" + k));
        out.add(String.format("大門組合 %d 種｜兩扇距離 平均 %.1f（%.1f～%.1f）｜%s", gatePairs.size(), gateDist.getAverage(), gateDist.getMin(), gateDist.getMax(), gateFloors));
        gatePairs.entrySet().stream().sorted((a, c) -> c.getValue() - a.getValue()).limit(12)
                .forEach(e -> out.add("  " + e.getValue() + "×  " + e.getKey()));
        out.add(String.format("保全室組合 %d 種｜兩台終端機距離 平均 %.1f（%.1f～%.1f）", secPairs.size(), secDist.getAverage(), secDist.getMin(), secDist.getMax()));
        secPairs.entrySet().stream().sorted((a, c) -> c.getValue() - a.getValue()).limit(10)
                .forEach(e -> out.add("  " + e.getValue() + "×  " + e.getKey()));
        out.add(String.format("木桶 平均 %.1f（%d～%d）｜電源候選 %d～%d", barrels.getAverage(), barrels.getMin(), barrels.getMax(), power.getMin(), power.getMax()));
        warn.forEach((k, v) -> out.add("  警告 " + v + "×：" + k + "（例如 " + warnExample.get(k) + "）"));
        for (String line : out) {
            getLogger().info("[maptest] " + line);
            if (sender instanceof Player) msg(sender, line, NamedTextColor.GRAY);
        }
    }

    private void listMaps(CommandSender sender) {
        java.io.File dir = new java.io.File(getDataFolder(), "pve-maps");
        String[] names = dir.list((d, n) -> n.endsWith(".txt"));
        if (names == null || names.length == 0) {
            msg(sender, "pve-maps 資料夾裡沒有地圖。", NamedTextColor.GRAY);
            return;
        }
        java.util.Arrays.sort(names);
        msg(sender, "PvE 地圖（/gh build pve <名字>）：", NamedTextColor.WHITE);
        for (String n : names) {
            String id = n.substring(0, n.length() - 4);
            String title = id;
            try {
                com.ghosthunt.building.FixedMap fm = com.ghosthunt.building.FixedMap.load(dir.toPath().resolve(n));
                title = id + "：" + fm.name + "（" + fm.type + "，" + fm.W + "×" + fm.H + "，" + fm.floorCount() + " 層）";
            } catch (java.io.IOException ignored) {
            }
            msg(sender, " - " + title, NamedTextColor.GRAY);
        }
    }

    /**
     * 把目前的大樓匯出成 JSON（plugins/GhostHunt/layouts/種子.json），給平衡模擬（tools/simulate.py）用。
     * 每層一張字元圖：. 走得到、# 眼睛高度有東西（擋視線）、, 擋路但不擋視線（矮家具）、空白 不是室內。
     */
    private void exportLayout(CommandSender sender) {
        if (building == null) {
            msg(sender, "還沒有大樓。", NamedTextColor.RED);
            return;
        }
        Building b = building;
        Layout L = b.layout;
        World world = ArenaWorld.getOrCreate(BuildingSettings.from(getConfig()).worldName());
        StringBuilder js = new StringBuilder("{");
        js.append("\"seed\":").append(b.seed).append(",\"size\":").append(L.size).append(",\"floors\":").append(L.floors)
                .append(",\"ox\":").append(L.ox).append(",\"oz\":").append(L.oz).append(",\"floorY\":[");
        for (int f = 0; f < L.floors; f++) js.append(f == 0 ? "" : ",").append(L.floorY(f));
        js.append("],\"tiles\":[");
        for (int f = 0; f < L.floors; f++) {
            java.util.Set<Pos> walk = b.walkTiles.getOrDefault(f, java.util.Set.of());
            int w = L.floorY(f) + 1;
            js.append(f == 0 ? "[" : ",[");
            for (int z = 0; z < L.size; z++) {
                StringBuilder row = new StringBuilder();
                for (int x = 0; x < L.size; x++) {
                    int wx = L.ox + x, wz = L.oz + z;
                    if (walk.contains(new Pos(wx, w, wz))) { row.append('.'); continue; }
                    org.bukkit.Material eye = world.getBlockAt(wx, w + 1, wz).getType();
                    org.bukkit.Material feet = world.getBlockAt(wx, w, wz).getType();
                    boolean eyeBlocks = eye.isOccluding();
                    if (eyeBlocks) row.append('#');
                    else if (!feet.isAir()) row.append(',');
                    else row.append(' ');
                }
                js.append(z == 0 ? "" : ",").append('"').append(row).append('"');
            }
            js.append(']');
        }
        js.append("],\"containers\":").append(posList(b, b.containers));
        js.append(",\"power\":").append(posList(b, b.powerCandidates));
        js.append(",\"gates\":[");
        for (int i = 0; i < b.gates.size(); i++) {
            Building.Gate g = b.gates.get(i);
            js.append(i == 0 ? "" : ",").append("{\"reader\":").append(posList(b, java.util.List.of(g.reader())))
                    .append(",\"exit\":").append(posList(b, java.util.List.of(g.exit()))).append('}');
        }
        js.append("],\"jails\":").append(posList(b, new java.util.ArrayList<>(b.jailCages.values())));
        js.append(",\"ghost\":").append(posList(b, java.util.List.of(b.ghostRoomCenter)));
        js.append(",\"stairs\":[");
        for (int i = 0; i < b.stairLinks.size(); i++) {
            Pos[] s2 = b.stairLinks.get(i);
            js.append(i == 0 ? "" : ",").append("[").append(posList(b, java.util.List.of(s2[0]))).append(',')
                    .append(posList(b, java.util.List.of(s2[1]))).append(']');
        }
        js.append("],\"crawlHoles\":");
        java.util.List<Pos> holes = new java.util.ArrayList<>();
        for (Building.CrawlHole h : b.crawlHoles) holes.add(h.lower());
        js.append(posList(b, holes));
        js.append(",\"rooms\":[");
        for (int i = 0; i < b.rooms.size(); i++) {
            Building.Room r = b.rooms.get(i);
            js.append(i == 0 ? "" : ",").append("{\"type\":\"").append(r.type().symbol).append("\",\"hideout\":").append(r.hideout())
                    .append(",\"at\":").append(posList(b, java.util.List.of(r.center()))).append('}');
        }
        js.append("]}");
        try {
            java.nio.file.Path out = getDataFolder().toPath().resolve("layouts").resolve(b.seed + ".json");
            java.nio.file.Files.createDirectories(out.getParent());
            java.nio.file.Files.writeString(out, js, java.nio.charset.StandardCharsets.UTF_8);
            msg(sender, "匯出到 " + out, NamedTextColor.GREEN);
        } catch (java.io.IOException e) {
            msg(sender, "匯出失敗：" + e.getMessage(), NamedTextColor.RED);
        }
    }

    /** [[樓層, x, z], ...]（x、z 是相對大樓西北角）。 */
    private static String posList(Building b, java.util.Collection<Pos> ps) {
        Layout L = b.layout;
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Pos p : ps) {
            int f = Math.max(0, Math.min(L.floors - 1, (p.y() - L.floorY(0) - 1) / L.floorHeight));
            sb.append(first ? "" : ",").append('[').append(f).append(',').append(p.x() - L.ox).append(',').append(p.z() - L.oz).append(']');
            first = false;
        }
        return sb.append(']').toString();
    }

    /** 告示牌的字要等方塊放好才寫得上去。 */
    /** 告示牌每一行的顏色（螢光字）：保全室紅、授權說明黃、電源金，其他白。 */
    public static NamedTextColor signLineColor(String line) {
        if (line.contains("保全室")) return NamedTextColor.RED;
        if (line.contains("授權")) return NamedTextColor.YELLOW;
        if (line.startsWith("⚡")) return NamedTextColor.GOLD;
        return NamedTextColor.WHITE;
    }

    private static int writeSigns(World world, Building b) {
        int n = 0;
        for (java.util.Map.Entry<Pos, String[]> e : b.signs.entrySet()) {
            Pos p = e.getKey();
            if (!(world.getBlockAt(p.x(), p.y(), p.z()).getState() instanceof org.bukkit.block.Sign sign)) continue;
            org.bukkit.block.sign.SignSide side = sign.getSide(org.bukkit.block.sign.Side.FRONT);
            String[] lines = e.getValue();
            int off = lines.length >= 4 ? 0 : 1; // 三行以內從第二行開始寫（看起來置中）
            for (int i = 0; i < lines.length && i + off < 4; i++) side.line(i + off, Component.text(lines[i], signLineColor(lines[i])));
            side.setGlowingText(true); // 螢光字（像用了螢光墨囊），暗的地方也看得到
            sign.setWaxed(true); // 不能編輯
            sign.update(true, false);
            n++;
        }
        return n;
    }

    private void clear(CommandSender sender) {
        if (busy) {
            msg(sender, "正在蓋，請稍等。", NamedTextColor.RED);
            return;
        }
        BuildingSettings s = BuildingSettings.from(getConfig());
        World world = ArenaWorld.getOrCreate(s.worldName());
        Layout L = building != null ? building.layout : s.layout();
        BlockMap air = new BlockMap();
        air.fill(L.rel(-4, L.floorY(0), -4), L.rel(L.size + 3, L.topY() + 1, L.size + 3), Material.AIR);
        clearDecorFrames(world);
        busy = true;
        BlockPlacer.place(this, world, air, s.blocksPerTick(), ticks -> {
            busy = false;
            building = null;
            msg(sender, "大樓清掉了。", NamedTextColor.GREEN);
        });
    }

    private void tp(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            msg(sender, "只有玩家能用。", NamedTextColor.RED);
            return;
        }
        if (building == null) {
            msg(sender, "還沒有大樓，先用 /ghosthunt build。", NamedTextColor.RED);
            return;
        }
        int f = 0;
        if (args.length > 1) {
            try {
                f = Integer.parseInt(args[1]) - 1;
            } catch (NumberFormatException ignored) {
            }
        }
        f = Math.max(0, Math.min(building.layout.floors - 1, f));
        World world = ArenaWorld.getOrCreate(BuildingSettings.from(getConfig()).worldName());
        Pos c = building.layout.crossing(f);
        p.teleport(new Location(world, c.x() + 0.5, c.y(), c.z() + 0.5));
        msg(sender, "傳送到 " + (f + 1) + "F 走廊交叉口。", NamedTextColor.GREEN);
    }

    private void fog(CommandSender sender, String[] args) {
        if (args.length < 2) {
            msg(sender, "用法：/ghosthunt fog <" + FogExperiment.DISTANCES + " 其中一個|off>", NamedTextColor.YELLOW);
            return;
        }
        org.bukkit.block.Biome biome;
        String label;
        if (args[1].equalsIgnoreCase("off")) {
            biome = FogExperiment.defaultBiome();
            label = "關掉霧";
        } else {
            int d;
            try {
                d = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                d = -1;
            }
            if (!FogExperiment.DISTANCES.contains(d)) {
                msg(sender, "距離只能是 " + FogExperiment.DISTANCES + " 或 off。", NamedTextColor.RED);
                return;
            }
            biome = FogExperiment.biome(d);
            label = "霧 " + d + " 格外完全看不見";
        }
        if (biome == null) {
            msg(sender, "霧的生態域還沒載入，請重開伺服器一次。", NamedTextColor.RED);
            return;
        }
        BuildingSettings s = BuildingSettings.from(getConfig());
        World world = ArenaWorld.getOrCreate(s.worldName());
        Layout L = building != null ? building.layout : s.layout();
        if (args.length >= 3 && (args[2].equalsIgnoreCase("me") || args[2].equalsIgnoreCase("others"))) {
            // 只給部分玩家看：me = 只有自己；others = 除了自己以外的線上玩家（測試「鬼看不到霧」用）
            List<Player> targets = new java.util.ArrayList<>(getServer().getOnlinePlayers());
            if (args[2].equalsIgnoreCase("me")) {
                targets.clear();
                if (sender instanceof Player p) targets.add(p);
            } else if (sender instanceof Player p) {
                targets.remove(p);
            }
            org.bukkit.block.Biome base = FogExperiment.defaultBiome();
            boolean ok = FogExperiment.sendPersonal(world, L, biome, base, targets, getLogger());
            msg(sender, ok ? label + "：只送給 " + targets.size() + " 個玩家（世界本身沒有霧）。"
                    : "只送給部分玩家失敗，看伺服器記錄。", ok ? NamedTextColor.GREEN : NamedTextColor.RED);
            return;
        }
        int chunks = FogExperiment.apply(world, L, biome);
        msg(sender, label + "（更新了 " + chunks + " 個區塊，所有人都看得到）。", NamedTextColor.GREEN);
    }

    private void info(CommandSender sender) {
        msg(sender, "GhostHunt " + getPluginMeta().getVersion(), NamedTextColor.AQUA);
        Building b = building;
        if (b == null) {
            msg(sender, "目前沒有大樓。", NamedTextColor.GRAY);
            return;
        }
        Layout L = b.layout;
        msg(sender, "種子 " + b.seed + "｜每層 " + L.size + "×" + L.size + " 格（含外牆）｜" + L.floors + " 層｜層高 " + L.floorHeight,
                NamedTextColor.GRAY);
        if (L.isIrregular()) {
            // 不規則大樓：每個小格中間是哪種房間（＝ 走廊、＃ 牆）
            for (int f = L.floors - 1; f >= 0; f--) {
                msg(sender, (f + 1) + "F（北在上）：", NamedTextColor.WHITE);
                for (String row : b.plans.getOrDefault(f, java.util.List.of())) msg(sender, "  " + row, NamedTextColor.WHITE);
            }
            for (int f = 0; f < L.floors; f++) {
                final int ff = f;
                long rooms = b.rooms.stream().filter(r -> r.floor() == ff && !r.hideout()).count();
                long hide = b.rooms.stream().filter(r -> r.floor() == ff && r.hideout()).count();
                msg(sender, (f + 1) + "F：房間 " + rooms + " 間、暗格 " + hide + " 個", NamedTextColor.GRAY);
            }
            msg(sender, "鑽洞 " + b.crawlHoles.size() + " 個（含暗格入口）｜保全室 " + b.securityRooms.size() + " 間｜地下通道 "
                    + b.tunnels + " 條（出入口 " + b.hatches.size() + " 個）｜可推倒的櫃子 " + b.toppleSpots.size() + " 個", NamedTextColor.GRAY);
        }
        for (int f = L.isIrregular() ? -1 : L.floors - 1; f >= 0; f--) {
            msg(sender, (f + 1) + "F（北在上）：", NamedTextColor.WHITE);
            for (int cz = 0; cz < L.n; cz++) {
                StringBuilder sb = new StringBuilder("  ");
                for (int cx = 0; cx < L.n; cx++) {
                    sb.append(symbol(b.type(f, cx, cz)));
                    if (L.corridorAfter(cx)) sb.append("║");
                }
                msg(sender, sb.toString(), NamedTextColor.WHITE);
                if (L.corridorAfter(cz)) msg(sender, "  ══╬════╬══", NamedTextColor.DARK_GRAY);
            }
        }
        for (Building.Gate g : b.gates) {
            msg(sender, "大門：" + (g.floor() + 1) + "F，位置 " + g.center(), NamedTextColor.GOLD);
        }
        msg(sender, "鬼房：" + (b.ghostRoom.floor() + 1) + "F｜容器 " + b.containers.size()
                + " 個｜電源候選 " + b.powerCandidates.size() + " 個｜燈 " + b.lights.size() + " 盞｜門 "
                + b.doors.size() + " 扇｜障礙物門口 " + b.barricadeSpots.size() + " 個", NamedTextColor.GRAY);
        StringBuilder st = new StringBuilder("可站的位置（檢查走得到的格子）：");
        for (int f = 0; f < L.floors; f++) {
            st.append(f + 1).append("F ").append(b.spawnTiles.getOrDefault(f, java.util.Set.of()).size()).append("  ");
        }
        msg(sender, st.toString(), NamedTextColor.GRAY);
        msg(sender, java.util.Arrays.stream(RoomType.values()).map(t -> t.symbol + "=" + t.label)
                .collect(java.util.stream.Collectors.joining(" ")), NamedTextColor.DARK_GRAY);
    }

    private static String symbol(RoomType t) {
        return t.symbol;
    }

    private static void msg(CommandSender s, String text, NamedTextColor color) {
        s.sendMessage(Component.text(text, color));
    }
}
