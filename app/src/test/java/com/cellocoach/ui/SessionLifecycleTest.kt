package com.cellocoach.ui

import android.app.Application
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.cellocoach.audio.FakePitchSource
import com.cellocoach.core.Clock
import com.cellocoach.core.PitchFrame
import com.cellocoach.core.ScoreFollower
import com.cellocoach.core.Tuning
import com.cellocoach.core.midiToHz
import com.cellocoach.core.nominalHzForString
import com.cellocoach.data.TuningStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.pow

/**
 * Robolectric tests for the session plumbing around the engine:
 *
 *  - the microphone only runs on the Tuning / Practice screens and while the app
 *    is in the foreground (backgrounding restarts a practice run);
 *  - a denied `RECORD_AUDIO` permission shows a banner with a retry and never
 *    starts capture until it is granted;
 *  - skipping or abandoning a recalibration keeps the saved calibration, so the
 *    Home "已校正" badge and the next Start agree.
 *
 * Same harness as [CalibrationFlowTest] / [PracticeFlowTest]: a [FakePitchSource]
 * stands in for the mic ([FakePitchSource.isRunning] tells us whether capture is
 * on) and the engine is stepped via [PracticeViewModel.stepForTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionLifecycleTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val fake = FakePitchSource()
    private lateinit var vm: PracticeViewModel
    private var micRequests = 0

    /** Frozen follower clock so time-based advances never fire on their own. */
    private val clock = Clock { 0L }

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private fun launchApp(): PracticeViewModel {
        vm = PracticeViewModel(app, fake, clock = clock, autoDrive = false)
        composeTestRule.setContent {
            CelloCoachApp(vm, onRequestMicPermission = { micRequests++ })
        }
        composeTestRule.waitForIdle()
        return vm
    }

    /** Click an element on the (vertically scrolling) Home screen. */
    private fun clickHome(tag: String) {
        composeTestRule.onNodeWithTag(tag).performScrollTo().performClick()
        composeTestRule.waitForIdle()
    }

    private fun click(tag: String) {
        composeTestRule.onNodeWithTag(tag).performClick()
        composeTestRule.waitForIdle()
    }

    private fun tick(hz: Float?) {
        fake.emit(PitchFrame(hz, 0.2f))
        vm.stepForTest()
        composeTestRule.waitForIdle()
    }

    /** Persist a full 4-string calibration before the ViewModel is built. */
    private fun savedTuningOnDisk(): Tuning {
        val t = Tuning()
        listOf("C" to -3.0, "G" to 1.5, "D" to 0.0, "A" to 4.0).forEach { (string, cents) ->
            t.calibrateString(string, nominalHzForString(string) * 2.0.pow(cents / 1200.0))
        }
        TuningStore(app.filesDir).save(t)
        return t
    }

    // ---- Microphone lifecycle ------------------------------------------------

    @Test
    fun `mic runs only on the tuning and practice screens`() {
        launchApp()
        assertFalse("no capture on Home", fake.isRunning)

        clickHome(TestTags.HOME_START) // no saved tuning -> Tuning
        assertEquals(Screen.TUNING, vm.screen)
        assertTrue("capture on Tuning", fake.isRunning)

        click(TestTags.TUNING_SKIP)
        assertEquals(Screen.PRACTICE, vm.screen)
        assertTrue("capture on Practice", fake.isRunning)

        click(TestTags.NAV_HOME)
        assertEquals(Screen.HOME, vm.screen)
        assertFalse("capture stops when leaving Practice", fake.isRunning)
    }

    @Test
    fun `going to the background stops the mic and restarts the practice run`() {
        launchApp()
        clickHome(TestTags.HOME_START)
        click(TestTags.TUNING_SKIP)
        assertTrue(vm.started)

        // g_major_scale starts G2 (43), A2 (45): advance to note 1 by pitch.
        repeat(ScoreFollower.ADVANCE_THRESHOLD_TICKS) { tick(midiToHz(45).toFloat()) }
        assertEquals(1, vm.currentNoteIdx)

        vm.onForegroundChanged(false)
        composeTestRule.waitForIdle()
        assertFalse("no capture in the background", fake.isRunning)
        assertFalse("the run is abandoned", vm.started)
        assertEquals(-1, vm.currentNoteIdx)

        vm.onForegroundChanged(true)
        composeTestRule.waitForIdle()
        assertTrue("capture resumes", fake.isRunning)
        assertTrue("practice restarts", vm.started)
        assertEquals("from the first note", 0, vm.currentNoteIdx)
    }

    // ---- Permission ------------------------------------------------------------

    @Test
    fun `denied permission shows the banner and never starts the mic until granted`() {
        launchApp()
        composeTestRule.onNodeWithTag(TestTags.MIC_PERMISSION_BANNER).assertDoesNotExist()

        vm.onMicPermissionChanged(false)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(TestTags.MIC_PERMISSION_BANNER).assertExists()

        clickHome(TestTags.HOME_START)
        assertEquals(Screen.TUNING, vm.screen)
        assertFalse("no capture without permission", fake.isRunning)

        // The banner stays up on the listening screen and its button asks again.
        click(TestTags.MIC_PERMISSION_REQUEST)
        assertEquals(1, micRequests)

        vm.onMicPermissionChanged(true)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(TestTags.MIC_PERMISSION_BANNER).assertDoesNotExist()
        assertTrue("granting starts capture on a listening screen", fake.isRunning)
    }

    // ---- Saved calibration -------------------------------------------------------

    @Test
    fun `skipping a recalibration keeps the saved tuning`() {
        val saved = savedTuningOnDisk()
        launchApp()
        assertTrue(vm.hasSavedTuning)
        assertTrue(vm.tuning.isCalibrated())

        clickHome(TestTags.HOME_CALIBRATE)
        assertEquals(Screen.TUNING, vm.screen)
        click(TestTags.TUNING_SKIP)
        assertEquals(Screen.PRACTICE, vm.screen)
        assertFalse("this run is judged against A=440", vm.tuning.isCalibrated())

        click(TestTags.NAV_HOME)
        assertTrue("saved calibration is back in effect", vm.tuning.isCalibrated())
        assertEquals(saved.asMap(), vm.tuning.asMap())
        composeTestRule.onNodeWithTag(TestTags.HOME_TUNING_STATUS)
            .assertTextContains("已校正", substring = true)

        clickHome(TestTags.HOME_START)
        assertEquals("Start skips calibration, as the badge says", Screen.PRACTICE, vm.screen)
    }

    @Test
    fun `leaving a recalibration midway keeps the saved tuning`() {
        val saved = savedTuningOnDisk()
        launchApp()

        clickHome(TestTags.HOME_CALIBRATE)
        // Accept a new C (+17 cents, unlike the saved -3), then abandon at G.
        val newC = (nominalHzForString("C") * 1.01).toFloat()
        repeat(PracticeViewModel.CALIB_REQUIRED_TICKS) { tick(newC) }
        assertEquals("G", vm.calibTarget)

        click(TestTags.NAV_HOME)
        assertEquals(saved.asMap(), vm.tuning.asMap())
        val onDisk = TuningStore(app.filesDir).load()
        assertNotNull(onDisk)
        assertEquals("saved file untouched", saved.asMap(), onDisk!!.asMap())
    }
}
