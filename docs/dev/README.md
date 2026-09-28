# 開發紀律

2026-09-28 · 狀態：已採用

所有貢獻者（包括 Claude Code）都要遵守。細節在各檔案，這裡只列必須記住的規則。

| 檔案 | 內容 |
| --- | --- |
| [language.md](language.md) | 什麼東西用什麼語言 |
| [code-structure.md](code-structure.md) | 目錄結構、檔案與函式長度上限 |
| [docs-management.md](docs-management.md) | 文件放哪裡、怎麼命名、怎麼維護索引與翻譯 |
| [git-workflow.md](git-workflow.md) | 分支、commit、完成定義、版本號 |
| [claude-code.md](claude-code.md) | 控制 Claude Code 的 context 與用量 |
| [authorship.md](authorship.md) | 作者與分工：需求與審閱由維護者負責，程式碼由 Claude 依指令撰寫；commit 標示與著作權上的意義 |

## 硬性規則

1. **語言**：討論用臺灣正體中文；程式碼、註解、commit 訊息用英文；文件以中文為正本，可附英文翻譯。
2. **長度上限**（函式與類別由 `./gradlew detekt` 檢查；檔案與文件由 `scripts/check_limits.py` 檢查）

   | 類型 | 上限 |
   | --- | --- |
   | Kotlin 原始檔 | 300 行 |
   | 函式 | 60 行 |
   | 測試檔 | 400 行 |
   | 文件 | 300 行或 20 KB |

3. **每個目錄都有索引**：`README.md` 列出目錄內每個檔案與一句用途；新增、刪除、改名檔案時同步更新。
4. **需求可追溯**：程式碼的測試名稱或 KDoc 標註它實作的 FR / NFR 編號，例如 `FR-62`，讓 grep 找得到。
5. **架構決策寫 ADR**：難以回頭的決定先寫 [ADR](../adr/README.md)，再寫程式。
6. **每個 commit 都要通過** `python scripts/check_limits.py`。
7. **分工**：需求、決策、審閱由維護者負責；程式碼由 Claude 依指令撰寫，有 AI 參與的 commit 要附 `Co-Authored-By`（[authorship.md](authorship.md)）。
