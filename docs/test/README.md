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
| [m4-af-timeline.md](m4-af-timeline.md) | M4 AF 行為實測與微距對焦觸發（FR-35、FR-31）：AUTO 觸發到鎖定的時間、連續對焦來回拉、AF 錯誤鎖定、實作驗收與相機回歸、RAW reader 滿載崩潰的修正、全黑下改用固定距離、0.4 s 門檻的有光重跑與回歸，2026-09-28 |
| [m3-test-plan.md](m3-test-plan.md) | M3 潛水鎖定與操作介面待測清單：實機測試守則、JVM 單元測試、G1 儀器與人工測試、回歸；各項狀態已依實測更新；G1 已測完，NFR-5 目視延到 UI 整理後，2026-09-30 |
| [m2-burst-resume.md](m2-burst-resume.md) | 連拍後預覽停頓 222–894 ms 的原因推測與實機量測計畫（各步驟時間點、判讀方式、可能的改善），全部是推測，2026-09-29 |
| [m3-instrumented.md](m3-instrumented.md) | M3 實機測試：`:app` 儀器測試與相機回歸、NFR-1 崩潰重啟、FR-45 場次、FR-51／FR-56 人工測試與不熄滅（有條件通過）、`DiveScreenTest` UI 量測、NFR-10 字串審查；FR-56 由系統停用鎖定畫面達成、亮度熱節流的發現，2026-09-30 |
| [macro-stacking-test-plan.md](macro-stacking-test-plan.md) | 微距景深合成待測清單（FR-33、FR-36、FR-37）：已由 repo 資料確認的值、17 項估算或未知數值的方法與通過標準、順序，2026-09-30 |
| [m9-macro-land.md](m9-macro-land.md) | M9 前置的微距陸上實測：T0 以 AF 估目標距離、T1 望遠實體串流與手動對焦（通過）、T3 46 cm 一點細掃、T9 焦點包圍時間（約 170 ms，但每張對焦位置未確認），2026-09-30 |
| [m9-imaging-phone.md](m9-imaging-phone.md) | `:core:imaging` 在手機上的速度與記憶體（合成影像，非 debug 版）：debug 版慢約 10 倍；對齊一張約 1.4 s、FR-17 五張只算亮度 11.8 s、FR-33 四張以上超出 256 MB 記憶體，2026-10-01 |
| [m9-gpu-trial.md](m9-gpu-trial.md) | ADR-0017 第 3 步 GPU 試作：減半、重新取樣、均方差三支著色器與 CPU 版比較；加 `precise` 後 32 位元逐位元相同，半精度往零捨入；速度只快 1–3.6 倍，2026-10-02 |
| [m9-gpu-fr17.md](m9-gpu-fr17.md) | ADR-0017 第 4 步 FR-17 上 GPU（進行中）：均方差比 CPU 快 6.8–8.5 倍（主因是紋理快取）；以 `trans_stat` 量時脈，忙時會升到最高，ADPF 再快 1.4 倍；重新取樣重複使用輸出平面；整張對齊上 GPU，與 CPU 相同、快約 2.3 倍；逐塊對齊上 GPU，與 CPU 相同、快約 7.4 倍；合成上 GPU，5 張 3 色 0.78–0.92 s（不含解碼、顏色、輸出），2026-10-02 |
| [m7-logical-zoom.md](m7-logical-zoom.md) | ADR-0011 第一、二階段：logical 取景的串流組合、0.51–10× 連續變焦的換鏡頭時機與掉幀、各倍率照片尺寸；錄影串流 30 / 60 fps、錄影中拍照；60 fps 與全尺寸照片串流不能並存，2026-10-01 |
