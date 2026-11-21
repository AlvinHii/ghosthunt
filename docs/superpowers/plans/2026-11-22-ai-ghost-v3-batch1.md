# AI 鬼第三版第 1 批 實作計畫

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal：** 讓 AI 鬼追丟以後會「推理」：用距離場、可見性表、粒子濾波猜人在哪，會注意痕跡、照證據查暗格；轉角更順；並用最小模擬器量出比第二版好。

**Architecture：** 新的演算法全部寫成 `game/ai/` 裡的純邏輯類別（不碰 Bukkit，JUnit 測），`AiGhost` 只負責把世界狀態餵進去、把結果拿來走路。走路照舊用第二版的 A*＋沿線走（距離場只用來推理），所以卡住 0 次不會被破壞。最小模擬器放在 `src/test`，只驅動純邏輯類別。

**Tech Stack：** Java（JDK 26 編譯、release 25）、Paper API 26.3、JUnit 5、Gradle Kotlin DSL。

**Spec：** `docs/AI鬼第三版改進建議.md`（第 1、2、6、7、9、10、23 項＋「逃生者的圖」）；現況說明在 `docs/AI鬼邏輯.md`。

## Global Constraints

- 回覆、遊戲內文字、註解、文件一律繁體中文。
- AI 只能用看得到、聽得到的資訊；感應是唯一例外。不看背包、不看沒看到的人的位置。
- 第二版「看到人」的判斷（`AiGhost.canSee` 的三條射線＋視野角）不改；可見性表只用來推理。
- AI 攻擊距離維持 2.5 格；鬼的速度規則不改（第 17 項「追擊加速」待決定，**不要實作**）。
- 卡住 0 次、傳送 0 次要維持（假逃生者 5 分鐘，固定地圖和隨機小地圖各一次）。
- 新數值都放 `src/main/resources/config.yml` 的 `ai-ghost:`，難度差異放 `ai-ghost.presets.easy|hard`；同步加到 `test-server/plugins/GhostHunt/config.yml`。
- 每一項都要留隨機（同樣情況不能每次做一樣的事），亂數一律用注入的 `RandomGenerator`／`m.rnd`，測試用固定種子。
- 純邏輯類別不能 import `org.bukkit`。
- AI 在主執行緒每 tick 目標 ≤ 1 ms（先量；超過再處理，不在這批做非同步以外的優化）。

## Review Focus

1. **粒子跑進鬼進不去的地方**（暗格裡、地下通道、監獄籠子）：搜尋目標要對到鬼走得到的格子（`NavMap.isMain`），不然鬼會走向走不到的點。→ Task 5 加測試 `bestTarget_onlyReturnsReachable`。
2. **最後看到的位置附近沒有尋路格子**（例如在樓梯上、家具旁）：`seed(-1)` 不能丟例外，追蹤器保持空的，退回第二版搜尋。→ Task 5 加測試 `seedInvalidNode_staysEmpty`。
3. **地圖中途改變**（放障礙物、卡門、打大洞）：距離場每次重算時用當下的成本，被擋住的邊不能讓粒子穿過。→ Task 1 測試 `infiniteEdge_isNotCrossed`、Task 5 測試 `particlesDoNotCrossBlockedEdge`。
4. **跨樓層**：可見性表只存同一層的格子對；不同層一律回答「不知道」（呼叫的人退回真的射線或當作看不到）。→ Task 4 測試 `differentFloors_notInTable`。
5. **逃生者被抓、逃出、離線**：`AiGhost.forget(id)` 要一併丟掉那個人的追蹤器，不然鬼會去搜一個不存在的人。→ Task 6 步驟裡加，Task 10 實機檢查。

---

## 檔案結構

| 檔案 | 責任 |
|---|---|
| `src/main/java/com/ghosthunt/game/ai/DistanceField.java`（新） | 多起點 Dijkstra，回答「走到每格要幾秒」 |
| `src/main/java/com/ghosthunt/game/ai/EscaperCost.java`（新） | 逃生者在尋路圖上的邊成本（小洞正常走、障礙物不能過……） |
| `src/main/java/com/ghosthunt/game/ai/PathFollow.java`（新） | 轉角前瞻距離、抵達減速倍率（純函式） |
| `src/main/java/com/ghosthunt/game/ai/VisibilityTable.java`（新） | 格子對格子的粗略視線表（含門） |
| `src/main/java/com/ghosthunt/game/ai/ParticleTracker.java`（新） | 一個逃生者的粒子濾波 |
| `src/main/java/com/ghosthunt/game/ai/WorldTraces.java`（新） | 記物件狀態，看出痕跡 |
| `src/main/java/com/ghosthunt/game/ai/HideoutOdds.java`（新） | 證據式查暗格／跟地洞的機率 |
| `src/main/java/com/ghosthunt/game/AiGhost.java`（改） | 接上以上全部；計時統計；除錯顯示 |
| `src/main/java/com/ghosthunt/game/AiGhostSettings.java`（改） | 讀新的設定值 |
| `src/main/java/com/ghosthunt/game/NavMap.java`（改） | 建可見性表要的快照（不透明格、門） |
| `src/main/resources/config.yml`（改） | 新設定 |
| `src/test/java/com/ghosthunt/game/ai/TestGrids.java`（新） | 測試用：字元圖 → GridNav（從 `GridNavTest` 搬出來共用） |
| `src/test/java/com/ghosthunt/sim/Sim.java`（新） | 最小模擬器 |
| `build.gradle.kts`（改） | `sim` 任務 |
| `docs/AI鬼邏輯.md`、`docs/AI鬼第三版改進建議.md`（改） | 更新到第三版第 1 批、總表狀態 |

