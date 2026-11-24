package com.cellocoach.core

/** A bar line drawn just before note [noteIdx]; [measure] is the bar that starts there. */
data class Barline(val noteIdx: Int, val measure: Int)

/**
 * Pure layout helpers for the practice score view (no Android dependencies, so
 * they are unit-tested on the JVM). The view spaces notes evenly, so bar lines are
 * placed by note index: before the first note that starts in each `<measure>`.
 */
object ScoreLayout {

    private const val EPS = 1e-6

    /**
     * Bar lines for [notes] from the score's [measures]: one before the first note
     * that starts at or after each measure's start. The first measure gets none
     * (the clef starts the staff); a measure with no note of its own is skipped.
     */
    fun barlines(notes: List<ScoreNote>, measures: List<MeasureStart>): List<Barline> {
        if (notes.isEmpty()) return emptyList()
        val out = ArrayList<Barline>()
        for (m in measures) {
            val idx = notes.indexOfFirst { it.start >= m.time - EPS }
            if (idx <= 0) continue
            // The measure's first note must start before the next bar does.
            val next = measures.firstOrNull { it.time > m.time + EPS }
            if (next != null && notes[idx].start >= next.time - EPS) continue
            if (out.lastOrNull()?.noteIdx == idx) out[out.lastIndex] = Barline(idx, m.number)
            else out += Barline(idx, m.number)
        }
        return out
    }

    /** Number of the measure the first note belongs to (shown at the start of the staff). */
    fun firstMeasureNumber(measures: List<MeasureStart>): Int? = measures.firstOrNull()?.number

    /**
     * Position in quarter notes of [seconds] through the tempo map [tempos]
     * (first entry at 0 s). Falls back to [bpm] when there is no map, so note
     * values are still classified correctly after a mid-score tempo change.
     */
    fun quartersAt(seconds: Double, tempos: List<TempoMark>, bpm: Double): Double {
        if (tempos.isEmpty()) return seconds * bpm.coerceAtLeast(1.0) / 60.0
        var q = 0.0
        for (i in tempos.indices) {
            val (start, t) = tempos[i]
            val end = tempos.getOrNull(i + 1)?.time
            if (end == null || seconds <= end) return q + (seconds - start) * t / 60.0
            q += (end - start) * t / 60.0
        }
        return q
    }
}
