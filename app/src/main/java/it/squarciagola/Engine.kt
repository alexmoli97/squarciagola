package it.squarciagola

import android.content.Context
import android.os.SystemClock
import it.squarciagola.auth.SpotifyAuth
import it.squarciagola.lyrics.LrcLibSource
import it.squarciagola.lyrics.LyricsRepository
import it.squarciagola.model.KaraokeFrame
import it.squarciagola.model.Lyrics
import it.squarciagola.model.PlaybackState
import it.squarciagola.playback.AudioOutput
import it.squarciagola.playback.PlaybackPoller
import it.squarciagola.playback.PositionClock
import it.squarciagola.render.AlbumArt
import it.squarciagola.ui.Accento
import it.squarciagola.widget.WidgetSquarciagola
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
 * è quello del processo. Un framework di injection qui sarebbe impalcatura a vuoto.
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

    private val _artwork = MutableStateFlow<android.graphics.Bitmap?>(null)

    /** La copertina sfocata, usata come sfondo sia dal karaoke sia dalla schermata. */
    val artwork: StateFlow<android.graphics.Bitmap?> = _artwork.asStateFlow()

    /** La copertina a fuoco, mostrata in home. */
    private val _copertina = MutableStateFlow<android.graphics.Bitmap?>(null)
    val copertina: StateFlow<android.graphics.Bitmap?> = _copertina.asStateFlow()

    /** Colore dettato dalla copertina del brano, o il menta di sempre quando non c'e'. */
    private val _accento = MutableStateFlow(Accento.PREDEFINITO)
    val accento: StateFlow<Int> = _accento.asStateFlow()

    /**
     * Stato unificato: lo alimenta App Remote quando c'e' l'app Spotify sul dispositivo,
     * altrimenti il polling della Web API. Chi disegna non sa quale delle due stia lavorando.
     */
    private val _playback = MutableStateFlow(PlaybackState.IDLE)
    val playback: StateFlow<PlaybackState> = _playback.asStateFlow()

    private val _problem = MutableStateFlow<String?>(null)
    val problem: StateFlow<String?> = _problem.asStateFlow()

    /** Da dove arriva lo stato di riproduzione, per la diagnostica in schermata. */
    private val _origine = MutableStateFlow("")
    val origine: StateFlow<String> = _origine.asStateFlow()

    /**
     * Compensazione del ritardo audio, tenuta separata per dispositivo di uscita.
     *
     * La latenza di rete si corregge da sola in [PlaybackPoller]. Questa copre il ritardo
     * dell'impianto, che nessuna API espone: si tara una volta per auto o per cuffie e poi
     * torna da sola al collegamento successivo.
     */
    var offsetMs: Long
        get() = prefs().getLong(offsetKey(), 0L)
        set(value) {
            prefs().edit().putLong(offsetKey(), value.coerceIn(-5_000, 5_000)).apply()
        }

    /**
     * Nome dell'uscita audio attiva.
     *
     * ponytail: valore tenuto in cache per un paio di secondi. Viene letto a ogni fotogramma
     * tramite l'offset, e interrogare AudioManager trenta volte al secondo per un dato che
     * cambia quando colleghi il Bluetooth sarebbe spreco puro.
     */
    val outputName: String
        get() {
            val now = SystemClock.elapsedRealtime()
            if (now - outputCheckedAt > OUTPUT_CACHE_MS) {
                cachedOutputName = AudioOutput.currentName(appContext)
                outputCheckedAt = now
            }
            return cachedOutputName
        }

    private var cachedOutputName = AudioOutput.DEFAULT
    private var outputCheckedAt = 0L

    /** True quando l'app e' gia' viva: il widget lo usa per non inizializzare nulla da solo. */
    val pronto: Boolean get() = ::appContext.isInitialized

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        auth = SpotifyAuth(appContext)
        poller = PlaybackPoller(auth)
        repository = LyricsRepository(appContext.filesDir, LrcLibSource())
    }

    /**
     * Avvia l'ascolto. Si prova prima App Remote: dove c'e' l'app Spotify, la posizione arriva
     * spinta e senza rete di mezzo. Dove non c'e', come sui televisori, si ripiega sul polling
     * della Web API senza che l'utente debba scegliere niente.
     *
     * Il contesto conta: al primo collegamento l'app Spotify deve poter mostrare la richiesta
     * di autorizzazione, e per farlo serve un'Activity. Dal servizio si passa comunque, e in
     * quel caso funziona solo se l'autorizzazione e' gia' stata concessa.
     */
    /**
     * Avvia l'ascolto. Una sola sorgente: il polling della Web API, che funziona ovunque
     * ci sia rete, telefono, auto e televisore compresi.
     */
    fun start(context: Context = appContext) {
        if (running?.isActive == true) return
        _origine.value = "Web API"
        android.util.Log.i(TAG, "Ascolto avviato sulla Web API di Spotify")
        running = scope.launch {
            launch { poller.run() }
            launch { poller.state.collect { _playback.value = it } }
            launch { poller.problem.collect { _problem.value = it } }
            launch {
                poller.state
                    .map { it.track?.id }
                    .distinctUntilChanged()
                    .collect { loadLyricsForCurrentTrack(caricaCopertina = true) }
            }
        }
    }

    fun stop() {
        running?.cancel()
        running = null
        _origine.value = ""
    }

    /** Fotogramma corrente, pronto per il renderer. */
    fun currentFrame(): KaraokeFrame {
        val state = playback.value
        val track = state.track
        val lyrics = _lyrics.value
        return KaraokeFrame(
            title = track?.title.orEmpty(),
            artist = track?.artist.orEmpty(),
            lyrics = lyrics,
            positionMs = PositionClock.positionMs(state, SystemClock.elapsedRealtime(), offsetMs),
            durationMs = track?.durationMs ?: 0L,
            isPlaying = state.isPlaying,
            artwork = _artwork.value,
            accent = _accento.value,
            source = when (lyrics) {
                is Lyrics.Synced -> lyrics.source
                is Lyrics.Plain -> "${lyrics.source}, non sincronizzato"
                else -> ""
            },
            message = problem.value,
        )
    }

    fun clearLyricsCache() {
        scope.launch(Dispatchers.IO) {
            repository.clearCache()
            loadLyricsForCurrentTrack()
        }
    }

    /**
     * @param caricaCopertina falso quando la copertina arriva gia' da App Remote, che la
     * consegna come Bitmap senza bisogno di scaricarla.
     */
    private suspend fun loadLyricsForCurrentTrack(caricaCopertina: Boolean = true) {
        val track = playback.value.track
        if (track == null) {
            _lyrics.value = Lyrics.None
            if (caricaCopertina) {
                _artwork.value = null
                _copertina.value = null
                _accento.value = Accento.PREDEFINITO
            }
            return
        }
        _lyrics.value = Lyrics.Loading
        // Il widget deve dire cosa suona adesso: il periodo minimo che il sistema concede da
        // solo e' mezz'ora, quindi lo si sveglia al cambio di brano.
        WidgetSquarciagola.aggiorna(appContext)
        // La copertina non blocca il testo: arriva quando arriva, e finche' manca lo sfondo
        // resta quello scuro di sempre.
        if (caricaCopertina) {
            scope.launch(Dispatchers.IO) {
                val sfondo = track.artworkUrl.takeIf { it.isNotEmpty() }?.let { AlbumArt.load(it) }
                _artwork.value = sfondo?.immagine
                _copertina.value = sfondo?.nitida
                _accento.value = sfondo?.accento ?: Accento.PREDEFINITO
                WidgetSquarciagola.aggiorna(appContext)
            }
        }
        _lyrics.value = withContext(Dispatchers.IO) { repository.load(track) }
    }


    private fun offsetKey() = "$KEY_OFFSET_PREFIX$outputName"

    private fun prefs() = appContext.getSharedPreferences("squarciagola", Context.MODE_PRIVATE)

    private const val TAG = "Squarciagola"
    private const val KEY_OFFSET_PREFIX = "offset_ms_"
    private const val OUTPUT_CACHE_MS = 2_000L
}
