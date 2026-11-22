# CelloCoach 實作契約（給並行開發 agent 的單一事實來源）

本檔定義所有模組的套件、類別與函式簽章。**請嚴格遵守簽章**，否則無法整合編譯；
簽章改變時要在同一個 commit 更新本檔（見 `.claude/skills/arc42-docs`）。架構說明見
[docs/arc42](docs/arc42/index.md)。
原始 Python 參考實作在 `/home/lane/music_code/`（`score_loader.py`, `pitch_detector.py`,
`score_follower.py`, `scorer.py`, `tuning.py`, `main.py`）。本 Android app 把原本
「瀏覽器偵測音高→POST 給 Flask」的架構改成**全部在 App 內**進行，移除原 README 中
HTTPS / secure-context / LAN 連線的痛點。

## 套件結構
- `com.cellocoach.core` — 純 Kotlin 演算法（可在 JVM 單元測試，無 Android 相依）
- `com.cellocoach.audio` — AudioRecord 麥克風 + 假來源
- `com.cellocoach.data` — 校正持久化
- `com.cellocoach.ui` — Jetpack Compose 畫面 + ViewModel
- `com.cellocoach.MainActivity`

## 已存在（不要重寫）— `core/ScoreNote.kt`, `core/Pitch.kt`
```kotlin
data class ScoreNote(val start: Double, val end: Double, val midi: Int, val name: String) {
    val isRest: Boolean              // midi < 0
    val freq: Double                 // A=A4_HZ（442）；rest 為 0
    companion object { const val REST = -1 }
}
const val A4_HZ: Double = 442.0          // 標準音高，全 App 唯一來源（Python 原版為 440，見 ADR-006）
const val LEGACY_A4_HZ: Double = 440.0   // v1.0.8 前的基準；舊校正檔據此換算
fun midiToHz(midi: Int): Double
fun midiToHz(midi: Double): Double
fun hzToMidi(hz: Double): Int       // 最近整數 MIDI
fun centsBetween(hz: Double, refHz: Double): Double

data class PitchFrame(val hz: Float?, val rms: Float)
interface PitchSource { fun start(onFrame: (PitchFrame) -> Unit); fun stop() }
fun interface Clock { fun nowNanos(): Long }
val SystemClock: Clock
```

## 要實作的核心模組

### `core/ScoreLoader.kt`
移植 `score_loader.py`。**不可用 music21**；以 JDK `javax.xml`(DocumentBuilder) 自己解析 MusicXML。
```kotlin
data class LoadedScore(val notes: List<ScoreNote>, val bpm: Double)
object ScoreLoader {
    /** bytes 可為純 XML 或 .mxl(ZIP，magic bytes "PK")，需自動解壓並取出主譜 XML。 */
    fun load(bytes: ByteArray, bpmOverride: Double? = null): LoadedScore
}
```
解析規則（只取第一個 part，單聲部）：
- ZIP 偵測：`bytes[0]=='P' && bytes[1]=='K'` → 解 zip，讀 `META-INF/container.xml` 的 rootfile full-path，否則取第一個非 META-INF 的 `.xml/.musicxml`。
- tempo：`bpmOverride` > 樂譜中第一個 `<sound tempo="..">` 或 `<metronome>`(beat-unit+per-minute) > 預設 120。
- 逐 measure 累積時間。每個 measure 內維護 `divisions`（來自 `<attributes><divisions>`）。
- `<note>`：`<duration>` / divisions = quarterLength；含 `<rest>` → midi=REST，name="rest"；含 `<pitch>`(step,octave,alter) → 轉 MIDI：`midi = (octave+1)*12 + stepSemitone[step] + alter`，stepSemitone: C0 D2 E4 F5 G7 A9 B11；name 用 `"$step$octave"`（有 alter 時加 #/b）。
- `<note>` 含 `<chord/>`：和弦音與「前一個音」同起點，取最高音（不前進時間游標）。
- `<note>` 前進時間游標；`<backup><duration>` 後退；`<forward><duration>` 前進。
- start = 目前時間游標(quarter) × (60/bpm)；end = start + quarterLength×(60/bpm)。
- 與 Python 對齊：rests 也要保留在 timeline。

