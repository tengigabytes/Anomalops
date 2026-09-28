# M4 實機儀器測試：深度切換與感測紀錄（Pixel 10 Pro）

2026-09-28 · 狀態：FR-84 裝置端切換與 FR-45 取樣器一分鐘測試通過；FR-45 90 分鐘驗收待 G2

分支 `m4/depth-and-conditions`。結果從 logcat 標籤 `M4Acceptance` 讀取，測試期間在背景持續寫檔，測完刪除。測試不拍照；感測紀錄的場次目錄在測試結束時刪除，原始資料不進 repo。

## 1. 結果

| 項目 | 條件 | 結果 | 門檻 | 判定 |
| --- | --- | --- | --- | --- |
| FR-84 切換 | 預設「隨手拍」（鏡頭 2），`ManualDepthSource` 淺 ↔ 深交替 20 次，經 `ShootingConditions` 與 `ConditionsFollower`，與拍攝畫面相同的接法 | 20 / 20 在新色彩設定的第一個預覽畫格出現；淺水為手動色彩、深水退回近似自動；全程同一鏡頭、未重建工作階段、狀態保持 PREVIEWING | 每次 3 s 內 | 通過 |
| FR-84 延遲 | 從切換深度段到第一個新設定畫格抵達 APP | 中位數 196.7 ms、p95 206.9 ms、最大 223.2 ms | 無正式門檻；參考 NFR-4 同鏡頭 p95 ≤ 300 ms | 206.9 ≤ 300 |
| FR-45 檔案 | `DiveLog` 開在 APP 外部檔案目錄的 `dives/<場次 ID>/` | 四個檔案都在，`sensors.csv` 標頭正確 | 四個檔案 | 通過 |
| FR-45 列數 | `SensorRecorder` 錄 60 s | 61 列，依時間跨度應有 61 列；間隔中位數 999.9 ms、最短 980.6 ms、最長 1017.2 ms；時間戳嚴格遞增 | ≥ 應有的 98% | 61 ≥ 59.8，通過 |
| FR-45 欄位 | 同上 | 氣壓、光度、磁力計三軸、電池溫度、電量、熱狀態、熱餘裕 61 / 61 列有值；氣壓計溫度 60 / 61（第一列時尚未回報） | 必要欄位最多缺第一列 | 通過 |

裝置上找到的感測器：`android.sensor.pressure`、`android.sensor.light`、`android.sensor.magnetic_field`、`com.google.sensor.pressure_temp`，與 G0 一致（[g0-blazer.md](g0-blazer.md)）。

## 2. 發現

- **條件切換的時序問題（已修正）**：第一版拍攝畫面以 `keys.drop(1)` 跳過開預覽時已用的條件。儀器測試在收集開始前就切換深度段，收集到的第一個值已是「深」，被丟掉，結果沒有送出請求，等待 3 s 逾時。改為 `ConditionsFollower` 記住相機目前拿到的條件：開預覽與開始收集誰先都不會漏送或重送；預覽停止期間的變更留到下次開預覽時套用。JVM 單元測試 `ConditionsFollowerTest` 涵蓋這三種情況。
- **電池溫度**：以 `RECEIVER_NOT_EXPORTED` 取得系統的電池黏性廣播可行，每列都有值（原標示 `UNVERIFIED(G1)`，已確認）。
- **熱餘裕**：每秒呼叫 `getThermalHeadroom(0)`，60 s 內每次都有數值，沒有出現 NaN。
- **按 Home 時的 `ConsumerBase ... abandonLocked` 錯誤訊息**：拍攝畫面按 Home 時，兩個 4080×3072 的 ImageReader 各記一行 E 級訊息。以 `main`（9a37ffb）建置同樣操作也出現，不是本分支造成；APP 回到前景後相機重新連線正常。推測是關閉 ImageReader 時的正常紀錄，與 M1 修掉的 reader 洩漏（「Abandoned BufferQueue」）不同，未另外追查。

## 3. 未涵蓋

- 條件改變時的請求值由預覽畫格上的請求標記確認；結果中的白平衡模式與增益沒有另外讀回比對。
- 拍攝畫面還沒有深度、濾鏡、潛水燈開關（M3），APP 內的即時切換只能用儀器測試觸發。
- FR-45 的開始與結束要接在 M3 潛水鎖定；90 分鐘列數驗收在 G2 以 `scripts/check_dive_log.py --minutes 90` 執行。
- 測試在儀器測試程序中執行時，連續回報型感測器仍有資料；APP 在背景時的行為未測（潛水鎖定期間 APP 在前景）。
