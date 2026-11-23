# 鬼抓人：大樓逃生（GhostHunt）

Paper 26.3 的非對稱小遊戲插件：1 鬼 vs 3–5 逃生者，在隨機生成的 3 層辦公大樓裡找門禁卡、開電源、刷卡開大門逃出。
企劃書：`鬼抓人：大樓逃生 企劃書.docx`（v3.1，之後的改動以程式和 `docs/` 為準）。
使用者講繁體中文，回覆、遊戲內文字、文件都用繁體中文。

## 建置與測試

- JDK 26（`C:\Program Files\Java\jdk-26.0.1`）編譯，`options.release = 25`；Gradle wrapper。
- `./gradlew build`：編譯＋單元測試（`src/test`，只測不碰伺服器的純邏輯）。`./gradlew deploy`：打包並複製 jar 到 `test-server/plugins/`。
- 本機測試伺服器在 `test-server/`（`start.bat`，Paper 26.3）。伺服器開著時換 jar 要重開。
- 遊戲內指令（`/ghosthunt` 或 `/gh`）：`build [seed]`、`build pve [地圖名|random] [seed]`（PvE：預設是固定地圖 `pve.map`）、`maps`（列出 PvE 固定地圖）、`pack`（重送資源包）、`lure`（測試用：敲迎賓鈴）、`maptest [地圖] [次數]`（固定地圖產生很多次，統計每局隨機的大門／保全室／木桶）、`selftest`（不需要玩家的整局檢查，改完規則先跑這個；含 AI 鬼尋路檢查）、`start <鬼ID|random> [basic|nightmare]`、`start ai [easy|normal|hard] [hound|ambusher|watcher|standard|auto] [solo]`（PvE：所有人當逃生者，鬼是電腦；第二個參數是 AI 的個性，預設 auto 依記憶自動選；`solo` 沒有真人逃生者，測試用）、`sidetest`（測試用：不用玩家把支線做一遍）、`cam <編號|exit>`／`pa <區域>`（監視器、廣播，聊天窗的按鈕，玩家可以用）、`aimemory reset [地圖|all]`（清掉 AI 鬼學到的東西，`plugins/GhostHunt/ai-memory/`）、`start ai test [難度] [個性] [數量]`（測試模式：你當逃生者，被抓一次立刻重生、之後 AI 鬼完全無視你；鬼放出來時自動生假逃生者，預設 3 個（`test-mode.dummies`）；不寫學習記憶）、`dummy [n]`（AI 局裡生假逃生者，測試用）、`aidebug`（AI 鬼除錯顯示）、`stop`、`tp [樓層]`、`fog <距離|off> [me|others]`、`info`、`export`（匯出大樓給模擬用）、`reload`、`clear`。
- 每局數據寫在 `test-server/plugins/GhostHunt/stats.log`。
- 伺服器跑在 app 的 Terminal 分頁時沒辦法打 stop：在 `test-server/plugins/GhostHunt/` 放一個 `restart.flag` 檔案，插件會在沒人在線上時正常存檔關閉（內容寫 force 就不管有沒有人）。

## 程式結構（`src/main/java/com/ghosthunt/`）

