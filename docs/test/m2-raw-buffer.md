# M2 RAW 緩衝與 DNG 手動實測（Pixel 10 Pro）

2026-09-28 · 狀態：已完成（手動測試；FR-62 的計數與記憶體、FR-16 的 `dng_validate` 與 darktable 另做）

分支 `m2/raw-buffer`，`fossDebug` 版。單張拍攝同時輸出 JPEG\_R 與 RAW（ADR-0005），RAW 留在記憶體緩衝，長按最近一張的縮圖才寫出 DNG（FR-62）。以 adb 點擊操作；DNG 只把位元組串流進記憶體解析 TIFF 標籤，不落地到電腦。測完已刪除手機上的測試照片與 DNG。

## 1. 情境

| 情境 | 結果 |
| --- | --- |
| 拍一張，2 s 後長按縮圖 | DNG 寫出，與照片同一檔名主幹、同一資料夾；寫入 179 ms |
| 拍一張，11 s 後長按 | 沒有寫出 DNG（已過期） |
| 鏡頭 2 拍一張，切到廣角（鏡頭 3）後長按 | 鏡頭 2 那張的 DNG 寫出，換鏡頭沒有使 RAW 失效 |
| 拍一張，按 Home（相機已釋放），約 7 s 後回來長按 | DNG 寫出 |

## 2. DNG 內容（鏡頭 2）

| 標籤 | 值 |
| --- | --- |
| 尺寸 | 4080 × 3072（等於 `RAW_SENSOR` 預設尺寸，FR-64） |
| Compression | 1（未壓縮，壓縮在 v1.1） |
| 檔案大小 | 25,107,212 bytes |
| Orientation | 6（與 JPEG 相同） |
| DNGVersion | 1.4.0.0 |
| BitsPerSample / WhiteLevel | 16 / 4095 |
| AsShotNeutral | [0.6045, 1, 0.4336]，等於手動白平衡增益 1.6532、2.3034 的倒數（ADR-0002） |
| MediaStore | `image/x-adobe-dng`，`Pictures/Anomalops/`，`is_pending = 0` |

## 3. 設計重點

- 關閉 `ImageReader` 會使它發出的所有 `Image` 失效，所以 RAW reader 由 `RawReaders` 依鏡頭管理，等 RAW 全部釋放後才關（ADR-0005 補充）。
- 拍攝失敗或逾時時，已取得或晚到的 RAW 都會關閉。
- `RawKeeper` 每秒清掉過期的 RAW，不必等下一張照片。
