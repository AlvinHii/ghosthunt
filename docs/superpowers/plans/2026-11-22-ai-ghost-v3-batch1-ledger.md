# SDD ledger — plan: docs/superpowers/plans/2026-11-22-ai-ghost-v3-batch1.md
Setup: Ruling: 直接在 pve-ai-ghost 分支上做（不開 worktree） — 這個分支本來就是 AI 鬼的功能分支，不是 main，使用者一直在這裡開發 — 錯了的話只是多一個分支要整理
Setup: 使用者決定第 17 項「追擊加速」不做（2026-11-22）
Pre-flight: Task 2 consumes Task 1 DistanceField.compute(nav,cost,bps,sources) — 一致
Pre-flight: Task 5 consumes DistanceField.seconds/downhill + EscaperCost.of(GridNav.Cost) — 一致
Pre-flight: Task 6 consumes VisibilityTable.known/visible(a,b,IntPredicate) + ParticleTracker API + NavMap.isMain — 一致；NavMap.nearest(Location,double) 已存在
Pre-flight: Task 8 consumes ParticleTracker.shareWithin(Collection<Integer>) — Task 5 有定義
Pre-flight: Task 9 consumes 全部純邏輯類別；SimMap 自己建 GridNav，不依賴 Bukkit — 一致
Task 1: complete (commits 41b166f..d4bc59e, tests: ./gradlew test -q → (no output))
Task 2: complete (commits d4bc59e..8d39606, tests: ./gradlew test -q → (no output))
Task 5: Ruling: bestTarget 的候選加上粒子同層 2 格內的格子 — 粒子全在看過的格子時原本會回傳 -1（退回第二版搜尋），但旁邊沒看過的地方才是該去的 — 錯了的話鬼會多走到粒子旁邊的格子，影響很小
Task 5: Ruling: bestTarget 的分數改成 Σ 粒子數 ÷（1＋距離²）（計畫寫的是 2 格內粒子數加總） — 加了候選格子以後，等權加總會讓 2 格內的格子同分、目標偏離粒子 — 錯了的話目標稍微偏向單一格的密集點
Task 5: Ruling: 先做 Task 5（純邏輯）再完成 Task 3、4 — Task 3 的實機卡住測試在背景跑 12 分鐘，Task 5 不依賴 Task 3／4 — 錯了只是 commit 順序跟計畫不同
Task 5: complete (commits 8d39606..07fd18c, tests: ./gradlew test --tests *ParticleTrackerTest -q → (no output))
Task 9: Ruling: 模擬器的鬼、逃生者、主迴圈寫在同一個 Sim.java（計畫寫三個檔） — 邏輯短又互相依賴 — 錯了只是檔案比較長
Task 9: Ruling: 「追丟」定義成 1 秒看不到（期間照真實位置追，跟 AI 鬼一樣） — 格子上的視線會閃，一閃就算追丟會讓兩版都接近 90% — 錯了的話追丟次數跟實機的定義略不同
Task 9: Ruling: 多印「平均幾秒重新找到」 — 30 秒內重新找到的比例兩版都接近上限（模擬的逃生者會亂走撞到鬼），需要第二個指標 — 無
Task 9: 結果（100 局×300 秒）：hydro 90.1→95.4%、resort 93.1→97.6%、office 95.4→96.3%；每分鐘抓 1.02→1.34、1.09→1.42、1.18→1.40；v3 三張圖都比 v2 高（驗收通過）。限制：模擬的逃生者很笨
Task 9: complete (commits 2b0a38a..3b5abe8, tests: ./gradlew test --tests *SimMapTest -q → (no output))
Task 3: Ruling: 轉角前瞻超過轉角的部分沿下一段走，切過去前確認直線留得下身體寬度；切轉角時到轉角 1 格內就換下一段 — 只在同一段上拉長瞄準點不會提早轉；不檢查會擦到桌角（第二版卡住的原因） — 錯了的話轉角變回第二版的樣子
Task 3: Ruling: 第一次卡住測試 hydro 卡住 1 次（隨機小地圖 0）：直線段、不是最後一個路點，是 2 秒內「往南 3 格又折回往北」淨位移太小的誤判；不退回，改成新路線往回走時卡住偵測重新起算，再重測 — 計畫寫「不是 0 就退回」，但這次卡住跟轉角前瞻無關 — 錯了的話要退回 Task 3 的 followPath 修改
Task 3: 重測（hydro_plant 11 normal 5 分鐘、seaside_resort 12 normal 5 分鐘，3 個假逃生者）：[AI 鬼 STUCK] 0 筆、aiStuck=0、aiTeleports=0、aiTickMsAvg 0.13／0.08 ms — 通過
Task 3: complete (commits 8d39606..4b47dd2, tests: ./gradlew test --tests *PathFollowTest -q → (no output))
Task 4/6/7/8: Ruling: AiGhost 的整合（可見性表背景建表、粒子、痕跡、證據式查暗格）放在同一個 commit — 四項都改 AiGhost 同一段（think、perceive、search），沒有互動式 staging 分不開；純邏輯各自先 commit 了 — 錯了只是 review 時一個 commit 比較大
Task 4: 實機：selftest 可見性表 hydro 2448 格 158 ms 約 1 MB、random 3490 格 199 ms 約 2 MB；對局中背景建表 101／187 ms — 通過（< 1500 ms）
Task 6: 實機（hydro_plant 11 normal 5 分鐘、random 7 normal 4 分鐘，3 個假逃生者）：卡住 0、傳送 0、沒有例外；aiLosses 22／2、aiReacquire30s 21／1；aiTickMsAvg 0.18／0.13 ms、max 19.7 ms；aiFieldMsAvg 0.53／0.68 ms — 通過
Task 7: 實機看到痕跡 DOOR（假逃生者開門）、HOLE（鬼自己打大洞被誤算）→ 修：鬼自己的記號等到狀態變成鬼要的那個才用掉（WorldTracesTest.ghostCaused_delayedChange_isNotATrace RED→GREEN）
Task 6: Ruling: 有粒子追蹤器時每次 think 把 lastSeenTick 推到現在，SEARCH_LAST_SEEN 會一直贏過 INVESTIGATE_NOISE — 聲音已經拉粒子了，粒子會帶鬼過去 — 錯了的話鬼在推理時比較不會直接衝去聽到的聲音
Task 8: Ruling: 查暗格的參考點用「真的最後看到的位置與時間」（新增 trueSeenLoc／trueSeenTick），聲音／感應時用聲音點與聽到的時間、感應點與感應的時間 — 粒子搜尋會改 lastSeen，證據要用真的 — 無
Task 4: complete (commits 4b47dd2..132d550, tests: ./gradlew test -q → (no output))
Task 6: complete (commits 4b47dd2..132d550, tests: ./gradlew test -q → (no output))
Task 7: complete (commits 4b47dd2..132d550, tests: ./gradlew test -q → (no output))
Task 8: complete (commits 4b47dd2..132d550, tests: ./gradlew test -q → (no output))
Task 10: 最後實機驗收（修正前）：hydro_plant hard 5 分鐘、seaside_resort normal 5 分鐘：卡住 0、傳送 0、沒有例外；aiTickMsAvg 0.18／0.11
Final review: 獨立審查者（opus）看完整個分支：Critical 1、Important 7、Minor 11，結論「修完再合併」
Final: fixed C1 有粒子時聲音（含迎賓鈴）最多 40 秒沒反應 — SearchRulesTest.newerNoiseFarFromParticles_letsNoiseWin／lure_alwaysWins RED→GREEN，suite 114/114
Final: fixed I1 粒子流進鬼不知道的地洞、通道吸住粒子 — EscaperCostTest.unknownHatch_isBlocked、ParticleTrackerTest.flee_doesNotTreatUnreachableAsFarthest RED→GREEN，suite 114/114
Final: fixed I2 ghostSees 沒看失明和蒸氣 — SearchRulesTest.blinded_cannotUseSight／smoke_blocksBeyondTwoBlocks RED→GREEN，suite 114/114
Final: fixed I3 可見性表把開局後放的障礙物、推倒的櫃子當透明 — VisibilityTableTest.barricade_blocksOnlyWhenPlaced RED→GREEN，suite 114/114
Final: fixed I4 追人時也做抵達減速 — PathFollowTest.chasing_noSlow（API 改動時編譯失敗＝紅）→GREEN，suite 114/114
Final: fixed I5 「之後沒聲音」被同一個 tick 的腳步吃掉 — SearchRulesTest.sameTickNoise_isStillQuiet RED→GREEN，suite 114/114
Final: fixed I6 撒粒子依賴 think 剛好落在寬限期 — SearchRulesTest.lostAfterOneSecond_onlyOnce RED→GREEN，suite 114/114
Final: fixed I7 往回走重設卡住偵測削弱指標 — StuckTrackerTest.oscillation_isNotStuck RED→GREEN（jitterInPlace_isStuck 守住真的卡住），拿掉重設，suite 114/114
Final: minor (deferred): M1 forget(id) 沒清 sensedTracker（幽靈粒子最多 40 秒，GO_SENSED 不會用到）
Final: minor (deferred): M2 chordClear 用節點中心而不是鬼的實際位置；每 tick 兩次 O(n) nearest
Final: minor (deferred): M3 粒子一條邊的成本可以超過一步的預算（地洞、拆卡門 0.5 秒走完）
Final: minor (deferred): M4 weightNear 用聲音的精確位置（沒加 noiseError 抖動），比第二版準
Final: minor (deferred): M5 效能：bestTarget O(候選×粒子格)、removeVisible 每顆粒子問一次、sense 每格 roomAt、cellsNear O(n)、lookForTraces 先讀全部門、visibilityGrid 樓梯層也拍快照
Final: minor (deferred): M6 VisibilityTable 只比完全相同的 y：樓梯格永遠看不到
Final: minor (deferred): M7 模擬器偏樂觀（逃生者逃跑規則＝粒子 flee 規則、成本跟遊戲不同）
Final: minor (deferred): M8 WorldTraces 鬼的動作被取消時記號留著；HOLE 痕跡只可能是鬼造成的
Final: minor (deferred): M9 reacquire30 不一定是同一個人；forget 沒清 lostAtTick
Final: minor (deferred): M10 gnode = -1 時 ghostField 全是無窮大，粒子 60% 時間凍結
Final: minor (deferred): M11 文件「眼前鑽進去 95%」原本因 I5 很少達成（I5 已修）
Final: 修正後實機（hydro normal 5 分鐘、resort normal 5 分鐘＋敲 3 次迎賓鈴）：卡住 0、傳送 0；敲鈴後鬼馬上 INVESTIGATE_NOISE 往涼亭走（追得到人時繼續追）— 通過
Task 10: complete (commits 41b166f..233e321, tests: ./gradlew test -q → (no output))