---

### Task 1：測試用格子圖＋距離場

**Files:**
- Create: `src/test/java/com/ghosthunt/game/ai/TestGrids.java`
- Modify: `src/test/java/com/ghosthunt/game/ai/GridNavTest.java`（改用 `TestGrids.grid`）
- Create: `src/main/java/com/ghosthunt/game/ai/DistanceField.java`
- Test: `src/test/java/com/ghosthunt/game/ai/DistanceFieldTest.java`

**Interfaces:**
- Produces:
  - `TestGrids.grid(String... rows) -> GridNav`：`.` 可走、`#` 牆、`B` 障礙物邊、`H` 小洞邊、`D` 門邊（ref 0）、`T` 地洞格（同一張圖裡兩個 `T` 用 HATCH 邊相連，長度 4）；y 固定 65；節點座標 = (欄, 65, 列)。`TestGrids.id(GridNav g, int x, int z)` 回傳節點編號。
  - `DistanceField.compute(GridNav nav, GridNav.Cost cost, double blocksPerSecond, int... sources) -> DistanceField`
  - `double seconds(int node)`：走不到是 `Double.POSITIVE_INFINITY`；`node < 0` 也回傳正無窮。
  - `int downhill(int node)`：值最小、而且邊成本有限的鄰格（沒有就 -1）。
  - 成本單位：秒 =（邊長度 ＋ `cost.extra(e)`）÷ `blocksPerSecond`。

- [ ] **Step 1：把 `GridNavTest` 裡的 `grid(...)` 搬到 `TestGrids`（加上 `D`、`T`），`GridNavTest` 改呼叫它；跑 `./gradlew test --tests "*GridNavTest"`，全部 PASS。**
- [ ] **Step 2：寫失敗的測試**

```java
@Test void aroundWall_matchesAStar() {
    GridNav g = TestGrids.grid(
        "......",
        ".####.",
        "......");
    int a = TestGrids.id(g, 0, 1), b = TestGrids.id(g, 5, 1);
    DistanceField f = DistanceField.compute(g, e -> 0, 1.0, a);
    List<Integer> p = g.path(a, b, e -> 0);
    assertEquals(p.size() - 1, f.seconds(b), 1e-9);   // 每條邊長度 1，速度 1 格/秒
}
@Test void multiSource_takesMinimum() { /* 兩端各一個起點，中間格的秒數 = 到較近起點的距離 */ }
@Test void specialEdge_addsExtraOverSpeed() {
    GridNav g = TestGrids.grid("..B..");
    DistanceField f = DistanceField.compute(g, e -> e.kind() == GridNav.Kind.BARRICADE ? 3 : 0, 2.0, TestGrids.id(g, 0, 0));
    assertEquals((4 + 3 + 3) / 2.0, f.seconds(TestGrids.id(g, 4, 0)), 1e-9); // 進出兩條邊各 +3
}
@Test void infiniteEdge_isNotCrossed() {
    GridNav g = TestGrids.grid("..B..");
    DistanceField f = DistanceField.compute(g, e -> e.kind() == GridNav.Kind.BARRICADE ? Double.POSITIVE_INFINITY : 0, 1, TestGrids.id(g, 0, 0));
    assertTrue(Double.isInfinite(f.seconds(TestGrids.id(g, 4, 0))));
}
@Test void downhill_stepsTowardSource() { /* 從 (5,0) 一路 downhill 會走到起點，每步秒數遞減 */ }
@Test void invalidNode_isInfinite() { assertTrue(Double.isInfinite(f.seconds(-1))); }
```

- [ ] **Step 3：跑 `./gradlew test --tests "*DistanceFieldTest"`，預期 FAIL（類別不存在）。**
- [ ] **Step 4：實作 `DistanceField`**：陣列版 Dijkstra（`double[]` 距離＋`PriorityQueue<double[]>`，跟 `GridNav.path` 同一種寫法），跳過 `extra` 是無窮大的邊。
- [ ] **Step 5：跑測試，預期 PASS；跑 `./gradlew test` 全部 PASS。**
- [ ] **Step 6：Commit** `git commit -m "AI 鬼第三版：距離場（多起點 Dijkstra）＋共用測試格子圖"`

