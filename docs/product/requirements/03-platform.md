# 3. 目標平台：Pixel 10 硬體與軟體能力盤點

[← 需求文件索引](README.md)

Pixel 10 有三項能力在防水殼內會失效：氣壓計測不到水深、GPS 與藍牙 / Wi-Fi 在水下無訊號、觸控需要隔著凝膠膜用力按壓。需求設計必須圍繞這三項限制展開。

| 項目 | Pixel 10 | Pixel 10 Pro / Pro XL |
| --- | --- | --- |
| 主鏡頭 | 48 MP、f/1.70、1/2 吋、82° | 50 MP、f/1.68、1/1.3 吋、82° |
| 超廣角 | 13 MP、f/2.2、120°、無 AF | 48 MP、f/1.7、123°、有 AF（微距） |
| 望遠 | 10.8 MP、f/3.1、5× | 48 MP、f/2.8、5×、OIS |
| 錄影 | 4K 24/30/60 | 4K 24/30/60、8K 24/30（Video Boost）、10-bit HDR、240 fps |
| 處理器 / RAM | Tensor G5、12 GB | Tensor G5、16 GB |
| 螢幕峰值亮度 | 3000 nits | 3300 nits |
| 感測器 | 氣壓計、磁力計、陀螺儀、加速度計、雷射 AF | 同左，另有光譜 / 閃爍感測器、溫度感測器 |
| 防水 | IP68（非潛水用） | IP68（非潛水用） |

上表是官方規格。第三方 APP 實際拿到的 RAW 是合併像素後的 12 MP（G0 實測 Pixel 10 Pro：超廣角與望遠 4032 × 3024，主鏡頭 4080 × 3072），另有三顆鏡頭的 2× 中央裁切，輸出同樣是 12 MP（[ADR-0012](../../adr/0012-macro-lens-selection.md)）。

軟體層面可用的能力：

- Camera2 API 的 MANUAL\_SENSOR（快門 / ISO / 對焦距離）與 MANUAL\_POST\_PROCESSING（自訂白平衡增益、色彩矩陣）。各鏡頭實際支援的 hardware level 與 RAW 尺寸需在實機以 CameraCharacteristics 驗證，尚未確認。
- RAW（DNG）單張與連拍輸出；水下白平衡在 RAW 上可事後無損重算。
- CameraX 的 Extensions 不包含自訂白平衡，v1 以 Camera2 為主。
- Tensor G5 可在裝置上執行小型影像模型（色彩還原、物種辨識），不依賴網路。
- Android 16 的高亮度模式與 120 Hz 顯示；水下預覽需鎖定最高亮度。

Google 原生相機的運算攝影功能大多不開放給第三方 APP，這是自建相機的代價；下表列出哪些不能用、哪些在水下本來就不重要。

| 原生功能 | 第三方可用性 | 水下重要性 | Anomalops 的替代 |
| --- | --- | --- | --- |
| HDR+ 多幀合成降噪 | 不開放；CameraX Extensions 的 HDR / Night 模式若裝置支援可用，但啟用後禁用手動白平衡與 RAW（需實機以 ExtensionsManager 查詢） | 高：深水、洞穴靠它壓噪點 | 自建對齊平均的 3–5 幀合成（FR-33 的對齊程式可共用）；接受低光畫質不如原廠 |
| Night Sight | 同上 | 低：需長時間靜止，水下做不到 | 潛水燈 |
| Super Res Zoom / Pro Res Zoom | 不開放 | 中：魚群、遠距大型生物 | 5× 光學望遠 + 一般裁切，不超過 10× |
| Top Shot / Best Take / Add Me | 不開放 | 低 | FR-14 預錄緩衝 + FR-69 自動淘選 |
| Video Boost（雲端處理） | 不開放 | 低 | 4K 60 10-bit HDR 本機錄影已足 |
| Real Tone 膚色 | 不開放 | 低 | 水下白平衡本就重寫色彩 |
| Camera Coach | 不開放 | 無 | — |
| 人像模式、動態模式、天文攝影 | 不開放 | 無 | — |
| Pro controls（快門 / ISO / 對焦 / 白平衡預設 / 50 MP） | 自建相機以 Camera2 自行實作，而且能做原生沒有的灰卡自訂白平衡 | 高 | 這是 Anomalops 存在的理由 |
| Magic Eraser、Photo Unblur、後製工具 | 屬 Google 相簿，對任何照片都能用 | 中：去懸浮物、去潛伴氣泡很實用 | 不受影響，Anomalops 的 HEIC / DNG 進相簿後照常使用 |

實際代價集中在一項：低光下的多幀降噪。陸上與水面照片可以直接切回原生相機，這也是為什麼下水前情境不需要 Anomalops 做到原生畫質。

在 DiveVolk SeaTouch 4 系列防水殼內的實際限制（本專案指定搭配的防水殼）：

| 手機能力 | 防水殼內狀態 | 需求上的回應 |
| --- | --- | --- |
| 氣壓計 | 殼體剛性，只有凝膠膜可微量內凹：入水後殼內氣壓應呈一次小幅階躍（估 10–50 hPa）後飽和，不再隨深度變化；降溫造成的氣壓下降與之同量級（待實測，FR-45） | 可用作入水 / 出水偵測；深度仍來自手動選擇、事後對齊潛水電腦，或未來的 BLE 感測模組 |
| GPS | 水下無訊號 | 以下水前最後定位寫入整趟照片 |
| 藍牙 / Wi-Fi | 水下無訊號 | 潛水電腦同步只在出水後進行 |
| 觸控 | 隔甘油凝膠膜，需裸指用力按壓，不支援手套 | 大型觸控目標、無需拖曳或多點手勢 |
| 實體按鍵 | 防水殼不提供快門按鍵 | 快門必須是螢幕上最大、最好按的元件 |
| 鏡頭外接 | 67 mm 螺紋可接 0.6× 廣角、+8 微距、紅 / 洋紅濾鏡 | APP 需有「已裝濾鏡」設定以調整白平衡起點 |

來源：[Pixel 10 Pro 規格 – Google Store](https://store.google.com/product/pixel_10_pro_specs?hl=en-US)、[Pixel 10 規格 – Google Fi](https://fi.google.com/about/phones/pixel-10-specs)、[SeaTouch 4 Max Review – Underwater Photography Guide](https://www.uwphotographyguide.com/divevolk-seatouch-4-max-review/)、[SeaTouch 4 系列比較 – DiveVolk](https://www.divevolkdiving.com/blogs/product-tips/divevolk-seatouch-4-comparison-guide)。
