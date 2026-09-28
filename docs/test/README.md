# 測試關卡結果

2026-09-28 · 狀態：已採用

各測試關卡（G0–G4，定義見 [mvp-acceptance.md](../product/mvp-acceptance.md)）的彙整結果。這裡只放彙整後的數字與結論；照片與原始感測紀錄不進 repo。

| 檔案 | 內容 |
| --- | --- |
| [g0-blazer.md](g0-blazer.md) | G0 能力偵測：Pixel 10 Pro（blazer），2026-09-28 |
| [m0-play-billing.md](m0-play-billing.md) | Play Billing 相依的授權、合併後權限與 foss 相依樹（ADR-0010），2026-09-28 |
| [g1-dive-lock-platform.md](g1-dive-lock-platform.md) | 潛水鎖定平台行為（ADR-0006）：螢幕固定、抬頭通知、電源鍵、崩潰重啟，2026-09-28 |
| [m1-camera-session.md](m1-camera-session.md) | M1 相機工作階段實機測試：串流組合、五種預設的曝光與鏡頭、生命週期與資源釋放，2026-09-28 |
| [m1-mediastore.md](m1-mediastore.md) | M1 成品寫入 MediaStore：位置、狀態、大小、寫入耗時、增益圖與 EXIF 方向，2026-09-28 |
| [m1-pipeline-calibration.md](m1-pipeline-calibration.md) | M1 手動白平衡管線：室內自動白平衡讀數、請求與回報一致性、手動與自動成品色彩比較，2026-09-28 |
| [m1-instrumented.md](m1-instrumented.md) | M1 實機儀器測試：FR-11、NFR-4、FR-61a、FR-81、NFR-7 的結果與切換延遲分析，2026-09-28 |
| [m2-stream-combos.md](m2-stream-combos.md) | M2 工作階段組合實測：JPEG\_R + RAW 單張、一般 JPEG 連拍、切換時間；JPEG\_R 與 JPEG 同工作階段會使 HAL 重啟，2026-09-28 |
| [m2-raw-buffer.md](m2-raw-buffer.md) | M2 RAW 緩衝與 DNG 手動實測：過期、換鏡頭、離開 APP 後保留；DNG 標籤，2026-09-28 |
| [m2-burst.md](m2-burst.md) | M2 連拍手動實測：張數、間隔、寫入積壓、預覽恢復；檔名與 Room 堆疊，2026-09-28 |
| [m2-instrumented.md](m2-instrumented.md) | M2 實機儀器測試：FR-15、FR-62（含記憶體）、FR-64、FR-68；PSS 看不到 RAW 的發現，2026-09-28 |
| [m4-instrumented.md](m4-instrumented.md) | M4 實機儀器測試：FR-84 手動深度切換即時改變預覽請求、FR-45 一分鐘感測紀錄、debug 起始深度段、`:app` 儀器測試回歸；條件切換時序問題的修正，2026-09-28 |
| [m4-af-timeline.md](m4-af-timeline.md) | M4 AF 行為實測與微距對焦觸發（FR-35、FR-31）：AUTO 觸發到鎖定的時間、連續對焦來回拉、AF 錯誤鎖定、實作驗收與相機回歸，2026-09-28 |