---

### Task 2：逃生者成本＋計時統計

**Files:**
- Create: `src/main/java/com/ghosthunt/game/ai/EscaperCost.java`
- Test: `src/test/java/com/ghosthunt/game/ai/EscaperCostTest.java`
- Modify: `src/main/java/com/ghosthunt/game/AiGhost.java`（計時、`ghostField`）

**Interfaces:**
- Consumes：`DistanceField.compute(...)`（Task 1）。
- Produces:
  - `EscaperCost.of(IntPredicate doorJammed, IntPredicate barricaded, double unjamBlocks, double hatchBlocks) -> GridNav.Cost`
    - WALK、STAIR、HOLE：0（逃生者蹲著鑽洞不減速）
    - DOOR：門卡住 → `unjamBlocks / 2`（進出各一半）；沒卡住 → 0
    - BARRICADE：有障礙物 → 正無窮；沒有 → 0
    - HATCH：`hatchBlocks`（活板門＋梯子）
  - `AiGhost`：`private DistanceField ghostField`（只有在有追蹤器活著時，每次 think 用 `costFor(Mode.SEARCH)`、速度 `5.3 * c.searchSpeed()` 重算）。
  - spec 第 1 項的 `escaperField[i]`（每個逃生者的距離場）只有第 2 批「繞前堵」用得到，這批不算（省主執行緒時間）；`DistanceField` 本身已經支援。
  - 統計（寫進 `statsInto`）：`aiPathMsAvg`、`aiPathMsMax`、`aiFieldMsAvg`、`aiTickMsAvg`、`aiTickMsMax`（毫秒，小數 2 位）。

- [ ] **Step 1：寫失敗的測試**：`hole_isFree`、`barricaded_isBlocked`、`unbarricaded_isFree`、`jammedDoor_costsHalfUnjamPerEdge`、`hatch_costsHatchBlocks`（用 `new GridNav.Edge(1, 1, Kind.X, 0)` 直接呼叫 `extra`）。
- [ ] **Step 2：跑測試，預期 FAIL。**
- [ ] **Step 3：實作 `EscaperCost.of`。**
- [ ] **Step 4：跑測試，預期 PASS。**
- [ ] **Step 5：`AiGhost` 加計時**：`tick()` 整段、`nav.nav.path(...)` 每次呼叫、`DistanceField.compute` 每次呼叫，用 `System.nanoTime()` 累加次數、總和、最大值；`statsInto` 輸出上面 5 個鍵。`ghostField` 欄位先建好（Task 6 才用）。
- [ ] **Step 6：`./gradlew build` 通過；Commit** `"AI 鬼第三版：逃生者邊成本、AI 計時統計"`

---

### Task 3：轉角前瞻＋抵達減速

**Files:**
- Create: `src/main/java/com/ghosthunt/game/ai/PathFollow.java`
- Test: `src/test/java/com/ghosthunt/game/ai/PathFollowTest.java`
- Modify: `src/main/java/com/ghosthunt/game/AiGhost.java`（`followPath`、`lineAim`、`steer`）

**Interfaces:**
- Produces:
  - `PathFollow.lookahead(double turnDegrees, boolean nearSpecial) -> double`：`nearSpecial`（目標或下一個路點是門、樓梯、障礙物、洞、地洞）→ 1.0；`turnDegrees > 30` → 1.75；其他 1.0。
  - `PathFollow.arrivalScale(double distToEnd, boolean lastWaypoint) -> double`：不是最後一個路點 → 1.0；最後一個且距離 < 2 → `max(0.35, distToEnd / 2)`；否則 1.0。
  - `AiGhost.lineAim(Location a, Location b, Location here, double ahead)`（原本寫死 1 格，改成參數）。
  - `AiGhost.steer(double dx, double dz, double dh, double scale)`：每 tick 步長再乘 `scale`。

- [ ] **Step 1：寫失敗的測試**：`straight_isOne`（turn 10 → 1.0）、`corner_looksFurther`（turn 45 → 1.75）、`door_staysOne`（turn 90、nearSpecial → 1.0）、`arrival_slowsNearEnd`（1.0 → 0.5、0.2 → 0.35）、`notLast_noSlow`。
- [ ] **Step 2：跑測試，預期 FAIL。** 
- [ ] **Step 3：實作 `PathFollow`。**
- [ ] **Step 4：跑測試，預期 PASS。**
- [ ] **Step 5：接進 `followPath`**：轉角角度 = 「from→target」跟「target→下一個路點」的夾角（沒有下一個路點時 0）；`nearSpecial` = `from→target` 或 `target→next` 的邊不是 WALK，或 `doorAim` 不是 null。`doorAim` 有結果時照舊用它（不經過前瞻）。最後一個路點用 `arrivalScale` 傳進 `steer`。
- [ ] **Step 6：實機驗證（卡住測試）**：`./gradlew build`，jar 放進 scratch 伺服器，`-Dghosthunt.stuckdebug=true` 跑：`build pve hydro_plant 11` → `start ai solo normal` → `dummy 3` → 300 秒 → `stop`；`build pve random 7` 同樣 240 秒。
  - 通過條件：log 裡 `[AI 鬼 STUCK]` 0 筆、stats.log 的 `aiStuck=0`、`aiTeleports=0`。**不通過就把 Step 5 退回，這項標成「退回第二版」。**
