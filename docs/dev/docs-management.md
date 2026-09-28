# 文件管理

2026-09-28 · 狀態：已採用

## 1. 文件放哪裡

| 目錄 | 內容 | 例子 |
| --- | --- | --- |
| `docs/product/` | 產品需求、MVP 範圍、驗收、里程碑 | `requirements/`、`mvp-scope.md` |
| `docs/adr/` | 架構決策紀錄，一個決策一份 | `0001-camera2.md` |
| `docs/dev/` | 開發紀律，也就是本目錄 | `code-structure.md` |
| `docs/release/` | 授權、發行、上架準備 | `licensing.md` |
| `docs/test/` | 測試計畫與關卡結果的彙整 | `g0-capabilities.md` |
| 模組內的 `README.md` | 模組職責與介面 | `core/camera/README.md` |

**不放進 repo 的東西**：測試照片、FR-45 原始紀錄、含位置的資料、個人器材清單。repo 是公開的，只提交彙整後的結果。

## 2. 每份文件的格式

- **第一行**是標題（`# `）。
- **第三行**是狀態列：`日期 · 狀態：草案 / 已採用 / 已凍結 / 已取代`。
- **檔名**用英文小寫加連字號（kebab-case）。需要排序時加數字前綴，例如 `05-1-capture.md`。
- **長度上限**是 300 行或 20 KB（[code-structure.md](code-structure.md)）。超過就拆成目錄，並在目錄裡加一個索引。

## 3. 索引

- `docs/` 底下每個目錄都有 `README.md`，每個檔案一列，附一句用途。
- 新增、刪除、改名檔案時，同一個 commit 內要更新索引。`check_limits.py` 會檢查索引是否完整、連結是否有效。
- 找東西的順序：先看 `docs/README.md`，再看子目錄索引，最後才讀目標檔案。找特定需求時用 grep 搜尋編號。

## 4. 中文與英文

- 中文是正本，檔名不加語言後綴：`mvp-scope.md`。
- 英文翻譯放在同一個目錄，加上 `.en` 後綴：`mvp-scope.en.md`。
- 翻譯檔的狀態列寫明它翻譯自哪個版本：`Translation of mvp-scope.md @ <commit>`。正本改了而翻譯沒跟上時，翻譯檔要標示「可能過時」，不能靜默放著。
- 例外：`README.md` 與 `CONTRIBUTING.md` 以英文為正本，中文版加 `.zh-TW` 後綴（[language.md](language.md)）。
- 翻譯是選擇性的，只翻譯需要給外部貢獻者看的文件。

## 5. 決策與變更

- 難以回頭的技術決定寫 ADR。已採納的 ADR 不改寫內容，只能補充註記，或另寫新的 ADR 取代它。
- 已凍結的文件（例如 `mvp-scope.md`）照它自己的變更規則修改，並記錄在該文件的變更紀錄。
- 待決事項集中在 `docs/product/requirements/09-open-items.md`，決定後打勾，並寫明決定內容與日期。

## 6. 授權

`docs/` 底下的文件以 CC BY-SA 4.0 授權（[docs/LICENSE.md](../LICENSE.md)）。引用外部資料時附上來源連結，不大段複製原文。
