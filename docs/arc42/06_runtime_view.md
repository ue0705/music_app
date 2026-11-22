# 6. 執行視圖 (Runtime View)

> arc42 第 6 章。上層：[文件索引](index.md)。相關：[5. 建構區塊視圖](05_building_block_view.md) ｜ [8. 橫切概念](08_crosscutting_concepts.md)。

本節描述 CelloCoach 的關鍵執行期情境。練習與校正迴圈比照 Python 參考實作 `main.py` 的 `_practice_tick_loop` 與 `_calibration_tick_loop`，但把原本「瀏覽器 POST → Flask SSE」的跨程序流，內聚成 App 內 `PracticeViewModel` 對 `PitchSource` 的訂閱與 50 ms tick。

`PitchSource` 的回呼只把最新一格存進 `latestFrame`（等同原版 `detector.get()` 讀最後一次 POST 的值）；tick 迴圈每 50 ms 讀取它。

## 6.1 情境一：50 ms 練習 tick 迴圈

```mermaid
sequenceDiagram
  participant MIC as "AudioPitchSource"
  participant PD as "PitchDetector"
  participant VM as "PracticeViewModel"
  participant SF as "ScoreFollower"
  participant SC as "Scorer"
  participant UI as "Practice 畫面"

  loop "每框（約 46 ms，背景 thread）"
    MIC->>PD: "detect(samples)"
    PD-->>MIC: "hz 或 null"
    MIC->>VM: "onFrame(PitchFrame) → latestFrame"
  end
  loop "每 50 ms（viewModelScope 協程）"
    VM->>VM: "detectedMidi ＝ round(69 + 12·log2(hz/440)) 或 null"
    VM->>SF: "observe(detectedMidi)"
    SF-->>VM: "currentNoteIdx() / expectedNote()"
    VM->>SC: "observe(currentNoteIdx, hz)"
    VM->>VM: "pitchStatus(hz, expected, tuning)"
    VM-->>UI: "更新 Compose state（游標、應拉 vs 你拉、cents、status 顏色）"
  end
```

