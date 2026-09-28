# 語言規範

2026-09-28 · 狀態：已採用

| 項目 | 語言 | 說明 |
| --- | --- | --- |
| 與維護者、與 Claude Code 的討論 | 臺灣正體中文 | 技術名詞與程式識別字保留原文 |
| Issue、PR 的討論 | 臺灣正體中文或英文 | 開源專案，兩種都接受；回覆用提問者的語言 |
| 程式識別字、註解、KDoc | 英文 | |
| Log 訊息、例外訊息 | 英文 | 方便搜尋錯誤與回報問題 |
| 測試名稱 | 英文 | 名稱中帶需求編號，例如 `fr62_longPressWithin10s_writesDng` |
| Commit 訊息、分支名稱、tag | 英文 | 格式見 [git-workflow.md](git-workflow.md) |
| 介面字串 | 英文與臺灣正體中文都要有 | 預設 `values/` 放英文，`values-zh-rTW/` 放中文；不使用簡體中文詞彙（NFR-10） |
| 產品、架構、開發文件 | 臺灣正體中文為正本 | 可附英文翻譯，規則見 [docs-management.md](docs-management.md) |
| `README.md`、`CONTRIBUTING.md` | 英文 | 開源專案的門面；中文版為 `README.zh-TW.md` |
| `CLAUDE.md` | 臺灣正體中文 | 維護者的工作語言 |

## 用語

- 文件中的程式識別字、API 名稱、擷取請求鍵，一律用程式碼格式保留原文，例如 `COLOR_CORRECTION_GAINS`，不翻譯。
- 臺灣慣用詞優先：程式碼（非代碼）、檔案（非文件，指 file 時）、預設（非默認）、介面（非接口）、資料（非數據）。
- 第一次出現的縮寫附上全名，例如「數位負片（DNG）」。
