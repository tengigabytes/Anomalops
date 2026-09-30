# ADR-0013 長焦微距的望遠實體鏡頭存取與手動對焦

2026-09-30 · 狀態：提議

## 背景

長焦微距（FR-36）要在望遠的最近對焦距離附近手動對焦。Google 相機的自動對焦只靠近到約 57 cm，再近就改用主鏡頭數位變焦（第三方評測，未查證）；本 APP 要繞過這個鏡頭切換邏輯，直接指定望遠。

已知（Pixel 10 Pro）：

- `cameraIdList` 只有 `0`、`1`；實體鏡頭不能單獨開啟，只能從邏輯鏡頭 0 以 `setPhysicalCameraId` 取用（[g0-blazer.md](../test/g0-blazer.md)）。
- 邏輯鏡頭 0 的 `getPhysicalCameraIds()` 列出 2、3、4、5、6、9；G0 查詢的實體鏡頭 × 4 種串流組合，HAL 全部回報支援（查詢結果，不等於實際串流成功；M2 已遇過查詢結果不可靠）。
- v1.0 已用同一做法實際串流並手動對焦 ID 2、3、9（ADR-0001、FR-35、[m4-af-timeline.md](../test/m4-af-timeline.md)）。**望遠 ID 4、6 只有查詢結果，還沒實際串流、也沒手動對焦過。**
- 邏輯鏡頭用 `CONTROL_ZOOM_RATIO` 時，G0 查到 5× 仍是主鏡頭，10× 以上才換到 ID 4；由 HAL 決定，本 APP 無法強制（ADR-0011）。

## 決策（提議）

- 長焦微距沿用 v1.0 的做法：邏輯鏡頭 0 的工作階段只含望遠一顆實體鏡頭的串流（`OutputConfiguration.setPhysicalCameraId`），`CONTROL_AF_MODE_OFF`，以 `LENS_FOCUS_DISTANCE` 手動對焦（FR-32 滑桿、FR-33 包圍）。
- 望遠的 ID 與最近對焦距離在執行時從 `CameraCharacteristics` 讀取，並與能力表比對；不一致時停用長焦微距並記錄（NFR-9）。
- 這一項的驗證排在長焦微距與 FR-33 所有工作之前（roadmap M9 前置工作）。

**若實測不可行的退路**，依序：

1. 邏輯串流加 `CONTROL_ZOOM_RATIO`（≥ HAL 換到望遠的倍率），再送 `LENS_FOCUS_DISTANCE`：要先確認 HAL 在邏輯串流上是否接受手動對焦距離，且近距時不會自行換回主鏡頭（推測可能會換）。
2. 主鏡頭 2× 裁切（ID 5）當作「中距微距」：最近對焦約 10.5 cm，畫面寬約 7 cm（推算，[ADR-0012](0012-macro-lens-selection.md)）。

採用退路時另寫 ADR 取代本 ADR。

## 不採用的選項

- **CameraX**：無法指定實體鏡頭並手動對焦（ADR-0001）。
- **只用邏輯串流**：鏡頭由 HAL 依距離與光線決定，近距時可能換回主鏡頭，長焦微距就不成立。

## 後果

- 長焦微距換鏡頭要重建工作階段（v1.0 實測 240–400 ms），與 ADR-0011 的 v1.1 logical 取景不同路。進入長焦微距時，取景改走實體串流；ADR-0011 採納時要一併寫明這個例外。
- 望遠的色彩校正值要另外收集（ADR-0002；v1.0 只校正預設用到的鏡頭）。

## 驗證

[macro-stacking-test-plan.md](../test/macro-stacking-test-plan.md) 第 2 節 T1、T2：ID 4、6 能否建立預覽與 RAW 串流；`CONTROL_AF_MODE_OFF` 下送出的 `LENS_FOCUS_DISTANCE` 與結果回報是否一致；最近對焦距離處的解析度卡是否清晰；兩顆的距離校準類型。