要點：
- 順序固定為 **先 `follower.observe` 再 `scorer.observe`**，因 Scorer 要用 follower 剛更新的 `currentNoteIdx()` 決定樣本歸到哪顆音（對齊 `_practice_tick_loop`）。
- 即使該 tick 沒偵測到音高也要呼叫 `follower.observe(null)`，讓 hard timeout 能把卡住的音推進（見 [8.4](08_crosscutting_concepts.md#84-跟譜遲滯-hysteresis-與前進邏輯)）。「等正確」模式沒有 timeout，會一直停在該音。
- ViewModel 是 follower／scorer 的唯一寫者；Compose 端只讀。

## 6.2 情境二：校正 (Calibration) 迴圈

開練前依序校正 C → G → D → A 四條空弦。每 tick 把落在目標弦 nominal ±50¢ 內的偵測值累積進 buffer，連續 `CALIB_REQUIRED_TICKS = 30` 格（約 1.5 秒）後取平均存為該弦偏移，前進下一弦；偵測為 null 或偏離 >50¢ 則清空 buffer 重來。

```mermaid
sequenceDiagram
  participant U as "學生"
  participant MIC as "PitchSource"
  participant VM as "PracticeViewModel"
  participant TU as "Tuning（新的工作副本）"
  participant TS as "TuningStore"
  participant UI as "Tuning 畫面"

  U->>UI: "開始練習（未校正）或 重新校正"
  UI->>VM: "beginCalibration()：tuning ＝ 新的 Tuning()"
  U->>MIC: "拉目標空弦（如 C）"
  loop "每 50 ms 直到本弦穩定"
    MIC->>VM: "latestFrame"
    alt "hz 為 null 或 |cents| > 50"
      VM->>VM: "清空 calibBuffer、進度歸零"
    else "在 ±50¢ 帶內"
      VM->>VM: "calibBuffer += hz"
      VM-->>UI: "即時 cents、進度條（n / 30）"
    end
  end
  VM->>TU: "calibrateString(target, avg(calibBuffer))"
  alt "還有未校正的弦"
    VM-->>UI: "切到下一條弦"
  else "四弦皆完成"
    VM->>TS: "save(tuning)（含 saved_at）"
    VM->>VM: "savedTuning ＝ tuning 的副本"
    VM-->>UI: "進入 Practice（以新 tuning 重建 Scorer）"
  end
```

要點：
- 重新校正寫進一份**新的** `Tuning`；已存的 `savedTuning` 只在四弦全部完成時才被取代。
- 「略過（使用 A=440）」只讓**這一次**練習以 A=440 為基準；回首頁時 `goHome()` 會恢復 `savedTuning`。所以首頁「已校正」標示與「開始練習」的行為永遠一致（見 [8.6](08_crosscutting_concepts.md#86-錯誤與權限處理)）。
- 中途按左上「首頁」離開，同樣恢復已存校正。

## 6.3 情境三：練習 → 報告 (Practice → Report)

```mermaid
sequenceDiagram
  participant U as "學生"
  participant UI as "Practice 畫面"
  participant VM as "PracticeViewModel"
  participant MIC as "PitchSource"
  participant SF as "ScoreFollower"
  participant SC as "Scorer"
  participant REP as "Report 畫面"

  U->>UI: "開始練習（已校正）"
  UI->>VM: "goToPractice()"
  VM->>VM: "resetEngine()：依 tempoFactor / waitMode 建 follower 與 scorer"
  VM->>MIC: "show(PRACTICE) → syncMic() → start()"
  VM->>VM: "4 拍倒數（beatMs ＝ 60000 / (bpm × tempoFactor)，節拍器開時發聲）"
  VM->>SF: "start()"
  loop "每 50 ms 直到 isDone()"
    VM->>SF: "observe(detectedMidi)"
    VM->>SC: "observe(currentNoteIdx, hz)"
    VM-->>UI: "推進游標、更新即時回饋"
  end
  SF-->>VM: "isDone() ＝ true"
  VM->>SC: "summary()（只快照一次）"
  VM->>MIC: "show(REPORT) → syncMic() → stop()"
  VM-->>REP: "顯示總分 / 對音 X/Y / 平均 cents / 診斷 / 逐音明細"
```

要點：
- 倒數畫面一定會顯示；節拍器聲音只在「節拍器」開關打開時才響，且「等正確」模式不播持續拍點。
- `summary()` 在 `isDone()` 第一次成立時快照一次（對齊 Python `last_summary`），避免報告數字在重組間漂動。
- Practice 畫面的「重新開始」呼叫 `resetPractice()`：重建 follower 與 scorer 並重新倒數（對齊 `/reset`）。

## 6.4 畫面狀態總覽

```mermaid
stateDiagram-v2
  [*] --> Home
  Home --> Tuning : "開始練習（未校正）或 重新校正"
  Home --> Practice : "開始練習（已校正）"
  Tuning --> Practice : "四弦完成 或 略過"
  Practice --> Practice : "重新開始"
  Practice --> Report : "follower.isDone()"
  Tuning --> Home : "首頁鍵"
  Practice --> Home : "首頁鍵"
  Report --> Home : "回首頁"
```

## 6.5 情境四：麥克風生命週期與背景

收音與否不是由各個按鈕各自決定，而是每次狀態改變時由 `syncMic()` 重新推導（[ADR-004](09_architecture_decisions.md#adr-004-麥克風只在需要時開啟-由狀態推導)）：

> **收音 ＝ 有 `RECORD_AUDIO` 權限 ∧ App 在前景 ∧ 畫面是 Tuning 或 Practice**

```mermaid
sequenceDiagram
  participant OS as "Android"
  participant MA as "MainActivity"
  participant VM as "PracticeViewModel"
  participant MIC as "AudioPitchSource"

  Note over VM: "練習進行中（收音中）"
  OS->>MA: "onStop()（按 Home 鍵、切 App；旋轉螢幕除外）"
  MA->>VM: "onForegroundChanged(false)"
  VM->>VM: "放棄這一輪：取消倒數與拍點、resetEngine()"
  VM->>MIC: "syncMic() → stop()，latestFrame 清為靜音"
  OS->>MA: "onStart()"
  MA->>VM: "onForegroundChanged(true)"
  VM->>MIC: "syncMic() → start()"
  VM->>VM: "startCountdown()：從第一顆音重新開始"
```

- 練習中無法「暫停」：follower 依時間判斷，背景期間的時間不可信，因此直接重來。
- 校正中切到背景只清空目前這條弦的 buffer，回來後繼續同一條弦。
- 實機驗證（Redmi 13C 5G，HyperOS 2.0）：首頁、報告頁、按 Home 鍵時系統 `dumpsys audio` 立即記錄 `rec stop`；回到 App 重新倒數且游標回到「音 1 / N」。

## 6.6 情境五：麥克風權限

```mermaid
sequenceDiagram
  participant U as "學生"
  participant MA as "MainActivity"
  participant VM as "PracticeViewModel"
  participant UI as "CelloCoachApp"

  MA->>VM: "onCreate：onMicPermissionChanged(目前是否已授權)"
  MA->>U: "首次啟動且未授權 → 系統授權視窗"
  U-->>MA: "不允許"
  MA->>VM: "onMicPermissionChanged(false)"
  VM-->>UI: "micPermissionDenied → 每個畫面頂端顯示橫幅"
  Note over VM: "進入 Tuning / Practice 也不會啟動收音"
  U->>UI: "按「授權麥克風」"
  UI->>MA: "onRequestMicPermission()"
  alt "系統仍可詢問"
    MA->>U: "再次顯示授權視窗"
  else "已選「不再詢問」"
    MA->>U: "開啟 App 的系統設定頁"
  end
  U-->>MA: "在設定頁授權後返回"
  MA->>VM: "onResume：onMicPermissionChanged(true)"
  VM-->>UI: "橫幅消失；若在 Tuning / Practice 立即開始收音"
```

## 6.7 情境六：匯入樂譜與匯出報告

```mermaid
sequenceDiagram
  participant U as "學生"
  participant HOME as "Home 畫面"
  participant VM as "PracticeViewModel"
  participant SL as "ScoreLoader"
  participant LIB as "ScoreLibrary"
  participant REP as "Report 畫面"

  alt "從檔案匯入"
    U->>HOME: "選檔（OpenDocument）"
    HOME->>VM: "importScore(顯示名稱, bytes)"
  else "從網址匯入"
    U->>HOME: "輸入 HTTPS 網址"
    HOME->>VM: "importFromUrl(url)：IO 執行緒下載"
    VM->>VM: "importScore(url, bytes)"
  end
  VM->>SL: "load(bytes)：解析失敗或沒有音符 → 匯入失敗訊息"
  VM->>LIB: "save(sanitize(名稱), bytes)"
  VM-->>HOME: "重新整理曲目清單並選取新曲，顯示「已匯入：… (N 音)」"
  U->>REP: "練習完成後按「匯出報告」"
  REP->>VM: "buildReportFile()：ReportHtml → cacheDir/reports/report_*.html"
  REP->>U: "FileProvider URI + ACTION_SEND（text/html）分享給老師"
```
