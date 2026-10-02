# Anomalops 專案規則

Pixel 潛水相機 APP（Android，Kotlin，Camera2）。目前階段：M2 RAW 與連拍完成（FR-16 待 `dng_validate`／darktable，FR-91 待灰卡）；v1.1 取景架構第一、二階段實測完成（ADR-0011，提議，`docs/test/m7-logical-zoom.md`；錄影固定 30 fps）；v1.1 不需手機的純邏輯已合進 main（`docs/product/early-logic.md`）；微距景深合成已規劃（FR-33、FR-36、FR-37，ADR-0012–0015 提議），T1、T2、T3、T4 已完成，T9 部分完成，下次做斜放直尺（T7、T10、重驗 T9，`docs/test/macro-stacking-test-plan.md` 第 4 節）；M4 陸上部分完成（FR-84、NFR-9、FR-35、FR-31；FR-45 待 M3 接上潛水鎖定，FR-82 待紅外相機）；M3 G1 已測完（`docs/test/m3-instrumented.md`；2026-09-30 新增連續崩潰不重啟、系統解除固定視同解鎖，已實作、不做實機測試；FR-51 90 分鐘與過熱門檻併入 G2，NFR-5 目視延到 UI 整理後），PR #1 已於 2026-09-30 合併。M9 前置的多幀運算走 GPU（ADR-0017）：第 1–3 步完成（`:core:gpu`，32 位元版與 CPU 逐位元相同），第 4 步 FR-17 上 GPU 進行中：RAW 到 ARGB 全程上 GPU、與 CPU 相同，5 張 0.91–1.09 s（上限 3 s）；輸出先用半尺寸（`docs/test/m9-gpu-fr17.md`，PR #32 已合併）；第 5 步 FR-33 候選 A、B 與 `StackGuard` 上 GPU、與 CPU 相同，6 張 RAW 到 ARGB 0.68–0.96 s（`docs/test/m9-gpu-fr33.md`，PR #33）；第 6 步 T11 記憶體陸上量完，最高約 675 MB、沒有崩潰（`docs/test/m9-gpu-memory.md`）。M3 畫面只是操作邏輯的測試版：先完善底層核心功能，UI 之後持續改進。

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
- `./gradlew detekt`（含 ktlint 格式）：函式 60 行、類別 250 行、每行 120 字元；`--auto-correct` 可自動修正格式。
- `python scripts/check_module_deps.py`：模組依賴規則（ADR-0007）。
- `python scripts/check_device_neutral.py`：產品程式不寫死型號，型號差異只放 `assets/device-profiles/`（NFR-9）。
- 文件 300 行或 20 KB；根目錄 `CLAUDE.md` 60 行、子目錄 30 行。
- 超過上限就拆檔，不要調高上限。

## 結構
- 模組：`:app`、`:core:camera`、`:core:gpu`、`:core:imaging`、`:core:profile`、`:core:store`、`:core:telemetry`、`:tools:probe`（ADR-0007、ADR-0016、ADR-0017）。
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
