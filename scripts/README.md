# scripts

開發用腳本。程式與註解用英文，單檔 ≤ 200 行。

| 檔案 | 用途 |
| --- | --- |
| [probe_to_profile.py](probe_to_profile.py) | 從偵測報告產生 `assets/device-profiles/<device>.json` 的能力區段，保留手寫區段；`--check` 在不一致時結束碼為 1（ADR-0003） |
| [check_flavor_manifests.py](check_flavor_manifests.py) | 檢查兩個 flavor 合併後 manifest 的權限（NFR-8、ADR-0010）；預設檢查 debug 變體，要先建置 |
| [check_module_deps.py](check_module_deps.py) | 依 ADR-0007 檢查各模組建置檔裡的 `project(":...")` 依賴；出現不允許的依賴時結束碼為 1 |
| [check_dive_log.py](check_dive_log.py) | FR-45 驗收：檢查一個潛水場次目錄（從手機以 adb 拉回，不進 repo）的四個檔案、`sensors.csv` 時間戳遞增、列數 ≥ 應有的 98%（`--minutes 90` 以 5400 列為準），並列出最長間隔 |
| [check_crash_restart.py](check_crash_restart.py) | NFR-1 驗收（需手機）：APP 已在潛水鎖定時，以 adb 反覆注入崩潰（debug 版），每輪檢查 3 s 內重啟、仍在固定狀態、程序 ID 改變、沒有多出 FR-45 場次目錄；`--rounds 10`，每輪間隔 `--gap 65` 秒（重啟後 60 s 內再崩潰不重啟）；`--crash-loop` 檢查第二次崩潰不重啟、固定解除 |
| [make_charts.py](make_charts.py)、[chart_parts.py](chart_parts.py)、[chart_tools.py](chart_tools.py)、[chart_stairs.py](chart_stairs.py)、[print-kit-README.txt](print-kit-README.txt) | 陸上測試的 A4 列印工具包（需 matplotlib、numpy）：A 平行對焦靶、B 斜放景深尺、C 距離尺、D 灰階與解析度、E1／E2 階梯紙模型（展開圖）、F 距離卡、G 點陣格線、H 色塊靶、I 螢幕安全區量規；每張一個 PDF、全部合一 PDF，再加說明打包成 `anomalops-print-kit.zip`，預設寫到 `build/charts/`；以 100% 列印並先量 100 mm 比例尺 |
| [check_device_neutral.py](check_device_neutral.py) | NFR-9：產品模組（不含測試與 `:tools:probe`）的程式碼不得出現型號代號、「Pixel 數字」型號名、`Build.MODEL` 等型號欄位或對 `Build.DEVICE` 的比較；註解不檢查。代號清單為能力表檔名加上腳本內的 Pixel 6–10 代號；有發現時結束碼為 1 |
| [check_limits.py](check_limits.py) | 檢查檔案長度上限、`docs/` 各目錄索引是否完整、相對連結是否有效；違反硬性上限時結束碼為 1。規則見 [code-structure.md](../docs/dev/code-structure.md) |
