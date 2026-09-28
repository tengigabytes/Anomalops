# :tools:probe

能力偵測工具（ADR-0003，關卡 G0）。獨立安裝的除錯 APP，applicationId `io.github.tengigabytes.anomalops.probe`。

| 項目 | 內容 |
| --- | --- |
| 職責 | 列出各邏輯與實體鏡頭的 Camera2 能力、串流格式（含 JPEG\_R、RAW）、可用擷取請求鍵、感測器，匯出為 JSON |
| 依賴 | `:core:profile`（共用 schema） |
| 產出 | `assets/device-profiles/<device>.json` 的能力部分，以及各 ADR「驗證」一節的 G0 答案 |
| 現況 | M0 骨架：佔位畫面，偵測功能是 M0 的下一步 |
