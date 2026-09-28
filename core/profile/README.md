# :core:profile

型號能力表與校正表（ADR-0003）。純 Kotlin/JVM 模組，不依賴 Android，單元測試可在任何環境執行。

| 項目 | 內容 |
| --- | --- |
| 職責 | 載入、驗證、查詢 `device-profiles` JSON；依「實體鏡頭 × 深度段 × 濾鏡 × 潛水燈模式」查白平衡值 |
| 依賴 | 無 |
| 被誰使用 | `:core:camera`、`:app`、`:tools:probe`（共用 schema） |
| 對應需求 | NFR-9、FR-21、FR-24、FR-25 |
| 內容 | `DeviceProfile`（資料模型即 schema，嚴格解析）、`DeviceProfiles.load(Build.DEVICE)`、`ProfileValidator` |
| 資料 | `assets/device-profiles/*.json`，以 resource 形式打包 |
| 測試 | `DeviceProfilesTest`：blazer 載入與驗證（NFR-9）、預設鏡頭（FR-11）、微距對焦（FR-31）、嚴格解析與驗證器的負面測試 |