- [ ] **Step 7：Commit** `"AI 鬼第三版：轉角前瞻、抵達減速（卡住測試 0 次）"`

---

### Task 4：可見性表

**Files:**
- Create: `src/main/java/com/ghosthunt/game/ai/VisibilityTable.java`
- Test: `src/test/java/com/ghosthunt/game/ai/VisibilityTableTest.java`
- Modify: `src/main/java/com/ghosthunt/game/NavMap.java`（快照）、`AiGhost.java`（`buildNav` 後非同步建表）

**Interfaces:**
- Produces:
  - `interface VisibilityTable.Grid { int size(); int x(int id); int y(int id); int z(int id); boolean opaque(int x, int y, int z); int doorAt(int x, int y, int z); }`（`doorAt` 沒有門回傳 -1；`y` 是節點的腳的高度，`opaque` 問的是眼睛那格，由實作決定）
  - `VisibilityTable.build(Grid g, double maxRange) -> VisibilityTable`（只算同一個 y、水平距離 ≤ maxRange 的格子對；對稱）
  - `boolean visible(int a, int b, IntPredicate doorClosed)`：表裡看得到、而且路上的門都沒關 → true。
  - `boolean known(int a, int b)`：同一個 y、在 maxRange 內 → true（不同層、太遠 → false，呼叫的人自己決定怎麼辦）。
  - `NavMap.visibilityGrid() -> VisibilityTable.Grid`：主執行緒上把每個節點眼睛那格（y+1）的「不透明」和門編號拍成快照（`isOccluding()` 或「實心且不是門、不是可穿過」算不透明；門回傳 `doors` 的編號），回傳的物件不再碰世界。
  - `AiGhost`：`private volatile VisibilityTable vis`；`buildNav()` 後用 `Bukkit.getScheduler().runTaskAsynchronously` 建表（`maxRange` = 30），建好前 `vis == null`，所有用到的地方退回「不知道」。log 印格子數、建表毫秒數、大約記憶體。

射線演算法（supercover，格子中心到格子中心，經過的每一格都要不透明才擋；剛好穿過角落時兩側都不透明才擋）：

```text
dx=|x1-x0|, dz=|z1-z0|, sx=sign, sz=sign, x=x0, z=z0, n=1+dx+dz, err=dx-dz, dx*=2, dz*=2
重複 n 次：檢查 (x,z)（起點終點不算）；
  err>0 → x+=sx, err-=dz；err<0 → z+=sz, err+=dx；
  err==0（正好經過角落）→ 兩個側格 (x+sx,z) 和 (x,z+sz) 都不透明才算擋住；然後 x+=sx, z+=sz, err+=dx-dz, n--
```
路上經過的門（`doorAt >= 0`）不算擋，但記在 `Map<Long, int[]>`（鍵：較小編號 × size ＋ 較大編號）給 `visible` 查。

- [ ] **Step 1：寫失敗的測試**（`TestGrids` 加一個 `VisibilityTable.Grid` 轉接：`#` 不透明、`D` 是門 0、其他透明）：
  - `openRoom_seesAcross`、`wall_blocks`、`cornerPinch_blocks`（兩個側格都是牆）、`cornerOneSide_sees`（只有一側是牆）、`closedDoor_blocks_openDoor_sees`（`doorClosed` 傳 `i -> true`／`i -> false`）、`beyondRange_notKnown`、`differentFloors_notInTable`（兩個不同 y 的節點：`known` 是 false）、`symmetric`。
- [ ] **Step 2：跑測試，預期 FAIL。**
- [ ] **Step 3：實作 `VisibilityTable`**（每個節點一個 `BitSet`，索引是節點編號；先用 `x,z` 建同一層的格子索引加速）。
- [ ] **Step 4：跑測試，預期 PASS。**
- [ ] **Step 5：接進 `NavMap`、`AiGhost`（非同步建表）。** `selftest` 加一行「可見性表：N 格、M ms、約 K MB」（selftest 裡同步建，量時間用）。
- [ ] **Step 6：實機**：`build pve hydro_plant 5` → `selftest`；`build pve random 3` → `selftest`。通過條件：兩張圖都印出可見性表那行，建表 < 1500 ms。
- [ ] **Step 7：Commit** `"AI 鬼第三版：可見性表（supercover 射線、門、非同步建表）"`

