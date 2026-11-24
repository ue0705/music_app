package com.cellocoach.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Bar lines and tempo-aware note values for the practice score view. */
class ScoreLayoutTest {

    private fun asset(name: String): ByteArray =
        listOf(File("src/main/assets/$name"), File("app/src/main/assets/$name"))
            .first { it.exists() }.readBytes()

    private fun q(step: String, oct: Int, dur: Int = 1) =
        "<note><pitch><step>$step</step><octave>$oct</octave></pitch><duration>$dur</duration></note>"

    private fun score(vararg measures: String) =
        "<score-partwise><part id=\"P1\">${measures.joinToString("")}</part></score-partwise>".toByteArray()

    @Test
    fun loaderRecordsEveryMeasureStart() {
        val s = ScoreLoader.load(asset("g_major_scale.musicxml"))
        // 8 quarters in 4/4 at 60 BPM → bars 1 and 2 at 0 s and 4 s.
        assertEquals(listOf(MeasureStart(0.0, 1), MeasureStart(4.0, 2)), s.measures)
    }

    @Test
    fun gMajorScaleHasOneBarLineBeforeTheFifthNote() {
        val s = ScoreLoader.load(asset("g_major_scale.musicxml"))
        assertEquals(listOf(Barline(4, 2)), ScoreLayout.barlines(s.notes, s.measures))
        assertEquals(1, ScoreLayout.firstMeasureNumber(s.measures))
    }

    @Test
    fun barLinesMatchTheXmlMeasuresIncludingPickupAndThreeFour() {
        val s = ScoreLoader.load(score(
            """<measure number="0"><attributes><divisions>1</divisions>
               <time><beats>3</beats><beat-type>4</beat-type></time></attributes>${q("D", 3)}</measure>""",
            """<measure number="1">${q("G", 2, 2)}${q("A", 2)}</measure>""",
            """<measure number="2">${q("B", 2)}${q("C", 3)}${q("D", 3)}</measure>""",
            """<measure number="3">${q("G", 2, 3)}</measure>""",
        ))
        assertEquals(
            listOf(Barline(1, 1), Barline(3, 2), Barline(6, 3)),
            ScoreLayout.barlines(s.notes, s.measures),
        )
        assertEquals(0, ScoreLayout.firstMeasureNumber(s.measures))
    }

    @Test
    fun twinkleGetsABarLinePerMeasure() {
        val s = ScoreLoader.load(asset("twinkle.mxl"))
        val bars = ScoreLayout.barlines(s.notes, s.measures)
        assertTrue("expected bar lines", bars.isNotEmpty())
        // Strictly increasing positions, one per measure after the first that has notes.
        assertEquals(bars.map { it.noteIdx }.sorted().distinct(), bars.map { it.noteIdx })
        assertTrue(bars.size <= s.measures.size - 1)
        assertTrue(bars.size >= s.measures.size - 3)
    }

    @Test
    fun quartersFollowTheTempoMap() {
        val tempos = listOf(TempoMark(0.0, 60.0), TempoMark(2.0, 120.0))
        assertEquals(2.0, ScoreLayout.quartersAt(2.0, tempos, 60.0), 1e-9)
        // After the change a quarter is 0.5 s: 2.5 s → 3 quarters, not 2.5.
        assertEquals(3.0, ScoreLayout.quartersAt(2.5, tempos, 60.0), 1e-9)
        assertEquals(1.5, ScoreLayout.quartersAt(1.5, emptyList(), 60.0), 1e-9)
    }
}
