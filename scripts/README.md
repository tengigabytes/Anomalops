# scripts

開發用腳本。程式與註解用英文，單檔 ≤ 200 行。

| 檔案 | 用途 |
| --- | --- |
| [check_module_deps.py](check_module_deps.py) | 依 ADR-0007 檢查各模組建置檔裡的 `project(":...")` 依賴；出現不允許的依賴時結束碼為 1 |
| [check_limits.py](check_limits.py) | 檢查檔案長度上限、`docs/` 各目錄索引是否完整、相對連結是否有效；違反硬性上限時結束碼為 1。規則見 [code-structure.md](../docs/dev/code-structure.md) |
