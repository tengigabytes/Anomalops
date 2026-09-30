# ADR-0007 技術堆疊與模組切分

2026-09-28 · 狀態：已採納（產品擁有者 2026-09-28 確認）

## 背景

v1.0 只支援 Pixel 10 Pro，但架構要能擴展到 Pixel 6 Pro 至 11 Pro（NFR-9）；所有功能不需網路（NFR-8）；深度來源要能替換（FR-84）。

## 決策

**語言與框架**
- Kotlin，並行處理用 Coroutines 與 Flow。
- Camera2 的回呼放在專用的 HandlerThread 上。
- UI 用 Jetpack Compose。
- 預覽用 `SurfaceView`，以 `AndroidView` 嵌入 Compose。推測 `SurfaceView` 比 `TextureView` 延遲低、耗電少，這是一般相機 APP 的做法。
- 單一 Activity。

**SDK 版本**
- `minSdk 36`（Android 16），與[需求第 1 節](../product/requirements/01-purpose.md)的目標「Android 16+」一致。JPEG\_R 只需 API 34，所以 36 沒有額外限制。
- 推測 Pixel 6 Pro 已有 Android 16 更新，要用實機確認。
- `targetSdk` 用建置時最新的穩定版。

**網路**
- Manifest 不宣告 `INTERNET` 權限，直接在平台層保證 NFR-8。
- v2.0 要下載物種辨識模型時，再另開 ADR 重新評估。
- 2026-09-28 補充：本規則依建置 flavor 細化，`play` flavor 允許 Play Billing Library 帶入的權限，見 ADR-0010。

**模組**

| 模組 | 職責 | 可以依賴 |
| --- | --- | --- |
| `:app` | Compose UI、潛水鎖定（ADR-0006）、設定、組裝各模組 | 全部 |
| `:core:camera` | Camera2 工作階段、請求組裝、曝光換算（ADR-0001、0002、0009） | `:core:profile` |
| `:core:profile` | 能力表與校正表的載入、驗證、查詢（ADR-0003） | 無 |
| `:core:store` | MediaStore 寫入、RAW 環形緩衝、DNG 寫出（ADR-0004、0005）、連拍堆疊索引 | `:core:camera` 的資料型別 |
| `:core:telemetry` | 深度來源、感測記錄（ADR-0008） | 無 |
| `:tools:probe` | 能力偵測工具，輸出 ADR-0003 的 JSON | `:core:profile` 的 schema |

**依賴規則**
- `:core:camera` 不依賴 `:core:telemetry`。深度段由 `:app` 從深度來源取得後，以數值傳給相機層。這條規則讓 FR-84「新增來源不改拍攝管線」有結構上的保證，並以 Gradle 模組依賴檢查強制。

**資料**
- 連拍堆疊與潛水場次的索引用 Room。
- 照片本身只存在 MediaStore。

**建置**
- Gradle Kotlin DSL 加 version catalog。
- 單元測試用 JUnit；和相機、感測器有關的測試一律在實機跑 instrumented test，模擬器不支援手動白平衡與 RAW。

## 不採用的選項

- **View 系統 UI**：沒有技術上的必要，Compose 做大型觸控目標與狀態驅動的介面比較直接。
- **單一模組**：FR-84 與 NFR-9 的替換性無法靠結構保證。
- **跨平台框架**：iOS 不在範圍內（[需求第 1 節](../product/requirements/01-purpose.md)），而且 Camera2 層一定是原生程式。

## 後果

- 模組邊界一開始就要定好介面，初期開發會慢一點。
- 能力偵測工具與主 APP 共用 schema，兩者不會各寫一套。

## 驗證

- G0：確認 Pixel 6 Pro 的 Android 版本，若低於 16，重新評估 `minSdk`。
- 在 CI 或本地建置中，加入模組依賴檢查。

2026-09-28 補充：模組依賴檢查以 `scripts/check_module_deps.py` 實作（掃描建置檔，不經 Gradle），本機與 CI 都執行；detekt 與 ktlint 格式檢查以 detekt 2.0.0-alpha.6 加其 ktlint-wrapper 實作。

2026-09-28 補充（M2）：堆疊索引以 Room 2.8.5 實作於 `:core:store` 的 `stack/`，註解處理用 KSP 2.3.12（2.3 起版本不再綁定 Kotlin），在 Kotlin 2.4.20 與 AGP 9 內建 Kotlin 下可建置；schema 匯出到 `core/store/schemas/` 並提交。

2026-10-01 補註：新增純 Kotlin/JVM 模組 `:core:imaging`，負責多幀影像處理（對齊、合成、景深合成），不依賴任何專案模組，`:app` 可以依賴它。見 [ADR-0016](0016-imaging-module.md)。
