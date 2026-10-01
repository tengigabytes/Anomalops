# :core:imaging

多幀影像處理（ADR-0016）。純 Kotlin/JVM 模組，不依賴 Android 與其他專案模組，單元測試可在任何環境執行。

| 項目 | 內容 |
| --- | --- |
| 職責 | RAW 解成線性 RGB、同一組連拍的對齊、景深合成（FR-33）；之後的多幀合成（FR-17）、超解析（FR-18、FR-19） |
| 依賴 | 無 |
| 被誰使用 | 尚未接上；之後由 `:app` 使用 |
| 對應需求 | FR-17、FR-33（ADR-0015） |
| 內容 | `align/`：`Plane`（單通道線性數值、雙線性取樣、減半；`fromBayer` 由 RAW 的 2 × 2 格合成亮度）、`Pyramid`、`Similarity`（以畫面中心縮放加位移）、`GlobalAligner`（整張的縮放與位移，由粗到細窮舉再以拋物線求次像素）、`TileAligner`（每 32 像素一塊的殘差位移、代價與紋理可信度）、`FrameAligner`（對外入口：一次建好參考影像的金字塔，逐張對齊；`warp` 依整張變換重取樣） |
| 內容（解碼） | `develop/`：`CfaLayout`（執行時讀的色彩排列）、`RawFrame`（RAW_SENSOR 樣本、各格黑位、白位）、`Demosaic.halfSize`（每格一個 RGB，快）與 `Demosaic.bilinear`（全尺寸）、`ColourPipeline`（白平衡增益加色彩矩陣，ADR-0002）、`Rgb.luma`（Rec. 709 亮度） |
| 內容（景深合成） | `stack/`：`FocusStack` 介面（以亮度決定、各色版照同一決定合成），ADR-0015 的三個候選：`ContrastSelectStack`（A，局部對比選取加平滑）、`LaplacianPyramidStack`（B，拉普拉斯金字塔逐層取最大係數）、`GuidedWeightStack`（C，引導濾波修整權重）；`StackGuard`（合成不比最清晰的單張清楚時退回單張，缺的像素以參考幀補）；`Filters`（拉普拉斯、盒狀平均、引導濾波、放大） |
| 參數 | `AlignOptions`：最大位移 64 像素、縮放 ±3%、塊 32 像素、塊內搜尋 ±8 像素、紋理門檻為中位數的 0.1；都是提議值，待微距待測清單 T9、T10 的實拍資料修正 |
| 測試 | `DemosaicTest`：四種色彩排列、各格黑位、純色還原、平滑色場的雙線性誤差、白平衡與矩陣的順序、亮度權重<br>`FocusStackTest`：斜放主體的合成包圍（5 張，各自清晰於一段深度），三個候選都要把誤差壓到最清晰單張的一半以下，加雜訊時要壓到四分之三以下；相同影像不變、色版跟隨亮度、`StackGuard` 保留好的合成與退回單張、補缺像素<br>`PlaneTest`：Bayer 合成與無號讀取、雙線性取樣、減半、金字塔座標換算、拋物線；`FrameAlignerTest`：以 `SyntheticScene`（解析式的高斯斑點場景，可渲染任意縮放、位移、模糊、局部移動）驗證相同影像、次像素位移、接近搜尋上限的大位移、焦點呼吸的縮放、模糊的景深合成幀、移動主體只出現在它的塊、`warp` |
| 速度 | 桌機 JVM，2040 × 1536（12 MP RAW 的亮度平面）：建參考金字塔約 31 ms，對齊一張約 0.82 s；6 張單色版景深合成 A 約 0.52 s、B 約 0.88 s、C 約 1.33 s（2026-10-01，各一次量測）；手機未量 |
