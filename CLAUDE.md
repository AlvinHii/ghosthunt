# 鬼抓人：大樓逃生（GhostHunt）

Paper 26.3 的非對稱小遊戲插件：1 鬼 vs 3–5 逃生者，在隨機生成的 3 層辦公大樓裡找門禁卡、開電源、刷卡開大門逃出。
企劃書：`鬼抓人：大樓逃生 企劃書.docx`（v3.1，之後的改動以程式和 `docs/` 為準）。
使用者講繁體中文，回覆、遊戲內文字、文件都用繁體中文。

## 建置與測試

- JDK 26（`C:\Program Files\Java\jdk-26.0.1`）編譯，`options.release = 25`；Gradle wrapper。
- `./gradlew build`：編譯＋單元測試（`src/test`，只測不碰伺服器的純邏輯）。`./gradlew deploy`：打包並複製 jar 到 `test-server/plugins/`。
- 本機測試伺服器在 `test-server/`（`start.bat`，Paper 26.3）。伺服器開著時換 jar 要重開。
- 遊戲內指令（`/ghosthunt` 或 `/gh`）：`build [seed]`、`build pve [seed]`（PvE 小地圖）、`selftest`（不需要玩家的整局檢查，改完規則先跑這個；含 AI 鬼尋路檢查）、`start <鬼ID|random> [basic|nightmare]`、`start ai`（PvE：所有人當逃生者，鬼是電腦；`start ai solo` 沒有逃生者、只看 AI 鬼巡邏，測試用）、`stop`、`tp [樓層]`、`fog <距離|off> [me|others]`、`info`、`export`（匯出大樓給模擬用）、`reload`、`clear`。
- 每局數據寫在 `test-server/plugins/GhostHunt/stats.log`。

## 程式結構（`src/main/java/com/ghosthunt/`）

- `GhostHuntPlugin`：指令、蓋樓流程、告示牌、匯出。`ArenaWorld`：空世界 `ghosthunt_arena`。`FogExperiment`：用資料包 biome 做霧（含只對逃生者送的個人霧，反射呼叫 NMS 封包）。
- `building/`：大樓生成。預設是 `IrregularGenerator`（`map.generator: irregular`）：config 的 `map.shape` 固定凹凸外型（3 層一樣），房間每局隨機拼、短走廊、門連成樹再多開幾扇繞圈、鑽洞通道、牆中暗格、1F 底下的地下通道、可推倒的櫃子、保全室終端機、家具依房間大小用規則擺，產生的 `Building` 欄位跟舊版一樣。舊的九宮格（`map.generator: grid`）：`BuildingGenerator`（房間分配、牆、走廊、大門）→ `RoomBuilder`（門、破洞、燈、家具）→ `Validator`（BFS 檢查走得到、樓梯淨空、死路）→ `BlockPlacer`（分批放方塊）。`Layout` 是座標幾何（3×3 房間＋井字走廊，房間 20、走廊 3、樓高 5 → 68×68）。
- `game/`：對局。鬼是 `LivingEntity`（真人是 Player、AI 是怪物），鬼的能力（`ghostCutPower`、`ghostMoveCard`、`ghostSabotage`、`ghostBreakBarricade`、`ghostBreakJam`、`ghostPryHatch`、`ghostWidenHole`、`ghostSwing`／`ghostHit`）真人點擊和 AI 共用。`AiGhost`（PvE 的 AI 鬼：感知、Paper 尋路、開門、揮拳、感應、卡住處理）＋`game/ai/GhostBrain`（純邏輯的優先順序，JUnit 測）＋`StuckTracker`。`Match`（主迴圈、抓人、監獄、救人、障礙物、破洞）、`Objectives`（木桶內容、電源、門禁卡、大門）、`Interactions`（站著不動的進度條互動）、`Items`/`ItemUse`（道具）、`Nightmare`（夢魘鬼熄燈）、`Hud`、`GameListener`（事件）、`GameSettings`/`ObjectiveSettings`（讀 config）。
- 數值全部在 `src/main/resources/config.yml`；測試伺服器用的是 `test-server/plugins/GhostHunt/config.yml`（舊檔不會自動補新欄位，刪掉重開才會重生）。

## 房間範本（不要直接改 `RoomTemplates.java`）

`RoomTemplates.java` 是產生出來的。改房間格局的流程：
1. 改 `tools/room_plans.py` 的 18×18 字元圖（符號表在檔案開頭和 `RoomBuilder.template()`）。
2. `python tools/room_plans.py` 檢查（門口、走得到、木桶摸得到）。
3. `python tools/gen_java_templates.py` 重新產生 `RoomTemplates.java`；`python tools/make_room_doc.py` 更新 `docs/房間設計v2.md`。

## 平衡模擬

`tools/simulate.py`：用 `/ghosthunt export` 匯出的大樓 JSON 跑電腦對局。
`python tools/simulate.py <layouts資料夾> <局數> '<JSON 覆寫參數>'`。結果和限制寫在 `docs/平衡模擬.md`（模擬沒有門、障礙物、破洞、躲藏，只看大方向）。

## 已確定的設計決定（使用者決定的，不要自己改）

- 沒有勝負，遊戲結束就顯示結果。鬼整場速度固定（只依人數微調）。所有人都不能跳。
- 大門 2 扇隨機放（不同樓層、相距 ≥25、靠不同樓梯）。門禁卡 2 張在不同樓層。
- 鬼每揮一次拳（揮空也算）1.5 秒內減速 90%（`ghost.attack-fatigue-slow`）。鬼看不到霧（霧只送給逃生者），鬼整場自帶夜視。停電亮度 1、有電 10。
- 加速效果不疊加。
- 場景互動：門口旁可推倒的櫃子（推倒＝擋住門口，鬼要拆）、蹲下右鍵雙開門卡門（每扇一次，鬼要撞開）。
- 門禁卡要先到保全室（全棟 2 間、不同樓層）的終端機授權才能刷；終端機要有電。
- PvE：2～3 人對抗 AI 鬼、2 層小地圖（`pve:`）；AI 鬼平常只看得到／聽得到，每 60 秒感應一次最近的逃生者大概在哪（被盯上的人聽到心跳加快）；只有基本鬼的能力（不會熄燈）。
- 1F 底下有地下通道（地上活板門＋梯子，連到遠處另一個房間）；逃生者自由進出，鬼要先撬開出入口。每間房間地板顏色不同（認路用）。

## 目前狀態

- 整局流程只用 `selftest` 和模擬驗證過，真人互動（翻箱、刷卡、抓人、救人、道具）還在第一次實測。
- 待決定：容器要不要加倍（見 `docs/平衡模擬.md` 第二輪）—— 等實測的 `stats.log` 再說。
- 專案用 git 管理（main 分支）。兩個 Claude 同時開發時，各自開分支或 worktree，不要同時改同一個檔案；改完先 commit。
