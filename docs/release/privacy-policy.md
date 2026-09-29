# Anomalops 隱私權政策（草案）

2026-09-29 · 狀態：草案，發布前要確認第 6 節各項

發布到 GitHub Pages 並在 Play Console 填寫網址（[checklist.md](checklist.md) R2）。本文以 v1.0 的功能與兩個建置 flavor（ADR-0010）為準；功能改變時要同步修改，特別是 v1.1 的 FR-42（照片寫入 GPS 定位）。

---

## Anomalops 隱私權政策

生效日期：（發布時填入）

Anomalops 是開源的水下相機 APP，原始碼公開於 <https://github.com/tengigabytes/Anomalops>。本 APP 沒有帳號、沒有廣告，也沒有自己的伺服器。

### 1. APP 在手機上產生的資料

| 資料 | 用途 | 存放位置 | 如何刪除 |
| --- | --- | --- | --- |
| 照片（JPEG / Ultra HDR JPEG）與你選擇保留的 RAW（DNG） | 你拍攝的成品 | 手機的「相片」媒體庫，資料夾 `Pictures/Anomalops` | 用相簿或檔案管理 APP 刪除；解除安裝 APP 不會刪除 |
| 潛水鎖定期間的感測紀錄：氣壓、環境光、磁力計、電池溫度與電量、系統熱狀態、觸控位置與時間、每次拍攝的相機設定 | 事後分析殼內操作與發熱 | APP 專屬的儲存空間（`Android/data/io.github.tengigabytes.anomalops/files/dives/`） | 解除安裝 APP 時由系統刪除；也可用檔案管理 APP 手動刪除 |
| 設定：已裝濾鏡、潛水鎖定狀態 | 下次開啟時沿用 | APP 私有設定檔 | 解除安裝或在系統設定清除 APP 資料 |

上述資料只存在你的手機上。APP 不讀取位置、聯絡人、麥克風或其他 APP 的檔案。

### 2. 權限

- **相機**：拍照與預覽。
- 其他權限依建置版本而不同，見第 3 節。

### 3. 網路與第三方

- **GitHub 與 F-Droid 發行的版本（`foss`）**：APP 不宣告網路權限，因此無法把任何資料傳出手機。
- **Google Play 版本（`play`）**：包含 Google Play 帳務程式庫，只用來提供自願的樂捐。為此 APP 具有網路權限。你進行樂捐時，付款由 Google Play 處理，Anomalops 的開發者只會收到 Google 提供的交易紀錄，不會取得你的付款資料。Google Play 帳務程式庫可能會把自身的使用紀錄傳送給 Google，這部分受 [Google 隱私權政策](https://policies.google.com/privacy)規範。除此之外，APP 不會傳送照片、感測紀錄或其他資料。

### 4. 兒童

本 APP 不以兒童為對象，也不收集任何人的個人資料。

### 5. 聯絡與變更

問題請在 GitHub 專案開 issue：<https://github.com/tengigabytes/Anomalops/issues>。本政策修改時會更新生效日期，修改紀錄可在 GitHub 查到。

---

## 6. 發布前要確認的事項（不隨政策發布）

| 項目 | 目前狀態 | 確認方法 |
| --- | --- | --- |
| Play 帳務程式庫的 `datatransport` 會回傳哪些資料 | 推測會傳回使用紀錄（[m0-play-billing.md](../test/m0-play-billing.md)） | 查 Google 對 Play Billing Library 的資料安全揭露；必要時以網路監看實測。資料安全表單要與本文一致 |
| 開發者收到的交易資料範圍 | 推測只有 Play Console 的訂單紀錄 | 開通 Payments 後查 Play Console 顯示的欄位 |
| 解除安裝時系統刪除 `Android/data/.../files/` | 依 Android 的 APP 專屬儲存空間規則，推測成立 | 實機解除安裝後檢查 |
| 感測紀錄的欄位 | 依 ADR-0008 與 `SessionRows`（M3） | M3 定稿後核對第 1 節表格 |
| v1.1 的 FR-42 GPS 定位、FR-96 潛點座標 | v1.0 沒有 | 功能加入前改寫第 1、2 節，並加上位置權限 |
| 英文版 | [privacy-policy.en.md](privacy-policy.en.md) | 與本文同步修改 |
