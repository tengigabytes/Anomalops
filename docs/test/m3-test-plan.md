# M3 潛水鎖定與操作介面：待測清單（Pixel 10 Pro）

2026-09-28 · 狀態：草案；第 2 節的純邏輯測試已寫，其餘程式未完成、待測

M3 的驗收項目與量測方法，依 [mvp-acceptance.md](../product/mvp-acceptance.md) 第 2、3 節，畫面配置見 [dive-lock-layout.md](../product/dive-lock-layout.md)。程式在沒有手機的環境撰寫，需要實機的項目回到本機後照本清單執行，結果寫進 `m3-instrumented.md`（屆時新增），本清單改為已完成。

## 1. 實機測試守則

每次實機測試都照做，順序如下：

1. 確認內建相機 APP 已關閉（相機同時只能給一個 APP）。
2. `adb shell settings put global stay_on_while_plugged_in 7`，測試結束改回 `0`。
3. logcat 在背景寫檔，測完刪除本機檔案。
4. `uiautomator dump` 之前確認 APP 在前景；讀完即刪除裝置上與本機的 dump 檔。
5. 測完查 `Pictures/Anomalops` 沒有殘留。刪除前逐一核對檔名、資料夾、時間，只刪本次測試產生的檔案。
6. FR-45 場次目錄（`files/dives/<場次 ID>/`）只在本機彙整數字，不進 repo，彙整後刪除。

## 2. JVM 單元測試（CI 可跑，不需手機）

| 項目 | 測試 | 門檻 | 狀態 |
| --- | --- | --- | --- |
| NFR-6 去抖 | 合成觸控序列：同點 200 ms 內重複、半徑 48 dp 邊界內外、恰好 200 ms、不同點同時間 | 半徑 48 dp 內、200 ms 內的重複只算一次；其餘全部通過；被濾掉的事件帶原因，供 `touches.csv` | 已寫，見下方說明 |
| FR-51 解鎖判定 | 按住 2.9 s 放開、3.0 s、按住期間被去抖濾掉的重複按下 | 滿 3 s 才解鎖；不足 3 s 解鎖 0 次；進度值單調遞增 | 已寫，見下方說明 |
| FR-55 版面幾何 | 以 Pixel 10 Pro 的 xdpi / ydpi（實機讀值前先用 495 ppi）計算每個可點元件 | 每個可點元件距四邊 ≥ 12 mm；按鍵 ≥ 64 dp；快門條 ≥ 88 dp；元件互不重疊 | 已寫，見下方說明 |
| 鎖定狀態機 | 一般 → 等待固定 → 鎖定 → 解鎖中 → 一般；固定被取消；崩潰重啟回鎖定 | 每個轉換符合 dive-lock-layout.md 第 2 節；重啟後場次 ID 不變 | 已寫，見下方說明 |
| 深度段、潛水燈鍵 | 深度段連按 3 次、潛水燈連按 2 次 | 淺 → 中 → 深 → 淺；關 → 開 → 關 | 已寫，見下方說明 |
| NFR-5 配色 | 版面用的每組前景 / 背景 | 對比 ≥ 7 : 1；狀態色不是藍色 | 已寫，見下方說明 |
| NFR-10 字串 | lint `MissingTranslation` 設為錯誤 | 英文與臺灣正體中文字串齊備；人工審查無簡體中文詞彙 | 待寫 |

2026-09-28：上表除 NFR-10 外都已寫成 `:app` 的 JVM 測試（`DiveLockTest`、`HoldToUnlockTest`、`TouchDebouncerTest`、`DiveLockLayoutTest`、`DivePaletteTest`、`DepthSwitchTest`，29 個；之後加上 `SessionRowsTest` 共 31 個）。這個環境無法跑 Gradle，改用獨立的 Kotlin 2.4.20 編譯器（`-Werror`）與 JUnit 4.13.2 在本機編譯執行，全部通過；`:core:profile` 的型別以同名的最小替身代入。另以 detekt 2.0.0-alpha.6 CLI（專案設定）與 ktlint 1.8.0（`intellij_idea` 風格，與既有程式相同）檢查，無問題。Gradle 建置與 CI 待推送後確認。

