package com.cellocoach.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import com.cellocoach.audio.FakePitchSource
import com.cellocoach.core.Clock
import com.cellocoach.core.PitchFrame
import com.cellocoach.core.RhythmIssue
import com.cellocoach.core.midiToHz
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric test of the live rhythm warning and the beat display on the
 * practice screen. `g_major_scale` is 4/4, ♩ = 60: one note per second.
 * Every tick moves the injected clock by 50 ms, like the real tick loop.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RhythmWarningTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val fake = FakePitchSource()

    private class FakeClock(var nanos: Long = 0L) : Clock {
        override fun nowNanos(): Long = nanos
    }

    private val clock = FakeClock()
    private lateinit var vm: PracticeViewModel

    private fun start(waitMode: Boolean = false) {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        vm = PracticeViewModel(app, fake, clock = clock, autoDrive = false)
        composeTestRule.setContent { CelloCoachApp(vm) }
        composeTestRule.runOnIdle {
            vm.changeWaitMode(waitMode)
            vm.skipCalibration()
        }
        composeTestRule.waitForIdle()
        assertEquals(Screen.PRACTICE, vm.screen)
    }

    /** Play [midi] for [seconds] in 50 ms ticks. */
    private fun play(midi: Int?, seconds: Double) {
        repeat((seconds / 0.05).toInt()) {
            clock.nanos += 50_000_000L
            fake.emit(PitchFrame(midi?.let { m -> midiToHz(m).toFloat() }, 0.2f))
            vm.stepForTest()
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun `playing the next note early shows a rushing warning at once`() {
        start()
        play(43, 0.45)          // G2 for under half a beat …
        play(45, 0.3)           // … then A2 half a beat early
        assertEquals(RhythmIssue.RUSHING, vm.rhythmWarning)
        assertEquals(1, vm.rushCount)
        composeTestRule.onNodeWithTag(TestTags.PRACTICE_RHYTHM_WARNING)
            .assertIsDisplayed()
            .assertTextContains("搶拍", substring = true)
    }

    @Test
    fun `holding a note too long shows a dragging warning before the next note`() {
        start()
        play(43, 1.5)           // G2 held 1.5 beats; A2 was due at 1.0 s
        assertEquals(RhythmIssue.DRAGGING, vm.rhythmWarning)
        assertEquals(1, vm.dragCount)
        composeTestRule.onNodeWithTag(TestTags.PRACTICE_RHYTHM_WARNING)
            .assertTextContains("拖拍", substring = true)
    }

    @Test
    fun `steady playing raises no warning and the warning clears after a while`() {
        start()
        play(43, 1.0)
        play(45, 1.0)
        play(47, 1.0)
        assertNull(vm.rhythmWarning)
        assertEquals(0, vm.rushCount + vm.dragCount)
        // A late note raises the warning; 1.5 s later it is gone again.
        play(47, 0.5)
        assertEquals(RhythmIssue.DRAGGING, vm.rhythmWarning)
        play(null, PracticeViewModel.RHYTHM_WARNING_HOLD_SEC + 0.2)
        assertNull(vm.rhythmWarning)
    }

    @Test
    fun `beat display follows the score meter`() {
        start()
        play(43, 0.1)
        composeTestRule.onNodeWithTag(TestTags.PRACTICE_BEAT).assertTextEquals("第 1 小節・第 1 拍 ●")
        play(43, 1.0)
        composeTestRule.onNodeWithTag(TestTags.PRACTICE_BEAT).assertTextEquals("第 1 小節・第 2 拍")
        composeTestRule.onNodeWithTag(TestTags.PRACTICE_TEMPO).assertTextContains("4/4", substring = true)
    }

    @Test
    fun `wait mode never judges rhythm`() {
        start(waitMode = true)
        play(43, 3.0)
        assertNull(vm.rhythmWarning)
        assertEquals(0, vm.dragCount)
    }
}
