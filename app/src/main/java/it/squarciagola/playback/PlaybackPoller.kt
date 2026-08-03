package it.squarciagola.playback

import android.os.SystemClock
import it.squarciagola.auth.SpotifyAuth
import it.squarciagola.model.PlaybackState
import it.squarciagola.model.TrackMeta
import it.squarciagola.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Interroga periodicamente lo stato di riproduzione dell'account Spotify.
 *
 * L'intervallo è volutamente largo: la posizione tra un poll e l'altro la ricostruisce
 * [PositionClock], quindi interrogare più spesso non migliora la sincronia e avvicina
 * soltanto il rate limit.
 */
class PlaybackPoller(private val auth: SpotifyAuth) {

    private val _state = MutableStateFlow(PlaybackState.IDLE)
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    /** Messaggio da mostrare al posto del testo, null quando va tutto bene. */
    private val _problem = MutableStateFlow<String?>(null)
    val problem: StateFlow<String?> = _problem.asStateFlow()

    suspend fun run() {
        while (currentCoroutineContext().isActive) {
            pollOnce()
            delay(POLL_INTERVAL_MS)
        }
    }

    private suspend fun pollOnce() = withContext(Dispatchers.IO) {
        if (auth.clientId.isEmpty()) {
            _problem.value = "Configura il Client ID dal telefono"
            return@withContext
        }
        val token = auth.accessToken()
        if (token == null) {
            _problem.value = if (auth.isLoggedIn) "Sessione scaduta, accedi dal telefono"
            else "Accedi a Spotify dal telefono"
            _state.value = PlaybackState.IDLE
            return@withContext
        }

        val requestStart = SystemClock.elapsedRealtime()
        val body = Http.get(PLAYER_URL, mapOf("Authorization" to "Bearer $token"))
        val roundTrip = SystemClock.elapsedRealtime() - requestStart
        if (body == null) {
            // Può essere assenza di rete oppure un errore temporaneo: si tiene l'ultimo stato
            // valido e si riprova al giro dopo, senza azzerare il testo già a schermo.
            _problem.value = "Nessuna connessione"
            return@withContext
        }
        if (body.isBlank()) { // 204: nessuna riproduzione attiva
            _problem.value = "Nessun brano in riproduzione"
            _state.value = PlaybackState.IDLE
            return@withContext
        }

        val parsed = parse(body, roundTrip)
        if (parsed == null) {
            _problem.value = "Nessun brano in riproduzione"
            _state.value = PlaybackState.IDLE
        } else {
            _problem.value = null
            _state.value = parsed
        }
    }

    private fun parse(body: String, roundTripMs: Long): PlaybackState? {
        val json = JSONObject(body)
        val item = json.optJSONObject("item") ?: return null
        val id = item.optString("id").takeIf { it.isNotEmpty() } ?: return null
        val artists = item.optJSONArray("artists")
        val artist = (0 until (artists?.length() ?: 0))
            .mapNotNull { artists?.optJSONObject(it)?.optString("name") }
            .filter { it.isNotEmpty() }
            .joinToString(", ")
        return PlaybackState(
            track = TrackMeta(
                id = id,
                title = item.optString("name"),
                artist = artist,
                album = item.optJSONObject("album")?.optString("name").orEmpty(),
                durationMs = item.optLong("duration_ms"),
                artworkUrl = mediumArtwork(item.optJSONObject("album")),
            ),
            progressMs = json.optLong("progress_ms"),
            isPlaying = json.optBoolean("is_playing"),
            // Il valore letto descrive un istante già passato: il server ha risposto a metà
            // del viaggio, quindi l'istante di campionamento vero è mezzo round-trip fa.
            // Questa parte della sincronia si corregge da sola, senza tarature.
            sampledAtElapsedRealtime = SystemClock.elapsedRealtime() - roundTripMs / 2,
        )
    }

    /**
     * Spotify elenca le copertine dalla piu' grande alla piu' piccola. Si prende quella di
     * mezzo, intorno ai 300 pixel: la piu' piccola e' da 64 e sfocata diventa una macchia
     * squadrata, la piu' grande e' traffico sprecato per un'immagine che finisce sfocata.
     */
    private fun mediumArtwork(album: JSONObject?): String {
        val images = album?.optJSONArray("images") ?: return ""
        if (images.length() == 0) return ""
        val indice = if (images.length() >= 3) 1 else 0
        return images.optJSONObject(indice)?.optString("url").orEmpty()
    }

    private companion object {
        const val PLAYER_URL = "https://api.spotify.com/v1/me/player"
        const val POLL_INTERVAL_MS = 4_000L
    }
}
