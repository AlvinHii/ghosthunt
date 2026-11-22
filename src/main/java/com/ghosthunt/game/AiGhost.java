package com.ghosthunt.game;

import com.destroystokyo.paper.entity.Pathfinder;
import com.ghosthunt.building.Building;
import com.ghosthunt.building.Pos;
import com.ghosthunt.building.RoomType;
import com.ghosthunt.game.ai.GhostBrain;
import com.ghosthunt.game.ai.GhostBrain.Goal;
import com.ghosthunt.game.ai.GhostBrain.Kind;
import com.ghosthunt.game.ai.GhostBrain.Point;
import com.ghosthunt.game.ai.AbilityTiming;
import com.ghosthunt.game.ai.AiMemory;
import com.ghosthunt.game.ai.GhostPersona;
import com.ghosthunt.game.ai.PersonaBandit;
import com.ghosthunt.game.ai.Chokepoints;
import com.ghosthunt.game.ai.DistanceField;
import com.ghosthunt.game.ai.Interceptor;
import com.ghosthunt.game.ai.SoundField;
import com.ghosthunt.game.ai.EscaperCost;
import com.ghosthunt.game.ai.GridNav;
import com.ghosthunt.game.ai.HideoutOdds;
import com.ghosthunt.game.ai.WorldTraces;
import com.ghosthunt.game.ai.ParticleTracker;
import com.ghosthunt.game.ai.PathFollow;
import com.ghosthunt.game.ai.SearchRules;
import com.ghosthunt.game.ai.RoomScores;
import com.ghosthunt.game.ai.SenseDirector;
import com.ghosthunt.game.ai.TargetPicker;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Door;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * AI 鬼（PvE）：一隻關掉原版 AI 的怪物。
 * <ul>
 *   <li>大腦 {@link GhostBrain} 決定目標；{@link TargetPicker} 選追誰（黏性、只用看得到的資訊）；
 *       {@link SenseDirector} 決定什麼時候感應；{@link RoomScores} 決定巡邏去哪。</li>
 *   <li>走路：插件自己在 {@link NavMap}（格子圖）上跑 A*，原版尋路只負責走到下一個幾格遠的路點。
 *       鬼不會走上家具；門、障礙物、蹲伏小洞、地洞是「要花時間」的邊，找路時就決定要拆還是繞。</li>
 *   <li>攻擊每 tick 判斷：進入攻擊距離、在前方、看得到 → 開始前搖，前搖結束時人還在範圍內就打到；
 *       已經退出範圍只有 whiff-chance 的機率還是會揮（揮空會疲勞 1.5 秒）。</li>
 *   <li>視野：前方 fov 度內看 sight-range 格（停電時 sight-range-dark），身邊 near-aware 格內不分方向；
 *       對方的眼睛、胸口、腳各射一條視線，任一條沒被擋就算看到。</li>
 *   <li>聽：插件的做事聲（有分級，低的蓋不掉高的）、跑步、走路（蹲著沒有）、開關門。</li>
 * </ul>
 */
final class AiGhost {

    /** 用來在伺服器當機重開後找出殘留的 AI 鬼。 */
    static NamespacedKey TAG;

    private final Match m;
    private final AiGhostSettings c;
    private final GhostBrain brain;
    private final TargetPicker picker;
    private SenseDirector director;
    private RoomScores rooms;
    private final List<Building.Room> roomList = new ArrayList<>();
    private final StuckTracker stuck;
    private Mob mob;
    private NavMap nav;

    // ---- 記憶 ----
    private UUID chaseId;
    private long chaseSeenTick = -1_000_000, lastChaseTick = -1_000_000;
    private Location lastSeen;
    private long lastSeenTick = -1_000_000, searchStart = -1_000_000;
    private UUID lastSeenId;
    private Vector lastDir;
    private Location prevSeenLoc;
    private Location noise;
    private long noiseTick = -1_000_000;
    private int noiseLevel;
    private Location sensed;
    private UUID sensedId;
    private int sensedRoom = -1;
    private long sensedUntil;
    private long blindUntil, flashImmuneUntil;
    private int flashExposure;
    private long flashLastTick;
    private int jailedBefore;
    private boolean guard;
    private long guardUntil;
    private final Set<Integer> knownHatches = new HashSet<>();
    /** 這次搜尋已經做過的子目標（地洞、暗格、往逃跑方向延伸）。 */
    private boolean searchTunnelDone, searchHideoutDone, searchExtendDone;
    private int patrolRoom = -1;
    private Location patrolTarget;
    private long patrolUntil;
    private final Map<Location, Long> badTargets = new HashMap<>();

    // ---- 第三版第 2 批：區域圖、繞前堵、埋伏、守讀卡機、看時機關電源 ----
    /** 區域圖（每個房間一區；相鄰 = 尋路圖上有一般的邊跨過去，不算蹲伏小洞、地洞）。 */
    private Chokepoints regions;
    /** 每區的出口：{區外那一格, 相鄰區, 區內那一格}。 */
    private final Map<Integer, List<int[]>> regionExits = new HashMap<>();
    /** 每區有哪些格子。 */
    private final List<List<Integer>> roomCells = new ArrayList<>();
    private record Sample(long tick, double x, double z) {}
    private final ArrayDeque<Sample> seenHist = new ArrayDeque<>();
    private UUID histId;
    private boolean interceptThisChase;
    private Location interceptAt;
    private int interceptTarget = -1;
    private long interceptPlanTick = -1_000_000;
    private boolean wasCalm;
    private Location ambushAt, ambushFace;
    private long ambushArrive = -1, ambushGiveUp;
    private Location readerTarget, readerSpot;
    private long readerGuardStart = -1, readerCooldownUntil;
    private int readerCycle;
    private long cutCheckTick = -1_000_000;
    private boolean cutWorth;
    private String leverFieldKey = "";
    private DistanceField leverField;
    private int intercepts, interceptTries, interceptPredicted, ambushes, readerGuards, soundsHeard, soundsBlocked, peeks;

    // ---- 第三版第 3 批：Utility、個性、學習、熱點、路線 ----
    private GhostPersona persona = GhostPersona.STANDARD;
    private String personaChosenBy = "預設";
    private final boolean utility;
    private final boolean learn, learnFromDummies;
    private final double memoryDecay;
    private AiMemory memory = new AiMemory();
    private final String memoryName;
    /** 追丟後等著判斷「被哪一招甩掉」：5 秒時看招式，30 秒還沒重新找到就記一筆。 */
    private record PendingEscape(long lostTick, int lostNode, AiMemory.Trick[] trick) {}
    private final Map<UUID, PendingEscape> pendingEscapes = new HashMap<>();
    private long lastSmokeTick = -1_000_000, lastLureTick = -1_000_000;
    private final Map<UUID, Integer> lastRegionSeen = new HashMap<>();
    private double[] regionHeat;
    private long regionHeatTick = -1_000_000;
    private int patrolCount;
    private final Map<AiMemory.Trick, Integer> learnedThisMatch = new EnumMap<>(AiMemory.Trick.class);

    // ---- 攻擊 ----
    private long windupStart = -1;

    // ---- 走路 ----
    private Goal goal;
    private double speedMul = 1.0;
    private GridNav.Cost cost;
    private List<Integer> rawPath = List.of(), wayPath = List.of();
    private int wayIdx, rawGoal = -1;
    private long planTick, planVersion = -1;
    private Location vanillaTarget;
    private int stuckCount;
    private long lastStuckTick = -100000;
    private long climbUntil = -1;
    private int climbTo = -1;
    private Location lastStepLoc;
    private int lookStep = -1;
    private float lookBase;
    private long lookNext;

    // ---- 統計、除錯 ----
    private final Map<Kind, Integer> goalTicks = new EnumMap<>(Kind.class);
    private int catches, misses, chases, stuckEvents, teleports, senses, catchesAfterSense;
    private long lastSenseTick = -1_000_000, firstContact = -1;
    final Set<UUID> debugViewers = new HashSet<>();

    AiGhost(Match m, AiGhostSettings c) {
        this.m = m;
        this.c = c;
        // 守監獄的機率在這裡擲（每次有人被關擲一次），大腦這邊永遠照做
        this.brain = new GhostBrain(new GhostBrain.Config(c.attackRange(), c.searchTime(), c.searchTime(), 1.0), m.rnd);
        this.picker = new TargetPicker(0.6, 20, 6.0, 6.0);
        this.director = new SenseDirector(c.senseQuiet(), c.senseGap(), c.pressureLimit(), c.calmTime());
        this.stuck = new StuckTracker(Math.max(20, c.stuckTime()), 1.0);
        this.crouchDarkSight = m.plugin.getConfig().getDouble("ai-ghost.crouch-dark-sight", 8);
        var cfg = m.plugin.getConfig();
        this.utility = !"rules".equalsIgnoreCase(cfg.getString("ai-ghost.brain", "utility"));
        this.learn = cfg.getBoolean("ai-ghost.learn", true) && !m.testMode(); // 測試局不寫學習記憶
        this.learnFromDummies = cfg.getBoolean("ai-ghost.learn-from-dummies", false);
        this.memoryDecay = cfg.getDouble("ai-ghost.memory-decay", 0.85);
        this.memoryName = m.b.mapName != null ? m.b.mapName : "building";
        if (learn) memory = loadMemory();
    }

    // ---- 個性、記憶 ----

    private java.io.File memoryFile() {
        return new java.io.File(new java.io.File(m.plugin.getDataFolder(), "ai-memory"), memoryName + ".yml");
    }

    private AiMemory loadMemory() {
        java.io.File f = memoryFile();
        if (!f.exists()) return new AiMemory();
        try {
            var y = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(f);
            return AiMemory.fromFlat(y.getValues(true));
        } catch (RuntimeException e) {
            m.plugin.getLogger().warning("[AI 鬼] 讀不到記憶 " + f + "：" + e.getMessage());
            return new AiMemory();
        }
    }

    private void saveMemory() {
        java.io.File f = memoryFile();
        try {
            f.getParentFile().mkdirs();
            var y = new org.bukkit.configuration.file.YamlConfiguration();
            for (Map.Entry<String, Object> e : memory.toFlat().entrySet()) y.set(e.getKey(), e.getValue());
            y.save(f);
        } catch (java.io.IOException | RuntimeException e) {
            m.plugin.getLogger().warning("[AI 鬼] 存不了記憶 " + f + "：" + e.getMessage());
        }
    }

    /** 開局選個性：指定 hound／ambusher／watcher／standard；auto（或 null）= 用記憶裡的拉桿 Thompson sampling 選。 */
    void usePersona(String arg) {
        var cfg = m.plugin.getConfig();
        String a = arg != null ? arg : cfg.getString("ai-ghost.persona", "auto");
        Map<String, GhostPersona> all = GhostPersona.builtIn();
        String id;
        if (a.equalsIgnoreCase("standard")) {
            persona = GhostPersona.STANDARD;
            personaChosenBy = "指定";
            return;
        } else if (all.containsKey(a.toLowerCase())) {
            id = a.toLowerCase();
            personaChosenBy = "指定";
        } else {
            id = PersonaBandit.choose(memory.bandit, GhostPersona.arms(), m.rnd);
            personaChosenBy = "自動";
        }
        GhostPersona p = all.get(id);
        var sec = cfg.getConfigurationSection("ai-ghost.personas." + id);
        if (sec != null) {
            Map<String, Double> over = new HashMap<>();
            for (String k : sec.getKeys(false)) if (sec.isDouble(k) || sec.isInt(k)) over.put(k, sec.getDouble(k));
            p = p.with(over);
        }
        persona = p;
    }

    GhostPersona persona() {
        return persona;
    }

    /** 學習的資料只用真人逃生者（假逃生者的習慣不算，除非 learn-from-dummies）。 */
    private boolean learnable(LivingEntity e) {
        return learn && (e instanceof Player || learnFromDummies);
    }

    private String regionKey(int r) {
        Pos p = roomList.get(r).center();
        return p.x() + "_" + p.y() + "_" + p.z();
    }

    /** 每一區的熱度（0～1，最熱的區是 1）；30 秒重算一次。 */
    private double regionHeat(int r) {
        if (!learn || roomCells.isEmpty() || r < 0) return 0;
        if (regionHeat == null || m.tick - regionHeatTick > 600) {
            regionHeatTick = m.tick;
            regionHeat = new double[roomList.size()];
            double max = 0;
            for (int i = 0; i < roomCells.size(); i++) {
                double sum = 0;
                for (int cell : roomCells.get(i)) sum += memory.heat.at(nav.nav.x(cell), nav.nav.y(cell), nav.nav.z(cell));
                regionHeat[i] = sum;
                max = Math.max(max, sum);
            }
            if (max > 0) for (int i = 0; i < regionHeat.length; i++) regionHeat[i] /= max;
        }
        return r < regionHeat.length ? regionHeat[r] : 0;
    }

    private double learned(AiMemory.Trick t, double cap) {
        return learn ? memory.bonus(t, cap) : 0;
    }

    /** 被煙霧彈打到（跟球車、手電筒分開：學「煙霧彈」這招用）。 */
    void smoked(int ticks) {
        lastSmokeTick = m.tick;
        blind(ticks);
        // 學會了：不在原地發呆，改去對方最可能跑去的地方（最後看到的位置沿逃跑方向 6 格）
        if (learned(AiMemory.Trick.SMOKE, 1) >= 0.5 && lastSeen != null && lastDir != null) {
            Location guess = snapWalkable(lastSeen.clone().add(lastDir.clone().multiply(6)), lastSeen);
            if (guess != null) {
                noise = guess;
                noiseTick = m.tick;
                noiseLevel = Match.NOISE_MID;
            }
        }
    }

    /** 追丟 5 秒後：看對方用了哪一招（暗格、地洞、蹲伏小洞、死路房間、煙霧彈、迎賓鈴）。 */
    private AiMemory.Trick whichTrick(LivingEntity e, PendingEscape p) {
        if (Math.abs(p.lostTick() - lastSmokeTick) <= 100) return AiMemory.Trick.SMOKE;
        if (p.lostTick() - lastLureTick >= 0 && p.lostTick() - lastLureTick <= 200) return AiMemory.Trick.LURE;
        Location l = e.getLocation();
        for (Building.Room r : m.b.rooms) {
            if (!r.hideout()) continue;
            Location c0 = m.loc(r.center());
            if (m.sameFloor(c0, l) && c0.distance(l) <= 2.5) return AiMemory.Trick.HIDEOUT;
        }
        for (Building.Hatch h : m.b.hatches) if (l.getY() < h.trapdoor().y() - 1.5) return AiMemory.Trick.HATCH;
        int now = nav.nearest(l, 2.5);
        if (now >= 0 && p.lostNode() >= 0) {
            List<Integer> path = nav.nav.path(p.lostNode(), now, escaperCost());
            for (int i = 1; i < path.size(); i++) {
                int a0 = path.get(i - 1), b0 = path.get(i);
                for (GridNav.Edge ed : nav.nav.edges(a0)) if (ed.to() == b0 && ed.kind() == GridNav.Kind.HOLE) return AiMemory.Trick.HOLE;
            }
            if (regions != null && nodeRoom[now] >= 0 && regions.deadEnd(nodeRoom[now])) return AiMemory.Trick.DEAD_END;
        }
        return null;
    }

    private void updateLearning() {
        if (!learn || pendingEscapes.isEmpty()) return;
        var it = pendingEscapes.entrySet().iterator();
        while (it.hasNext()) {
            var en = it.next();
            PendingEscape p = en.getValue();
            long age = m.tick - p.lostTick();
            if (!lostAt.containsKey(en.getKey())) { // 重新找到了、被抓了、逃出去了：不算
                it.remove();
                continue;
            }
            if (age >= 100 && p.trick()[0] == null) {
                LivingEntity e = byId(en.getKey());
                if (e == null) {
                    it.remove();
                    continue;
                }
                AiMemory.Trick t = whichTrick(e, p);
                if (t == null) {
                    it.remove();
                    continue;
                }
                p.trick()[0] = t;
            }
            if (age >= 600 && p.trick()[0] != null) {
                AiMemory.Trick t = p.trick()[0];
                boolean first = memory.escapes(t) < 0.5;
                memory.escaped(t);
                learnedThisMatch.merge(t, 1, Integer::sum);
                if (first) {
                    // 解鎖感：它好像學會了
                    for (LivingEntity x : targets()) if (x instanceof Player pl)
                        pl.sendActionBar(Component.text("你覺得那個東西好像記住了這一招……", NamedTextColor.DARK_PURPLE));
                }
                it.remove();
            }
        }
    }

