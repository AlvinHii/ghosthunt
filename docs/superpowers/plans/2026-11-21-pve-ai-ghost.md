# PvE AI 鬼 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新增 PvE 模式：2～3 個真人逃生者對抗 1 隻 AI 鬼（原版怪物＋Paper 尋路＋插件決定行為），配一張 2 層的小地圖。

**Architecture:** 對局的「鬼」從 `Player` 改成 `LivingEntity`（真人是 Player、AI 是 Mob）；鬼的能力抽成不依賴點擊的方法，真人點擊和 AI 都呼叫同一套。AI 分兩層：`GhostBrain`（純邏輯、不碰 Bukkit，JUnit 測）決定目標，`AiGhost`（Paper）負責感知、尋路、開門、揮拳、卡住處理並執行目標。小地圖沿用 `IrregularGenerator`，只換 config 的 `pve:` 外型與數量。

**Tech Stack:** Java 25（JDK 26 編譯）、Paper API 26.3、Gradle、JUnit 5（新增，只測純邏輯）。

**Spec:** `docs/superpowers/specs/2026-11-21-pve-ai-ghost-design.md`

## Global Constraints

- 回覆、遊戲內文字、註解、文件一律繁體中文。
- 不改真人鬼的玩法與 PvP 模式；`map.generator: grid` 舊地圖照常能用。
- AI 鬼不能做到真人鬼做不到的事：不穿牆、鑽不過 1.5 格高的蹲伏小洞、揮拳後 1.5 秒減速 90%（`ghost.attack-fatigue-slow`）且疲勞中抓不到人。
- 已確定的規則不動：大門 2 扇不同樓層、門禁卡 2 張不同樓層、保全室 2 間不同樓層、加速不疊加、鬼看不到霧、鬼自帶夜視、沒有勝負。
- 數值全部放 `src/main/resources/config.yml`（新區塊 `pve:`、`ai-ghost:`）；test-server 的 config 要同步。
- 每個 Task 結尾 `./gradlew build` 必須通過，並 commit（訊息結尾加 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`）。
- 整局驗證用臨時伺服器（scratchpad 裡的 `srv`，port 25599，用 stdin 餵指令），不要動使用者正在開的 test-server。

## Review Focus

1. **AI 鬼卡在門口／樓梯／家具角落**：3 秒幾乎沒移動就要重新找路，連續卡住換目標，絕不穿牆 —— Task 5 的 `StuckTracker` 單元測試＋臨時伺服器跑 3 分鐘看 log 的「卡住」次數。
2. **真人鬼模式退化**：重構後 `/gh start <玩家>` 的抓人、揮拳減速、拆障礙物、撞門、撬地洞照舊 —— Task 2 的 selftest＋臨時伺服器 PvP 開局不報錯。
3. **只有 1 個玩家開 PvE**（自己測試）：要能開局，不能因為人數少於 2 直接結束或除以零 —— Task 6 驗證步驟。
4. **AI 鬼在逃生者全部被抓／逃出後**：局要正常結束、AI 鬼實體要被移除（不留在世界裡）—— Task 6 的結束清理檢查。
5. **煙霧彈、手電筒對 AI 鬼**：煙霧彈讓 AI 鬼 3 秒「看不到」並打斷正在做的事 —— Task 5 的 `GhostBrain` 測試（blinded 時不追人）。

---

### Task 1: JUnit ＋ `GhostBrain` 純邏輯

**Files:**
- Modify: `build.gradle.kts`（加 `testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")`、`testRuntimeOnly("org.junit.platform:junit-platform-launcher")`、`testImplementation("io.papermc.paper:paper-api:26.3.build.+")`、`tasks.test { useJUnitPlatform() }`）
- Create: `src/main/java/com/ghosthunt/game/ai/GhostBrain.java`
- Test: `src/test/java/com/ghosthunt/game/ai/GhostBrainTest.java`

**Interfaces:**
- Produces:
  - `record GhostBrain.Situation(long tick, boolean blinded, Seen seen, Point lastSeen, long lastSeenTick, Point noise, long noiseTick, Point sensed, boolean cardRevealOpen, boolean cardMoveAvailable, boolean powerOn, boolean cutPowerUsed, List<Point> sabotageableGates, boolean someoneJailed, Point jail, Point here)`
  - `record GhostBrain.Seen(UUID id, Point at, double distance)`（看得到的最近逃生者；沒有是 null）
  - `record GhostBrain.Point(double x, double y, double z)`
  - `enum GhostBrain.Kind { CHASE, ATTACK, SEARCH_LAST_SEEN, INVESTIGATE_NOISE, GO_SENSED, MOVE_CARD, CUT_POWER, SABOTAGE_GATE, GUARD_JAIL, PATROL }`
  - `record GhostBrain.Goal(Kind kind, Point target, UUID victim)`
  - `GhostBrain(GhostBrain.Config cfg, java.util.random.RandomGenerator rnd)`；`Goal decide(Situation s)`
  - `record GhostBrain.Config(double attackRange, int searchTicks, int noiseMemoryTicks, double guardJailChance)`（預設 2.5、200、200、0.5）

