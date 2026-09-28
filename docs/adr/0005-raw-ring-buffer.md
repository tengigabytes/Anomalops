# ADR-0005 RAW 按需保留：逐張擷取、記憶體環形緩衝、DngCreator

2026-09-28 · 狀態：已採納（產品擁有者 2026-09-28 確認）

## 背景

FR-62：緩衝最近 5 張 DNG，拍後 10 s 內長按縮圖才寫入，否則丟棄。FR-64：12.5 MP 合併像素、無損壓縮、單張 ≤ 25 MB。FR-68：連拍不存 DNG。

## 決策

**擷取**
- 單張拍攝的請求同時輸出兩個目標：成品（JPEG\_R / JPEG）與 `RAW_SENSOR`。
- 連拍請求只輸出成品。
- 預覽不帶 RAW 目標，避免持續佔用頻寬與耗電。

**緩衝**
- RAW 的 `Image`，連同對應的 `CaptureResult` 與 `CameraCharacteristics`（DngCreator 需要這兩者），一起放進記憶體中的環形緩衝，最多 5 張。
- 超過 10 s，或被新照片擠出時，關閉 `Image` 釋放記憶體。
- RAW 的 `ImageReader` 設 `maxImages = 5 + 2`，直接持有 `Image`，不複製。

**寫入**
- 10 s 內長按縮圖，就在背景執行緒用 `DngCreator` 寫到 MediaStore，與成品放同一資料夾、同一檔名主幹。

**尺寸**
- 使用 `RAW_SENSOR` 的預設尺寸，推測是合併像素後的 12.5 MP。
- 不使用 `SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION`，也就是不存 50 MP 全尺寸 RAW。

**記憶體估算（推測）**
- 12.5 MP × 2 byte ≈ 25 MB／張
- 5 張 ≈ 125 MB
- 相對於 16 GB RAM，可以接受。

## 不採用的選項

- **預覽持續串流 RAW，拍照時取最近一幀（零快門延遲式）**：耗電、發熱都會增加，而 NFR-2、NFR-3 是 v1.0 要驗證的問題之一。
- **每張都寫 DNG，事後再刪**：寫入量大，還會和 NFR-7「每張 ≤ 500 ms」競爭 I/O。
- **v1.0 自寫無損 JPEG 壓縮的 DNG 寫入器**：可行，但屬於額外工作；已決定延到 v1.1（mvp-scope.md 第 6 節第 3 項）。

## 後果

- DngCreator 推測輸出未壓縮 DNG，單張約 25 MB，加上中繼資料和縮圖會略超過 25 MB；FR-64 已改為 v1.0 只記錄大小，≤ 25 MB 門檻自 v1.1 起。
- APP 被系統回收時，緩衝中的 RAW 會遺失。這可以接受，因為 RAW 本來就是選擇性保留。
- 持有 `Image` 時，若 HAL 的緩衝輪轉受影響，會拖慢下一張拍攝；如果發生，改為先複製到直接記憶體再釋放 `Image`。

## 驗證

- G0：`RAW_SENSOR` 的預設尺寸，以及成品與 RAW 能否在同一請求中輸出。
- G1：寫出的 DNG 用 exiftool 查看 Compression 標籤與檔案大小，確認是否未壓縮。
- G1：在緩衝已滿的狀態下，連續拍攝不出現延遲尖峰（NFR-4）。
- G1：連拍 300 張，記憶體沒有持續上升（mvp-acceptance.md 的 FR-62 項）。

2026-09-28 G0 結果（Pixel 10 Pro）：見 [g0-blazer.md](../test/g0-blazer.md)。RAW 預設 4080×3072（12.53 MP），推測成立；成品與 RAW 同一請求的組合查詢全部支援。