    /** 看到人：熱點 +1（每秒一次），記下他從哪一區走到哪一區。 */
    private void learnSighting(LivingEntity seen) {
        if (!learnable(seen) || nav == null) return;
        Location l = seen.getLocation();
        if (m.tick % 20 == 0) memory.heat.see(l.getBlockX(), l.getBlockY(), l.getBlockZ(), 1);
        if (nodeRoom == null) return;
        int n = nav.nearest(l, 2.5);
        int r = n < 0 ? -1 : nodeRoom[n];
        if (r < 0) return;
        Integer prev = lastRegionSeen.put(seen.getUniqueId(), r);
        // 只記相鄰兩區之間的移動（中間沒看到的那段不算）
        if (prev != null && prev != r && regions != null && regions.neighbors(prev).contains(r))
            memory.routes.transition(regionKey(prev), regionKey(r));
    }

    /** 局末：更新個性拉桿（勢均力敵分數）、舊資料 ×memory-decay、存檔。 */
    void endMatch(int realSurvivors, int escaped) {
        if (!learn) return;
        if (realSurvivors > 0 && persona != GhostPersona.STANDARD && personaChosenBy.equals("自動"))
            PersonaBandit.update(memory.bandit, persona.id(),
                    PersonaBandit.score(escaped / (double) realSurvivors, PersonaBandit.target(c.difficulty())));
        memory.endMatch(memoryDecay);
        saveMemory();
    }

    AiGhostSettings settings() {
        return c;
    }

    NavMap navMap() {
        return nav;
    }