### `core/PitchDetector.kt`
移植瀏覽器端 Pitchy 的 normalized autocorrelation（McLeod-style）。純運算。
```kotlin
class PitchDetector(
    val sampleRate: Int = 44100,
    val fmin: Double = 60.0,       // CELLO_FMIN
    val fmax: Double = 1100.0,     // CELLO_FMAX
    val clarityThreshold: Double = 0.9,
) {
    /** 回傳偵測到的 Hz；無清晰音高(雜訊/靜音/超出範圍)回 null。 */
    fun detect(samples: FloatArray): Float?
}
```
做法（McLeod / NSDF）：RMS < 0.01 回 null；從 **lag 0** 算 NSDF 到 maxLag，跳過 lag-0 主瓣後收集 key maxima（只有 `[minLag, maxLag]` 內的 lag 有資格），取第一個 ≥ 0.8 × 最大峰者，拋物線內插求精確 lag 與 clarity；clarity < clarityThreshold 或 hz 超出 [fmin,fmax] 回 null。（從 minLag 開始掃描會把 830 Hz 以上的音判成低八度，見 arc42 §8.2。）

### `core/ScoreFollower.kt`
**逐行移植** `score_follower.py`。常數：`ADVANCE_THRESHOLD_TICKS=5`, `LOOKAHEAD=3`, `TIMEOUT_FACTOR=2.5`。
用注入的 `Clock`（預設 `SystemClock`）取代 `time.monotonic()`（單位秒 = nanos/1e9）。
```kotlin
class ScoreFollower(
    private val notes: List<ScoreNote>,
    private val clock: Clock = SystemClock,
    private val waitForCorrect: Boolean = false,   // 「等正確」模式（Android 新增）
) {
    fun start()
    fun started(): Boolean
    fun isDone(): Boolean
    fun expectedNote(): ScoreNote?
    fun currentNoteIdx(): Int           // -1 if not in range
    fun elapsed(): Double               // 秒
    fun observe(detectedMidi: Int?)
}
```
預設路徑邏輯完全比照 Python：hard timeout、rest 依時間、重複同音 80% 前進、lookahead 命中需連續 ADVANCE_THRESHOLD_TICKS。
`waitForCorrect = true`：停在目前音，**目前音**連續正確 ADVANCE_THRESHOLD_TICKS 次才前進；無 timeout、無 lookahead；rest 依時間前進。

### `core/Tuning.kt`
移植 `tuning.py`。
```kotlin
val STRINGS: List<Pair<String,Int>>   // C36 G43 D50 A57
val STRING_NAMES: List<String>
fun nominalHzForString(name: String): Double
fun stringForMidi(midi: Int): String
class Tuning(val offsets: MutableMap<String, Double> = mutableMapOf()) {
    fun isCalibrated(): Boolean
    fun calibrateString(name: String, detectedHz: Double): Double
    fun offsetCentsForMidi(midi: Int): Double
    fun clear()
    fun asMap(): Map<String, Double>          // 四捨五入到 0.1
    fun nextUncalibrated(): String?
}
```

### `core/Scorer.kt`（含診斷）
移植 `scorer.py` + `main.py` 的 status 判定 + README 提到的自動診斷。
```kotlin
class NoteResult(val index:Int, val expectedMidi:Int, val expectedName:String, val start:Double, val end:Double) {
    var samples:Int; var voicedSamples:Int; var correctSamples:Int
    val centsValues: MutableList<Double>
    val voicedMidiCounts: MutableMap<Int,Int>
    val isRest:Boolean; val sustain:Double; val meanCents:Double?  // trimmed mean 0.2
    val intonation:Double; val pitchCorrect:Boolean; val modalPlayedMidi:Int?; val score:Double
}
data class NoteSummary(val i:Int, val name:String, val expectedMidi:Int, val playedMidi:Int?,
    val score:Double, val pitchOk:Boolean, val cents:Double?, val sustain:Double)
data class PracticeSummary(val score:Double, val nTotal:Int, val nCorrect:Int,
    val meanCents:Double?, val duration:Double, val notes:List<NoteSummary>, val diagnostics:List<String>)
class Scorer(val notes:List<ScoreNote>, val tuning: Tuning = Tuning()) {
    fun observe(noteIdx:Int, detectedHz:Float?)
    fun summary(): PracticeSummary
}
/** 即時 status：依 cents/midi 回 "good"(<20) / "close"(<50) / "off"(>=50) / "wrong"(midi 不符) */
enum class PitchStatus { GOOD, CLOSE, OFF, WRONG }
fun pitchStatus(detectedHz: Double, expected: ScoreNote, tuning: Tuning): PitchStatus
```
診斷規則（產生繁中字串，無問題則空 list）：
- 整體偏移：|meanCents| ≥ 10 → 「整體音準偏{高/低} {x}¢」。
- 重複錯音：同一 expectedName 出現 ≥2 次且都 !pitchOk → 「{name} 反覆拉錯（{n} 次）」。
- 系統性偏移：某弦上的音平均 cents 偏移 ≥ 15 → 「{C/G/D/A} 弦的音普遍偏{高/低}」。

