package com.cellocoach.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.cellocoach.core.PitchDetector
import com.cellocoach.core.PitchFrame
import com.cellocoach.core.PitchSource
import kotlin.concurrent.thread
import kotlin.math.sqrt

/**
 * Real-microphone [PitchSource].
 *
 * Replaces the original project's browser-side capture: in the Python/Flask
 * version the page used the Web Audio API + Pitchy and POSTed `{hz, rms}` to the
 * server at ~20 Hz. Here we do the exact same job in-process — read mono PCM from
 * [AudioRecord] on a background thread, run [PitchDetector] (the Kotlin port of
 * Pitchy's NSDF estimator) on each window, and push a [PitchFrame] to [onFrame]
 * about 20 times a second.
 *
 * The class assumes the `RECORD_AUDIO` runtime permission has already been
 * granted by the UI layer ([com.cellocoach.MainActivity]); it does not request
 * it. The [SuppressLint] on [start] documents that contract.
 *
 * Threading: capture runs on a single daemon thread spun up in [start] and torn
 * down in [stop]. [onFrame] is therefore invoked off the main thread; the
 * ViewModel marshals state back onto the UI via Compose state.
 */
class AudioPitchSource(
    private val detector: PitchDetector = PitchDetector(),
) : PitchSource {

    /** AudioRecord sample rate; matches the detector's default 44.1 kHz. */
    private val sampleRate = detector.sampleRate

    /**
     * Window of samples fed to the detector. ~50 ms at 44.1 kHz (2205 → 2048 is
     * a clean power-of-two-ish chunk). This drives the ~20 Hz frame rate.
     */
    private val windowSize = 2048

    @Volatile private var running = false

    /**
     * Bumped on every [start] / [stop]. A capture loop only keeps going (and only
     * emits) while its own generation is current, so a quick stop→start can never
     * leave an old loop reading the mic alongside the new one.
     */
    @Volatile private var generation = 0
    private var recordThread: Thread? = null
    @Volatile private var recorder: AudioRecord? = null

    /**
     * Begin capture. Spawns the recording loop on a background thread; returns
     * immediately. A second call while running is ignored; it may be called again
     * after [stop] (the ViewModel starts/stops capture as screens change).
     */
    @SuppressLint("MissingPermission") // RECORD_AUDIO granted by the UI before construction.
    override fun start(onFrame: (PitchFrame) -> Unit) {
        if (running) return
        running = true
        val gen = ++generation
        fun current() = running && gen == generation

        recordThread = thread(name = "AudioPitchSource", isDaemon = true) {
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            val bufferBytes = maxOf(minBuf, windowSize * 2)

            val record = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes,
            )
            recorder = record

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                if (recorder === record) recorder = null
                if (gen == generation) running = false
                return@thread
            }

            val shortBuf = ShortArray(windowSize)
            val floatBuf = FloatArray(windowSize)
            try {
                record.startRecording()
                while (current()) {
                    val read = record.read(shortBuf, 0, windowSize)
                    if (read <= 0) continue

                    // Convert 16-bit PCM to float [-1, 1] and compute frame RMS.
                    var sumSq = 0.0
                    for (i in 0 until read) {
                        val v = shortBuf[i] / 32768f
                        floatBuf[i] = v
                        sumSq += v.toDouble() * v
                    }
                    val rms = sqrt(sumSq / read).toFloat()

                    val window = if (read == windowSize) floatBuf else floatBuf.copyOf(read)
                    val hz = detector.detect(window)
                    if (current()) onFrame(PitchFrame(hz = hz, rms = rms))
                }
            } catch (_: IllegalStateException) {
                // Recorder torn down mid-read; loop exits via running flag.
            } finally {
                runCatching { record.stop() }
                runCatching { record.release() }
                if (recorder === record) recorder = null
            }
        }
    }

    /** Stop capture and release the recorder. Idempotent. */
    override fun stop() {
        running = false
        generation++
        val t = recordThread
        recordThread = null
        if (t != null) {
            runCatching { t.join(500) }
            // Still blocked in read() after the grace period: release its recorder
            // to unblock it (the loop then exits on its stale generation).
            if (t.isAlive) {
                recorder?.let {
                    runCatching { it.stop() }
                    runCatching { it.release() }
                }
            }
        }
        recorder = null
    }
}
