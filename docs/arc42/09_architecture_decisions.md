# 9. 架構決策 (Architecture Decisions)

> arc42 第 9 章。上層：[文件索引](index.md)。相關：[10. 品質需求](10_quality_requirements.md) ｜ [11. 風險與技術債](11_risks_and_technical_debt.md)。

本章以 ADR（Architecture Decision Record）形式記錄 CelloCoach Android app
的關鍵架構決策。每筆 ADR 說明背景、決策、考量過的替代方案，以及取捨。

相關章節：

- 品質需求與決策的對應 → [10_quality_requirements.md](10_quality_requirements.md)
- 各決策帶來的風險與技術債 → [11_risks_and_technical_debt.md](11_risks_and_technical_debt.md)
- 名詞解釋（cents、NSDF、DTW、MusicXML…） → [12_glossary.md](12_glossary.md)
- 實作簽章與模組邊界 → [../../CONTRACTS.md](../../CONTRACTS.md)

> 背景：原始參考實作（`/home/lane/music_code/`）是「瀏覽器偵測音高 → POST 給
> Flask server」的網頁架構。Android 版的核心目標是把整條管線搬進 App 內，移除
> 原 README 花大量篇幅描述的 HTTPS / secure-context / LAN 連線痛點。下列 ADR
> 記錄這次重新設計的主要決策；ADR-004 起是之後新增功能時補上的決策。

ADR 索引：

