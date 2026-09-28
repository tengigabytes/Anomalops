# Play Billing 相依檢查（ADR-0010）

2026-09-28 · 狀態：已完成

驗證方式：把 `com.android.billingclient:billing:9.1.0`（當時的最新版）以 `playImplementation` 暫時加入 `:app`，建置兩個 flavor 的 release manifest，比對合併後的 manifest 與執行期相依樹，最後移除這個相依。產品擁有者決定到 R3 實作樂捐時才正式加入。

## 1. 授權

| 套件 | 授權（取自 POM） | 與 GPL 的關係 |
| --- | --- | --- |
| `com.android.billingclient:billing` | Android Software Development Kit License（專有） | 需附加許可 |
| `com.google.android.gms:play-services-base`、`basement`、`tasks`、`location`、`places-placereport` | Android Software Development Kit License（專有） | 需附加許可 |
| `com.google.android.datatransport:transport-api`、`transport-runtime`、`transport-backend-cct` | Apache-2.0 | 相容 |
| `com.google.firebase:firebase-encoders`、`encoders-json`、`encoders-proto` | Apache-2.0 | 相容 |

**處理**：`NOTICE.md` 第 1 條原本只涵蓋 Billing Library，已依產品擁有者決定擴充為「Billing Library 與它所依賴的 Google Play 服務函式庫（`com.google.android.gms`）」。

## 2. 合併後的 manifest

| 項目 | `foss` | `play`（加入 Billing 後） |
| --- | --- | --- |
| 權限 | 只有 androidx 內部的 `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | 另有 **`INTERNET`**、**`ACCESS_NETWORK_STATE`**、`com.android.vending.BILLING` |
| 元件 | 我們的 Activity、androidx 初始化元件 | 另有 Billing 的 2 個 Activity、Play 服務的 `GoogleApiActivity`、`datatransport` 的背景排程 Service 與 BroadcastReceiver |
| 其他 | — | `datatransport` 以 `CctBackendFactory` 為後端；另有 `<queries>` 宣告 |

推測 `datatransport` 的 CCT 後端會把 Billing Library 的使用紀錄傳回 Google。這不影響拍攝功能離線運作（NFR-8），但 Play 的資料安全表單與隱私權政策要如實揭露。

## 3. 相依樹

| flavor | 執行期套件數 | Google 相關套件 |
| --- | --- | --- |
| `foss` | 96 | 只有 `com.google.guava:listenablefuture:1.0`（POM 未寫授權，推測繼承 Guava 的 Apache-2.0） |
| `play` | 115 | 上表所列的 Billing、Play 服務、datatransport、firebase-encoders |

`foss` 沒有任何專有套件，符合 F-Droid 收錄條件（ADR-0010）。

## 4. 自動檢查

`scripts/check_flavor_manifests.py` 讀取指定變體（預設 debug）合併後的 manifest：`foss` 只允許 androidx 內部權限；`play` 另外允許上表三個 Billing 權限，出現其他權限就失敗。CI 的 `android` 工作在建置後執行。已驗證：在 app manifest 暫時加入 `INTERNET` 時，`foss` 檢查會失敗。
