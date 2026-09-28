# :tools:probe

能力偵測工具（ADR-0003，關卡 G0）。獨立安裝的除錯 APP，applicationId `io.github.tengigabytes.anomalops.probe`。

| 項目 | 內容 |
| --- | --- |
| 職責 | 列出各邏輯與實體鏡頭的 Camera2 能力、串流格式（含 JPEG\_R、RAW）、可用擷取請求鍵、感測器，匯出為 JSON |
| 依賴 | `:core:profile`（共用 schema） |
| 產出 | `assets/device-profiles/<device>.json` 的能力部分，以及各 ADR「驗證」一節的 G0 答案 |
| 執行 | 安裝後 `adb shell pm grant io.github.tengigabytes.anomalops.probe android.permission.CAMERA`，啟動 APP；報告用 `adb exec-out run-as io.github.tengigabytes.anomalops.probe cat files/probe/latest.json` 取回 |
| 行為 | 只讀取能力與詢問輸出組合，不拍照、不寫入相簿 |
| 其他畫面 | `FirLiveActivity`：溫度感測器即時數值；`LockTestActivity`：潛水鎖定平台測試（ADR-0006），用 `am broadcast -a io.github.tengigabytes.anomalops.probe.LOCKTEST --es cmd <lock\|unlock\|notify\|crash\|status>` 驅動 |
| 結果 | `results/<device>-<日期>.json` 原始報告；彙整見 [docs/test/](../../docs/test/README.md) |
