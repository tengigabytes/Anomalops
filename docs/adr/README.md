# 架構決策紀錄（ADR）

每份 ADR 記錄一項難以事後更改的架構決策。狀態有四種：

- **提議**：待產品擁有者確認
- **已採納**：已確認，實作依此進行
- **已取代**：被後來的 ADR 取代，保留原文並註明取代者
- **已否決**：討論後不採用，保留理由

凡是標為「推測」的技術前提，都列在該 ADR 的「驗證」一節，在關卡 G0（能力偵測）或 G1（桌測）確認。確認結果與推測不同時，修改或取代該 ADR。

| ADR | 標題 | 狀態 | 相關需求 |
| --- | --- | --- | --- |
| [0001](0001-camera2.md) | 直接使用 Camera2，不用 CameraX | 已採納 | 第 3 節、FR-31、FR-81、FR-91 |
| [0002](0002-wb-in-capture-request.md) | 白平衡與色彩矩陣在擷取請求層套用 | 已採納 | FR-21、FR-24、FR-25、FR-91 |
| [0003](0003-device-profiles.md) | 每型號能力表與校正表以資料檔描述 | 已採納 | NFR-9、第 8 節 |
| [0004](0004-output-format.md) | v1.0 成品格式：JPEG\_R，不支援時 JPEG | 已採納 | FR-61、FR-61a、FR-68 |
| [0005](0005-raw-ring-buffer.md) | RAW 按需保留：逐張擷取、記憶體環形緩衝、DngCreator | 已採納 | FR-16、FR-62、FR-64 |
| [0006](0006-dive-lock.md) | 潛水鎖定：螢幕固定 + 沉浸模式 + 鎖定畫面上顯示 | 已採納 | FR-51、FR-55、FR-56、NFR-1 |
| [0007](0007-stack-and-modules.md) | 技術堆疊與模組切分 | 已採納 | NFR-8、NFR-9、FR-84 |
| [0008](0008-depth-telemetry.md) | 深度與遙測來源抽象、感測記錄 | 已採納 | FR-45、FR-84 |
| [0009](0009-exposure-shutter-priority.md) | 曝光控制：預覽自動曝光，拍攝時換算為快門優先 | 已採納 | FR-11、FR-81、FR-91 |
| [0010](0010-flavors-and-donations.md) | 建置 flavor 與樂捐：play 用 Play Billing，foss 用外部連結 | 已採納 | NFR-8、發行 |
| [0011](0011-logical-viewfinder.md) | v1.1 取景與錄影改用 logical 串流，連續變焦跨鏡頭 | 提議（暫定，待實驗） | FR-13、FR-13a |
| [0012](0012-macro-lens-selection.md) | 微距鏡頭選擇：廣角微距與長焦微距 | 提議 | FR-31、FR-33、FR-36 |
| [0013](0013-tele-physical-lens-access.md) | 長焦微距的望遠實體鏡頭存取與手動對焦 | 提議 | FR-32、FR-36、FR-95 |
| [0014](0014-focus-distance-calibration.md) | 對焦距離校準與包圍步進 | 提議 | FR-33、FR-36、NFR-9 |
| [0015](0015-focus-stacking-algorithm.md) | 景深合成演算法 | 提議（演算法待定） | FR-33、FR-63、NFR-8 |
| [0016](0016-imaging-module.md) | 多幀影像處理放在新模組 `:core:imaging` | 已採納 | FR-17、FR-33 |
| [0017](0017-gpu-multiframe.md) | 多幀運算在手機上走 GPU：OpenGL ES 運算著色器、逐張累加 | 已採納 | FR-17、FR-33、NFR-9 |

## 範本

```markdown
# ADR-NNNN 標題

日期 · 狀態：提議

## 背景
要解決什麼問題、受哪些需求與限制約束。

## 決策
採用什麼。

## 不採用的選項
每個選項一行，附理由。

## 後果
好處、代價、之後會受影響的決策。

## 驗證
哪些前提是推測、何時用什麼方法確認。
```
