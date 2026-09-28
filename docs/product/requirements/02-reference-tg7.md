# 2. 功能參考：OLYMPUS TG-7 水下攝影功能

[← 需求文件索引](README.md)

TG-7（1/2.33 吋 12 MP 感光元件）把水下場景模式、分深度白平衡、1 cm 顯微對焦、環境感測紀錄與實體按鍵整合成預先設定好的操作流程。Anomalops 參考的是這套流程，而不是硬體規格。

| TG-7 功能 | 規格 / 行為 | 對 Anomalops 的啟示 |
| --- | --- | --- |
| 水下場景模式 | 水下快照、水下廣角、水下微距、水下顯微、水下 HDR 五種一鍵模式 | 需要一組「場景預設」而非完整手動選單 |
| 水下白平衡 | 淺水 / 中深 / 深水三段預設 + 一鍵自訂白平衡（4 組記憶槽） | 白平衡是核心功能，需支援依深度自動切換與灰卡校正 |
| 顯微模式 | 最近對焦 1 cm、最高約 7× 放大、支援焦點堆疊與焦點包圍 | Pixel 的超廣角微距距離約 2–5 cm，需以裁切 + 多幀合成補足 |
| 鏡頭 | 25–100 mm 等效、f/2.0–4.9 | Pixel 主鏡頭 f/1.68 進光更多，但無真正光學變焦銜接 |
| Pro Capture | 半按快門即預先緩衝，最多 70 張連拍 | 需要「預錄緩衝」以捕捉魚群突然轉向 |
| 錄影 | 4K 30p、FHD 120 fps、直式 4K、4K 縮時 | Pixel 10 Pro 支援 4K 60 / 10-bit HDR |
| 環境感測（Field Sensor） | GPS、壓力計（深度 / 高度）、電子羅盤、溫度計，可寫入 EXIF 與 Log | Pixel 10 具氣壓計、磁力計、GPS，但氣壓計在防水殼內無法量水深 |
| 防水 | 機身 IPX8 至 15 m；PT-059 防水殼至 45 m | 手機 IP68 不能下水潛水，深度完全取決於防水殼 |
| 閃燈 / 補光 | 內建閃燈 + FD-1 擴散片、LG-1 導光環，可光纖觸發外閃 | v1 不做光學觸發，改以持續光潛水燈為主 |
| 操作 | 實體轉盤與大按鍵，戴手套可操作 | 大面積觸控目標、避免多層選單 |

來源：[OM System TG-7 Review – Underwater Photography Guide](https://www.uwphotographyguide.com/om-system-tg-7-review/)、[TG-7 Review – Bluewater Photo](https://www.bluewaterphotostore.com/om-system-tg-7-review)、[White Balance on the TG6/TG7 – Alphamarine](https://alphamarinephoto.com/blog/2023/8/3/white-balance-on-the-olympus-tg6)、[OM System Tough TG-7 – Wikipedia](https://en.wikipedia.org/wiki/OM_System_Tough_TG-7)。PT-059 45 m 與五種水下模式名稱待以 OM System 官方規格確認。
