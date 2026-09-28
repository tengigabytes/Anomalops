# 授權

2026-09-28 · 狀態：已採用（產品擁有者 2026-09-28 決定）

本文件記錄授權安排的理由與規則，但不是法律意見。第一次正式發行前，建議請熟悉開源授權的人看過 `NOTICE.md` 的附加許可。

## 1. 授權範圍

| 對象 | 授權 | 檔案 |
| --- | --- | --- |
| 程式碼、建置腳本、`assets/` 內的資料檔 | GPL-3.0-or-later，加上附加許可 | `LICENSE`、`NOTICE.md` |
| `docs/` 底下的文件、`README*` | CC BY-SA 4.0 | `docs/LICENSE.md` |

**選 GPL 的理由**：轉發的衍生版本也必須開源，別人不能拿去做閉源的付費版上架。GPL 本身不妨礙上架 Google Play。

**選 CC BY-SA 的理由**：文件是說明性質的內容，採用與 GPL 精神一致的相同方式分享授權。

## 2. 附加許可（GPLv3 第 7 條）

全文在 `NOTICE.md`，從第一個 commit 起就生效，共兩項：

1. **連結 Google Play Billing Library 與 Google Play 服務函式庫**：允許 `play` flavor 與這些專有函式庫連結後一起發行（ADR-0010）。Billing Library 會連帶引入 5 個專有的 `com.google.android.gms` 套件，所以兩者都要涵蓋（2026-09-28 擴充，見 [m0-play-billing.md](../test/m0-play-billing.md)）。沒有這條，GPL 程式不能合法地和專有函式庫一起散布。
2. **不授予商標權**（第 7 條 (e) 款）：「Anomalops」名稱與圖示不隨程式碼授權。衍生版本要發行時必須改名、換圖示，避免使用者以為是官方版本。

M0 已確認：Billing Library 與 Play 服務函式庫的 POM 授權都是「Android Software Development Kit License」。

## 3. 貢獻

- **採用 DCO，不用 CLA**：每個 commit 都要 `git commit -s`，表示貢獻者有權提交，並同意以「GPL-3.0-or-later 加上 `NOTICE.md` 的附加許可」釋出。
- **代價**：沒有 CLA，維護者日後不能自行把他人的貢獻改用其他授權。如果將來想雙重授權，要在接受第一個外部貢獻之前改用 CLA。

## 4. 第三方程式碼

| 可以引入 | 不可以引入 |
| --- | --- |
| Apache-2.0、MIT、BSD、ISC、MPL-2.0、LGPL、GPL-3.0 | GPL-2.0-only（與 GPLv3 不相容） |
| | 專有授權（`play` flavor 的 Play Billing Library 除外） |
| | CC BY-NC 等禁止商業使用的授權 |

- 引入前先確認授權，記在該模組的 `README.md`。
- 需求文件提到的參考實作，例如 timothybrooks/hdr-plus、MotionCam，授權都還沒查證；借用程式碼前要先確認。

## 5. AI 撰寫的程式碼

本專案的程式碼由 Claude 依維護者的指令撰寫。依美國著作權局與臺灣智慧財產局的見解，沒有人類創意投入的 AI 生成內容不受著作權保護，所以 GPL 對這部分的約束力可能有限（推測）。分工、揭露方式與因應做法見 [authorship.md](../dev/authorship.md) 第 4 節。

## 6. 著作權標示

- 原始檔用 SPDX 標頭：`SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors`。
- 真名的公開程度與既有的公開專案（MokyaLora）一致。
