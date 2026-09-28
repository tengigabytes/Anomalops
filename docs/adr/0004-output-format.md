# ADR-0004 v1.0 成品格式：JPEG_R，不支援時 JPEG

2026-09-28 · 狀態：已採納（產品擁有者 2026-09-28 決定）

## 背景

FR-61 原本定為 10-bit HEIC + 增益圖。要在 Pixel 上做到，需要 HAL 直接輸出，或是自建「10-bit YUV → HEVC 編碼 → HEIF 封裝」管線。HAL 是否直出尚未查證。

10-bit 的主要好處是白平衡重調時不出現色階斷層（FR-61 驗收標準），但 v1.0 不做 APP 內重新沖洗（FR-65 延後），白平衡重調一律在 DNG 上做。

## 決策

- 所選鏡頭的 `StreamConfigurationMap` 列出 `ImageFormat.JPEG_R` 時，輸出 Ultra HDR JPEG；否則輸出一般 JPEG。這一條記為 FR-61a。
- 成品經 MediaStore 寫入 `Pictures/Anomalops/`。
- 10-bit HEIC 延到 v1.1。屆時依 G0 結果，決定用 HAL 直出還是自建編碼。

## 不採用的選項

- **v1.0 就做 10-bit HEIC**：可能要自建編碼管線，而它的主要好處在 v1.0 用不到。
- **只輸出一般 JPEG**：JPEG\_R 是向下相容的 JPEG，不支援的檢視器照樣能看，又能保留水面光束、潛水燈熱點等高光，沒有理由不用。

## 後果

- 白平衡重調只能在 DNG 上做。沒保留 DNG 的照片，白平衡等於定案，所以 FR-62 的保留操作必須好按，這是泳池測試的重點。
- 連拍（FR-68）也用同一格式。
- 5.1 節捷徑表中的 JPEG\_R 列，改為「v1.0 的成品格式」。

## 驗證

- G0：主鏡頭、超廣角、望遠是否各自支援 JPEG\_R。
- G0：關閉 AWB、手動設定增益時，JPEG\_R 仍正常產生增益圖（以 `Bitmap.hasGainmap()` 檢查）。
- G1：20 張平均檔案大小 ≤ 6 MB（FR-61 原門檻沿用到 FR-61a）。

2026-09-28 G0 結果（Pixel 10 Pro）：見 [g0-blazer.md](../test/g0-blazer.md)。JPEG_R 所有鏡頭支援；HEIC 不在輸出格式中。JPEG_R 的 stall 為 150 ms，影響連拍速度，待決。

2026-09-28 補充：因 JPEG_R 的 stall 為 150 ms，產品擁有者決定連拍（FR-68）改用一般 JPEG，單張照片維持 JPEG_R。上方「連拍（FR-68）也用同一格式」一句以此補充為準。
