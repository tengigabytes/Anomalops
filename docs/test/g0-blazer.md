# G0 能力偵測結果：Pixel 10 Pro（blazer）

2026-09-28 · 狀態：已完成（查詢層級）

| 項目 | 內容 |
| --- | --- |
| 裝置 | Pixel 10 Pro，代號 `blazer`，Tensor G5 |
| 系統 | Android 17，SDK 37.1，build `CP3A.260905.009`，安全性修補 2026-09-05 |
| 工具 | `:tools:probe`（schema `anomalops-probe/3`，含 5 秒感測器取樣與鏡頭內參；2026-10-01 起 `anomalops-probe/4` 加上 GPU 與 NNAPI，見第 6 節） |
| 原始報告 | `tools/probe/results/blazer-20260928.json`（約 220 KB，請用 python / jq 查詢，不要整份讀取） |

**「查詢層級」的意思**：這次只讀取了 `CameraCharacteristics`，並向 HAL 詢問輸出組合能否使用（`isSessionConfigurationSupported`），沒有實際串流或拍照。實際行為在 G1 驗證。

## 1. 鏡頭組成

| 相機 ID | 類型 | 焦距 | 最近對焦 | 最大輸出（JPEG / JPEG_R / RAW） |
| --- | --- | --- | --- | --- |
| 0 | 後置邏輯鏡頭（`LOGICAL_MULTI_CAMERA`），`FULL` | 6.9 mm | 9.52 D（約 10.5 cm） | 4080×3072 |
| 2、5 | 主鏡頭（實體） | 6.9 mm | 9.52 D | 4080×3072 |
| 3、9 | 超廣角（實體） | 2.02 mm | 50 D（2.0 cm） | 4032×3024 |
| 4、6 | 望遠（實體） | 17.9 mm | 3.33 D（約 30 cm） | 4032×3024 |
| 1 | 前置邏輯鏡頭；實體鏡頭為 7、8 | 2.71 mm | 5 D | 3440×2448 |

- `cameraIdList` 裡只有 `0`、`1`。實體鏡頭不能單獨開啟，只能從邏輯鏡頭用 `setPhysicalCameraId` 取用。
- 同一顆鏡頭出現兩個實體 ID（2/5、3/9、4/6）。**已確認：5、9、6 是 2、3、4 的 2 倍中央裁切模式**，見 1.1 節。
- 2026-09-30 補充（[macro-stacking-test-plan.md](macro-stacking-test-plan.md) T2，從同一份 probe 報告讀出）：後置 6 個實體 ID 的距離校準都是 `APPROXIMATE`。超焦距：2 為 0.169 D、5 為 0.085 D、3 為 1.167 D、9 為 0.583 D、4 為 0.0245 D、6 為 0.0122 D；2× 裁切的超焦距恰為一半。

### 1.1 重複實體 ID 的實驗（2026-09-28）

**中繼資料**：5、9、6 的感光元件實體尺寸剛好是 2、3、4 的長寬各一半，像素數相同，換算像素間距也剛好一半，符合「中央裁切、不合併像素」。但它們的鏡頭內參（以像素計的焦距）與 `binningFactor` 和 2、3、4 完全相同，和實體尺寸矛盾；推測是 HAL 沿用了合併模式的值。

**實拍比對**：手機固定對著書架，用 `FovTestActivity` 從每個實體 ID 取一幀 640×480 預覽，只在記憶體裡縮成亮度格點比較，不存檔。比較「完整畫面縮小」與「完整畫面的中央一半」哪個更像另一個 ID 的畫面（正規化互相關，1 為完全相同）：

| 配對 | 與完整畫面 | 與中央一半 |
| --- | --- | --- |
| 主鏡頭 2 / 5 | 0.40 | **0.73** |
| 超廣角 3 / 9 | 0.19 | **0.92** |
| 望遠 4 / 6 | 0.10 | **0.62** |

