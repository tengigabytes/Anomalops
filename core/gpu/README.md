# :core:gpu

多幀運算的 GPU 版（ADR-0017）：OpenGL ES 3.1 運算著色器，從 Kotlin 呼叫，不用 NDK。CPU 參考版在 `:core:imaging`，每支著色器都和對應的 CPU 函式比對。

| 項目 | 內容 |
| --- | --- |
| 職責 | 在 GPU 上做 FR-17 低光合成與 FR-33 景深合成的對齊、合成與輸出；目前是第 3 步試作 |
| 依賴 | `:core:imaging`（共用 `Plane`、`Similarity`、`Region` 與 CPU 參考函式） |
| 被誰使用 | 尚未接上；之後由 `:app` 使用 |
| 對應需求 | FR-17、FR-33（ADR-0015、ADR-0017） |
| 內容 | `GlesContext`（無畫面的 EGL 環境，建立它的執行緒才能用）、`ComputeProgram`（編譯、uniform、dispatch）、`GpuPlane` 與 `PlaneFormat`（`FLOAT32` 為 R32F；`HALF` 暫存在 RGBA16F 的紅色通道，因為 R16F 不能當寫入目標）、`PlaneTransfer`（上傳先進 32 位元紋理、在 GPU 上轉半精度；下載經儲存緩衝）、`PlaneKernels`（`half` 對應 `Plane.half`、`warp` 對應 `FrameAligner.warp`、`warpFiltered` 改用硬體雙線性）、`MeanSquaredDiffKernel`（一次算一批候選變換的 `meanSquaredDiff`，每個工作群組在共享記憶體加總，CPU 以 double 合計） |
| 測試 | 只有實機測試（要 GPU）：`PlaneKernelsTest`，2040 × 1536 的合成平面（12 位元 RAW 範圍），三個核心各與 CPU 版比對兩種格式的誤差並記錄 GPU 時間；誤差上限是提議值。執行：`gradlew :core:gpu:connectedDebugAndroidTest`，結果看 logcat 的 `PlaneKernelsTest` |
