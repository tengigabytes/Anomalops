# 作者與分工

2026-09-28 · 狀態：已採用

## 1. 分工

| 工作 | 負責 |
| --- | --- |
| 產品需求（`docs/product/requirements/`） | Terry Wang 撰寫 |
| 所有決策：範圍、架構（ADR）、授權、發行 | Terry Wang 決定 |
| 審閱：每一份文件、每一次變更 | Terry Wang |
| 程式碼、測試、註解 | Claude（Anthropic 的 AI 模型，透過 Claude Code）依 Terry Wang 的指令撰寫 |
| ADR、驗收條件、里程碑、開發規範等文件的起草 | Claude 依 Terry Wang 的決定起草，經其審閱定稿 |

## 2. 工作流程

1. **下指令**：維護者說明範圍（FR / ADR 編號）、要改的模組、怎麼驗收。
2. **實作**：Claude 撰寫程式碼，回報改了什麼，並列出哪些推測尚未驗證。
3. **審閱**：維護者閱讀 diff、跑測試，必要時上實機驗證；可以要求修改，也可以接受。
4. **提交**：由維護者決定何時 commit 與 push。

Claude **不做**以下三件事：
- 自行擴大範圍。
- 自行做架構決定。需要時提出 ADR 草稿，交給維護者決定。
- 略過審閱，直接 commit 或 push。

## 3. Commit 的標示

- **Author 與 `Signed-off-by`**：維護者本人。DCO 的聲明由人類提交者負責（[git-workflow.md](git-workflow.md)）。
- **有 Claude 撰寫的內容**：commit 訊息加上 `Co-Authored-By: Claude <模型名稱> <noreply@anthropic.com>`。
- 這樣從 git 歷史就能分辨哪些變更有 AI 參與。

## 4. 著作權上的意義

以下是主管機關的公開見解，對本專案影響的判斷是推測，不是法律意見。

- **美國著作權局 2025-01 報告**：純 AI 生成的內容不受保護；以目前的技術，單靠提示詞通常不構成足夠的人類控制；人類在輸出中可辨識的創作，以及對輸出做的創造性選擇、編排或修改，才受保護。
  - 來源：[Copyright and Artificial Intelligence, Part 2](https://www.copyright.gov/ai/Copyright-and-Artificial-Intelligence-Part-2-Copyrightability-Report.pdf)
- **臺灣智慧財產局**：AI 作為輔助工具、而且有人類的創意投入時，完成的作品才受保護，著作權歸實際投入創意的人；使用者只下指令、沒有投入創意時，生成的內容不受著作權保護。
  - 來源：[法源法律網 2025-05-20 報導](https://www.lawbank.com.tw/news/NewsContent.aspx?NID=208489.00)、[智慧財產局解釋資料](https://www.tipo.gov.tw/tw/copyright/692-34252.html)

**對本專案的影響（推測）**：
- Claude 依指令產生、沒有經過人類創造性修改的程式碼，可能不受著作權保護。GPL 的相同方式分享要求是靠著作權執行的，對這部分的約束力可能有限。
- 人類撰寫的需求、決策，以及對程式碼的實質修改，受保護的可能性較高。

**因應方式**：
1. **保留人類創作的紀錄**：需求、ADR、審閱意見、要求修改的內容，都留在 repo 或 PR 裡。
2. **授權不變**：GPL 適用於所有受著作權保護的部分；專案整體開源的承諾也不因此改變。
3. **公開揭露**：`README.md` 與 `NOTICE.md` 寫明分工，不讓使用者誤以為程式碼全由人類撰寫。

## 5. 外部貢獻者使用 AI 工具

- **允許使用**，但要在 PR 中說明哪些部分是用 AI 產生的、用的是哪個工具。
- **提交者要負責**：必須自己審閱過內容；`Signed-off-by` 代表由提交者本人做出 DCO 聲明。
- **AI 寫了主要部分時**，commit 加上該工具的 `Co-Authored-By` trailer。
