package it.squarciagola.lyrics

import it.squarciagola.model.Lyrics
import it.squarciagola.model.LyricLine
import it.squarciagola.model.TrackMeta
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Trova il testo di una traccia e lo conserva su disco.
 *
 * Viene messo in cache anche l'esito negativo: senza, ogni brano senza testo verrebbe
 * ricercato di nuovo a ogni riproduzione, e in auto significa attesa inutile a ogni cambio
 * di canzone.
 *
 * ponytail: file JSON in filesDir, niente Room. Sono letture per chiave singola, poche
 * centinaia di record, nessuna query. Un database qui sarebbe impianto senza uso.
 */
class LyricsRepository(
    private val cacheDir: File,
    private val source: LrcLibSource,
) {

    /** Bloccante: va invocata su Dispatchers.IO. */
    fun load(track: TrackMeta): Lyrics {
        readCache(track.id)?.let { return it }

        val found = runCatching { source.fetch(track) }.getOrNull()
        val result = found ?: Lyrics.None
        writeCache(track.id, result)
        return result
    }

    fun clearCache() {
        dir().listFiles()?.forEach { it.delete() }
    }

    // --- persistenza ----------------------------------------------------------------------

    private fun dir(): File = File(cacheDir, "lyrics").also { it.mkdirs() }

    private fun fileFor(trackId: String) = File(dir(), "$trackId.json")

    private fun readCache(trackId: String): Lyrics? {
        val file = fileFor(trackId)
        if (!file.exists()) return null
        return runCatching { deserialize(JSONObject(file.readText())) }.getOrNull()
    }

    private fun writeCache(trackId: String, lyrics: Lyrics) {
        runCatching { fileFor(trackId).writeText(serialize(lyrics).toString()) }
    }

    private fun serialize(lyrics: Lyrics): JSONObject = when (lyrics) {
        is Lyrics.Synced -> JSONObject().apply {
            put("type", "synced")
            put("source", lyrics.source)
            put("lines", JSONArray().apply {
                lyrics.lines.forEach { put(JSONObject().put("t", it.timeMs).put("x", it.text)) }
            })
        }

        is Lyrics.Plain -> JSONObject().apply {
            put("type", "plain")
            put("source", lyrics.source)
            put("text", lyrics.text)
        }

        else -> JSONObject().put("type", "none")
    }

    private fun deserialize(json: JSONObject): Lyrics = when (json.optString("type")) {
        "synced" -> {
            val array = json.getJSONArray("lines")
            val lines = ArrayList<LyricLine>(array.length())
            for (i in 0 until array.length()) {
                val line = array.getJSONObject(i)
                lines.add(LyricLine(line.getLong("t"), line.optString("x")))
            }
            Lyrics.Synced(lines, json.optString("source"))
        }

        "plain" -> Lyrics.Plain(json.optString("text"), json.optString("source"))
        else -> Lyrics.None
    }
}
