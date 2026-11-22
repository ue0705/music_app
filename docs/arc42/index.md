# CelloCoach 架構文件（arc42）

CelloCoach 是大提琴練習的即時音準與節奏教練，現以**原生 Android App**重新實作。本文件依 [arc42](https://arc42.org) 標準章節結構撰寫，描述系統的目標、限制、脈絡與解決策略。

> 背景：原始版本（Python 參考實作 `music_code`）是 Flask 後端 + 瀏覽器前端的 Web 架構，麥克風偵測在瀏覽器端進行，再 `POST /pitch` 給 Flask。這個架構在 LAN／行動裝置上飽受 HTTPS／secure-context／連線設定之苦。CelloCoach Android 把**全部運算搬進 App 內**，徹底移除這些痛點。實作契約（模組簽章的單一事實來源）見 [CONTRACTS.md](../../CONTRACTS.md)。

## 章節索引

| 章節 | 內容 |
|---|---|
| [01 介紹與目標](01_introduction_and_goals.md) | 系統要解決的問題（含 Android 版新增的匯入、慢練／等正確、報告匯出、權限）、品質目標、利害關係人 |
| [02 架構限制](02_architecture_constraints.md) | 技術／組織／慣例限制（Kotlin/Compose、JDK17、devcontainer 或 macOS 工具鏈、不加相依、SDK 授權、文件錨點） |
| [03 系統脈絡與範圍](03_context_and_scope.md) | 系統邊界、外部介面（麥克風、檔案選擇器、HTTPS 匯入、系統分享），與原 Web 架構的對比 |
| [04 解決策略](04_solution_strategy.md) | 核心架構決策 D1–D10，功能對 Android 模組的對應表 |
| [05 建構區塊視圖](05_building_block_view.md) | 模組分解與套件結構（core / audio / data / ui），ScoreView 版面 |
| [06 執行視圖](06_runtime_view.md) | 練習 tick、校正、練習→報告、畫面狀態、麥克風生命週期、權限、匯入／匯出 |
| [07 部署視圖](07_deployment_view.md) | devcontainer、emulator、WSL2 USB、macOS 本機工具鏈 + USB 實機（含 HyperOS 注意事項） |
| [08 橫切概念](08_crosscutting_concepts.md) | 樂譜解析、音高偵測、計分、跟譜與練習模式、調音、權限、並行、測試性、匯入匯出安全 |
| [09 架構決策 (ADR)](09_architecture_decisions.md) | ADR-001 原生 Canvas 渲染、002 裝置端偵測、003 Robolectric 測試、004 麥克風生命週期、005 練習模式 |
| [10 品質需求](10_quality_requirements.md) | 延遲、準確度（含實機實測）、離線、可測試性、隱私與耗電 |
| [11 風險與技術債](11_risks_and_technical_debt.md) | 領域限制、Android／HyperOS 環境風險、技術債清單、WSL2 實機部署路徑 |
| [12 詞彙表](12_glossary.md) | cents、MIDI、MusicXML、NSDF、key maximum、sustain、空弦、等正確模式、HyperOS 等 |

## 名詞速查

| 名詞 | 說明 |
|---|---|
| MusicXML / .mxl | 樂譜交換格式（W3C 標準）；`.mxl` 為 ZIP 壓縮版 |
| cents（音分） | 半音的 1/100，音準偏差單位 |
| score follower | 跟譜器，結合音高與時間判斷學生拉到哪一顆音 |
| NSDF | Normalized Square Difference Function，音高偵測演算法 |
| 校正（calibration） | 拉四條空弦（C G D A），以實際琴音作為評分基準 |

## 相關文件與工具

| 項目 | 用途 |
|---|---|
| [CONTRACTS.md](../../CONTRACTS.md) | 模組簽章的單一事實來源 |
| [README.md](../../README.md) | 功能總覽、建置與部署指令 |
| [CLAUDE.md](../../CLAUDE.md) | 給 Claude Code 的開發指引 |
| [`.claude/skills/device-verify`](../../.claude/skills/device-verify/SKILL.md) | Claude Code skill：建置、安裝並在 USB 實機上驗證 |
| [`.claude/skills/arc42-docs`](../../.claude/skills/arc42-docs/SKILL.md) | Claude Code skill：改程式時同步維護本文件 |
| `scripts/check-doc-links.py` | 檢查所有 Markdown 連結與錨點 |