---

### Task 5：粒子濾波（純邏輯）

**Files:**
- Create: `src/main/java/com/ghosthunt/game/ai/ParticleTracker.java`
- Test: `src/test/java/com/ghosthunt/game/ai/ParticleTrackerTest.java`

**Interfaces:**
- Consumes：`DistanceField`（Task 1）、`EscaperCost`（Task 2 的 `GridNav.Cost`）。
- Produces:
  - `record ParticleTracker.Config(int count, double blocksPerSecond, double fleeWeight, double goalWeight, double soundSigma, int minAlive, long maxAgeTicks)`；隨機移動的權重 = `1 - fleeWeight - goalWeight`。
  - `new ParticleTracker(GridNav nav, GridNav.Cost escaperCost, Config cfg, RandomGenerator rnd)`
  - `void seed(int node, long tick)`：`node < 0` → 什麼都不做（`alive() == 0`）。
  - `void seedUniform(Collection<Integer> nodes, long tick)`
  - `void step(double seconds, DistanceField ghostField, DistanceField goalField)`：`goalField` 可以是 null（那部分權重併進隨機）。
  - `void removeVisible(IntPredicate visibleToGhost)`
  - `void weightNear(double x, double y, double z, double factor)`：高斯權重（σ = `soundSigma`；不同 y 的粒子權重不變）＋重新取樣回 `count` 顆（剩 0 顆時不動）。聲音呼叫 factor 2，痕跡呼叫 factor 2。
  - `int alive()`、`boolean expired(long tick)`（`alive() < minAlive` 或 `tick - seedTick > maxAgeTicks`）
  - `int bestTarget(IntPredicate unseen, IntPredicate reachable)`：每顆粒子所在格子計數；每格的分數 = 自己＋水平距離 ≤ 2 的同層格子的粒子數；在 `unseen && reachable` 的格子裡取分數最高（同分取編號小）；都沒有回傳 -1。粒子在 `reachable` 是 false 的格子（暗格、通道）時，把計數記到它最近的 `reachable` 鄰格（BFS 最多 3 步）。
  - `double shareWithin(Collection<Integer> nodes)`：在這些格子裡的粒子比例（Task 8 用）。
  - `int[] positions()`（除錯顯示用）

粒子一步（每顆，距離預算 = `blocksPerSecond × seconds`，沿著邊一格一格走直到預算用完）：

```text
選下一格：r = rnd.nextDouble()
  r < fleeWeight              → 鄰格裡 ghostField.seconds 最大的（成本無限的邊不算）
  r < fleeWeight+goalWeight   → 鄰格裡 goalField.seconds 最小的（goalField 是 null 時當成隨機）
  否則                        → 隨機一個鄰格
走過去花掉（邊長度 ＋ escaperCost.extra）；沒有可走的鄰格就停。
```

- [ ] **Step 1：寫失敗的測試**（固定種子 `new java.util.Random(1)`，Config `(200, 5.6, 0.6, 0.0, 3, 10, 800)`）：
  - `seedInvalidNode_staysEmpty`：`seed(-1, 0)` 後 `alive() == 0`、`bestTarget(...) == -1`。
  - `removeVisible_leavesNoParticleInSeenCells`：開放房間，seed 中間，step 一次，`removeVisible(左半邊)` 後 `positions()` 沒有左半邊的格子。
  - `deadEnd_particlesStayInside`：`"#####\n#...........\n#####"` 這種一端封死的走廊，鬼站在開口端（ghostField 從開口算），step 10 次（每次 0.5 秒）後 ≥ 90% 的粒子在走廊裡。
  - `sound_pullsParticles`：大房間均勻撒，`weightNear(某點, 2)` 3 次後，≥ 50% 的粒子在那點 6 格內。
  - `particlesDoNotCrossBlockedEdge`：`"...B..."`，障礙物成本無限，左邊 seed，step 20 次後右邊沒有粒子。
  - `bestTarget_onlyReturnsReachable`：粒子都在一個 `reachable` 是 false 的格子，`bestTarget` 回傳的是它旁邊 reachable 的格子，不是它自己。
  - `expires_afterMaxAge_orTooFew`。
  - `sameSeed_sameResult`（兩個同種子的追蹤器 step 後 `positions()` 一樣）。
- [ ] **Step 2：跑測試，預期 FAIL。**
- [ ] **Step 3：實作 `ParticleTracker`**（粒子存 `int[] node` ＋ `double[] weight`；重新取樣用 systematic resampling）。
- [ ] **Step 4：跑測試，預期 PASS。**
- [ ] **Step 5：Commit** `"AI 鬼第三版：粒子濾波追蹤器（純邏輯）"`

---

