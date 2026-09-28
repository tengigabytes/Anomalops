# :app

UI、潛水鎖定與各模組的組裝點。

| 項目 | 內容 |
| --- | --- |
| 職責 | Compose 介面、潛水鎖定（ADR-0006）、設定頁、把深度來源的深度段傳給相機層（ADR-0007） |
| 建置 flavor | `play`（Play Billing 樂捐）、`foss`（無專有相依），見 ADR-0010；樂捐程式放在 `src/play/`、`src/foss/` |
| 依賴 | `:core:camera`、`:core:profile`、`:core:store`、`:core:telemetry` |
| 權限 | 不宣告 `INTERNET`（NFR-8） |
| 內容 | `MainActivity`：依 `Build.DEVICE` 載入並驗證能力表，沒有能力表就不開相機（NFR-9）<br>`capture/CaptureScreen`：M1 測試畫面，預覽、五種預設切換、快門、狀態列（鏡頭、白平衡近似、上一張的格式／大小／快門／ISO）<br>`capture/CameraPreview`：`SurfaceView` 預覽（緩衝固定 1440×1080）與相機權限 |
| 權限補充 | `CAMERA`；M1 暫時鎖直向，M3 潛水鎖定介面再決定方向處理 |
| 現況 | M1 進行中：拍攝結果只記 log，尚未寫入 MediaStore |