- [ ] **Step 1: 寫失敗的測試** `GhostBrainTest`：
  - `seenWithinRange_attacks`：seen 距離 2.0 → `Kind.ATTACK`、victim = 該 id。
  - `seenFar_chases`：seen 距離 10 → `CHASE`、target = seen.at。
  - `blinded_ignoresSeen`：blinded = true、seen 距離 2 → 不是 ATTACK/CHASE。
  - `lostTarget_searchesLastSeen_withinSearchTicks`：seen = null、lastSeenTick = tick − 100 → `SEARCH_LAST_SEEN`；tick − 300 → 不是。
  - `noise_isInvestigated`：noiseTick = tick − 50 → `INVESTIGATE_NOISE`，target = noise。
  - `sensed_isFollowed_whenNothingElse`：只有 sensed → `GO_SENSED`。
  - `cardMove_beatsPatrol`：cardRevealOpen 且 cardMoveAvailable → `MOVE_CARD`。
  - `gateSabotage_beatsCutPower`：有可弄壞的大門且電源開著未關過 → `SABOTAGE_GATE`。
  - `cutPower_whenPowerOnAndUnused`：→ `CUT_POWER`。
  - `nothing_patrols`：全空 → `PATROL`。
  - 優先序（spec 第 4 節）：ATTACK > CHASE > SEARCH_LAST_SEEN > INVESTIGATE_NOISE > GO_SENSED > MOVE_CARD > SABOTAGE_GATE > CUT_POWER > GUARD_JAIL（用 rnd < guardJailChance）> PATROL。
- [ ] **Step 2: 跑測試確認失敗**：`./gradlew test` → 編譯失敗（GhostBrain 不存在）。
- [ ] **Step 3: 實作 `GhostBrain`**（不 import 任何 org.bukkit 類別）。
- [ ] **Step 4: 跑測試確認通過**：`./gradlew test` → BUILD SUCCESSFUL，11 個測試通過。
- [ ] **Step 5: Commit**。

### Task 2: 鬼從 `Player` 改成 `LivingEntity`（行為不變）

**Files:**
- Modify: `src/main/java/com/ghosthunt/game/Match.java`、`Objectives.java`、`Nightmare.java`、`ItemUse.java`、`Interactions.java`

**Interfaces:**
- Produces（Match）：
  - `LivingEntity ghostEntity()`（真人回傳 Player，AI 回傳 Mob；不在線回傳 null）
  - `Player ghostPlayer()`（AI 時回傳 null）
  - `boolean isGhostId(UUID id)`；保留 `boolean isGhost(Player p)`
  - `void ghostMessage(Component c)`：只有真人鬼才送 action bar
- Produces（Interactions）：`Act startAct(Entity who, String key, String label, int neededTicks, boolean coop, boolean noisy, Location at, Runnable onDone)`；原本 `Player` 版改成呼叫它。內部用 `Bukkit.getEntity(uuid)` 找人，留下條件改成 `m.isGhostId(id) || m.isFreeSurvivor(p)`；action bar 只送給 Player。
- 規則：原本 `Player g = ghost()` 的地方改成 `LivingEntity g = ghostEntity()`；`sendActionBar`／`showTitle`／`spawnParticle`（只給鬼看的）只在 `g instanceof Player` 時做；速度屬性（`Attribute.MOVEMENT_SPEED`）、夜視、位置在 LivingEntity 上都能用。`tick()` 開頭 `ghostEntity() == null` 或 `!isValid()` 才結束（「鬼離開了」）。

- [ ] **Step 1: 改型別與呼叫點**（上面列的檔案，`grep -n "ghost()" game/*.java` 全部處理掉，`ghost()` 方法刪除）。
- [ ] **Step 2: 編譯**：`./gradlew build` → 成功。
- [ ] **Step 3: 臨時伺服器驗證**：餵 `ghosthunt build 101`、`ghosthunt selftest` → log 有「[自我檢查] 完成，沒有錯誤」，沒有 Exception。
- [ ] **Step 4: Commit**。

### Task 3: 鬼的能力抽成 AI 也能呼叫的方法

**Files:**
- Modify: `Match.java`、`Objectives.java`

