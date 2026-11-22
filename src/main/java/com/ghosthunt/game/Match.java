package com.ghosthunt.game;

import com.ghosthunt.FogExperiment;
import com.ghosthunt.building.Building;
import com.ghosthunt.building.Pos;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * 一局遊戲。第一版目前有：角色分配、出生、鬼房 30 秒、抓人、監獄與救援、守監獄判定、緊急衝刺、
 * 心跳、障礙物、15 分鐘發光、17 分鐘結束、夢魘的熄燈。
 * 還沒有：電源、門禁卡、大門、道具、互動進度條（除了鬼拆障礙物）。
 */
public final class Match {

    public enum Status { FREE, JAILED, ELIMINATED, ESCAPED }

    public static final class Survivor {
        final UUID id;
        final String name;
        Status status = Status.FREE;
        int captures;
        long protectedUntil;
        int jailFloor = -1;
        long dashReadyAt;
        long boostUntil;
        double boostValue;
        double appliedBoost;
        long lastHeartbeat;
        /** 心跳用的鬼距離（沿路），每半秒更新一次。 */
        double heartDist = Double.POSITIVE_INFINITY;
        long heartDistTick = -1_000;
        /** 4 段心跳：現在播第幾段（0 = 沒有）、這段什麼時候開始播。 */
        int heartStage;
        long heartStageStart;
        Location lastSample;
        long stillSince;
        boolean inHole;
        double holeBonus;

        Survivor(Player p) {
            this.id = p.getUniqueId();
            this.name = p.getName();
        }
    }

    static final NamespacedKey JUMP_KEY = new NamespacedKey("ghosthunt", "no_jump");
    static final NamespacedKey GHOST_SPEED_KEY = new NamespacedKey("ghosthunt", "ghost_speed");
    static final NamespacedKey BOOST_KEY = new NamespacedKey("ghosthunt", "boost");
    static final NamespacedKey FATIGUE_KEY = new NamespacedKey("ghosthunt", "fatigue");
    static final NamespacedKey HOLE_SNEAK_KEY = new NamespacedKey("ghosthunt", "hole_sneak");
    static final NamespacedKey HOLE_SPEED_KEY = new NamespacedKey("ghosthunt", "hole_speed");

    final Plugin plugin;
    final Building b;
    final World world;
    final GameSettings s;
    final GhostType type;
    /** 鬼的 UUID（真人鬼是玩家，AI 鬼開局生出來後換成怪物的）。 */
    UUID ghost;
    final String ghostName;
    final Map<UUID, Survivor> survivors = new LinkedHashMap<>();
    /** 這局的隨機種子（大腦和執行共用，寫進記錄，出 bug 時可以用同一組種子重跑）。 */
    final long rngSeed = java.util.concurrent.ThreadLocalRandom.current().nextLong();
    final Random rnd = new Random(rngSeed);
    /** 聲音等級：低（翻木桶、腳步、掃描器）＜ 中（開關門、推倒櫃子、卡門）＜ 高（刷卡、授權、開／修電源）。 */
    static final int NOISE_LOW = 0, NOISE_MID = 1, NOISE_HIGH = 2;
    /** 地圖變了（障礙物、卡門、打大洞口、撬開地洞）就加一，AI 鬼看到就重新找路。 */
    long mapVersion;
    /** 測試用的假逃生者（/gh dummy）。 */
    final TestDummies dummies = new TestDummies(this);

    long tick;
    boolean released;
    boolean ended;
    long ghostCaptureReady;
    /** 攻擊後疲勞：這段時間鬼比逃生者慢，也抓不到人。 */
    long fatigueUntil;
    private boolean fatigueApplied;
    private double ghostBonus;
    boolean glowing;
    private final Map<Integer, Integer> rescueProgress = new HashMap<>();
    private final Map<Integer, Integer> campTime = new HashMap<>();
    private final Map<Integer, Integer> campAway = new HashMap<>();
    /** 已經被鬼打大的蹲伏破洞（編號）。 */
    private final java.util.Set<Integer> widened = new java.util.HashSet<>();
    private long lastHoleMsg;
    /** 障礙物：門口編號 → 是否已放。 */
    private final Map<Integer, Boolean> barricaded = new HashMap<>();
    /** 已經推倒的櫃子（b.toppleSpots 的編號）。 */
    private final java.util.Set<Integer> toppled = new java.util.HashSet<>();
    /** 雙開門：下半部 → 旁邊那扇的下半部。 */
    private final Map<Pos, Pos> doorPair = new HashMap<>();
    /** 被鬼撬開的地下通道出入口（b.hatches 的編號）。 */
    private final java.util.Set<Integer> pried = new java.util.HashSet<>();
    /** 被卡住的門（下半部）、這局已經卡過的門。 */
    private final java.util.Set<Pos> jammed = new java.util.HashSet<>(), jamUsed = new java.util.HashSet<>();
    final Nightmare nightmare;
    /** 地圖音效（固定地圖有 [sounds] 才有）。 */
    MapAudio audio;
    /** 地圖機關（迎賓鈴、球車、蒸氣閥門；固定地圖有 [interactions] 才有）。 */
    Gimmicks gimmicks;
    /** PvE：AI 鬼（真人鬼時是 null）。 */
    AiGhost aiGhost;
    final ObjectiveSettings o;
    final Interactions acts;
    final Objectives obj;
    final ItemUse items;
    private Hud hud;
    /** 每局記錄的數據（結束時寫進 plugins/GhostHunt/stats.log）。 */
    final Map<String, Object> stats = new LinkedHashMap<>();
    private String noiseText;
    private long noiseUntil;
    private BukkitTask task;
    private final Runnable onEnd;

    public Match(Plugin plugin, Building b, World world, GameSettings s, GhostType type, Player ghost,
                 List<Player> survivorPlayers, Runnable onEnd) {
        this(plugin, b, world, s, type, ghost.getUniqueId(), ghost.getName(), survivorPlayers, onEnd);
    }

    /** PvE：所有玩家都是逃生者，鬼是 AI（基本鬼）。 */
    public static Match pve(Plugin plugin, Building b, World world, GameSettings s, List<Player> survivors, Runnable onEnd) {
        return pve(plugin, b, world, s, survivors, onEnd, null);
    }

    /** difficulty：easy／normal／hard（null 用 config 的 ai-ghost.difficulty）。 */
    public static Match pve(Plugin plugin, Building b, World world, GameSettings s, List<Player> survivors, Runnable onEnd, String difficulty) {
        return pve(plugin, b, world, s, survivors, onEnd, difficulty, null);
    }

    /** persona：hound／ambusher／watcher／standard／auto（null 用 config 的 ai-ghost.persona）。 */
    public static Match pve(Plugin plugin, Building b, World world, GameSettings s, List<Player> survivors, Runnable onEnd,
                            String difficulty, String persona) {
        return pve(plugin, b, world, s, survivors, onEnd, difficulty, persona, -1);
    }

    /**
     * testDummies ≥ 0：測試模式。玩家照常當逃生者，第一次被抓立刻重生、之後 AI 鬼完全無視他（看不到、聽不到、不感應），
     * 鬼放出來時自動生 testDummies 個假逃生者；這一局不寫 AI 的學習記憶。
     */
    public static Match pve(Plugin plugin, Building b, World world, GameSettings s, List<Player> survivors, Runnable onEnd,
                            String difficulty, String persona, int testDummies) {
        Match m = new Match(plugin, b, world, s, GhostType.BASIC, UUID.randomUUID(), "電腦", survivors, onEnd);
        m.testDummies = testDummies;
        m.aiGhost = new AiGhost(m, difficulty != null ? AiGhostSettings.from(plugin.getConfig(), difficulty) : s.ai());
        m.aiGhost.usePersona(persona);
        return m;
    }

    private Match(Plugin plugin, Building b, World world, GameSettings s, GhostType type, UUID ghostId, String ghostName,
                  List<Player> survivorPlayers, Runnable onEnd) {
        this.plugin = plugin;
        var hc = plugin.getConfig();
        this.heart = new com.ghosthunt.game.ai.Heartbeat(hc.getDouble("heartbeat.range", 32), hc.getDouble("heartbeat.min-bpm", 55),
                hc.getDouble("heartbeat.max-bpm", 150), hc.getDouble("heartbeat.min-volume", 0.3),
                hc.getDouble("heartbeat.max-volume", 1.0), hc.getDouble("heartbeat.close-pitch", 1.15));
        this.heartSound = hc.getString("heartbeat.sound", "minecraft:entity.warden.heartbeat");
        this.heartPath = !"straight".equalsIgnoreCase(hc.getString("heartbeat.distance", "path"));
        this.heartStageSounds = hc.getStringList("heartbeat.stage-sounds");
        List<Double> sd = hc.getDoubleList("heartbeat.stage-distances");
        if (sd.size() != heartStageSounds.size()) sd = List.of(32.0, 20.0, 12.0, 6.0).subList(0, Math.min(4, heartStageSounds.size()));
        this.heartStageDist = sd.stream().mapToDouble(Double::doubleValue).toArray();
        this.heartStageLoop = (int) Math.round(hc.getDouble("heartbeat.stage-loop", 24) * 20);
        this.heartStageVolume = (float) hc.getDouble("heartbeat.stage-volume", 1.0);
        org.bukkit.SoundCategory cat = org.bukkit.SoundCategory.PLAYERS;
        try {
            cat = org.bukkit.SoundCategory.valueOf(hc.getString("heartbeat.category", "players").toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            // 寫錯就用「玩家」
        }
        this.heartCategory = cat;
        this.b = b;
        this.world = world;
        this.s = s;
        this.type = type;
        this.ghost = ghostId;
        this.ghostName = ghostName;
        for (Player p : survivorPlayers) survivors.put(p.getUniqueId(), new Survivor(p));
        this.nightmare = type == GhostType.NIGHTMARE ? new Nightmare(this) : null;
        this.onEnd = onEnd;
        this.o = ObjectiveSettings.from(plugin.getConfig());
        this.acts = new Interactions(this);
        this.obj = new Objectives(this);
        this.items = new ItemUse(this);
        this.audio = MapAudio.create(this);
        this.gimmicks = Gimmicks.create(this);
    }

    // ---- 開始 ----

    /** 固定地圖：電源所在房間的門口告示牌加一行「⚡ 電源」（電源位置每局開局才決定）；on = false 時改回原本的字。 */
    void labelPowerRoom(boolean on) {
        if (b.placedLever == null || b.signRegion.isEmpty()) return;
        Integer key = b.powerCandidateRegion.get(b.placedLever.add(0, -1, 0));
        if (key == null) return;
        for (Map.Entry<Pos, Integer> e : b.signRegion.entrySet()) {
            if (!e.getValue().equals(key)) continue;
            if (!(block(e.getKey()).getState() instanceof org.bukkit.block.Sign sign)) continue;
            String[] lines = b.signs.getOrDefault(e.getKey(), new String[0]);
            var side = sign.getSide(org.bukkit.block.sign.Side.FRONT);
            // 跟 GhostHuntPlugin.writeSigns 一樣的排法：三行以內從第二行開始；「⚡ 電源」放在字下面第一個空行（滿了就蓋最後一行）
            int off = lines.length >= 4 ? 0 : 1;
            String[] show = {"", "", "", ""};
            for (int i = 0; i < lines.length && i + off < 4; i++) show[i + off] = lines[i];
            if (on) show[Math.min(3, lines.length + off)] = "⚡ 電源";
            for (int i = 0; i < 4; i++) side.line(i, Component.text(show[i], com.ghosthunt.GhostHuntPlugin.signLineColor(show[i])));
            side.setGlowingText(true);
            sign.update(true, false);
        }
    }

    public void start() {
        resetBuilding();
        obj.setup();
        labelPowerRoom(true);
        stats.put("seed", b.seed);
        stats.put("ghostType", type.name());
        stats.put("survivors", survivors.size());
        stats.put("captures", 0);
        stats.put("rescues", 0);
        LivingEntity g;
        Player gp = null;
        if (aiGhost != null) {
            // PvE：在鬼房生出 AI 鬼，鬼的 UUID 換成牠的
            holdChunks(plugin, true); // 玩家走遠時 AI 鬼也照樣會動
            allowMonsters(true);
            org.bukkit.entity.Mob mob = aiGhost.spawn(loc(b.ghostRoomCenter));
            ghost = mob.getUniqueId();
            g = mob;
            stats.put("mode", "pve");
            stats.put("rngSeed", rngSeed);
        } else {
            gp = ghostPlayer();
            preparePlayer(gp);
            // 鬼自帶夜視：停電、熄燈時也看得清楚（逃生者沒有）
            if (s.ghostNightVision())
                gp.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, PotionEffect.INFINITE_DURATION, 0, false, false));
            gp.teleport(loc(b.ghostRoomCenter));
            if (nightmare != null) gp.getInventory().setItem(0, Items.lightsOut());
            gp.getInventory().setItem(1, Items.moveCard());
            g = gp;
            stats.put("mode", "pvp");
        }
        double bonus = GameSettings.bySurvivors(s.ghostSpeedBySurvivors(), survivors.size());
        ghostBonus = bonus;
        g.getAttribute(Attribute.MOVEMENT_SPEED).addTransientModifier(
                new AttributeModifier(GHOST_SPEED_KEY, bonus, AttributeModifier.Operation.ADD_SCALAR));

