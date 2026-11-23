# 3. 系統脈絡與範圍

> arc42 第 3 章。上層：[文件索引](index.md)。相關：[1. 介紹與目標](01_introduction_and_goals.md) ｜ [2. 架構限制](02_architecture_constraints.md) ｜ [4. 解決策略](04_solution_strategy.md)。

本章界定 CelloCoach Android App 的邊界：哪些在系統內、哪些是外部、以及與原始 Web 架構的根本差異。

## 3.1 業務脈絡

CelloCoach 是一支**自給自足、單一裝置**的 App。沒有後端、沒有跨裝置通訊；練習流程（收音、偵測、跟譜、計分、校正）完全離線。對外互動只有三個由使用者主動觸發的出入口：從檔案或網址匯入樂譜、把報告分享出去。

```mermaid
graph TD
  Student["大提琴學生 <br/> 演奏 ＋ 操作 App"]
  Teacher["老師"]
  App["CelloCoach Android App"]
  Cello["大提琴 <br/> 聲音"]
  Mic["裝置麥克風 <br/> AudioRecord"]
  Assets["內建樂譜 <br/> assets: g_major_scale.musicxml, twinkle.mxl"]
  Picker["系統檔案選擇器 <br/> OpenDocument"]
  Web["HTTPS 網址 <br/> .musicxml / .mxl"]
  Store["裝置儲存 <br/> filesDir: tuning.json, scores/"]
  Share["系統分享 <br/> ACTION_SEND"]

  Student -->|"拉琴"| Cello
  Cello -->|"聲波"| Mic
  Mic -->|"音訊樣本"| App
  Student -->|"選曲 / 匯入 / 開始 / 看報告"| App
  App -->|"五線譜 / 即時回饋 / 報告 / 節拍器"| Student
  Assets -->|"樂譜 bytes"| App
  Picker -->|"使用者選的檔案"| App
  Web -->|"使用者輸入的網址"| App
  App -->|"讀寫校正、匯入曲目"| Store
  App -->|"report_*.html"| Share
  Share -->|"Email / 通訊軟體"| Teacher
```

### 外部介面

| 外部實體 | 方向 | 介面 | 內容 |
|---|---|---|---|
| 學生 | 雙向 | 麥克風 + 觸控螢幕 | 演奏聲音輸入；選曲、匯入、練習設定、開始、校正、看報告 |
| 大提琴 + 麥克風 | 入 | `AudioRecord`（44.1 kHz、每框 2048 樣本 ≈ 21 Hz），需 `RECORD_AUDIO` | PCM 音訊樣本 → 音高偵測；只在校正／練習畫面且前景時開啟 |
| 內建樂譜 | 入 | App assets（僅 `.musicxml` / `.mxl` 副檔名） | 樂譜 bytes，解析為音符 timeline |
| 系統檔案選擇器 | 入 | `ActivityResultContracts.OpenDocument` | 使用者挑選的 `.musicxml` / `.mxl` / `.xml` |
| 網址 | 入 | `HttpURLConnection`（連線 10 s、讀取 15 s、跟隨轉址），需 `INTERNET` | 下載的樂譜 bytes；Android 9+ 預設只允許 HTTPS |
| 裝置檔案系統 | 雙向 | `filesDir/tuning.json`、`filesDir/scores/`、`cacheDir/reports/` | 四弦校正、匯入的曲目、待分享的報告 |
| 系統分享 | 出 | `FileProvider`（`${applicationId}.fileprovider`）+ `ACTION_SEND text/html` | 單一 HTML 練習報告 |

**沒有的外部介面**：沒有 HTTP server、沒有瀏覽器、沒有 SSE、沒有 `POST /pitch`、沒有任何背景網路呼叫或遙測。網路只在使用者按「從網址匯入」時使用一次。

## 3.2 技術脈絡：與原始 Web 架構對比

原始版本（`/home/lane/music_code/`）把運算拆在瀏覽器與 Flask 之間，這正是 README 中所有連線痛點的根源。

### 原始架構（Flask + 瀏覽器）

