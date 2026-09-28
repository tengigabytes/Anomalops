# ADR-0001 直接使用 Camera2，不用 CameraX

2026-09-28 · 狀態：已採納（[需求第 3 節](../product/requirements/03-platform.md)「v1 以 Camera2 為主」）

## 背景

v1.0 需要以下控制，而且每一張擷取請求都要能個別指定：

- 關閉自動白平衡，自行設定白平衡增益與色彩矩陣（FR-21、FR-91）
- 指定實體鏡頭，例如微距用超廣角（FR-31）
- 同一張擷取同時輸出 RAW 與 JPEG，連拍則只輸出 JPEG（FR-62、FR-68）
- 每張都保證閃光燈不觸發（FR-81）
- 拍攝時換算曝光參數（ADR-0009）

[需求第 3 節](../product/requirements/03-platform.md)已指出，CameraX Extensions 不支援自訂白平衡。

## 決策

相機層直接使用 `android.hardware.camera2`，封裝在 `:core:camera` 模組（ADR-0007）。UI 層不直接接觸 Camera2 類別，只透過該模組的介面下指令、收狀態。

## 不採用的選項

- **CameraX + Camera2Interop**：可以覆寫個別擷取請求鍵，新版也有 RAW 擷取（推測自 CameraX 1.4 起）。但「每張請求輸出哪些 Surface」與「逐幀 CaptureResult」都要繞道取得，而這兩點正是 FR-62 與 ADR-0009 的核心。
- **CameraExtensionSession**：擴充工作階段通常不接受手動白平衡、不輸出 RAW（[需求 5.1 節](../product/requirements/05-1-capture.md)）。只保留作為 FR-17a（P1）的低光備選路徑。

## 後果

- 好處：完整控制每張請求與結果，預覽和拍攝用同一套參數（FR-91）。
- 代價：要自行處理工作階段生命週期、裝置斷線重開、錯誤恢復，也拿不到 CameraX 內建的裝置相容性修補。
- 影響：ADR-0002、0005、0009 都假設可以逐張控制請求。

## 驗證

G0 能力偵測要在 Pixel 10 Pro 上列出：

- 各邏輯與實體鏡頭的 `INFO_SUPPORTED_HARDWARE_LEVEL`、`REQUEST_AVAILABLE_CAPABILITIES`（至少要有 MANUAL\_SENSOR、MANUAL\_POST\_PROCESSING、RAW）
- 第三方 APP 能否以 `OutputConfiguration.setPhysicalCameraId` 取用超廣角實體鏡頭（推測可以，未確認）
