# ADR-0010 建置 flavor 與樂捐：play 用 Play Billing，foss 用外部連結

2026-09-28 · 狀態：已採納（產品擁有者 2026-09-28 決定）

## 背景

產品擁有者決定：
- 程式碼以 GPL-3.0-or-later 開源。
- 在 Google Play 上架，並保留自由樂捐。
- Play 版用 Play Billing 收樂捐。
- applicationId 為 `io.github.tengigabytes.anomalops`。

限制條件：

- **Google Play 付款政策**：APP 本身與商店頁面都不得把使用者引導到 Play Billing 以外的付款方式。免稅捐款是例外，但實際認定很嚴。2026-07 至 08 月，AnkiDroid 的樂捐連結指向一個 501(c)(6) 組織，Google 不接受，要求必須是 501(c)(3) 或同等級的慈善機構；AnkiDroid 最後在 Play 版移除了連結。
- **F-Droid**：不收錄帶有專有相依套件的 APP（推測 Play Billing Library 屬於這一類，要在 M0 確認）。
- **Play Billing Library 的授權**：推測是專有授權。GPL 程式要和它連結，需要依 GPLv3 第 7 條加上附加許可。

## 決策

**兩個建置 flavor**，屬於同一個 flavor dimension `distribution`：

| flavor | 發行管道 | 樂捐方式 | 專有相依 |
| --- | --- | --- | --- |
| `play` | Google Play | Play Billing 的消耗型內購「支持開發」，分幾個金額級距；APP 與商店頁面都不放外部樂捐連結 | 只有 Play Billing Library |
| `foss` | GitHub Release、F-Droid | 「關於」頁面放外部樂捐連結，平台待定 | 無 |

**其他規則**

- **不解鎖功能**：樂捐純屬支持，兩個 flavor 的功能完全相同。
- **程式碼放置**：樂捐相關程式放在 `:app` 的 flavor source set（`app/src/play/`、`app/src/foss/`），透過同一個 `DonationProvider` 介面對外，其他模組不得依賴 Play Billing。
- **applicationId**：兩個 flavor 相同。GitHub 版與 Play 版的簽章不同，使用者要換管道時必須先解除安裝。
- **網路權限**：ADR-0007 的 INTERNET 規則細化如下：
  - `foss`：合併後的 manifest 不得有 `INTERNET` 權限。
  - `play`：只允許出現 Play Billing Library 合併進來的權限。
  - 兩個 flavor 的所有拍攝功能都必須在離線時可用（NFR-8）。
- **授權附加許可**：GPLv3 第 7 條的附加許可寫在根目錄的 `NOTICE.md`，從第一個 commit 起就生效，所以所有貢獻者的程式碼都一併適用。

## 不採用的選項

- **Play 版放外部樂捐連結**：違反付款政策，AnkiDroid 的例子說明審核會實際執行。
- **只有 Play 版、沒有 foss 版**：GPL 專案應該要能完全以自由軟體建置出來，也讓不使用 Google Play 的使用者能取得。
- **兩個 flavor 用不同的 applicationId**：兩個版本可以同時安裝，但 FR-45 紀錄與設定會分散在兩處，使用者也容易混淆。

## 後果

- **個人資料公開**：Play 版開始收款後，Google Payments 商家帳號的完整地址會公開顯示在 Play 商店頁面（個人帳號也一樣）。在歐盟發行可能還要申報交易者身分（推測，以 Play Console 為準）。
- **建置**：M0 建立專案骨架時就要設好 flavor；CI 兩個 flavor 都要建置。
- **發行時程**：見 roadmap.md 第 8 節的發行軌道（R1–R4）。

## 驗證

- M0：從 Play Billing Library 的 POM 確認授權名稱，並據此核對 `NOTICE.md` 附加許可中的名稱。
- M0：列出 `play` flavor 合併後 manifest 的權限。
- M0：確認 `foss` flavor 的相依樹中沒有任何專有套件。
- R2 之前：在 Play Console 確認商家地址的顯示方式，以及歐盟交易者身分的申報要求。