**邏輯鏡頭各縮放倍率實際使用的實體 ID**：0.51× → 3；1×、1.5×、2×、3×、5× → 2；10×、20× → 4。裁切模式 5、9、6 不會出現在這個欄位。5× 仍是主鏡頭，可能因為書架距離小於望遠最近對焦距離（約 30 cm）或光線不足（推測）。

| ID | 模式 | 視角 | 輸出 | 適合 |
| --- | --- | --- | --- | --- |
| 2 / 3 / 4 | 整片感光元件，2×2 合併像素 | 原生 | 12 MP | 一般拍攝、低光（像素面積大） |
| 5 / 9 / 6 | 中央 1/4，不合併像素 | 2× | 12 MP | 不靠數位放大取得 2× 視角；9 可做 FR-31 的「中心裁切不低於 12 MP」 |

## 2. 各 ADR 的 G0 答案

| ADR / 需求 | 問題 | 結果 |
| --- | --- | --- |
| ADR-0001 | 各鏡頭是否支援手動控制與 RAW | 所有邏輯與實體鏡頭都是 `FULL`，都有 `MANUAL_SENSOR`、`MANUAL_POST_PROCESSING`、`RAW`、`BURST_CAPTURE` |
| ADR-0001、FR-95 | 能否透過 `setPhysicalCameraId` 使用實體鏡頭 | 後置 7 個實體鏡頭 × 4 種組合，HAL 全部回報支援 |
| ADR-0002 | 手動白平衡的請求鍵 | 邏輯鏡頭有 `colorCorrection.gains`、`transform`、`mode`、`control.awbMode`；實體鏡頭的請求鍵也有 `gains` 和 `transform`，所以可以對每顆實體鏡頭分別設定白平衡 |
| ADR-0004 | JPEG_R | 所有鏡頭都支援。**HEIC 不在輸出格式裡**，所以 v1.1 的 10-bit HEIC（FR-61）要自建編碼 |
| ADR-0004 | 10-bit | 動態範圍設定檔有 `STANDARD` 和 `HLG10`，可以輸出 10-bit HLG |
| ADR-0005 | RAW 預設尺寸 | 主鏡頭 4080×3072 = 12.53 MP，符合原本推測的 12.5 MP。最大解析度模式下沒有 RAW 輸出 |
| ADR-0005 | 成品與 RAW 能否同一請求 | `JPEG+RAW`、`JPEG_R+RAW`、`YUV1080+JPEG_R+RAW` 全部回報支援 |
| ADR-0005、FR-64 | 未壓縮 DNG 大小 | 12,533,760 像素 × 2 byte = 25.07 MB，比 v1.1 的門檻 25 MB 多 0.07 MB（還沒加上中繼資料） |
| ADR-0009 | 自動曝光的幀率範圍 | 最低 15 fps，所以自動曝光最長會用到 1/15 s，無法用幀率範圍限制在 1/125 s，**確認需要 ADR-0009 的換算** |
| ADR-0009 | ISO 範圍（主鏡頭 ID 2） | 21–5333，類比增益上限 333；超過 ISO 333 之後是數位增益 |
| ADR-0008、NFR-4 | 時間戳來源 | `REALTIME`，可以直接和 `elapsedRealtimeNanos` 對齊 |
| FR-31 | 微距最近對焦 | 超廣角 50 D，即 2.0 cm，符合 ≤ 5 cm 的要求 |
| FR-81 | 閃光燈 | 手機有閃光燈，AE 模式包含 `ON_AUTO_FLASH`、`ON_ALWAYS_FLASH`，所以 ADR-0009 的「禁止閃燈 AE 模式」規則是必要的 |
| FR-17a | 相機擴充 | 兩個邏輯鏡頭都**只有 `NIGHT`，沒有 `HDR`** |
| FR-94（v1.1） | LOG 色調曲線 | 有 `tonemap.mode` 和 `tonemap.curve` 請求鍵 |

## 3. 感測器（FR-45、ADR-0008）