## 3. 實機儀器測試（G1，陸上、無殼）

| 項目 | 方法 | 門檻 | 狀態 |
| --- | --- | --- | --- |
| FR-12 選擇器 | UI 測試量測五個預設的邊界 | 位於單一長邊；每個 ≥ 64 dp；不捲動；單點即切換 | 待測 |
| FR-52 快門條 | UI 測試量測邊界；按下即拍、按住 400 ms 轉連拍沿用 FR-15 的測試 | 寬度 ≥ 88 dp；左右各扣 12 mm | 待測 |
| FR-55 安全區 | UI 測試讀每個可點元件的邊界，以實機 xdpi / ydpi 換算 mm | 全部 ≥ 12 mm | 待測 |
| 深度段、潛水燈鍵 | UI 測試量測邊界；點擊後預覽請求改變（沿用 FR-84 的接法） | ≥ 64 dp；位於安全區內；請求值隨之改變 | 待測 |
| FR-24 設定頁 | 在一般模式選「紅」，回拍攝畫面；進入鎖定後設定鍵不出現 | 請求用濾鏡專屬校正值（陸上尚無值時退回近似，記錄即可）；鎖定中無法開啟設定頁 | 待測 |
| NFR-1 崩潰重啟 | 手動按「潛水鎖定」並確認固定後，執行 `python scripts/check_crash_restart.py --rounds 10`：每輪以 `am start ... --ez injectCrash true` 注入崩潰（debug 版）。儀器測試做不到，崩潰會連測試程序一起結束 | 每次 3 s 內回到潛水鎖定，10 / 10；固定狀態保住；程序 ID 改變；FR-45 延續同一場次（沒有多出場次目錄） | 腳本已寫（以假 adb 測過流程），待測 |
| FR-45 場次檔案 | 儀器測試 `acceptance/DiveSessionTest`：開場次、寫觸控與拍攝列、關閉，再以同一 ID 重開（模擬崩潰重啟）；另測 `PrefsLockStore` 寫入後新實例讀得到 | `touches.csv`、`captures.csv` 標頭各一次、列數與欄位正確；`session.json` 重開後不變；`sensors.csv` 跨兩次開啟時間戳遞增 | 已寫，待測 |
| FR-45 開始與結束 | 進入鎖定、拍 5 張、解鎖 | 進入時建立場次；`captures.csv` 5 列；`touches.csv` 有觸控列；解鎖後停止寫入；`python scripts/check_dive_log.py <目錄>` 通過（不帶 `--minutes`） | 待測 |

## 4. 實機人工測試（G1）

| 項目 | 方法 | 門檻 | 狀態 |
| --- | --- | --- | --- |
| FR-51 手勢 | 鎖定中，四邊的邊緣滑動、返回、主畫面手勢、下拉通知各 30 次 | APP 離開前景 0 次 | 待測 |
| FR-51 解鎖 | 按住解鎖 3 s 共 10 次；按不足 3 s 共 10 次 | 解鎖 10 / 10；誤解鎖 0 次 | 待測 |
| FR-51 不熄滅 | 鎖定 90 分鐘不操作（可與 G2 合併） | 螢幕不熄滅、亮度維持最高 | 待測 |
| FR-56 | 鎖定中按電源鍵關螢幕，再以電源鍵或輕觸喚醒，共 10 次；前提是系統「輕觸喚醒」已開啟 | 1 s 內回到預覽、不需解鎖，10 / 10 | 待測 |
| 一般模式與系統鎖定畫面 | 一般模式按電源鍵關螢幕再喚醒 | 顯示系統鎖定畫面，不直接蓋上本 APP（`showWhenLocked` 只在鎖定時開啟，推測可行） | 待測 |
| 固定確認對話框 | 按「潛水鎖定」後按「知道了」；另一次取消 | 按下後進入鎖定、FR-45 開始；取消時留在一般模式且沒有建立場次 | 待測 |
| 解鎖後 | 解鎖 | 手機隨即上鎖（G1 平台測試第 6 項）；解開手機後回到一般模式 | 待測 |
| NFR-5 | 室內與戶外日光各看一次 | 所有控制元件與資訊可辨識；對比已由第 2 節確認 | 待測（5 m 水下在 G4） |