        List<Pos> spawns = pickSpawns();
        int i = 0;
        for (Survivor sv : survivors.values()) {
            Player p = Bukkit.getPlayer(sv.id);
            if (p == null) continue;
            preparePlayer(p);
            p.getInventory().setItem(0, Items.barricade(1));
            p.teleport(loc(spawns.get(i++ % spawns.size())));
            sv.lastSample = p.getLocation();
            p.showTitle(Title.title(Component.text("你是逃生者", NamedTextColor.GREEN),
                    Component.text((aiGhost != null ? "鬼是電腦（AI）" : "鬼是 " + ghostName + "（" + type.label + "）") + "｜按 F 衝刺",
                            NamedTextColor.GRAY)));
        }
        if (gp != null) gp.showTitle(Title.title(Component.text("你是" + type.label, NamedTextColor.DARK_RED),
                Component.text((s.releaseDelay() / 20) + " 秒後放你出去", NamedTextColor.GRAY)));
        broadcast("遊戲開始！鬼：" + (aiGhost != null ? "電腦（AI）" : ghostName + "（" + type.label + "）")
                + "，逃生者 " + survivors.size() + " 人。", NamedTextColor.GOLD);
        briefing(gp);
        hud = new Hud(this);
        hud.show();
        refreshSurvivorFog();

        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    /**
     * 不需要玩家的自我檢查：跑一次開局的場地準備（拉桿、容器內容、上鎖、大門、燈、計分板），
     * 再模擬開電源、關電源、開大門，最後回報結果。用來在真的開局前抓錯。
     */
    public static List<String> selfTest(Plugin plugin, Building b, World world, GameSettings s, java.util.function.Consumer<String> later) {
        Match m = new Match(plugin, b, world, s, GhostType.NIGHTMARE, UUID.randomUUID(), "selftest", List.of(), () -> { });
        m.resetBuilding();
        m.obj.setup();
        List<String> out = new ArrayList<>(m.obj.selfTestReport());
        m.hud = new Hud(m);
        m.hud.update();
        m.hud.hide();
        out.addAll(m.obj.selfTestActions());
        m.refreshLights();
        out.add("燈光（沒電）：" + m.ambientLight());
        if (m.gimmicks != null) out.addAll(m.gimmicks.selfTest());
        for (Building.Gate gt : b.gates) {
            if (gt.floor() == 0) continue;
            int ladders = 0;
            Location ex = m.loc(gt.exit());
            for (int dx = -6; dx <= 6; dx++)
                for (int dz = -6; dz <= 6; dz++)
                    for (int y = b.layout.floorY(0); y <= ex.getBlockY() + 1; y++)
                        if (world.getBlockAt(ex.getBlockX() + dx, y, ex.getBlockZ() + dz).getType() == org.bukkit.Material.LADDER) ladders++;
            out.add((gt.floor() + 1) + "F 大門外面：陽台" + (ladders > 0 ? "＋梯子 " + ladders + " 格" : "（底下是場地，沒放梯子）"));
        }
        m.aiPathTest(plugin, later);

        return out;
    }

    /**
     * 自我檢查：生一隻 AI 鬼在 1F 起點，等牠落地（剛生出來還沒更新過，尋路一定失敗），
     * 再問尋路找不找得到路到各個關鍵點（電源、大門讀卡機、終端機、監獄），結果用 later 回報，然後移除。
     */
    private void aiPathTest(Plugin plugin, java.util.function.Consumer<String> later) {
        // 用 AI 鬼自己的尋路圖（格子圖 A*）檢查：從 1F 起點走不走得到各個關鍵點（電源、大門讀卡機、終端機、監獄）
        long t0 = System.nanoTime();
        NavMap nm = NavMap.build(this);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        record Target(String name, Location at, double near) {}
        List<Target> targets = new ArrayList<>();
        Location lever = obj.leverLocation();
        if (lever != null) targets.add(new Target("電源開關", lever.clone().add(0, -1, 0), 2.5)); // 站在拉桿下面那格
        for (Building.Gate gt : b.gates) targets.add(new Target((gt.floor() + 1) + "F 大門讀卡機", loc(gt.reader()), 2.5));
        for (Pos t : b.securityTerminals) targets.add(new Target("終端機 " + t, loc(t), 2.5));
        for (Map.Entry<Integer, Pos> e : b.jailCages.entrySet()) targets.add(new Target((e.getKey() + 1) + "F 監獄", loc(e.getValue()), 6.0));
        for (int i = 0; i < b.hatches.size(); i++) targets.add(new Target("地洞出入口 " + i, loc(b.hatches.get(i).trapdoor()).add(0, 1, 0), 1.5));
        int start = nm.nearestMain(loc(b.layout.crossing(0)), 3);
        int ok = 0;
        List<String> fails = new ArrayList<>();
        for (Target tg : targets) {
            int g = nm.nearestMain(tg.at(), tg.near());
            var path = nm.nav.path(start, g, e -> 0);
            if (g >= 0 && !path.isEmpty()) ok++;
            else fails.add(tg.name() + (g < 0 ? "（附近沒有站得住的格子）" : "（找不到路）"));
        }
        later.accept("AI 鬼尋路：尋路圖 " + nm.nav.size() + " 格（" + ms + " ms），找得到路 " + ok + "/" + targets.size()
                + (fails.isEmpty() ? "" : "｜走不到：" + String.join("、", fails)));
        long v0 = System.nanoTime();
        com.ghosthunt.game.ai.VisibilityTable vt = com.ghosthunt.game.ai.VisibilityTable.build(nm.visibilityGrid(), 30);
        later.accept("可見性表：" + nm.nav.size() + " 格、" + (System.nanoTime() - v0) / 1_000_000 + " ms、約 " + vt.approxBytes() / 1024 + " KB");
    }

    private int floorOf(Location l) {
        return Math.max(0, Math.min(b.layout.floors - 1, (int) Math.floor((l.getY() - b.layout.floorY(0) - 1) / b.layout.floorHeight)));
    }

    private org.bukkit.Difficulty savedDifficulty;

    /**
     * 和平難度會把敵對怪物（AI 鬼的身體）一生出來就刪掉：AI 局和自我檢查期間暫時調成簡單，結束後改回來。
     * 大樓世界本來就關掉怪物自然生成，飢餓值也鎖住，AI 鬼不會攻擊也不會受傷，所以調難度沒有副作用。
     */
    void allowMonsters(boolean on) {
        if (on) {
            if (world.getDifficulty() == org.bukkit.Difficulty.PEACEFUL) {
                savedDifficulty = org.bukkit.Difficulty.PEACEFUL;
                world.setDifficulty(org.bukkit.Difficulty.EASY);
                // 伺服器中途當機的話，下次啟動時照這個檔案把難度改回來
                try {
                    java.nio.file.Files.writeString(recoveryFile(plugin), world.getName() + "\n" + savedDifficulty.name());
                } catch (java.io.IOException ignored) {
                }
            }
        } else if (savedDifficulty != null) {
            world.setDifficulty(savedDifficulty);
            savedDifficulty = null;
            try {
                java.nio.file.Files.deleteIfExists(recoveryFile(plugin));
            } catch (java.io.IOException ignored) {
            }
        }
    }

    static java.nio.file.Path recoveryFile(Plugin plugin) {
        return plugin.getDataFolder().toPath().resolve("ai-state.txt");
    }

