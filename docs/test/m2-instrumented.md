# M2 實機儀器測試（Pixel 10 Pro）

2026-09-28 · 狀態：FR-15、FR-62、FR-64、FR-68 已完成；FR-16 待 `dng_validate` 與 darktable

分支 `m2/instrumented-tests`。`:app` 的 androidTest（`acceptance/`）以真實相機執行 APP 的拍攝管線：預覽以 PRIVATE ImageReader 代替 SurfaceView，堆疊索引用獨立的測試資料庫，測試結束時刪除所有產生的照片、DNG 與測試資料庫。結果從 logcat 標籤 `M2Acceptance` 讀取，測試期間在背景持續寫檔。

## 1. 結果

| 項目 | 條件 | 結果 | 門檻（mvp-acceptance.md） | 判定 |
| --- | --- | --- | --- | --- |
| FR-15 | 連拍 2.6 s（按住 3 s 扣掉 400 ms 門檻） | 59 張，全部寫出，間隔中位數 33.3 ms | ≥ 30 張，中位數 ≤ 100 ms | 通過 |
| FR-62 保留 | 20 張，拍後 2、5、9 s 輪流保留 | 20 / 20 寫出 DNG | 20 / 20 | 通過 |
| FR-62 不保留 | 20 張，10.5 s 後嘗試保留 | 可保留 0 張，DNG 0 個 | 0 | 通過 |
| FR-62 擠出 | 連拍 6 張單張 | 第 1 張不可保留，第 2–6 張可保留 | 第 6 張擠出第 1 張 | 通過 |
| FR-62 記憶體 | 300 張單張 + 10 s 連拍（256 張，25.6 fps） | PSS 64.4 → 65.3 MB（+0.9）；dma-buf 223.1 → 211.2 MB（−11.9） | 增加 ≤ 50 MB | 通過 |
| FR-64 | 保留的 20 個 DNG | 全部 4080 × 3072（等於 `RAW_SENSOR` 預設尺寸），每個 25,107,212 bytes | 尺寸相等，只記錄大小 | 通過 |
| FR-68 | 10 次 1 s 連拍 | 10 個堆疊、共 222 張；DNG 0、全為一般 JPEG、封面為 B001 | 10 個堆疊 | 通過 |
| FR-16 | — | 未測 | `dng_validate` + darktable | 待工具 |

改用 APP 管線後重跑 NFR-7：300 張寫入中位數 70 ms、p95 126 ms（門檻 500 ms），仍通過。

FR-15 的 400 ms 按住門檻屬於介面手勢，在手動測試中確認（[m2-burst.md](m2-burst.md)）；這裡驗證連拍本身。

## 2. 記憶體量測的發現

原本以 PSS 判斷 FR-62 記憶體，實驗（`experiment/RawMemoryExperiment.kt`）顯示 PSS 看不到持有的 RAW：

| 時間點 | TOTAL PSS | 本程序的 dma-buf |
| --- | --- | --- |
| 閒置 | 58.0 MB | 7.2 MB |
| 持有 5 張 RAW | 64.1 MB | 187.2 MB |
| 清空 RAW 緩衝後 | 63.3 MB | 187.2 MB |

- RAW 放在未映射的 dma-buf，`dumpsys meminfo` 的 Graphics 為 0，PSS 只增加約 6–12 MB。因此改為同時檢查 PSS 與本程序的 dma-buf（從 `/proc/self/fdinfo` 加總 `exp_name` 為 dma-buf 的 `size`）。
- 關閉 RAW `Image` 後 dma-buf 不減少：緩衝只回到 reader 的佇列，reader 開著時最多保留 7 個（ADR-0005 的 `maxImages`）。切換到連拍工作階段時，相機端斷開連線，RAW reader 的緩衝池被釋放，之後再次拍攝才重新配置。
- 所以記憶體比較在「暖機後、清空 RAW 緩衝」的相同狀態下取兩次讀數；連拍後先再拍 10 張讓緩衝池長回來。

## 3. 其他觀察

- 300 張單張之後的 10 s 連拍為 21–26 fps，低於 3 s 連拍時的 30 fps；推測是連續拍攝後的降頻，未查證。仍高於 FR-15 的 10 fps。
- 測試方法若以 `runBlocking { … }` 撰寫，最後一行不是 Unit 時方法會有回傳值，JUnit 視為無效測試；所有儀器測試已改為 `runBlocking<Unit>`。
