package it.squarciagola.model

/** Metadati della traccia in riproduzione, come li restituisce la Web API di Spotify. */
data class TrackMeta(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
)

/**
 * Istantanea dello stato di riproduzione.
 *
 * [sampledAtElapsedRealtime] e' il valore di SystemClock.elapsedRealtime() nel momento in cui
 * [progressMs] e' stato campionato. Serve a [it.squarciagola.playback.PositionClock] per
 * interpolare la posizione tra un poll e l'altro.
 */
data class PlaybackState(
    val track: TrackMeta?,
    val progressMs: Long,
    val isPlaying: Boolean,
    val sampledAtElapsedRealtime: Long,
) {
    companion object {
        val IDLE = PlaybackState(null, 0L, false, 0L)
    }
}

/** Una riga di testo con il suo istante di attacco. */
data class LyricLine(val timeMs: Long, val text: String)

/** Esito della ricerca del testo per una traccia. */
sealed interface Lyrics {
    /** Testo sincronizzato riga per riga. */
    data class Synced(val lines: List<LyricLine>, val source: String) : Lyrics

    /** Testo senza timestamp: si mostra statico, senza evidenziazione. */
    data class Plain(val text: String, val source: String) : Lyrics

    /** Nessuna sorgente ha il testo. Esito messo in cache, non si ritenta. */
    data object None : Lyrics

    /** Ricerca in corso. */
    data object Loading : Lyrics
}

/**
 * Tutto cio' che serve a disegnare un fotogramma. Il renderer non conosce nient'altro:
 * ne' Android Auto, ne' Compose, ne' la rete.
 */
data class KaraokeFrame(
    val title: String = "",
    val artist: String = "",
    val lyrics: Lyrics = Lyrics.None,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val isPlaying: Boolean = false,
    /** Da dove arriva il testo mostrato. Vuoto se non c'e' testo. */
    val source: String = "",
    /** Messaggio che sostituisce il testo quando qualcosa non va (login scaduto, niente rete). */
    val message: String? = null,
)
