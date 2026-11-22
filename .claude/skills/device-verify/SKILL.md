---
name: device-verify
description: Build CelloCoach, install it on the USB-connected Android phone and verify behaviour on the real device (mic, permission, calibration, pitch) by driving the UI over adb. Use when asked to "run it on the phone", "deploy to my device", "test on the real device", or to confirm a fix with the real microphone. Not for JVM/Robolectric tests (./gradlew testDebugUnitTest) or the emulator (scripts/run-emulator-tests.sh).
---

# 實機建置、安裝與驗證（device-verify）

把目前的程式碼編成 debug APK、裝到 USB 上的實體手機，並**用 adb 操作畫面、讀系統狀態**來驗證行為。
架構背景見 [arc42 §7.5 macOS 本機 + USB 實機路徑](../../../docs/arc42/07_deployment_view.md#75-macos-本機工具鏈--usb-實機路徑)
與 [§8.8 測試性](../../../docs/arc42/08_crosscutting_concepts.md#88-測試性-testability)。

> 原則（[CLAUDE.md](../../../CLAUDE.md)）：平時的 UI/E2E 回歸用 Robolectric 或 emulator；實機只用於
> 「真實麥克風」與「最終安裝確認」。使用者明確要求在實機跑時才走本流程。

## 0. 先確認，再動手

1. `adb devices -l` — 必須是 `device`（不是 `unauthorized`）。記下型號：
   `adb shell getprop ro.product.marketname`、`ro.build.version.release`、`ro.mi.os.version.name`（HyperOS）。
2. 手機上是否已有 `com.cellocoach`：`adb shell pm list packages | grep cellocoach`。
   - 若是**別台電腦簽章**的版本，`adb install -r` 會 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，必須解除安裝
     → **會清掉 App 資料**。先備份（debug 版才行）：
     ```bash
     adb shell run-as com.cellocoach ls -la files files/scores
     adb exec-out run-as com.cellocoach cat files/tuning.json > backup/tuning.json
     ```
     解除安裝前**先問使用者**，重裝後用 `adb push` 到 `/data/local/tmp` 再 `run-as ... cp` 放回。
3. 建置工具：
   - devcontainer 可用 → `./gradlew assembleDebug`（見 README）。
   - macOS 沒有 devcontainer → `scripts/setup-macos-toolchain.sh`。它會下載約 1.4 GB，並需要同意
     **Android SDK 授權條款**：這是代使用者同意條款，**必須先取得使用者同意**才加 `--accept-android-sdk-licenses`。

## 1. 建置與安裝

```bash
bash scripts/deploy-macos.sh                 # assembleDebug → adb install -r → 啟動
WITH_TEST_APK=1 bash scripts/deploy-macos.sh # 連 androidTest APK 一起裝
```

- Xiaomi / HyperOS 每次 USB 安裝都會在手機跳確認窗；`INSTALL_FAILED_USER_RESTRICTED` = 沒人按「繼續安裝」。
  告訴使用者去按，腳本會重試。
- 編譯警告 `Divider ... deprecated` 是既有的，可忽略。

## 2. 開啟 adb 操作權限（HyperOS 必做）

`adb shell input ...` 出現 `SecurityException ... INJECT_EVENTS` 時：

1. 請使用者開「設定 → 更多設定 → 開發者選項 → **USB 偵錯（安全設定）**」（需登入小米帳號、插 SIM）。
2. 開好後 `getprop persist.security.adbinput` 會是 `1`，但 adbd 還沒套用 → 執行 **`adb usb`** 重啟 adbd。
3. 用 `adb shell input keyevent 0`（KEYCODE_UNKNOWN，無副作用）確認不再報錯。

同一個設定也是 `pm grant/revoke` 所需。

## 3. 用 `scripts/phone.sh` 操作與觀察

```bash
P=scripts/phone.sh
$P dump                      # 所有元素：resource-id（= TestTags.*）| 文字
$P scroll; $P tap home_start # Home 是可捲動清單；手機橫放時按鈕常在畫面外
$P text practice_detected    # 讀「你拉」
$P mic                       # 最後一筆收音事件：rec start/update = 收音中；rec stop/release = 已停
$P shot step1                # 截圖（用 Read 看圖）
```

- resource-id 一律對照 [`ui/TestTags.kt`](../../../app/src/main/java/com/cellocoach/ui/TestTags.kt)；沒有 tag 的按鈕（如「等正確」）用 `taptext`。
- `uiautomator dump` 每次約 1–1.5 秒；要「看到就立刻點」的情境（權限視窗）請自己寫單次 dump + tap。
- 使用者常常就在手機旁：**開始自動操作前請他先別碰手機**，否則系統視窗可能被他先按掉（曾發生權限視窗被人按「允許」）。

## 4. 驗證情境（對照 arc42 §6）

| 要驗證的 | 怎麼做 | 預期 |
|---|---|---|
| 麥克風生命週期（§6.5） | Home →「開始練習」→ 回首頁 → 跑到報告；每步 `$P mic` | 只有 Tuning/Practice 是 `rec start/update`；Home/Report 為 `rec stop` |
| 背景（§6.5） | 練習中 `adb shell input keyevent KEYCODE_HOME`，再 `adb shell am start -n com.cellocoach/.MainActivity` | 背景立刻 `rec stop`；回來重新倒數、游標回「音 1 / N」 |
| 權限被拒（§6.6） | `adb shell pm revoke com.cellocoach android.permission.RECORD_AUDIO` → 重開 → 點 `permission_deny*` | 各畫面出現 `mic_permission_banner`；Practice 無新收音事件；按 `mic_permission_request` 會重問或開設定頁；`pm grant` 後回 App 橫幅消失並開始收音 |
| 已存校正（§8.6） | 用 `run-as` 寫入 `files/tuning.json`（格式見 `data/TuningStore.kt`）→ 重開 →「重新校正」→「略過」→ 首頁 →「開始練習」 | 首頁仍「已校正」，開始練習直接進 Practice |
| 音高偵測（§8.2） | `python3 scripts/gen-tones.py`；Home 選「等正確」→ 開始練習；請使用者把電腦**取消靜音**、手機靠近喇叭；`afplay build/tones/A5_880.wav &` 同時讀 `practice_detected` | 讀值與播放頻率差 < 10¢（實測：880 → 880.0、987.77 → 987.77、1046.5 → 1046.52） |

**不要自己改電腦音量或手機系統設定**（螢幕旋轉、音量等）——請使用者處理。

## 5. Instrumented 煙霧測試（選用）

```bash
adb shell am instrument -w -r com.cellocoach.test/androidx.test.runner.AndroidJUnitRunner
```

已知在 HyperOS 不可靠：系統會擋下 instrumentation 從背景啟動 Activity（logcat `MIUILOG- Permission Denied Activity`），
測試會一直等。卡住時停止指令並 `am force-stop`。另外 `AppSmokeTest` 斷言「開始練習 已顯示」前沒有捲動，
手機橫放或曲目多時會失敗（見 arc42 §11）。**不要用 `connectedDebugAndroidTest`**：它跑完會把 App 解除安裝。

## 6. 收尾（必做）

- 刪掉為測試寫入的資料（例如假的 `files/tuning.json`），把使用者原本的資料放回。
- `adb uninstall com.cellocoach.test`；`adb shell rm -f /sdcard/cc_ui.xml`。
- App 留在首頁（麥克風關閉），並提醒使用者不需要時可關掉「USB 偵錯（安全設定）」。
- 回報：裝置型號、每個情境的實測值，以及**沒驗到的部分與原因**。
