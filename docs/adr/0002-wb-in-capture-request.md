# ADR-0002 白平衡與色彩矩陣在擷取請求層套用

2026-09-28 · 狀態：已採納（FR-91，P0）

## 背景

水下白平衡是整個 APP 的核心。FR-91 要求預覽與成品看起來一致，灰卡 RGB 差異 ≤ 5%。

如果在預覽畫面上另外加 GPU 濾鏡，預覽和 ISP 產出的成品就是兩條不同的管線，永遠要各自校正。深水時紅色通道訊號很弱，後級濾鏡也救不回 ISP 已經量化掉的資訊。

## 決策

預覽請求與拍攝請求都設定同一組值：

| 擷取請求鍵 | 值 |
| --- | --- |
| `CONTROL_AWB_MODE` | `OFF` |
| `COLOR_CORRECTION_MODE` | `TRANSFORM_MATRIX` |
| `COLOR_CORRECTION_GAINS` | 校正表查得的 R / Geven / Godd / B 增益 |
| `COLOR_CORRECTION_TRANSFORM` | 校正表查得的 3×3 色彩矩陣 |

- 查表的鍵是「型號 × 實體鏡頭 × 深度段 × 已裝濾鏡 × 潛水燈模式」，表的格式見 ADR-0003。
- 預覽不加任何色彩濾鏡。
- 每個 `CaptureResult` 回報的增益與矩陣都和請求值比對。HAL 可能自行調整，不一致時寫進 FR-45 的紀錄。

## 不採用的選項

- **預覽加 GPU 濾鏡，成品在後級處理**：兩條管線，違反 FR-91；深水紅色通道已被量化，事後救回的有限。
- **讓 AWB 自動，再乘上偏移量**：AWB 在水下的收斂目標本身就不穩定，偏移量也不可重現。

## 後果

- 鏡頭必須支援 MANUAL\_POST\_PROCESSING。不支援的鏡頭退回自動白平衡 + 事後處理（[需求第 8 節](../product/requirements/08-risks.md)），介面上標示「白平衡為近似」。
- 深度段、濾鏡、燈光模式任一改變時，只要換一組請求值，不必重開工作階段。
- DNG 的 AsShotNeutral 由 DngCreator 從 CaptureResult 帶入（推測），所以 DNG 與成品的白平衡同源。

## 驗證

- G0：列出各實體鏡頭是否支援 MANUAL\_POST\_PROCESSING。
- G0：在 JPEG\_R 輸出下，確認手動增益確實反映在成品上（ADR-0004）。
- G1：用灰卡量測 FR-91 的 ≤ 5% 門檻，方法見 mvp-acceptance.md。
- G1：抽查 CaptureResult 的增益是否等於請求值。

2026-09-28 G0 結果（Pixel 10 Pro）：見 [g0-blazer.md](../test/g0-blazer.md)。所有實體鏡頭都有 `MANUAL_POST_PROCESSING`，實體鏡頭請求鍵含色彩校正增益與矩陣；`CaptureResult` 是否回報請求值仍待 G1。

2026-09-28 補充（M1，維護者同意）：鏡頭支援 `MANUAL_POST_PROCESSING`，但校正表沒有目前「實體鏡頭 × 深度段 × 濾鏡 × 潛水燈模式」的項目時，同樣退回自動白平衡，介面標示「白平衡為近似」。實作於 `:core:camera` 的 `RequestPlanner`（`ColorSpec.AutoApproximate`）。

2026-09-28 補充（M2）：上文「DNG 的 AsShotNeutral 由 DngCreator 從 CaptureResult 帶入（推測）」已驗證：手動白平衡增益 R 1.6532、B 2.3034 的照片，DNG 的 AsShotNeutral 為 [0.6045, 1, 0.4336]，即增益的倒數（[m2-raw-buffer.md](../test/m2-raw-buffer.md)）。