| 感測器 | 型號 | 最長取樣間隔 | 1 Hz 記錄 |
| --- | --- | --- | --- |
| 氣壓計 | SPL07003 | 1,000,000 µs | 可以直接用 1 Hz |
| 環境光 | TMD3743（數值改變時才回報） | 1,000,000 µs | 可以 |
| 磁力計 | MMC5616 | 800,000 µs | 最慢只能 1.25 Hz，要自己降到 1 Hz |
| 環境溫度（type 13） | **沒有** | — | 和 ADR-0008 的推測一致 |
| 氣壓計溫度（`com.google.sensor.pressure_temp`） | SPL07003 | 1,000,000 µs | 可以。**ADR-0008 沒預料到**，可以代表殼內空氣溫度，建議加進 `sensors.csv` |
| 陀螺儀溫度 | ICM45631 | 666,667 µs | 可以。實測約 50 Hz，讀數 35.9 °C |
| 紅外線溫度計（`fir_temperature`、`fir_extended_temperature`） | MLX90632 | — | **不能用**：需要 `com.google.sensor.permission.FAR_INFRARED_TEMPERATURE`，保護等級 `signature\|preinstalled`（由 `com.android.pixeldisplayservice` 定義），第三方 APP 無法取得。實測 0 筆事件 |

5 秒取樣實測：氣壓計溫度 118 筆（約 25 Hz）、平均 35.8 °C；陀螺儀溫度 247 筆、平均 35.9 °C。兩者每筆回傳 16 個數值，只有第 1 個有意義，其餘為 0。

**水溫**：原本考慮用 FIR 溫度計隔著殼窗量水溫（熱傳導估算顯示窗口內側溫度接近水溫），但因上述權限限制無法實作。氣壓計與陀螺儀溫度是晶片溫度，會被手機發熱墊高，不能代表水溫。照片的水溫只能靠 FR-44（潛水電腦 Log，v1.1）或 FR-85 的 BLE 感測模組。

偵測當下的狀態：熱狀態 `NONE`，10 秒熱餘裕 0.546，電池溫度 30.7 °C。

## 4. 新發現的問題

**JPEG_R 的 stall duration 是 150 ms，JPEG 是 0 ms。**

| 格式 | 最短幀間隔 | stall | 推算連拍上限 | FR-15 要求 |
| --- | --- | --- | --- | --- |
| JPEG | 33.3 ms | 0 ms | 約 30 fps | ≥ 10 fps |
| JPEG_R | 33.3 ms | 150 ms | 約 6.7 fps | ≥ 10 fps |

如果每張 JPEG_R 都要多等 150 ms，連拍速度推算只有 6.7 fps，達不到 FR-15 要求的 10 fps。這是依 API 語意推算的，實際速度要在 G1 量測。

建議：連拍改用一般 JPEG，單張照片維持 JPEG_R。這會影響 FR-68 與 ADR-0004，要由產品擁有者決定。→ 2026-09-28 決定：採用建議，已更新 FR-68、mvp-scope.md、mvp-acceptance.md 與 ADR-0004。

## 5. 還沒驗證的

| 項目 | 何時驗證 |
| --- | --- |
| ADR-0002：`CaptureResult` 回報的增益是否等於請求值；手動白平衡下 JPEG_R 是否仍會產生增益圖 | G1，要實際拍攝 |
| ADR-0005：DNG 的壓縮方式與實際大小；緩衝滿載時的延遲 | G1 |
| ADR-0006：螢幕固定、通知、崩潰重啟等平台行為 | M0 的下一步 |
| ADR-0007：Pixel 6 Pro 的 Android 版本 | 需要那支手機 |
| ADR-0010：Play Billing 的授權與合併後 manifest 的權限 | M0，加入 `play` flavor 的相依之後 |
| ADR-0017：NNAPI 各裝置的功能等級 | 要用 NDK（`ANeuralNetworksDevice_getFeatureLevel`）才查得到，待維護者決定 |

