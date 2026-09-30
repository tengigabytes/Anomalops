# Git 工作流程

2026-09-28 · 狀態：已採用

## 1. 分支

- `main` 永遠要能建置、能通過檢查。
- 功能分支命名為 `<里程碑>/<主題>`，全部小寫英文，例如 `m1/camera-session`、`m3/dive-lock`。里程碑見 [roadmap.md](../product/roadmap.md)。
- 分支存在的時間盡量短，一個分支只做一件事。合併方式用 squash 或 rebase，保持歷史是一條直線。
- 不開常駐的 dev 分支（2026-09-30 決定）。v1.0 發布前，下一版的程式只要是新增檔案、不改 v1.0 已通過 G1 的程式路徑（相機請求、潛水鎖定畫面、寫檔），就可以合進 `main`；要接上這些路徑的改動，等 v1.0 發布前從 `main` 切出 `release/1.0` 之後再合。`release/1.0` 只收 v1.0 的修正。

## 2. Commit 訊息

用英文，格式採 [Conventional Commits](https://www.conventionalcommits.org/)：

```
feat(camera): apply depth-band WB gains to preview and still requests

Implements FR-21 and FR-91 per ADR-0002.

Signed-off-by: Name <email>
```

- **type**：`feat`、`fix`、`refactor`、`test`、`docs`、`build`、`ci`、`chore`
- **scope**：模組名稱，例如 `camera`、`profile`、`store`、`telemetry`、`app`、`probe`；或 `docs`、`adr`
- **內文**：寫明對應的 FR / NFR / ADR 編號
- **DCO**：每個 commit 都要 `git commit -s`，加上 `Signed-off-by`，表示貢獻者同意以本專案的授權釋出（[licensing.md](../release/licensing.md)）
- **AI 協作**：與 Claude Code 協作產生的 commit，保留 `Co-Authored-By` trailer；規則見 [authorship.md](authorship.md)

## 3. 完成定義

每次合併進 `main` 前都要符合：

1. `python scripts/check_limits.py`、`python scripts/check_module_deps.py` 與 `python scripts/check_device_neutral.py` 通過。
2. 建置、單元測試、`./gradlew detekt`（含 ktlint 格式）通過，而且 `play`、`foss` 兩個 flavor 都要過。CI 的 `android` 工作會執行這一項。
3. 動到的需求都有測試，或在 [mvp-acceptance.md](../product/mvp-acceptance.md) 對應的列上註明驗證方式。
4. 新增檔案已寫進目錄索引；新的架構決定已寫 ADR。
5. 如果改變了對外行為，需求文件或驗收文件已同步修改。

## 4. 版本號

- tag 用 `vX.Y.Z`（SemVer）。X.Y 對應 roadmap 的版本，例如 v1.0、v1.1。
- `versionCode = X × 10000 + Y × 100 + Z`，例如 v1.2.3 → 10203。
- 預覽版用 `vX.Y.Z-rc.N`，每發一次預覽版 Z 加 1，確保 versionCode 只增不減。

## 5. 不進 repo 的東西

`.gitignore` 已排除以下內容，也不要用 `git add -f` 強制加入：

- 金鑰：`*.jks`、`*.keystore`、`keystore.properties`
- 本機設定：`local.properties`
- 建置產物：`build/`、`.gradle/`
- 測試資料：照片、FR-45 原始紀錄
