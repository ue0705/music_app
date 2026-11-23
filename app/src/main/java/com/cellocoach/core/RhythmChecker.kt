package com.cellocoach.core

import kotlin.math.abs

/** Kind of rhythm mistake. */
enum class RhythmIssue {
    /** Note came in too early (搶拍 / 太快). */
    RUSHING,
    /** Note came in too late, or the previous note was held too long (拖拍 / 太慢). */
    DRAGGING,
}

/**
 * One rhythm mistake on note [noteIdx], detected at [time] seconds.
 * [deviationSec] is played-minus-expected (negative = early).
 */
data class RhythmEvent(val noteIdx: Int, val issue: RhythmIssue, val deviationSec: Double, val time: Double)

/**
 * Real-time rhythm judge. Android-only addition (the Python reference has none).
 *
 * Fed once per tick with the elapsed time, the follower's current note and the
 * detected MIDI. It finds note **onsets** — the moment a run of the expected
 * pitch began — and compares the time between two onsets with the score's time
 * between those notes (already scaled to the practice tempo). Comparing
 * intervals, not absolute times, means a late entry is reported once instead of
 * flagging every following note.
 *
 *  - Onset earlier than allowed → [RhythmIssue.RUSHING].
 *  - Onset later than allowed → [RhythmIssue.DRAGGING].
 *  - Live: if the next note is overdue while the student is still holding the
 *    previous pitch (or is silent) → [RhythmIssue.DRAGGING] straight away,
 *    without waiting for the late note. A wrong pitch is not judged here — the
 *    pitch feedback handles it.
 *
 * Allowed deviation = max([MIN_TOLERANCE_SEC], [RELATIVE_TOLERANCE] × expected
 * interval). Each note is reported at most once. Repeated same-pitch notes only
 * count as a new onset after a silence (a re-bow), so a detector glitch can't
 * fake one.
 */
class RhythmChecker(private val notes: List<ScoreNote>) {

    private var runMidi: Int? = null
    private var runStart = 0.0
    private var runTicks = 0
    private var runAfterSilence = true
    private var lastOnsetIdx = -1
    private var lastOnsetTime = 0.0
    private val reported = HashSet<Int>()

    private val _events = mutableListOf<RhythmEvent>()
    val events: List<RhythmEvent> get() = _events

    fun count(issue: RhythmIssue): Int = _events.count { it.issue == issue }

    /** Feed one tick. Returns the event detected on this tick, if any. */
    fun observe(now: Double, currentIdx: Int, detectedMidi: Int?): RhythmEvent? {
        when {
            detectedMidi == null -> { runMidi = null; runTicks = 0; runAfterSilence = true }
            detectedMidi == runMidi -> runTicks++
            else -> {
                runAfterSilence = runMidi == null
                runMidi = detectedMidi; runStart = now; runTicks = 1
            }
        }
        val fired = checkOnset(currentIdx) ?: checkOverdue(now)
        fired?.let { _events += it; reported += it.noteIdx }
        return fired
    }

    private fun checkOnset(idx: Int): RhythmEvent? {
        if (idx !in notes.indices || idx <= lastOnsetIdx) return null
        val note = notes[idx]
        if (note.isRest || runMidi != note.midi || runTicks < ONSET_TICKS) return null
        if (lastOnsetIdx >= 0) {
            if (runStart <= lastOnsetTime) return null // same bow as the previous onset
            if (notes[lastOnsetIdx].midi == note.midi && !runAfterSilence) return null
        }
        val prevIdx = lastOnsetIdx
        val prevTime = lastOnsetTime
        lastOnsetIdx = idx
        lastOnsetTime = runStart
        if (prevIdx < 0 || idx in reported) return null
        val expected = note.start - notes[prevIdx].start
        val dev = (runStart - prevTime) - expected
        val tol = tolerance(expected)
        return when {
            dev < -tol -> RhythmEvent(idx, RhythmIssue.RUSHING, dev, runStart)
            dev > tol -> RhythmEvent(idx, RhythmIssue.DRAGGING, dev, runStart)
            else -> null
        }
    }

    private fun checkOverdue(now: Double): RhythmEvent? {
        if (lastOnsetIdx < 0) return null
        val held = notes[lastOnsetIdx].midi
        if (runMidi != null && runMidi != held) return null
        // Next pitched note whose pitch differs (a legato repeat can't be heard).
        var k = lastOnsetIdx + 1
        while (k < notes.size && (notes[k].isRest || notes[k].midi == held)) k++
        if (k >= notes.size || k in reported) return null
        val expected = notes[k].start - notes[lastOnsetIdx].start
        val dev = (now - lastOnsetTime) - expected
        return if (dev > tolerance(expected)) RhythmEvent(k, RhythmIssue.DRAGGING, dev, now) else null
    }

    private fun tolerance(expected: Double): Double =
        maxOf(MIN_TOLERANCE_SEC, RELATIVE_TOLERANCE * abs(expected))

    companion object {
        /** Ticks of a steady pitch before it counts as an onset (~150 ms at 20 Hz). */
        const val ONSET_TICKS = 3
        /** Smallest allowed timing deviation, in seconds. */
        const val MIN_TOLERANCE_SEC = 0.15
        /** Allowed deviation as a fraction of the expected interval. */
        const val RELATIVE_TOLERANCE = 0.3
    }
}
