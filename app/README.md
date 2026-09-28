# :app

UI、潛水鎖定與各模組的組裝點。

| 項目 | 內容 |
| --- | --- |
| 職責 | Compose 介面、潛水鎖定（ADR-0006）、設定頁、把深度來源的深度段傳給相機層（ADR-0007） |
| 建置 flavor | `play`（Play Billing 樂捐）、`foss`（無專有相依），見 ADR-0010；樂捐程式放在 `src/play/`、`src/foss/` |
| 依賴 | `:core:camera`、`:core:profile`、`:core:store`、`:core:telemetry` |
| 權限 | 不宣告 `INTERNET`（NFR-8） |
| 現況 | M0 骨架：只有一個佔位畫面 |
