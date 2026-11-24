package com.cellocoach.core

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Result of loading a MusicXML score: a flattened single-voice [notes] timeline
 * (rests included, matching the Python reference) and the effective [bpm] that is
 * baked into each note's start/end times.
 */
data class LoadedScore(
    val notes: List<ScoreNote>,
    /** Tempo at the start of the score (quarter-note BPM). */
    val bpm: Double,
    /** Metronome grid derived from the score's time signatures (seconds, at the score tempo). */
    val beats: List<Beat> = emptyList(),
    /** Tempo map: the first entry is at time 0; later entries are tempo changes in the score. */
    val tempos: List<TempoMark> = listOf(TempoMark(0.0, bpm)),
    /** Time signature of the first measure (4/4 when the score has none). */
    val timeSignature: TimeSignature = TimeSignature(4, 4),
    /** Start of every `<measure>` in the first part, in score order (for bar lines). */
    val measures: List<MeasureStart> = emptyList(),
)

/** A `<measure>` begins at [time] seconds; [number] is its `number` attribute (or its 1-based position). */
data class MeasureStart(val time: Double, val number: Int)

/** A time signature such as 3/4: [beats] per measure, each one 1/[beatType] note long. */
data class TimeSignature(val beats: Int, val beatType: Int) {
    /** Length of one beat in quarter notes (4/4 → 1.0, 6/8 → 0.5, 2/2 → 2.0). */
    val beatQuarters: Double get() = 4.0 / beatType
    override fun toString(): String = "$beats/$beatType"
}

/** One metronome beat at [time] seconds: beat [beat] (1-based) of measure [measure]. */
data class Beat(val time: Double, val measure: Int, val beat: Int) {
    val isDownbeat: Boolean get() = beat == 1
}

/** From [time] seconds on, the score's tempo is [bpm] quarter notes per minute. */
data class TempoMark(val time: Double, val bpm: Double)

/**
 * Port of `score_loader.py`.
 *
 * The Python original leans on `music21` to parse MusicXML and flatten the part
 * into a timeline. music21 is not available on Android, so this reimplements the
 * relevant slice of MusicXML parsing directly with the JDK's
 * [javax.xml.parsers.DocumentBuilder]:
 *
 *  - `.mxl` (zipped MusicXML) is detected by the "PK" ZIP magic bytes and the main
 *    score XML is pulled out via [java.util.zip] (reading `META-INF/container.xml`
 *    when present, otherwise the first non-`META-INF` `.xml`/`.musicxml` entry).
 *  - Tempo precedence mirrors the contract: `bpmOverride` → first `<sound tempo>`
 *    or `<metronome>` (beat-unit + per-minute) → 120 BPM default.
 *  - Only the first `<part>` is read. Time is accumulated measure-by-measure using
 *    the running `<divisions>` from `<attributes>`. `<note>`s advance the cursor,
 *    `<backup>` rewinds it, `<forward>` advances it, and `<chord/>` notes share the
 *    previous note's onset (the highest pitch of a chord wins) without advancing.
 */
object ScoreLoader {

    /** Semitone offset of each diatonic step within an octave (C=0 … B=11). */
    private val STEP_SEMITONE = mapOf(
        "C" to 0, "D" to 2, "E" to 4, "F" to 5, "G" to 7, "A" to 9, "B" to 11,
    )

    /** Quarter-length of each `<beat-unit>` value used by `<metronome>`. */
    private val BEAT_UNIT_QUARTERS = mapOf(
        "whole" to 4.0,
        "half" to 2.0,
        "quarter" to 1.0,
        "eighth" to 0.5,
        "16th" to 0.25,
        "32nd" to 0.125,
        "64th" to 0.0625,
    )

    private const val DEFAULT_BPM = 120.0

