# :app

UI、潛水鎖定與各模組的組裝點。

| 項目 | 內容 |
| --- | --- |
| 職責 | Compose 介面、潛水鎖定（ADR-0006）、設定頁、把深度來源的深度段傳給相機層（ADR-0007） |
| 建置 flavor | `play`（Play Billing 樂捐）、`foss`（無專有相依），見 ADR-0010；樂捐程式放在 `src/play/`、`src/foss/` |
| 依賴 | `:core:camera`、`:core:profile`、`:core:store`、`:core:telemetry` |
| 權限 | 不宣告 `INTERNET`（NFR-8） |
| 內容 | `MainActivity`：依 `Build.DEVICE` 載入並驗證能力表，沒有能力表就不開相機（NFR-9）；以 `ManualDepthSource` 提供深度段；debug 版可用 `am start ... --es depthBand DEEP` 選起始深度段（M3 深度開關前的測試用）<br>`capture/CaptureScreen`：M1 測試畫面，預覽、五種預設切換、快門、狀態列（鏡頭、白平衡近似、上一張的格式／大小／快門／ISO、已儲存的檔名）<br>`capture/ShutterButton`：按下即拍一張，按住 400 ms 起連拍、放開即停（FR-15、NFR-4）<br>`capture/ShotPipeline`：拍攝 → MediaStore → RAW 進緩衝；連拍時 3 個協程平行寫入並記錄 Room 堆疊；`capture/LatestThumbnail`：最近一張縮圖，長按保留 RAW（FR-62）<br>`conditions/ShootingConditions`：深度來源的深度段、濾鏡、潛水燈 → `CalibrationKey`（FR-21／24／25、FR-84）；`conditions/ConditionsFollower` 記住相機目前的條件，條件改變時 `CaptureScreen` 以目前預設重新送預覽請求，不因開預覽與收集的先後漏送<br>`capture/CameraPreview`：`SurfaceView` 預覽（緩衝固定 1440×1080）與相機權限 |
| 權限補充 | `CAMERA`；M1 暫時鎖直向，M3 潛水鎖定介面再決定方向處理 |
| 測試 | JVM 單元測試：`conditions/ShootingConditionsTest`（假的 `DepthSource` 切換深度段，預覽請求的色彩值隨之改變；濾鏡與潛水燈各自選到自己的校正值）、`ConditionsFollowerTest`<br>實機儀器測試（`src/androidTest/.../acceptance/`，共用 `AppRig`，測完刪除所有檔案）：`StillWriteTest`（NFR-7）、`RawKeepTest`（FR-62、FR-64）、`BurstTest`（FR-15、FR-68）、`MemoryTest`（FR-62 記憶體，PSS 加 dma-buf）、`ConditionsSwitchTest`（FR-84 深度切換）、`RawFullTest`（RAW reader 滿載時照片照常存、APP 不崩潰）；`experiment/RawMemoryExperiment` 記錄 RAW 在 meminfo 中的位置；結果見 [m2-instrumented.md](../docs/test/m2-instrumented.md)、[m4-instrumented.md](../docs/test/m4-instrumented.md) |
| 現況 | M1 進行中：拍攝後經 `:core:store` 寫入 MediaStore；狀態列固定兩行高，按鍵位置不隨文字長度移動 |