### `audio/AudioPitchSource.kt`（實機麥克風，不需單元測試）
```kotlin
class AudioPitchSource(private val detector: PitchDetector = PitchDetector()) : PitchSource {
    override fun start(onFrame: (PitchFrame) -> Unit)   // AudioRecord 背景 thread，每 2048 樣本（≈21Hz）→ detect → onFrame
    override fun stop()                                  // 冪等；最多等 500ms 讓迴圈結束
}
```
RECORD_AUDIO 權限由 UI 層取得；此類別假設已有權限。**可重複 start/stop**（ViewModel 依畫面啟停）：每次 start 遞增 generation，舊迴圈不會繼續讀或送框。

### `audio/Metronome.kt`
```kotlin
class Metronome {
    fun tick()      // ToneGenerator TONE_PROP_BEEP 40ms
    fun accent()    // TONE_PROP_BEEP2 60ms（倒數第一拍）
    fun release()
}
```

### `audio/FakePitchSource.kt`（測試/預覽用）
```kotlin
/** 依腳本回放 PitchFrame，用於 Robolectric/預覽，免真實麥克風。 */
class FakePitchSource(private val frames: List<PitchFrame> = emptyList()) : PitchSource {
    var isRunning: Boolean                 // 測試用：斷言麥克風是否開著（private set）
    fun emit(frame: PitchFrame)            // 手動推一格（測試逐步驅動）；停止時忽略
    fun emitHz(hz: Float?, rms: Float = 0.2f)
    override fun start(onFrame: (PitchFrame) -> Unit)
    override fun stop()
}
```

### `data/TuningStore.kt`
等價於 `~/.cello-practice/tuning.json` 的持久化。用 app `filesDir/tuning.json`（JSON 純文字）。
```kotlin
class TuningStore(private val dir: java.io.File) {
    fun load(): Tuning?            // 不存在/壞檔回 null（需四弦齊全）；無 a4_hz 的舊檔視為 A=440，偏移換算到 A4_HZ
    fun save(tuning: Tuning)       // 僅在 isCalibrated 時寫入；含 a4_hz 與 savedAt ISO 時間
    fun savedAt(): String?
}
```

### `data/ScoreLibrary.kt`
匯入的樂譜，存 app `filesDir/scores/`。同名時優先於內建 assets。
```kotlin
class ScoreLibrary(private val dir: java.io.File) {
    fun list(): List<String>                     // .musicxml/.mxl/.xml，排序
    fun exists(name: String): Boolean
    fun read(name: String): ByteArray?
    fun save(rawName: String, bytes: ByteArray): String   // 回傳存檔名（已 sanitize）
    fun delete(name: String): Boolean
    fun sanitize(rawName: String): String        // 取最後一段、去 ?/#、非 [\w.-] → _、補 .musicxml
}
```

### `core/ReportHtml.kt`
```kotlin
object ReportHtml {
    fun build(summary: PracticeSummary, scoreName: String, generatedAt: String): String  // 單一自含 HTML，字串皆 escape
    fun midiName(midi: Int): String                                                     // 57 -> "A3"
}
```

## UI 行為 (com.cellocoach.ui)
- `MainActivity`：`setContent { CelloCoachApp(viewModel, onRequestMicPermission = ::requestMicPermission) }`，預設 `AudioPitchSource`。
  - onCreate / onResume / 權限結果 → `viewModel.onMicPermissionChanged(granted)`；首次啟動未授權時請求權限。
  - onStart → `onForegroundChanged(true)`；onStop（非 `isChangingConfigurations`）→ `onForegroundChanged(false)`。
  - 橫幅的重試：系統仍會詢問就再請求，否則開 `Settings.ACTION_APPLICATION_DETAILS_SETTINGS`。
