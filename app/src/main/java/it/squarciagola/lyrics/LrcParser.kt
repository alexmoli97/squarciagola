package it.squarciagola.lyrics

import it.squarciagola.model.LyricLine

/**
 * Parser del formato LRC.
 *
 * Regge i casi che si incontrano davvero: piu' timestamp sulla stessa riga (ritornelli),
 * centesimi o millesimi di secondo, tag di metadata da ignorare, righe vuote che nel
 * formato indicano le pause strumentali e vanno conservate.
 */
object LrcParser {

    private val TIMESTAMP = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

    fun parse(raw: String): List<LyricLine> {
        val out = ArrayList<LyricLine>()
        for (line in raw.lineSequence()) {
            val tags = TIMESTAMP.findAll(line).toList()
            if (tags.isEmpty()) continue // tag di metadata tipo [ar:...] oppure riga libera
            val text = line.substring(tags.last().range.last + 1).trim()
            for (tag in tags) {
                val minutes = tag.groupValues[1].toLongOrNull() ?: continue
                val seconds = tag.groupValues[2].toLongOrNull() ?: continue
                if (seconds > 59) continue
                out.add(LyricLine(minutes * 60_000 + seconds * 1000 + fraction(tag.groupValues[3]), text))
            }
        }
        out.sortBy { it.timeMs }
        return out
    }

    /** "5" vale 500 ms, "05" vale 50 ms, "005" vale 5 ms. */
    private fun fraction(raw: String): Long = when (raw.length) {
        0 -> 0L
        1 -> raw.toLong() * 100
        2 -> raw.toLong() * 10
        else -> raw.take(3).toLong()
    }

    /**
     * Indice della riga attiva alla posizione data, -1 se il brano non ha ancora raggiunto
     * la prima riga. Ricerca binaria: viene invocata a ogni fotogramma.
     */
    fun activeIndex(lines: List<LyricLine>, positionMs: Long): Int {
        var low = 0
        var high = lines.size - 1
        var found = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (lines[mid].timeMs <= positionMs) {
                found = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return found
    }
}