### Task 6：粒子接進 AI 鬼（搜尋、感應、聽覺）

**Files:**
- Modify: `src/main/java/com/ghosthunt/game/AiGhost.java`、`AiGhostSettings.java`、`src/main/resources/config.yml`、`test-server/plugins/GhostHunt/config.yml`

**Interfaces:**
- Consumes：Task 1、2、4、5 全部。
- Produces（設定，`ai-ghost:` 底下）：
  - `particles: 200`、`particle-flee: 0.6`、`particle-goal: 0.25`、`particle-sigma: 3`、`particle-min: 10`、`particle-max-age: 40`（秒）、`escaper-speed: 5.6`
  - presets：`easy: {particles: 80, particle-flee: 0, particle-goal: 0}`；`hard: {particles: 400}`
- `AiGhost` 新欄位：`Map<UUID, ParticleTracker> trackers`、`ParticleTracker sensedTracker`。

- [ ] **Step 1：追丟時撒粒子**：`perceive()` 裡，正在追的人「剛看不到」超過 15 tick（第二版用真實位置的那 1 秒結束）時，`trackers.put(id, 新追蹤器)` 並 `seed(nav.nearest(lastSeen, 2.5))`。
- [ ] **Step 2：每次 think 更新**：有任何追蹤器活著時重算 `ghostField`；每 2 次 think（0.5 秒）對每個追蹤器 `step(0.5, ghostField, goalField)`，`goalField` = 逃生者下一個遊戲目標的距離場（電源沒開 → 電源位置；有電、還沒授權 → 最近的終端機；授權了 → 最近的大門讀卡機；用 `EscaperCost`、`escaper-speed` 算，同一個目標快取到 `m.mapVersion` 改變）；然後 `removeVisible(i -> 鬼看得到 i)`，「看得到」= `vis != null && vis.known(鬼格, i) && vis.visible(鬼格, i, 門關著) && 在視野角內（或 near-aware 內）`；`expired` 的移除。
- [ ] **Step 3：搜尋改用粒子**：`search()` 走到最後位置以後的「③ 往逃跑方向多走 7 格」改成「去 `bestTarget(未看過, nav::isMain)`」；有追蹤器活著時延長搜尋（`lastSeenTick` 推到現在，最多到 `particle-max-age`，取代 `search-cap`）。① 地洞、② 暗格步驟保留（Task 8 改機率）。追蹤器全部空了才退回第二版的 `startLook()`。
- [ ] **Step 4：感應改用粒子**：`sense()` 的結果房間，把那間房的格子（`roomAt(nav.loc(i)) == 房間`）丟進 `sensedTracker.seedUniform`；`GO_SENSED` 走到房間中心後，改去 `sensedTracker.bestTarget`，直到 `expired`。
- [ ] **Step 5：聽到聲音**：`hearNoise(at, level)` 對所有追蹤器 `weightNear(at, 2)`。
- [ ] **Step 6：忘記**：`forget(id)` 移除 `trackers.get(id)`。
- [ ] **Step 7：除錯顯示**：`debugDraw` 每個追蹤器畫粒子（每格一點，不同逃生者不同顏色，最多 100 點）；`aidebug` 那行多顯示「粒子 N」。
- [ ] **Step 8：統計**：`aiReacquire30s`（追丟後 30 秒內重新看到任何人的次數）、`aiLosses`（追丟次數）。
- [ ] **Step 9：實機**：跟 Task 3 Step 6 一樣的兩場（假逃生者），加 `aidebug` 看粒子。通過條件：卡住 0、傳送 0、log 沒有例外、stats.log 有 `aiReacquire30s`、`aiLosses`。
- [ ] **Step 10：Commit** `"AI 鬼第三版：追丟、感應改用粒子搜尋"`

---

### Task 7：看痕跡

**Files:**
- Create: `src/main/java/com/ghosthunt/game/ai/WorldTraces.java`
- Test: `src/test/java/com/ghosthunt/game/ai/WorldTracesTest.java`
- Modify: `src/main/java/com/ghosthunt/game/AiGhost.java`、`Objectives.java`（加 `boolean isSearched(Pos)`）、`Match.java`（加 `boolean isToppled(int)`）

**Interfaces:**
- Produces:
  - `enum WorldTraces.Type { DOOR, BARREL, CABINET, HOLE }`
  - `record WorldTraces.Trace(Type type, int id, double x, double y, double z, long gapTicks, long tick)`；`double usefulness()`：`gapTicks <= 1200`（60 秒）→ 1.0，否則 0.25。
  - `Trace observe(Type type, int id, int state, double x, double y, double z, long tick)`：第一次看到只記錄、回傳 null；狀態跟上次看到不同、而且不是鬼自己改的 → 回傳 Trace；否則 null。每次都更新「上次看到的狀態與時間」。
  - `void ghostChanged(Type type, int id, int newState)`：鬼自己造成的變化，下次看到不算痕跡。
  - 狀態編碼：門 0 關／1 開／2 卡住；木桶 0 沒翻／1 翻過；櫃子 0 站著／1 推倒；小洞 0 原本／1 打大。

