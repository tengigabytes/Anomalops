# :core:imaging

多幀影像處理（ADR-0016）。純 Kotlin/JVM 模組，不依賴 Android 與其他專案模組，單元測試可在任何環境執行。

| 項目 | 內容 |
| --- | --- |
| 職責 | RAW 解成線性 RGB、同一組連拍的對齊、景深合成（FR-33）、低光多幀合成（FR-17）；之後的超解析（FR-18、FR-19） |
| 依賴 | 無 |
| 被誰使用 | 尚未接上；之後由 `:app` 使用 |
| 對應需求 | FR-17、FR-33（ADR-0015） |
| 內容 | `align/`：`Plane`（單通道線性數值、雙線性取樣、減半；`fromBayer` 由 RAW 的 2 × 2 格合成亮度）、`Pyramid`、`Similarity`（以畫面中心縮放加位移）、`GlobalAligner`（整張的縮放與位移，由粗到細窮舉）、`GaussNewton`（整張的次像素求精；均方差的拋物線擬合在雜訊或清晰度不同時有偏差，見 ADR-0015 補註）、`TileAligner`（每 32 像素一塊的殘差位移、代價與紋理可信度）、`FrameAligner`（對外入口：一次建好參考影像的金字塔，逐張對齊；`warp` 依整張變換重取樣） |
| 內容（解碼） | `develop/`：`CfaLayout`（執行時讀的色彩排列）、`RawFrame`（RAW_SENSOR 樣本、各格黑位、白位）、`Demosaic.halfSize`（每格一個 RGB，快）與 `Demosaic.bilinear`（全尺寸）、`ColourPipeline`（白平衡增益加色彩矩陣，ADR-0002）、`Rgb.luma`（Rec. 709 亮度） |
| 內容（輸出） | `develop/`：`Render.toArgb`（ADR-0015 最後一步：相機 RGB → 8-bit sRGB ARGB；依序為 RAW 接近剪切的程度、鏡頭陰影補償、白平衡、色彩矩陣、曝光、高光往中性灰混合、以最亮色版套色調曲線（保持色相）、sRGB 編碼）、`RenderOptions`（曝光、高光起點 0.9、曲線肩部 0.9、白點 2；都是提議值，外觀未定）、`ShadingMap`（`STATISTICS_LENS_SHADING_CORRECTION_MAP` 的四色版增益格點，雙線性內插；RAW 是否已補償、2× 裁切串流的對應範圍待實機確認） |
| 內容（景深合成） | `stack/`：`FocusStack` 介面（以亮度決定、各色版照同一決定合成），ADR-0015 的三個候選：`ContrastSelectStack`（A，局部對比選取加平滑）、`LaplacianPyramidStack`（B，拉普拉斯金字塔逐層取最大係數）、`GuidedWeightStack`（C，引導濾波修整權重）；`StackGuard`（合成不比最清晰的單張清楚時退回單張，缺的像素以參考幀補）；`Filters`（拉普拉斯、盒狀平均、引導濾波、放大） |
| 內容（低光合成） | `merge/`：`LowLightMerge`（FR-17 第一階段：逐張對齊後，依 3 × 3 鄰域與參考幀的差距相對雜訊給權重，差太多的像素幾乎不計入，避免移動的魚留下重影；`estimateNoise` 由拉普拉斯的中位絕對值估雜訊）、`OffsetField`（整張變換加上在塊中心之間內插的殘差位移） |
| 桌機工具（T12） | 測試資料夾的 `tool/`：`StackTool`（手機拉回的 DNG 依序解馬賽克、以中間那張為參考對齊、A／B／C 三個候選各合成一次並經 `StackGuard`、輸出 PNG 與最清晰的單張，印出時間與判定；`--lowlight` 改跑 FR-17、`--full` 用全尺寸）、`DngReader`（`DngCreator` 寫的未壓縮 Bayer DNG：黑白位、AsShotNeutral、D65 的 ForwardMatrix、OpcodeList2 的 GainMap 陰影表）。執行：`gradlew :core:imaging:stackTool --args="<輸出資料夾> <a.dng> <b.dng> ..."`。不進 APP；放在測試資料夾、不另開模組（2026-10-01 維護者同意）；尚未用手機的實拍 DNG 跑過 |
| 參數 | `AlignOptions`：最大位移 64 像素、縮放 ±3%、塊 32 像素、塊內搜尋 ±8 像素、紋理門檻為中位數的 0.1；都是提議值，待微距待測清單 T9、T10 的實拍資料修正 |
| 測試 | `LowLightMergeTest`：5 張帶手晃與雜訊的合成連拍，雜訊標準差 6.01 → 2.01（FR-17 要求降 ≥ 50%）；一隻每張多游 6 像素的魚，合成後魚的位置與參考幀差 4.81、與單純平均差 11.66（沒有重影）；雜訊估計、色版跟隨亮度<br>`RenderTest`：sRGB 編碼標準值、色調曲線（肩部前不變、肩部斜率 1、白點為 1、單調）、中間調照增益與矩陣通過、全剪切的高光成中性白、肩部保持色相、負值歸零、陰影表的雙線性內插與邊界、陰影補償在剪切判斷之後、灰色 RAW 端到端<br>`DngReaderTest`：合成的 DNG（GBRG、各格黑位、AsShotNeutral、兩組 ForwardMatrix、四張 GainMap）讀回、ForwardMatrix 換算後白色仍是白色、`StackTool` 對三張合成 DNG 寫出四張 PNG<br>`DemosaicTest`：四種色彩排列、各格黑位、純色還原、平滑色場的雙線性誤差、白平衡與矩陣的順序、亮度權重<br>`FocusStackTest`：斜放主體的合成包圍（5 張，各自清晰於一段深度），三個候選都要把誤差壓到最清晰單張的一半以下，加雜訊時要壓到四分之三以下；相同影像不變、色版跟隨亮度、`StackGuard` 保留好的合成與退回單張、補缺像素<br>`PlaneTest`：Bayer 合成與無號讀取、雙線性取樣、減半、金字塔座標換算、拋物線；`FrameAlignerTest`：以 `SyntheticScene`（解析式的高斯斑點場景，可渲染任意縮放、位移、模糊、局部移動）驗證相同影像、次像素位移、接近搜尋上限的大位移、焦點呼吸的縮放、模糊的景深合成幀、只差雜訊的靜止兩張、模糊參考張對清晰影像（後兩項在舊的拋物線求精上會失敗）、移動主體只出現在它的塊、`warp` |
| 速度 | 桌機 JVM，2040 × 1536（12 MP RAW 的亮度平面）：建參考金字塔約 31 ms，對齊一張約 0.82 s（2026-10-01 改用高斯牛頓後，另一個 6000 斑點的場景新舊都約 0.6 s）；6 張單色版景深合成 A 約 0.52 s、B 約 0.88 s、C 約 1.33 s；FR-17 低光合成 5 張單色版約 3.39 s；`Render.toArgb` 一張（含陰影表）約 90 ms（2026-10-01，各一次量測）。FR-17 要求手機上 12.5 MP × 5 張 ≤ 3 s，桌機的 CPU 版已超過，正式版要走 GPU；手機（非 debug 版，2026-10-01，[m9-imaging-phone.md](../../docs/test/m9-imaging-phone.md)）：對齊一張約 1.4 s、FR-17 五張只算亮度 11.8 s，FR-33 四張以上超出 256 MB 記憶體 |