2026-09-28 補充（M2）：`isSessionConfigurationSupported` 的回答不可靠。「預覽 + JPEG\_R + JPEG」與「預覽 + JPEG\_R + RAW + JPEG」回報支援，實際建立工作階段卻失敗，並使相機 HAL 程序重啟；「預覽 + JPEG\_R + RAW」與「預覽 + JPEG」在鏡頭 2、3、9 實際建立成功。見 [m2-stream-combos.md](m2-stream-combos.md)。

## 6. GPU 與 NNAPI（ADR-0017 第 1 步，2026-10-01）

原始報告 `tools/probe/results/blazer-20261001.json` 的 `gpu` 區段；能力表的 `capabilities.gpu`、`capabilities.nnapi` 由 `probe_to_profile.py` 產生。鏡頭與感測器部分和 09-28 的報告相同。

| 項目 | 結果 |
| --- | --- |
| GPU | Imagination PowerVR D-Series DXT-48-1536，驅動 `25.3@6908880` |
| OpenGL ES | 3.2（含 Android extension pack），GLSL ES 3.20，EGL 1.5 |
| Vulkan | 1.4.0，level 1，支援 compute；dEQP 等級 2026-03-01 |
| 工作群組 | 每軸最多 1024、合計 1024 個呼叫；群組數每軸 65535 |
| 共享記憶體 | 32 KiB |
| 運算著色器資源 | 影像 24 個、紋理單元 24 個、SSBO 35 個 |
| 最大紋理 | 32768 × 32768 |
| mediump | 尾數 10 位元、指數 ±15（片段著色器的回報；運算著色器不能查詢），即真正的半精度 |
| NNAPI | 執行環境功能等級 7；HAL 裝置 `google-edgetpu`（APP 內執行 `service list` 取得） |

格式測試：16 × 16 紋理，運算著色器寫入後以 `texelFetch` 讀回比對，數值 0.3–4078.3（12 位元 RAW 範圍）。

| 格式 | `imageStore` 寫入 | 同一張讀寫 | 當顏色輸出 |
| --- | --- | --- | --- |
| R16F | 不支援（編譯失敗：Unsupported image format） | 不支援 | 可 |
| RGBA16F | 可，往零捨入（見下） | 不支援 | 可 |
| R32F | 可，完全相同 | 可，完全相同 | 可 |
| RGBA32F | 可，完全相同 | 不支援 | 可 |

R16UI 以 `GL_UNSIGNED_SHORT` 上傳（RAW 路徑），256 個值讀回全部正確。

發現：

1. **寫入半精度是往零捨入，不是四捨五入。** RGBA16F 的 256 個值，全部等於「往零捨入」的結果，和四捨五入（round-to-nearest-even）不同。最大誤差 1.29 個 RAW 單位，平均 −0.58（一律偏小）。ADR-0017 第 3 節「最亮處的捨入約 1 個 RAW 單位」是照四捨五入估的；往零捨入時 2048–4095 之間最多將近 2 個單位，而且有固定的負偏差。只測了 `imageStore`；以 `glTexSubImage2D` 上傳成半精度、片段著色器輸出成半精度都沒測。推測：相對偏差平均約 0.03%，單次存放看不出來；每存一次半精度就偏一次，多層金字塔或多次存放會累積。第 3 步和 CPU 版比對時一併評估。
2. **只有 R32F 能在同一張影像上讀寫。** GLES 3.1 規格本來就如此（r32f、r32i、r32ui 以外要標 `readonly` 或 `writeonly`），已實測確認。逐張累加的總和與權重若放 RGBA32F，要兩張輪流（ping-pong）、拆成多張 R32F，或改用 SSBO。
3. **32 位元浮點紋理不能線性內插**（沒有 `GL_OES_texture_float_linear`）；半精度可以（`GL_OES_texture_half_float_linear`）。重新取樣要用硬體雙線性內插，來源得是半精度；用 32 位元就要在著色器裡自己內插。
4. **單通道半精度不能當 `imageStore` 目標。** 亮度平面若要半精度，可用 RGBA16F（空間 4 倍）、R32F、片段著色器輸出到 R16F，或 SSBO 搭配 `packHalf2x16`。
