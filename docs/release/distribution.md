# 發行與上架

2026-09-28 · 狀態：已採用。政策會變動，每次上架前重新確認。

## 1. 管道

| 管道 | flavor | 樂捐 | 簽章 |
| --- | --- | --- | --- |
| GitHub Release | `foss` | 「關於」頁面的外部連結 | 自有的發行金鑰 |
| Google Play | `play` | Play Billing 消耗型內購 | Play App Signing（Google 保管應用程式簽署金鑰，開發者持有上傳金鑰） |
| F-Droid | `foss` | F-Droid 的樂捐欄位加上 `FUNDING.yml` | 由 F-Droid 簽署；若採可重現建置，可改用開發者簽章 |

applicationId 為 `io.github.tengigabytes.anomalops`，所有管道相同（ADR-0010）。

## 2. Google Play 的條件

| 條件 | 內容 | 來源 |
| --- | --- | --- |
| 封閉測試 | 2023-11-13 之後建立的個人開發者帳號，要先讓至少 12 位測試者連續加入封閉測試 14 天，才能申請正式版上架 | [Play Console 說明](https://support.google.com/googleplay/android-developer/answer/14151465?hl=en) |
| 付款政策 | APP 與商店頁面都不得把使用者引導到 Play Billing 以外的付款方式；免稅捐款是例外，但實際認定很嚴 | [Payments policy](https://support.google.com/googleplay/android-developer/answer/9858738?hl=en)、[AnkiDroid #21656](https://github.com/ankidroid/Anki-Android/issues/21656) |
| 商家地址公開 | 開始收款（付費 APP 或內購）後，Payments 商家資料上的完整地址會公開顯示在 Play 商店頁面 | [PhoneArena 報導](https://www.phonearena.com/news/Google-Play-to-require-public-physical-address-for-monetized-app-developers_id60839)；以 Play Console 顯示為準 |
| 歐盟交易者身分 | 有收款可能要申報，並公開聯絡資訊（推測，未查證） | 以 Play Console 為準 |
| 隱私權政策 | 推測所有 APP 都要提供，並填寫資料安全表單 | 以 Play Console「應用程式內容」頁面為準 |
| targetSdk | Play 每年提高最低 targetSdk 要求；ADR-0007 規定一律用最新穩定版 | Play Console |

**封閉測試的測試者從哪裡來**：12 人連續 14 天，需要事先找好，例如潛水社群、潛水店。這是 R2 軌道最花時間的前置工作。

## 3. 樂捐

- **`play` flavor**：用 Play Billing 的消耗型商品，分 3 個金額級距（級距待定）；不解鎖任何功能。商店頁面與 APP 內都不出現外部連結。
- **`foss` flavor**：「關於」頁面連到外部樂捐平台，平台待定（[checklist.md](checklist.md)）。
- **GitHub**：repo 根目錄的 `.github/FUNDING.yml` 讓 GitHub 顯示 Sponsor 按鈕；F-Droid 也用它來驗證樂捐連結。

## 4. 開發者驗證（側載）

- Google 正在推行 Android 開發者驗證：2026-09-30 起，巴西、印尼、新加坡、泰國開始要求 APP 註冊；2027 年擴及全球所有經認證的 Android 裝置。未註冊的 APP 仍可用 adb 或進階安裝流程側載。
  - 來源：[Android Developers Blog](https://android-developers.googleblog.com/2026/06/android-developer-verification.html)
- 對本專案的影響：R1 GitHub Release 的 APK 在 2027 年之後，一般使用者要安裝，就需要先註冊套件名稱與簽章金鑰。F-Droid 簽署的版本怎麼處理，屆時查 F-Droid 的公告。

## 5. 金鑰管理

- 上傳金鑰與 GitHub 發行金鑰分開產生，都不進 repo；`.gitignore` 與 `.claude/settings.json` 都已排除。
- 各自離線備份兩份，放在不同地點。遺失 GitHub 發行金鑰，已安裝的使用者就無法更新。
- 密碼不寫在任何檔案裡；本機建置用環境變數或系統的憑證儲存區。
