# :core:camera

Camera2 工作階段與擷取請求。

| 項目 | 內容 |
| --- | --- |
| 職責 | 開啟實體鏡頭、組裝預覽與拍攝請求、白平衡套用（ADR-0002）、曝光換算（ADR-0009）、閃光燈硬關（FR-81） |
| 依賴 | `:core:profile`；**不得**依賴 `:core:telemetry`（ADR-0007），深度段以數值從 `:app` 傳入 |
| 對應需求 | FR-11、FR-15、FR-21、FR-31、FR-35、FR-81、FR-91 |
| 內容 | `preset/PresetTable`：五種預設的最長曝光與 AF 策略（FR-11，mvp-scope.md 第 4 節初稿）<br>`exposure/ShutterPriority`：預覽測光換算為快門優先（ADR-0009）<br>`request/RequestPlanner`：組出預覽與拍攝的 `RequestSpec`（鏡頭、AE、AF、白平衡）；`RequestSpec` 是純資料，Camera2 轉接層另寫 |
| 測試 | JVM 單元測試：預設參數、曝光換算、請求組裝、FR-81 閃光燈防護（`AeMode` 只有 ON/OFF；掃描 `:core:camera` 與 `:app` 原始碼不得出現閃光燈或手電筒模式） |
| 現況 | M1 進行中：請求策略完成；Camera2 工作階段與轉接層尚未實作 |