**Interfaces:**
- Consumes: Task 2 的 `startAct(Entity …)`、`ghostEntity()`。
- Produces（全部回傳 `boolean`：true = 開始做了／做完了；規則、時間、聲音與真人點擊完全相同，真人點擊路徑改成呼叫這些方法）：
  - Objectives：`boolean ghostCutPower(LivingEntity g)`（站在拉桿 2.5 格內才行）、`boolean ghostMoveCard(LivingEntity g)`、`boolean ghostSabotage(LivingEntity g, GateState gs)`
  - Objectives：`Location leverLocation()`、`List<GateState> runningUnsabotagedGates()`、`boolean cardRevealOpen()`、`boolean cardMoveAvailable()`、`Location jailNearestTo(Location l)`
  - Match：`boolean ghostBreakBarricade(int spotIndex)`、`boolean ghostBreakJam(Pos doorLower)`、`boolean ghostPryHatch(int hatchIndex)`、`boolean ghostWidenHole(int holeIndex)`
  - Match：`int barricadeAt(Pos p)`（該格是擋住的門口就回傳編號，否則 −1）、`Pos jammedDoorAt(Pos p)`、`int hatchIndexAt(Pos p)`、`int holeIndexAt(Pos p)`（給 AI 判斷前面擋住的是什麼）
  - Match：`void ghostSwing()`（= 原 `onGhostSwing`）、`void ghostHit(Player victim)`（= 原 `onGhostHit`，AI 也呼叫）

- [ ] **Step 1: 抽方法並讓 GameListener／原本的點擊處理改呼叫它們**。
- [ ] **Step 2: 編譯**：`./gradlew build` → 成功。
- [ ] **Step 3: 臨時伺服器**：build ＋ selftest 無錯。
- [ ] **Step 4: Commit**。

### Task 4: PvE 小地圖

**Files:**
- Modify: `src/main/resources/config.yml`（新區塊）、`building/BuildingSettings.java`、`GhostHuntPlugin.java`、`building/IrregularGenerator.java`

**Interfaces:**
- Produces：`BuildingSettings BuildingSettings.from(FileConfiguration c, boolean pve)`（pve = true 時用 `pve.shape`、`pve.floors`、`pve.hideouts-per-floor`、`pve.crawl-tunnels-per-floor`、`pve.tunnels`、`pve.barrel-area`，其他照一般設定）；`BuildingSettings` 新欄位 `int barrelArea`（一般大樓 140，木桶「每幾格地板一個」）、`boolean pve`。`Building` 新欄位 `public boolean pve`。`/gh build pve [種子]`。
- config 值（spec 第 2 節）：

```yaml
pve:
  shape:
    - ".####."
    - "######"
    - "#####."
    - "######"
    - ".###.."
  floors: 2
  hideouts-per-floor: 2
  crawl-tunnels-per-floor: 2
  tunnels: 2
  barrel-area: 90
```

- [ ] **Step 1: 實作 settings、指令、生成器讀 `barrelArea`**（取代寫死的 140）。
- [ ] **Step 2: 編譯**。
- [ ] **Step 3: 臨時伺服器**：`ghosthunt build pve 1`～`pve 8` 各一次＋selftest → 8 棟都「產生完成」、自我檢查無錯、`/gh info` 顯示 2 層；有任何種子 30 次都失敗就調外型（不放寬規則）。
- [ ] **Step 4: Commit**。

### Task 5: `AiGhost`（感知、尋路、揮拳、卡住處理）

**Files:**
- Create: `src/main/java/com/ghosthunt/game/ai/AiGhost.java`、`src/main/java/com/ghosthunt/game/ai/StuckTracker.java`
- Modify: `config.yml`（`ai-ghost:` 區塊）、`game/GameSettings.java`
- Test: `src/test/java/com/ghosthunt/game/ai/StuckTrackerTest.java`、`GhostBrainTest.java`（補 Review Focus 5）

**Interfaces:**
- Consumes: Task 1 `GhostBrain`；Task 2、3 的 Match／Objectives 方法。
- Produces：
  - `StuckTracker(int windowTicks, double minMove)`；`boolean update(long tick, double x, double y, double z)`（窗內移動 < minMove 回傳 true）；`void reset()`
  - `AiGhost(Match m, GameSettings s)`；`Mob spawn(Location at)`；`void tick()`（Match 每 tick 呼叫）；`void remove()`；`void blind(int ticks)`（煙霧彈呼叫）；`void hearNoise(Location at)`（`Match.noise` 呼叫）
- config（spec 第 3～5 節）：

```yaml
ai-ghost:
  mob: HUSK
  sight-range: 24
  sight-range-dark: 14
  think-every: 5            # 每幾 tick 想一次（大腦）
  reaction: 0.3             # 進入攻擊距離後多久才揮拳（秒）
  attack-range: 2.5
  search-time: 10
  noise-error: 3            # 聽到聲音的誤差（格）
  sense-interval: 60        # 感應最近逃生者的間隔（秒）
  sense-error: 8
  guard-jail-chance: 0.5
  check-hideout-chance: 0.4
  follow-tunnel-chance: 0.5
  stuck-time: 3
```

