package com.cellocoach.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RhythmChecker] driven tick by tick (50 ms). The score is four quarter notes at
 * 60 BPM: C3 D3 E3 F3 at 0, 1, 2, 3 s. The "follower index" is passed in directly.
 */
class RhythmCheckerTest {

    private val notes = listOf(
        ScoreNote(0.0, 1.0, 48, "C3"),
        ScoreNote(1.0, 2.0, 50, "D3"),
        ScoreNote(2.0, 3.0, 52, "E3"),
        ScoreNote(3.0, 4.0, 53, "F3"),
    )
    private val tick = 0.05

    /** Play [midi] (idx [idx]) from [from] to [to] seconds; returns events fired. */
    private fun RhythmChecker.play(idx: Int, midi: Int?, from: Double, to: Double): List<RhythmEvent> {
        val out = mutableListOf<RhythmEvent>()
        var t = from
        while (t < to - 1e-9) { observe(t, idx, midi)?.let { out += it }; t += tick }
        return out
    }

    @Test
    fun onTimePlayingRaisesNothing() {
        val r = RhythmChecker(notes)
        for (i in notes.indices) assertTrue(r.play(i, notes[i].midi, i * 1.0, i + 1.0).isEmpty())
        assertTrue(r.events.isEmpty())
    }

    @Test
    fun smallTimingWobbleIsTolerated() {
        val r = RhythmChecker(notes)
        r.play(0, 48, 0.0, 1.1)
        assertTrue(r.play(1, 50, 1.1, 2.0).isEmpty()) // 0.1 s late < 0.3 s tolerance
    }

    @Test
    fun earlyNoteIsRushing() {
        val r = RhythmChecker(notes)
        r.play(0, 48, 0.0, 0.5)
        val ev = r.play(1, 50, 0.5, 1.0) // D3 half a beat early
        assertEquals(1, ev.size)
        assertEquals(RhythmIssue.RUSHING, ev[0].issue)
        assertEquals(1, ev[0].noteIdx)
        assertEquals(-0.5, ev[0].deviationSec, 1e-6)
    }

    @Test
    fun holdingTooLongWarnsLiveBeforeTheLateNote() {
        val r = RhythmChecker(notes)
        r.play(0, 48, 0.0, 1.0)
        // Still on C3 at 1.3 s: inside tolerance.
        assertTrue(r.play(0, 48, 1.0, 1.3).isEmpty())
        // Past 1.3 s the D3 is overdue → warned while C3 is still sounding.
        val ev = r.play(0, 48, 1.3, 1.5)
        assertEquals(1, ev.size)
        assertEquals(RhythmIssue.DRAGGING, ev[0].issue)
        assertEquals(1, ev[0].noteIdx)
        // When D3 finally arrives it is not reported a second time.
        assertTrue(r.play(1, 50, 1.5, 2.0).isEmpty())
        assertEquals(1, r.count(RhythmIssue.DRAGGING))
    }

    @Test
    fun lateEntryIsJudgedOnceThenIntervalsCount() {
        val r = RhythmChecker(notes)
        // Started 0.6 s late, but every following interval is right.
        r.play(0, null, 0.0, 0.6)
        r.play(0, 48, 0.6, 1.6)
        assertTrue(r.play(1, 50, 1.6, 2.6).isEmpty())
        assertTrue(r.play(2, 52, 2.6, 3.6).isEmpty())
        assertTrue(r.events.isEmpty())
    }

    @Test
    fun wrongPitchIsNotARhythmError() {
        val r = RhythmChecker(notes)
        r.play(0, 48, 0.0, 1.0)
        // Playing a wrong note (G3) instead of D3: pitch feedback, not rhythm.
        assertTrue(r.play(1, 55, 1.0, 2.0).isEmpty())
    }

    @Test
    fun repeatedPitchNeedsARebowToCount() {
        val rep = listOf(
            ScoreNote(0.0, 1.0, 48, "C3"),
            ScoreNote(1.0, 2.0, 48, "C3"),
            ScoreNote(2.0, 3.0, 50, "D3"),
        )
        val r = RhythmChecker(rep)
        r.play(0, 48, 0.0, 0.95)
        r.play(0, null, 0.95, 1.0) // short silence = re-bow
        // Follower moves to note 1 at 0.8 s in real life; re-bow at 1.0 s is on time.
        assertTrue(r.play(1, 48, 1.0, 2.0).isEmpty())
        assertTrue(r.play(2, 50, 2.0, 2.5).isEmpty())
        assertTrue(r.events.isEmpty())
    }

    @Test
    fun restsBetweenNotesAreCountedInTheInterval() {
        val withRest = listOf(
            ScoreNote(0.0, 1.0, 48, "C3"),
            ScoreNote(1.0, 2.0, ScoreNote.REST, "rest"),
            ScoreNote(2.0, 3.0, 50, "D3"),
        )
        val r = RhythmChecker(withRest)
        r.play(0, 48, 0.0, 1.0)
        assertNull(r.play(1, null, 1.0, 2.0).firstOrNull())
        assertTrue(r.play(2, 50, 2.0, 2.5).isEmpty())
    }
}