- [ ] **Step 1：寫失敗的測試**：`firstSight_isNotATrace`、`changedState_isATrace`、`sameState_isNotATrace`、`ghostCaused_isNotATrace`、`oldGap_isLessUseful`（gap 1300 tick → 0.25）。
- [ ] **Step 2：跑測試，預期 FAIL。**
- [ ] **Step 3：實作 `WorldTraces`。**
- [ ] **Step 4：跑測試，預期 PASS。**
- [ ] **Step 5：接進 `AiGhost`**：每 4 次 think（1 秒）對 16 格內、可見性表看得到（門用 `vis.visible`）、在視野角內的物件呼叫 `observe`：門（`nav.doors`，狀態看方塊 `Openable.isOpen()` 和 `m.isDoorJammed`）、木桶（`m.b.containers`，`m.obj.isSearched`）、櫃子（`m.b.toppleSpots` 編號，`m.isToppled`）、小洞（`m.b.crawlHoles` 編號，`m.isHoleWidened`）。有痕跡：`rooms.noise(roomAt(位置), 60 * usefulness)`，所有追蹤器 `weightNear(位置, 2)`；log（`STUCK_DEBUG` 或 aidebug 時）印「[AI 鬼] 痕跡：門開了 …」。
  - 鬼自己的動作：`openDoorsOnPath` 開門、撞開卡門、拆障礙物、打大洞後呼叫 `ghostChanged`。
- [ ] **Step 6：統計** `aiTraces`（看到幾個痕跡）。
- [ ] **Step 7：`./gradlew build`；Commit** `"AI 鬼第三版：看痕跡（門、木桶、櫃子、小洞）"`

---

### Task 8：證據式查暗格、跟地洞

**Files:**
- Create: `src/main/java/com/ghosthunt/game/ai/HideoutOdds.java`
- Test: `src/test/java/com/ghosthunt/game/ai/HideoutOddsTest.java`
- Modify: `AiGhost.java`（`search`、`hideoutCheck`）、`AiGhostSettings.java`、`config.yml`（`check-hideout-chance` 改名 `check-hideout-base`，讀的時候舊名當備案）

**Interfaces:**
- Produces:
  - `HideoutOdds.chance(double base, double distToEntrance, double secondsSinceSeen, boolean quietSince, double particleShare, double learnBonus) -> double`
    - `distToEntrance <= 3 && secondsSinceSeen <= 5 && quietSince` → ×2.5
    - `particleShare >= 0.3` → ×2
    - `distToEntrance > 8` → ×0.3
    - ×(1 ＋ `learnBonus`)（這批一律傳 0）
    - 上限 0.95
  - `AiGhost`：`lastQuietBreakTick`（聽到腳步、開門、任何 `hearNoise` 時更新）；`quietSince = lastQuietBreakTick < lastSeenTick`。

- [ ] **Step 1：寫失敗的測試**（spec 的驗收數字）：
  - `inFrontOfGhost_atLeast80Percent`：`chance(0.4, 1, 2, true, 0, 0) >= 0.8`（＝ 0.95）
  - `farAway_atMost20Percent`：`chance(0.4, 12, 2, true, 0, 0) <= 0.2`（＝ 0.12）
  - `noisyAfter_noBonus`：`chance(0.4, 1, 2, false, 0, 0) == 0.4`
  - `particles_double`：`chance(0.4, 5, 20, false, 0.5, 0) == 0.8`
  - `capped`：`chance(0.9, 1, 1, true, 0.5, 0) == 0.95`
- [ ] **Step 2：跑測試，預期 FAIL。**
- [ ] **Step 3：實作 `HideoutOdds`。**
- [ ] **Step 4：跑測試，預期 PASS。**
- [ ] **Step 5：接進 `AiGhost`**：`hideoutCheck` 改成對 12 格內每個暗格算 `chance`（`particleShare` = 追蹤器 `shareWithin(暗格躲藏空間的格子)`，沒有追蹤器 0），挑機率最高的那個擲一次。跟地洞：`followTunnelChance` 當 base，同一套倍率（`particleShare` 用地洞另一端附近的格子），上限 0.95。
- [ ] **Step 6：`./gradlew build`；Commit** `"AI 鬼第三版：證據式查暗格、跟地洞"`

---

### Task 9：最小模擬器

**Files:**
- Create: `src/test/java/com/ghosthunt/sim/Sim.java`（main）、`src/test/java/com/ghosthunt/sim/SimMap.java`、`src/test/java/com/ghosthunt/sim/SimGhost.java`、`src/test/java/com/ghosthunt/sim/SimEscaper.java`
- Modify: `build.gradle.kts`（`sim` 任務）

