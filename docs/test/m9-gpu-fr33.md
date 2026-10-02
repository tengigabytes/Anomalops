# M9 前置：FR-33 候選 A、B 上 GPU（ADR-0017 第 5 步）

2026-10-02 · 狀態：候選 A、B 完成，與 CPU 相同（相對差 ≤ 4 × 10⁻⁷）；6 張 3 色 0.44–0.56 s，不含對齊與 `StackGuard`

手機 blazer（Pixel 10 Pro），溫度狀態 0。量法同 [m9-gpu-fr17.md](m9-gpu-fr17.md)：計時用非 debug 版（暫時改 `:app` 的 debug 建置，沒有提交，量完已裝回 debug 版），有前景畫面。

## 1. 寫法

- `GpuFocusAccumulator`：GPU 版的 `FocusAccumulator`，逐張加入、`passes` 輪、`finish` 交回合成的平面。
- `GpuContrastSelect`（候選 A，對應 `ContrastSelectStack`）：照 2026-10-02 維護者決定的兩輪。第一輪每張算清晰度（3 × 3 平均 → 拉普拉斯 → 平方 → 9 × 9 平均），記下每個像素的最高分與張號；第二輪每張的權重是「選中這張」的 13 × 13 平均，除以各張權重的總和，乘上各色版加總。
- `GpuLaplacianPyramid`（候選 B，對應 `LaplacianPyramidStack`）：每張建高斯金字塔（`PlaneKernels.half`），每層的帶通（本層減去上一層放大）；每層以亮度帶通的 3 × 3 絕對值平均為分數，分數較高的那張，各色版的係數留下；最粗一層加總，結束時取平均再逐層合回去。
- 共用的核心（`StackKernels`、`StackShaders`）：盒狀平均拆成橫、直兩段加總（邊緣裁切，除以實際的窗格面積）、拉普拉斯、放大兩倍（`Filters.double` 的座標）。
- 與 CPU 唯一刻意的差異：CPU 的盒狀平均用 double 的累加表，GPU 是 float 加總；B 最粗一層的總和 CPU 是 double，GPU 是 float。所以分數幾乎相同的兩張，有可能選到另一張。
- 每張可以上傳到同一組平面（`PlaneTransfer.upload` 的 `into`）。A 的第二輪要再讀一次每張，如同再讀一次 RAW 緩衝。

## 2. 正確性

`:core:gpu` 的 `GpuFocusStackTest`（debug 版）：1024 × 768，5 張含雜訊（±15）的焦點包圍，每張只在自己的橫帶清晰、其他地方模糊，亮度加一個衍生色版；CPU 的 `merge` 與 GPU 逐張加入比對。

| 候選 | 最大相對差（兩個色版） | 差超過 10⁻³ 的像素 | 與清晰原圖的平均誤差（CPU／GPU） |
| --- | --- | --- | --- |
| A | 2.4 × 10⁻⁷、2.8 × 10⁻⁷ | 0（共 786 432） | 8.51／8.51 |
| B | 4.0 × 10⁻⁷、3.6 × 10⁻⁷ | 0（共 786 432） | 8.95／8.95 |

- 預期可能發生的「近乎平手而選到另一張」，這次沒有出現。測試的上限是提議值：差超過 10⁻³ 的像素至多 0.5%。

## 3. 速度

`:app` 的 `experiment/GpuStackBenchmark`：6 張 2040 × 1536（12.5 MP RAW 的半尺寸平面），亮度加 3 個色版（由亮度衍生），每張上傳到同一組 4 個平面再加入；不含對齊、不含 `StackGuard`。每個候選跑 3 次：

| 候選 | 上傳 | 加入 | 結束 | 合計 |
| --- | --- | --- | --- | --- |
| A（兩輪，上傳 12 次） | 194.5–259.1 ms | 210.0–269.1 ms | 1.7–24.9 ms | 452.1–560.2 ms |
| B（一輪） | 122.5–127.1 ms | 281.3–303.0 ms | 32.7–58.6 ms | 441.1–484.2 ms |

- CPU 版（[m9-imaging-phone.md](m9-imaging-phone.md) 第 4 節，6 張、只算亮度、含 `StackGuard`）：A 8.6 s、B 15.6 s。條件不同（GPU 多算 3 個色版、少了 `StackGuard`），只能看量級。
- T12 的提議目標是 6 張 12 MP ≤ 10 s（[macro-stacking-test-plan.md](macro-stacking-test-plan.md)）；GPU 合成本身約 0.5 s，還要加上解碼、對齊與 `StackGuard`。

## 4. 還沒做的

- `StackGuard`（合成不比最清晰的單張清楚時退回單張、缺的像素補參考幀）上 GPU。
- 與 FR-17 的解碼、對齊接成一條，量 FR-33 從 RAW 到 ARGB 的整段時間。
- 候選 C（引導濾波權重）仍要收齊全部張數，沒有上 GPU。
- T12 實拍比較（需要實拍的焦點包圍）。
