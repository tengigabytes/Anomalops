# :core:store

成品與 RAW 的儲存。

| 項目 | 內容 |
| --- | --- |
| 職責 | MediaStore 寫入（ADR-0004）、RAW 環形緩衝與 DNG 寫出（ADR-0005）、連拍堆疊索引（FR-68） |
| 依賴 | `:core:camera` 的資料型別 |
| 對應需求 | FR-16、FR-61a、FR-62、FR-64、FR-68、NFR-7 |
| 內容 | `media/StillStore`：成品寫入 MediaStore `Pictures/Anomalops/`，先 `IS_PENDING = 1` 寫入再公開，失敗就刪除該列；回傳寫入耗時（NFR-7）<br>`media/StillNames`：檔名 `ANM_yyyyMMdd_HHmmss_SSS.jpg`（本地時間到毫秒），之後的 DNG 沿用同一主幹（ADR-0005） |
| 權限 | 寫入自己的照片不需儲存權限（Android 10 起） |
| 現況 | M1：成品寫入完成；RAW 環形緩衝、DNG、連拍堆疊尚未實作 |
