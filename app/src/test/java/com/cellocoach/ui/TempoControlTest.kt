package com.cellocoach.ui

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.cellocoach.audio.FakePitchSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric Compose tests for the metronome-style practice tempo (♩ = N) on
 * the Home screen and its effect on the ViewModel. The bundled default score
 * `g_major_scale.musicxml` declares `<sound tempo="60"/>`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TempoControlTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val fake = FakePitchSource()

    private fun launchApp(): PracticeViewModel {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val vm = PracticeViewModel(app, fake, autoDrive = false)
        composeTestRule.setContent { CelloCoachApp(vm) }
        composeTestRule.waitForIdle()
        return vm
    }

    private fun click(tag: String) {
        composeTestRule.onNodeWithTag(tag).performScrollTo().performClick()
        composeTestRule.waitForIdle()
    }

    @Test
    fun `practice tempo starts at the score tempo`() {
        val vm = launchApp()
        assertEquals(60.0, vm.bpm, 1e-9)
        assertEquals(60.0, vm.practiceBpm, 1e-9)
        assertEquals(1.0, vm.tempoFactor, 1e-9)
        composeTestRule.onNodeWithTag(TestTags.HOME_BPM_VALUE).assertTextEquals("♩ = 60")
        // Already at the score tempo, so "回到原速" has nothing to do.
        composeTestRule.onNodeWithTag(TestTags.HOME_BPM_RESET).assertIsNotEnabled()
    }

    @Test
    fun `minus and plus buttons change the tempo by one BPM`() {
        val vm = launchApp()
        click(TestTags.HOME_BPM_MINUS)
        click(TestTags.HOME_BPM_MINUS)
        assertEquals(58.0, vm.practiceBpm, 1e-9)
        composeTestRule.onNodeWithTag(TestTags.HOME_BPM_VALUE).assertTextEquals("♩ = 58")

        click(TestTags.HOME_BPM_PLUS)
        assertEquals(59.0, vm.practiceBpm, 1e-9)
        assertEquals(59.0 / 60.0, vm.tempoFactor, 1e-9)
    }

    @Test
    fun `reset returns to the score tempo`() {
        val vm = launchApp()
        composeTestRule.runOnIdle { vm.changePracticeBpm(45.0) }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(TestTags.HOME_BPM_VALUE).assertTextEquals("♩ = 45")

        click(TestTags.HOME_BPM_RESET)
        assertEquals(60.0, vm.practiceBpm, 1e-9)
    }

    @Test
    fun `tempo is rounded and clamped to the metronome range`() {
        val vm = launchApp()
        composeTestRule.runOnIdle { vm.changePracticeBpm(72.4) }
        assertEquals(72.0, vm.practiceBpm, 1e-9)

        composeTestRule.runOnIdle { vm.changePracticeBpm(5.0) }
        assertEquals(PracticeViewModel.MIN_PRACTICE_BPM, vm.practiceBpm, 1e-9)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(TestTags.HOME_BPM_MINUS).assertIsNotEnabled()

        composeTestRule.runOnIdle { vm.changePracticeBpm(999.0) }
        assertEquals(PracticeViewModel.MAX_PRACTICE_BPM, vm.practiceBpm, 1e-9)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(TestTags.HOME_BPM_PLUS).assertIsNotEnabled()
        // Faster than the score is allowed too.
        assertEquals(4.0, vm.tempoFactor, 1e-9)
    }

    @Test
    fun `wait mode is an independent switch that keeps the chosen tempo`() {
        val vm = launchApp()
        composeTestRule.runOnIdle { vm.changePracticeBpm(40.0) }
        assertFalse(vm.waitMode)

        click(TestTags.HOME_WAIT_MODE)
        assertTrue(vm.waitMode)
        assertEquals(40.0, vm.practiceBpm, 1e-9)

        click(TestTags.HOME_WAIT_MODE)
        assertFalse(vm.waitMode)
    }

    @Test
    fun `practice screen shows the chosen tempo`() {
        val vm = launchApp()
        composeTestRule.runOnIdle { vm.changePracticeBpm(50.0) }
        composeTestRule.runOnIdle { vm.goToPractice() }
        composeTestRule.waitForIdle()
        assertEquals(Screen.PRACTICE, vm.screen)
        composeTestRule.onNodeWithTag(TestTags.PRACTICE_TEMPO).assertTextEquals("♩ = 50・4/4")
    }
}