    /**
     * 插件啟動時：上次 AI 局沒正常結束（當機）就把難度改回來、刪掉殘留的 AI 鬼。
     */
    public static void recoverAfterCrash(Plugin plugin) {
        AiGhost.TAG = new org.bukkit.NamespacedKey(plugin, "ai_ghost");
        java.nio.file.Path f = recoveryFile(plugin);
        try {
            if (java.nio.file.Files.exists(f)) {
                String[] parts = java.nio.file.Files.readString(f).split("\n");
                World w = Bukkit.getWorld(parts[0].trim());
                if (w != null && parts.length > 1) {
                    w.setDifficulty(org.bukkit.Difficulty.valueOf(parts[1].trim()));
                    plugin.getLogger().info("上次 AI 局沒正常結束：把 " + w.getName() + " 的難度改回 " + parts[1].trim());
                }
                java.nio.file.Files.deleteIfExists(f);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("恢復 AI 局狀態失敗：" + e);
        }
        for (World w : Bukkit.getWorlds())
            for (org.bukkit.entity.Entity e : w.getEntities())
                if (e.getPersistentDataContainer().has(AiGhost.TAG)) e.remove();
    }

    /** 把大樓範圍的區塊掛上／拿掉外掛票：沒有玩家在附近時生物也會動（AI 鬼、自我檢查用）。 */
    void holdChunks(Plugin plugin, boolean on) {
        int cx0 = (b.layout.ox - 8) >> 4, cz0 = (b.layout.oz - 8) >> 4;
        int cx1 = (b.layout.ox + b.layout.size + 8) >> 4, cz1 = (b.layout.oz + b.layout.size + 8) >> 4;
        for (int cx = cx0; cx <= cx1; cx++)
            for (int cz = cz0; cz <= cz1; cz++) {
                if (on) world.getChunkAt(cx, cz).addPluginChunkTicket(plugin);
                else world.removePluginChunkTicket(cx, cz, plugin);
            }
    }

    /** 開局說明：每個人這局要做什麼、怎麼操作。 */
    private void briefing(Player g) {
        int nCards = o.cards();
        String[] survivorLines = {
                "【逃生者】目標：從大門逃出去",
                "1. 到電力室開總電源（右鍵拉桿，站 " + o.powerOn() / 20 + " 秒；兩個人一起更快）",
                obj.authRequired()
                        ? "2. 翻木桶找門禁卡（共 " + nCards + " 張），先拿到保全室的終端機授權（右鍵，站 " + o.authorize() / 20 + " 秒，要有電），再去大門旁的讀卡機（磁石）刷，大門倒數 " + o.openCountdown() / 20 + " 秒後打開"
                        : "2. 翻木桶找門禁卡（共 " + nCards + " 張），拿去大門旁的讀卡機（磁石）刷，大門倒數 " + o.openCountdown() / 20 + " 秒後打開",
                "3. 穿過打開的大門就算逃出",
                "操作：右鍵木桶／拉桿／讀卡機 → 站著不動等進度條｜F 衝刺｜蹲下鑽牆上的小洞（鬼過不去）",
                "障礙物：拿著右鍵門口的黃色地毯｜門口旁邊的深色木頭櫃子：右鍵推倒擋住門口｜蹲下右鍵門：卡門（每扇一次）",
                "1F 地上的活板門通到地下通道（爬梯子下去），可以鑽到很遠的另一個房間；鬼要先撬開才能下去",
                "被抓會關進監獄，隊友站在籠子旁邊就能救｜第 " + s.maxCaptures() + " 次被抓淘汰",
                "注意：翻箱子、開電源會發出聲音，附近的鬼會知道方向",
                aiGhost != null ? "這局的鬼是電腦（" + aiGhost.settings().difficulty() + "）：只看得到前方、聽得到腳步和開門聲（蹲著走沒聲音）；"
                        + "太久找不到人時會感應最近的人在哪一間房（被盯上時你會聽到心跳加快）；手電筒照它的臉 1 秒，它會暫時看不到人" : ""};
        String[] ghostLines = {
                "【鬼】目標：把逃生者抓進監獄（鬼房 " + s.releaseDelay() / 20 + " 秒後打開）",
                "左鍵打到就抓到；每揮一次手（揮空也算）" + s.attackFatigue() / 20.0 + " 秒內幾乎走不動、抓不到人，要看準再揮",
                "畫面下方的箭頭 = 附近逃生者做事的聲音方向",
                "右鍵拉桿可以關一次電源；右鍵倒數中的讀卡機可以弄壞大門（每扇一次）",
                "門禁卡位置每 " + o.revealInterval() / 20 + " 秒亮一次：拿「搬卡」右鍵，把卡搬走並鎖起來",
                "右鍵障礙物、推倒的櫃子可以拆掉；右鍵卡住的門可以撞開；右鍵牆上的小洞可以打大；右鍵 1F 的活板門可以撬開地洞",
                nightmare != null ? "夢魘：拿「熄燈」右鍵關掉整棟的燈，熄燈時看得到附近逃生者的軌跡" : ""};
        for (Player p : survivorPlayers()) for (String l : survivorLines) if (!l.isEmpty()) p.sendMessage(Component.text(l, NamedTextColor.GREEN));
        if (g != null) for (String l : ghostLines) if (!l.isEmpty()) g.sendMessage(Component.text(l, NamedTextColor.RED));
    }

    private void preparePlayer(Player p) {
        p.setGameMode(GameMode.ADVENTURE);
        p.getInventory().clear();
        for (PotionEffect e : p.getActivePotionEffects()) p.removePotionEffect(e.getType());
        p.setHealth(p.getAttribute(Attribute.MAX_HEALTH).getValue());
        p.setFoodLevel(20);
        p.setSaturation(20);
        clearModifiers(p);
        p.getAttribute(Attribute.JUMP_STRENGTH).addTransientModifier(
                new AttributeModifier(JUMP_KEY, -1, AttributeModifier.Operation.MULTIPLY_SCALAR_1));
    }

    static void clearModifiers(Player p) {
        p.getAttribute(Attribute.JUMP_STRENGTH).removeModifier(JUMP_KEY);
        AttributeInstance sp = p.getAttribute(Attribute.MOVEMENT_SPEED);
        sp.removeModifier(GHOST_SPEED_KEY);
        sp.removeModifier(BOOST_KEY);
        sp.removeModifier(FATIGUE_KEY);
        sp.removeModifier(HOLE_SPEED_KEY);
        p.getAttribute(Attribute.SNEAKING_SPEED).removeModifier(HOLE_SNEAK_KEY);
    }

    /** 把上一局留下的東西復原：鬼房封起來、障礙物拿掉、燈恢復、霧恢復。 */
    private void resetBuilding() {
        for (Map.Entry<Pos, BlockData> e : b.ghostDoorBlocks.entrySet()) block(e.getKey()).setBlockData(e.getValue(), false);
        for (Building.BarricadeSpot spot : b.barricadeSpots) removeBarricade(spot);
        for (Building.CrawlHole h : b.crawlHoles) {
            block(h.lower()).setType(Material.AIR, false);
            block(h.slab()).setBlockData(h.slabData(), false);
        }
        for (Building.ToppleSpot t : b.toppleSpots)
            for (Map.Entry<Pos, BlockData> e : t.blocks().entrySet()) block(e.getKey()).setBlockData(e.getValue(), false);
        for (Map.Entry<Pos, BlockData> e : b.doorBlocks.entrySet()) block(e.getKey()).setBlockData(e.getValue(), false);
        for (Building.Hatch h : b.hatches) block(h.trapdoor()).setBlockData(h.closed(), false);
        java.util.Set<Pos> doorSet = new java.util.HashSet<>(b.doors);
        for (Pos d : b.doors) {
            for (int[] dd : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                Pos o2 = d.add(dd[0], 0, dd[1]);
                if (doorSet.contains(o2)) doorPair.put(d, o2);
            }
        }
        setLights(s.emergencyLight());
        applyFog("off"); // 世界本身不放霧（鬼看不到霧），霧只用封包送給逃生者
    }

    /** 逃生者出生點：離鬼房至少 25 格、彼此至少 10 格，優先放在跟鬼不同的樓層。 */
    private List<Pos> pickSpawns() {
        List<Pos> all = new ArrayList<>();
        int gf = b.ghostRoom.floor();
        for (Map.Entry<Integer, java.util.Set<Pos>> e : b.spawnTiles.entrySet()) {
            if (e.getKey() != gf) all.addAll(e.getValue());
        }
        if (all.isEmpty()) b.spawnTiles.values().forEach(all::addAll);
        Collections.shuffle(all, rnd);
        for (double minGhost = 25, minEach = 10; minGhost >= 0; minGhost -= 5, minEach = Math.max(0, minEach - 2)) {
            List<Pos> chosen = new ArrayList<>();
            for (Pos p : all) {
                if (p.distance(b.ghostRoomCenter) < minGhost) continue;
                boolean ok = true;
                for (Pos q : chosen) if (p.distance(q) < minEach) ok = false;
                if (!ok) continue;
                chosen.add(p);
                if (chosen.size() == survivors.size()) return chosen;
            }
        }
        return all.subList(0, Math.max(1, Math.min(all.size(), survivors.size())));
    }

    // ---- 每 tick ----

    private void tick() {
        if (ended) return;
        tick++;
        LivingEntity g = ghostEntity();
        if (g == null || !g.isValid()) {
            end(aiGhost != null ? "AI 鬼不見了（被其他插件或世界設定刪掉？）" : "鬼離開了");
            return;
        }
        if (!released && tick >= s.releaseDelay()) release();
        applyFatigue(g);

        for (Survivor sv : survivors.values()) {
            Player p = Bukkit.getPlayer(sv.id);
            if (p == null) continue;
            applyBoost(sv, p);
            if (sv.status != Status.FREE) {
                if (sv.inHole) setHoleSpeed(sv, p, false);
                stopHeartbeat(sv, p);
                continue;
            }
            heartbeat(sv, p, g);
            trackStillness(sv, p);
            holeSpeed(sv, p);
            if (glowing && !p.hasPotionEffect(PotionEffectType.GLOWING)) {
                p.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, PotionEffect.INFINITE_DURATION, 0, false, false));
            }
        }
        if (!glowing && tick >= s.glowStart()) {
            glowing = true;
            broadcast("15 分鐘了，還沒逃出的人全部發光！", NamedTextColor.RED);
        }
        rescues(g);
        acts.tick();
        obj.tick(g);
        if (tick % 20 == 0 && hud != null) hud.update();
        items.tick(g);
        if (aiGhost != null) aiGhost.tick();
        dummies.tick(g);
        escapes();
        if (nightmare != null) nightmare.tick(g);
        if (audio != null && tick % 20 == 0) audio.tick();
        if (gimmicks != null) gimmicks.tick();
        if (tick % 10 == 0 && g instanceof Player gp) ghostHud(gp);
        if (tick % 200 == 0) refreshSurvivorFog();
        if (tick % 10 == 0) closeHatches();

