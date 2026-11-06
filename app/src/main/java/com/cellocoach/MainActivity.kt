package com.cellocoach

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.cellocoach.audio.AudioPitchSource
import com.cellocoach.core.PitchSource
import com.cellocoach.ui.CelloCoachApp
import com.cellocoach.ui.PracticeViewModel

/**
 * Single Activity host.
 *
 * Responsibilities:
 *  - Own the `RECORD_AUDIO` permission (the [AudioPitchSource] assumes it's
 *    already granted): ask once on a fresh launch, keep the ViewModel informed of
 *    the real grant state (on create, on resume, after the dialog) so it never
 *    starts capture without it and the UI can show a "no permission" banner, and
 *    handle the banner's retry — re-asking, or opening the app's system settings
 *    once Android has stopped showing the dialog.
 *  - Tell the ViewModel when the app enters / leaves the foreground so the mic
 *    is not left recording in the background.
 *  - Build the [PracticeViewModel] with an injected [PitchSource]. Production
 *    uses [AudioPitchSource]; the [PracticeViewModel.factory] indirection is what
 *    lets a Robolectric test construct the same ViewModel with a
 *    [com.cellocoach.audio.FakePitchSource] instead.
 *  - `setContent { CelloCoachApp(viewModel, ...) }`.
 *
 * The whole audio/UI stack moved in-process here, so unlike the original Flask
 * project there is no HTTPS / secure-context / LAN plumbing — just a permission
 * prompt and Compose.
 */
class MainActivity : ComponentActivity() {

    /** Swappable for tests; defaults to the real mic-backed source. */
    private val pitchSource: PitchSource by lazy { AudioPitchSource() }

    private val viewModel: PracticeViewModel by viewModels {
        viewModelFactory()
    }

    /** Exposed so instrumentation/Robolectric can override the source if desired. */
    private fun viewModelFactory(): ViewModelProvider.Factory =
        PracticeViewModel.factory(application, pitchSource)

    /**
     * Set once a request comes back denied with no rationale to show: Android
     * will no longer display the dialog ("don't ask again"), so the banner's
     * retry has to send the student to the app's settings page instead.
     */
    private var micRequestBlocked = false

    private val requestPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            viewModel.onMicPermissionChanged(granted)
            if (!granted && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
                micRequestBlocked = true
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        micRequestBlocked = savedInstanceState?.getBoolean(KEY_MIC_BLOCKED) ?: false
        val granted = hasMicPermission()
        viewModel.onMicPermissionChanged(granted)
        // Ask once on a fresh launch; afterwards the banner's button retries.
        if (!granted && savedInstanceState == null) {
            requestPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
        setContent {
            CelloCoachApp(viewModel, onRequestMicPermission = ::requestMicPermission)
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.onForegroundChanged(true)
    }

    override fun onResume() {
        super.onResume()
        // Re-sync: the student may have granted (or revoked) it in system settings.
        viewModel.onMicPermissionChanged(hasMicPermission())
    }

    override fun onStop() {
        super.onStop()
        // A rotation recreates the Activity while the ViewModel (and the mic) carry on.
        if (!isChangingConfigurations) viewModel.onForegroundChanged(false)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_MIC_BLOCKED, micRequestBlocked)
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** The banner's retry: re-ask, or open app settings if Android won't ask again. */
    private fun requestMicPermission() {
        if (micRequestBlocked) {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null),
                ),
            )
        } else {
            requestPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private companion object {
        const val KEY_MIC_BLOCKED = "mic_request_blocked"
    }
}
