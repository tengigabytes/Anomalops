# device-profiles

每個型號一個檔案，檔名是 `Build.DEVICE`（ADR-0003）。格式由 `:core:profile` 的 Kotlin 資料模型定義，解析是嚴格的：多或少一個欄位都會失敗。

| 檔案 | 型號 | 狀態 |
| --- | --- | --- |
| [blazer.json](blazer.json) | Pixel 10 Pro | 能力表來自 2026-09-28 的 G0 報告；場景預設鏡頭已定稿；校正表只有 M1 管線測試用的室內值（鏡頭 2、3、9，淺水段），G4 取代 |

## 各區段怎麼維護

| 區段 | 來源 | 怎麼改 |
| --- | --- | --- |
| `device`、`source`、`capabilities` | `tools/probe/results/<device>-<日期>.json` | 不要手改；執行 `python scripts/probe_to_profile.py <device>` 重新產生 |
| `presetLenses` | [mvp-scope.md](../../docs/product/mvp-scope.md) 第 4 節 | 手寫；重新產生時會保留 |
| `calibration` | 校正潛水（G4）；目前是 M1 室內值（[m1-pipeline-calibration.md](../../docs/test/m1-pipeline-calibration.md)） | 手寫；重新產生時會保留 |

CI 會執行 `python scripts/probe_to_profile.py <device> --check`，能力表與最新偵測報告不一致時失敗。
