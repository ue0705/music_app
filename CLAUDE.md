# CLAUDE.md — CelloCoach 開發指引

大提琴練習助手（Android / Kotlin + Jetpack Compose）。從 `../music_code` 的
Flask + 瀏覽器版移植為原生 App。架構與決策見 [`docs/arc42/`](docs/arc42/)，模組
簽章見 [`CONTRACTS.md`](CONTRACTS.md)。

## 開發環境

一律在 **devcontainer** 內建置與測試（主機不需裝 Android SDK / JDK）。容器映像見
`.devcontainer/Dockerfile`（JDK 17 + Android SDK 34 + emulator + Gradle 8.7）。

主機若用 CLI（非 VS Code），等效的 docker 叫法：

```bash
docker run --rm -v "$PWD":/workspace -w /workspace \
  -v cellocoach-gradle:/root/.gradle -v cellocoach-android:/root/.android \
  -e ANDROID_SDK_ROOT=/opt/android-sdk -e GRADLE_USER_HOME=/root/.gradle \
  --user root cellocoach-dev:latest bash -lc './gradlew <task>'
```

> **務必掛 `-v cellocoach-android:/root/.android`**：debug keystore 放在這裡。
> 不掛的話每次 build 都會產生新 keystore → 簽章不同 → `adb install -r` 會因
> `INSTALL_FAILED_UPDATE_INCOMPATIBLE` 失敗、得先解除安裝。

## 測試策略（重要）

分兩層，**都不需要實體手機**：

1. **單元 + Robolectric UI/E2E（JVM，免 emulator、免麥克風）— 平時主力**
   ```bash
   ./gradlew testDebugUnitTest
   ```
   - core 純演算法單元測試。
   - Compose 畫面流程（校正 / 練習 / 報告）以 `FakePitchSource` 餵腳本音高、
     `testTag` 斷言。秒級回饋，CI 直接可跑。

2. **Instrumented E2E（真機畫面驗證）→ 一律用 emulator，不要用實體手機**
   ```bash
   bash scripts/run-emulator-tests.sh    # headless KVM emulator + connectedDebugAndroidTest
   ```
   容器需以 `--device=/dev/kvm` 啟動（devcontainer 已設定）。

### 為什麼 E2E / UI 自動化用 emulator，不用實體手機

- 不用一直插著手機、不用顧線。
- emulator 可自由用 `adb shell input tap` 驅動 UI；**實體手機（尤其 Xiaomi /
  HyperOS）預設禁止 adb 模擬點擊**，要另外開「USB 偵錯（安全設定）」才行，
  否則自動點擊無聲失效——非常容易誤判成 App bug。
- emulator 乾淨、可重現、可丟進 CI。

實體手機**只**用於：真實麥克風的手動體驗、使用者要求的實機驗證，或最終 sideload
安裝確認。流程照 [`device-verify` skill](.claude/skills/device-verify/SKILL.md)。

> 需要用 adb 自動驅動畫面時，記得 App 已開 `testTagsAsResourceId`，可用
> `uiautomator dump` 取 `resource-id`（即各 `TestTags` 字串）來精準點擊；
> `scripts/phone.sh` 已包好 dump / tap / text / mic / shot。

### Xiaomi / HyperOS 實機的已知陷阱（2026-11 在 Redmi 13C 5G 遇過）

- `adb install` 會在手機上跳「繼續安裝」確認；沒人按就是 `INSTALL_FAILED_USER_RESTRICTED`。
- `input tap` 報 `INJECT_EVENTS`：請使用者開「USB 偵錯（安全設定）」，再跑 **`adb usb`** 讓 adbd 套用。
- instrumentation 從背景啟動 Activity 會被擋（`MIUILOG- Permission Denied Activity`），
  測試卡住；`connectedDebugAndroidTest` 跑完還會解除安裝 App。實機請用 `phone.sh`。
- 手機上的 App 若是別台電腦簽的，`install -r` 會失敗；解除安裝會清資料，**先用
  `run-as com.cellocoach` 備份 `files/` 並問使用者**。
- 使用者常在手機旁，系統視窗可能被他先按掉；自動操作前請他先別碰手機。
- 不要自己改電腦音量、手機螢幕旋轉等系統設定，請使用者處理。

## 部署到實機

平時開發不需要；要實機體驗時依開發機選一條路：

| 開發機 | 指令 | 說明 |
|---|---|---|
| WSL2 + Windows | `bash scripts/deploy-windows-adb.sh` | 直接呼叫 Windows 的 `adb.exe`，免 usbipd（[arc42 §11.3](docs/arc42/11_risks_and_technical_debt.md#113-實機部署路徑-wsl2)） |
| macOS（無 Docker） | `bash scripts/deploy-macos.sh` | 先跑一次 `scripts/setup-macos-toolchain.sh --accept-android-sdk-licenses`（[arc42 §7.5](docs/arc42/07_deployment_view.md#75-macos-本機工具鏈--usb-實機路徑)） |

> `setup-macos-toolchain.sh` 會下載約 1.4 GB，且 `--accept-android-sdk-licenses`
> 代表同意 Android SDK 授權條款——**一定要先問使用者**，不可自行代為同意。

## 慣例

- 改演算法務必對照 `../music_code/*.py` 與 `CONTRACTS.md`，保持行為一致。
- 任何被測元件都掛 `Modifier.testTag(TestTags.*)`；tag 字串集中在
  `ui/TestTags.kt`。
- core（`com.cellocoach.core`）維持純 Kotlin、無 Android 相依，才能在 JVM 測。
- 提交前至少跑過 `./gradlew testDebugUnitTest`。
- **文件與程式碼同一個 commit 更新**：依 [`arc42-docs` skill](.claude/skills/arc42-docs/SKILL.md)
  更新 `docs/arc42/`、README、CONTRACTS.md，並跑 `python3 scripts/check-doc-links.py`（必須 0 broken）。
- 公開簽章改了就同步改 `CONTRACTS.md`（它是單一事實來源）。
