package it.squarciagola

import android.content.Context
import android.os.SystemClock
import it.squarciagola.auth.SpotifyAuth
import it.squarciagola.lyrics.LrcLibSource
import it.squarciagola.lyrics.LyricsRepository
import it.squarciagola.model.KaraokeFrame
import it.squarciagola.model.Lyrics
import it.squarciagola.model.PlaybackState
import it.squarciagola.playback.AppRemoteSource
import it.squarciagola.playback.AudioOutput
import it.squarciagola.playback.PlaybackPoller
import it.squarciagola.playback.PositionClock
import it.squarciagola.render.AlbumArt
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
    private lateinit var appRemote: AppRemoteSource

    private val _lyrics = MutableStateFlow<Lyrics>(Lyrics.None)
    val lyrics: StateFlow<Lyrics> = _lyrics.asStateFlow()

    private val _artwork = MutableStateFlow<android.graphics.Bitmap?>(null)

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

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        auth = SpotifyAuth(appContext)
        poller = PlaybackPoller(auth)
        // Il Client ID si legge quando serve, non alla partenza: puo' essere incollato
        // nell'app dopo l'avvio, e catturarlo qui lo bloccherebbe al valore vuoto.
        appRemote = AppRemoteSource({ auth.clientId }, scope)
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
    fun start(context: Context = appContext) {
        if (avviando || appRemote.connected.value) return
        avviando = true
        // Si ritenta App Remote a ogni ritorno in primo piano, anche se la Web API sta gia'
        // lavorando: e' quello che permette di passare alla sorgente migliore appena diventa
        // disponibile, per esempio dopo aver concesso l'autorizzazione o riaperto Spotify.
        // Senza questo, chi parte una volta sulla Web API ci resta per sempre.
        appRemote.connect(context) { riuscito ->
            avviando = false
            when {
                riuscito -> avviaConAppRemote()
                running?.isActive != true -> avviaConWebApi()
            }
        }

        // Rete di sicurezza: se la connessione non risponde ne' si' ne' no, si parte comunque
        // con la Web API. Restare in attesa significherebbe nessuna sorgente attiva e uno
        // schermo fermo senza spiegazione, che e' peggio di una sincronia meno precisa.
        scope.launch {
            delay(ATTESA_APP_REMOTE_MS)
            if (avviando && running?.isActive != true) {
                android.util.Log.i(TAG, "App Remote non risponde entro l'attesa, passo alla Web API")
                avviando = false
                avviaConWebApi()
            }
        }
    }

    private var avviando = false

    private fun avviaConAppRemote() {
        running?.cancel()
        _origine.value = "App Remote"
        _problem.value = null
        android.util.Log.i(TAG, "Sorgente: App Remote, aggiornamenti spinti dall'app Spotify")
        running = scope.launch {
            launch { appRemote.state.collect { _playback.value = it } }
            launch { appRemote.artwork.collect { _artwork.value = it } }
            launch {
                appRemote.state
                    .map { it.track?.id }
                    .distinctUntilChanged()
                    .collect { loadLyricsForCurrentTrack(caricaCopertina = false) }
            }
            // Se l'app Spotify viene chiusa la connessione cade: si torna alla Web API invece
            // di restare fermi su un ultimo stato che non si aggiorna piu'.
            launch {
                appRemote.connected.collect { collegato -> if (!collegato) avviaConWebApi() }
            }
        }
    }

    private fun avviaConWebApi() {
        running?.cancel()
        _origine.value = "Web API"
        android.util.Log.i(TAG, "Sorgente: Web API, App Remote non disponibile")
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
        appRemote.disconnect()
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
            if (caricaCopertina) _artwork.value = null
            return
        }
        _lyrics.value = Lyrics.Loading
        // La copertina non blocca il testo: arriva quando arriva, e finche' manca lo sfondo
        // resta quello scuro di sempre.
        if (caricaCopertina) {
            scope.launch(Dispatchers.IO) {
                _artwork.value = track.artworkUrl.takeIf { it.isNotEmpty() }?.let { AlbumArt.load(it) }
            }
        }
        _lyrics.value = withContext(Dispatchers.IO) { repository.load(track) }
    }


    private fun offsetKey() = "$KEY_OFFSET_PREFIX$outputName"

    private fun prefs() = appContext.getSharedPreferences("squarciagola", Context.MODE_PRIVATE)

    private const val TAG = "Squarciagola"
    private const val ATTESA_APP_REMOTE_MS = 6_000L
    private const val KEY_OFFSET_PREFIX = "offset_ms_"
    private const val OUTPUT_CACHE_MS = 2_000L
}
