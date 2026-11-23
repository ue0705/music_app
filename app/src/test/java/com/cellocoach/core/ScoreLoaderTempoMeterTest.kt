package com.cellocoach.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tempo changes, time signatures and the beat grid in [ScoreLoader]. */
class ScoreLoaderTempoMeterTest {

    private fun q(step: String, oct: Int, dur: Int = 1) =
        "<note><pitch><step>$step</step><octave>$oct</octave></pitch><duration>$dur</duration></note>"

    private fun score(vararg measures: String) = """
        <score-partwise><part id="P1">${measures.joinToString("")}</part></score-partwise>
    """.trimIndent().toByteArray()

    @Test
    fun midScoreTempoChangeRetimesLaterNotes() {
        val bytes = score(
            """<measure number="1"><attributes><divisions>1</divisions>
               <time><beats>2</beats><beat-type>4</beat-type></time></attributes>
               <sound tempo="60"/>${q("C", 3)}${q("D", 3)}</measure>""",
            """<measure number="2">
               <direction><direction-type><metronome><beat-unit>quarter</beat-unit>
               <per-minute>120</per-minute></metronome></direction-type></direction>
               ${q("E", 3)}${q("F", 3)}</measure>""",
        )
        val s = ScoreLoader.load(bytes)
        assertEquals(60.0, s.bpm, 1e-9)
        // Bar 1 at 60 BPM: 1 s per quarter; bar 2 at 120 BPM: 0.5 s per quarter.
        assertEquals(listOf(0.0, 1.0, 2.0, 2.5), s.notes.map { it.start })
        assertEquals(3.0, s.notes.last().end, 1e-9)
        assertEquals(listOf(TempoMark(0.0, 60.0), TempoMark(2.0, 120.0)), s.tempos)
        // Beat grid follows the change too.
        assertEquals(listOf(0.0, 1.0, 2.0, 2.5), s.beats.map { it.time })
    }

    @Test
    fun threeFourGivesThreeBeatsPerBarWithDownbeats() {
        val bytes = score(
            """<measure number="1"><attributes><divisions>1</divisions>
               <time><beats>3</beats><beat-type>4</beat-type></time></attributes>
               <sound tempo="60"/>${q("G", 2, 3)}</measure>""",
            """<measure number="2">${q("A", 2)}${q("B", 2)}${q("C", 3)}</measure>""",
        )
        val s = ScoreLoader.load(bytes)
        assertEquals(TimeSignature(3, 4), s.timeSignature)
        assertEquals("3/4", s.timeSignature.toString())
        assertEquals(listOf(1, 2, 3, 1, 2, 3), s.beats.map { it.beat })
        assertEquals(listOf(1, 1, 1, 2, 2, 2), s.beats.map { it.measure })
        assertTrue(s.beats[3].isDownbeat)
        assertEquals(3.0, s.beats[3].time, 1e-9)
    }

    @Test
    fun pickupMeasureBeatsAreRightAligned() {
        val bytes = score(
            """<measure number="0"><attributes><divisions>1</divisions>
               <time><beats>4</beats><beat-type>4</beat-type></time></attributes>
               <sound tempo="60"/>${q("D", 3)}</measure>""",
            """<measure number="1">${q("G", 2, 4)}</measure>""",
        )
        val s = ScoreLoader.load(bytes)
        // A one-beat upbeat is beat 4 of bar 0; the next bar starts on beat 1.
        assertEquals(4, s.beats.first().beat)
        assertEquals(0, s.beats.first().measure)
        assertEquals(listOf(4, 1, 2, 3, 4), s.beats.map { it.beat })
        assertEquals(1.0, s.beats[1].time, 1e-9)
    }

    @Test
    fun sixEightBeatsAreEighthNotesAndDottedMetronomeConverts() {
        val bytes = score(
            """<measure number="1"><attributes><divisions>2</divisions>
               <time><beats>6</beats><beat-type>8</beat-type></time></attributes>
               <direction><direction-type><metronome><beat-unit>quarter</beat-unit>
               <beat-unit-dot/><per-minute>40</per-minute></metronome></direction-type></direction>
               ${q("C", 3, 3)}${q("D", 3, 3)}</measure>""",
        )
        val s = ScoreLoader.load(bytes)
        // Dotted quarter = 40 → quarter = 60.
        assertEquals(60.0, s.bpm, 1e-9)
        assertEquals(0.5, s.timeSignature.beatQuarters, 1e-9)
        assertEquals(6, s.beats.size)
        assertEquals(0.5, s.beats[1].time, 1e-9)
    }

    @Test
    fun noTimeSignatureDefaultsToFourFour() {
        val s = ScoreLoader.load(score("""<measure number="1"><attributes><divisions>1</divisions></attributes>
            ${q("C", 3)}</measure>"""))
        assertEquals(TimeSignature(4, 4), s.timeSignature)
        assertEquals(listOf(TempoMark(0.0, 120.0)), s.tempos)
    }
}
