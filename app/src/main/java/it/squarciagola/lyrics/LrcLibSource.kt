package it.squarciagola.lyrics

import it.squarciagola.model.Lyrics
import it.squarciagola.model.TrackMeta
import it.squarciagola.net.Http
import org.json.JSONObject

/**
 * Testi da lrclib.net: pubblico, senza autenticazione, sincronizzato per riga.
 *
 * La durata e' parte della chiave di ricerca e serve a distinguere versioni diverse dello
 * stesso brano (singolo, album, remaster), quindi va passata sempre.
 */
class LrcLibSource {

    val name = "LRCLIB"

    /** Bloccante: va invocata su Dispatchers.IO. Null se il brano non e' in catalogo. */
    fun fetch(track: TrackMeta): Lyrics? {
        val query = Http.encodeForm(
            mapOf(
                "artist_name" to track.artist,
                "track_name" to track.title,
                "album_name" to track.album,
                "duration" to (track.durationMs / 1000).toString(),
            )
        )
        val body = Http.get("$BASE?$query") ?: return searchFallback(track)
        return toLyrics(JSONObject(body)) ?: searchFallback(track)
    }

    /**
     * La ricerca esatta fallisce quando i metadati di Spotify e quelli di LRCLIB divergono,
     * cosa frequente su remaster e riedizioni. Si riprova senza album ne' durata prendendo
     * il primo risultato con testo sincronizzato.
     */
    private fun searchFallback(track: TrackMeta): Lyrics? {
        val query = Http.encodeForm(
            mapOf("artist_name" to track.artist, "track_name" to track.title)
        )
        val body = Http.get("$SEARCH?$query") ?: return null
        val results = org.json.JSONArray(body)
        for (i in 0 until results.length()) {
            val candidate = results.optJSONObject(i) ?: continue
            toLyrics(candidate)?.let { if (it is Lyrics.Synced) return it }
        }
        return null
    }

    private fun toLyrics(json: JSONObject): Lyrics? {
        val synced = json.optString("syncedLyrics")
        if (synced.isNotEmpty()) {
            val lines = LrcParser.parse(synced)
            if (lines.isNotEmpty()) return Lyrics.Synced(lines, name)
        }
        val plain = json.optString("plainLyrics")
        return if (plain.isNotEmpty()) Lyrics.Plain(plain, name) else null
    }

    private companion object {
        const val BASE = "https://lrclib.net/api/get"
        const val SEARCH = "https://lrclib.net/api/search"
    }
}
