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

    private Building building;
    private boolean busy;
    private com.ghosthunt.game.Match match;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        com.ghosthunt.game.Items.init(this);
        getServer().getPluginManager().registerEvents(new com.ghosthunt.game.GameListener(() -> match), this);
        if (FogExperiment.install(this)) {
            getLogger().warning("霧的資料包已寫入或更新，要重開伺服器一次才會生效。");
        }
        getLogger().info("GhostHunt enabled");
    }

    @Override
    public void onDisable() {
        if (match != null) match.end("伺服器關閉");
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
            case "stop" -> {
                if (match == null) msg(sender, "目前沒有在進行的遊戲。", NamedTextColor.GRAY);
                else match.end("管理員結束了遊戲");
            }
            case "tp" -> tp(sender, args);
            case "info" -> info(sender);
            case "fog" -> fog(sender, args);
            case "export" -> exportLayout(sender);
            case "selftest" -> {
                if (inMatch(sender)) break;
                if (building == null) {
                    msg(sender, "還沒有大樓，先用 /ghosthunt build。", NamedTextColor.RED);
                    break;
                }
                World w = ArenaWorld.getOrCreate(BuildingSettings.from(getConfig()).worldName());
                try {
                    for (String line : com.ghosthunt.game.Match.selfTest(this, building, w, com.ghosthunt.game.GameSettings.from(getConfig())))
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
        long seed;
        try {
            seed = args.length > 1 ? Long.parseLong(args[1]) : ThreadLocalRandom.current().nextLong(1_000_000);
        } catch (NumberFormatException e) {
            msg(sender, "種子要是數字。", NamedTextColor.RED);
            return;
        }
        BuildingSettings s = BuildingSettings.from(getConfig());
        World world = ArenaWorld.getOrCreate(s.worldName());
        if (world == null) {
            msg(sender, "建立遊戲世界失敗。", NamedTextColor.RED);
            return;
        }
        long t0 = System.nanoTime();
        BuildingGenerator.Result r = BuildingGenerator.generate(s, seed);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        if (!r.ok()) {
            msg(sender, "大樓產生失敗（試了 " + r.attempts() + " 次）：", NamedTextColor.RED);
            r.errors().stream().limit(10).forEach(e -> msg(sender, " - " + e, NamedTextColor.RED));
            return;
        }
        r.rejected().forEach(e -> getLogger().info("退回的版本：" + e));
        busy = true;
        Building b = r.building();
        msg(sender, "產生完成（種子 " + b.seed + "，試了 " + r.attempts() + " 次，" + ms + " ms，"
                + r.blocks().blocks().size() + " 個方塊），開始分批蓋…", NamedTextColor.GRAY);
        BlockPlacer.place(this, world, r.blocks(), s.blocksPerTick(), ticks -> {
            busy = false;
            building = b;
            int signs = writeSigns(world, b);
            getLogger().info("告示牌寫好 " + signs + "/" + b.signs.size() + " 個");
            msg(sender, "大樓蓋好了，花了 " + ticks + " tick。用 /ghosthunt tp 1 進去看看。", NamedTextColor.GREEN);
            info(sender);
        });
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
    private static int writeSigns(World world, Building b) {
        int n = 0;
        for (java.util.Map.Entry<Pos, String[]> e : b.signs.entrySet()) {
            Pos p = e.getKey();
            if (!(world.getBlockAt(p.x(), p.y(), p.z()).getState() instanceof org.bukkit.block.Sign sign)) continue;
            org.bukkit.block.sign.SignSide side = sign.getSide(org.bukkit.block.sign.Side.FRONT);
            String[] lines = e.getValue();
            for (int i = 0; i < lines.length && i < 4; i++) side.line(i + 1, Component.text(lines[i]));
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
                + " 個｜電源候選 " + b.powerCandidates.size() + " 個｜燈 " + b.lights.size() + " 盞｜雙開門 "
                + b.doors.size() / 2 + " 組｜障礙物門口 " + b.barricadeSpots.size() + " 個", NamedTextColor.GRAY);
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
