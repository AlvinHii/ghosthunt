# GhostHunt 資源包（地圖音效）

`./gradlew build` 會把這個資料夾打包成 `ghosthunt-pack.zip` 放進插件 jar；插件啟動時自己開一個小網頁伺服器送給玩家（見 `config.yml` 的 `resource-pack`）。

- `assets/ghosthunt/sounds.json`：音效名稱（遊戲裡用 `ghosthunt:hydro.global` 這種名字）。
- `assets/ghosthunt/sounds/hydro/`：水力發電廠音效包（2026-11-21 使用者提供，OGG Vorbis，立體聲 48kHz，環境音 30 秒、事件音 5～10 秒）。

立體聲的音效在 Minecraft 不會依距離變小聲，所以插件是「看玩家在哪一區，直接在他身上播」，不是放在固定座標。
地圖要用哪些音效寫在地圖檔的 `[sounds]` 區塊（見 `docs/PvE地圖格式.md`）。
