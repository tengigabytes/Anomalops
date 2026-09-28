# Anomalops 專案規則

Pixel 潛水相機 APP（Android，Kotlin，Camera2）。目前階段：M0 進行中（專案骨架已可建置；下一步是能力偵測工具）。

## 語言
- 與使用者討論：臺灣正體中文。
- 程式碼、註解、KDoc、log、測試名稱、commit 訊息：英文。
- 文件：臺灣正體中文為正本；英文翻譯用 `.en.md` 後綴。細節見 `docs/dev/language.md`。

## 讀檔順序（控制 context）
1. 先讀 `docs/README.md` 或目標目錄的 `README.md` 索引，再讀單一目標檔案。
2. 找需求用 grep 搜尋編號：`grep -rn "FR-62 " docs/product/requirements/`。不要整批讀取需求目錄。
3. 不要讀 `LICENSE` 全文、建置產物、金鑰、`tools/probe/results/*.json`；這些已在 `.claude/settings.json` 中禁止讀取。偵測報告用 python 查詢需要的欄位。

## 硬性上限
- `python scripts/check_limits.py` 檢查：Kotlin 原始檔 300 行、測試檔 400 行。
- detekt / ktlint 檢查（M0 起）：函式 60 行、每行 120 字元。
- 文件 300 行或 20 KB；根目錄 `CLAUDE.md` 60 行、子目錄 30 行。
- 超過上限就拆檔，不要調高上限。

## 結構
- 模組：`:app`、`:core:camera`、`:core:profile`、`:core:store`、`:core:telemetry`、`:tools:probe`（ADR-0007）。
- 建置 flavor：`play`（Play Billing 樂捐）、`foss`（無專有相依）（ADR-0010）。
- 每個目錄都有 `README.md` 索引；新增、刪除、改名檔案時同步更新。
- 原始檔開頭兩行 SPDX 標頭，格式見 `docs/dev/code-structure.md` 第 4 節。

## 分工
- 需求、決策、審閱：維護者 Terry Wang。Claude 依指令撰寫程式碼與文件草稿。
- 不自行擴大範圍或做架構決定；需要時提出 ADR 草稿，由維護者決定。
- 回報時列出未驗證的推測；有 Claude 撰寫內容的 commit 附 `Co-Authored-By`（`docs/dev/authorship.md`）。

## 決策與範圍
- v1.0 範圍已凍結：`docs/product/mvp-scope.md`。範圍外的功能不要順手做。
- 架構決策：`docs/adr/`。已採納的 ADR 只能補註或另寫新 ADR 取代。
- 需要實機驗證的推測，在程式碼中標記 `// UNVERIFIED(G0):`，在文件中標示「推測」。

## Git
- 分支 `<里程碑>/<主題>`；Conventional Commits；`git commit -s`（DCO）。
- repo 是公開的：使用者沒有明確要求，就不要 commit 或 push。
- 金鑰、測試照片、FR-45 原始紀錄不進 repo。

## 完成定義
見 `docs/dev/git-workflow.md` 第 3 節。
