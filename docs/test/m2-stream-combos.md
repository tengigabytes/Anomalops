# M2 工作階段組合實測（Pixel 10 Pro）

2026-09-28 · 狀態：已完成

M2 需要單張同時輸出 JPEG\_R 與 RAW（ADR-0005），連拍輸出一般 JPEG（FR-68）。G0 只用 `isSessionConfigurationSupported` 查詢過三路組合，這次實際建立工作階段。實驗程式：`core/camera/src/androidTest/.../experiment/StreamComboExperiment.kt`；預覽以 1440 × 1080 PRIVATE ImageReader 代替 SurfaceView；拍攝以手動曝光 1/125 s、ISO 400。照片只在記憶體中計算大小，沒有寫出。

## 1. 組合

| 組合（皆含預覽） | 查詢結果 | 實際建立 |
| --- | --- | --- |
| JPEG\_R + RAW | 支援 | 成功（鏡頭 2、3、9） |
| JPEG | 支援 | 成功（鏡頭 2、3、9） |
| JPEG\_R + JPEG | 支援 | **失敗**（鏡頭 2：`Error configuring streams: Broken pipe`），相機 HAL 程序隨即重啟，約數秒內 camera 0 無法開啟 |
| JPEG\_R + RAW + JPEG | 支援 | **失敗**（鏡頭 2，同上） |

查詢結果不可靠。含 JPEG\_R 與 JPEG 兩路 BLOB 的組合不再測試，以免再次使 HAL 重啟；程式中不得建立這種組合。

## 2. 單張與連拍分開兩個工作階段

同一次開啟相機：單張工作階段 → 單張拍攝 → 切到連拍工作階段連拍 3 s → 切回單張工作階段。切換前先 `abortCaptures()`。

| 鏡頭 | 建立單張工作階段 | 單張（JPEG\_R + RAW） | 切換到第一張連拍 JPEG | 連拍 3 s | 切回到第一幀預覽 |
| --- | --- | --- | --- | --- | --- |
| 2 | 53 ms | 1.84 MB + RAW 4080 × 3072 | 241 ms | 93 張，間隔中位數 33.3 ms | 894 ms |
| 3 | 57 ms | 2.49 MB + RAW 4032 × 3024 | 275 ms | 81 張，33.3 ms | 222 ms |
| 9 | 53 ms | 2.34 MB + RAW 4032 × 3024 | 285 ms | 90 張，33.3 ms | 724 ms |

- RAW 尺寸等於能力表的 `RAW_SENSOR` 預設尺寸（FR-64）。
- 連拍 30 fps，是 FR-15 門檻（10 fps）的 3 倍。按住 400 ms 後才切換，推算按滿 3 s 約可得 69 張（門檻 ≥ 30 張）。
- 連拍後切回預覽 222–894 ms，這段時間預覽停住。推測是 HAL 仍在編碼排隊中的 JPEG；FR-15 沒有規定，但會影響操作感，實作時再量測與改善。