- `CelloCoachApp(vm: PracticeViewModel, onRequestMicPermission: () -> Unit = {})`：頂欄、`vm.micPermissionDenied` 時的權限橫幅、依 `vm.screen` 切換四畫面。
- 畫面（單一 Activity + Compose 狀態切換，無需 Navigation 套件）：
  1. **Home**：選曲（assets 內 `g_major_scale.musicxml` 等）、顯示已存校正狀態、「開始練習」。
  2. **Tuning**：依序 C→G→D→A，顯示目前目標弦、即時 cents、進度條（30 tick）；可「略過」。完成存檔。
  3. **Practice**：原生 Compose 五線譜 `ScoreView`（畫五線譜、音符、隨演奏移動的游標、目前音 good/close/off/wrong 上色）、即時面板（應拉 vs 你拉、cents、status 顏色）、節拍器 4 拍倒數、開始前倒數。
  4. **Report**：總分、對音 X/Y、平均 cents、每顆音明細清單、診斷。
- `PracticeViewModel`：持有 ScoreFollower + Scorer + Tuning，訂閱 PitchSource，50ms tick 邏輯比照 `main.py` 的 `_practice_tick_loop` 與 `_calibration_tick_loop`。狀態以 Compose `mutableStateOf` 暴露。
  ```kotlin
  class PracticeViewModel(
      app: Application,
      pitchSource: PitchSource,
      tuningStore: TuningStore = TuningStore(app.filesDir),
      scoreLibrary: ScoreLibrary = ScoreLibrary(File(app.filesDir, "scores")),
      clock: Clock = SystemClock,          // 測試注入假時鐘
      autoDrive: Boolean = true,           // 測試設 false，改用 stepForTest()
  ) : AndroidViewModel(app) {
      // 導覽：Screen { HOME, TUNING, PRACTICE, REPORT }
      fun selectScore(name: String); fun onStartPressed(); fun beginCalibration(); fun skipCalibration()
      fun goToPractice(); fun resetPractice(); fun goHome()
      // 練習速度：♩ = practiceBpm（30–240，載入曲目時＝樂譜 bpm）；tempoFactor = practiceBpm / bpm（唯讀）
      val bpm: Double; val practiceBpm: Double; val tempoFactor: Double; val waitMode: Boolean
      fun changePracticeBpm(value: Double); fun adjustPracticeBpm(delta: Int); fun resetPracticeBpm()
      fun changeWaitMode(on: Boolean); fun toggleMetronome()
      fun onMicPermissionChanged(granted: Boolean); fun onForegroundChanged(foreground: Boolean)
      fun importScore(displayName: String, bytes: ByteArray); fun importFromUrl(url: String); fun clearImportStatus()
      fun buildReportFile(): File?
      internal fun stepForTest()
      companion object { const val TICK_MS = 50L; const val CALIB_REQUIRED_TICKS = 30
                         const val MIN_PRACTICE_BPM = 30.0; const val MAX_PRACTICE_BPM = 240.0; fun factory(app, source) }
  }
  ```
  - 麥克風：`syncMic()` 在每次狀態改變時重算「有權限 ∧ 前景 ∧ 畫面為 TUNING/PRACTICE」決定 start/stop；背景中的練習會重設，回前景重新倒數。
  - 校正：`beginCalibration` 寫入新的 `Tuning`；四弦完成才存檔並取代已存校正；`skipCalibration` 只影響當次；`goHome` 恢復已存校正。
- **測試掛勾**：所有可測元件加 `Modifier.testTag(...)`，tag 命名集中放 `ui/TestTags.kt`（例：`home_start`, `tuning_skip`, `practice_cursor`, `report_score`, `mic_permission_banner`, `mic_permission_request`...），並開啟 `testTagsAsResourceId`。

## 測試
- 單元測試（`app/src/test`，JVM）：core 各模組。
- Robolectric Compose UI 測試（`app/src/test`，`@RunWith(RobolectricTestRunner::class)` + `createComposeRule()`）：注入 `FakePitchSource` 餵腳本音高，驗證：校正流程、練習游標前進、回饋顏色、報告數字；`SessionLifecycleTest` 驗證麥克風只在 Tuning/Practice 開、背景重來、權限橫幅、略過／中途離開保留已存校正。**這是「mock 錄音」的 e2e 螢幕驗證**，可在 devcontainer 內免 emulator 執行。
- Instrumented（`app/src/androidTest`）：一支煙霧測試，於 emulator 啟動 App 驗證主畫面（HyperOS 實機會擋 instrumentation 啟動 Activity）。
- 實機驗證：`scripts/deploy-macos.sh` 安裝、`scripts/phone.sh` 操作與讀狀態，流程見 `.claude/skills/device-verify/SKILL.md`。

## 樣式/相依
- Material3、Compose BOM 已設定。主題 `Theme.CelloCoach`。
- 禁止新增未在 `app/build.gradle.kts` 宣告的相依。