- `GhostHuntPlugin`：指令、蓋樓流程、告示牌、匯出。`ArenaWorld`：空世界 `ghosthunt_arena`。`FogExperiment`：用資料包 biome 做霧（含只對逃生者送的個人霧，反射呼叫 NMS 封包）。
- `building/`：大樓生成。PvE 預設用固定地圖：`FixedMap`（讀 `pve-maps/*.txt` 文字平面圖）→ `FixedMapGenerator`（照圖蓋，每局隨機挑大門、保全室、木桶；格式見 `docs/PvE地圖格式.md`，檢查工具 `tools/pve_map_check.py`，給別的 AI 畫圖的 prompt 在 `docs/PvE地圖生成prompt.md`，別的 AI 畫的圖先用專案 skill `ghosthunt-map-check`（`.claude/skills/`）檢查原圖，再用 `tools/convert_ai_maps.py` 轉）。附的地圖：`office_park`、`hydro_plant`、`seaside_resort`、`mall2`（夜半購物城 106×106，PvE 預設，有所有支線）、`mall`（91×91）、`mall_m`（76×76）（`GhostHuntPlugin.BUNDLED_MAPS`）。模組化藍圖（巨集圖＋16×16 模組，見 `docs/PvE大地圖_模組化prompt.md`）用 `tools/assemble_macro_map.py` 拼成地圖；購物中心的原始藍圖在 `docs/pve-maps/blueprints/`（家具改藍圖裡的 ```furniture 區塊再重跑工具：`python tools/assemble_macro_map.py docs/pve-maps/blueprints/mall_m.md src/main/resources/pve-maps/mall_m.txt --id mall_m --barrels 30`，mall 用 `--id mall --barrels 36`；`[furniture]` 的朝向、裝飾選項見 `FixedMap.Furniture`）。大樓（PvP）預設是 `IrregularGenerator`（`map.generator: irregular`）：config 的 `map.shape` 固定凹凸外型（3 層一樣），房間每局隨機拼、短走廊、門連成樹再多開幾扇繞圈、鑽洞通道、牆中暗格、1F 底下的地下通道、可推倒的櫃子、保全室終端機、家具依房間大小用規則擺，產生的 `Building` 欄位跟舊版一樣。舊的九宮格（`map.generator: grid`）：`BuildingGenerator`（房間分配、牆、走廊、大門）→ `RoomBuilder`（門、破洞、燈、家具）→ `Validator`（BFS 檢查走得到、樓梯淨空、死路）→ `BlockPlacer`（分批放方塊）。`Layout` 是座標幾何（3×3 房間＋井字走廊，房間 20、走廊 3、樓高 5 → 68×68）。
- `game/`：對局。鬼是 `LivingEntity`（真人是 Player、AI 是怪物），鬼的能力（`ghostCutPower`、`ghostMoveCard`、`ghostSabotage`、`ghostBreakBarricade`、`ghostBreakJam`、`ghostPryHatch`、`ghostWidenHole`、`ghostSwing`／`ghostHit`）真人點擊和 AI 共用。`AiGhost`（PvE 的 AI 鬼：感知、每 tick 攻擊、照自己的尋路圖推著走、搜尋、巡邏、守望、除錯、統計）＋`NavMap`（從方塊建尋路圖）＋`TestDummies`（假逃生者）＋`game/ai/`（純邏輯、JUnit 測：`GhostBrain` 優先順序、`TargetPicker`、`GridNav` A*、`RoomScores`、`SenseDirector`；第三版加了 `DistanceField`、`EscaperCost`、`VisibilityTable`、`ParticleTracker`、`WorldTraces`、`HideoutOdds`、`PathFollow`，第 2～4 批加了 `Chokepoints`、`Interceptor`、`SoundField`、`AbilityTiming`、`GhostPersona`、`AiMemory`、`Hotspots`、`RouteModel`、`PersonaBandit`；大腦是 Utility（`GhostBrain.decideUtility`））＋`StuckTracker`。完整說明見 `docs/AI鬼邏輯.md`，第三版計畫見 `docs/AI鬼第三版改進建議.md`。最小模擬器：`./gradlew sim --args="--map hydro_plant --runs 100 --search v3"`（`src/test/java/com/ghosthunt/sim`，比較第二版／第三版的搜尋；`--tune normal` 自動調參，只印建議值）。`Match`（主迴圈、抓人、監獄、救人、障礙物、破洞）、`Objectives`（木桶內容、電源、門禁卡、大門）、`Interactions`（站著不動的進度條互動）、`Items`/`ItemUse`（道具）、`Nightmare`（夢魘鬼熄燈）、`Hud`、`GameListener`（事件）、`GameSettings`/`ObjectiveSettings`（讀 config）。
- 地圖音效：`resourcepack/`（`./gradlew build` 打包成 `ghosthunt-pack.zip` 放進 jar）→ `ResourcePackHost`（插件自己開小網頁伺服器送資源包，`config.yml` 的 `resource-pack`）→ `game/MapAudio`＋`AudioConfig`（固定地圖的 `[sounds]`：全場環境音、依區域換環境音、地下通道、隨機事件音、電源開關聲；音檔是立體聲，所以直接在玩家身上播）。目前只有 `hydro_plant` 有自己的音效（使用者提供的水力發電廠音效包）；其他地圖播預設的恐怖白噪音（`audio.default-ambient`，`resourcepack/.../sounds/ambient/`）。逃生者的心跳是 4 段音檔（`heartbeat.stage-sounds`，`sounds/heartbeat/`）。使用者給的 mp3、wav 用 Python 的 soundfile 轉成 .ogg（Vorbis 要分小塊寫，一次寫太長在 Windows 會堆疊溢位）。
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
- 加速效果不疊加。AI 鬼不做「追擊加速」（2026-11-22 決定：鬼的速度規則不變）。
- AI 鬼沒有腳步聲（`ai-ghost.footsteps: false`），逃生者靠心跳判斷鬼多近：心跳用「鬼走過來要走的路」算距離，越近越快、越大聲（config 的 `heartbeat`；2026-11-22 決定，之後換成使用者提供的心跳音檔）。每張圖會有恐怖氛圍的白噪音（使用者提供）。
- 場景互動：門口旁可推倒的櫃子（推倒＝擋住門口，鬼要拆）、蹲下右鍵門卡門（單門、雙開門都可以，每扇一次，鬼要撞開；單門可以卡是 2026-11-22 決定的）。
- 門禁卡要先到保全室（全棟 2 間、不同樓層）的終端機授權才能刷；終端機要有電。
- PvE：2～3 人對抗 AI 鬼；地圖格局固定（`pve-maps/`，可以是室內、室外、室內＋室外），每局重蓋時大門、保全室、木桶隨機（`pve.reroll-each-match`）；AI 鬼只看得到前方、聽得到腳步和開門，太久沒接觸才感應最近的人在哪一間房（被盯上的人聽到心跳加快）；只有基本鬼的能力（不會熄燈）。AI 攻擊距離維持 2.5 格（故意比真人短，平衡感應）；手電筒照 AI 鬼的臉有效；AI 選目標只用看得到的資訊（不看誰有門禁卡）；AI 下地下通道是「撬開＋爬梯子傳送＋通道裡照路走」。
- 固定地圖 2F 也放大門候選（每局一扇 1F、一扇 2F）；2F 大門外面是陽台＋欄杆＋梯子下到地面（只是外觀，穿過大門就算逃出）。
- 度假村機關（地圖檔 `[interactions]`，`game/Gimmicks`，數值在 config 的 `gimmicks`）：迎賓鈴（遠處涼亭響廣播吸引鬼，AI 不管多遠都聽得到）、球車遠光燈（扇形強光，鬼看不到人＋緩速，每台 2 次）、溫泉蒸氣（煙裡 AI 看不到人、真人鬼失明）。路燈分區通電、2F 木棧道腳步聲不做（跟電源規則衝突／等腳步聲系統）。
- mall2 的支線（`game/SideQuests`，數值在 config 的 `side:`，說明見 `docs/支線任務候選.md`、`docs/夜間進度報告-2026-11-23.md`）：逃生路線大門＋每局隨機 1～2 條（貨梯、屋頂直升機、下水道），監控室、廣播、販賣機、配電箱、鐵捲門、急救包、雙人備用電源、今晚的規則。新地圖符號 L F P R X N M Y A Z V T U 見 `docs/PvE購物中心v2_藍圖prompt.md`。
- PvE 不用鬼房（2026-11-23 決定，`pve.ghost-room: false`）：逃生者隨機分散出生，AI 鬼出生在離所有人走路最遠的地方，開局就放出來、馬上可以抓（所以用大地圖）。PvP 之後也要一樣。待做方向見 `docs/待做建議.md`。
- 1F 底下有地下通道（地上活板門＋梯子，連到遠處另一個房間）；逃生者自由進出，鬼要先撬開出入口。每間房間地板顏色不同（認路用）。

## 目前狀態

- **目前專心開發 PvE**（2026-11-22 使用者決定）；主力地圖是 `mall2`（夜半購物城，2026-11-23 起 PvE 預設，有所有支線），`mall`、`mall_m` 保留。
- mall2 的支線只用 `/gh sidetest` 和假逃生者驗證過（路線抽選、開鎖、鐵捲門、配電箱、廣播引開 AI、AI 弄壞貨梯、下水道打開）；監視器、販賣機、急救包、雙人備用電源、實際搭貨梯／直升機／下水道逃出還要真人測。

- 整局流程只用 `selftest` 和模擬驗證過，真人互動（翻箱、刷卡、抓人、救人、道具）還在第一次實測。
- 待決定：容器要不要加倍（見 `docs/平衡模擬.md` 第二輪）—— 等實測的 `stats.log` 再說。
- 專案用 git 管理（main 分支）。兩個 Claude 同時開發時，各自開分支或 worktree，不要同時改同一個檔案；改完先 commit。