## 5. 程式中標記 `UNVERIFIED(G1)` 的推測

| 推測 | 位置 | 驗證方法 |
| --- | --- | --- |
| 固定確認對話框會拿走視窗焦點，關閉時交還 | `lock/PinWatcher` | 第 4 節「固定確認對話框」兩種情況；另有 250 ms 輪詢備援，無對話框且 5 s 未固定視為取消 |
| 執行中切換 `setShowWhenLocked` 有效 | `lock/WindowLock` | 第 4 節 FR-56 與「一般模式與系統鎖定畫面」 |
| 過熱警示門檻：系統熱狀態 ≥ MODERATE（維護者 2026-09-29 決定，需求第 9 節） | `dive/StatusBand` | G2 水浴記錄熱狀態，確認警示出現的時機是否合適 |

## 6. 回歸

新畫面取代 M1 起的測試畫面，`:app` 儀器測試整組重跑（M4 時 9 個，8 分 29 秒；M3 加上 `DiveSessionTest` 的 2 個）；`AppRig` 若改動，一併記錄。`:core:camera` 也要重跑：預覽測光拆到 `PreviewMeter`，連拍放開後加了耗時記錄（行為未改，[m2-burst-resume.md](m2-burst-resume.md)）；順便記下 `burst resume` 的各段耗時。

## 7. 這個環境裡已做與未做的驗證

2026-09-29，程式在無法執行 Gradle 的環境撰寫（Android SDK 與 Google Maven 被網路政策擋住）：

- 純邏輯與 31 個 JVM 測試：以 Kotlin 2.4.20 編譯器（`-Werror`）編譯並全數通過。
- 非 Compose 的 Android 程式（`lock/`、`Sessions`、`touch/` 與實際的 `:core:telemetry`）：以 Maven Central 上的 Android 17 框架 jar（Robolectric `android-all` 17）編譯通過；`MainActivity` 以替身代入。
- Compose 畫面（`dive/`、`settings/`、`MainActivity`、`capture/ShutterButton`、`LatestThumbnail`）：AndroidX 只能從 Google Maven 取得，**未編譯**，第一次編譯在 CI 或本機。
- detekt 2.0.0-alpha.6（專案設定）與 ktlint 1.8.0（`intellij_idea` 風格）：無問題；ktlint 單獨執行時對 `@Composable` 的命名警告，專案的 detekt 設定已排除。
- CI（[PR #1](https://github.com/tengigabytes/Anomalops/pull/1)，2026-09-29）：兩個 flavor 建置、`:app` 單元測試、detekt、合併後 manifest 檢查通過，Compose 程式在此第一次編譯。CI 不跑 Android lint，`MissingTranslation` 只在本機 `./gradlew lint` 生效；CI 也不編譯儀器測試，`DiveSessionTest` 以 AndroidX Test 替身在本機編譯過。

## 8. 之後的關卡（不在 M3 完成條件內）

| 項目 | 關卡 |
| --- | --- |
| FR-12 殼內 20 次切換第一次就成功 ≥ 19 次 | G3 |
| FR-52 殼內刻意按 100 次拍下 ≥ 95 張、誤觸率 < 5% | G3 |
| FR-55 殼內可按範圍量測，定稿安全區 | G3 |
| 深度段、潛水燈鍵殼內 10 / 10 | G3 |
| FR-51 泳池 30 分鐘意外離開 0 次；解除固定手勢是否被誤觸 | G3 |
| NFR-6 被濾掉事件的數量與位置分布 | G3、G4 |
| 固定中、手機真正上鎖時按電源鍵（藍牙信任裝置不在） | G2 |