- 行為要點（spec 第 3～5 節，實作照這些）：生成時 `setAI(true)` 但清空 goal selector／target selector（`Bukkit.getMobGoals().removeAllGoals(mob)`）、`setSilent(false)`、`setPersistent(false)`、`setRemoveWhenFarAway(false)`、`setInvulnerable(true)`、不燒（`setShouldBurnInDay(false)`）、裝備深色皮甲＋凋零骷髏頭、`customName("鬼")`；速度由 Match 照真人鬼同一套屬性處理。看得到 = 同層、距離 ≤ sight-range（停電時 sight-range-dark）、`mob.hasLineOfSight(player)`。路線用 `mob.getPathfinder().moveTo(loc, 1.0)`，每 think-every 更新；前方 1.5 格內是關著的雙開門就開門；路線被擋（`barricadeAt`／`jammedDoorAt`／`hatchIndexAt`／`holeIndexAt` 有值）且 `getPathfinder().findPath` 找不到別條路時才呼叫 `ghostBreakBarricade`／`ghostBreakJam`／`ghostPryHatch`／`ghostWidenHole`。ATTACK 目標維持 reaction 秒仍在範圍且看得到才揮拳：呼叫 `ghostSwing()`，若仍在 attack-range 再 `ghostHit(victim)`。感應每 sense-interval 秒取最近的自由逃生者位置＋誤差，並讓那名玩家播心跳加快音效。StuckTracker 回報卡住：先重新 moveTo，連續 3 次換一個隨機走得到格子（`b.walkTiles`）當目標，連續 6 次把鬼傳到離它最近的走得到格子。巡邏：當層 `b.rooms` 隨機挑最近 3 間沒去過的房間中心。SEARCH_LAST_SEEN 時，最後看到的位置 6 格內有暗格入口（`b.rooms` 裡 hideout 的入口洞）就以 check-hideout-chance 去打大入口查看；看到逃生者走進活板門那格時，以 follow-tunnel-chance 呼叫 `ghostPryHatch` 追下去，否則改走去該通道另一端。`ItemUse` 的煙霧彈打到 AI 鬼時呼叫 `aiGhost.blind(s.smokeBlind)` 並取消它正在做的事（`acts.cancel(ghostId, …)`）。

- [ ] **Step 1: 寫失敗的測試** `StuckTrackerTest`：`stillForWindow_isStuck`（60 tick 內移動 0.2 格、minMove 1.0 → true）、`moving_isNotStuck`（每 tick 前進 0.2 → false）、`reset_clears`；`GhostBrainTest.smokeBlind_stopsChase` 已在 Task 1 涵蓋則略過。
- [ ] **Step 2: 跑測試確認失敗**：`./gradlew test` → StuckTracker 不存在。
- [ ] **Step 3: 實作 `StuckTracker`、`AiGhost`、settings**。
- [ ] **Step 4: 跑測試確認通過**：`./gradlew test` → 全部通過。
- [ ] **Step 5: Commit**。

### Task 6: `/gh start ai`、開局說明、selftest、文件

**Files:**
- Modify: `GhostHuntPlugin.java`、`game/Match.java`、`game/Objectives.java`（selftest 報告）、`CLAUDE.md`、`docs/平衡模擬.md` 不動
- Modify: `test-server/plugins/GhostHunt/config.yml`（跟 src 不同時先備份成 `config.old.yml`）

**Interfaces:**
- Consumes: Task 5 `AiGhost`。
- Produces：`static Match Match.pve(Plugin, Building, World, GameSettings, List<Player> survivors, Runnable onEnd)`；`/gh start ai`（所有線上玩家都是逃生者；0 個玩家時拒絕；1 個玩家可以開）；`selftest` 在大樓上多做一項「AI 鬼尋路」：生成 AI 鬼、對電力室、兩扇大門讀卡機、兩台終端機、各樓層監獄各呼叫一次 `getPathfinder().findPath`，回報幾個找得到路，然後移除鬼；stats.log 多 `mode=pve|pvp`；開局說明（AI 局給逃生者看的版本，說明鬼是電腦、會每 60 秒感應一次）；結束時 `aiGhost.remove()`。

- [ ] **Step 1: 實作**。
- [ ] **Step 2: 編譯＋測試**：`./gradlew build test`。
- [ ] **Step 3: 臨時伺服器**：`ghosthunt build pve 1`、`ghosthunt selftest` → AI 尋路項目「找得到路 n/n」且 n 至少 7；log 無 Exception。
- [ ] **Step 4: 更新 CLAUDE.md**（指令列表加 `build pve`、`start ai`；程式結構加 `game/ai/`；已確定的設計決定加 PvE 四條）＋同步 test-server config＋`./gradlew deploy`。
- [ ] **Step 5: Commit**。
