# :core:telemetry

深度來源與感測記錄（ADR-0008）。

| 項目 | 內容 |
| --- | --- |
| 職責 | 即時的 `DepthSource`（v1.0 為手動深度段）、事後的 `TelemetryImporter`（v1.1）、FR-45 的 1 Hz 感測記錄 |
| 依賴 | 無 |
| 對應需求 | FR-45、FR-84 |
| 內容 | `depth/DepthSource`：`DepthSource` 介面（`StateFlow<DepthReading>`）、本模組自有的 `DepthZone`（淺／中／深，由 `:app` 對應到校正表的深度段）、v1.0 的 `ManualDepthSource`<br>`log/CsvLog`：RFC 4180 列、數字不受語系影響、空值為空欄；檔案已存在就續寫不重寫標頭，每列 flush<br>`log/SensorSample`：`sensors.csv` 欄位（時間、氣壓、光度、磁力計三軸、氣壓計溫度、電池溫度、電量、熱狀態、熱餘裕）、`SensorLatch`、1 Hz 對齊格線的 `nextTickNs`<br>`log/DiveLog`：`<外部檔案目錄>/dives/<場次 ID>/` 的四個檔案；`session.json` 只寫一次，崩潰重啟後沿用；場次 ID 為 `DIVE_yyyyMMdd_HHmmss`（本地時間，與照片檔名同基準）<br>`log/SensorRecorder`：註冊感測器、每秒寫一列；`touches.csv`、`captures.csv` 的欄位由 `:app` 定義 |
| 測試 | JVM 單元測試：`ManualDepthSourceTest`、`DiveLogTest`（CSV 格式、四個檔案、重開續寫、欄數檢查、空欄、1 Hz 格線）<br>實機儀器測試 `SensorRecorderTest`：錄 60 s 檢查檔案、列數、遞增與欄位，測完刪除場次目錄；結果見 [m4-instrumented.md](../../docs/test/m4-instrumented.md) |
| 現況 | M4：FR-84 手動深度與 FR-45 紀錄程式已寫；FR-45 要等 M3 潛水鎖定接上開始與結束，再以 `scripts/check_dive_log.py` 驗收 |