        // 只有鬼一個人時（單人測試用）不會因為沒有逃生者而結束
        if (!survivors.isEmpty() && freeCount() == 0) end("場上沒有能自由行動的逃生者");
        else if (tick >= s.timeLimit()) end("時間到");
    }

    private void release() {
        released = true;
        ghostCaptureReady = tick;
        for (Pos p : b.ghostDoorBlocks.keySet()) block(p).setType(Material.AIR, false);
        Player g = ghostPlayer();
        world.playSound(loc(b.ghostRoomCenter), Sound.BLOCK_IRON_DOOR_OPEN, 4f, 0.5f);
        broadcast("鬼出來了！", NamedTextColor.DARK_RED);
        if (g != null) g.showTitle(Title.title(Component.empty(), Component.text("去抓人吧", NamedTextColor.RED),
                Title.Times.times(Duration.ZERO, Duration.ofSeconds(2), Duration.ofMillis(500))));
        if (nightmare != null) nightmare.onRelease();
        obj.onRelease();
        if (testMode() && aiGhost != null && testDummies > 0) {
            dummies.spawn(testDummies);
            broadcast("［測試］生了 " + testDummies + " 個假逃生者。", NamedTextColor.GRAY);
        }
    }

    // ---- 抓人 ----

    /** 鬼用手打到逃生者。回傳 true 表示有處理（事件要取消）。 */
    public void ghostHit(Player victim) {
        Survivor sv = survivors.get(victim.getUniqueId());
        if (sv == null || sv.status != Status.FREE || !released) return;
        LivingEntity g = ghostEntity();
        if (g == null) return;
        if (tick < sv.protectedUntil) {
            ghostMessage(Component.text(victim.getName() + " 剛被救出來，暫時抓不到", NamedTextColor.GRAY));
            ghostSwing();
            return;
        }
        if (tick < fatigueUntil) return; // 疲勞中抓不到人
        if (tick < ghostCaptureReady) {
            ghostSwing();
            return;
        }
        ghostSwing();
        ghostCaptureReady = tick + s.captureCooldown();
        sv.captures++;
        sv.boostUntil = 0;
        acts.cancel(sv.id, null);
        obj.dropCards(victim, victim.getLocation());
        stats.merge("captures", 1, (a, c) -> (Integer) a + (Integer) c);
        stat("firstCaptureAt", tick / 20, false);
        if (aiGhost != null) aiGhost.onCatch(victim.getUniqueId());
        victim.removePotionEffect(PotionEffectType.GLOWING);
        world.playSound(victim.getLocation(), Sound.ENTITY_PLAYER_HURT, 2f, 0.6f);
        if (testMode()) {
            // 測試模式：不關監獄，立刻在出生點重生；之後 AI 鬼完全無視他，可以在旁邊看它追假逃生者
            aiIgnored.add(victim.getUniqueId());
            List<Pos> sp = pickSpawns();
            if (!sp.isEmpty()) victim.teleport(loc(sp.get(0)));
            victim.showTitle(Title.title(Component.text("被抓了（測試模式）", NamedTextColor.YELLOW),
                    Component.text("已重生，之後鬼會無視你", NamedTextColor.GRAY)));
            broadcast("［測試］" + victim.getName() + " 被抓了：已重生，之後 AI 鬼無視他。", NamedTextColor.GRAY);
            return;
        }
        if (sv.captures >= s.maxCaptures()) {
            sv.status = Status.ELIMINATED;
            victim.setGameMode(GameMode.SPECTATOR);
            victim.showTitle(Title.title(Component.text("你被淘汰了", NamedTextColor.DARK_RED),
                    Component.text("可以觀戰剩下的人", NamedTextColor.GRAY)));
            broadcast(victim.getName() + " 第 " + sv.captures + " 次被抓，淘汰！", NamedTextColor.RED);
        } else {
            int f = farthestJail(g);
            sv.status = Status.JAILED;
            sv.jailFloor = f;
            victim.teleport(loc(b.jailCages.get(f)));
            victim.showTitle(Title.title(Component.text("被抓了", NamedTextColor.RED),
                    Component.text("關在 " + (f + 1) + "F 監獄，等隊友來救", NamedTextColor.GRAY)));
            broadcast(victim.getName() + " 被抓了，關在 " + (f + 1) + "F 監獄。", NamedTextColor.YELLOW);
        }
    }

    private int farthestJail(LivingEntity g) {
        int best = 0;
        double bd = -1;
        for (Map.Entry<Integer, Pos> e : b.jailCages.entrySet()) {
            double d = loc(e.getValue()).distance(g.getLocation());
            if (d > bd) {
                bd = d;
                best = e.getKey();
            }
        }
        return best;
    }

    /** 鬼揮一次手（打到或揮空都算）：接下來一段時間大幅減速（預設只剩 10%）。疲勞中再揮不會延長。 */
    public void ghostSwing() {
        if (!released || tick < fatigueUntil) return;
        fatigueUntil = tick + s.attackFatigue();
    }

    private void applyFatigue(LivingEntity g) {
        boolean want = tick < fatigueUntil;
        if (want == fatigueApplied) return;
        AttributeInstance sp = g.getAttribute(Attribute.MOVEMENT_SPEED);
        sp.removeModifier(FATIGUE_KEY);
        if (want) {
            // 最後再乘上 (1 - fatigueSlow)：0.9 = 速度只剩 10%
            sp.addTransientModifier(new AttributeModifier(FATIGUE_KEY, -s.fatigueSlow(),
                    AttributeModifier.Operation.MULTIPLY_SCALAR_1));
        }
        fatigueApplied = want;
    }

    // ---- 救援 ----

    private void rescues(LivingEntity g) {
        for (Map.Entry<Integer, Pos> e : b.jailCages.entrySet()) {
            int f = e.getKey();
            Location cage = loc(e.getValue());
            // 守監獄
            boolean ghostNear = sameFloor(g.getLocation(), cage) && horiz(g.getLocation(), cage) <= s.campRadius();
            if (ghostNear) {
                campTime.merge(f, 1, Integer::sum);
                campAway.put(f, 0);
            } else if (campAway.merge(f, 1, Integer::sum) >= s.campRecover()) {
                campTime.put(f, 0);
            }
            List<Survivor> jailed = survivors.values().stream()
                    .filter(v -> v.status == Status.JAILED && v.jailFloor == f).toList();
            if (jailed.isEmpty()) {
                rescueProgress.put(f, 0);
                continue;
            }
            boolean camped = campTime.getOrDefault(f, 0) >= s.campTime();
            int needed = camped ? s.campRescue() : GameSettings.bySurvivors(s.rescueBySurvivors(), survivors.size()) * 20;
            List<Player> rescuers = new ArrayList<>();
            for (Survivor v : survivors.values()) {
                if (v.status != Status.FREE) continue;
                Player p = Bukkit.getPlayer(v.id);
                if (p != null && sameFloor(p.getLocation(), cage) && horiz(p.getLocation(), cage) <= 4.6) rescuers.add(p); // 籠子 6×6，外面一圈離中心約 4 格
            }
            if (rescuers.isEmpty()) {
                rescueProgress.put(f, 0);
                continue;
            }
            int prog = rescueProgress.merge(f, 1, Integer::sum);
            Component bar = Component.text("救援中 " + bar(prog, needed) + (camped ? "（鬼守監獄，救援加快）" : ""), NamedTextColor.AQUA);
            rescuers.forEach(p -> p.sendActionBar(bar));
            jailed.forEach(v -> {
                Player p = Bukkit.getPlayer(v.id);
                if (p != null) p.sendActionBar(bar);
            });
            if (prog >= needed) {
                rescueProgress.put(f, 0);
                Location out = rescuers.get(0).getLocation();
                for (Survivor v : jailed) {
                    Player p = Bukkit.getPlayer(v.id);
                    v.status = Status.FREE;
                    v.jailFloor = -1;
                    v.protectedUntil = tick + s.rescueProtection();
                    boost(v, s.rescueBoostBonus(), s.rescueBoostTime());
                    if (p != null) {
                        p.teleport(out);
                        p.showTitle(Title.title(Component.empty(), Component.text("被救出來了！快跑", NamedTextColor.GREEN),
                                Title.Times.times(Duration.ZERO, Duration.ofSeconds(2), Duration.ofMillis(300))));
                    }
                }
                world.playSound(cage, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1.2f);
                broadcast(rescuers.get(0).getName() + " 救出了 " + jailed.size() + " 個人（" + (f + 1) + "F）。", NamedTextColor.GREEN);
                stats.merge("rescues", 1, (a, c) -> (Integer) a + (Integer) c);
            }
        }
    }

    // ---- 加速（衝刺、被救後）：不疊加，取最高 ----

    void boost(Survivor sv, double value, int ticks) {
        if (tick >= sv.boostUntil || value > sv.boostValue) {
            sv.boostValue = value;
            sv.boostUntil = tick + ticks;
        } else if (value == sv.boostValue) {
            sv.boostUntil = Math.max(sv.boostUntil, tick + ticks);
        }
    }

    private void applyBoost(Survivor sv, Player p) {
        double want = tick < sv.boostUntil && sv.status == Status.FREE ? sv.boostValue : 0;
        if (want == sv.appliedBoost) return;
        AttributeInstance sp = p.getAttribute(Attribute.MOVEMENT_SPEED);
        sp.removeModifier(BOOST_KEY);
        if (want > 0) sp.addTransientModifier(new AttributeModifier(BOOST_KEY, want, AttributeModifier.Operation.ADD_SCALAR));
        sv.appliedBoost = want;
    }

    public void onSwapHand(Player p) {
        Survivor sv = survivors.get(p.getUniqueId());
        if (sv == null || sv.status != Status.FREE) return;
        if (tick < sv.dashReadyAt) {
            p.sendActionBar(Component.text("衝刺冷卻中：" + ((sv.dashReadyAt - tick) / 20 + 1) + " 秒", NamedTextColor.GRAY));
            return;
        }
        boost(sv, s.dashBonus(), s.dashDuration());
        sv.dashReadyAt = tick + s.dashCooldown();
        p.playSound(p.getLocation(), Sound.ENTITY_BREEZE_WIND_BURST, 0.6f, 1.4f);
        p.sendActionBar(Component.text("衝刺！", NamedTextColor.AQUA));
    }

    // ---- 心跳 ----

    private void heartbeat(Survivor sv, Player p, LivingEntity g) {
        if (nightmare != null && nightmare.active) {
            stopHeartbeat(sv, p);
            nightmare.whisper(sv, p, g);
            return;
        }
        if (!p.getWorld().equals(g.getWorld())) {
            stopHeartbeat(sv, p);
            return;
        }
        if (tick - sv.heartDistTick >= 10) {
            sv.heartDistTick = tick;
            sv.heartDist = heartDistance(p.getLocation(), g.getLocation());
        }
        double d = sv.heartDist;
        if (!heartStageSounds.isEmpty()) {
            // 4 段心跳音檔（每段是固定速度的循環）：依距離切換，同一段每 stage-loop 秒重播
            int st = com.ghosthunt.game.ai.Heartbeat.stage(d, heartStageDist, sv.heartStage, 2);
            if (st != sv.heartStage) {
                stopHeartbeat(sv, p);
                sv.heartStage = st;
                if (st > 0) playStage(sv, p);
            } else if (st > 0 && tick - sv.heartStageStart >= heartStageLoop) playStage(sv, p);
            return;
        }
        int interval = heart.intervalTicks(d);
        if (interval < 0 || tick - sv.lastHeartbeat < interval) return;
        sv.lastHeartbeat = tick;
        p.playSound(p.getLocation(), heartSound, heartCategory, (float) heart.volume(d), (float) heart.pitch(d));
    }

    private final com.ghosthunt.game.ai.Heartbeat heart;
    private final List<String> heartStageSounds;
    private final double[] heartStageDist;
    private final int heartStageLoop;
    private final float heartStageVolume;

    private void playStage(Survivor sv, Player p) {
        sv.heartStageStart = tick;
        // 跟著玩家播（音檔是單聲道：播在固定位置的話，走離那裡 16 格以上就聽不到了）
        p.playSound(p, heartStageSounds.get(sv.heartStage - 1), heartCategory, heartStageVolume, 1f);
    }

    /** 停掉正在播的那段心跳（被抓、熄燈、遊戲結束、鬼離太遠）。 */
    void stopHeartbeat(Survivor sv, Player p) {
        if (sv.heartStage > 0 && !heartStageSounds.isEmpty() && p != null)
            p.stopSound(heartStageSounds.get(sv.heartStage - 1), heartCategory);
        sv.heartStage = 0;
    }
    private final String heartSound;
    private final boolean heartPath;
    /** 心跳走哪個音量滑桿（預設「玩家」：每個人可以在音效設定裡自己調，不影響其他聲音）。 */
    private final org.bukkit.SoundCategory heartCategory;

    /**
     * 心跳的距離：AI 局用鬼的尋路圖算「鬼走過來要走的路」（不分樓層，沿樓梯；地洞每條邊 +2）；
     * 真人鬼的局、尋路圖還沒建好、不在圖上時用直線距離。
     */
    private double heartDistance(Location p, Location g) {
        double straight = p.distance(g);
        if (!heartPath || aiGhost == null || aiGhost.navMap() == null || straight > heart.range()) return straight;
        NavMap nav = aiGhost.navMap();
        int a = nav.nearest(g, 3), b = nav.nearest(p, 3);
        if (a < 0 || b < 0) return straight;
        com.ghosthunt.game.ai.SoundField.Heard h = com.ghosthunt.game.ai.SoundField.hear(nav.nav, a, b, heart.range(),
                e -> e.kind() == com.ghosthunt.game.ai.GridNav.Kind.HATCH ? 2 : 0, 0);
        return h == null ? Double.POSITIVE_INFINITY : h.distance();
    }

    private void trackStillness(Survivor sv, Player p) {
        if (tick % 5 != 0) return;
        Location now = p.getLocation();
        if (sv.lastSample == null || !sv.lastSample.getWorld().equals(now.getWorld())
                || sv.lastSample.distanceSquared(now) > 0.01) {
            sv.stillSince = tick;
        }
        sv.lastSample = now;
    }

    // ---- 障礙物 ----

    /** 逃生者右鍵門口的記號放障礙物。回傳 true 表示有處理。 */
    public boolean tryPlaceBarricade(Player p, Block clicked) {
        Survivor sv = survivors.get(p.getUniqueId());
        if (sv == null || sv.status != Status.FREE || clicked == null) return false;
        int idx = spotAt(clicked);
        if (idx < 0) {
            p.sendActionBar(Component.text("障礙物只能放在門口地上的黃色記號", NamedTextColor.GRAY));
            return true;
        }
        if (barricaded.getOrDefault(idx, false)) return true;
        Building.BarricadeSpot spot = b.barricadeSpots.get(idx);
        if (doorwayOccupied(spot)) {
            p.sendActionBar(Component.text("門口有人，放不下去", NamedTextColor.GRAY));
            return true;
        }
        for (Pos q : spot.blocks()) block(q).setType(Material.SPRUCE_PLANKS, false);
        barricaded.put(idx, true);
        mapVersion++;
        org.bukkit.inventory.ItemStack hand = p.getInventory().getItemInMainHand();
        hand.setAmount(hand.getAmount() - 1);
        world.playSound(clicked.getLocation(), Sound.BLOCK_WOOD_BREAK, 1.5f, 0.8f);
        p.sendActionBar(Component.text("門口擋起來了", NamedTextColor.GOLD));
        return true;
    }

    /** 門口有沒有人（放障礙物、推倒櫃子前檢查，免得把人卡在裡面）。 */
    private boolean doorwayOccupied(Building.BarricadeSpot spot) {
        for (Pos q : spot.blocks()) {
            org.bukkit.util.BoundingBox box = org.bukkit.util.BoundingBox.of(block(q));
            for (Player other : world.getPlayers()) {
                if (other.getGameMode() != GameMode.SPECTATOR && other.getBoundingBox().overlaps(box)) return true;
            }
        }
        return false;
    }

    // ---- 推倒櫃子 ----

    private int toppleAt(Block clicked) {
        Pos p = new Pos(clicked.getX(), clicked.getY(), clicked.getZ());
        for (int i = 0; i < b.toppleSpots.size(); i++) if (b.toppleSpots.get(i).blocks().containsKey(p)) return i;
        return -1;
    }

    /**
     * 逃生者右鍵門口旁邊的櫃子：馬上推倒，把門口擋住（跟障礙物一樣，鬼要花時間拆）。每個櫃子一局一次。
     * 回傳 true 表示點到的是櫃子（事件要取消）。
     */
    public boolean tryTopple(Player p, Block clicked) {
        if (clicked == null) return false;
        int i = toppleAt(clicked);
        if (i < 0 || toppled.contains(i)) return false;
        if (!isFreeSurvivor(p)) return true;
        Building.ToppleSpot t = b.toppleSpots.get(i);
        int idx = t.barricade();
        if (barricaded.getOrDefault(idx, false)) {
            p.sendActionBar(Component.text("門口已經擋住了", NamedTextColor.GRAY));
            return true;
        }
        Building.BarricadeSpot spot = b.barricadeSpots.get(idx);
        if (doorwayOccupied(spot)) {
            p.sendActionBar(Component.text("門口有人，推不下去", NamedTextColor.GRAY));
            return true;
        }
        for (Pos q : t.blocks().keySet()) block(q).setType(Material.AIR, false);
        for (Pos q : spot.blocks()) {
            org.bukkit.block.data.Orientable log = (org.bukkit.block.data.Orientable) Bukkit.createBlockData(Material.STRIPPED_DARK_OAK_LOG);
            Pos a0 = spot.blocks().get(0), a1 = spot.blocks().get(spot.blocks().size() - 1);
            log.setAxis(a0.x() != a1.x() ? org.bukkit.Axis.X : org.bukkit.Axis.Z);
            block(q).setBlockData(log, false);
        }
        barricaded.put(idx, true);
        mapVersion++;
        toppled.add(i);
        Location at = loc(spot.blocks().get(0));
        world.playSound(at, Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, 3f, 0.7f);
        world.playSound(at, Sound.BLOCK_WOOD_BREAK, 2f, 0.6f);
        noise(at, NOISE_MID);
        p.sendActionBar(Component.text("把櫃子推倒，門口擋住了！", NamedTextColor.GOLD));
        stats.merge("toppled", 1, (x, y) -> (Integer) x + (Integer) y);
        return true;
    }

    // ---- 卡門 ----

    /**
     * 有人右鍵門（單門或雙開門）。回傳 true 表示要取消原版的開關門。
     * 逃生者蹲下右鍵：站著卡門（兩扇一起關上卡住，誰都打不開；每扇門一局一次）。
     * 卡住的門：鬼右鍵撞開（很大聲），逃生者蹲下右鍵可以自己拆掉。
     */
    public boolean onDoor(Player p, Block clicked) {
        if (clicked == null || !org.bukkit.Tag.DOORS.isTagged(clicked.getType())) return false;
        Pos lower = new Pos(clicked.getX(), clicked.getY(), clicked.getZ());
        if (clicked.getBlockData() instanceof org.bukkit.block.data.Bisected bi && bi.getHalf() == org.bukkit.block.data.Bisected.Half.TOP)
            lower = lower.add(0, -1, 0);
        if (!b.doorBlocks.containsKey(lower)) return false;
        final Pos door = lower;
        Pos other = doorPair.get(door);
        Location at = loc(door).add(0, 1, 0);
        String key = "door:" + Math.min(door.hashCode(), other == null ? Integer.MAX_VALUE : other.hashCode());
        if (jammed.contains(door)) {
            if (isGhost(p)) {
                ghostBreakJam(door);
                return true;
            }
            if (isFreeSurvivor(p) && p.isSneaking()) {
                acts.start(p, key, "拆掉卡門", o.jamDoor(), false, false, at, () -> {
                    setJam(door, other, false);
                    world.playSound(at, Sound.BLOCK_CHAIN_BREAK, 1.5f, 1f);
                });
            } else {
                p.sendActionBar(Component.text("門被卡住了" + (isGhost(p) ? "" : "（蹲下右鍵可以拆掉）"), NamedTextColor.GRAY));
            }
            return true;
        }
        if (!isFreeSurvivor(p) || !p.isSneaking()) return false;
        if (jamUsed.contains(door)) {
            p.sendActionBar(Component.text("這扇門已經卡過了，卡不住了", NamedTextColor.GRAY));
            return true;
        }
        acts.start(p, key, "卡門", o.jamDoor(), false, false, at, () -> {
            for (Player q : world.getPlayers()) {
                if (q.getGameMode() == GameMode.SPECTATOR) continue;
                if (q.getBoundingBox().overlaps(org.bukkit.util.BoundingBox.of(block(door)))
                        || other != null && q.getBoundingBox().overlaps(org.bukkit.util.BoundingBox.of(block(other)))) {
                    p.sendActionBar(Component.text("門口有人，卡不住", NamedTextColor.GRAY));
                    return;
                }
            }
            setDoorOpen(door, false);
            if (other != null) setDoorOpen(other, false);
            setJam(door, other, true);
            world.playSound(at, Sound.BLOCK_CHAIN_PLACE, 1.5f, 0.8f);
            p.sendActionBar(Component.text("門卡住了！鬼要花時間撞開", NamedTextColor.GOLD));
            stats.merge("doorsJammed", 1, (x, y) -> (Integer) x + (Integer) y);
        });
        return true;
    }

    private void setJam(Pos door, Pos other, boolean on) {
        mapVersion++;
        for (Pos d : other == null ? List.of(door) : List.of(door, other)) {
            if (on) {
                jammed.add(d);
                jamUsed.add(d);
            } else jammed.remove(d);
        }
    }

    /** AI 鬼開門用：雙開門兩扇一起開（只開一扇的話，打開的門板加上另一扇會把通道擠得比鬼的身體還窄）。 */
    void setDoorOpenPublic(Pos lower, boolean open) {
        setDoorOpen(lower, open);
        Pos other = doorPair.get(lower);
        if (other != null && !jammed.contains(other)) setDoorOpen(other, open);
    }

    private void setDoorOpen(Pos lower, boolean open) {
        for (int dy = 0; dy <= 1; dy++) {
            Block bl = block(lower.add(0, dy, 0));
            if (bl.getBlockData() instanceof org.bukkit.block.data.type.Door d) {
                d.setOpen(open);
                bl.setBlockData(d, false);
            }
        }
    }

    /** 鬼右鍵障礙物開始拆。回傳 true 表示有處理。 */
    public boolean tryBreakBarricade(Player g, Block clicked) {
        if (!g.getUniqueId().equals(ghost) || clicked == null || acts.busy(ghost)) return false;
        int idx = spotAt(clicked);
        if (idx < 0 || !barricaded.getOrDefault(idx, false)) return false;
        ghostBreakBarricade(idx);
        return true;
    }

    // ---- 鬼的能力（真人點擊和 AI 都呼叫這些；規則、時間、聲音都一樣） ----

    /** 拆掉門口的障礙物（或推倒的櫃子）。回傳 true 表示開始拆了。 */
    public boolean ghostBreakBarricade(int idx) {
        LivingEntity g = ghostEntity();
        if (g == null || idx < 0 || idx >= b.barricadeSpots.size() || !barricaded.getOrDefault(idx, false) || acts.busy(ghost)) return false;
        Building.BarricadeSpot spot = b.barricadeSpots.get(idx);
        Location at = loc(spot.blocks().get(0)).add(0, 0.5, 0);
        Interactions.Act a = acts.startAct(g, "break:" + idx, "拆障礙物", s.breakBarricade(), false, false, at, () -> {
            removeBarricade(spot);
            barricaded.put(idx, false);
            mapVersion++;
            world.playSound(at, Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, 6f, 0.9f);
        });
        // 很大聲，全場都聽得到
        if (a != null) a.onTick = () -> {
            if (tick % 10 == 0) world.playSound(at, Sound.ENTITY_ZOMBIE_ATTACK_WOODEN_DOOR, 6f, 0.8f);
        };
        return a != null;
    }

    /** 撞開卡住的門（doorLower 是其中一扇的下半部）。 */
    public boolean ghostBreakJam(Pos doorLower) {
        LivingEntity g = ghostEntity();
        if (g == null || !released || doorLower == null || !jammed.contains(doorLower)) return false;
        Pos other = doorPair.get(doorLower);
        Location at = loc(doorLower).add(0, 1, 0);
        String key = "door:" + Math.min(doorLower.hashCode(), other == null ? Integer.MAX_VALUE : other.hashCode());
        Interactions.Act a = acts.startAct(g, key, "撞開卡住的門", o.breakJam(), false, false, at, () -> {
            setJam(doorLower, other, false);
            setDoorOpen(doorLower, true);
            if (other != null) setDoorOpen(other, true);
            world.playSound(at, Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, 6f, 0.8f);
        });
        if (a != null) a.onTick = () -> {
            if (tick % 10 == 0) world.playSound(at, Sound.ENTITY_ZOMBIE_ATTACK_WOODEN_DOOR, 6f, 0.9f);
        };
        return a != null;
    }

    /** 撬開地下通道的出入口。 */
    public boolean ghostPryHatch(int i) {
        LivingEntity g = ghostEntity();
        if (g == null || !released || i < 0 || i >= b.hatches.size() || pried.contains(i)) return false;
        Building.Hatch h = b.hatches.get(i);
        Location at = loc(h.trapdoor()).add(0, 0.5, 0);
        Interactions.Act a = acts.startAct(g, "pry:" + i, "撬開地洞", o.widenHole(), false, false, at, () -> {
            pried.add(i);
            mapVersion++;
            block(h.trapdoor()).setBlockData(h.ladder(), false); // 換成梯子：大家都能上下
            world.playSound(at, Sound.BLOCK_WOOD_BREAK, 3f, 0.6f);
        });
        if (a != null) a.onTick = () -> {
            if (tick % 10 == 0) world.playSound(at, Sound.BLOCK_WOOD_HIT, 2f, 0.6f);
        };
        return a != null;
    }

    /** 把蹲伏破洞（或暗格入口）打大。 */
    public boolean ghostWidenHole(int i) {
        LivingEntity g = ghostEntity();
        if (g == null || !released || i < 0 || i >= b.crawlHoles.size() || widened.contains(i)) return false;
        Building.CrawlHole h = b.crawlHoles.get(i);
        Location at = loc(h.slab());
        Interactions.Act a = acts.startAct(g, "widen:" + i, "打大破洞", o.widenHole(), false, false, at, () -> {
            widened.add(i);
            mapVersion++;
            block(h.slab()).setType(Material.AIR, false);
            world.playSound(at, Sound.BLOCK_STONE_BREAK, 3f, 0.6f);
        });
        if (a != null) a.onTick = () -> {
            if (tick % 10 == 0) world.playSound(at, Sound.BLOCK_STONE_HIT, 2f, 0.6f);
        };
        return a != null;
    }

    /** 這格是擋住的門口（障礙物、推倒的櫃子）就回傳編號，否則 -1。 */
    public int barricadeAt(Pos p) {
        for (int i = 0; i < b.barricadeSpots.size(); i++) {
            if (!barricaded.getOrDefault(i, false)) continue;
            if (b.barricadeSpots.get(i).blocks().contains(p)) return i;
        }
        return -1;
    }

    /** 這格是卡住的門就回傳那扇門的下半部，否則 null。 */
    public Pos jammedDoorAt(Pos p) {
        if (jammed.contains(p)) return p;
        if (jammed.contains(p.add(0, -1, 0))) return p.add(0, -1, 0);
        return null;
    }

    /** 這格（活板門那格或下面的洞）是還沒撬開的地洞出入口就回傳編號，否則 -1。 */
    public int hatchIndexAt(Pos p) {
        for (int i = 0; i < b.hatches.size(); i++) {
            Pos t = b.hatches.get(i).trapdoor();
            if (p.x() == t.x() && p.z() == t.z() && p.y() <= t.y() + 1 && p.y() >= t.y() - 4 && !pried.contains(i)) return i;
        }
        return -1;
    }

    /** 這格是還沒打大的蹲伏破洞就回傳編號，否則 -1。 */
    public int holeIndexAt(Pos p) {
        int i = holeAt(p.x(), p.y(), p.z());
        return i >= 0 && !widened.contains(i) ? i : -1;
    }

    boolean isDoorJammed(Pos lower) {
        return jammed.contains(lower);
    }

    /** 已經被撬開的地洞出入口。 */
    boolean isHatchPried(int i) {
        return pried.contains(i);
    }

    // ---- 蹲伏破洞 ----

    /** 這格是哪個蹲伏破洞（不管有沒有打大），不是回傳 -1。 */
    int holeAt(Pos p) {
        return holeAt(p.x(), p.y(), p.z());
    }

    /** 第 i 個可推倒的櫃子已經推倒了（AI 看痕跡用）。 */
    boolean isToppled(int i) {
        return toppled.contains(i);
    }

    boolean isHoleWidened(int i) {
        return widened.contains(i);
    }

    /** 這格是哪個門口的障礙物位置（不管現在有沒有擋），不是回傳 -1。 */
    int barricadeSpotAt(Pos p) {
        for (int i = 0; i < b.barricadeSpots.size(); i++) if (b.barricadeSpots.get(i).blocks().contains(p)) return i;
        return -1;
    }

    boolean isBarricadeSpot(Pos p) {
        return barricadeSpotAt(p) >= 0;
    }

    boolean barricadeIndexBarricaded(int i) {
        return barricaded.getOrDefault(i, false);
    }

    private int holeAt(int x, int y, int z) {
        for (int i = 0; i < b.crawlHoles.size(); i++) {
            Building.CrawlHole h = b.crawlHoles.get(i);
            if (h.lower().x() == x && h.lower().z() == z && (h.lower().y() == y || h.slab().y() == y)) return i;
        }
        return -1;
    }

    /** 鬼要走進還沒打大的破洞：擋下來。回傳 true 表示要取消這次移動。 */
    public boolean blocksGhost(Player p, Location to) {
        if (!isGhost(p)) return false;
        int hi = hatchColumn(to);
        if (hi >= 0 && !pried.contains(hi)) {
            if (tick - lastHoleMsg > 40) {
                lastHoleMsg = tick;
                p.sendActionBar(Component.text("鬼下不去地洞（對著活板門右鍵，站 " + o.widenHole() / 20 + " 秒可以撬開）", NamedTextColor.GRAY));
            }
            return true;
        }
        int i = holeAt(to.getBlockX(), to.getBlockY(), to.getBlockZ());
        if (i < 0 || widened.contains(i)) return false;
        if (tick - lastHoleMsg > 40) {
            lastHoleMsg = tick;
            p.sendActionBar(Component.text("鬼鑽不過破洞（對著洞右鍵，站 " + o.widenHole() / 20 + " 秒可以打大）", NamedTextColor.GRAY));
        }
        return true;
    }

    // ---- 地下通道的出入口 ----

    private int hatchAt(Block clicked) {
        Pos p = new Pos(clicked.getX(), clicked.getY(), clicked.getZ());
        for (int i = 0; i < b.hatches.size(); i++) if (b.hatches.get(i).trapdoor().equals(p)) return i;
        return -1;
    }

    /** to 是不是在某個出入口的洞裡（活板門那格到梯子底）。 */
    private int hatchColumn(Location to) {
        for (int i = 0; i < b.hatches.size(); i++) {
            Pos t = b.hatches.get(i).trapdoor();
            if (to.getBlockX() == t.x() && to.getBlockZ() == t.z() && to.getY() < t.y() + 0.99 && to.getY() > t.y() - 4) return i;
        }
        return -1;
    }

    /**
     * 有人右鍵活板門。回傳 null 表示不是出入口；true 表示要取消（鬼不能開，改成開始撬）；false 表示照原版開關。
     * 逃生者可以自由開關、爬下去；鬼要先撬開（站著幾秒），撬開後那個出入口大家都能走、不會再關。
     */
    public Boolean onHatch(Player p, Block clicked) {
        if (clicked == null) return null;
        int i = hatchAt(clicked);
        if (i < 0) return null;
        if (isGhost(p)) {
            ghostPryHatch(i);
            return true;
        }
        return !isFreeSurvivor(p);
    }

    /** 沒人在旁邊的出入口自動關上（免得鬼從打開的洞掉下去）。 */
    private void closeHatches() {
        for (int i = 0; i < b.hatches.size(); i++) {
            if (pried.contains(i)) continue;
            Building.Hatch h = b.hatches.get(i);
            Block bl = block(h.trapdoor());
            if (!(bl.getBlockData() instanceof org.bukkit.block.data.type.TrapDoor td) || !td.isOpen()) continue;
            boolean near = false;
            Location c = loc(h.trapdoor());
            for (Player q : world.getPlayers()) {
                if (q.getGameMode() == GameMode.SPECTATOR) continue;
                Location l = q.getLocation();
                if (Math.abs(l.getX() - c.getX()) < 1.6 && Math.abs(l.getZ() - c.getZ()) < 1.6 && l.getY() > c.getY() - 4.5 && l.getY() < c.getY() + 2.5) near = true;
            }
            if (!near) bl.setBlockData(h.closed(), false);
        }
    }

    /** 鬼右鍵破洞開始打大。回傳 true 表示有處理。 */
    public boolean tryWidenHole(Player g, Block clicked) {
        if (!isGhost(g) || clicked == null || !released) return false;
        int i = holeAt(clicked.getX(), clicked.getY(), clicked.getZ());
        if (i < 0 || widened.contains(i)) return false;
        ghostWidenHole(i);
        return true;
    }

    private void removeBarricade(Building.BarricadeSpot spot) {
        for (Pos q : spot.blocks()) block(q).setType(Material.AIR, false);
        for (Pos q : spot.markers()) block(q).setType(Material.YELLOW_CARPET, false);
    }

    private int spotAt(Block clicked) {
        Pos p = new Pos(clicked.getX(), clicked.getY(), clicked.getZ());
        for (int i = 0; i < b.barricadeSpots.size(); i++) {
            Building.BarricadeSpot spot = b.barricadeSpots.get(i);
            if (spot.blocks().contains(p) || spot.markers().contains(p)) return i;
            // 也可以點門口下面那格地板
            for (Pos m : spot.markers()) if (m.add(0, -1, 0).equals(p)) return i;
        }
        return -1;
    }

    // ---- 鬼的畫面下方資訊 ----

    private void ghostHud(Player g) {
        if (acts.busy(ghost)) return;
        if (tick < noiseUntil && noiseText != null) {
            g.sendActionBar(Component.text(noiseText, NamedTextColor.GOLD));
            return;
        }
        String text;
        if (!released) text = "放出倒數：" + ((s.releaseDelay() - tick) / 20 + 1) + " 秒";
        else if (tick < fatigueUntil) text = "攻擊後疲勞……";
        else text = (nightmare != null ? nightmare.hud() + "｜" : "") + obj.ghostHud(g);
        g.sendActionBar(Component.text(text, NamedTextColor.DARK_PURPLE));
    }

    /**
     * 逃生者做事（翻箱子、開電源、刷卡、用掃描器）發出的聲音：同一層、一定距離內的鬼在畫面下方看到方向箭頭（八個方向），
     * 不給精確位置、牆不擋。夢魘熄燈時（追蹤沒被打斷）另外看到聲音來源的精確位置。
     */
    void noise(Location at) {
        noise(at, NOISE_LOW);
    }

    void noise(Location at, int level) {
        LivingEntity g = ghostEntity();
        if (g == null || !released) return;
        // AI 鬼：聲音沿路傳（關著的門、小洞要多算距離；會沿樓梯傳到別層）
        if (aiGhost != null) aiGhost.hearSound(at, level, o.noiseRadius());
        if (!sameFloor(g.getLocation(), at)) return;
        double d = horiz(g.getLocation(), at);
        if (d > o.noiseRadius()) return;
        ghostArrow(g, at);
    }

    /** 測試用（/gh lure）：敲迎賓鈴，回傳說明。 */
    public String debugLure() {
        if (gimmicks == null) return "這張地圖沒有迎賓鈴";
        Location at = gimmicks.debugLure();
        return at == null ? "這張地圖沒有迎賓鈴" : "敲了迎賓鈴，聲源在 " + at.getBlockX() + "," + at.getBlockY() + "," + at.getBlockZ();
    }

    /** 迎賓鈴的廣播：不管多遠、哪一層，鬼都聽得到（AI 當成高等級聲音；真人鬼另外有方向箭頭）。 */
    void lureNoise(Location at) {
        LivingEntity g = ghostEntity();
        if (g == null || !released) return;
        if (aiGhost != null) aiGhost.hearNoise(at, NOISE_HIGH);
        ghostArrow(g, at);
    }

    private void ghostArrow(LivingEntity g, Location at) {
        double target = Math.toDegrees(Math.atan2(-(at.getX() - g.getLocation().getX()), at.getZ() - g.getLocation().getZ()));
        double rel = ((target - g.getLocation().getYaw()) % 360 + 540) % 360 - 180; // -180..180，正的是右邊
        String[] arrows = {"↓", "↙", "←", "↖", "↑", "↗", "→", "↘"};
        int idx = (int) Math.round((rel + 180) / 45.0) % 8;
        noiseText = "聽到聲音 " + arrows[idx];
        noiseUntil = tick + 30;
        ghostMessage(Component.text(noiseText, NamedTextColor.GOLD));
        if (nightmare != null && nightmare.tracking() && g instanceof Player gp)
            gp.spawnParticle(Particle.SOUL_FIRE_FLAME, at, 12, 0.3, 0.4, 0.3, 0.01);
    }

    // ---- 逃出 ----

    private void escapes() {
        for (Survivor sv : survivors.values()) {
            if (sv.status != Status.FREE) continue;
            Player p = Bukkit.getPlayer(sv.id);
            if (p == null) continue;
            Objectives.GateState gs = obj.escapedThrough(p);
            if (gs == null) continue;
            sv.status = Status.ESCAPED;
            acts.cancel(sv.id, null);
            if (aiGhost != null) aiGhost.forget(sv.id);
            obj.dropCards(p, loc(gs.g.reader()));
            p.removePotionEffect(PotionEffectType.GLOWING);
            p.setGameMode(GameMode.SPECTATOR);
            p.showTitle(Title.title(Component.text("逃出去了！", NamedTextColor.GREEN),
                    Component.text("可以觀戰剩下的人", NamedTextColor.GRAY)));
            broadcast(p.getName() + " 從 " + (gs.g.floor() + 1) + "F 大門逃出去了！", NamedTextColor.GREEN);
            stat("firstEscapeAt", tick / 20, false);
        }
    }

    // ---- 燈 ----

    /** 現在該有的亮度：夢魘熄燈 0、有電正常照明、沒電緊急照明。 */
    int ambientLight() {
        if (nightmare != null && nightmare.active) return 0;
        return obj.powerOn ? o.normalLight() : s.emergencyLight();
    }

    void refreshLights() {
        setLights(ambientLight());
    }

    // ---- 結束 ----

    public void end(String reason) {
        if (ended) return;
        ended = true;
        if (task != null) task.cancel();
        if (nightmare != null) nightmare.stop();
        if (audio != null) audio.stop();
        for (Survivor sv : survivors.values()) stopHeartbeat(sv, Bukkit.getPlayer(sv.id));
        labelPowerRoom(false);
        if (aiGhost != null) {
            aiGhost.statsInto(stats);
            stats.put("dummyCatches", dummies.caught);
            aiGhost.remove();
            holdChunks(plugin, false);
            allowMonsters(false);
        }
        dummies.removeAll();
        acts.cancelAll();
        obj.cleanup();
        items.cleanup();
        if (hud != null) hud.hide();
        personalFog("off", survivorPlayers());
        long secs = tick / 20;
        broadcast("遊戲結束：" + reason + "（" + secs / 60 + " 分 " + secs % 60 + " 秒）", NamedTextColor.GOLD);
        for (Survivor sv : survivors.values()) {
            String st = switch (sv.status) {
                case FREE -> "還在大樓裡";
                case JAILED -> "在監獄";
                case ELIMINATED -> "被淘汰";
                case ESCAPED -> "逃出";
            };
            broadcast(" - " + sv.name + "：" + st + "（被抓 " + sv.captures + " 次）", NamedTextColor.WHITE);
        }
        List<UUID> all = new ArrayList<>(survivors.keySet());
        all.add(ghost);
        Location back = loc(b.layout.crossing(0));
        for (UUID id : all) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            clearModifiers(p);
            for (PotionEffect e : p.getActivePotionEffects()) p.removePotionEffect(e.getType());
            p.getInventory().clear();
            p.setGameMode(GameMode.ADVENTURE);
            p.teleport(back);
        }
        writeStats(reason);
        onEnd.run();
    }

    /** 記一個數據；list = false 時只記第一次（例如「第一次抓人的時間」），list = true 時累積成清單。 */
    void stat(String key, Object value, boolean list) {
        if (list) {
            @SuppressWarnings("unchecked")
            List<Object> l = (List<Object>) stats.computeIfAbsent(key, k -> new ArrayList<>());
            l.add(value);
        } else {
            stats.putIfAbsent(key, value);
        }
    }

    private void writeStats(String reason) {
        stats.put("endReason", reason);
        stats.put("durationSec", tick / 20);
        int escaped = 0, eliminated = 0, jailed = 0, free = 0;
        for (Survivor sv : survivors.values()) {
            switch (sv.status) {
                case ESCAPED -> escaped++;
                case ELIMINATED -> eliminated++;
                case JAILED -> jailed++;
                case FREE -> free++;
            }
        }
        stats.put("escaped", escaped);
        if (aiGhost != null) aiGhost.endMatch(survivors.size(), escaped); // 學習：個性拉桿、舊資料淡掉、存檔
        stats.put("eliminated", eliminated);
        stats.put("jailedAtEnd", jailed);
        stats.put("freeAtEnd", free);
        StringBuilder sb = new StringBuilder(java.time.LocalDateTime.now().withNano(0).toString());
        stats.forEach((k, v) -> sb.append(' ').append(k).append('=').append(v));
        try {
            java.nio.file.Path f = plugin.getDataFolder().toPath().resolve("stats.log");
            java.nio.file.Files.createDirectories(f.getParent());
            java.nio.file.Files.writeString(f, sb + System.lineSeparator(), java.nio.charset.StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (java.io.IOException e) {
            plugin.getLogger().warning("寫入 stats.log 失敗：" + e.getMessage());
        }
    }

    public void onQuit(Player p) {
        if (p.getUniqueId().equals(ghost)) {
            end("鬼離開了");
            return;
        }
        Survivor sv = survivors.get(p.getUniqueId());
        if (sv != null && sv.status == Status.FREE) {
            sv.status = Status.ELIMINATED;
            acts.cancel(sv.id, null);
            if (aiGhost != null) aiGhost.forget(sv.id);
            obj.dropCards(p, p.getLocation());
            broadcast(p.getName() + " 離開了遊戲。", NamedTextColor.GRAY);
        }
        clearModifiers(p);
    }

    // ---- 工具 ----

    public boolean isGhost(Player p) {
        return p.getUniqueId().equals(ghost);
    }

    public boolean isParticipant(Player p) {
        return isGhost(p) || survivors.containsKey(p.getUniqueId());
    }

    public boolean isFreeSurvivor(Player p) {
        Survivor sv = survivors.get(p.getUniqueId());
        return sv != null && sv.status == Status.FREE;
    }

    /** 線上的參加者（鬼＋所有逃生者）。 */
    List<Player> participants() {
        List<Player> out = new ArrayList<>();
        Player g = ghostPlayer();
        if (g != null) out.add(g);
        out.addAll(survivorPlayers());
        return out;
    }

    List<Player> freeSurvivors() {
        List<Player> out = new ArrayList<>();
        for (Survivor sv : survivors.values()) {
            Player p = Bukkit.getPlayer(sv.id);
            if (p != null && sv.status == Status.FREE) out.add(p);
        }
        return out;
    }

    /** 只給逃生者看的訊息（鬼看不到）。 */
    void survivorMessage(String text, NamedTextColor color) {
        Component c = Component.text(text, color);
        survivorPlayers().forEach(p -> p.sendMessage(c));
    }

    public boolean isAi() {
        return aiGhost != null;
    }

    // ---- 測試模式 ----

    /** ≥ 0：測試模式（放鬼時自動生這麼多假逃生者）；-1：一般局。 */
    int testDummies = -1;
    /** 測試模式裡被抓過一次的玩家：AI 鬼之後完全無視。 */
    final java.util.Set<UUID> aiIgnored = new java.util.HashSet<>();

    public boolean testMode() {
        return testDummies >= 0;
    }

    /** AI 鬼要不要無視這個人（看、聽、感應、做事的聲音都不算）。 */
    boolean aiIgnores(UUID id) {
        return aiIgnored.contains(id);
    }

    /** 這群人做事的聲音：全部都被無視時不發聲音。 */
    boolean aiIgnoresAll(java.util.Collection<UUID> ids) {
        if (ids.isEmpty() || aiIgnored.isEmpty()) return false;
        for (UUID id : ids) if (!aiIgnored.contains(id)) return false;
        return true;
    }

    public void spawnDummies(int n) {
        dummies.spawn(n);
    }

    /** 切換這個玩家的 AI 鬼除錯顯示，回傳切換後是否開著。 */
    public boolean toggleAiDebug(Player p) {
        if (aiGhost == null) return false;
        if (!aiGhost.debugViewers.remove(p.getUniqueId())) {
            aiGhost.debugViewers.add(p.getUniqueId());
            return true;
        }
        return false;
    }

    /** 鬼的實體：真人鬼是 Player，AI 鬼是怪物；不在線／不存在回傳 null。 */
    LivingEntity ghostEntity() {
        return Bukkit.getEntity(ghost) instanceof LivingEntity le ? le : null;
    }

    /** 真人鬼（AI 鬼時回傳 null）。 */
    Player ghostPlayer() {
        return Bukkit.getPlayer(ghost);
    }

    boolean isGhostId(java.util.UUID id) {
        return ghost.equals(id);
    }

    /** 只有真人鬼才看得到的畫面下方提示。 */
    void ghostMessage(Component c) {
        Player g = ghostPlayer();
        if (g != null) g.sendActionBar(c);
    }

    /** 只有真人鬼才看得到的標題。 */
    void ghostTitle(Title t) {
        Player g = ghostPlayer();
        if (g != null) g.showTitle(t);
    }

    int freeCount() {
        int n = 0;
        for (Survivor sv : survivors.values()) if (sv.status == Status.FREE) n++;
        return n;
    }

    Location loc(Pos p) {
        return new Location(world, p.x() + 0.5, p.y(), p.z() + 0.5);
    }

    Block block(Pos p) {
        return world.getBlockAt(p.x(), p.y(), p.z());
    }

    static double horiz(Location a, Location c) {
        double dx = a.getX() - c.getX(), dz = a.getZ() - c.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    boolean sameFloor(Location a, Location c) {
        return a.getWorld().equals(c.getWorld()) && Math.abs(a.getY() - c.getY()) < b.layout.floorHeight - 1;
    }

    void setLights(int level) {
        for (Pos p : b.lights) {
            Block bl = block(p);
            Levelled d = (Levelled) Bukkit.createBlockData(Material.LIGHT);
            d.setLevel(level);
            bl.setBlockData(d, false);
        }
    }

    /** "off" 或 FogExperiment.DISTANCES 裡的數字；找不到時回傳 null。 */
    Biome fogBiome(String setting) {
        Biome biome;
        if (setting == null || setting.equalsIgnoreCase("off")) biome = FogExperiment.defaultBiome();
        else {
            try {
                biome = FogExperiment.biome(Integer.parseInt(setting.trim()));
            } catch (NumberFormatException e) {
                biome = null;
            }
        }
        if (biome == null) {
            plugin.getLogger().warning("霧的設定 " + setting + " 找不到對應的生態域（要用 " + FogExperiment.DISTANCES + " 或 off，並重開伺服器載入資料包）");
        }
        return biome;
    }

    /** 整個世界換成這個霧（所有人都看得到）。 */
    void applyFog(String setting) {
        Biome biome = fogBiome(setting);
        if (biome != null) FogExperiment.apply(world, b.layout, biome);
    }

    /**
     * 只給這些玩家看 setting 的霧，世界本身（和鬼）沒有霧。
     * 送封包失敗時退回成所有人都有霧。
     */
    void personalFog(String setting, java.util.Collection<Player> players) {
        Biome show = fogBiome(setting);
        Biome base = FogExperiment.defaultBiome();
        if (show == null || base == null) return;
        if (!FogExperiment.sendPersonal(world, b.layout, show, base, players, plugin.getLogger())) {
            FogExperiment.apply(world, b.layout, show);
        }
    }

    /** 逃生者現在該看到的霧：熄燈中用熄燈的霧，否則用平常的霧。 */
    String survivorFog() {
        return nightmare != null && nightmare.active ? s.nmFog() : s.normalFog();
    }

    /**
     * 重送逃生者的霧。個人霧是用封包送的，區塊被整個重傳（例如傳送、走遠再回來）就會消失，所以每 10 秒補送一次。
     * 平常的霧是 off 而且沒在熄燈時不用送。
     */
    void refreshSurvivorFog() {
        String f = survivorFog();
        if (f == null || f.equalsIgnoreCase("off")) return;
        personalFog(f, survivorPlayers());
    }

    // ---- 蹲伏破洞：逃生者蹲著鑽洞不減速 ----

    /**
     * 逃生者在破洞附近蹲著走時，蹲下的速度懲罰拿掉，而且補回跑步的速度（蹲下會中斷跑步）。
     * 補回的速度不跟衝刺／被救加速疊加：有加速時只補差額（加速效果不疊加）。
     */
    private void holeSpeed(Survivor sv, Player p) {
        boolean crouched = p.isSneaking() || p.getPose() == org.bukkit.entity.Pose.SNEAKING;
        setHoleSpeed(sv, p, crouched && !p.isSprinting() && nearHole(p.getLocation()));
    }

    private void setHoleSpeed(Survivor sv, Player p, boolean on) {
        double bonus = on ? Math.max(0, s.holeSpeedBonus() - sv.appliedBoost) : 0;
        if (on == sv.inHole && bonus == sv.holeBonus) return;
        AttributeInstance sneak = p.getAttribute(Attribute.SNEAKING_SPEED);
        AttributeInstance sp = p.getAttribute(Attribute.MOVEMENT_SPEED);
        if (on != sv.inHole) {
            sneak.removeModifier(HOLE_SNEAK_KEY);
            // 蹲下速度上限是 1（= 不減速）
            if (on) sneak.addTransientModifier(new AttributeModifier(HOLE_SNEAK_KEY, 1.0, AttributeModifier.Operation.ADD_NUMBER));
        }
        sp.removeModifier(HOLE_SPEED_KEY);
        if (bonus > 0) sp.addTransientModifier(new AttributeModifier(HOLE_SPEED_KEY, bonus, AttributeModifier.Operation.ADD_SCALAR));
        sv.inHole = on;
        sv.holeBonus = bonus;
    }

    /** 站在破洞那格、或洞口前後 1.5 格內（同一層）。 */
    private boolean nearHole(Location l) {
        for (int i = 0; i < b.crawlHoles.size(); i++) {
            if (widened.contains(i)) continue;
            Pos h = b.crawlHoles.get(i).lower();
            if (Math.abs(l.getY() - h.y()) > 1.0) continue;
            double dx = l.getX() - (h.x() + 0.5), dz = l.getZ() - (h.z() + 0.5);
            if (dx * dx + dz * dz <= 2.0 * 2.0) return true;
        }
        return false;
    }

    /** 所有還在線上的逃生者（不管有沒有被抓）。 */
    List<Player> survivorPlayers() {
        List<Player> out = new ArrayList<>();
        for (Survivor sv : survivors.values()) {
            Player p = Bukkit.getPlayer(sv.id);
            if (p != null) out.add(p);
        }
        return out;
    }

    void broadcast(String text, NamedTextColor color) {
        Component c = Component.text(text, color);
        Bukkit.getOnlinePlayers().forEach(p -> p.sendMessage(c));
        Bukkit.getConsoleSender().sendMessage(c);
    }

    static String bar(int progress, int needed) {
        int n = 10, filled = Math.min(n, progress * n / Math.max(1, needed));
        return "█".repeat(filled) + "░".repeat(n - filled);
    }
}
