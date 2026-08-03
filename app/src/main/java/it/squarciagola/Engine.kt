package it.squarciagola

import android.content.Context
import android.os.SystemClock
import it.squarciagola.auth.SpotifyAuth
import it.squarciagola.lyrics.LrcLibSource
import it.squarciagola.lyrics.LyricsRepository
import it.squarciagola.lyrics.LyricsSource
import it.squarciagola.lyrics.SpotifyLyricsSource
import it.squarciagola.model.KaraokeFrame
import it.squarciagola.model.Lyrics
import it.squarciagola.model.PlaybackState
import it.squarciagola.playback.PlaybackPoller
import it.squarciagola.playback.PositionClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Stato condiviso tra la schermata del telefono e quella dell'auto.
 *
 * ponytail: un singleton invece di un contenitore di dipendenze. I consumatori sono due,
 * vivono nello stesso processo e nessuno dei due possiede l'altro; il ciclo di vita utile
 * e' quello del processo. Un framework di injection qui sarebbe impalcatura a vuoto.
 *
 * La posizione nel brano non viene pubblicata su un flusso: la calcolano i disegnatori
 * chiamando [currentFrame] a ogni fotogramma. Emettere trenta volte al secondo su uno
 * StateFlow farebbe ricomporre la UI del telefono per nulla.
 */
object Engine {

    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var running: Job? = null

    lateinit var auth: SpotifyAuth
        private set
    private lateinit var repository: LyricsRepository
    private lateinit var poller: PlaybackPoller

    private val _lyrics = MutableStateFlow<Lyrics>(Lyrics.None)
    val lyrics: StateFlow<Lyrics> = _lyrics.asStateFlow()

    val playback: StateFlow<PlaybackState> get() = poller.state
    val problem: StateFlow<String?> get() = poller.problem

    /** Compensazione manuale del ritardo audio, in millisecondi. */
    var offsetMs: Long
        get() = prefs().getLong(KEY_OFFSET, 0L)
        set(value) {
            prefs().edit().putLong(KEY_OFFSET, value.coerceIn(-5_000, 5_000)).apply()
        }

    /** Se false, la sorgente Spotify viene saltata e si usa solo LRCLIB. */
    var useSpotifyLyrics: Boolean
        get() = prefs().getBoolean(KEY_USE_SPOTIFY, false)
        set(value) {
            prefs().edit().putBoolean(KEY_USE_SPOTIFY, value).apply()
            rebuildRepository()
        }

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        auth = SpotifyAuth(appContext)
        poller = PlaybackPoller(auth)
        rebuildRepository()
    }

    fun start() {
        if (running?.isActive == true) return
        running = scope.launch {
            launch { poller.run() }
            launch {
                poller.state
                    .map { it.track?.id }
                    .distinctUntilChanged()
                    .collect { loadLyricsForCurrentTrack() }
            }
        }
    }

    fun stop() {
        running?.cancel()
        running = null
    }

    /** Fotogramma corrente, pronto per il renderer. */
    fun currentFrame(): KaraokeFrame {
        val state = playback.value
        val track = state.track
        return KaraokeFrame(
            title = track?.title.orEmpty(),
            artist = track?.artist.orEmpty(),
            lyrics = _lyrics.value,
            positionMs = PositionClock.positionMs(state, SystemClock.elapsedRealtime(), offsetMs),
            durationMs = track?.durationMs ?: 0L,
            isPlaying = state.isPlaying,
            message = problem.value,
        )
    }

    fun clearLyricsCache() {
        scope.launch(Dispatchers.IO) {
            repository.clearCache()
            loadLyricsForCurrentTrack()
        }
    }

    private suspend fun loadLyricsForCurrentTrack() {
        val track = playback.value.track
        if (track == null) {
            _lyrics.value = Lyrics.None
            return
        }
        _lyrics.value = Lyrics.Loading
        _lyrics.value = withContext(Dispatchers.IO) { repository.load(track) }
    }

    private fun rebuildRepository() {
        val sources = buildList<LyricsSource> {
            if (useSpotifyLyrics) add(SpotifyLyricsSource(auth))
            add(LrcLibSource())
        }
        repository = LyricsRepository(appContext.filesDir, sources)
    }

    private fun prefs() = appContext.getSharedPreferences("squarciagola", Context.MODE_PRIVATE)

    private const val KEY_OFFSET = "offset_ms"
    private const val KEY_USE_SPOTIFY = "use_spotify_lyrics"
}