    /**
     * Parse [bytes] (plain MusicXML or an `.mxl` ZIP) into a [LoadedScore].
     *
     * @param bytes raw bytes; if they begin with the "PK" ZIP magic they are unzipped first.
     * @param bpmOverride if non-null, forces the tempo and overrides anything in the score.
     */
    fun load(bytes: ByteArray, bpmOverride: Double? = null): LoadedScore {
        val xmlBytes = unwrapIfZip(bytes)
        val doc = parseXml(xmlBytes)

        val bpm = bpmOverride ?: detectTempo(doc) ?: DEFAULT_BPM

        val part = firstElement(doc.documentElement, "part")
            ?: return LoadedScore(emptyList(), bpm)

        // Notes are collected with start/end in *quarter notes* and converted to
        // seconds through the tempo map at the end, so mid-score tempo changes land
        // where the score puts them.
        val notes = mutableListOf<ScoreNote>()
        val tempoEvents = mutableListOf<Pair<Double, Double>>() // (quarter position, bpm)
        val beatsQ = mutableListOf<Triple<Double, Int, Int>>()  // (quarter position, measure, beat)
        val measuresQ = mutableListOf<Pair<Double, Int>>()      // (quarter position, measure number)
        var firstTimeSig: TimeSignature? = null
        var timeSig = TimeSignature(4, 4)
        var divisions = 1.0
        var cursorQuarters = 0.0       // current playback position, in quarter notes
        var lastOnsetQuarters = 0.0    // onset of the previous note (for <chord/>)

        for ((mIndex, measure) in childElements(part, "measure").withIndex()) {
            val measureNo = measure.getAttribute("number").trim().toIntOrNull() ?: (mIndex + 1)
            val measureStart = cursorQuarters
            measuresQ += measureStart to measureNo
            var measureEnd = cursorQuarters
            for (child in childElements(measure)) {
                when (child.tagName) {
                    "attributes" -> {
                        firstElement(child, "divisions")?.textContent?.trim()
                            ?.toDoubleOrNull()?.let { if (it > 0) divisions = it }
                        firstElement(child, "time")?.let { parseTime(it) }?.let { timeSig = it }
                    }

                    "direction" -> directionTempo(child)?.let { tempoEvents += cursorQuarters to it }

                    "sound" -> child.getAttribute("tempo").trim().toDoubleOrNull()
                        ?.takeIf { it > 0 }?.let { tempoEvents += cursorQuarters to it }

                    "backup" -> {
                        val dur = durationQuarters(child, divisions)
                        cursorQuarters -= dur
                    }

                    "forward" -> {
                        val dur = durationQuarters(child, divisions)
                        cursorQuarters += dur
                    }

                    "note" -> {
                        val isChord = firstElement(child, "chord") != null
                        val dur = durationQuarters(child, divisions)
                        val onset = if (isChord) lastOnsetQuarters else cursorQuarters
                        val start = onset
                        val end = start + dur

                        val rest = firstElement(child, "rest") != null
                        val pitchEl = firstElement(child, "pitch")

                        if (rest) {
                            // Rests stay on the timeline to match the Python reference.
                            notes.add(ScoreNote(start, end, ScoreNote.REST, "rest"))
                        } else if (pitchEl != null) {
                            val (midi, name) = pitchToMidi(pitchEl)
                            if (isChord) {
                                // Chord member shares the previous onset; keep the highest pitch.
                                val prev = notes.lastOrNull()
                                if (prev != null && !prev.isRest && midi > prev.midi) {
                                    notes[notes.lastIndex] =
                                        ScoreNote(prev.start, prev.end, midi, name)
                                } else if (prev == null || prev.isRest) {
                                    notes.add(ScoreNote(start, end, midi, name))
                                }
                            } else {
                                notes.add(ScoreNote(start, end, midi, name))
                            }
                        }

                        if (!isChord) {
                            lastOnsetQuarters = cursorQuarters
                            cursorQuarters += dur
                        }
                    }
                }
                if (cursorQuarters > measureEnd) measureEnd = cursorQuarters
            }
            if (firstTimeSig == null) firstTimeSig = timeSig
            beatsQ += measureBeats(measureStart, measureEnd - measureStart, timeSig, measureNo, pickup = mIndex == 0)
        }

        // Tempo map: the initial tempo from time 0, then every later change in the
        // part. An override pins the whole score to one tempo.
        val segments = mutableListOf(0.0 to bpm)
        if (bpmOverride == null) {
            for ((q, t) in tempoEvents.sortedBy { it.first }) {
                if (q <= EPS) continue // the opening tempo is already `bpm`
                if (kotlin.math.abs(segments.last().first - q) <= EPS) segments[segments.lastIndex] = q to t
                else if (segments.last().second != t) segments += q to t
            }
        }
        fun seconds(q: Double): Double {
            var sec = 0.0
            for (i in segments.indices) {
                val (segStart, segBpm) = segments[i]
                val segEnd = segments.getOrNull(i + 1)?.first
                if (segEnd == null || q <= segEnd) return sec + (q - segStart) * 60.0 / segBpm
                sec += (segEnd - segStart) * 60.0 / segBpm
            }
            return sec
        }

        return LoadedScore(
            notes = notes.map { it.copy(start = seconds(it.start), end = seconds(it.end)) },
            bpm = bpm,
            beats = beatsQ.map { (q, m, b) -> Beat(seconds(q), m, b) },
            tempos = segments.map { (q, t) -> TempoMark(seconds(q), t) },
            timeSignature = firstTimeSig ?: TimeSignature(4, 4),
            measures = measuresQ.map { (q, m) -> MeasureStart(seconds(q), m) },
        )
    }

