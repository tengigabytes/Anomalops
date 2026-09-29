# 商店頁面文案（草案）

2026-09-29 · 狀態：草案，v1.0（M6）完成後依實際功能定稿

Google Play 與 F-Droid 共用的文案，臺灣正體中文與英文（[checklist.md](checklist.md) R2）。只寫功能與限制的事實，不寫評價或與其他產品比較。字數上限：名稱 30、簡短說明 80、完整說明 4000（Play Console）；本稿字數見第 3 節。

## 1. 臺灣正體中文

**名稱**：Anomalops 潛水相機

**簡短說明**：給觸控潛水殼中的 Pixel 用：分深度白平衡、潛水鎖定、大按鍵、按需保留 RAW。

**完整說明**：

Anomalops 是給裝在觸控式防水殼（例如 DiveVolk SeaTouch 4 系列）裡的 Pixel 手機使用的水下相機，開放原始碼。

拍攝
• 五種水下預設：快照、廣角、魚群、微距、低光。每種預設決定鏡頭、最長曝光與對焦方式。
• 白平衡分淺、中、深三段，另可設定已裝濾鏡（無、紅、洋紅）與潛水燈模式。白平衡直接套在相機的擷取請求上，預覽看到的顏色就是照片的顏色。
• 照片輸出 Ultra HDR JPEG；按住快門連拍。
• RAW 按需保留：拍攝後 10 秒內長按縮圖，才把該張存成 DNG，不佔用多餘空間。

潛水鎖定
• 下水前按一次進入：全螢幕、螢幕固定、最高亮度、不休眠，擋住返回與主畫面手勢。
• 所有按鍵距螢幕邊緣至少 12 mm、邊長約 11 mm，隔著凝膠膜也能按；快門是整條橫跨螢幕下緣的長條。
• 手機被系統上鎖時，相機畫面仍直接顯示；APP 意外結束時會自動回到鎖定畫面。
• 按住解鎖鍵 3 秒才會離開。
• 鎖定期間每秒記錄氣壓、光度、磁力計、電池溫度等感測資料，存在手機上供事後分析。

隱私
• 不需要帳號，不顯示廣告。
• GitHub 與 F-Droid 版本沒有網路權限；Play 版本只在樂捐時使用 Google Play 帳務服務。
• 照片與紀錄只存在你的手機上。

限制
• v1.0 只支援 Pixel 10 Pro。
• 本 APP 不量測水深，深度段由你手動切換；它不是潛水電腦。
• v1.0 只有拍照，沒有錄影。低光預設為單張拍攝，沒有多幀降噪。

原始碼與問題回報：https://github.com/tengigabytes/Anomalops（GPL-3.0-or-later）

## 2. English

**Title**: Anomalops Dive Camera

**Short description**: For Pixels in touch housings: depth white balance, dive lock, big keys, RAW.

**Full description**:

Anomalops is an open-source underwater camera for Pixel phones in touch-screen dive housings (such as the DiveVolk SeaTouch 4 series).

Shooting
• Five underwater presets: Snapshot, Wide, Fish school, Macro and Low light. Each sets the lens, the longest exposure and the focus mode.
• White balance in three depth bands (shallow, mid, deep), plus settings for a mounted filter (none, red, magenta) and a dive-light mode. White balance is applied in the camera's capture request, so the preview shows the colours the photo will have.
• Photos are saved as Ultra HDR JPEG; hold the shutter for a burst.
• RAW on demand: long-press the thumbnail within 10 seconds of a shot to keep it as a DNG, so no space goes to RAW files you do not want.

Dive lock
• One press before the dive: full screen, screen pinning, full brightness, no sleep, back and home gestures blocked.
• Every key is at least 12 mm from the screen edge and about 11 mm across, so it can be pressed through the gel membrane; the shutter is a strip across the bottom of the screen.
• When the phone locks itself, the camera stays on screen; if the app stops unexpectedly, it comes back in dive lock.
• Hold the unlock key for 3 seconds to leave.
• While locked, air pressure, light, magnetometer, battery temperature and more are logged once a second on the phone for later analysis.

Privacy
• No account and no ads.
• The GitHub and F-Droid editions have no network permission; the Play edition uses Google Play billing for donations only.
• Photos and logs stay on your phone.

Limitations
• Version 1.0 supports the Pixel 10 Pro only.
• The app does not measure depth; you switch the depth band yourself. It is not a dive computer.
• Version 1.0 takes photos only, no video. The low-light preset takes a single frame, without multi-frame noise reduction.

Source code and issue reports: https://github.com/tengigabytes/Anomalops (GPL-3.0-or-later)

## 3. 字數與待確認

| 欄位 | 中文（字元） | 英文（字元） | 上限 |
| --- | --- | --- | --- |
| 名稱 | 14 | 21 | 30 |
| 簡短說明 | 42 | 76 | 80 |
| 完整說明 | 730 | 1914 | 4000 |

待確認：
- 「APP 意外結束時會自動回到鎖定畫面」要等 NFR-1 實機 10 / 10 通過才能寫（M3 待測清單 `docs/test/m3-test-plan.md`，合併 M3 後改為連結）。
- 「按鍵約 11 mm」「距邊緣 12 mm」要等 G3 泳池量測定稿 FR-55 後核對。
- 提到 DiveVolk SeaTouch 4 是否需要對方同意或商標標示，上架前確認；可改為只寫「觸控式防水殼」。
- 截圖與主視覺圖另外準備，不放在本文。