```mermaid
graph TD
  Browser["瀏覽器（手機/平板/電腦） <br/> Web Audio + Pitchy 偵測 <br/> OSMD 渲染五線譜"]
  Flask["Flask server <br/> follower / scorer / tuning <br/> Server-Sent Events"]
  TLS["HTTPS / secure-context 障礙 <br/> self-signed 憑證、chrome flags、reverse proxy"]

  Browser -->|"POST /pitch hz,rms"| Flask
  Flask -->|"SSE 狀態推送"| Browser
  Browser -.->|"麥克風需安全來源 <br/> http://192.168.x.y 不算安全"| TLS
```

痛點（README 整段說明）：瀏覽器僅允許在「安全來源」存取麥克風，`http://localhost` 算安全但 `http://192.168.x.y` 與雲端 IP **不算**。從 LAN／行動裝置使用時，學生被迫在三條繞道中擇一——加 `--ssl` 跑 self-signed HTTPS（再手動略過瀏覽器警告）、改 `chrome://flags/#unsafely-treat-insecure-origin-as-secure` 加白名單、或把 server 放在 reverse proxy（caddy/nginx/cloudflare tunnel）後終結 TLS。

### Android 架構（單一 App，全部 on-device）

```mermaid
graph TD
  subgraph APP["CelloCoach App（單一程序，離線）"]
    UI["ui：Compose 畫面 + PracticeViewModel"]
    Audio["audio：AudioPitchSource（AudioRecord）"]
    Core["core：ScoreLoader / PitchDetector <br/> ScoreFollower / Scorer / Tuning"]
    Data["data：TuningStore / ScoreLibrary（filesDir）"]
  end
  Mic["裝置麥克風"]
  Mic --> Audio --> Core --> UI
  Core --> Data
```

麥克風擷取、音高偵測、跟譜、計分、校正、UI 渲染全在同一程序內，以函式呼叫與 Kotlin 協程連接，取代原本的 HTTP/SSE。**因此 HTTPS／secure-context／LAN 連線設定的整類問題從架構上消失**——麥克風存取只需一次 Android `RECORD_AUDIO` 執行期權限。

### 對應表

| 面向 | 原始 Web 版 | Android 版 |
|---|---|---|
| 音高偵測位置 | 瀏覽器（Pitchy + Web Audio） | App 內 `core/PitchDetector`（NSDF）+ `audio/AudioPitchSource` |
| 跟譜／計分／校正 | Flask server | App 內 `core` 模組 |
| 五線譜渲染 | OpenSheetMusicDisplay（CDN） | 原生 Compose `ScoreView` |
| 元件間通訊 | HTTP `POST /pitch` + SSE | 程序內函式呼叫 + 協程 / StateFlow |
| 樂譜解析 | music21 | `javax.xml` DocumentBuilder（自解 `.mxl` ZIP） |
| 校正儲存 | `~/.cello-practice/tuning.json` | app `filesDir/tuning.json` |
| 網路需求 | 需要（LAN/HTTPS 設定） | 練習不需要；僅「從網址匯入」時使用 |
| 麥克風授權 | 瀏覽器安全來源規則 | Android `RECORD_AUDIO` 權限；被拒時顯示橫幅可重試 |

## 3.3 範圍

**範圍內**：MusicXML 載入（含 `.mxl`）與匯入（檔案、HTTPS 網址）、即時音高偵測、DTW 風格單向跟譜、「等正確」逐音模式與節拍器式練習速度（♩ = N）、逐音計分與自動診斷、四弦校正與存檔、依樂譜拍號與曲中變速的倒數與可開關的節拍器、節奏錯誤（搶拍／拖拍）即時警示、原生五線譜與即時回饋 UI、練習報告與 HTML 匯出。

**範圍外**（沿用原版已知限制）：跟譜僅能往前（學生回頭重拉前段不會倒回）；指法對應簡化（高把位一律算最高開弦套校正偏移）；只追蹤第一個 part，且只支援單聲部（多聲部、連結線、裝飾音、反覆記號支援有限，見 [8.1](08_crosscutting_concepts.md#81-樂譜解析-musicxml)）；無 OMR（只支援已有 MusicXML 的曲目）；校正 JSON 無 schema 版本管理；匯入的曲目目前無法在 App 內刪除。

---

模組如何各司其職，見 [4. 解決策略](04_solution_strategy.md)。
