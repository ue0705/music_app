package com.cellocoach.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import com.cellocoach.ui.screens.HomeScreen
import com.cellocoach.ui.screens.PracticeScreen
import com.cellocoach.ui.screens.ReportScreen
import com.cellocoach.ui.screens.TuningScreen

/**
 * Root composable. Owns the [CelloCoachTheme] and switches between the four
 * screens purely off [PracticeViewModel.screen] — no navigation library, just a
 * `when` over the [Screen] enum (the contract explicitly forbids a nav dep).
 *
 * The [PracticeViewModel] is passed in (not created here) so [com.cellocoach.MainActivity]
 * can inject the real [com.cellocoach.audio.AudioPitchSource] while Robolectric
 * tests inject a [com.cellocoach.audio.FakePitchSource] to drive the flow
 * deterministically.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun CelloCoachApp(
    vm: PracticeViewModel,
    /** Called by the "no microphone permission" banner's retry button. */
    onRequestMicPermission: () -> Unit = {},
) {
    CelloCoachTheme {
        // Expose Compose testTags as resource-ids so uiautomator / instrumented
        // tooling can target them by id (harmless in production).
        Surface(modifier = Modifier
            .fillMaxSize()
            .semantics { testTagsAsResourceId = true }) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(titleFor(vm.screen)) },
                        navigationIcon = {
                            if (vm.screen != Screen.HOME) {
                                IconButton(
                                    onClick = { vm.goHome() },
                                    modifier = Modifier.testTag(TestTags.NAV_HOME),
                                ) {
                                    Icon(Icons.Filled.Home, contentDescription = "首頁")
                                }
                            }
                        },
                    )
                },
            ) { padding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .testTag(TestTags.SCREEN_ROOT),
                ) {
                    if (vm.micPermissionDenied) {
                        MicPermissionBanner(onRequestMicPermission)
                    }
                    Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        when (vm.screen) {
                            Screen.HOME -> HomeScreen(vm)
                            Screen.TUNING -> TuningScreen(vm)
                            Screen.PRACTICE -> PracticeScreen(vm)
                            Screen.REPORT -> ReportScreen(vm)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Shown on every screen while `RECORD_AUDIO` is denied, so a student never
 * practises against a silent mic without knowing why. The button re-asks (or
 * opens the app's system settings once Android stops showing the dialog).
 */
@Composable
private fun MicPermissionBanner(onRequest: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(TestTags.MIC_PERMISSION_BANNER),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "沒有麥克風權限，App 聽不到你拉琴。",
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = onRequest,
                modifier = Modifier.testTag(TestTags.MIC_PERMISSION_REQUEST),
            ) { Text("授權麥克風") }
        }
    }
}

private fun titleFor(screen: Screen): String = when (screen) {
    Screen.HOME -> "大提琴練習助手"
    Screen.TUNING -> "校正"
    Screen.PRACTICE -> "練習"
    Screen.REPORT -> "報告"
}
