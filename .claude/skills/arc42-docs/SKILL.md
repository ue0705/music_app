---
name: arc42-docs
description: Keep CelloCoach's architecture documentation (docs/arc42, README, CONTRACTS.md, CLAUDE.md) in step with the code, in arc42 format. Use whenever a change adds or alters a feature, module, public signature, runtime flow, deployment/test path, risk or design decision, or when asked to "update the docs / arc42 / README / CONTRACTS".
---

# 維護 arc42 架構文件（arc42-docs）

CelloCoach 的設計文件放在 [`docs/arc42/`](../../../docs/arc42/index.md)，依 [arc42](https://arc42.org) 的 12 章結構撰寫。
改程式碼時，相關章節要**在同一個 commit 一起更新**。

## 1. 改了什麼 → 更新哪裡

| 程式碼變動 | 要更新的文件 |
|---|---|
| 新功能／使用者可見行為 | 01（G 表「延伸目標」）、03（外部介面／範圍）、04.2（功能→模組對應）、README「功能總覽」 |
| 新模組、類別或公開簽章 | 05（建構區塊圖與表）、**CONTRACTS.md**（簽章的單一事實來源） |
| 執行流程（tick、畫面切換、生命週期） | 06（sequence／state 圖）、08（相關橫切概念） |
| 演算法或常數 | 08（公式與流程圖）、12（新名詞）；有實測數據就寫進 10 的品質情境 |
| 建置、測試、部署方式 | 07、CLAUDE.md、README；必要時更新 `.claude/skills/device-verify` |
| 重要且不易反悔的取捨 | 09 新增 ADR（背景／決策／替代方案／理由與取捨／狀態），並在 10、11 交叉引用 |
| 新的已知限制、環境陷阱 | 11（風險或技術債表格，寫清楚影響與緩解） |

## 2. 格式規範

- **語言**：繁體中文；程式識別字、檔名、指令維持原文並加反引號。
- **章節頁首**：`# N. 標題`，下一行是 arc42 導覽列（上層 index、相關章節）。小節編號 `## N.M`、`### N.M.K`，不跳號。
- **ADR**：`## ADR-00X: 標題`（**半形冒號 + 空格**），內含 `### 背景`、`### 決策`、`### 替代方案`、`### 理由與取捨`，並加進 ADR 索引表。
- **Mermaid**：維持 GitLab／GitHub 相容語法；節點文字含空白或符號時用 `"..."` 包起來，換行用 `<br/>`。
- **表格優先**：限制、風險、對應關係都用表格；每列寫「是什麼／影響／怎麼處理」。

## 3. 錨點規則（最常壞的地方）

GitHub 產生錨點時會轉小寫、空白轉 `-`、**刪掉全形標點**（`（）：`）與 `. + / ,` 等符號。所以：

- 標題中的括號請用**半形加空格**：`### 8.1 樂譜解析 (MusicXML)` → `#81-樂譜解析-musicxml`。
  全形 `（MusicXML）` 會變成 `#81-樂譜解析musicxml`，和慣用連結對不上。
- ADR 標題用半形冒號：`ADR-002: 裝置端音高偵測 (而非瀏覽器 + Flask server)` → `#adr-002-裝置端音高偵測-而非瀏覽器--flask-server`。
- 改了標題，就要搜尋並修正所有指到它的連結：`grep -rn "#舊錨點" docs README.md CLAUDE.md CONTRACTS.md .claude`。

## 4. 完成前檢查

```bash
python3 scripts/check-doc-links.py      # 檢查所有 .md 的相對連結與錨點是否存在
```

- 輸出必須是 `0 broken`。
- 圖中的類別名、常數（例如 `ADVANCE_THRESHOLD_TICKS=5`）要和程式碼一致；不確定就 `grep` 原始碼確認。
- 只寫已實作或已實測的內容；尚未驗證的寫進 11 的風險，並標明「未驗證」。
