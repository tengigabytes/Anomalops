# :core:store

成品與 RAW 的儲存。

| 項目 | 內容 |
| --- | --- |
| 職責 | MediaStore 寫入（ADR-0004）、RAW 環形緩衝與 DNG 寫出（ADR-0005）、連拍堆疊索引（FR-68） |
| 依賴 | `:core:camera` 的資料型別 |
| 對應需求 | FR-16、FR-61a、FR-62、FR-64、FR-68、NFR-7 |
| 內容 | `media/StillStore`：成品寫入 MediaStore `Pictures/Anomalops/`，先 `IS_PENDING = 1` 寫入再公開，失敗就刪除該列；回傳寫入耗時（NFR-7）；`replace` 讓事後算好的成品（FR-17 的合成）取代已存的那張：先以 `_M` 結尾的名稱另存，再刪掉舊的、改回原名，中途被關掉只會留下完整的一張或兩張<br>`media/StillNames`：檔名 `ANM_yyyyMMdd_HHmmss_SSS.jpg`（曝光的本地時間到毫秒），DNG 用同一主幹（ADR-0005），連拍為 `<主幹>_B001.jpg`…（FR-68） |
| 權限 | 寫入自己的照片不需儲存權限（Android 10 起） |
| RAW | `raw/RawBuffer`：5 張、10 s 的環形緩衝（純邏輯，JVM 測試）<br>`raw/RawKeeper`：持有 RAW 並定時清除過期；長按縮圖時寫 DNG（FR-62）<br>`raw/DngStore`：`DngCreator` 寫 DNG，與成品同一檔名主幹（ADR-0005、FR-64） |
| 堆疊 | `stack/StackDatabase`、`BurstStacks`：Room 連拍堆疊索引（FR-68，ADR-0007），封面為第一張連拍；schema 在 `schemas/` |
| 現況 | M2：成品、RAW 緩衝與 DNG、連拍檔案與堆疊索引完成 |
| v1.1 提前（未接上） | `library/`：FR-66 `Reclaim`、FR-73 `RecycleBin`、FR-70 `KeepBest`、FR-67 `ShotSizes` 與 `Capacity`；`cull/`：FR-69 `FrameMetrics`（清晰度、剪切）與 `StackCull`；JVM 測試 `SpaceRulesTest`、`CapacityTest`、`StackCullTest`。見 [early-logic.md](../../docs/product/early-logic.md) |