    private const val EPS = 1e-6

    /**
     * Beats of one measure (quarter positions). A full measure gets beats 1..n from
     * its start. A short first measure is a pickup (anacrusis): its beats are
     * right-aligned so they carry the last beat numbers of the bar.
     */
    private fun measureBeats(
        start: Double, length: Double, ts: TimeSignature, measureNo: Int, pickup: Boolean,
    ): List<Triple<Double, Int, Int>> {
        val bq = ts.beatQuarters
        val nominal = ts.beats * bq
        val len = if (length <= EPS) nominal else length
        val out = ArrayList<Triple<Double, Int, Int>>()
        if (pickup && len < nominal - EPS) {
            val count = kotlin.math.ceil(len / bq - EPS).toInt()
            for (j in 0 until count) {
                val q = start + len - (count - j) * bq
                if (q >= start - EPS) out += Triple(q, measureNo, ts.beats - count + j + 1)
            }
        } else {
            var k = 0
            while (k * bq < len - EPS) {
                out += Triple(start + k * bq, measureNo, (k % ts.beats) + 1)
                k++
            }
        }
        return out
    }

    /** `<time><beats>3</beats><beat-type>4</beat-type></time>`; "3+2" beats are summed. */
    private fun parseTime(time: Element): TimeSignature? {
        val beats = firstElement(time, "beats")?.textContent?.trim()
            ?.split('+')?.mapNotNull { it.trim().toIntOrNull() }?.sum() ?: return null
        val type = firstElement(time, "beat-type")?.textContent?.trim()?.toIntOrNull() ?: return null
        return if (beats > 0 && type > 0) TimeSignature(beats, type) else null
    }

    /** Tempo set by a `<direction>`: its `<sound tempo>`, else its `<metronome>`. */
    private fun directionTempo(direction: Element): Double? {
        firstElement(direction, "sound")?.getAttribute("tempo")?.trim()?.toDoubleOrNull()
            ?.takeIf { it > 0 }?.let { return it }
        for (dt in childElements(direction, "direction-type")) {
            val m = firstElement(dt, "metronome") ?: continue
            return metronomeBpm(m)
        }
        return null
    }

    /** `<metronome>` (beat-unit, optional dot, per-minute) in quarter-note BPM. */
    private fun metronomeBpm(m: Element): Double? {
        val perMinute = firstElement(m, "per-minute")?.textContent?.trim()?.toDoubleOrNull()
            ?.takeIf { it > 0 } ?: return null
        val beatUnit = firstElement(m, "beat-unit")?.textContent?.trim()
        var unitQuarters = BEAT_UNIT_QUARTERS[beatUnit] ?: 1.0
        if (firstElement(m, "beat-unit-dot") != null) unitQuarters *= 1.5
        return perMinute * unitQuarters
    }

    // --- ZIP handling --------------------------------------------------------

    /**
     * If [bytes] is an `.mxl` ZIP (starts with "PK"), return the main score XML
     * bytes; otherwise return [bytes] unchanged.
     */
    private fun unwrapIfZip(bytes: ByteArray): ByteArray {
        if (bytes.size < 2 || bytes[0] != 'P'.code.toByte() || bytes[1] != 'K'.code.toByte()) {
            return bytes
        }

        // Read all entries into memory; mxl archives are small.
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) entries[entry.name] = zis.readBytes()
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        // Prefer the rootfile declared in META-INF/container.xml.
        entries["META-INF/container.xml"]?.let { container ->
            runCatching {
                val cdoc = parseXml(container)
                val rootfiles = cdoc.getElementsByTagName("rootfile")
                if (rootfiles.length > 0) {
                    val fullPath = (rootfiles.item(0) as Element).getAttribute("full-path")
                    if (fullPath.isNotEmpty()) entries[fullPath]?.let { return it }
                }
            }
        }

