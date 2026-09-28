# M1 相機工作階段實機測試（Pixel 10 Pro）

2026-09-28 · 狀態：已完成（手動測試；FR-11、NFR-4 等的儀器測試另做）

分支 `m1/camera-session`，`fossDebug` 版，裝在 Pixel 10 Pro（blazer）。以 adb 點擊操作 M1 測試畫面，從 logcat 與 `dumpsys media.camera` 讀結果。室內燈光，校正表為空，所以白平衡都走「近似」的自動白平衡（ADR-0002 補充）。拍攝的照片沒有寫入儲存空間。

## 1. 串流組合

`dumpsys media.camera` 顯示的 session（快照預設）：

| 串流 | 尺寸 | 格式／dataspace | 實體鏡頭 |
| --- | --- | --- | --- |
| 預覽（`SurfaceView`） | 1440 × 1080 | 0x23 / 0x8810000 | 2 |
| 拍照（`ImageReader`） | 4080 × 3072 | 0x21 / 0x1005（JPEG\_R） | 2 |

G0 只測過 YUV 1920 × 1080 + JPEG\_R；1440 × 1080 的 `SurfaceView` 預覽在鏡頭 2、3、9 都能建立 session。

## 2. 五種預設各拍一張

請求值是 ADR-0009 換算後的值；回報值取自拍攝結果中該實體鏡頭的 `CaptureResult`。

| 預設 | 鏡頭 | 大小 | 請求：時間／ISO | 回報：時間／ISO | AF 模式（回報） | 閃光燈 |
| --- | --- | --- | --- | --- | --- | --- |
| 快照 | 2 | 2.84 MB | 8.000 ms / 1387 | 7.998 ms / 1387 | CONTINUOUS\_PICTURE | 未觸發 |
| 廣角 | 3 | 3.01 MB | 16.667 ms / 1000 | 16.663 ms / 999 | CONTINUOUS\_PICTURE | 未觸發 |
| 魚群 | 2 | 3.17 MB | 4.000 ms / 2774 | 3.993 ms / 2773 | CONTINUOUS\_PICTURE | 未觸發 |
| 微距 | 9 | 2.80 MB | 8.000 ms / 1204 | 7.997 ms / 1204 | AUTO | 未觸發 |
| 低光 | 2 | 2.79 MB | 33.324 ms / 333 | 33.311 ms / 333 | CONTINUOUS\_PICTURE | 未觸發 |

- 鏡頭與 AF 模式符合 mvp-scope.md 第 4 節；格式都是 JPEG\_R，大小都在 FR-61a 的 6 MB 以內。
- 曝光時間與 ISO 同時寫在 logical 請求與 `setPhysicalCameraKey` 上，實體鏡頭的回報值與請求值一致；時間差 ≤ 0.2%，推測是感測器以行時間量化。
- 換算前後一致：預覽 AE 在鏡頭 2 約為 1/30 s、ISO 333，快照 333 × 33.3 / 8 ≈ 1387，魚群 333 × 33.3 / 4 ≈ 2774；低光的 33.32 ms 未超過上限 33.33 ms，直接沿用。
- `COLOR_CORRECTION_*` 手動路徑未測（校正表為空）。

## 3. 生命週期與資源釋放

| 情境 | 結果 |
| --- | --- |
| 建置安裝時手機熄屏上鎖 | 開相機失敗（`cannot open camera "0" from background`）；解鎖回到前景後自動重新開啟 |
| 按 Home、連續 3 次 Home／重新開啟 | 相機每次都釋放（`Device 0 is closed`），重新開啟後預設保留，拍攝正常 |
| 按返回鍵 | 相機釋放 |
| 換鏡頭（2 ↔ 3 ↔ 9） | 舊 session 關閉、新 session 建立，無錯誤 |

測試中發現並修正兩個問題：

1. 關 session 後立刻關拍照用的 `ImageReader`，HAL 仍在歸還緩衝區，出現 `cancelBuffer: BufferQueue has been abandoned`。改為在 session 的 `onClosed` 才關 reader。
2. 按 Home 時先關 session 再立刻關 device，舊 session 的 `onClosed` 沒有送達，reader 沒被關閉（每次進出洩漏一個 4080 × 3072 reader）。改為 `stop()` 先關 session、等 `onClosed`（上限 1 秒），再關 reader 與 device。修正後每個建立的 reader 都有對應的關閉紀錄。

每次關閉 reader 時 app 程序都會印一行 `E/ConsumerBase ... abandonLocked: ConsumerBase is abandoned!`；推測這是這版系統正常關閉時的訊息，因為修正後不再出現 `cancelBuffer` 類的錯誤。

## 4. 未驗證

- 照片方向：本測試時照片沒有寫出；之後在 `m1/mediastore` 由維護者確認直向拍攝方向正確（[m1-mediastore.md](m1-mediastore.md)）。
- 預覽 AE 的測光值確實取自正在出圖的實體鏡頭（程式優先讀實體鏡頭結果，沒有記錄是否退回 logical 結果）。
- 延遲（NFR-4）、增益圖（FR-61a）、預覽與成品色差（FR-91）：留給儀器測試與 `m1/mediastore`。