**Interfaces:**
- Consumes：`GridNav`、`DistanceField`、`EscaperCost`、`VisibilityTable`、`ParticleTracker`、`HideoutOdds`。
- Produces:
  - `./gradlew sim --args="--map hydro_plant --runs 200 --seconds 300 --search v3 --seed 1"`；`--search v2` 用第二版的搜尋（走到最後位置 → 往逃跑方向 7 格 → 張望 3 秒 → 隨機巡邏）。
  - `SimMap.load(Path mapTxt) -> SimMap`：只讀 `[1F]`（這批只模擬 1F）。可走：`. , ;`、標記字母、數字、門 `D W O`（當作開著）；`h` 是 HOLE 邊（只有逃生者能走）；`H` 是 HOLE 邊、後面那格是暗格節點；牆、家具、`I`、`J`、`K` 不可走。不透明：牆、`%`、家具（全部當 2 格高）。提供 `GridNav nav()`、`VisibilityTable vis()`、`IntPredicate ghostReachable()`、`List<Integer> hideoutNodes()`。
  - 時間步：0.25 秒。鬼速度 5.3 × 倍率（追 1.0、搜尋 0.9、巡邏 0.75），逃生者 5.6（跑）／4.3（走）。
  - 鬼：看得到（`vis` ＋ 視野 120 度 ＋ 24 格）就沿 A* 追；距離 ≤ 1.5 抓到（被抓的逃生者傳到離鬼最遠的格子）；看不到時 v2 或 v3 搜尋（v3：追蹤器＋`bestTarget`＋`HideoutOdds` 查暗格）；都沒有就隨機巡邏。聽：8 格內跑步的逃生者 → `weightNear`。
  - 逃生者：沒事時往隨機目標格走；14 格內、看得到鬼時用鬼的 `DistanceField` 往「離鬼最遠的鄰格」跑；跑到暗格入口旁、鬼看不到時 30% 機率躲 10 秒。
  - 輸出（每組設定一行）：`runs、每分鐘抓幾次、追丟次數、追丟後 30 秒內重新找到的比例、平均第一次接觸秒數`。

- [ ] **Step 1：寫 `SimMap` 的測試** `SimMapTest.loadsHydroPlant`：讀 `src/main/resources/pve-maps/hydro_plant.txt`，`nav().size() > 1000`、`hideoutNodes().size() == 5`、出生點格子走得到鬼房門口外的格子。
- [ ] **Step 2：跑測試，預期 FAIL；實作 `SimMap`；跑測試 PASS。**
- [ ] **Step 3：實作 `SimGhost`、`SimEscaper`、`Sim`（main，解析參數、跑、印結果）；`build.gradle.kts` 加：**

```kotlin
tasks.register<JavaExec>("sim") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.ghosthunt.sim.Sim")
}
```

- [ ] **Step 4：跑比較**：`./gradlew sim --args="--map hydro_plant --runs 200 --search v2"` 和 `--search v3`，`seaside_resort`、`office_park` 各一次。通過條件（spec 驗收）：三張圖 v3 的「追丟後 30 秒內重新找到的比例」都比 v2 高。結果寫進 `docs/AI鬼邏輯.md` 的測試段。
- [ ] **Step 5：Commit** `"AI 鬼第三版：最小模擬器（v2／v3 搜尋比較）"`

---

### Task 10：整批驗收與文件

**Files:**
- Modify: `docs/AI鬼邏輯.md`（升到「第三版第 1 批」：距離場、可見性表、粒子、痕跡、證據式查暗格、轉角前瞻、模擬器、新設定、新統計）、`docs/AI鬼第三版改進建議.md`（總表 1、2、6、7、9、10、23 改成「完成」或「退回」）、`CLAUDE.md`（`game/ai/` 新類別、`./gradlew sim`）

- [ ] **Step 1：`./gradlew build`，所有 JUnit PASS（第二版的測試全部還在）。**
- [ ] **Step 2：實機總驗收**（scratch 伺服器、`-Dghosthunt.stuckdebug=true`）：固定地圖 `hydro_plant` normal 5 分鐘、hard 5 分鐘、`seaside_resort` normal 5 分鐘、`build pve random 7` normal 4 分鐘，各 3 個假逃生者。通過條件：
  - `[AI 鬼 STUCK]` 0 筆、`aiTeleports=0`
  - log 沒有例外
  - stats.log 的 `aiTickMsAvg`（記下數字；> 1 ms 的話寫進文件當已知問題，不在這批處理）
  - 「逃生者被抓後鬼不再搜他」：看 aidebug，被抓的那個人的粒子消失（Review Focus 5）
- [ ] **Step 3：更新三份文件。**
- [ ] **Step 4：Commit** `"AI 鬼第三版第 1 批：驗收、文件"`
