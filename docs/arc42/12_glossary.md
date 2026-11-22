# 12. 詞彙表 (Glossary)

> arc42 第 12 章。上層：[文件索引](index.md)。

本章定義 CelloCoach 文件中使用的領域與技術名詞。其他章節以連結指回此處。

相關章節：
[09 架構決策](09_architecture_decisions.md) ｜
[10 品質需求](10_quality_requirements.md) ｜
[11 風險與技術債](11_risks_and_technical_debt.md) ｜
[CONTRACTS.md](../../CONTRACTS.md)

## 領域名詞 (音樂 / 音準)

### cents

音高間距的對數單位。一個半音 = 100 cents，一個八度 = 1200 cents。
公式：`cents = 1200 × log2(f / f_ref)`（見 `core/Pitch.kt` 的 `centsBetween`）。
CelloCoach 用 cents 表示「你拉的音」與「應拉的音」的偏差，並驅動 status 門檻
（good <20¢、close <50¢、off ≥50¢）。

### intonation (音準)

演奏者把音拉到正確音高的準確程度。CelloCoach 以每顆音的 cents 偏差衡量
intonation，並在報告中彙總（平均 cents、系統性偏移診斷）。見
[10.3 準確度](10_quality_requirements.md#1032-準確度-accuracy)。

### MIDI

音高的整數編號標準。`midi = (octave+1)×12 + step半音 + alter`，A4=69、C2=36。
CelloCoach 內部以 MIDI 整數比對「應拉/你拉」是否為同一顆音（不同 = WRONG）。
見 `core/ScoreNote.kt`、`midiToHz` / `hzToMidi`（`core/Pitch.kt`）。

### MusicXML

樂譜交換的業界標準（W3C 規格），副檔名 `.xml` / `.musicxml`，壓縮版為
[.mxl](#mxl)。CelloCoach 用 JDK `javax.xml`（DocumentBuilder）自行解析，**不用
music21**，只取第一個 part（單聲部）。見 `core/ScoreLoader.kt`、
[CONTRACTS.md](../../CONTRACTS.md)。

### .mxl

壓縮版 MusicXML，本質是 ZIP（magic bytes `PK`）。`ScoreLoader` 以 magic bytes
偵測並解壓，讀 `META-INF/container.xml` 的 rootfile 取出主譜 XML — 不被被竄改的
副檔名騙到。

### sustain (延音 / 持音)

一顆音被穩定、正確地維持的程度。CelloCoach 的 `NoteResult.sustain` 是該音窗內
「偵測到的 MIDI 等於譜上音」的 tick 比例（`correctSamples / samples`），佔每音分數的一半；
`sustain > 0.5` 即算「對音」。見 [8.3](08_crosscutting_concepts.md#83-計分數學-sustain--intonation)。

### open string (空弦)

不按弦、直接拉空弦發出的音。大提琴四條空弦為 C2 / G2 / D3 / A3
（MIDI 36 / 43 / 50 / 57）。CelloCoach 的[校正](#tuning-校正)讓使用者依序拉
C→G→D→A 四條空弦，建立每弦的 cents 基準。見 `core/Tuning.kt`。

### tuning (校正)

把評分基準從硬綁標準音高（A=442，`A4_HZ`）改成「使用者這把琴的實際空弦音高」的流程。
開練前拉四條空弦，記錄每弦相對 nominal 值的 cents 偏移，後續評分時依音所在的
弦扣掉該偏移。校正結果存於 `filesDir/tuning.json`（含 `savedAt`）。見
`core/Tuning.kt`、`data/TuningStore.kt`，及
[10.3 準確度](10_quality_requirements.md#1032-準確度-accuracy)。

### score follower (跟譜器)

把「演奏者目前拉到樂譜的哪一顆音」即時定位的元件。CelloCoach 的 follower 結合
偵測音高與經過時間：命中當前音則停留，命中前方 1..LOOKAHEAD 顆並持續
ADVANCE_THRESHOLD_TICKS 才前進，超過 TIMEOUT_FACTOR×音長則強制前進。
**只能往前**（forward-only），見
[11 章風險](11_risks_and_technical_debt.md#111-風險)、`core/ScoreFollower.kt`。

### 等正確模式 (wait-for-correct)

Android 版新增的練習模式：游標停在目前音，直到**這顆音**連續正確
`ADVANCE_THRESHOLD_TICKS`（5）個 tick 才前進，沒有逾時、沒有 lookahead；休止符照
時間前進。適合逐音慢練。見 `ScoreFollower(waitForCorrect = true)`、
[ADR-005](09_architecture_decisions.md#adr-005-練習模式-慢速與等正確-不改動移植常數)。

## 技術名詞 (演算法 / 工具 / 環境)

### 音高偵測 (pitch detection)

從音訊波形估出基頻（Hz）的過程。CelloCoach 在裝置端以純 Kotlin 實作
（[NSDF](#nsdf) / autocorrelation），移植自瀏覽器的 [Pitchy](#pitchy)。見
[ADR-002](09_architecture_decisions.md#adr-002-裝置端音高偵測-而非瀏覽器--flask-server)、
`core/PitchDetector.kt`。

### NSDF

Normalized Square Difference Function，McLeod Pitch Method（MPM）使用的
正規化自相關函式。CelloCoach 取最高 peak（過 clarity 門檻 0.9），以拋物線內插
求精確 lag，再 `hz = sampleRate / lag`。超出 [fmin,fmax] 或 RMS 過低回 null。
見 `core/PitchDetector.kt`。

### key maximum (NSDF 峰值)

NSDF 在每個正值區段內的最高點。McLeod 方法先跳過 lag 0 的主瓣，再從這些峰值中
挑第一個 ≥ 0.8 × 最大峰者作為週期，藉此避免八度誤判。CelloCoach 只讓
`[minLag, maxLag]` 內的 lag 成為候選。見 [8.2](08_crosscutting_concepts.md#82-音高偵測演算法)。

### DTW

Dynamic Time Warping，動態時間規整 — 一種對齊兩條時序（如演奏與樂譜）的演算法，
容忍拉快/拉慢的速度差異。CelloCoach 的跟隨採「DTW 風格」的音高+時間混合策略
（forward-only 的近似，非完整 DTW），讓學生拉慢、暫停、跳音仍跟得上。見
[score follower](#score-follower-跟譜器)。

### Pitchy

瀏覽器端的 JS 音高偵測函式庫（normalized autocorrelation），參考實作所用。
Android 版以純 Kotlin 重新實作其演算法，移除瀏覽器依賴。見
[ADR-002](09_architecture_decisions.md#adr-002-裝置端音高偵測-而非瀏覽器--flask-server)。

### OSMD

[OpenSheetMusicDisplay](https://opensheetmusicdisplay.org) — 在瀏覽器渲染
MusicXML 五線譜的 JS 函式庫，參考實作所用。Android 版改以原生 Compose Canvas
自繪，去掉 WebView。見
[ADR-001](09_architecture_decisions.md#adr-001-原生-compose-canvas-樂譜渲染-而非-webview--osmd)。

### Robolectric

讓 Android 框架類別能在 JVM（無 emulator/裝置）下執行的測試框架。CelloCoach
用它跑 Compose UI/E2E 測試（注入 `FakePitchSource`），在 devcontainer/CI 免
emulator 驗證螢幕行為。見
[ADR-003](09_architecture_decisions.md#adr-003-robolectric-jvm-可執行的-uie2e-測試)。

### WSL2

Windows Subsystem for Linux 2 — 在 Windows 上跑 Linux 核心的環境，本專案
devcontainer 的常見宿主。實機 USB 需透過 `usbipd-win` 轉發進 WSL2，連線較脆弱。
見 [11 章風險](11_risks_and_technical_debt.md#111-風險)、
[scripts/deploy-usb.sh](../../scripts/deploy-usb.sh)。

### KVM

Kernel-based Virtual Machine — Linux 的硬體虛擬化，Android emulator 加速所需。
devcontainer 以 `--device=/dev/kvm` 啟動才能跑 headless emulator。見
[run-emulator-tests.sh](../../scripts/run-emulator-tests.sh)。

### HyperOS / USB 偵錯 (安全設定)

Xiaomi 手機的系統（前身 MIUI）。預設禁止 adb 模擬點擊（`INJECT_EVENTS`）與
`pm grant/revoke`，要在開發者選項打開「USB 偵錯（安全設定）」並執行 `adb usb`
讓 adbd 套用；每次 USB 安裝也需在手機上確認。見
[7.5](07_deployment_view.md#75-macos-本機工具鏈--usb-實機路徑)。

### uiautomator

Android 內建的 UI 檢視工具。`uiautomator dump` 輸出目前畫面的元素樹（含
`resource-id` 與座標）。CelloCoach 開啟 `testTagsAsResourceId`，所以 `TestTags`
字串就是 resource-id，`scripts/phone.sh` 依此點擊與讀值。

### FileProvider

AndroidX 元件，把 App 私有檔案以 `content://` URI 暫時授權給其他 App 讀取。
CelloCoach 只開放 `cacheDir/reports/`，用來分享 HTML 練習報告。見
[8.9](08_crosscutting_concepts.md#89-匯入匯出與檔案安全)。

### secure context (安全來源)

瀏覽器只在「安全來源」（HTTPS 或 `http://localhost`）才允許存取麥克風等敏感
API。`http://192.168.x.y` 與雲端 IP **不算**安全來源 — 這正是參考實作被迫處理
self-signed HTTPS / `chrome://flags` / reverse proxy 的根因。Android 版用系統
`RECORD_AUDIO` 權限直接取得麥克風，**徹底消除**此類問題。見
[ADR-002](09_architecture_decisions.md#adr-002-裝置端音高偵測-而非瀏覽器--flask-server)。

---

上一章：[11. 風險與技術債](11_risks_and_technical_debt.md) ｜
回到 [9. 架構決策](09_architecture_decisions.md)
