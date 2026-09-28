# Anomalops

[English](README.md)

給 Google Pixel 手機使用的開源潛水相機 APP，搭配觸控式防水殼（DiveVolk SeaTouch 4 系列）。目標是讓休閒潛水員隔著凝膠膜、單手就能操作，拍出色彩正確的水下照片。

> **狀態**：設計階段。需求、MVP 範圍、架構決策與里程碑已經寫好，還沒開始開發，目前沒有可用的版本。

## v1.0（MVP）規劃

- 五種水下場景預設；分深度白平衡直接套用在 Camera2 擷取請求上，預覽與成品一致
- 潛水鎖定模式：全螢幕、不會誤觸離開、大型觸控目標避開殼框邊緣
- RAW（DNG）按需保留：拍後 10 秒內長按縮圖
- 1 Hz 感測記錄，供潛水後分析
- 目標裝置 Pixel 10 Pro；架構以資料表驅動，之後可擴充到 Pixel 6 Pro – 11 Pro

## 專案怎麼做出來的

- 需求、所有決策（範圍、架構、授權）與每一次審閱：**Terry Wang**（[@tengigabytes](https://github.com/tengigabytes)）。
- 程式碼由 **Claude**（Anthropic 的 AI 模型，透過 Claude Code）依他的指令撰寫。ADR、驗收條件、開發規範等文件，也是 Claude 依他的決定起草，經他審閱後定稿。
- 每一次變更都經他審閱並接受後才提交。含有 AI 撰寫內容的 commit 會附 `Co-Authored-By: Claude`。詳見 [docs/dev/authorship.md](docs/dev/authorship.md)。

## 文件

文件以臺灣正體中文撰寫，程式碼與註解用英文。從 [docs/README.md](docs/README.md) 開始看。

## 授權

- 程式碼：[GPL-3.0-or-later](LICENSE)，附加條款見 [NOTICE.md](NOTICE.md)
- 文件：[CC BY-SA 4.0](docs/LICENSE.md)

衍生版本不得使用「Anomalops」名稱與圖示，詳見 [NOTICE.md](NOTICE.md)。

## 參與貢獻

見 [CONTRIBUTING.md](CONTRIBUTING.md)（英文）。開發紀律見 [docs/dev/](docs/dev/README.md)。