| 編號 | 標題 | 狀態 |
|---|---|---|
| [ADR-001](#adr-001-原生-compose-canvas-樂譜渲染-而非-webview--osmd) | 原生 Compose Canvas 樂譜渲染（而非 WebView + OSMD） | 已採用 |
| [ADR-002](#adr-002-裝置端音高偵測-而非瀏覽器--flask-server) | 裝置端音高偵測（而非瀏覽器 + Flask server） | 已採用 |
| [ADR-003](#adr-003-robolectric-jvm-可執行的-uie2e-測試) | Robolectric JVM 可執行的 UI/E2E 測試 | 已採用（2026-11 補充實機驗證） |
| [ADR-004](#adr-004-麥克風只在需要時開啟-由狀態推導) | 麥克風只在需要時開啟（由狀態推導） | 已採用 |
| [ADR-005](#adr-005-練習模式-慢速與等正確-不改動移植常數) | 練習模式（慢速與等正確）不改動移植常數 | 已採用 |
| [ADR-006](#adr-006-標準音高改為-a442) | 標準音高改為 A=442 | 已採用（v1.0.8） |

---

## ADR-001: 原生 Compose Canvas 樂譜渲染 (而非 WebView + OSMD)

**狀態：** 已採用

### 背景

參考實作用 [OpenSheetMusicDisplay (OSMD)](12_glossary.md#osmd) 在瀏覽器渲染
MusicXML 五線譜，並用 JS 移動光標。要在 Android 沿用這條路，唯一選項是塞一個
`WebView`，把 OSMD（透過 CDN 或打包的 JS bundle）與一層 JS bridge 一起載入。

App 需要呈現的內容其實很受限：單聲部、第一個 part、五線譜 + 音符 + 一個隨演奏
移動的光標 + 依即時 status 上色（good / close / off / wrong）。並不需要 OSMD
的完整排版引擎。

### 決策

用 **Jetpack Compose `Canvas`（`androidx.compose.foundation.Canvas`）原生繪製**
五線譜，實作 `ui/ScoreView.kt`。資料來源是 `core/ScoreLoader.kt` 解析出的
`List<ScoreNote>` timeline。光標位置與每顆音的上色由 `PracticeViewModel`
暴露的 Compose state 驅動。

### 替代方案

1. **WebView + OSMD（被否決）** — 與參考實作一致，排版品質最好。
2. **原生 Compose Canvas（採用）** — 自行繪製受限的五線譜子集。
3. **預先把樂譜算成點陣圖** — 失去動態光標與即時上色，否決。

### 理由與取捨

採用原生 Canvas 的理由：

- **可測試性**：渲染輸入是純 Kotlin 資料（`ScoreNote`），畫面狀態是 Compose
  state，可在 [Robolectric](12_glossary.md#robolectric)（見
  [ADR-003](#adr-003-robolectric-jvm-可執行的-uie2e-測試)）下用 `testTag`
  斷言光標位置與上色，無需在 WebView 裡跑 JS、也無須 DOM 探針。
- **離線**：不依賴 CDN、不需打包並維護一份 OSMD JS bundle，App 完全離線可用。
  對應 [品質需求：離線](10_quality_requirements.md#1033-離線-offline)。
- **無 WebView**：去掉 WebView 等於去掉一整類問題 — JS bridge 的執行緒/序列化
  成本、WebView 版本破碎、context 生命週期、以及把 native 偵測結果丟進 JS 再
  回拋的延遲。對應 [品質需求：延遲](10_quality_requirements.md#1031-延遲-latency)。
- **單一渲染心智模型**：整個 UI 都是 Compose，沒有「原生 / WebView」兩套世界。

接受的取捨（記錄於
[11 章](11_risks_and_technical_debt.md#112-技術債)）：

- 我們重新實作了一個**受限的**樂譜排版器。複雜記譜（連音線、多聲部、裝飾音、
  反覆記號）不像 OSMD 那樣完整支援；目前範圍是單聲部、第一個 part。
- 排版美觀度低於 OSMD。對「練習導向」的 MVP 可接受。

---

## ADR-002: 裝置端音高偵測 (而非瀏覽器 + Flask server)

**狀態：** 已採用

### 背景

參考實作把音訊擷取與[音高偵測](12_glossary.md#音高偵測-pitch-detection)放在
**瀏覽器**（Web Audio API + [Pitchy](12_glossary.md#pitchy)），把 `{hz, rms}`
每 ~50 ms POST 給 Flask server，server 端只是個執行緒安全的最新值持有者
（`pitch_detector.py`）。

這個設計逼出原 README 花好幾段解釋的痛點：瀏覽器只在
[secure context](12_glossary.md#secure-context-安全來源) 才允許存取麥克風。
`http://localhost` 算安全，但 `http://192.168.x.y` 與雲端 IP 不算。於是使用者
被迫處理 self-signed HTTPS 憑證警告、`chrome://flags` 白名單，或在 reverse
proxy 後面終結 TLS — 全都只是為了讓平板的麥克風能開起來。

### 決策

在 **Android App 內**完成整條音訊管線，完全不需 server：

- `audio/AudioPitchSource.kt`：用 `AudioRecord` 在背景執行緒 ~20 Hz 讀取麥克風。
- `core/PitchDetector.kt`：純 Kotlin 移植 Pitchy 的 normalized autocorrelation
  / [NSDF](12_glossary.md#nsdf)（McLeod-style），含拋物線內插與 clarity 門檻。
- 兩者皆透過 `PitchSource` 介面（見 [CONTRACTS.md](../../CONTRACTS.md)），
  讓測試可注入 `audio/FakePitchSource.kt`。

### 替代方案

1. **保留瀏覽器 + Flask（被否決）** — 重用最多既有程式碼，但把 HTTPS /
   secure-context / LAN 問題一起帶進 Android。
2. **App 內偵測（採用）** — `AudioRecord` + 純 Kotlin NSDF。
3. **App 內擷取，但偵測丟雲端** — 增加網路延遲與隱私顧慮，且無法離線，否決。

### 理由與取捨

- **消滅 secure-context / HTTPS / LAN 整類問題**：App 直接用系統的
  `RECORD_AUDIO` 執行期權限拿麥克風。沒有瀏覽器來源規則、沒有憑證、沒有
  `chrome://flags`、沒有 reverse proxy。這是本決策最大的動機。
- **延遲**：音訊樣本不再跨網路往返。擷取 → 偵測 → follower/scorer 全在同一個
  process。對應 [品質需求：延遲](10_quality_requirements.md#1031-延遲-latency)。
- **離線**：完全不需網路。對應
  [品質需求：離線](10_quality_requirements.md#1033-離線-offline)。
- **可測試性**：偵測邏輯是純函式 `PitchDetector.detect(FloatArray): Float?`，
  可在 JVM 單元測試直接餵合成波形驗證。`PitchSource` 介面讓 UI/E2E 測試用
  `FakePitchSource` 餵腳本音高（見
  [ADR-003](#adr-003-robolectric-jvm-可執行的-uie2e-測試)）。
- **準確度可控**：偵測與 [tuning](12_glossary.md#tuning-校正) 校正在同一程式碼庫，
  能一致地把每弦的 cents 偏移套用到評分。對應
  [品質需求：準確度](10_quality_requirements.md#1032-準確度-accuracy)。

接受的取捨（記錄於
[11 章](11_risks_and_technical_debt.md)）：

- 麥克風品質與環境噪音現在由手機決定；吵雜房間的偵測穩定度是已知風險
  （[11 章](11_risks_and_technical_debt.md#111-風險)）。
- emulator 的虛擬麥克風無法真正收音，所以即時音準只能在實機驗證；
  CI 用 `FakePitchSource` 規避（[ADR-003](#adr-003-robolectric-jvm-可執行的-uie2e-測試)）。

---

## ADR-003: Robolectric JVM 可執行的 UI/E2E 測試

**狀態：** 已採用

### 背景

CelloCoach 的價值高度集中在「即時互動」：光標前進、回饋上色、校正流程、報告
數字。這些是畫面層行為，傳統上要用 instrumented 測試在 emulator/實機跑。但
emulator 在 [WSL2](12_glossary.md#wsl2) + devcontainer 環境下啟動慢、需要
[KVM](12_glossary.md#kvm)，且其虛擬麥克風無法提供真實音高 — 正是我們想在 CI 跑
端到端螢幕驗證時最不想依賴的東西。

### 決策

採**雙層測試策略**：

1. **Robolectric Compose UI/E2E 測試**（`app/src/test`，JVM 可執行）：
   用 `@RunWith(RobolectricTestRunner::class)` + `createComposeRule()`，
   注入 `FakePitchSource` 餵**腳本化的 `PitchFrame`**（mock 錄音），透過
   `Modifier.testTag(...)`（集中於 `ui/TestTags.kt`）斷言：校正流程推進、
   練習光標前進、回饋顏色、報告數字。這是「mock 麥克風」的端到端螢幕驗證，
   **在 devcontainer 內免 emulator 即可跑**，CI 友善。
2. **JVM 單元測試**（`app/src/test`）：`com.cellocoach.core` 各純演算法模組
   （ScoreLoader / PitchDetector / ScoreFollower / Tuning / Scorer）。
3. **選用的 instrumented 煙霧測試**（`app/src/androidTest`）：一支在真機 /
   emulator 啟動 App、驗證主畫面的測試，透過 USB 部署（見
   [README 的部署章節](../../README.md)）。

### 替代方案

1. **只有 instrumented 測試（被否決）** — 每次 CI 都要起 emulator，慢且脆弱，
   且無法驗證即時音準（虛擬麥克風）。
2. **Robolectric + FakePitchSource 為主，instrumented 為選用煙霧測試（採用）**。
3. **只有單元測試，不測畫面** — 漏掉最核心的互動行為，否決。

### 理由與取捨

- **可測試性 / CI**：把 `PitchSource`（[CONTRACTS.md](../../CONTRACTS.md)）做成
  可注入的介面後，整個練習/校正流程能在 JVM 上以決定性的腳本音高重放，
  毫秒級執行、零 emulator 依賴。對應
  [品質需求：可測試性](10_quality_requirements.md#1034-可測試性-testability)。
- **決定性**：`FakePitchSource.emit()` 讓測試逐格驅動狀態機，避免真實音訊的
  非決定性。follower 用注入的 `Clock`（見 [CONTRACTS.md](../../CONTRACTS.md)）
  取代 wall-clock，使時間相關邏輯也可被測試控制。
- **仍保留真機驗證**：instrumented 煙霧測試守住「App 真的能在裝置上啟動」這條
  底線，補上 Robolectric 不模擬的硬體/系統行為。

接受的取捨：

- Robolectric 對 Compose 的繪製是近似的（shadow 實作），像素級渲染正確性仍需
  靠人工或 instrumented 測試確認。
- USB / emulator 路徑本身有環境脆弱性（WSL2 USB passthrough），記錄於
  [11 章](11_risks_and_technical_debt.md#111-風險)。

### 補充 (2026-11)：實機驗證不寫成 instrumented 測試

在 Xiaomi／HyperOS 實機上，instrumentation 從背景啟動 Activity 會被系統擋下
（`MIUILOG- Permission Denied Activity`），`connectedDebugAndroidTest` 跑完還會把
App 解除安裝。因此實機上的端到端確認改用 adb 腳本：`scripts/phone.sh` 以
resource-id（即 `TestTags`）點擊與讀取畫面，以 `dumpsys audio` 的收音事件判斷
麥克風狀態，流程寫成專案 skill（[`device-verify`](../../.claude/skills/device-verify/SKILL.md)）。
instrumented 煙霧測試保留給 emulator。

---

## ADR-004: 麥克風只在需要時開啟 (由狀態推導)

**狀態：** 已採用（2026-11）

### 背景

最初的 `PracticeViewModel` 在第一次按「開始練習」時啟動 `PitchSource`，之後直到
ViewModel 被銷毀才停止。結果：

- 首頁、報告頁、甚至 App 退到背景時都在收音與做 NSDF 運算，耗電，系統麥克風
  指示燈一直亮著；
- 權限被拒時 `AudioRecord` 初始化失敗，`sourceStarted` 卻已設為 true，之後永遠
  不會重試，畫面也沒有任何提示，學生對著沒在聽的 App 拉完整首，報告全是 0 分；
- 背景期間 tick 迴圈繼續用最後一格舊讀值計分。

### 決策

把「要不要收音」變成**由狀態推導的結果**，在每次相關狀態改變時由單一函式
`syncMic()` 重算：

> 收音 ＝ `!micPermissionDenied` ∧ `inForeground` ∧ `screen ∈ {TUNING, PRACTICE}`

- `MainActivity` 在 onCreate / onResume / 授權結果時呼叫 `onMicPermissionChanged`，
  在 onStart / onStop（排除旋轉）時呼叫 `onForegroundChanged`；
- 所有畫面切換經 `show(screen)`，裡面呼叫 `syncMic()`；
- 停止時把 `latestFrame` 清成靜音；練習中進入背景則放棄這一輪，回前景重新倒數；
- `AudioPitchSource` 改為可安全重複 start/stop（generation 機制）。

### 替代方案

1. **維持一次啟動、永不停止（被否決）** — 即上述問題。
2. **在每個按鈕／畫面的事件裡各自 start/stop** — 分散在多處，容易漏掉某條路徑
   （例如報告頁、權限晚授予、背景），否決。
3. **用 `ProcessLifecycleOwner` 觀察前景** — 需新增 `lifecycle-process` 相依，違反
   限制 T10；由 Activity 回報即可，否決。
4. **由狀態推導（採用）**。

### 理由與取捨

- **隱私與耗電**：只有在學生真的在拉琴的畫面才收音。對應
  [品質需求 10.3.5](10_quality_requirements.md#1035-隱私與耗電-privacy--battery)。
- **正確性**：權限晚授予、從設定頁回來、從背景回來，都會自動回到正確狀態，沒有
  遺漏的路徑。
- **可測試**：`FakePitchSource.isRunning` 讓 Robolectric 直接斷言麥克風開關
  （`SessionLifecycleTest`）；實機以 `dumpsys audio` 確認。

接受的取捨：

- 練習無法「暫停」：follower 依牆鐘判斷，背景期間的時間不可信，所以直接重來。
- 每次離開 Tuning/Practice 都會停掉 `AudioRecord`，`stop()` 在主執行緒最多等
  500 ms（實際約一個框，≈ 46 ms）。

---

## ADR-005: 練習模式 (慢速與等正確) 不改動移植常數

**狀態：** 已採用（上游 PR #1 與先前的慢速練習）

### 背景

學生需要慢練難的段落，或逐音確認音準。`ScoreFollower` 的預設行為逐行移植自
`score_follower.py`（逾時 2.5 倍、lookahead 3、遲滯 5 tick），且有單元測試釘住；
直接改常數會破壞與參考實作的一致性（限制 O2）。

### 決策

- **慢速（75% / 50%）**：不改 follower，而是把交給引擎的 timeline 整體拉長
  （`engineNotes()`：`start/end ÷ tempoFactor`），倒數拍長同比例放慢；畫面上的五線譜
  不變。
  - **v1.0.9 更新**：固定的 75% / 50% 改為節拍器式自選速度「♩ = N」（30–240），
    `tempoFactor` 改由 `practiceBpm / bpm` 推導，可慢也可快；「等正確」改為獨立開關，
    可與任何速度搭配。縮放 timeline 的機制不變，所以本 ADR 的決策仍成立。
- **等正確**：在 `ScoreFollower` 加入獨立的 `waitForCorrect` 路徑（`observeWait`）：
  停在目前音，直到該音連續正確 `ADVANCE_THRESHOLD_TICKS` 次才前進；沒有逾時、
  沒有 lookahead；休止依時間前進；不播持續拍點。預設 `false`，原路徑完全不變。

### 替代方案

1. **調整 `TIMEOUT_FACTOR` 等常數做慢速** — 會改變原速練習的行為與既有測試，否決。
2. **另寫一個新的 follower 類別做等正確** — 重複「開始／結束／目前音」等共用邏輯，
   否決。
3. **拉長 timeline + 獨立路徑（採用）**。

### 理由與取捨

- 原速、預設模式的行為與 Python 參考實作保持一致，既有測試不需修改。
- 慢速時所有時間相關規則（逾時、休止、重複同音 80%）自動等比例放慢。
- 取捨：等正確模式下 `Scorer` 仍按 tick 累積，學生在一顆音上停越久，`sustain`
  越低，分數會比原速低；報告分數在兩種模式間不可直接比較。

---

## ADR-006: 標準音高改為 A=442

**狀態：** 已採用（v1.0.8，2026-11-22 由團隊成員提出）

### 背景

Python 參考實作與 v1.0.7 以前都以 A=440 為標準音高，散落在 `ScoreNote.kt`、`Scorer.kt`、
`PracticeViewModel.kt` 的 `440.0` 字面值。使用者的樂團以 A=442 調音：略過校正時，
整把琴會被判定偏高約 +7.85¢；已存的校正偏移也是相對 440 記錄的。

### 決策

- 新增 `A4_HZ = 442.0`（`core/ScoreNote.kt`），所有音高換算改用它，程式中不再有 `440.0` 字面值。
- 「略過（使用 A=442）」的當次練習以 442 為基準。
- `TuningStore` 存檔加 `a4_hz` 欄位；讀到沒有此欄的舊檔時，視為 A=440（`LEGACY_A4_HZ`），
  每弦偏移加上 `1200·log2(440/442)`。

### 替代方案

1. **只改略過時的基準，其餘維持 440** — 兩套基準並存，容易出錯，否決。
2. **讓使用者在設定裡選 440 / 442** — 目前沒有設定頁，需求只有 442；之後需要時把 `A4_HZ`
   換成可設定值即可，暫不做。
3. **舊校正直接作廢、要求重校** — 會讓使用者升級後看到「未校正」，否決。
4. **單一常數 + 舊檔換算（採用）**。

### 理由與取捨

- 已校正時，cents 是相對每弦實際音高計算的，換算後評分結果與改版前相同；改變只出現在
  略過校正、以及 MIDI 判定邊界（整體移動 7.85¢，影響極小）。
- 取捨：與 Python 參考實作的音高基準不再一致（限制 O2 的例外），以 `TuningTest`、
  `TuningStoreTest` 釘住新行為。

---

下一章：[10. 品質需求](10_quality_requirements.md)
