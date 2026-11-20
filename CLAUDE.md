# 鬼抓人：大樓逃生（GhostHunt）

Paper 26.3 的非對稱小遊戲插件：1 鬼 vs 3–5 逃生者，在隨機生成的 3 層辦公大樓裡找門禁卡、開電源、刷卡開大門逃出。
企劃書：`鬼抓人：大樓逃生 企劃書.docx`（v3.1，之後的改動以程式和 `docs/` 為準）。
使用者講繁體中文，回覆、遊戲內文字、文件都用繁體中文。

## 建置與測試

- JDK 26（`C:\Program Files\Java\jdk-26.0.1`）編譯，`options.release = 25`；Gradle wrapper。
- `./gradlew build`：編譯。`./gradlew deploy`：打包並複製 jar 到 `test-server/plugins/`。
- 本機測試伺服器在 `test-server/`（`start.bat`，Paper 26.3）。伺服器開著時換 jar 要重開。
- 遊戲內指令（`/ghosthunt` 或 `/gh`）：`build [seed]`、`selftest`（不需要玩家的整局檢查，改完規則先跑這個）、`start <鬼ID|random> [basic|nightmare]`、`stop`、`tp [樓層]`、`fog <距離|off> [me|others]`、`info`、`export`（匯出大樓給模擬用）、`reload`、`clear`。
- 每局數據寫在 `test-server/plugins/GhostHunt/stats.log`。

## 程式結構（`src/main/java/com/ghosthunt/`）

- `GhostHuntPlugin`：指令、蓋樓流程、告示牌、匯出。`ArenaWorld`：空世界 `ghosthunt_arena`。`FogExperiment`：用資料包 biome 做霧（含只對逃生者送的個人霧，反射呼叫 NMS 封包）。
- `building/`：大樓生成。預設是 `IrregularGenerator`（`map.generator: irregular`）：config 的 `map.shape` 固定凹凸外型（3 層一樣），房間每局隨機拼、短走廊、門連成樹再多開幾扇繞圈、鑽洞通道、牆中暗格、家具依房間大小用規則擺，產生的 `Building` 欄位跟舊版一樣。舊的九宮格（`map.generator: grid`）：`BuildingGenerator`（房間分配、牆、走廊、大門）→ `RoomBuilder`（門、破洞、燈、家具）→ `Validator`（BFS 檢查走得到、樓梯淨空、死路）→ `BlockPlacer`（分批放方塊）。`Layout` 是座標幾何（3×3 房間＋井字走廊，房間 20、走廊 3、樓高 5 → 68×68）。
- `game/`：對局。`Match`（主迴圈、抓人、監獄、救人、障礙物、破洞）、`Objectives`（木桶內容、電源、門禁卡、大門）、`Interactions`（站著不動的進度條互動）、`Items`/`ItemUse`（道具）、`Nightmare`（夢魘鬼熄燈）、`Hud`、`GameListener`（事件）、`GameSettings`/`ObjectiveSettings`（讀 config）。
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
- 鬼揮手會疲勞（比人類慢）。鬼看不到霧（霧只送給逃生者）。停電亮度 1、有電 10。
- 加速效果不疊加。

## 目前狀態

- 整局流程只用 `selftest` 和模擬驗證過，真人互動（翻箱、刷卡、抓人、救人、道具）還在第一次實測。
- 待決定：容器要不要加倍（見 `docs/平衡模擬.md` 第二輪）—— 等實測的 `stats.log` 再說。
- 專案用 git 管理（main 分支）。兩個 Claude 同時開發時，各自開分支或 worktree，不要同時改同一個檔案；改完先 commit。