    /** 在 at 生出 AI 鬼。 */
    Mob spawn(Location at) {
        EntityType type;
        try {
            type = EntityType.valueOf(c.mob().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            type = EntityType.HUSK;
        }
        if (type.getEntityClass() == null || !Mob.class.isAssignableFrom(type.getEntityClass())) type = EntityType.HUSK;
        mob = (Mob) at.getWorld().spawnEntity(at, type);
        Bukkit.getMobGoals().removeAllGoals(mob);
        mob.setTarget(null);
        mob.setPersistent(false);
        mob.setRemoveWhenFarAway(false);
        mob.setInvulnerable(true);
        mob.setSilent(true); // 不要原版的呻吟（等於一直報位置）；腳步聲由插件照真人鬼的規則播
        mob.setCanPickupItems(false);
        mob.customName(Component.text("鬼", NamedTextColor.DARK_RED));
        mob.setCustomNameVisible(false);
        if (TAG != null) mob.getPersistentDataContainer().set(TAG, PersistentDataType.BYTE, (byte) 1);
        if (mob instanceof Zombie z) {
            z.setAdult();
            z.setShouldBurnInDay(false);
            z.setCanBreakDoors(false);
        }
        var follow = mob.getAttribute(Attribute.FOLLOW_RANGE);
        if (follow != null) follow.setBaseValue(48);
        var speed = mob.getAttribute(Attribute.MOVEMENT_SPEED);
        if (speed != null) speed.setBaseValue(c.baseSpeed()); // 約等於玩家跑步；加成和揮拳減速由 Match 處理
        var jump = mob.getAttribute(Attribute.JUMP_STRENGTH);
        if (jump != null) jump.setBaseValue(0); // 跟逃生者一樣不能跳
        var step = mob.getAttribute(Attribute.STEP_HEIGHT);
        if (step != null) step.setBaseValue(0.6); // 跟玩家一樣：走得上樓梯，走不上家具
        var kb = mob.getAttribute(Attribute.KNOCKBACK_RESISTANCE);
        if (kb != null) kb.setBaseValue(1.0);
        Pathfinder pf = mob.getPathfinder();
        pf.setCanOpenDoors(true);
        pf.setCanPassDoors(true);
        pf.setCanFloat(false);
        dress(mob.getEquipment());
        return mob;
    }

    private static void dress(EntityEquipment eq) {
        if (eq == null) return;
        eq.setHelmet(new ItemStack(Material.WITHER_SKELETON_SKULL));
        for (Material m : new Material[]{Material.LEATHER_CHESTPLATE, Material.LEATHER_LEGGINGS, Material.LEATHER_BOOTS}) {
            ItemStack it = new ItemStack(m);
            if (it.getItemMeta() instanceof LeatherArmorMeta meta) {
                meta.setColor(Color.fromRGB(20, 18, 24));
                it.setItemMeta(meta);
            }
            switch (m) {
                case LEATHER_CHESTPLATE -> eq.setChestplate(it);
                case LEATHER_LEGGINGS -> eq.setLeggings(it);
                default -> eq.setBoots(it);
            }
        }
        eq.setItemInMainHand(null);
        eq.setHelmetDropChance(0);
        eq.setChestplateDropChance(0);
        eq.setLeggingsDropChance(0);
        eq.setBootsDropChance(0);
    }

    Mob mob() {
        return mob;
    }

    void remove() {
        if (mob != null) mob.remove();
        mob = null;
    }

    // =====================================================================
    // 外部事件
    // =====================================================================

    /** 煙霧彈：一段時間看不到人，正在做的事也中斷。 */
    void blind(int ticks) {
        blindUntil = Math.max(blindUntil, m.tick + ticks);
        windupStart = -1;
        if (mob != null) {
            m.acts.cancel(mob.getUniqueId(), null);
            mob.getPathfinder().stopPathfinding();
        }
    }

    /** 手電筒照到（每 tick 呼叫）：連續照滿 flash-time 就短暫看不到人，之後一段時間免疫。 */
    void flashed(Player by) {
        if (m.tick < flashImmuneUntil) return;
        flashExposure = m.tick - flashLastTick <= 2 ? flashExposure + 1 : 1;
        flashLastTick = m.tick;
        if (flashExposure < c.flashTime()) return;
        flashExposure = 0;
        blindUntil = Math.max(blindUntil, m.tick + c.flashBlind());
        flashImmuneUntil = m.tick + c.flashBlind() + c.flashImmune();
        windupStart = -1;
        mob.getWorld().playSound(mob.getLocation(), Sound.ENTITY_ENDERMAN_HURT, 1.2f, 0.5f);
        by.sendActionBar(Component.text("照到鬼的臉了！它暫時看不到", NamedTextColor.YELLOW));
    }

    /** 全圖都聽得到的聲音（迎賓鈴）：不走路徑傳播，位置是「真實位置 ± noise-error」。 */
    void hearNoise(Location at, int level) {
        if (mob == null) return;
        lastLureTick = m.tick;
        // 學會了「迎賓鈴引開」：有機率不理
        if (m.rnd.nextDouble() < learned(AiMemory.Trick.LURE, 0.6)) return;
        noticed(snapWalkable(jitter(at, c.noiseError()), at), level);
    }

    /**
     * 沿路傳過來的聲音（做事聲、腳步、開關門、閥門）：聲音沿著可走的格子傳，關著的門 +4 格、蹲伏小洞 +2 格、地洞 +4 格，
     * 傳到鬼的距離超過 range 就聽不到（不分樓層，樓梯照實際長度）。聽到的位置是傳播路徑上「離聲音 noise-error 格」那一格，
     * 走過去會從聲音傳來的那個門口進去。
     */
    void hearSound(Location at, int level, double range) {
        if (mob == null || !mob.getWorld().equals(at.getWorld())) return;
        Location here = mob.getLocation();
        if (at.distance(here) > range) return; // 直線都超過了，路徑一定更長
        int src = nav == null ? -1 : nav.nearest(at, 3);
        int me = nav == null ? -1 : nav.nearest(here, 2.5);
        if (me < 0 && nav != null) me = nav.nearest(here, 6);
        if (src < 0 || me < 0) {
            // 尋路圖上找不到（還沒建好、聲源在圖外）：照舊（同一層、直線距離）
            if (m.sameFloor(at, here)) noticed(snapWalkable(jitter(at, c.noiseError()), at), level);
            return;
        }
        SoundField.Heard h = SoundField.hear(nav.nav, src, me, range, soundPenalty(), 2, c.noiseError());
        if (h == null) {
            soundsBlocked++;
            return;
        }
        soundsHeard++;
        noticed(nav.loc(h.pointNode()), level);
    }

    /** 聲音傳播的額外距離（每條邊）：關著的門進出各 +2、蹲伏小洞各 +1、地洞各 +2。 */
    private GridNav.Cost soundPenalty() {
        return e -> switch (e.kind()) {
            case WALK, STAIR, BARRICADE -> 0;
            case DOOR -> doorClosed(e.ref()) ? 2 : 0;
            case HOLE -> 1;
            case HATCH -> 2;
        };
    }

    private void noticed(Location heard, int level) {
        if (heard == null) return;
        lastQuietBreakTick = m.tick;
        for (ParticleTracker t : trackers.values()) t.weightNear(heard.getX(), heard.getY(), heard.getZ(), 2);
        if (sensedTracker != null) sensedTracker.weightNear(heard.getX(), heard.getY(), heard.getZ(), 2);
        if (noise != null && m.tick - noiseTick <= c.searchTime() && level < noiseLevel) return;
        noise = heard;
        noiseTick = m.tick;
        noiseLevel = level;
        director.contact(m.tick);
        contactStat();
        if (rooms != null) rooms.noise(roomAt(heard), 60);
    }

    /** 開關門的聲音（GameListener）：hear-door 格內、沿路傳得到才聽得到。 */
    void hearDoor(Location at) {
        hearSound(at, Match.NOISE_MID, c.hearDoor());
    }

    /** 某個逃生者被抓、淘汰、逃出、離線：忘掉跟他有關的記憶（不然會在抓人的空地搜 10 秒、追不存在的人）。 */
    void forget(UUID id) {
        trackers.remove(id);
        if (id.equals(lostHandledId)) lostHandledId = null;
        if (id.equals(chaseId)) chaseId = null;
        if (id.equals(lastSeenId)) {
            lastSeen = null;
            lastSeenId = null;
            lastSeenTick = -1_000_000;
        }
        if (id.equals(sensedId)) {
            sensed = null;
            sensedId = null;
            sensedTracker = null;
        }
        lostAt.remove(id);
        windupStart = -1;
    }

    /** 抓到人：記一筆、節奏控制進入冷靜期。 */
    void onCatch(UUID id) {
        catches++;
        if (m.tick - lastSenseTick <= 30 * 20) catchesAfterSense++;
        director.captured(m.tick);
        forget(id);
    }

    // =====================================================================
    // 每 tick
    // =====================================================================

    void tick() {
        long t0 = System.nanoTime();
        boolean hadNav = nav != null;
        tickInner();
        // 建尋路圖（每局一次）另外記，不算進每 tick 的時間
        if (!hadNav && nav != null) navBuildMs = (System.nanoTime() - t0) / 1_000_000;
        else tickTime.add(System.nanoTime() - t0);
    }

    private long navBuildMs = -1;

    // ---- 計時（stats.log：AI 在主執行緒花多少時間）----

    /** 一種計時：次數、總和、最大值（奈秒）。 */
    private static final class Timing {
        long count, total, max;

        void add(long ns) {
            count++;
            total += ns;
            max = Math.max(max, ns);
        }

        double avgMs() {
            return count == 0 ? 0 : Math.round(total / (double) count / 10_000.0) / 100.0;
        }

        double maxMs() {
            return Math.round(max / 10_000.0) / 100.0;
        }
    }

    private final Timing tickTime = new Timing(), pathTime = new Timing(), fieldTime = new Timing();
    /** 鬼到每一格要幾秒（只有在有粒子追蹤器時才每次 think 重算）。 */
    private DistanceField ghostField;
    /** 可見性表（背景建好之前是 null）。 */
    private volatile com.ghosthunt.game.ai.VisibilityTable vis;

    // ---- 粒子濾波（第三版）：追丟、感應後推理人在哪 ----
    private final Map<UUID, ParticleTracker> trackers = new HashMap<>();
    private ParticleTracker sensedTracker;
    /** 這個人的這次追丟已經撒過粒子了（重新看到時清掉）。 */
    private UUID lostHandledId;
    /** 每個逃生者這次追丟的時間（重新看到同一個人才算「重新找到」）。 */
    private final Map<UUID, Long> lostAt = new HashMap<>();
    /** 每個尋路格子屬於哪一間房（第一次感應時算一次）。 */
    private int[] nodeRoom;
    private int losses, reacquire30, thinkCount;
    private DistanceField goalField;
    private String goalFieldKey = "";
    private List<Integer> goalSources = List.of();
    /** 真的看到人的最後時間、位置（粒子搜尋會改 lastSeen，證據要用真的）。 */
    private long trueSeenTick = -1_000_000;
    private Location trueSeenLoc;
    /** 最後一次聽到任何聲音（腳步、開門、做事）的時間：看到人之後一直很安靜 → 他多半躲起來了。 */
    private long lastQuietBreakTick = -1_000_000;
    private final WorldTraces traces = new WorldTraces();
    private int tracesSeen;

    /** 每秒一次：16 格內、看得到的門、木桶、櫃子，狀態變了就是痕跡（那一區巡邏加分、附近的粒子加權）。 */
    private void lookForTraces() {
        if (nav == null || vis == null) return;
        int gnode = nav.nearest(mob.getLocation(), 2.5);
        Location me = mob.getLocation();
        for (int i = 0; i < nav.doors.size(); i++) {
            Pos d = nav.doors.get(i);
            if (Math.abs(d.x() + 0.5 - me.getX()) > 16 || Math.abs(d.z() + 0.5 - me.getZ()) > 16) continue;
            int state = m.isDoorJammed(d) ? 2 : doorClosed(i) ? 0 : 1;
            observeTrace(gnode, WorldTraces.Type.DOOR, i, state, m.loc(d));
        }
        for (int i = 0; i < m.b.containers.size(); i++) {
            Pos b = m.b.containers.get(i);
            observeTrace(gnode, WorldTraces.Type.BARREL, i, m.obj.isSearched(b) ? 1 : 0, m.loc(b));
        }
        for (int i = 0; i < m.b.toppleSpots.size(); i++) {
            Pos b = m.b.toppleSpots.get(i).blocks().keySet().iterator().next();
            observeTrace(gnode, WorldTraces.Type.CABINET, i, m.isToppled(i) ? 1 : 0, m.loc(b));
        }
        // 小洞只有鬼會打大，看到變大一定是自己造成的，不看
    }

    private void observeTrace(int gnode, WorldTraces.Type type, int id, int state, Location at) {
        if (at.distance(mob.getLocation()) > 16) return;
        int cell = nav.nearest(at, 1.6);
        if (cell < 0 || !ghostSees(gnode, cell)) return;
        WorldTraces.Trace t = traces.observe(type, id, state, at.getX(), at.getY(), at.getZ(), m.tick);
        if (t == null) return;
        tracesSeen++;
        double use = t.usefulness();
        if (rooms != null) rooms.noise(roomAt(at), 60 * use);
        for (ParticleTracker tr : trackers.values()) tr.weightNear(at.getX(), at.getY(), at.getZ(), 2);
        if (sensedTracker != null) sensedTracker.weightNear(at.getX(), at.getY(), at.getZ(), 2);
        if (STUCK_DEBUG || !debugViewers.isEmpty())
            m.plugin.getLogger().info("[AI 鬼] 痕跡：" + type + " " + id + " → " + state + "（隔 " + t.gapTicks() / 20 + " 秒）");
    }

    /** 鬼自己開門、撞開卡門、打大洞：下次看到不算痕跡。 */
    private void ghostChangedDoorsNear(Pos lower, int newState) {
        if (nav == null) return;
        for (int i = 0; i < nav.doors.size(); i++) {
            Pos d = nav.doors.get(i);
            if (Math.abs(d.x() - lower.x()) + Math.abs(d.z() - lower.z()) <= 1 && d.y() == lower.y()) traces.ghostChanged(WorldTraces.Type.DOOR, i, newState, m.tick);
        }
    }

    private ParticleTracker newTracker() {
        ParticleTracker t = createTracker();
        if (learn) t.setBias(this::particleBias);
        return t;
    }

    /**
     * 粒子隨機那幾步的偏好（第三版第 20、22 項）：跨區時乘「以前看過從這區走到那區」的機率 × 相鄰區數（平均 1）；
     * 熱點（常看到人的格子）最多 ×1.5。
     */
    private double particleBias(int from, int to) {
        double w = 1 + 0.5 * Math.min(1, memory.heat.at(nav.nav.x(to), nav.nav.y(to), nav.nav.z(to)) / 5);
        if (regions == null || nodeRoom == null) return w;
        int a = nodeRoom[from], b = nodeRoom[to];
        if (a < 0 || b < 0 || a == b) return w;
        List<String> opts = new ArrayList<>();
        for (int nb : regions.neighbors(a)) opts.add(regionKey(nb));
        if (opts.isEmpty()) return w;
        return w * memory.routes.probability(regionKey(a), regionKey(b), opts) * opts.size();
    }

    private ParticleTracker createTracker() {
        return new ParticleTracker(nav.nav, escaperCost(),
                new ParticleTracker.Config(c.particles(), c.escaperSpeed(), c.particleFlee(), c.particleGoal(), c.particleSigma(),
                        c.particleMin(), Math.round(c.particleMaxAge() * persona.particleAgeMul())), m.rnd);
    }

    /** 逃生者在尋路圖上的成本：小洞正常走、放了障礙物的門口過不去、卡住的門自己拆得開。 */
    private GridNav.Cost escaperCost() {
        GridNav.Cost base = EscaperCost.of(i -> m.isDoorJammed(nav.doors.get(i)), i -> m.barricadeIndexBarricaded(i),
                i -> knownHatches.contains(i) && knownHatches.contains(i ^ 1), // 鬼只知道自己看過兩端的地洞
                m.o.jamDoor() / 20.0 * c.escaperSpeed(), 2 * c.escaperSpeed());
        // 上鎖的門、還沒打開的鐵捲門：逃生者也過不去（推理時不往那裡猜）
        return e -> e.kind() == GridNav.Kind.DOOR && m.isDoorLocked(nav.doors.get(e.ref())) ? Double.POSITIVE_INFINITY : base.extra(e);
    }

    private boolean doorClosed(int d) {
        return m.block(nav.doors.get(d)).getBlockData() instanceof org.bukkit.block.data.Openable o && !o.isOpen();
    }

    /** 鬼現在看得到這一格嗎（可見性表＋視野角＋距離；表還沒建好就當作看不到）。 */
    private boolean ghostSees(int ghostNode, int cell) {
        if (vis == null || ghostNode < 0) return false;
        Location l = nav.loc(cell);
        double d = l.distance(mob.getLocation());
        if (!vis.known(ghostNode, cell)) {
            // 樓梯上、樓梯間看上下層：表裡沒有，12 格內用真的方塊射線（腳的高度＋1）
            if (d > 12 || (d > c.nearAware() && !inFront(l, c.fovDegrees()))) return false;
            if (!SearchRules.sightUsable(m.tick, blindUntil, false, false, d)) return false;
            Location eye = mob.getEyeLocation(), to = l.clone().add(0, 1, 0);
            org.bukkit.util.Vector dir = to.toVector().subtract(eye.toVector());
            if (dir.length() < 0.01) return true;
            var hit = eye.getWorld().rayTraceBlocks(eye, dir.normalize(), dir.length(), FluidCollisionMode.NEVER, true, bl -> bl.getType() != org.bukkit.Material.BARRIER);
            return hit == null || hit.getHitBlock() == null;
        }
        if (d > (m.obj.powerOn() ? c.sightRange() : c.sightRangeDark())) return false;
        if (d > c.nearAware() && !inFront(l, c.fovDegrees())) return false;
        // 跟「看到人」一樣：失明（煙霧彈、手電筒、球車）時不能用視線；鬼或那一格在溫泉蒸氣裡也不行
        boolean smokeMe = m.gimmicks != null && m.gimmicks.inSmoke(mob.getLocation());
        boolean smokeCell = m.gimmicks != null && m.gimmicks.inSmoke(l);
        if (!SearchRules.sightUsable(m.tick, blindUntil, smokeMe, smokeCell, d)) return false;
        // 門關著、門口放了障礙物或推倒的櫃子：擋住
        return vis.visible(ghostNode, cell, this::doorClosed, i -> m.barricadeIndexBarricaded(i));
    }

    /** 逃生者下一個遊戲目標的距離場：沒電 → 電源；有電 → 終端機和大門讀卡機（多起點）。同樣的目標、地圖沒變就不重算。 */
    private DistanceField goalField() {
        boolean power = m.obj.powerOn();
        String key = power + ":" + m.mapVersion;
        if (key.equals(goalFieldKey)) return goalField;
        List<Integer> src = new ArrayList<>();
        if (!power) {
            Location lever = m.obj.leverLocation();
            if (lever != null) src.add(nav.nearest(lever.clone().add(0, -1, 0), 2.5));
        } else {
            for (Pos t : m.b.securityTerminals) src.add(nav.nearest(m.loc(t), 2.5));
            for (Building.Gate g : m.b.gates) src.add(nav.nearest(m.loc(g.reader()), 2.5));
        }
        src.removeIf(i -> i < 0);
        goalFieldKey = key;
        goalSources = src;
        goalField = src.isEmpty() ? null : timedField(escaperCost(), c.escaperSpeed(), src.stream().mapToInt(Integer::intValue).toArray());
        return goalField;
    }

    /** 每次 think：丟掉過期的追蹤器；每 0.5 秒讓粒子走一步、刪掉看得到的格子裡的粒子；有追蹤器時延長搜尋。 */
    private void updateTrackers() {
        thinkCount++;
        trackers.entrySet().removeIf(e -> e.getValue().expired(m.tick));
        if (sensedTracker != null && sensedTracker.expired(m.tick)) sensedTracker = null;
        if ((trackers.isEmpty() && sensedTracker == null) || nav == null) return;
        // 推理還有線索：繼續搜（把「最後看到」推到現在）；但有更新的聲音、而且不在粒子附近（或是刷卡、迎賓鈴）時讓聲音贏
        ParticleTracker cur = lastSeenId == null ? null : trackers.get(lastSeenId);
        if (cur != null) {
            double share = noise == null ? 1 : cur.shareNear(noise.getX(), noise.getY(), noise.getZ(), 2 * c.particleSigma());
            if (SearchRules.keepSearchFresh(noise == null ? Long.MIN_VALUE : noiseTick, noiseLevel, trueSeenTick, share)) lastSeenTick = m.tick;
        }
        if (thinkCount % Math.max(1, 10 / c.thinkEvery()) != 0) return;
        int gnode = nav.nearest(mob.getLocation(), 2.5);
        if (gnode < 0) gnode = nav.nearest(mob.getLocation(), 6);
        if (gnode < 0) return; // 鬼不在尋路圖上（例如爬梯子中）：這一步先不推理
        ghostField = timedField(costFor(Mode.SEARCH), 5.3 * c.searchSpeed(), gnode);
        final int gn = gnode;
        Map<Integer, Boolean> seenCache = new HashMap<>();
        DistanceField gf = goalField();
        List<ParticleTracker> all = new ArrayList<>(trackers.values());
        if (sensedTracker != null) all.add(sensedTracker);
        for (ParticleTracker t : all) {
            t.step(0.5, ghostField, gf);
            t.removeVisible(i -> seenCache.computeIfAbsent(i, k -> ghostSees(gn, k)));
        }
    }

    /** 這個追蹤器的搜尋目標（粒子最密、現在看不到、鬼走得到）；沒有回傳 null。 */
    private Location trackerTarget(ParticleTracker t) {
        if (t == null || nav == null) return null;
        int gnode = nav.nearest(mob.getLocation(), 2.5);
        int tgt = t.bestTarget(i -> !ghostSees(gnode, i), nav::isMain);
        return tgt < 0 ? null : nav.loc(tgt);
    }

    private List<Integer> timedPath(int a, int b, GridNav.Cost cost) {
        long t0 = System.nanoTime();
        List<Integer> p = nav.nav.path(a, b, cost);
        pathTime.add(System.nanoTime() - t0);
        return p;
    }

    private DistanceField timedField(GridNav.Cost cost, double blocksPerSecond, int... sources) {
        long t0 = System.nanoTime();
        DistanceField f = DistanceField.compute(nav.nav, cost, blocksPerSecond, sources);
        fieldTime.add(System.nanoTime() - t0);
        return f;
    }

    private void tickInner() {
        if (mob == null || !mob.isValid()) return;
        // 尋路圖、區域圖、可見性表在放鬼之前就建好（鬼房關著的時候），放出來的那一刻不會卡一下
        if (nav == null) {
            buildNav();
            return;
        }
        if (!m.released) {
            mob.getPathfinder().stopPathfinding();
            return;
        }
        if (lastContactTick < 0) lastContactTick = m.tick; // 放鬼的那一刻開始算「多久沒接觸」
        if (climbUntil >= 0) {
            climbTick();
            return;
        }
        footsteps();
        boolean busy = m.acts.busy(mob.getUniqueId());
        attackTick();
        if (!busy) {
            if (lookStep >= 0) lookTick();
            else followPath();
        }
        if (goal != null) goalTicks.merge(goal.kind(), 1, Integer::sum);
        if (m.tick % 10 == 0) debugDraw();
        if (m.tick % c.thinkEvery() != 0) return;
        if (m.survivors.isEmpty() && m.tick % 40 == 0) // 單人測試（沒有真人逃生者）時每 2 秒記一次
            m.plugin.getLogger().info("[AI 鬼] " + debug());
        think(busy);
    }

    private void buildNav() {
        long t0 = System.nanoTime();
        nav = NavMap.build(m);
        List<RoomScores.RoomInfo> infos = new ArrayList<>();
        for (Building.Room r : m.b.rooms) {
            if (r.hideout() || r.type() == RoomType.GHOST_ROOM) continue;
            roomList.add(r);
            Location l = m.loc(r.center());
            infos.add(new RoomScores.RoomInfo(infos.size(), l.getX(), l.getY(), l.getZ(), r.floor()));
        }
        rooms = new RoomScores(infos, 1.0, 300);
        buildRegions();
        // 大地圖：太久沒接觸的感應提早（壓迫感；2026-11-23 使用者同意）
        var cfg = m.plugin.getConfig();
        if (nav.nav.size() >= cfg.getInt("ai-ghost.big-map-nodes", 8000)) {
            double k = cfg.getDouble("ai-ghost.big-map-sense-scale", 0.67);
            director = new SenseDirector((int) Math.round(c.senseQuiet() * k), (int) Math.round(c.senseGap() * k), c.pressureLimit(), c.calmTime());
            m.plugin.getLogger().info("[AI 鬼] 大地圖（" + nav.nav.size() + " 格）：感應間隔 ×" + k + " → " + Math.round(c.senseQuiet() * k / 20.0) + " 秒");
        }
        if (mob != null && !mob.getLocation().getBlock().isPassable()) {
            int id = nav.nearest(mob.getLocation(), 4);
            if (id >= 0) mob.teleport(nav.loc(id));
        }
        m.plugin.getLogger().info("[AI 鬼] 尋路圖：" + nav.nav.size() + " 個格子（" + (System.nanoTime() - t0) / 1_000_000 + " ms）");
        // 可見性表：主執行緒拍快照，背景建表（建好前 vis 是 null，用到的地方當作「不知道」）
        com.ghosthunt.game.ai.VisibilityTable.Grid grid = nav.visibilityGrid();
        org.bukkit.Bukkit.getScheduler().runTaskAsynchronously(m.plugin, () -> {
            long v0 = System.nanoTime();
            com.ghosthunt.game.ai.VisibilityTable t = com.ghosthunt.game.ai.VisibilityTable.build(grid, 30);
            vis = t;
            m.plugin.getLogger().info("[AI 鬼] 可見性表：" + grid.size() + " 格（" + (System.nanoTime() - v0) / 1_000_000 + " ms，約 "
                    + t.approxBytes() / 1024 + " KB）");
        });
    }

    /** 區域圖：每一格屬於哪個房間、房間之間怎麼連（割點、介數中心性、死路）。 */
    private void buildRegions() {
        long t0 = System.nanoTime();
        nodeRoom = new int[nav.nav.size()];
        for (int i = 0; i < roomList.size(); i++) roomCells.add(new ArrayList<>());
        for (int i = 0; i < nodeRoom.length; i++) {
            nodeRoom[i] = nav.isMain(i) ? roomAt(nav.loc(i)) : -1;
            if (nodeRoom[i] >= 0) roomCells.get(nodeRoom[i]).add(i);
        }
        List<int[]> edges = new ArrayList<>();
        for (int i = 0; i < nodeRoom.length; i++) {
            int a = nodeRoom[i];
            if (a < 0) continue;
            for (GridNav.Edge e : nav.nav.edges(i)) {
                if (e.kind() == GridNav.Kind.HOLE || e.kind() == GridNav.Kind.HATCH) continue;
                int b = nodeRoom[e.to()];
                if (b < 0 || b == a) continue;
                edges.add(new int[]{a, b});
                // 同一個相鄰區、4 格內已經有出口的就不重複記
                List<int[]> ex = regionExits.computeIfAbsent(a, k -> new ArrayList<>());
                boolean dup = false;
                for (int[] o : ex) {
                    if (o[1] == b && nav.loc(o[0]).distance(nav.loc(e.to())) < 4) {
                        dup = true;
                        break;
                    }
                }
                if (!dup) ex.add(new int[]{e.to(), b, i});
            }
        }
        regions = new Chokepoints(roomList.size(), edges);
        int dead = 0, art = 0;
        for (int r = 0; r < roomList.size(); r++) {
            if (regions.deadEnd(r)) dead++;
            if (regions.isArticulation(r)) art++;
        }
        m.plugin.getLogger().info("[AI 鬼] 區域圖：" + roomList.size() + " 區、死路 " + dead + "、割點 " + art + "（"
                + (System.nanoTime() - t0) / 1_000_000 + " ms）");
    }

    private boolean chokeRoom(int r) {
        return regions != null && (regions.isArticulation(r) || regions.betweenness(r) >= 0.5);
    }

    /** 這一區有沒有「有人在裡面」的證據：最近的聲音、剛看到的位置、粒子 20% 以上。 */
    private boolean roomEvidence(int r) {
        if (r < 0) return false;
        if (noise != null && m.tick - noiseTick <= 600 && roomAt(noise) == r) return true;
        if (lastSeen != null && m.tick - lastSeenTick <= 600 && lastSeen.getWorld().equals(mob.getWorld()) && roomAt(lastSeen) == r) return true;
        if (r < roomCells.size()) {
            List<Integer> cells = roomCells.get(r);
            for (ParticleTracker t : trackers.values()) if (t.shareWithin(cells) >= 0.2) return true;
            if (sensedTracker != null && sensedTracker.shareWithin(cells) >= 0.2) return true;
        }
        return false;
    }

    private boolean visFrom(int from, int cell) {
        if (vis == null || from < 0 || cell < 0 || !vis.known(from, cell)) return false;
        return vis.visible(from, cell, this::doorClosed, i -> m.barricadeIndexBarricaded(i));
    }

    // ---- 繞前堵 ----

    /** 追人時：猜對方要往哪個出口跑，鬼會先到的話去那裡堵；沒有回傳 null（照舊直追）。每秒重算一次。 */
    private Location interceptPoint(LivingEntity seen) {
        if (!interceptThisChase || nav == null || regions == null || seen == null) return null;
        Location here = mob.getLocation(), s = seen.getLocation();
        if (!s.getWorld().equals(here.getWorld()) || s.distance(here) <= 4) {
            interceptAt = null;
            return null;
        }
        if (interceptAt != null && interceptAt.distance(here) < 1.5) {
            interceptAt = null; // 堵到了：直追
            interceptTarget = -1;
            interceptPlanTick = m.tick;
            return null;
        }
        if (m.tick - interceptPlanTick < 20) return interceptAt;
        interceptPlanTick = m.tick;
        Sample old = null;
        for (Sample sm : seenHist) if (m.tick - sm.tick() >= 15) old = sm;
        int sn = nav.nearest(s, 2.5);
        int r = sn < 0 ? -1 : nodeRoom[sn];
        if (old == null || r < 0) {
            interceptAt = null;
            return null;
        }
        interceptTries++;
        double secs = (m.tick - old.tick()) / 20.0;
        double vx = (s.getX() - old.x()) / secs, vz = (s.getZ() - old.z()) / secs;
        List<Integer> nodes = new ArrayList<>();
        for (int[] ex : regionExits.getOrDefault(r, List.of())) nodes.add(ex[0]);
        goalField();
        for (int g : goalSources) if (nav.loc(g).distance(s) < 40) nodes.add(g);
        if (nodes.isEmpty()) {
            interceptAt = null;
            return null;
        }
        double[][] cand = new double[nodes.size()][];
        for (int i = 0; i < nodes.size(); i++) {
            Location l = nav.loc(nodes.get(i));
            double before = Math.hypot(l.getX() - old.x(), l.getZ() - old.z()), now = Math.hypot(l.getX() - s.getX(), l.getZ() - s.getZ());
            cand[i] = new double[]{l.getX(), l.getZ(), (before - now) / secs / c.escaperSpeed()};
        }
        double[] p = Interceptor.targetProbabilities(s.getX(), s.getZ(), vx, vz, cand, 0.35);
        // 第三版第 22 項：以前看過「從這區出去常往哪裡走」當先驗（只看出口；沒資料時每個出口一樣）
        if (learn) {
            List<String> opts = new ArrayList<>();
            for (int node : nodes) opts.add(nodeRoom[node] >= 0 && nodeRoom[node] != r ? regionKey(nodeRoom[node]) : "goal");
            double sum = 0;
            for (int i = 0; i < p.length; i++) {
                p[i] *= opts.get(i).equals("goal") ? 1.0 / opts.size() : memory.routes.probability(regionKey(r), opts.get(i), opts);
                sum += p[i];
            }
            if (sum > 0) for (int i = 0; i < p.length; i++) p[i] /= sum;
        }
        int best = 0;
        for (int i = 1; i < p.length; i++) if (p[i] > p[best]) best = i;
        int keep = nodes.indexOf(interceptTarget);
        int pick;
        if (p[best] > 0.5) pick = best;
        else if (keep >= 0 && p[keep] >= 0.3) pick = keep; // 對方還大致往同一個方向：繼續堵
        else {
            interceptAt = null;
            interceptTarget = -1;
            return null;
        }
        int target = nodes.get(pick);
        interceptPredicted++;
        List<Integer> path = timedPath(sn, target, escaperCost());
        int gnode = nav.nearest(here, 2.5);
        if (gnode < 0) gnode = nav.nearest(here, 6);
        if (path.isEmpty() || gnode < 0) {
            interceptAt = null;
            return null;
        }
        DistanceField gf = timedField(costFor(Mode.CHASE), 5.3 * c.chaseSpeed(), gnode);
        DistanceField ef = timedField(escaperCost(), c.escaperSpeed(), sn);
        int v = Interceptor.interceptNode(path, gf, ef, 0.5);
        Location at = v < 0 ? null : nav.loc(v);
        if (at == null || at.distance(s) < 2) {
            interceptAt = null;
            return null;
        }
        if (interceptTarget != target || interceptAt == null) intercepts++;
        interceptTarget = target;
        interceptAt = at;
        return at;
    }

    /** 發現人時的吼聲（每種個性不一樣：逃生者可以猜今天是哪一種）。 */
    private String growl() {
        return "minecraft:" + persona.growl().toLowerCase().replace('_', '.');
    }

    private void recordSeen(LivingEntity seen) {
        if (!seen.getUniqueId().equals(histId)) {
            seenHist.clear();
            histId = seen.getUniqueId();
        }
        Location l = seen.getLocation();
        seenHist.addLast(new Sample(m.tick, l.getX(), l.getZ()));
        while (!seenHist.isEmpty() && m.tick - seenHist.peekFirst().tick() > 40) seenHist.pollFirst();
    }

    // ---- 退場埋伏 ----

    /** 冷靜期一開始：照逃生者最後的位置和下一個目標，選一個他們會經過、又看不到鬼的地方等。 */
    private void planAmbush() {
        if (c.ambushTime() <= 0 || nav == null || regions == null || vis == null) return;
        // 從哪裡來：最後看到的位置 → 追蹤粒子 → 最後一次真的看到人的地方（剛抓到人時，其他人通常在附近）
        Location fromLoc = lastSeen;
        if (fromLoc == null) for (UUID id : trackers.keySet()) if ((fromLoc = knownPosition(id)) != null) break;
        if (fromLoc == null) fromLoc = trueSeenLoc;
        if (fromLoc == null || !fromLoc.getWorld().equals(mob.getWorld())) return;
        int from = nav.nearest(fromLoc, 2.5);
        if (from < 0) return;
        goalField();
        List<Integer> path = List.of();
        for (int g : goalSources) {
            List<Integer> p = timedPath(from, g, escaperCost());
            if (!p.isEmpty() && (path.isEmpty() || p.size() < path.size())) path = p;
        }
        if (path.size() < 12) return; // 離目標太近：來不及繞過去
        // 路上的區域交界：優先選咽喉區的入口，至少離起點 10 格
        // 分數 = 咽喉區 1 分 ＋ 以前常看到人走這個交界（第 22 項，最多 1 分）；同分選前面的
        int door = -1, fallback = -1;
        double doorScore = 0;
        for (int i = 10; i < path.size(); i++) {
            int a = nodeRoom[path.get(i - 1)], b = nodeRoom[path.get(i)];
            if (a < 0 || b < 0 || a == b) continue;
            if (fallback < 0) fallback = i;
            double sc = (chokeRoom(b) || chokeRoom(a) ? 1 : 0)
                    + (learn ? Math.min(1, memory.routes.count(regionKey(a), regionKey(b)) / 3) : 0);
            if (sc > doorScore) {
                doorScore = sc;
                door = i;
            }
        }
        if (door < 0) door = fallback >= 0 ? fallback : Math.min(path.size() - 1, 15);
        int doorNode = path.get(door);
        int approach = path.get(Math.max(0, door - 6));
        Set<Integer> onPath = new HashSet<>(path);
        Location dl = nav.loc(doorNode);
        int region = nodeRoom[doorNode];
        if (region < 0) return;
        int bestCell = -1;
        double bestScore = Double.MAX_VALUE;
        for (int cell : roomCells.get(region)) {
            Location l = nav.loc(cell);
            double d = l.distance(dl);
            if (d < 1.5 || d > 5 || onPath.contains(cell)) continue;
            if (visFrom(approach, cell) || visFrom(from, cell)) continue; // 走過來的人、最後看到人的地方都看不到
            // 靠牆（周圍可走的格子少）的優先
            double score = d + nav.nav.edges(cell).size() * 0.3;
            if (score < bestScore) {
                bestScore = score;
                bestCell = cell;
            }
        }
        if (bestCell < 0) return;
        ambushAt = nav.loc(bestCell);
        ambushFace = dl;
        ambushArrive = -1;
        ambushGiveUp = m.tick + 30 * 20 + ambushTicks();
        ambushes++;
    }

    private void endAmbush() {
        ambushAt = null;
        ambushFace = null;
        ambushArrive = -1;
    }

    private void updateAmbush() {
        boolean calm = director.calm(m.tick);
        if (calm && !wasCalm) planAmbush();
        wasCalm = calm;
        if (ambushAt != null && (m.tick > ambushGiveUp || (ambushArrive >= 0 && m.tick - ambushArrive > ambushTicks()))) endAmbush();
    }

    private long ambushTicks() {
        return Math.round(c.ambushTime() * persona.ambushTimeMul());
    }

    // ---- 守讀卡機 ----

    /** 有人授權了：輪流守還沒開的大門的讀卡機，每次最多 guard-reader-cap 秒，之後走開一半的時間再回來。 */
    private Location readerWatch() {
        if (c.guardReaderCap() <= 0 || m.obj.authorized.isEmpty()) {
            readerGuardStart = -1;
            return null;
        }
        List<Location> readers = new ArrayList<>();
        for (Objectives.GateState gs : m.obj.gates) if (!gs.opened) readers.add(m.loc(gs.g.reader()).add(0.5, 0.5, 0.5));
        if (readers.isEmpty() || m.tick < readerCooldownUntil) return null;
        if (readerGuardStart < 0) {
            readerGuardStart = m.tick;
            readerTarget = readers.get(readerCycle++ % readers.size());
            readerSpot = pickWatchSpot(readerTarget, 3, 9, 0);
            if (readerSpot == null) readerSpot = snapWalkable(readerTarget.clone().add(0, -1, 0), null);
            readerGuards++;
        }
        if (m.tick - readerGuardStart > c.guardReaderCap() || readerSpot == null) {
            readerGuardStart = -1;
            readerCooldownUntil = m.tick + c.guardReaderCap() / 2;
            readerSpot = null;
            return null;
        }
        return readerSpot;
    }

    private void guardReader(Location spot) {
        if (spot.distance(mob.getLocation()) > 1.5) navigate(spot, Mode.CALM);
        else {
            stopMoving();
            if (readerTarget != null) face(readerTarget);
        }
    }

    private void face(Location at) {
        Vector v = at.toVector().subtract(mob.getLocation().toVector());
        mob.setRotation((float) Math.toDegrees(Math.atan2(-v.getX(), v.getZ())), 0);
    }

    // ---- 看時機關電源 ----

    /** 關掉的價值夠高才去：他們走回電源的平均秒數 − 鬼走過去的秒數 ≥ cut-power-advantage；開了 cut-power-max-wait 秒就不管了。 */
    private boolean cutPowerWorth() {
        if (m.tick - m.obj.powerOnTick() >= c.cutPowerMaxWait()) return true;
        if (m.tick - cutCheckTick < 40) return cutWorth;
        cutCheckTick = m.tick;
        Location lever = m.obj.leverLocation();
        if (lever == null || nav == null) return cutWorth = true;
        Location stand = lever.clone().add(0, -1, 0);
        int ln = nav.nearest(stand, 2.5);
        if (ln < 0) return cutWorth = true;
        String key = ln + ":" + m.mapVersion;
        if (!key.equals(leverFieldKey)) {
            leverFieldKey = key;
            leverField = timedField(escaperCost(), c.escaperSpeed(), ln);
        }
        List<LivingEntity> ts = targets();
        double[] secs = new double[ts.size()];
        for (int i = 0; i < ts.size(); i++) {
            Location known = knownPosition(ts.get(i).getUniqueId());
            int n = known == null ? -1 : nav.nearest(known, 3);
            secs[i] = n < 0 ? Double.POSITIVE_INFINITY : leverField.seconds(n);
        }
        double ghostSecs = travelSeconds(stand);
        double avg = AbilityTiming.UNKNOWN_SECONDS;
        if (secs.length > 0) {
            double sum = 0;
            for (double v : secs) sum += Double.isInfinite(v) ? AbilityTiming.UNKNOWN_SECONDS : v;
            avg = sum / secs.length;
        }
        lastCutAdvantage = avg - ghostSecs;
        return cutWorth = AbilityTiming.shouldCutPower(secs, ghostSecs, c.cutPowerAdvantage());
    }

    /** 鬼知道的某人位置：30 秒內看到的位置，或追蹤粒子最密的地方；不知道回傳 null。 */
    private Location knownPosition(UUID id) {
        if (id.equals(lastSeenId) && lastSeen != null && m.tick - lastSeenTick <= 600) return lastSeen;
        ParticleTracker t = trackers.get(id);
        if (t != null && t.alive() > 0) {
            int[] pos = t.positions();
            return nav.loc(pos[pos.length / 2]);
        }
        return null;
    }

    private void think(boolean busy) {
        // ---- 看 ----
        LivingEntity seen = perceive();
        hearFootsteps();
        boolean near = seen != null && seen.getLocation().distance(mob.getLocation()) <= 12;
        director.pressure(m.tick, near, c.thinkEvery());
        learnHatches();
        if (busy) {
            // 正在做事（拆東西、關電源、弄壞大門……）：5 格內有人才放下；做不到一半時 8 格內有人也放下
            if (seen == null) return;
            double d = seen.getLocation().distance(mob.getLocation());
            double prog = m.acts.progress(mob.getUniqueId());
            if (d <= 5 || (prog < 0.5 && d <= 8)) m.acts.cancel(mob.getUniqueId(), null);
            else return;
        }
        updateTrackers();
        if (thinkCount % Math.max(1, 20 / c.thinkEvery()) == 0) lookForTraces();
        if (director.shouldSense(m.tick)) sense();
        if (sensed != null && m.tick > sensedUntil) sensed = null;
        updateAmbush();
        Goal prev = goal;
        GhostBrain.Situation sit = situation(seen);
        goal = utility ? brain.decideUtility(sit, persona.weights(), "hard".equals(c.difficulty()), factors()) : brain.decide(sit);
        updateLearning();
        switch (goal.kind()) {
            case ATTACK, CHASE, SEARCH_LAST_SEEN, INVESTIGATE_NOISE, GO_SENSED -> endAmbush(); // 聽到聲音、看到人就結束埋伏
            default -> {}
        }
        if (goal.kind() != Kind.SEARCH_LAST_SEEN || prev == null || prev.kind() != Kind.SEARCH_LAST_SEEN) {
            if (goal.kind() != Kind.SEARCH_LAST_SEEN) {
                searchTunnelDone = searchHideoutDone = searchExtendDone = false;
            }
        }
        if (goal.kind() != Kind.GUARD_JAIL && prev != null && prev.kind() == Kind.GUARD_JAIL) watchSpot = null;
        act(goal, seen, prev);
    }

    // =====================================================================
    // 感知
    // =====================================================================

    /** 自由的逃生者（玩家）＋假逃生者。 */
    private List<LivingEntity> targets() {
        List<LivingEntity> out = new ArrayList<>();
        for (Player p : m.freeSurvivors()) if (!m.aiIgnores(p.getUniqueId())) out.add(p); // 測試模式被抓過的人：無視
        out.addAll(m.dummies.alive());
        if (m.side != null) out.addAll(m.side.cameraBodies()); // 看監視器的人留在原地的身體
        return out;
    }

    /** 看得到 e 嗎：距離、視野角（身邊幾格不分方向）、三條視線（眼睛、胸口、腳）任一條沒被擋。 */
    /** 停電、暗的地方蹲著的人：看得到的距離最多這麼遠（ai-ghost.crouch-dark-sight）。 */
    private final double crouchDarkSight;

    private boolean canSee(LivingEntity e) {
        if (m.tick < blindUntil || !e.getWorld().equals(mob.getWorld())) return false;
        Location me = mob.getEyeLocation();
        double d = e.getLocation().distance(mob.getLocation());
        // 修好配電箱的區域照有電算；暗的地方蹲著的人更難看到（crouch-dark-sight）
        boolean lit = m.obj.powerOn() || (m.side != null && m.side.litAt(e.getLocation()));
        double range = lit ? c.sightRange() : c.sightRangeDark();
        if (!lit && e instanceof Player sp && sp.isSneaking()) range = Math.min(range, crouchDarkSight);
        if (d > range) return false;
        if (d > c.nearAware() && !inFront(e.getLocation(), c.fovDegrees())) return false;
        // 溫泉蒸氣：鬼在煙裡或人在煙裡都看不到（貼臉 2 格內除外）
        if (d > 2 && m.gimmicks != null && (m.gimmicks.inSmoke(e.getLocation()) || m.gimmicks.inSmoke(mob.getLocation()))) return false;
        for (double h : new double[]{e.getEyeHeight(), 1.0, 0.2}) {
            Location to = e.getLocation().add(0, h, 0);
            Vector dir = to.toVector().subtract(me.toVector());
            double len = dir.length();
            if (len < 0.01) return true;
            var hit = me.getWorld().rayTraceBlocks(me, dir.normalize(), len, FluidCollisionMode.NEVER, true, bl -> bl.getType() != org.bukkit.Material.BARRIER);
            if (hit == null || hit.getHitBlock() == null) return true;
        }
        return false;
    }

    private boolean inFront(Location to, double fov) {
        Vector face = mob.getLocation().getDirection().setY(0);
        Vector dir = to.toVector().subtract(mob.getLocation().toVector()).setY(0);
        if (face.lengthSquared() < 1e-6 || dir.lengthSquared() < 1e-6) return true;
        return Math.toDegrees(face.angle(dir)) <= fov / 2;
    }

    private LivingEntity byId(UUID id) {
        if (id == null) return null;
        for (LivingEntity e : targets()) if (e.getUniqueId().equals(id)) return e;
        return null;
    }

    private LivingEntity perceive() {
        List<TargetPicker.Candidate> cands = new ArrayList<>();
        Map<UUID, LivingEntity> byId = new HashMap<>();
        for (LivingEntity e : targets()) {
            if (!canSee(e)) continue;
            boolean isBusy = e instanceof Player p ? m.acts.busy(p.getUniqueId()) : m.dummies.busy(e);
            boolean rescued = e instanceof Player p && m.survivors.get(p.getUniqueId()) != null
                    && m.tick < m.survivors.get(p.getUniqueId()).protectedUntil + 100;
            cands.add(new TargetPicker.Candidate(e.getUniqueId(), e.getLocation().distance(mob.getLocation()), isBusy, rescued));
            byId.put(e.getUniqueId(), e);
        }
        UUID pick = picker.pick(chaseId, chaseSeenTick, m.tick, cands);
        if (pick != null && !pick.equals(chaseId)) windupStart = -1;
        chaseId = pick;
        LivingEntity seen = pick == null ? null : byId.get(pick);
        if (seen != null) {
            trackers.remove(seen.getUniqueId());
            lostHandledId = null;
            trueSeenTick = m.tick;
            trueSeenLoc = seen.getLocation();
            Long lost = lostAt.remove(seen.getUniqueId());
            if (lost != null && m.tick - lost <= 600) reacquire30++;
            chaseSeenTick = m.tick;
            recordSeen(seen);
            learnSighting(seen);
            Location now = seen.getLocation();
            if (prevSeenLoc != null && prevSeenLoc.getWorld().equals(now.getWorld()) && prevSeenLoc.distance(now) > 0.2)
                lastDir = now.toVector().subtract(prevSeenLoc.toVector()).setY(0).normalize();
            prevSeenLoc = now;
            if (lastSeenTick < m.tick - c.searchTime() || lastSeen == null) searchStart = m.tick;
            lastSeen = now;
            lastSeenTick = m.tick;
            lastSeenId = seen.getUniqueId();
            director.contact(m.tick);
            contactStat();
            if (m.tick - lastChaseTick > 100) {
                chases++;
                interceptThisChase = c.interceptChance() > 0 && (persona.interceptAlways() || m.rnd.nextDouble() < c.interceptChance());
                interceptAt = null;
                interceptTarget = -1;
                // 發現人：短促的低吼，讓逃生者知道「被看到了」
                mob.getWorld().playSound(mob.getLocation(), growl(), 1.2f, 1.4f);
            }
            lastChaseTick = m.tick;
        } else if (chaseId != null) {
            // 剛看不到（1 秒內）：繼續用對方的真實位置，等同真人鬼看到他往哪邊轉
            LivingEntity e = byId(chaseId);
            if (e != null && m.tick - chaseSeenTick <= 15) {
                lastSeen = e.getLocation();
                lastSeenTick = m.tick;
            }
        }
        if (seen == null && lastSeenId != null && nav != null && lastSeen != null
                && SearchRules.lostTarget(m.tick, trueSeenTick, lastSeenId.equals(lostHandledId))) {
            // 追丟了（超過 1 秒沒真的看到）：在最後看到的格子撒粒子
            ParticleTracker t = newTracker();
            t.seed(nav.nearest(lastSeen, 2.5), m.tick);
            if (t.alive() > 0) trackers.put(lastSeenId, t);
            lostHandledId = lastSeenId;
            losses++;
            lostAt.put(lastSeenId, m.tick);
            LivingEntity lostOne = byId(lastSeenId);
            if (lostOne != null && learnable(lostOne))
                pendingEscapes.put(lastSeenId, new PendingEscape(m.tick, nav.nearest(lastSeen, 2.5), new AiMemory.Trick[1]));
        }
        return seen;
    }

    /** 聽腳步：跑步 hear-sprint 格、走路 hear-walk 格（蹲著沒有聲音），開關門另外算。 */
    private void hearFootsteps() {
        for (LivingEntity e : targets()) {
            if (!e.getWorld().equals(mob.getWorld())) continue;
            double d = e.getLocation().distance(mob.getLocation());
            boolean moving, sprint;
            if (e instanceof Player p) {
                Match.Survivor sv = m.survivors.get(p.getUniqueId());
                moving = sv != null && m.tick - sv.stillSince < 10;
                sprint = p.isSprinting();
                if (p.isSneaking()) continue;
            } else {
                moving = e instanceof Mob mb && mb.getPathfinder().hasPath();
                sprint = m.dummies.running(e);
            }
            if (!moving) continue;
            double range = sprint ? c.hearSprint() : c.hearWalk();
            if (d <= range) hearSound(e.getLocation(), Match.NOISE_LOW, range);
        }
    }

    /** 感應（作弊）：多久沒接觸才觸發，給「最近那個人在哪一間房間」；那個人會聽到心跳加快。 */
    private void sense() {
        LivingEntity best = null;
        double bd = Double.MAX_VALUE;
        for (LivingEntity e : targets()) {
            if (!e.getWorld().equals(mob.getWorld())) continue;
            double d = e.getLocation().distance(mob.getLocation()) + (m.sameFloor(e.getLocation(), mob.getLocation()) ? 0 : 12);
            if (d < bd) {
                bd = d;
                best = e;
            }
        }
        if (best == null) return;
        director.sensed(m.tick);
        senses++;
        lastSenseTick = m.tick;
        sensedRoom = roomAt(best.getLocation());
        sensed = sensedRoom >= 0 ? snapWalkable(m.loc(roomList.get(sensedRoom).center()), null) : snapWalkable(best.getLocation(), null);
        sensedId = best.getUniqueId();
        sensedUntil = m.tick + 30 * 20;
        if (nav != null && sensedRoom >= 0) {
            List<Integer> cells = new ArrayList<>();
            if (nodeRoom == null) {
                nodeRoom = new int[nav.nav.size()];
                for (int i = 0; i < nodeRoom.length; i++) nodeRoom[i] = nav.isMain(i) ? roomAt(nav.loc(i)) : -1;
            }
            for (int i = 0; i < nodeRoom.length; i++) if (nodeRoom[i] == sensedRoom) cells.add(i);
            sensedTracker = newTracker();
            sensedTracker.seedUniform(cells, m.tick);
            if (sensedTracker.alive() == 0) sensedTracker = null;
        }
        if (best instanceof Player target) {
            target.playSound(target.getLocation(), Sound.ENTITY_WARDEN_HEARTBEAT, 1f, 1.6f);
            Bukkit.getScheduler().runTaskLater(m.plugin, () -> target.playSound(target.getLocation(), Sound.ENTITY_WARDEN_HEARTBEAT, 1f, 1.8f), 6L);
            target.sendActionBar(Component.text("你覺得有東西盯上你了……", NamedTextColor.DARK_RED));
        }
    }

    /** 地圖知識跟真人鬼一樣：只知道自己看過的地洞出入口。 */
    private void learnHatches() {
        if (m.tick % 20 != 0) return;
        for (int i = 0; i < m.b.hatches.size(); i++) {
            if (knownHatches.contains(i)) continue;
            Location t = m.loc(m.b.hatches.get(i).trapdoor()).add(0, 1.1, 0);
            if (!t.getWorld().equals(mob.getWorld()) || t.distance(mob.getLocation()) > 16) continue;
            Location me = mob.getEyeLocation();
            Vector dir = t.toVector().subtract(me.toVector());
            var hit = me.getWorld().rayTraceBlocks(me, dir.clone().normalize(), dir.length(), FluidCollisionMode.NEVER, true, bl -> bl.getType() != org.bukkit.Material.BARRIER);
            if (hit == null || hit.getHitBlock() == null) knownHatches.add(i);
        }
    }

    private double gateSlack = 1, lastCutAdvantage = Double.NaN;

    /** Utility 的其他考量（第三版第 16 項）：只用鬼知道的東西。 */
    private GhostBrain.Factors factors() {
        Location here = mob.getLocation();
        double nl = noise == null ? Double.NaN : noiseLevel / 2.0;
        double nd = noise == null || !noise.getWorld().equals(here.getWorld()) ? Double.NaN : noise.distance(here);
        // 粒子集中程度：中位數那顆粒子 4 格內的比例（越集中越值得照粒子找）
        double conc = 1;
        ParticleTracker tr = lastSeenId == null ? null : trackers.get(lastSeenId);
        if (tr != null && tr.alive() > 0) {
            int[] pos = tr.positions();
            Location mid = nav.loc(pos[pos.length / 2]);
            conc = Math.min(1, tr.shareNear(mid.getX(), mid.getY(), mid.getZ(), 4) * 2);
        }
        double jailGuarded = guard ? Math.max(0, 1 - (guardUntil - m.tick) / (double) Math.max(1, c.guardCap())) : 0;
        // 有人可能正在靠近監獄：監獄 15 格內最近有聲音 → 1；粒子在監獄 12 格內的比例 ×2；都沒有 → 0.5
        double approach = 0.5;
        Location jail = m.obj.jailNearestTo(here);
        if (jail != null) {
            if (noise != null && m.tick - noiseTick <= 200 && noise.getWorld().equals(jail.getWorld()) && noise.distance(jail) <= 15) approach = 1;
            else for (ParticleTracker t : trackers.values()) approach = Math.max(approach, Math.min(1, 2 * t.shareNear(jail.getX(), jail.getY(), jail.getZ(), 12)));
        }
        double readerGuarded = readerGuardStart < 0 ? 0 : (m.tick - readerGuardStart) / (double) Math.max(1, c.guardReaderCap());
        double cutValue = Double.isNaN(lastCutAdvantage) ? 1 : lastCutAdvantage / Math.max(1, 2 * c.cutPowerAdvantage());
        return new GhostBrain.Factors(nl, nd, conc, gateSlack, jailGuarded, approach, readerGuarded, cutValue);
    }

    private GhostBrain.Situation situation(LivingEntity seen) {
        GhostBrain.Seen s = seen == null ? null
                : new GhostBrain.Seen(seen.getUniqueId(), pt(seen.getLocation()), seen.getLocation().distance(mob.getLocation()));
        List<Point> gates = new ArrayList<>();
        gateSlack = 1;
        for (Objectives.GateState gs : m.obj.runningUnsabotagedGates()) {
            // 趕得上才去：走過去的時間＋弄壞 4 秒 ＜ 剩下的倒數
            double secs = travelSeconds(m.loc(gs.g.reader())) + m.o.ghostSabotage() / 20.0;
            if (secs < gs.remaining / 20.0) {
                gates.add(pt(m.loc(gs.g.reader())));
                gateSlack = Math.min(gateSlack, (gs.remaining / 20.0 - secs) / Math.max(1, gs.remaining / 20.0));
            }
        }
        // 倒數中的貨梯跟大門一樣：趕得上就去弄壞（站 4 秒，倒數回到滿，一次）
        Location elev = m.side == null ? null : m.side.elevatorTarget();
        if (elev != null) {
            double secs = travelSeconds(elev) + m.o.ghostSabotage() / 20.0;
            double left = m.side.elevatorTicksLeft() / 20.0;
            if (secs < left) {
                gates.add(pt(elev));
                gateSlack = Math.min(gateSlack, (left - secs) / Math.max(1, left));
            }
        }
        int jailed = 0;
        for (Match.Survivor sv : m.survivors.values()) if (sv.status == Match.Status.JAILED) jailed++;
        if (jailed > jailedBefore) {
            guard = m.rnd.nextDouble() < c.guardJailChance() + persona.guardJailChanceAdd();
            guardUntil = m.tick + c.guardCap();
        }
        if (jailed == 0 || m.tick > guardUntil) guard = false;
        jailedBefore = jailed;
        Location jail = m.obj.jailNearestTo(mob.getLocation());
        // 電源剛開不要馬上衝去關（像被遙控）：開了 power-cut-delay 秒以後才算
        // 第三版：而且要「關掉以後他們要花很久才修得回來」（看時機）
        boolean powerOn = m.obj.powerOn() && m.tick - m.obj.powerOnTick() >= c.powerCutDelay()
                && !m.obj.cutPowerUsed() && cutPowerWorth();
        return new GhostBrain.Situation(m.tick, m.tick < blindUntil, s, pt(lastSeen), lastSeenTick, pt(noise), noiseTick, pt(sensed),
                m.obj.cardRevealOpen(), m.obj.cardMoveAvailable(), powerOn, m.obj.cutPowerUsed(), gates,
                guard && jailed > 0, pt(jail), pt(mob.getLocation()), pt(ambushAt), pt(readerWatch()));
    }

    // =====================================================================
    // 攻擊（每 tick）
    // =====================================================================

    private void attackTick() {
        LivingEntity t = chaseId == null ? null : byId(chaseId);
        if (t == null || m.tick < blindUntil || !t.getWorld().equals(mob.getWorld())) {
            windupStart = -1;
            return;
        }
        double d = t.getLocation().distance(mob.getLocation());
        boolean inReach = d <= c.attackRange() && inFront(t.getLocation(), c.fovDegrees()) && mob.hasLineOfSight(t);
        if (windupStart < 0) {
            if (inReach && m.tick >= m.fatigueUntil) {
                windupStart = m.tick;
                mob.lookAt(t);
                // 前搖的預警：逃生者聽得到，才有辦法靠反應騙它揮空
                mob.getWorld().playSound(mob.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.8f, 0.5f);
            }
            return;
        }
        mob.lookAt(t);
        if (m.tick - windupStart < c.reaction()) return;
        windupStart = -1;
        if (inReach) {
            mob.swingMainHand();
            if (t instanceof Player p) m.ghostHit(p);
            else if (m.side != null && m.side.hitBody(t)) forget(t.getUniqueId()); // 打到看監視器的人的身體 = 抓到那個人
            else {
                m.ghostSwing();
                catches++;
                director.captured(m.tick);
                m.dummies.caught(t, mob);
                forget(t.getUniqueId());
            }
        } else if (m.rnd.nextDouble() < c.whiffChance()) {
            mob.swingMainHand();
            m.ghostSwing();
            misses++;
        }
    }

    // =====================================================================
    // 行動
    // =====================================================================

    private void act(Goal g, LivingEntity seen, Goal prev) {
        speedMul = switch (g.kind()) {
            case ATTACK, CHASE -> c.chaseSpeed();
            case SEARCH_LAST_SEEN, INVESTIGATE_NOISE, GO_SENSED, SABOTAGE_GATE -> c.searchSpeed();
            default -> c.patrolSpeed();
        };
        Mode mode = switch (g.kind()) {
            case ATTACK, CHASE -> Mode.CHASE;
            case SEARCH_LAST_SEEN, INVESTIGATE_NOISE, GO_SENSED -> Mode.SEARCH;
            default -> Mode.CALM;
        };
        Location here = mob.getLocation();
        if (g.kind() != Kind.SEARCH_LAST_SEEN && g.kind() != Kind.INVESTIGATE_NOISE && g.kind() != Kind.GO_SENSED && g.kind() != Kind.PATROL)
            lookStep = -1;
        switch (g.kind()) {
            case ATTACK, CHASE -> {
                lookStep = -1;
                Location cut = g.kind() == Kind.CHASE ? interceptPoint(seen) : null;
                navigate(cut != null ? cut : seen.getLocation(), mode);
            }
            case SEARCH_LAST_SEEN -> search(here);
            case INVESTIGATE_NOISE -> {
                if (noise.distance(here) < 2.5) {
                    noise = null;
                    if (!hideoutCheck(here, noiseTick, c.checkHideoutNoiseChance())) startLook();
                } else {
                    // 困難：做事的聲音（中、高等級）→ 對方還沒發現鬼，從視線外摸過去
                    boolean sneak = c.tacticalApproach() && noiseLevel >= Match.NOISE_MID && nav != null;
                    sneakFrom = sneak ? nav.nearest(noise, 3) : -1;
                    if (sneak && sneakFrom >= 0 && noiseTick != sneakNoiseTick) { // 每個聲音只算一次
                        sneaks++;
                        sneakNoiseTick = noiseTick;
                    }
                    navigate(noise, mode);
                    sneakFrom = -1;
                }
            }
            case GO_SENSED -> {
                if (sensed.distance(here) < 2.5) {
                    Location tgt = trackerTarget(sensedTracker);
                    if (tgt != null && tgt.distance(here) > 2.5 && m.tick < sensedUntil) {
                        sensed = tgt;
                        navigate(tgt, mode);
                        return;
                    }
                    sensed = null;
                    if (!hideoutCheck(here, lastSenseTick, c.checkHideoutNoiseChance())) startLook();
                } else navigate(sensed, mode);
            }
            case MOVE_CARD -> {
                stopMoving();
                m.obj.ghostMoveCard(mob);
            }
            case SABOTAGE_GATE -> {
                Location reader = toLoc(g.target());
                Location elevT = m.side == null ? null : m.side.elevatorTarget();
                if (elevT != null && elevT.distance(reader) < 1) {
                    if (reader.distance(here) <= 2.5) {
                        stopMoving();
                        m.side.ghostSabotageElevator(mob);
                    } else navigate(reader, mode);
                    return;
                }
                if (reader.distance(here) <= 2.5) {
                    for (Objectives.GateState gs : m.obj.runningUnsabotagedGates()) {
                        if (m.loc(gs.g.reader()).distance(reader) < 0.5) {
                            stopMoving();
                            m.obj.ghostSabotage(mob, gs);
                        }
                    }
                } else navigate(reader, mode);
            }
            case CUT_POWER -> {
                Location lever = m.obj.leverLocation();
                if (lever == null) return;
                Location stand = lever.clone().add(0, -1, 0); // 拉桿裝在牆上（眼睛高度），站在它下面那格
                if (stand.distance(here) <= 2.5) {
                    stopMoving();
                    m.obj.ghostCutPower(mob);
                } else navigate(stand, mode);
            }
            case GUARD_JAIL -> guardJail(toLoc(g.target()));
            case GUARD_READER -> guardReader(toLoc(g.target()));
            case AMBUSH -> {
                Location at = toLoc(g.target());
                if (at.distance(here) > 1.2) navigate(at, Mode.CALM);
                else {
                    // 站著不動（沒有腳步聲）、面向來路
                    stopMoving();
                    if (ambushArrive < 0) ambushArrive = m.tick;
                    if (ambushFace != null) face(ambushFace);
                }
            }
            case PATROL -> patrol();
        }
    }

    enum Mode { CHASE, SEARCH, CALM }

    // ---- 搜尋 ----

    /** 搜尋的子目標開始時延長搜尋時間（最多 search-cap），不然走到一半會被切回巡邏。 */
    private void extendSearch() {
        if (m.tick - searchStart < c.searchCap()) lastSeenTick = m.tick;
    }

    private void search(Location here) {
        if (workPendingHole()) return;
        if (lastSeen.distance(here) >= 2.5) {
            navigate(lastSeen, Mode.SEARCH);
            return;
        }
        if (lookStep >= 0) return;
        // ① 最後看到的位置在地洞出入口旁邊：有機率下去追（照實際的路，可能走通道、也可能從地面繞）
        if (!searchTunnelDone) {
            searchTunnelDone = true;
            for (int i = 0; i < m.b.hatches.size(); i++) {
                Location t = m.loc(m.b.hatches.get(i).trapdoor()).add(0, 1, 0);
                if (t.distance(lastSeen) > 2.5) continue;
                int other = i ^ 1; // 生成時兩個出入口一組：0-1、2-3……
                if (other >= m.b.hatches.size()) break;
                // 證據式：看到人在洞口消失、之後沒聲音 → 幾乎一定跟；粒子很多在另一端 → 加倍
                Location otherTop = m.loc(m.b.hatches.get(other).trapdoor()).add(0, 1, 0);
                ParticleTracker trk = lastSeenId == null ? null : trackers.get(lastSeenId);
                double share = trk == null ? 0 : trk.shareNear(otherTop.getX(), otherTop.getY(), otherTop.getZ(), 3);
                double p = HideoutOdds.chance(Math.min(0.95, c.followTunnelChance() + learned(AiMemory.Trick.HATCH, 0.4)), trueSeenLoc == null ? 99 : t.distance(trueSeenLoc),
                        (m.tick - trueSeenTick) / 20.0, SearchRules.quietSince(lastQuietBreakTick, trueSeenTick), share, 0);
                if (m.rnd.nextDouble() >= p) break;
                knownHatches.add(i);
                knownHatches.add(other); // 跟著鑽下去，自然會知道通到哪
                lastSeen = m.loc(m.b.hatches.get(other).trapdoor()).add(0, 1, 0);
                extendSearch();
                navigate(lastSeen, Mode.SEARCH);
                return;
            }
        }
        // ② 附近有暗格：這次搜尋只擲一次骰子，中了就查最近的一個
        if (!searchHideoutDone) {
            searchHideoutDone = true;
            if (hideoutCheck(trueSeenLoc != null ? trueSeenLoc : lastSeen, trueSeenTick,
                    Math.min(0.95, c.checkHideoutChance() + learned(AiMemory.Trick.HIDEOUT, 0.35)))) {
                extendSearch();
                return;
            }
        }
        // ③（第三版）有粒子：去粒子最密、現在看不到的地方
        ParticleTracker tr = lastSeenId == null ? null : trackers.get(lastSeenId);
        if (tr != null) {
            Location tgt = trackerTarget(tr);
            if (tgt != null && tgt.distance(here) > 2.5) {
                lastSeen = tgt;
                lastSeenTick = m.tick;
                navigate(tgt, Mode.SEARCH);
                return;
            }
        }
        // ③（第二版，沒有粒子時）照對方最後的移動方向多走 6～8 格
        if (tr == null && !searchExtendDone && lastDir != null) {
            searchExtendDone = true;
            Location ahead = snapWalkable(lastSeen.clone().add(lastDir.clone().multiply(7)), lastSeen);
            if (ahead != null && ahead.distance(here) > 3) {
                lastSeen = ahead;
                extendSearch();
                navigate(ahead, Mode.SEARCH);
                return;
            }
        }
        startLook();
    }

    /**
     * 證據式查暗格：12 格內每個暗格算機率（{@link HideoutOdds}：參考點 ref 離入口多遠、ref 那時之後多久、之後有沒有聲音、
     * 粒子在暗格裡的比例），挑機率最高的那個擲一次，中了就去打大它的入口。
     */
    private boolean hideoutCheck(Location ref, long refTick, double base) {
        if (ref == null) return false;
        int bestHole = -1;
        double bestP = 0;
        for (Building.Room r : m.b.rooms) {
            if (!r.hideout()) continue;
            Location pocket = m.loc(r.center());
            if (!pocket.getWorld().equals(ref.getWorld()) || pocket.distance(ref) > 12) continue;
            for (int i = 0; i < m.b.crawlHoles.size(); i++) {
                Building.CrawlHole h = m.b.crawlHoles.get(i);
                if (m.holeIndexAt(h.lower()) < 0) continue;
                Location ent = m.loc(h.lower());
                if (ent.distance(pocket) > 3) continue;
                double share = 0;
                for (ParticleTracker tr : trackers.values()) share = Math.max(share, tr.shareNear(pocket.getX() + 0.5, pocket.getY(), pocket.getZ() + 0.5, 1));
                double p = HideoutOdds.chance(base, ent.distance(ref), (m.tick - refTick) / 20.0, SearchRules.quietSince(lastQuietBreakTick, refTick), share, 0);
                if (p > bestP) {
                    bestP = p;
                    bestHole = i;
                }
            }
        }
        if (bestHole < 0 || m.rnd.nextDouble() >= bestP) return false;
        pendingHole = bestHole;
        return true;
    }

    private int pendingHole = -1;

    /** 去打大選好的暗格入口（search／noise 之後幾次思考都會走到這裡）。 */
    private boolean workPendingHole() {
        if (pendingHole < 0) return false;
        if (m.holeIndexAt(m.b.crawlHoles.get(pendingHole).lower()) < 0) {
            pendingHole = -1;
            return false;
        }
        Location hole = m.loc(m.b.crawlHoles.get(pendingHole).lower());
        if (hole.distance(mob.getLocation()) <= 2.5) {
            stopMoving();
            int widening = pendingHole;
            if (m.ghostWidenHole(pendingHole)) {
                traces.ghostChanged(WorldTraces.Type.HOLE, widening, 1, m.tick);
                pendingHole = -1;
            }
        } else navigate(hole, Mode.SEARCH);
        return true;
    }

    // ---- 轉頭張望 ----

    private void startLook() {
        if (lookStep >= 0) return;
        stopMoving();
        lookStep = 0;
        lookBase = mob.getLocation().getYaw();
        lookNext = m.tick;
    }

    private void lookTick() {
        if (m.tick < lookNext) {
            mob.setRotation(currentLookYaw(), 0);
            return;
        }
        lookStep++;
        if (lookStep > 3) {
            lookStep = -1;
            return;
        }
        lookNext = m.tick + 12 + m.rnd.nextInt(8);
        mob.setRotation(currentLookYaw(), 0);
    }

    private float currentLookYaw() {
        float[] offs = {0, -70, 70, 180};
        return lookBase + offs[Math.min(lookStep, offs.length - 1)];
    }

    // ---- 守監獄 ----

    private Location watchSpot;
    private long watchUntil;
    private int watchPhase;

    /** 守望點：籠子「守太久救援會變快」範圍外、看得到籠子的格子，站著面向監獄；兩個點以後走開一陣子再回來。 */
    private void guardJail(Location jail) {
        if (watchSpot == null || m.tick > watchUntil) {
            watchPhase++;
            if (watchPhase % 3 == 0) {
                // 走開：去一個離監獄遠一點的地方 15～20 秒
                watchSpot = snapWalkable(jitter(jail, m.s.campRadius() + 12), jail);
                watchUntil = m.tick + 300 + m.rnd.nextInt(100);
            } else {
                watchSpot = pickWatchSpot(jail, m.s.campRadius() + 1, m.s.campRadius() + 6, 3.3);
                watchUntil = m.tick + 300;
            }
        }
        if (watchSpot == null) {
            navigate(jail, Mode.CALM);
            return;
        }
        if (watchSpot.distance(mob.getLocation()) > 1.5) navigate(watchSpot, Mode.CALM);
        else {
            stopMoving();
            face(jail);
        }
    }

    /** 守望點：同一層、離 target min～max 格、眼睛看得到 target（高度 +targetHeight）的格子，隨機挑一個。 */
    private Location pickWatchSpot(Location jail, double min, double max, double targetHeight) {
        if (nav == null) return null;
        List<Location> cands = new ArrayList<>();
        List<Integer> candNodes = new ArrayList<>();
        for (int i = 0; i < nav.nav.size(); i++) {
            if (!nav.isMain(i)) continue;
            Location l = nav.loc(i);
            if (!m.sameFloor(l, jail)) continue;
            double d = l.distance(jail);
            if (d < min || d > max) continue;
            Location eye = l.clone().add(0, 1.6, 0), cage = jail.clone().add(0, targetHeight, 0);
            Vector dir = cage.toVector().subtract(eye.toVector());
            var hit = eye.getWorld().rayTraceBlocks(eye, dir.clone().normalize(), dir.length(), FluidCollisionMode.NEVER, true, bl -> bl.getType() != org.bukkit.Material.BARRIER);
            if (hit == null || hit.getHitBlock() == null) {
                cands.add(l);
                candNodes.add(i);
            }
        }
        if (cands.isEmpty()) return null;
        // 最小割（第 12 項）：優先選看得到最多「切斷用的門口」的格子
        List<Integer> doors = cutDoors(jail);
        if (!doors.isEmpty() && vis != null) {
            int best = 0;
            List<Location> top = new ArrayList<>();
            for (int k = 0; k < cands.size(); k++) {
                int seen = 0;
                for (int dn : doors) if (visFrom(candNodes.get(k), dn)) seen++;
                if (seen > best) {
                    best = seen;
                    top.clear();
                }
                if (seen == best && seen > 0) top.add(cands.get(k));
            }
            if (!top.isEmpty()) {
                cutWatchSpots++;
                return top.get(m.rnd.nextInt(top.size()));
            }
        }
        return cands.get(m.rnd.nextInt(cands.size()));
    }

    private int cutWatchSpots;
    private final Map<Integer, List<Integer>> cutDoorCache = new HashMap<>();

    /** 目標所在的區：最小割的那幾個交界，取靠目標那一側的門口格子。 */
    private List<Integer> cutDoors(Location target) {
        if (regions == null) return List.of();
        int t = roomAt(target);
        if (t < 0) return List.of();
        return cutDoorCache.computeIfAbsent(t, k -> {
            List<Integer> out = new ArrayList<>();
            for (int[] e : regions.minCut(k, 3))
                for (int[] ex : regionExits.getOrDefault(e[1], List.of())) if (ex[1] == e[0]) out.add(ex[2]);
            return out;
        });
    }

    // ---- 巡邏 ----

    private void patrol() {
        if (workPendingHole()) return;
        Location here = mob.getLocation();
        if (patrolTarget != null && patrolTarget.distance(here) > 2.5 && m.tick < patrolUntil) {
            navigate(patrolTarget, Mode.CALM);
            return;
        }
        if (patrolTarget != null && patrolTarget.distance(here) <= 2.5) {
            if (patrolRoom >= 0) rooms.visited(patrolRoom, m.tick);
            patrolTarget = null;
            startLook();
            return;
        }
        if (lookStep >= 0) return;
        int cur = roomAt(here);
        boolean calm = director.calm(m.tick);
        boolean firstPatrol = patrolCount++ == 0;
        double hatchRoomBonus = learned(AiMemory.Trick.HATCH, 0.4);
        Set<Integer> hatchRooms = new HashSet<>();
        if (hatchRoomBonus > 0) for (Building.Hatch h : m.b.hatches) hatchRooms.add(roomAt(m.loc(h.trapdoor()).add(0, 1, 0)));
        Location contact = lastSeen;
        boolean powerOn = m.obj.powerOn();
        boolean anyAuthorized = !m.obj.authorized.isEmpty();
        int pick = rooms.choose(m.rnd, m.tick, cur, r -> {
            Building.Room room = roomList.get(r.id());
            double w = 1;
            if (!powerOn && room.type() == RoomType.ELECTRICAL) w *= 3;
            if (powerOn && !anyAuthorized && room.type() == RoomType.SECURITY) w *= 3;
            if (anyAuthorized && nearGate(m.loc(room.center()))) w *= 3;
            // 冷靜期：偏向離最後接觸的地方遠一點
            if (calm && contact != null && contact.getWorld().equals(here.getWorld()))
                w *= Math.min(3, 0.3 + m.loc(room.center()).distance(contact) / 20.0);
            // 第三版：死路房間沒有證據 ×0.3（只在門口看一眼）；咽喉區 ×1.5
            if (regions != null) {
                // 學會「躲死路房間」：0.3 慢慢回到 1
                if (regions.deadEnd(r.id()) && !roomEvidence(r.id())) w *= 0.3 + 0.7 * learned(AiMemory.Trick.DEAD_END, 1);
                else if (chokeRoom(r.id())) w *= 1.5 * persona.chokePatrolMul();
            }
            // 熱點：常看到人的區 ×（1 ＋ 熱度）；開局第一個巡邏目標更偏向最熱的區
            w *= 1 + regionHeat(r.id()) * (firstPatrol ? 3 : 1);
            // 學會「鑽地洞」：有地洞出入口的區常去看
            if (hatchRoomBonus > 0 && hatchRooms.contains(r.id())) w *= 1 + hatchRoomBonus;
            Location l = m.loc(room.center());
            if (m.side != null) w *= m.side.patrolBias(l); // 有人在推進的支線附近（貨梯、下水道、停機坪）常去看
            for (Map.Entry<Location, Long> b : badTargets.entrySet())
                if (b.getValue() > m.tick && b.getKey().getWorld().equals(l.getWorld()) && b.getKey().distance(l) < 4) w = 0;
            return w;
        }, r -> m.loc(roomList.get(r.id()).center()).distance(here) / 4.0);
        if (pick < 0) return;
        patrolRoom = pick;
        patrolTarget = snapWalkable(m.loc(roomList.get(pick).center()), null);
        if (regions != null && regions.deadEnd(pick) && !roomEvidence(pick) && learned(AiMemory.Trick.DEAD_END, 1) < 0.5) {
            // 死路房間：走到入口往裡面看（不進去，被看到就跑不掉的地方留給有證據的時候）
            int[] best = null;
            double bd = Double.MAX_VALUE;
            for (int[] ex : regionExits.getOrDefault(pick, List.of())) {
                double d = nav.loc(ex[2]).distance(here);
                if (d < bd) {
                    bd = d;
                    best = ex;
                }
            }
            if (best != null) {
                patrolTarget = nav.loc(best[2]);
                peeks++;
            }
        }
        patrolUntil = m.tick + 30 * 20;
        if (patrolTarget != null) navigate(patrolTarget, Mode.CALM);
    }

    private boolean nearGate(Location l) {
        for (Building.Gate g : m.b.gates) if (m.loc(g.reader()).getWorld().equals(l.getWorld()) && m.loc(g.reader()).distance(l) < 14) return true;
        return false;
    }

    /** at 所在的房間（同一層最近的房間中心），找不到回傳 -1。 */
    private int roomAt(Location at) {
        int best = -1;
        double bd = Double.MAX_VALUE;
        for (int i = 0; i < roomList.size(); i++) {
            Building.Room r = roomList.get(i);
            Location l = m.loc(r.center());
            if (!l.getWorld().equals(at.getWorld()) || !m.sameFloor(l, at)) continue;
            double d = l.distance(at) / Math.sqrt(Math.max(9, r.area()));
            if (d < bd) {
                bd = d;
                best = i;
            }
        }
        return best;
    }

    // =====================================================================
    // 走路：自己的 A* ＋ 原版尋路走短路
    // =====================================================================

    private void stopMoving() {
        mob.getPathfinder().stopPathfinding();
        rawPath = List.of();
        wayPath = List.of();
        rawGoal = -1;
        vanillaTarget = null;
    }

    /** 這一段的成本：卡住的門 2.5 秒、障礙物 3.5 秒、蹲伏小洞 4 秒（只在追人、搜尋時用）、地洞（只在追人、搜尋時、而且知道兩端）。 */
    private GridNav.Cost costFor(Mode mode) {
        GridNav.Cost base = baseCost(mode);
        final int from = sneakFrom;
        if (from < 0 || vis == null) return base;
        // 戰術尋路（第三版第 4 項）：聲音那裡的人看得到的格子多算 sneak-penalty 格，從背後、視線外摸過去
        return com.ghosthunt.game.ai.TacticalCost.wrap(base, v -> visFrom(from, v), c.sneakPenalty());
    }

    /** 去聲音的地方時，從這一格（聲音）看得到的格子要避開；-1 = 不用。 */
    private int sneakFrom = -1;
    private int sneaks;
    private long sneakNoiseTick = Long.MIN_VALUE;

    private GridNav.Cost baseCost(Mode mode) {
        double bps = 5.3 * speedMul; // 每秒幾格
        return e -> switch (e.kind()) {
            case WALK, STAIR -> 0;
            case DOOR -> m.isDoorLocked(nav.doors.get(e.ref())) ? Double.POSITIVE_INFINITY // 上鎖的門、關著的鐵捲門：鬼打不開
                    : m.isDoorJammed(nav.doors.get(e.ref())) ? m.o.breakJam() / 20.0 * bps / 2 : 0.15;
            case BARRICADE -> m.barricadeIndexBarricaded(e.ref()) ? m.s.breakBarricade() / 20.0 * bps / 2 : 0;
            case HOLE -> m.isHoleWidened(e.ref()) ? 0
                    : mode == Mode.CALM ? Double.POSITIVE_INFINITY
                    : m.o.widenHole() / 20.0 * bps / 2 * (1 - learned(AiMemory.Trick.HOLE, 0.4)); // 學會了：更早決定打大洞口
            case HATCH -> {
                if (mode == Mode.CALM) yield Double.POSITIVE_INFINITY;
                int other = e.ref() ^ 1;
                if (!knownHatches.contains(e.ref()) || !knownHatches.contains(other)) yield Double.POSITIVE_INFINITY;
                yield (m.isHatchPried(e.ref()) ? 0 : m.o.widenHole() / 20.0 * bps) + 1.5 * bps;
            }
        };
    }

    /** 估計走到 to 要幾秒（用 A* 的路線長度；找不到就用直線距離）。 */
    private double travelSeconds(Location to) {
        if (nav == null) return to.distance(mob.getLocation()) / 5.3;
        int a = nav.nearest(mob.getLocation(), 2.5), b = nav.nearestMain(to, 3);
        List<Integer> p = timedPath(a, b, costFor(Mode.SEARCH));
        if (p.isEmpty()) return to.distance(mob.getLocation()) / 5.3 * 1.5;
        return p.size() / (5.3 * c.searchSpeed());
    }

    /** 走到 goal：需要時重新找路（目標移動、地圖變了、追人時每 2 秒）。 */
    /** 最近一次 navigate 的模式（追人時不做抵達減速）。 */
    private Mode navMode = Mode.CALM;

    private void navigate(Location goal, Mode mode) {
        navMode = mode;
        if (goal == null) return;
        int g = nav == null ? -1 : nav.nearestMain(goal, 3);
        if (g < 0 && nav != null) g = nav.nearest(goal, 3); // 例如跟著鑽地洞：目標在通道另一端的出入口
        if (g < 0) {
            // 目標不在自己的圖上：交給原版尋路，舊的路點要清掉，不然兩邊一起推會卡住（度假村實測，2026-11-21）
            rawPath = List.of();
            wayPath = List.of();
            rawGoal = -1;
            vanillaMove(goal);
            return;
        }
        boolean replan = rawGoal < 0 || wayPath.isEmpty() || planVersion != m.mapVersion
                || (g != rawGoal && nav.loc(g).distance(nav.loc(rawGoal)) > 2)
                || (mode == Mode.CHASE && m.tick - planTick >= 40);
        if (!replan) return;
        int start = nav.nearest(mob.getLocation(), 2.5);
        cost = costFor(mode);
        List<Integer> p = timedPath(start, g, cost);
        planTick = m.tick;
        planVersion = m.mapVersion;
        if (p.isEmpty()) {
            // 自己的圖找不到：交給原版尋路試試
            rawPath = List.of();
            wayPath = List.of();
            rawGoal = g;
            vanillaMove(goal);
            return;
        }
        rawPath = p;
        wayPath = nav.nav.simplify(p, nav::clear);
        wayIdx = Math.min(1, wayPath.size() - 1);
        rawGoal = g;
        if (vanillaTarget != null || mob.getPathfinder().hasPath()) mob.getPathfinder().stopPathfinding(); // 原版尋路還在走會跟推著走打架
        vanillaTarget = null;
    }

    private void vanillaMove(Location goal) {
        if (vanillaTarget == null || vanillaTarget.distance(goal) > 1.5 || !mob.getPathfinder().hasPath()) {
            vanillaTarget = goal.clone();
            mob.getPathfinder().moveTo(goal, speedMul);
        }
    }

    /**
     * 每 tick：照路點走；前面是要花時間的邊就停下來處理；開路線上的門；卡住處理。
     * 移動是照格子路線直接推著走（每 tick 設定速度）：路點都是確認過站得住的格子，路點之間的直線也留了身體寬度，
     * 所以不會撞牆、不會走上家具；上樓梯靠跨步高度（0.6，跟玩家一樣）。原版尋路只在自己的圖找不到路時用。
     */
    private void followPath() {
        if (wayPath.isEmpty() || nav == null) {
            stuck.reset();
            return;
        }
        if (wayIdx >= wayPath.size()) {
            stopMoving();
            return;
        }
        int target = wayPath.get(wayIdx);
        int from = wayPath.get(Math.max(0, wayIdx - 1));
        Location tl = nav.loc(target);
        Location here = mob.getLocation();
        GridNav.Edge e = nav.nav.edge(from, target);
        // 要花時間的邊：走到旁邊停下來處理
        if (e != null && e.kind() != GridNav.Kind.WALK && e.kind() != GridNav.Kind.STAIR) {
            Location fl = nav.loc(from);
            if (handleSpecial(e, from, target, fl, here)) return;
        }
        openDoorsOnPath();
        double[] aim = doorAim(target, tl, here);
        double tdx = tl.getX() - here.getX(), tdz = tl.getZ() - here.getZ();
        if ((aim == null || aim[2] == 0) && Math.hypot(tdx, tdz) < 0.45 && Math.abs(tl.getY() - here.getY()) < 1.3) {
            wayIdx++;
            stuck.reset();
            return;
        }
        boolean last = wayIdx == wayPath.size() - 1;
        if (aim == null) {
            // 轉角前瞻：下一個路點是轉角時瞄準點拉長到 1.75 格，超過轉角的部分沿下一段走（提早轉）；
            // 但「從現在位置到瞄準點」的直線要留得下身體寬度才切，不然照舊（第二版靠這個不擦到桌角）
            int next = last ? -1 : wayPath.get(wayIdx + 1);
            GridNav.Edge en = next < 0 ? null : nav.nav.edge(target, next);
            boolean nearSpecial = (e != null && e.kind() != GridNav.Kind.WALK) || (en != null && en.kind() != GridNav.Kind.WALK);
            double ahead = PathFollow.lookahead(next < 0 ? 0 : turnDegrees(nav.loc(from), tl, nav.loc(next)), nearSpecial);
            Location nl = next < 0 || ahead <= 1.0 ? null : nav.loc(next);
            aim = lineAim(nav.loc(from), tl, here, ahead, nl);
            if (aim[2] == 1 && !chordClear(here, aim)) aim = lineAim(nav.loc(from), tl, here, 1.0, null);
            // 偏離中線、直走過去會擦到東西（桌角、木桶）：先拉回中線（瞄準自己在路線上的投影），再往前
            if (aim[2] == 0 && !chordClear(here, aim)) aim = lineAim(nav.loc(from), tl, here, 0.0, null);
            else if (aim[2] == 1 && Math.hypot(tdx, tdz) < 1.0) {
                // 正在切轉角、而且已經到轉角 1 格內：直接換下一段（不然會走過頭再折回轉角那一點）
                wayIdx++;
                return;
            }
        }
        double dx = aim[0] - here.getX(), dz = aim[1] - here.getZ(), dh = Math.hypot(dx, dz);
        if (dh < 0.2) {
            dx = tdx;
            dz = tdz;
            dh = Math.hypot(dx, dz);
        }
        steer(dx, dz, dh, PathFollow.arrivalScale(Math.hypot(tdx, tdz), last, navMode == Mode.CHASE));
        // 剛揮完拳（疲勞減速 90%）、抓完人時本來就幾乎不動，不算卡住
        if (m.tick < m.fatigueUntil + 5) stuck.reset();
        else if (stuck.update(m.tick, here.getX(), here.getY(), here.getZ(), lastStep)) onStuck();
    }

    /**
     * 過門要對準：門框只有 1 格寬，開著的門板還佔掉一側 3/16 格，鬼的身體 0.6 格寬，
     * 斜斜走過去會卡在門板或門框上。所以目標是門（或鬼正站在門裡）時，橫向對準門的「空的那一段」的中間；
     * 離門還遠、橫向又沒對準時，先走到門前一格對準。
     * 回傳 {x, z, 0=對準門 / 1=門前對準點 / 2=正在門裡往外走}；跟門沒關係回傳 null。
     */
    private double[] doorAim(int target, Location tl, Location here) {
        Block door = null;
        int tx = nav.nav.x(target), tz = nav.nav.z(target), ty = nav.nav.y(target);
        Block tb = m.world.getBlockAt(tx, ty, tz);
        Block hb = here.getBlock();
        boolean targetIsDoor = tb.getBlockData() instanceof Door;
        if (targetIsDoor) door = tb;
        else if (hb.getBlockData() instanceof Door) door = hb;
        if (door == null) return null;
        Door d = (Door) door.getBlockData();
        boolean alongX = d.getFacing().getModX() != 0; // 穿過門的方向
        double lo = 0, hi = 1;
        for (var box : door.getCollisionShape().getBoundingBoxes()) {
            double a = alongX ? box.getMinZ() : box.getMinX(), b = alongX ? box.getMaxZ() : box.getMaxX();
            if (b - a > 0.5) continue; // 不是側邊的門板（關著的門板會被開掉）
            if (a < 0.05) lo = Math.max(lo, b);
            else if (b > 0.95) hi = Math.min(hi, a);
        }
        double lat = (alongX ? door.getZ() : door.getX()) + (lo + hi) / 2;
        double hereLat = alongX ? here.getZ() : here.getX();
        if (!targetIsDoor) {
            // 正在門裡、往門後的路點走：先照穿過門的方向走出門框，橫向保持在空的那段中間
            double pass = alongX ? tl.getX() : tl.getZ();
            return alongX ? new double[]{pass, lat, 2} : new double[]{lat, pass, 2};
        }
        double doorPass = (alongX ? door.getX() : door.getZ()) + 0.5;
        double herePass = alongX ? here.getX() : here.getZ();
        double side = Math.signum(herePass - doorPass);
        if (side != 0 && Math.abs(hereLat - lat) > 0.12 && Math.abs(herePass - doorPass) > 0.7) {
            double prePass = doorPass + side * 1.0;
            int pre = alongX ? nav.nav.id((int) Math.floor(prePass), ty, door.getZ()) : nav.nav.id(door.getX(), ty, (int) Math.floor(prePass));
            if (pre >= 0) return alongX ? new double[]{prePass, lat, 1} : new double[]{lat, prePass, 1};
        }
        return alongX ? new double[]{doorPass, lat, 0} : new double[]{lat, doorPass, 0};
    }

    /**
     * 沿著路線走：路點之間的直線是確認過留了身體寬度的，但鬼到達路點時不一定剛好在格子中間（0.45 格內就算到），
     * 從偏掉的位置直接斜衝向下一個路點，身體會擦到桌角、木桶。所以瞄準「路線上、鬼的投影往前 1 格」的點，偏了會被拉回線上。
     */
    /**
     * 沿線走的瞄準點：自己在 a→b 上的投影往前 ahead 格。超過 b 而且有下一個路點 next 時，多出來的沿 b→next 走
     * （回傳的第三個值是 1，呼叫的人要確認切過去的直線沒擋到）；否則停在 b。
     */
    private double[] lineAim(Location a, Location b, Location here, double ahead, Location next) {
        double ax = a.getX(), az = a.getZ(), bx = b.getX(), bz = b.getZ();
        double len = Math.hypot(bx - ax, bz - az);
        if (len < 0.01) return new double[]{bx, bz, 0};
        double ux = (bx - ax) / len, uz = (bz - az) / len;
        double t = (here.getX() - ax) * ux + (here.getZ() - az) * uz;
        double off = Math.abs((here.getX() - ax) * uz - (here.getZ() - az) * ux);
        if (off > 1.2 || t > len) return new double[]{bx, bz, 0}; // 離線太遠（被推開了）：直接往路點走
        double s = Math.max(0, t) + ahead;
        if (s <= len || next == null || Math.abs(next.getY() - b.getY()) > 0.5) {
            double at = Math.min(len, s);
            return new double[]{ax + ux * at, az + uz * at, 0};
        }
        double nx = next.getX(), nz = next.getZ(), len2 = Math.hypot(nx - bx, nz - bz);
        if (len2 < 0.01) return new double[]{bx, bz, 0};
        double over = Math.min(len2, s - len);
        return new double[]{bx + (nx - bx) / len2 * over, bz + (nz - bz) / len2 * over, 1};
    }

    /** a→b 和 b→c 的轉角（度，0 是直走）。 */
    private static double turnDegrees(Location a, Location b, Location c) {
        double x1 = b.getX() - a.getX(), z1 = b.getZ() - a.getZ(), x2 = c.getX() - b.getX(), z2 = c.getZ() - b.getZ();
        double l1 = Math.hypot(x1, z1), l2 = Math.hypot(x2, z2);
        if (l1 < 0.01 || l2 < 0.01) return 0;
        double cos = Math.max(-1, Math.min(1, (x1 * x2 + z1 * z2) / (l1 * l2)));
        return Math.toDegrees(Math.acos(cos));
    }

    /** 從現在位置到瞄準點的直線（含身體寬度）都是普通可走格子。 */
    private boolean chordClear(Location here, double[] aim) {
        int y = here.getBlockY();
        return PathFollow.segmentClear(here.getX(), here.getZ(), aim[0], aim[1], 0.35, (x, z) -> {
            int id = nav.nav.id(x, y, z);
            return id >= 0 && !nav.special.contains(id);
        });
    }

    /** 往 (dx, dz) 方向推一步（速度跟著速度屬性走，所以疲勞、加成都有效）。 */
    private void steer(double dx, double dz, double dh) {
        steer(dx, dz, dh, 1.0);
    }

    /** scale：抵達減速的倍率（1 = 正常速度）。 */
    private void steer(double dx, double dz, double dh, double scale) {
        if (dh < 1e-4) {
            lastStep = 0;
            return;
        }
        var attr = mob.getAttribute(Attribute.MOVEMENT_SPEED);
        double perTick = (attr == null ? 0.45 : attr.getValue()) * speedMul * 0.57; // 0.4635 ≈ 每 tick 0.265 格（實測每秒 5.3 格）
        double step = Math.min(perTick * scale, dh);
        lastStep = step;
        mob.setVelocity(new Vector(dx / dh * step, mob.getVelocity().getY(), dz / dh * step));
        mob.setRotation((float) Math.toDegrees(Math.atan2(-dx, dz)), 0);
        if (STUCK_DEBUG) {
            Location h = mob.getLocation();
            trace[traceIdx++ % trace.length] = String.format("%d:%.2f,%.2f>%.2f,%.2f", m.tick % 1000, h.getX(), h.getZ(), dx / dh * step, dz / dh * step);
        }
    }

    private final String[] trace = new String[40];
    /** 這個 tick 叫鬼走多遠（卡住偵測用：減速時叫它走的距離本來就短）。 */
    private double lastStep;
    private int traceIdx;

    /** 細路線上離鬼最近那格的下一格。 */
    private int nextRawNode() {
        int best = -1;
        double bd = Double.MAX_VALUE;
        for (int i = 0; i < rawPath.size(); i++) {
            double d = nav.loc(rawPath.get(i)).distance(mob.getLocation());
            if (d < bd) {
                bd = d;
                best = i;
            }
        }
        return best >= 0 && best + 1 < rawPath.size() ? rawPath.get(best + 1) : -1;
    }

    /** 處理要花時間的邊。回傳 true 表示這個 tick 不要再走（正在處理或等待）。 */
    private boolean handleSpecial(GridNav.Edge e, int from, int target, Location fl, Location here) {
        switch (e.kind()) {
            case BARRICADE -> {
                if (!m.barricadeIndexBarricaded(e.ref())) return false;
                if (fl.distance(here) > 1.8) return false; // 還沒走到前面
                stopMovingKeepPath();
                m.ghostBreakBarricade(e.ref());
                return true;
            }
            case DOOR -> {
                Pos door = nav.doors.get(e.ref());
                if (!m.isDoorJammed(door)) return false;
                if (fl.distance(here) > 1.8) return false;
                stopMovingKeepPath();
                m.ghostBreakJam(door);
                ghostChangedDoorsNear(door, 1);
                return true;
            }
            case HOLE -> {
                if (m.isHoleWidened(e.ref())) return false;
                if (fl.distance(here) > 1.8) return false;
                stopMovingKeepPath();
                m.ghostWidenHole(e.ref());
                traces.ghostChanged(WorldTraces.Type.HOLE, e.ref(), 1, m.tick);
                return true;
            }
            case HATCH -> {
                // 地洞：還沒撬開就先撬（4 秒，很大聲）；撬開後花大約爬梯子的時間（1.5 秒），配梯子聲，傳到梯子另一端
                if (fl.distance(here) > 1.8) return false;
                stopMovingKeepPath();
                if (!m.isHatchPried(e.ref())) {
                    m.ghostPryHatch(e.ref());
                    return true;
                }
                climbTo = target;
                climbUntil = m.tick + 30;
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private void stopMovingKeepPath() {
        mob.getPathfinder().stopPathfinding();
        stuck.reset();
    }

    private void climbTick() {
        if (m.tick % 6 == 0) mob.getWorld().playSound(mob.getLocation(), Sound.BLOCK_LADDER_STEP, 1f, 0.8f);
        if (m.tick < climbUntil) return;
        climbUntil = -1;
        if (climbTo >= 0) mob.teleport(nav.loc(climbTo));
        climbTo = -1;
        wayIdx++;
    }

    /** 只開路線上接下來兩格會經過的門（不是腳邊所有的門），2 格內才開。 */
    private void openDoorsOnPath() {
        int start = -1;
        double bd = Double.MAX_VALUE;
        for (int i = 0; i < rawPath.size(); i++) {
            double d = nav.loc(rawPath.get(i)).distance(mob.getLocation());
            if (d < bd) {
                bd = d;
                start = i;
            }
        }
        if (start < 0) return;
        for (int i = start; i <= Math.min(rawPath.size() - 1, start + 2); i++) {
            int id = rawPath.get(i);
            Block bl = m.world.getBlockAt(nav.nav.x(id), nav.nav.y(id), nav.nav.z(id));
            if (!(bl.getBlockData() instanceof Door d) || d.isOpen()) continue;
            if (nav.loc(id).distance(mob.getLocation()) > 2.2) continue;
            Pos lower = new Pos(bl.getX(), bl.getY(), bl.getZ());
            if (m.isDoorJammed(lower) || m.isDoorLocked(lower)) continue;
            m.setDoorOpenPublic(lower, true);
            ghostChangedDoorsNear(lower, 1);
            m.world.playSound(bl.getLocation(), Sound.BLOCK_WOODEN_DOOR_OPEN, 1f, 0.9f);
        }
    }

    /**
     * 卡住了（stuck-time 秒內幾乎沒移動）：第 1 次重新找路，第 2 次改走下一個細的格子，
     * 第 3 次把鬼放到路線上附近的格子（只傳到路線上，絕不穿牆），並把目標列入 30 秒黑名單。
     */
    private void onStuck() {
        stuckEvents++;
        if (m.tick - lastStuckTick > 200) stuckCount = 0; // 10 秒內沒再卡住就重新算
        lastStuckTick = m.tick;
        stuckCount++;
        if (STUCK_DEBUG) stuckDump();
        if (stuckCount == 1) {
            rawGoal = -1; // 下次 navigate 重新找路
            wayPath = List.of();
        } else if (stuckCount == 2) {
            // 改走細的格子路線（不拉直），從離鬼最近那格開始
            int next = nextRawNode();
            int i = next < 0 ? -1 : rawPath.indexOf(next);
            if (i >= 0) {
                wayPath = rawPath.subList(i, rawPath.size());
                wayIdx = 0;
            }
        } else {
            int next = nextRawNode();
            if (next >= 0 && nav.loc(next).distance(mob.getLocation()) < 3) {
                mob.teleport(nav.loc(next));
                teleports++;
                m.plugin.getLogger().info("[AI 鬼] 卡住，移到路線上的下一格 " + nav.loc(next).toVector());
            }
            if (patrolTarget != null) badTargets.put(patrolTarget, m.tick + 600L);
            patrolTarget = null;
            stuckCount = 0;
            rawGoal = -1;
            wayPath = List.of();
        }
    }

    static boolean STUCK_DEBUG = Boolean.getBoolean("ghosthunt.stuckdebug");

    private void stuckDump() {
        Location h = mob.getLocation();
        StringBuilder sb = new StringBuilder("[AI 鬼 STUCK] #" + stuckCount + " goal=" + (goal == null ? "-" : goal.kind())
                + String.format(" at=%.2f,%.2f,%.2f", h.getX(), h.getY(), h.getZ())
                + " vel=" + String.format("%.2f,%.2f,%.2f", mob.getVelocity().getX(), mob.getVelocity().getY(), mob.getVelocity().getZ())
                + " onGround=" + mob.isOnGround() + " vanillaPath=" + mob.getPathfinder().hasPath()
                + " wayIdx=" + wayIdx + "/" + wayPath.size());
        if (nav != null && !wayPath.isEmpty() && wayIdx < wayPath.size()) {
            int t = wayPath.get(wayIdx), f = wayPath.get(Math.max(0, wayIdx - 1));
            GridNav.Edge e = nav.nav.edge(f, t);
            sb.append(" from=").append(nav.nav.x(f)).append(',').append(nav.nav.y(f)).append(',').append(nav.nav.z(f))
                    .append(" to=").append(nav.nav.x(t)).append(',').append(nav.nav.y(t)).append(',').append(nav.nav.z(t))
                    .append(" edge=").append(e == null ? "none" : e.kind());
        }
        for (org.bukkit.entity.Entity en : mob.getNearbyEntities(1.5, 1.5, 1.5))
            sb.append(" near=").append(en.getType()).append(String.format("@%.2f,%.2f", en.getLocation().getX(), en.getLocation().getZ()));
        sb.append("\n  trace:");
        for (int i = 0; i < trace.length; i += 2) {
            String t = trace[(traceIdx + i) % trace.length];
            if (t != null) sb.append(' ').append(t);
        }
        int bx = h.getBlockX(), by = h.getBlockY(), bz = h.getBlockZ();
        sb.append("\n  blocks y=").append(by).append(" (x-2..x+2 rows z-2..z+2):");
        for (int z = bz - 2; z <= bz + 2; z++) {
            sb.append("\n   ");
            for (int x = bx - 2; x <= bx + 2; x++) {
                Block b0 = m.world.getBlockAt(x, by, z), b1 = m.world.getBlockAt(x, by + 1, z);
                String s = (b0.isPassable() ? "." : b0.getType().name().substring(0, Math.min(6, b0.getType().name().length())))
                        + "/" + (b1.isPassable() ? "." : b1.getType().name().substring(0, Math.min(6, b1.getType().name().length())));
                if (b0.getBlockData() instanceof Door d) s = "DOOR" + (d.isOpen() ? "o" : "c") + d.getFacing().name().charAt(0) + d.getHinge().name().charAt(0);
                boolean node = nav != null && nav.nav.id(x, by, z) >= 0;
                sb.append(String.format("%-15s", (node ? "*" : " ") + s));
            }
        }
        m.plugin.getLogger().info(sb.toString());
    }

    // ---- 腳步聲 ----

    /** 鬼的腳步聲（ai-ghost.footsteps，預設關：靠逃生者的心跳判斷距離）。 */
    private Boolean footstepSounds;

    private void footsteps() {
        if (footstepSounds == null) footstepSounds = m.plugin.getConfig().getBoolean("ai-ghost.footsteps", false);
        if (!footstepSounds || m.tick % 7 != 0) return;
        Location here = mob.getLocation();
        if (lastStepLoc != null && lastStepLoc.getWorld().equals(here.getWorld()) && lastStepLoc.distance(here) > 0.6) {
            Block below = here.clone().add(0, -0.2, 0).getBlock();
            Sound step = below.getBlockData().getSoundGroup().getStepSound();
            boolean chasing = goal != null && (goal.kind() == Kind.CHASE || goal.kind() == Kind.ATTACK);
            mob.getWorld().playSound(here, step, chasing ? 1.0f : 0.55f, (chasing ? 0.9f : 0.75f) * persona.stepPitch());
        }
        lastStepLoc = here;
    }

    // ---- 工具 ----

    private Location jitter(Location at, double r) {
        if (at == null) return null;
        return at.clone().add((m.rnd.nextDouble() * 2 - 1) * r, 0, (m.rnd.nextDouble() * 2 - 1) * r);
    }

    /** 對齊到鬼走得到的格子（尋路圖的節點，同一層）；暗格裡面不是節點，所以不會對到暗格裡。 */
    private Location snapWalkable(Location at, Location fallback) {
        if (at == null) return fallback;
        if (nav != null) {
            for (double r : new double[]{2, 4, 7}) {
                int id = nav.nearestMain(at, r);
                if (id >= 0) return nav.loc(id);
            }
        }
        return fallback != null ? fallback : at;
    }

    private static Point pt(Location l) {
        return l == null ? null : new Point(l.getX(), l.getY(), l.getZ());
    }

    private Location toLoc(Point p) {
        return new Location(mob.getWorld(), p.x(), p.y(), p.z());
    }

    private void contactStat() {
        if (firstContact < 0) firstContact = m.tick;
        if (lastContactTick >= 0) longestQuiet = Math.max(longestQuiet, m.tick - lastContactTick);
        lastContactTick = m.tick;
    }

    /** 壓迫感的統計：多久沒有接觸（看到、聽到任何人）；放鬼時開始算。 */
    private long lastContactTick = -1, longestQuiet;

    // ---- 沒有鬼房的開局（第三版之後：鬼出生在離所有人最遠的地方） ----

    /** 開局前先建好尋路圖（要算出生點）。 */
    void prepareNav() {
        if (nav == null) buildNav();
    }

    /** 逃生者的隨機出生點：尋路圖上的一般格子，離監獄 10 格以上、彼此至少 minEach 格（找不到就放寬）。 */
    List<Location> randomSpawns(int n, double minEach) {
        List<Integer> cells = new ArrayList<>();
        for (int i = 0; i < nav.nav.size(); i++) {
            if (!nav.isMain(i)) continue;
            Location l = nav.loc(i);
            boolean nearJail = false;
            for (Pos cage : m.b.jailCages.values()) if (m.loc(cage).getWorld().equals(l.getWorld()) && m.loc(cage).distance(l) < 10) nearJail = true;
            if (!nearJail) cells.add(i);
        }
        java.util.Collections.shuffle(cells, m.rnd);
        for (double min = minEach; min >= 0; min -= 3) {
            List<Location> out = new ArrayList<>();
            for (int c0 : cells) {
                Location l = nav.loc(c0);
                boolean ok = true;
                for (Location o : out) if (o.distance(l) < min) ok = false;
                if (!ok) continue;
                out.add(l);
                if (out.size() == n) return out;
            }
        }
        List<Location> out = new ArrayList<>();
        for (int i = 0; i < Math.min(n, cells.size()); i++) out.add(nav.loc(cells.get(i)));
        return out;
    }

    /** 鬼的出生點：離「最近的逃生者」走路最遠的格子（多起點距離場）；沒有逃生者回傳 null。 */
    Location farthestFrom(List<Location> people) {
        List<Integer> src = new ArrayList<>();
        for (Location l : people) {
            int n = nav.nearest(l, 3);
            if (n >= 0) src.add(n);
        }
        if (src.isEmpty()) return null;
        DistanceField f = DistanceField.compute(nav.nav, e -> e.kind() == GridNav.Kind.WALK || e.kind() == GridNav.Kind.STAIR
                || e.kind() == GridNav.Kind.DOOR ? 0 : Double.POSITIVE_INFINITY, 1.0, src.stream().mapToInt(Integer::intValue).toArray());
        int best = -1;
        double bd = -1;
        for (int i = 0; i < nav.nav.size(); i++) {
            if (!nav.isMain(i)) continue;
            double d = f.seconds(i);
            if (!Double.isInfinite(d) && d > bd) {
                bd = d;
                best = i;
            }
        }
        if (best < 0) return null;
        m.plugin.getLogger().info("[AI 鬼] 出生點：離最近的逃生者走路 " + Math.round(bd) + " 格");
        return nav.loc(best);
    }

    // ---- 除錯、統計 ----

    private String topScores() {
        StringBuilder sb = new StringBuilder();
        List<GhostBrain.Scored> sc = brain.lastScores();
        for (int i = 0; i < Math.min(3, sc.size()); i++)
            sb.append(i == 0 ? "｜" : " ").append(sc.get(i).kind().name().toLowerCase()).append(' ').append(String.format("%.1f", sc.get(i).score()));
        return sb.toString();
    }

    String debug() {
        Location here = mob.getLocation();
        return (goal == null ? "（還沒想）" : goal.kind().name())
                + "｜位置 " + here.getBlockX() + "," + here.getBlockY() + "," + here.getBlockZ()
                + "｜路點 " + (wayPath.isEmpty() ? "-" : wayIdx + "/" + wayPath.size())
                + "｜卡住 " + stuckCount + "｜速度 " + speedMul
                + (chaseId != null ? "｜追 " + chaseId.toString().substring(0, 4) : "")
                + (interceptAt != null ? "｜堵" : "") + (ambushAt != null ? "｜埋伏" + (ambushArrive >= 0 ? "中" : "") : "")
                + "｜" + persona.label() + (utility ? topScores() : "")
                + "｜壓力 " + Math.round(director.pressure())
                + "｜粒子 " + (trackers.values().stream().mapToInt(ParticleTracker::alive).sum() + (sensedTracker == null ? 0 : sensedTracker.alive()));
    }

    private void debugDraw() {
        if (debugViewers.isEmpty() || nav == null) return;
        for (UUID id : debugViewers) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            p.sendActionBar(Component.text("[AI 鬼] " + debug(), NamedTextColor.LIGHT_PURPLE));
            for (int i = Math.max(0, wayIdx - 1); i < wayPath.size(); i++)
                p.spawnParticle(Particle.END_ROD, nav.loc(wayPath.get(i)).add(0, 0.3, 0), 1, 0, 0, 0, 0);
            dot(p, lastSeen, Color.RED);
            dot(p, noise, Color.YELLOW);
            dot(p, sensed, Color.PURPLE);
            dot(p, watchSpot, Color.AQUA);
            dot(p, patrolTarget, Color.LIME);
            // 粒子：每個逃生者一種顏色、每格一點、最多 100 點
            Color[] cols = {Color.ORANGE, Color.FUCHSIA, Color.TEAL, Color.WHITE};
            int ci = 0, drawn = 0;
            List<ParticleTracker> all = new ArrayList<>(trackers.values());
            if (sensedTracker != null) all.add(sensedTracker);
            for (ParticleTracker t : all) {
                java.util.Set<Integer> cells = new java.util.HashSet<>();
                for (int v : t.positions()) cells.add(v);
                Color col = cols[ci++ % cols.length];
                for (int v : cells) {
                    if (drawn++ >= 100) break;
                    p.spawnParticle(Particle.DUST, nav.loc(v).add(0, 0.2, 0), 1, 0, 0, 0, 0, new Particle.DustOptions(col, 0.8f));
                }
            }
        }
    }

    private static void dot(Player p, Location l, Color col) {
        if (l == null || !l.getWorld().equals(p.getWorld())) return;
        p.spawnParticle(Particle.DUST, l.clone().add(0, 1.2, 0), 6, 0.15, 0.3, 0.15, 0, new Particle.DustOptions(col, 1.4f));
    }

    /** 每局記錄（寫進 stats.log）。 */
    void statsInto(Map<String, Object> stats) {
        stats.put("aiDifficulty", c.difficulty());
        stats.put("aiCatches", catches);
        stats.put("aiMisses", misses);
        stats.put("aiChases", chases);
        stats.put("aiStuck", stuckEvents);
        stats.put("aiTeleports", teleports);
        stats.put("aiSenses", senses);
        stats.put("aiCatchesWithin30sOfSense", catchesAfterSense);
        stats.put("aiFirstContactSec", firstContact < 0 ? -1 : firstContact / 20);
        long quietNow = lastContactTick < 0 ? 0 : m.tick - lastContactTick;
        stats.put("aiLongestQuietSec", Math.max(longestQuiet, quietNow) / 20);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Kind, Integer> e : goalTicks.entrySet()) sb.append(sb.isEmpty() ? "" : ",").append(e.getKey()).append(':').append(e.getValue() / 20);
        stats.put("aiGoalSeconds", sb.toString());
        stats.put("aiLosses", losses);
        stats.put("aiTraces", tracesSeen);
        stats.put("aiReacquire30s", reacquire30);
        stats.put("aiTickMsAvg", tickTime.avgMs());
        stats.put("aiTickMsMax", tickTime.maxMs());
        stats.put("aiPathMsAvg", pathTime.avgMs());
        stats.put("aiPathMsMax", pathTime.maxMs());
        stats.put("aiFieldMsAvg", fieldTime.avgMs());
        stats.put("aiNavBuildMs", navBuildMs);
        stats.put("aiSneaks", sneaks);
        stats.put("aiCutWatchSpots", cutWatchSpots);
        stats.put("aiIntercepts", intercepts + "/" + interceptPredicted + "/" + interceptTries);
        stats.put("aiAmbushes", ambushes);
        stats.put("aiReaderGuards", readerGuards);
        stats.put("aiSoundsHeard", soundsHeard);
        stats.put("aiSoundsBlocked", soundsBlocked);
        stats.put("aiPeeks", peeks);
        stats.put("aiPersona", persona.id() + "(" + personaChosenBy + ")");
        stats.put("aiBrain", utility ? "utility" : "rules");
        StringBuilder lt = new StringBuilder();
        for (Map.Entry<AiMemory.Trick, Integer> e : learnedThisMatch.entrySet()) lt.append(lt.isEmpty() ? "" : ",").append(e.getKey()).append(':').append(e.getValue());
        stats.put("aiLearned", lt.isEmpty() ? "-" : lt.toString());
        stats.put("aiMemoryMatches", memory.matches());
    }
}
