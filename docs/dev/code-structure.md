# 目錄結構與長度上限

2026-09-28 · 狀態：已採用

目標：任何一次工作都只需要讀少數幾個小檔案。這對人和 Claude Code 都一樣，檔案越小，讀錯範圍的代價越低。

## 1. 頂層目錄

```
Anomalops/
├─ CLAUDE.md            Claude Code 的專案規則（≤ 60 行）
├─ README.md            英文專案介紹；README.zh-TW.md 為中文版
├─ LICENSE  NOTICE.md   授權（GPL-3.0-or-later + 附加許可）
├─ docs/                所有文件，見 docs/README.md
├─ app/                 :app，UI、潛水鎖定、flavor 相關程式（ADR-0007、0010）
├─ core/                :core:camera、:core:profile、:core:store、:core:telemetry
├─ tools/probe/         :tools:probe 能力偵測工具；results/ 放原始偵測報告
├─ assets/device-profiles/  各型號能力表與校正表（ADR-0003），由 :core:profile 打包
├─ scripts/             開發用腳本：check_limits.py、check_module_deps.py 等
├─ config/detekt/       detekt 設定（只放覆寫預設值的部分）
├─ .github/workflows/   CI；目前執行 check_limits.py，M0 起加入 Android 建置與測試
└─ .claude/             Claude Code 的專案設定
```

模組的切分與依賴規則見 ADR-0007。新增頂層目錄要先改本節。

## 2. 模組內的結構

- 每個模組根目錄都有 `README.md`，不超過 40 行，內容包括：職責、對外介面、依賴哪些模組、對應的 ADR 與需求編號。
- 套件依功能切分，不依型別切分：用 `camera/session/`、`camera/exposure/`，不用 `camera/utils/`、`camera/models/`。
- 一個套件目錄超過 12 個原始檔，就拆成子套件。
- 禁止建立 `util`、`common`、`helper`、`misc` 這類無法說明用途的套件名稱。

## 3. 長度上限

| 對象 | 建議 | 上限（CI 擋下） | 檢查工具 |
| --- | --- | --- | --- |
| Kotlin / Java 原始檔 | 200 行 | 300 行 | `check_limits.py` |
| 測試檔 | 300 行 | 400 行 | `check_limits.py` |
| 函式 | 40 行 | 60 行 | detekt `LongMethod` |
| 類別 | 200 行 | 250 行 | detekt `LargeClass` |
| 函式參數 | 5 個 | 6 個 | detekt `LongParameterList` |
| 每行字元 | | 120 | ktlint、`.editorconfig` |
| Gradle 建置檔 | 100 行 | 150 行 | `check_limits.py` |
| 腳本（py / sh） | 150 行 | 200 行 | `check_limits.py` |
| 文件（md） | 250 行或 16 KB | 300 行或 20 KB | `check_limits.py` |
| `CLAUDE.md` | | 根目錄 60 行，子目錄 30 行 | `check_limits.py` |

**檢查指令**（完成定義見 [git-workflow.md](git-workflow.md) 第 3 節）：

```sh
./gradlew detekt                  # 程式品質與 ktlint 格式（detekt 2.0.0-alpha.6 加 ktlint-wrapper）
./gradlew detekt --auto-correct   # 自動修正格式問題
python scripts/check_limits.py     # 檔案長度、文件索引、連結
python scripts/check_module_deps.py   # 模組依賴規則（ADR-0007）
python scripts/check_device_neutral.py   # 產品程式不含型號判斷（NFR-9）
python scripts/check_flavor_manifests.py   # 兩個 flavor 的權限（NFR-8、ADR-0010），建置後執行
python scripts/probe_to_profile.py blazer --check   # 能力表與偵測報告一致（ADR-0003）
./gradlew :core:camera:connectedDebugAndroidTest :app:connectedFossDebugAndroidTest   # 實機驗收測試（接手機；FR-81 另行遮住鏡頭執行）
```

detekt 用 2.0 的 alpha 版，因為它是唯一以 Kotlin 2.4 建置的版本；只用在開發檢查，不進 APP，2.0 正式版推出後升級。

**不受限制的檔案**：`LICENSE`、自動產生的檔案、`assets/device-profiles/*.json` 等資料檔。資料檔改由 schema 驗證（ADR-0003）。

**超過上限時**，不要調高上限，要拆檔：

- 一個類別太大：依職責拆成幾個協作類別。
- 一個函式太長：抽出有名稱的步驟。
- 一份文件太長：依章節拆成目錄，加一個索引 `README.md`，參考 `docs/product/requirements/` 的做法。

## 4. 原始檔開頭

每個原始檔前兩行是 SPDX 標頭，其他授權說明集中在 `NOTICE.md`：

```kotlin
// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
```

## 5. 註解

- 用英文寫。說明「為什麼」，不說明「做了什麼」。
- 對應需求或 ADR 時，直接寫編號，例如 `// FR-62: keep RAW only when the user long-presses within 10 s.`
- 需要實機驗證的推測，標記 `// UNVERIFIED(G0):`，驗證後刪除。這樣 grep 就能列出所有未驗證的前提。
