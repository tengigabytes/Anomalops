# Anomalops 潛水相機 APP 需求文件

Sep 28, 2026 · @Terry Wang

需求本文依章節拆成多個檔案。找特定需求時，用 grep 搜尋編號，不要整批讀取：

```sh
grep -rn "FR-62 " docs/product/requirements/
```

| 章節 | 檔案 | 需求編號 |
| --- | --- | --- |
| 1. 文件目的與範圍 | [01-purpose.md](01-purpose.md) |  |
| 2. 功能參考：OLYMPUS TG-7 水下攝影功能 | [02-reference-tg7.md](02-reference-tg7.md) |  |
| 3. 目標平台：Pixel 10 硬體與軟體能力盤點 | [03-platform.md](03-platform.md) |  |
| 4. 使用情境與使用者角色 | [04-scenarios.md](04-scenarios.md) |  |
| 5.1 拍攝模式 | [05-1-capture.md](05-1-capture.md) | FR-11 … FR-19a（13 項） |
| 5.2 白平衡與色彩還原 | [05-2-white-balance.md](05-2-white-balance.md) | FR-21 … FR-27（7 項） |
| 5.3 對焦與微距 | [05-3-focus.md](05-3-focus.md) | FR-31 … FR-37（7 項） |
| 5.4 潛水資訊與中繼資料 | [05-4-dive-data.md](05-4-dive-data.md) | FR-41 … FR-45（5 項） |
| 5.5 潛水鎖定與操作 | [05-5-dive-lock.md](05-5-dive-lock.md) | FR-51 … FR-58（8 項） |
| 5.6 儲存與檔案管理 | [05-6-storage.md](05-6-storage.md) | FR-61 … FR-73（14 項） |
| 5.7 感測、對焦輔助與硬體擴充 | [05-7-sensors.md](05-7-sensors.md) | FR-81 … FR-86（6 項） |
| 5.8 補充功能 | [05-8-supplementary.md](05-8-supplementary.md) | FR-91 … FR-98（8 項） |
| 6. 非功能需求（NFR） | [06-nfr.md](06-nfr.md) | NFR-1 … NFR-10（10 項） |
| 7. 防水殼與硬體配件整合需求 | [07-housing.md](07-housing.md) |  |
| 8. 限制、風險與假設 | [08-risks.md](08-risks.md) |  |
| 9. 待決事項與後續版本規劃 | [09-open-items.md](09-open-items.md) |  |

第 5 節「功能需求（FR）」的總則：

P0 是 v1 上架的最小集合：場景預設、分深度白平衡、大快門、潛水鎖定、RAW。其餘依優先度分版本加入，見第 9 節。
