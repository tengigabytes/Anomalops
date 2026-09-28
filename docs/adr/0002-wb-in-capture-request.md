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