        // Fall back to the first non-META-INF .xml/.musicxml entry.
        val fallback = entries.entries.firstOrNull { (name, _) ->
            !name.startsWith("META-INF/") &&
                (name.lowercase().endsWith(".xml") || name.lowercase().endsWith(".musicxml"))
        }?.value
        return fallback
            ?: throw IllegalArgumentException("No score XML found inside compressed MusicXML")
    }

    // --- Tempo ---------------------------------------------------------------

    /** First `<sound tempo>` or `<metronome>` in document order, or null. */
    private fun detectTempo(doc: Document): Double? {
        // <sound tempo="..">
        val sounds = doc.getElementsByTagName("sound")
        for (i in 0 until sounds.length) {
            val t = (sounds.item(i) as Element).getAttribute("tempo")
            t.trim().toDoubleOrNull()?.let { if (it > 0) return it }
        }
        // <metronome><beat-unit>..</beat-unit><per-minute>..</per-minute></metronome>
        val metros = doc.getElementsByTagName("metronome")
        for (i in 0 until metros.length) {
            // per-minute counts beat-units; convert to quarter-note BPM.
            metronomeBpm(metros.item(i) as Element)?.let { return it }
        }
        return null
    }

    // --- Pitch / duration ----------------------------------------------------

    /**
     * Convert a `<pitch>` element to a (midi, name) pair.
     *
     * `midi = (octave + 1) * 12 + stepSemitone[step] + alter`. The display name is
     * `"$step$octave"`, with `#` appended for sharps and `b` for flats.
     */
    private fun pitchToMidi(pitch: Element): Pair<Int, String> {
        val step = firstElement(pitch, "step")?.textContent?.trim()?.uppercase() ?: "C"
        val octave = firstElement(pitch, "octave")?.textContent?.trim()?.toIntOrNull() ?: 4
        val alter = firstElement(pitch, "alter")?.textContent?.trim()?.toDoubleOrNull()?.toInt() ?: 0
        val semitone = STEP_SEMITONE[step] ?: 0
        val midi = (octave + 1) * 12 + semitone + alter
        val accidental = when {
            alter > 0 -> "#".repeat(alter)
            alter < 0 -> "b".repeat(-alter)
            else -> ""
        }
        return midi to "$step$accidental$octave"
    }

    /** `<duration>` of an element divided by [divisions] gives the quarter-length. */
    private fun durationQuarters(el: Element, divisions: Double): Double {
        val dur = firstElement(el, "duration")?.textContent?.trim()?.toDoubleOrNull() ?: 0.0
        return if (divisions > 0) dur / divisions else 0.0
    }

    // --- XML helpers ---------------------------------------------------------

    private fun parseXml(bytes: ByteArray): Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            // MusicXML references an external DTD; never fetch it.
            runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
            runCatching { setFeature("http://xml.org/sax/features/validation", false) }
        }
        val builder = factory.newDocumentBuilder().apply {
            // Swallow DTD lookups so parsing works fully offline.
            setEntityResolver { _, _ -> org.xml.sax.InputSource(ByteArrayInputStream(ByteArray(0))) }
        }
        return builder.parse(ByteArrayInputStream(bytes)).also { it.documentElement.normalize() }
    }

    /** First direct-child [Element] of [parent] named [tag], or null. */
    private fun firstElement(parent: Element, tag: String): Element? {
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val n = children.item(i)
            if (n.nodeType == Node.ELEMENT_NODE && (n as Element).tagName == tag) return n
        }
        return null
    }

    /** Direct-child elements of [parent] (optionally filtered by [tag]). */
    private fun childElements(parent: Element, tag: String? = null): List<Element> {
        val out = ArrayList<Element>()
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val n = children.item(i)
            if (n.nodeType == Node.ELEMENT_NODE) {
                val e = n as Element
                if (tag == null || e.tagName == tag) out.add(e)
            }
        }
        return out
    }
}
