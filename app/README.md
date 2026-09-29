# :app

UI、潛水鎖定與各模組的組裝點。

| 項目 | 內容 |
| --- | --- |
| 職責 | Compose 介面、潛水鎖定（ADR-0006）、設定頁、把深度來源的深度段傳給相機層（ADR-0007） |
| 建置 flavor | `play`（Play Billing 樂捐）、`foss`（無專有相依），見 ADR-0010；樂捐程式放在 `src/play/`、`src/foss/` |
| 依賴 | `:core:camera`、`:core:profile`、`:core:store`、`:core:telemetry` |
| 權限 | 不宣告 `INTERNET`（NFR-8） |
| 內容 | `MainActivity`：依 `Build.DEVICE` 載入並驗證能力表，沒有能力表就不開相機（NFR-9）；以 `ManualDepthSource` 提供深度段（debug 版可用 `am start ... --es depthBand DEEP` 選起始深度段）；持有潛水鎖定狀態機，切換視窗狀態與 FR-45 場次；debug 版 `--ez injectCrash true` 注入崩潰（NFR-1 測試）<br>`dive/DiveScreen`：唯一的拍攝畫面，一般模式與潛水鎖定共用，依 `layout/DiveLockLayout` 以 xdpi / ydpi 換算 mm 擺放（[dive-lock-layout.md](../docs/product/dive-lock-layout.md)）；`dive/DiveKeys`、`dive/Keys`（按鍵、按住 3 s 解鎖鍵）、`dive/StatusBand`（下緣唯讀資訊：電量、溫度與過熱警示、記錄狀態、條件）、`dive/ShotControls`（預設、拍攝、連拍、保留 RAW、`captures.csv`）、`dive/CaptureRecords`（每張照片與每次連拍的 `captures.csv` 列，請求值與結果並列，ADR-0008）、`dive/TouchLog`（整個畫面的 NFR-6 去抖與 `touches.csv`）、`dive/Placement`（mm 換 dp）<br>`settings/SettingsScreen`：設定頁，只在一般模式開啟；FR-24 濾鏡，`FilterPrefs` 保存<br>`lock/`：`DiveLock` 狀態機；`PinWatcher` 把系統固定對話框的結果轉成狀態；`WindowLock` 套用 ADR-0006 視窗狀態；`CrashRestarter` 與 `RestartTrampolineActivity`（`:restarter` 程序）在鎖定中崩潰時保住固定並重啟（NFR-1，由 `:tools:probe` 移植）；`PrefsLockStore`；`DiveSession` 與 `SessionRows`（FR-45 的 `touches.csv`、`captures.csv` 欄位）；`HoldToUnlock`（FR-51）；`Sessions`：程序層級的 FR-45 場次<br>`touch/TouchDebouncer`（NFR-6）；`theme/DivePalette`（NFR-5）<br>`capture/ShutterButton`：快門條，按下即拍一張、按住 400 ms 起連拍（FR-52、FR-15、NFR-4），排除系統邊緣手勢；`capture/ShotPipeline`：拍攝 → MediaStore → RAW 進緩衝，連拍平行寫入並記錄 Room 堆疊；`capture/LatestThumbnail`：最近一張，長按保留 RAW（FR-62）；`capture/CameraPreview`：`SurfaceView` 預覽（緩衝固定 1440×1080）與相機權限<br>`conditions/ShootingConditions`：深度段、濾鏡、潛水燈 → `CalibrationKey`（FR-21／24／25、FR-84）；`conditions/ConditionsFollower`：條件改變時以目前預設重送預覽請求；`conditions/DepthSwitch`：深度段鍵循環 |
| 權限補充 | `CAMERA`；直向固定；`MainActivity` 為 `singleTask`（與 G1 平台測試相同）；lint `MissingTranslation` 為錯誤（NFR-10） |
| 測試 | JVM 單元測試：`conditions/ShootingConditionsTest`、`ConditionsFollowerTest`、`DepthSwitchTest`；M3：`lock/DiveLockTest`、`HoldToUnlockTest`、`SessionRowsTest`、`touch/TouchDebouncerTest`、`layout/DiveLockLayoutTest`、`theme/DivePaletteTest`<br>實機儀器測試（`src/androidTest/.../acceptance/`，共用 `AppRig`，測完刪除所有檔案）：`StillWriteTest`（NFR-7）、`RawKeepTest`（FR-62、FR-64）、`BurstTest`（FR-15、FR-68）、`MemoryTest`（FR-62 記憶體，PSS 加 dma-buf）、`ConditionsSwitchTest`（FR-84 深度切換）、`DiveSessionTest`（FR-45 場次列與重開續寫、`PrefsLockStore`）、`RawFullTest`（RAW reader 滿載時照片照常存、APP 不崩潰）；`experiment/RawMemoryExperiment`；結果見 [m2-instrumented.md](../docs/test/m2-instrumented.md)、[m4-instrumented.md](../docs/test/m4-instrumented.md)；M3 待測見 [m3-test-plan.md](../docs/test/m3-test-plan.md) |
| 現況 | M3 程式已寫，CI 建置與 JVM 單元測試通過，尚未實機測試（見 m3-test-plan.md 第 7 節） |
